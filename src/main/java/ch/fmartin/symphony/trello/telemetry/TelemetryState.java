package ch.fmartin.symphony.trello.telemetry;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// The local-only `telemetry.json` content. Nothing in this file is transmitted except the
/// installation ID, registration date, and counters, which the heartbeat copies deliberately.
public record TelemetryState(
        @JsonProperty("format_version") int formatVersion,
        @JsonProperty("preference_revision") long preferenceRevision,
        @JsonProperty("mode") TelemetryMode mode,
        @JsonProperty("installation_id") @Nullable UUID installationId,
        @JsonProperty("registered_on") @Nullable LocalDate registeredOn,
        @JsonProperty("notice_revision") int noticeRevision,
        @JsonProperty("notice_shown_at") @Nullable Instant noticeShownAt,
        @JsonProperty("first_worker_deadline") @Nullable Instant firstWorkerDeadline,
        @JsonProperty("board_imports_total") long boardImportsTotal,
        @JsonProperty("board_creations_total") long boardCreationsTotal,
        @JsonProperty("last_reported_date") @Nullable LocalDate lastReportedDate,
        @JsonProperty("retry") @Nullable RetryState retry,
        @JsonProperty("pending_report") @Nullable PendingHeartbeat pendingReport,
        @JsonProperty("claim") @Nullable ReportClaim claim,
        @JsonProperty("ownership") @Nullable TelemetryOwnership ownership) {

    public static final int FORMAT_VERSION = 1;

    public static TelemetryState initial() {
        return new TelemetryState(
                FORMAT_VERSION,
                0,
                TelemetryMode.ENABLED,
                null,
                null,
                0,
                null,
                null,
                0,
                0,
                null,
                null,
                null,
                null,
                null);
    }

    public boolean hasIdentity() {
        return installationId != null && registeredOn != null;
    }

    public Optional<UUID> installation() {
        return Optional.ofNullable(installationId);
    }

    public Optional<LocalDate> registration() {
        return Optional.ofNullable(registeredOn);
    }

    public Optional<Instant> firstWorkerDeadlineAt() {
        return Optional.ofNullable(firstWorkerDeadline);
    }

    public Optional<LocalDate> lastReported() {
        return Optional.ofNullable(lastReportedDate);
    }

    public Optional<PendingHeartbeat> pending() {
        return Optional.ofNullable(pendingReport);
    }

    public Optional<ReportClaim> activeClaim(Instant now) {
        return Optional.ofNullable(claim).filter(current -> current.expiresAt().isAfter(now));
    }

    public Optional<RetryState> retrySchedule() {
        return Optional.ofNullable(retry);
    }

    public TelemetryState withIdentity(UUID installationId, LocalDate registeredOn) {
        Copy next = new Copy(this);
        next.installationId = installationId;
        next.registeredOn = registeredOn;
        return next.build();
    }

    public TelemetryState withNotice(int noticeRevision, Instant shownAt) {
        Copy next = new Copy(this);
        next.noticeRevision = noticeRevision;
        next.noticeShownAt = shownAt;
        return next.build();
    }

    public TelemetryState withFirstWorkerDeadline(@Nullable Instant deadline) {
        Copy next = new Copy(this);
        next.firstWorkerDeadline = deadline;
        return next.build();
    }

    /// A preference change bumps the revision and discards any pending report, claim, and its retry
    /// schedule so an in-flight completion cannot restore them. A retry schedule without a pending
    /// report is the hold after a permanent or quota-limited failure; it survives so toggling the
    /// mode cannot send again before the next UTC day.
    public TelemetryState withMode(TelemetryMode mode) {
        Copy next = new Copy(this);
        next.preferenceRevision = preferenceRevision + 1;
        next.mode = mode;
        next.retry = pendingReport == null ? retry : null;
        next.pendingReport = null;
        next.claim = null;
        return next.build();
    }

    /// The invariants a stored file must satisfy beyond its JSON shape. A file that breaks one is
    /// treated as unreadable so no identity is regenerated and nothing is sent.
    public Optional<String> invariantProblem() {
        if (mode == null) {
            return Optional.of("mode is missing");
        }
        if ((installationId == null) != (registeredOn == null)) {
            return Optional.of("installation id and registration date must be stored together");
        }
        if (preferenceRevision < 0 || noticeRevision < 0 || boardImportsTotal < 0 || boardCreationsTotal < 0) {
            return Optional.of("a revision or counter is negative");
        }
        if (ownership != null && !ownership.credential().installationId().equals(installationId)) {
            return Optional.of("ownership credential does not match installation identity");
        }
        if (retry != null && (retry.attempts() < 1 || retry.notBefore() == null || retry.reason() == null)) {
            return Optional.of("retry schedule is incomplete");
        }
        if (pendingReport != null) {
            Optional<String> problem = pendingReport.invariantProblem(preferenceRevision, installationId);
            if (problem.isPresent()) {
                return problem;
            }
        }
        if (claim != null) {
            if (claim.owner() == null
                    || claim.eventUuid() == null
                    || claim.attempt() == null
                    || claim.expiresAt() == null) {
                return Optional.of("report claim is incomplete");
            }
            if (pendingReport == null || !pendingReport.eventUuid().equals(claim.eventUuid())) {
                return Optional.of("report claim does not match the pending report");
            }
        }
        return Optional.empty();
    }

    public TelemetryState withCounters(long boardImportsTotal, long boardCreationsTotal) {
        Copy next = new Copy(this);
        next.boardImportsTotal = boardImportsTotal;
        next.boardCreationsTotal = boardCreationsTotal;
        return next.build();
    }

    public TelemetryState withReporting(
            @Nullable LocalDate lastReportedDate,
            @Nullable RetryState retry,
            @Nullable PendingHeartbeat pendingReport,
            @Nullable ReportClaim claim) {
        Copy next = new Copy(this);
        next.lastReportedDate = lastReportedDate;
        next.retry = retry;
        next.pendingReport = pendingReport;
        next.claim = claim;
        return next.build();
    }

    public TelemetryState withOwnership(TelemetryOwnership next) {
        Copy copy = new Copy(this);
        copy.ownership = next;
        return copy.build();
    }

    /// The erasure requested for the current reporting period, if any.
    public TelemetryOwnership.@Nullable Erasure erasure() {
        return ownership == null ? null : ownership.erasure();
    }

    public boolean blocksReporting() {
        return ownership != null && ownership.blocksReporting();
    }

    public Optional<String> analyticsId() {
        return Optional.ofNullable(ownership)
                .map(TelemetryOwnership::analyticsId)
                .or(() -> installation().map(UUID::toString));
    }

    /// The immutable report waiting for delivery. Retries reuse the UUID, timestamp, and properties.
    public record PendingHeartbeat(
            @JsonProperty("event_uuid") UUID eventUuid,
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("observation_date") LocalDate observationDate,
            @JsonProperty("preference_revision") long preferenceRevision,
            @JsonProperty("properties") HeartbeatProperties properties) {

        Optional<String> invariantProblem(long currentRevision, @Nullable UUID installationId) {
            if (eventUuid == null || timestamp == null || observationDate == null || properties == null) {
                return Optional.of("pending report is incomplete");
            }
            if (preferenceRevision < 0 || preferenceRevision > currentRevision) {
                return Optional.of("pending report belongs to an unknown preference revision");
            }
            if (installationId == null) {
                return Optional.of("pending report exists without an installation id");
            }
            return properties.invariantProblem();
        }
    }

    /// The worker that is currently delivering the pending report. A claim expires so a dead worker
    /// cannot silence the installation.
    public record ReportClaim(
            @JsonProperty("owner") String owner,
            @JsonProperty("event_uuid") UUID eventUuid,
            @JsonProperty("attempt") UUID attempt,
            @JsonProperty("expires_at") Instant expiresAt) {}

    /// Bounded retry bookkeeping. `not_before` gates every dispatch, including a fresh snapshot after
    /// a permanent failure; `reason` is the safe outcome summary shown by `telemetry status`.
    public record RetryState(
            @JsonProperty("attempts") int attempts,
            @JsonProperty("not_before") Instant notBefore,
            @JsonProperty("reason") String reason) {}

    /// A mutable copy used by the `with...` methods, so each one names only the components it
    /// changes and a new component is added here instead of in every method.
    private static final class Copy {
        private final int formatVersion;
        private long preferenceRevision;
        private TelemetryMode mode;
        private @Nullable UUID installationId;
        private @Nullable LocalDate registeredOn;
        private int noticeRevision;
        private @Nullable Instant noticeShownAt;
        private @Nullable Instant firstWorkerDeadline;
        private long boardImportsTotal;
        private long boardCreationsTotal;
        private @Nullable LocalDate lastReportedDate;
        private @Nullable RetryState retry;
        private @Nullable PendingHeartbeat pendingReport;
        private @Nullable ReportClaim claim;
        private @Nullable TelemetryOwnership ownership;

        Copy(TelemetryState source) {
            formatVersion = source.formatVersion;
            preferenceRevision = source.preferenceRevision;
            mode = source.mode;
            installationId = source.installationId;
            registeredOn = source.registeredOn;
            noticeRevision = source.noticeRevision;
            noticeShownAt = source.noticeShownAt;
            firstWorkerDeadline = source.firstWorkerDeadline;
            boardImportsTotal = source.boardImportsTotal;
            boardCreationsTotal = source.boardCreationsTotal;
            lastReportedDate = source.lastReportedDate;
            retry = source.retry;
            pendingReport = source.pendingReport;
            claim = source.claim;
            ownership = source.ownership;
        }

        TelemetryState build() {
            return new TelemetryState(
                    formatVersion,
                    preferenceRevision,
                    mode,
                    installationId,
                    registeredOn,
                    noticeRevision,
                    noticeShownAt,
                    firstWorkerDeadline,
                    boardImportsTotal,
                    boardCreationsTotal,
                    lastReportedDate,
                    retry,
                    pendingReport,
                    claim,
                    ownership);
        }
    }
}
