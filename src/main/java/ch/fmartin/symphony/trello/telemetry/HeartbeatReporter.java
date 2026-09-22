package ch.fmartin.symphony.trello.telemetry;

import ch.fmartin.symphony.trello.telemetry.TelemetryState.PendingHeartbeat;
import ch.fmartin.symphony.trello.telemetry.TelemetryState.ReportClaim;
import ch.fmartin.symphony.trello.telemetry.TelemetryState.RetryState;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore.StateRead;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore.Update;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/// The worker-side due check. Called once when a worker is ready and then about once per minute,
/// it decides under the state lock whether this worker should deliver today's heartbeat, releases
/// the lock, sends asynchronously, and records the outcome only if its claim and the preference
/// revision are unchanged. All timing comes from the injected clock and jitter source.
public final class HeartbeatReporter {
    static final Duration CLAIM_LIFETIME = Duration.ofSeconds(90);
    static final List<Duration> RETRY_BACKOFF =
            List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(60));
    static final Duration RETRY_JITTER_MAX = Duration.ofSeconds(30);

    private final TelemetryInstallation installation;
    private final HeartbeatSnapshots snapshots;
    private final PostHogCaptureClient client;
    private final Clock clock;
    private final Executor executor;
    private final LongSupplier jitterSeconds;
    private final TelemetryOutput output;
    private final String owner = UUID.randomUUID().toString();
    private final AtomicBoolean unreadableReported = new AtomicBoolean();
    /// Admission control: one delivery attempt per reporter at a time, so a stalled request cannot
    /// pile up further attempts behind it on the delivery executor.
    private final AtomicBoolean deliveryInFlight = new AtomicBoolean();
    private @Nullable HeartbeatProperties lastDebugPreview;

    public HeartbeatReporter(
            TelemetryInstallation installation,
            HeartbeatSnapshots snapshots,
            PostHogCaptureClient client,
            Clock clock,
            Executor executor,
            LongSupplier jitterSeconds,
            TelemetryOutput output) {
        this.installation = installation;
        this.snapshots = snapshots;
        this.client = client;
        this.clock = clock;
        this.executor = executor;
        this.jitterSeconds = jitterSeconds;
        this.output = output;
    }

    /// One local due check. It performs no network request itself; a dispatch runs on the executor.
    public synchronized CheckResult check() {
        return installation.installedStore().map(this::check).orElse(CheckResult.NOT_INSTALLED);
    }

    private CheckResult check(TelemetryStateStore store) {
        StateRead read = store.read();
        if (read.unreadable()) {
            reportUnreadable(read);
            return CheckResult.UNREADABLE;
        }
        TelemetryState current = read.stateOrInitial();
        TelemetryErasure erasure = new TelemetryErasure(installation, clock);
        boolean graceEnded = current.firstWorkerDeadlineAt()
                .filter(deadline -> !deadline.isAfter(clock.instant()))
                .isPresent();
        if (installation.networkEligible()
                && erasure.needsMaintenance(current)
                && (current.ownership() != null || graceEnded)) {
            if (deliveryInFlight.compareAndSet(false, true)) {
                try {
                    executor.execute(() -> {
                        try {
                            erasure.maintain(store);
                        } catch (TelemetryStateException exception) {
                            output.info("telemetry ownership request deferred: " + exception.getMessage());
                        } finally {
                            deliveryInFlight.set(false);
                        }
                    });
                } catch (RuntimeException exception) {
                    deliveryInFlight.set(false);
                    throw exception;
                }
            }
            return CheckResult.WAITING;
        }
        TelemetryOwnership ownership = current.ownership();
        if (ownership != null && ownership.blocksReporting()) {
            return CheckResult.WAITING;
        }
        EffectiveTelemetry effective =
                installation.effective(read.stateOrInitial().mode());
        if (effective.effective() == TelemetryMode.DEBUG) {
            printDebugPreview(read.stateOrInitial());
            return CheckResult.DEBUG;
        }
        if (effective.effective() == TelemetryMode.DISABLED) {
            return CheckResult.DISABLED;
        }
        if (!installation.distribution().ready()) {
            return CheckResult.NOT_CONFIGURED;
        }
        if (deliveryInFlight.get()) {
            return CheckResult.WAITING;
        }
        // Platform, version, and board count are read before the lock so the transaction stays short.
        HeartbeatSnapshots.Observation observation = snapshots.observe();
        Decision decision;
        try {
            decision = store.update(state -> decide(state, observation));
        } catch (TelemetryStateException exception) {
            output.info("telemetry check skipped: " + exception.getMessage());
            return CheckResult.UNREADABLE;
        }
        if (decision.result() == CheckResult.DISPATCHED) {
            Dispatch dispatch = decision.dispatch();
            deliveryInFlight.set(true);
            try {
                executor.execute(() -> deliver(store, dispatch));
            } catch (RuntimeException exception) {
                deliveryInFlight.set(false);
                throw exception;
            }
        }
        if (decision.printNotice()) {
            output.info(String.join(
                    "\n", TelemetryNotice.lines(Optional.ofNullable(decision.deadline()), clock.instant())));
        }
        return decision.result();
    }

    private Update<Decision> decide(TelemetryState stored, HeartbeatSnapshots.Observation observation) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (!installation.effective(stored.mode()).sendsReports()) {
            return Update.unchanged(Decision.of(CheckResult.DISABLED));
        }
        TelemetryState state = stored;
        boolean printNotice = false;
        if (!state.hasIdentity() && installation.distribution().erasure().isEmpty()) {
            state = state.withIdentity(UUID.randomUUID(), today);
        }
        if (state.noticeRevision() < TelemetryNotice.REVISION) {
            state = state.withNotice(TelemetryNotice.REVISION, now);
        }
        // The shared grace period starts once, at the first ready worker, and is never reset. The
        // worker that starts it prints the notice with the real deadline even when setup already
        // showed the advance disclosure; later workers and restarts print nothing.
        if (state.firstWorkerDeadlineAt().isEmpty()) {
            printNotice = true;
        }
        Instant deadline = state.firstWorkerDeadlineAt().orElseGet(() -> now.plus(TelemetryNotice.FIRST_REPORT_GRACE));
        state = state.withFirstWorkerDeadline(deadline);
        if (deadline.isAfter(now)) {
            return Update.write(state, new Decision(CheckResult.GRACE, printNotice, deadline, null));
        }
        if (!state.hasIdentity()) {
            return Update.write(state, new Decision(CheckResult.WAITING, printNotice, deadline, null));
        }
        TelemetryOwnership ownership = state.ownership();
        if (ownership != null && ownership.blocksReporting()) {
            return Update.unchanged(Decision.of(CheckResult.WAITING));
        }
        // A clock that moved backwards must not produce a second report for a day already covered:
        // anything observed on or after today counts as done or still pending.
        Optional<PendingHeartbeat> pending =
                state.pending().filter(report -> !report.observationDate().isBefore(today));
        if (state.pending().isPresent() && pending.isEmpty()) {
            // A report from an earlier UTC day is never replayed: drop it with its retry schedule so a
            // fresh observation is not delayed by the old backoff.
            state = state.withReporting(state.lastReportedDate(), null, null, null);
        }
        if (state.retrySchedule().map(retry -> retry.notBefore().isAfter(now)).orElse(false)
                || state.activeClaim(now).isPresent()) {
            return Update.write(state, new Decision(CheckResult.WAITING, printNotice, deadline, null));
        }
        if (pending.isEmpty()
                && state.lastReported().map(last -> !last.isBefore(today)).orElse(false)) {
            return Update.write(state, new Decision(CheckResult.DONE_TODAY, printNotice, deadline, null));
        }
        // Build the snapshot from the state that already carries the identity created above.
        TelemetryState registered = state;
        PendingHeartbeat report = pending.orElseGet(() -> new PendingHeartbeat(
                UUID.randomUUID(),
                now,
                today,
                registered.preferenceRevision(),
                snapshots.properties(registered, observation)));
        // The attempt token is new per claim, so a stale completion of an earlier attempt for the
        // same event cannot record over a newer attempt.
        ReportClaim claim = new ReportClaim(owner, report.eventUuid(), UUID.randomUUID(), now.plus(CLAIM_LIFETIME));
        // A fresh report starts its own bounded backoff; only a retry of the same report keeps it.
        RetryState retry = pending.isPresent() ? state.retry() : null;
        TelemetryState claimed = state.withReporting(state.lastReportedDate(), retry, report, claim);
        if (ownership != null) {
            claimed = claimed.withOwnership(ownership.markDispatched(claim.expiresAt()));
        }
        Dispatch dispatch = new Dispatch(claimed, report, claim);
        return Update.write(claimed, new Decision(CheckResult.DISPATCHED, printNotice, deadline, dispatch));
    }

    private void deliver(TelemetryStateStore store, Dispatch dispatch) {
        try {
            dispatch(store, dispatch);
        } finally {
            deliveryInFlight.set(false);
        }
    }

    private void dispatch(TelemetryStateStore store, Dispatch dispatch) {
        // Permission is rechecked right before any bytes leave: a disable that landed between the
        // claim and this point must win.
        if (!canStartRequest(store, dispatch)) {
            return;
        }
        HeartbeatEvent event = snapshots.event(
                dispatch.state(),
                dispatch.report().eventUuid(),
                dispatch.report().timestamp(),
                dispatch.report().properties());
        String body;
        try {
            body = HeartbeatJson.serialize(event.requireSendable());
        } catch (IllegalStateException exception) {
            output.info("telemetry report not sent: " + exception.getMessage());
            record(store, dispatch, CaptureOutcome.localFailure("local validation failed"));
            return;
        }
        boolean log = installation.environment().log();
        if (log) {
            output.info("telemetry request POST " + client.endpoint() + " headers="
                    + PostHogCaptureClient.requestHeaders() + "\n" + body);
        }
        // Serialization and logging can be delayed. Require the full transport budget immediately before IO.
        if (!canStartRequest(store, dispatch)) {
            return;
        }
        CaptureOutcome outcome = client.capture(body);
        if (log) {
            output.info("telemetry response " + outcome.summary()
                    + outcome.statusCode().map(status -> " status=" + status).orElse("")
                    + outcome.retryAfter()
                            .map(delay -> " retry-after=" + delay.toSeconds() + "s")
                            .orElse(""));
        }
        record(store, dispatch, outcome);
    }

    private boolean canStartRequest(TelemetryStateStore store, Dispatch dispatch) {
        StateRead latest = store.read();
        return !latest.unreadable()
                && stillOurs(latest.stateOrInitial(), dispatch)
                && dispatch.claim().expiresAt().isAfter(clock.instant().plus(client.requestTimeout()));
    }

    private void record(TelemetryStateStore store, Dispatch dispatch, CaptureOutcome outcome) {
        try {
            store.update(state -> {
                if (!stillOurs(state, dispatch)) {
                    return Update.unchanged(null);
                }
                return Update.write(recorded(state, dispatch.report(), outcome), null);
            });
        } catch (TelemetryStateException exception) {
            output.info("telemetry outcome not recorded: " + exception.getMessage());
        }
    }

    private TelemetryState recorded(TelemetryState state, PendingHeartbeat report, CaptureOutcome outcome) {
        Instant now = clock.instant();
        int attempts = state.retrySchedule().map(RetryState::attempts).orElse(0) + 1;
        return switch (outcome.kind()) {
            case ACCEPTED -> state.withReporting(report.observationDate(), null, null, null);
            // A quota drop is not an accepted report: keep the last real success, do not retry today
            // (the next attempt would be dropped too), and leave the reason visible in status.
            case QUOTA_LIMITED ->
                state.withReporting(
                        state.lastReportedDate(),
                        new RetryState(attempts, nextUtcDay(report.observationDate()), outcome.summary()),
                        null,
                        null);
            case TRANSIENT ->
                state.withReporting(
                        state.lastReportedDate(),
                        new RetryState(
                                attempts, now.plus(retryDelay(attempts, outcome.retryAfter())), outcome.summary()),
                        report,
                        null);
            case PERMANENT ->
                state.withReporting(
                        state.lastReportedDate(),
                        new RetryState(attempts, nextUtcDay(report.observationDate()), outcome.summary()),
                        null,
                        null);
        };
    }

    private Duration retryDelay(int attempts, Optional<Duration> retryAfter) {
        Duration base = retryAfter.orElseGet(() -> RETRY_BACKOFF.get(Math.min(attempts, RETRY_BACKOFF.size()) - 1));
        long jitter = Math.floorMod(jitterSeconds.getAsLong(), RETRY_JITTER_MAX.toSeconds() + 1);
        return base.plusSeconds(jitter);
    }

    private boolean stillOurs(TelemetryState state, Dispatch dispatch) {
        ReportClaim claim = state.claim();
        if (claim == null) {
            return false;
        }
        TelemetryOwnership ownership = state.ownership();
        return (ownership == null || !ownership.blocksReporting())
                && installation.effective(state.mode()).sendsReports()
                && state.preferenceRevision() == dispatch.report().preferenceRevision()
                && owner.equals(claim.owner())
                && dispatch.claim().attempt().equals(claim.attempt())
                && dispatch.claim().eventUuid().equals(claim.eventUuid())
                && state.pending()
                        .map(pending ->
                                pending.eventUuid().equals(dispatch.report().eventUuid()))
                        .orElse(false);
    }

    private void printDebugPreview(TelemetryState state) {
        HeartbeatProperties properties = snapshots.properties(state);
        if (properties.equals(lastDebugPreview)) {
            return;
        }
        lastDebugPreview = properties;
        HeartbeatEvent preview = snapshots.event(state, UUID.randomUUID(), clock.instant(), properties);
        output.info("telemetry debug preview (local only, not sent):\n" + HeartbeatJson.serialize(preview));
    }

    private void reportUnreadable(StateRead read) {
        if (unreadableReported.compareAndSet(false, true)) {
            output.info("telemetry reporting is off: " + read.problem().orElse("state unreadable"));
        }
    }

    static Instant nextUtcDay(LocalDate day) {
        return day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public enum CheckResult {
        NOT_INSTALLED,
        UNREADABLE,
        DISABLED,
        DEBUG,
        NOT_CONFIGURED,
        GRACE,
        WAITING,
        DONE_TODAY,
        DISPATCHED
    }

    private record Decision(
            CheckResult result, boolean printNotice, @Nullable Instant deadline, @Nullable Dispatch dispatch) {
        static Decision of(CheckResult result) {
            return new Decision(result, false, null, null);
        }
    }

    private record Dispatch(TelemetryState state, PendingHeartbeat report, ReportClaim claim) {}
}
