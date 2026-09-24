package ch.fmartin.symphony.trello.telemetry;

import ch.fmartin.symphony.trello.telemetry.TelemetryErasureClient.Action;
import ch.fmartin.symphony.trello.telemetry.TelemetryOwnership.Erasure;
import ch.fmartin.symphony.trello.telemetry.TelemetryOwnership.Phase;
import ch.fmartin.symphony.trello.telemetry.TelemetryStateStore.Update;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/// Coordinates local durable state and the native service without holding a state lock during IO.
final class TelemetryErasure {
    // Provider deletion can take days. Waiting half the time since the request grows the interval
    // geometrically without persisting an attempt count, and the ceiling keeps status reasonably fresh.
    static final Duration RETRY_FLOOR = Duration.ofMinutes(1);
    static final Duration RETRY_CEILING = Duration.ofHours(6);
    private static final int RETRY_DIVISOR = 2;

    private final TelemetryInstallation installation;
    private final Clock clock;

    TelemetryErasure(TelemetryInstallation installation, Clock clock) {
        this.installation = installation;
        this.clock = clock;
    }

    /// Outcome of an erase request that did not fail.
    enum Request {
        /// No report ever left this installation, so there is nothing to erase.
        NOTHING_SENT,
        /// The operation is saved and the service was contacted if it was due.
        SUBMITTED
    }

    boolean needsMaintenance(TelemetryState state) {
        if (installation.distribution().erasure().isEmpty()) {
            return false;
        }
        Erasure job = state.erasure();
        if (job != null) {
            return active(job) && !job.notBefore().isAfter(clock.instant());
        }
        return !state.hasIdentity() && installation.effective(state.mode()).sendsReports();
    }

    /// Background retry by workers, bounded by the stored retry time.
    void maintain(TelemetryStateStore store) {
        run(store, this::needsMaintenance);
    }

    /// Status check a person asked for. It skips the background backoff but still waits for the
    /// drain deadline of a dispatched heartbeat.
    void refresh(TelemetryStateStore store) {
        run(store, state -> {
            Erasure job = state.erasure();
            TelemetryOwnership ownership = state.ownership();
            return job != null && ownership != null && active(job) && ownership.drained(clock.instant());
        });
    }

    private void run(TelemetryStateStore store, Predicate<TelemetryState> due) {
        installation.distribution().erasure().ifPresent(endpoint -> {
            try (var client = new TelemetryErasureClient(endpoint, installation.distribution())) {
                run(store, client, due);
            }
        });
    }

    private void run(TelemetryStateStore store, TelemetryErasureClient client, Predicate<TelemetryState> due) {
        TelemetryState state = readable(store);
        if (!due.test(state)) {
            return;
        }
        if (!state.hasIdentity()) {
            TelemetryCredential credential = client.issue();
            store.update(current -> {
                if (current.hasIdentity()
                        || current.preferenceRevision() != state.preferenceRevision()
                        || !installation.effective(current.mode()).sendsReports()) {
                    return Update.unchanged(null);
                }
                return Update.write(
                        current.withIdentity(
                                        credential.installationId(),
                                        LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC))
                                .withOwnership(TelemetryOwnership.issued(credential)),
                        null);
            });
            return;
        }
        TelemetryOwnership ownership = Objects.requireNonNull(state.ownership());
        Erasure job = Objects.requireNonNull(ownership.erasure());
        // Reserve the next attempt before IO. A failed or interrupted process cannot spin on every tick.
        Erasure attempt = job.next(job.phase(), retryAt(job));
        if (!replace(store, ownership, job, attempt)) {
            return;
        }
        client.request(
                attempt.phase() == Phase.REQUESTED ? Action.ERASE : Action.STATUS, ownership, attempt, clock.instant());
        Phase phase = client.status(ownership).orElse(attempt.phase());
        boolean saved = replace(store, ownership, attempt, attempt.next(phase, retryAt(attempt)));
        if (saved && phase == Phase.COMPLETE) {
            // Completion is already durable, so a lost acknowledgment is not retried. The operator
            // archives a status flag that stays behind.
            client.acknowledge(ownership, attempt, clock.instant());
        }
    }

    Request request(TelemetryStateStore store) {
        Claim claim = store.update(state -> {
            TelemetryOwnership owner = state.ownership();
            TelemetryState disabled = state.withMode(TelemetryMode.DISABLED);
            if (owner == null) {
                return Update.write(disabled, state.hasIdentity() ? Claim.LEGACY : Claim.NOTHING_SENT);
            }
            if (owner.erasure() != null) {
                return Update.write(disabled, Claim.OWNED);
            }
            Instant now = clock.instant();
            // Allow an already dispatched heartbeat's bounded transport to finish before looking up its profile.
            Instant notBefore = owner.drainedAt(now);
            Phase phase = owner.used() ? Phase.REQUESTED : Phase.COMPLETE;
            Erasure job = new Erasure(UUID.randomUUID(), now, notBefore, phase);
            return Update.write(disabled.withOwnership(owner.withErasure(job)), Claim.OWNED);
        });
        return switch (claim) {
            case LEGACY ->
                throw new TelemetryStateException(
                        "reporting is off; this installation has no ownership credential; use maintainer-assisted erasure");
            case NOTHING_SENT -> Request.NOTHING_SENT;
            case OWNED -> {
                maintain(store);
                yield Request.SUBMITTED;
            }
        };
    }

    private enum Claim {
        LEGACY,
        NOTHING_SENT,
        OWNED
    }

    static TelemetryState readable(TelemetryStateStore store) {
        TelemetryStateStore.StateRead read = store.read();
        if (read.unreadable()) {
            throw new TelemetryStateException("telemetry state is unreadable; reporting remains off");
        }
        return read.stateOrInitial();
    }

    private Instant retryAt(Erasure job) {
        Instant now = clock.instant();
        Duration waited = Duration.between(job.requestedAt(), now).dividedBy(RETRY_DIVISOR);
        Duration delay = waited.compareTo(RETRY_FLOOR) < 0
                ? RETRY_FLOOR
                : waited.compareTo(RETRY_CEILING) > 0 ? RETRY_CEILING : waited;
        return now.plus(delay);
    }

    private static boolean active(Erasure job) {
        return job.phase() != Phase.COMPLETE && job.phase() != Phase.REFUSED;
    }

    private static boolean replace(
            TelemetryStateStore store, TelemetryOwnership owner, Erasure expected, Erasure next) {
        return store.update(state -> {
            TelemetryOwnership current = state.ownership();
            if (current == null
                    || !current.period().equals(owner.period())
                    || !Objects.equals(current.erasure(), expected)) {
                return Update.unchanged(false);
            }
            return Update.write(state.withOwnership(current.withErasure(next)), true);
        });
    }
}
