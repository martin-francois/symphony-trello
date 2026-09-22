package ch.fmartin.symphony.trello.telemetry;

import java.time.Duration;
import java.util.Optional;

/// The safe summary of one capture attempt. It carries no response body and no exception text that
/// could include secrets; only a status code or a short category.
public record CaptureOutcome(Kind kind, Optional<Integer> statusCode, Optional<Duration> retryAfter, String summary) {
    public enum Kind {
        /// The endpoint accepted the event with its documented success response.
        ACCEPTED,
        /// The endpoint answered 200 but reported the event as quota-limited; a retry would be dropped too.
        QUOTA_LIMITED,
        /// A retry later may succeed: rate limit, server error, timeout, or connection failure.
        TRANSIENT,
        /// The request itself is rejected: bad token, malformed body, or payload too large.
        PERMANENT
    }

    /// Only a documented success response counts. A quota drop is deferred like a permanent
    /// failure, not recorded as an accepted report.
    public boolean accepted() {
        return kind == Kind.ACCEPTED;
    }

    static CaptureOutcome accepted(int statusCode) {
        return new CaptureOutcome(Kind.ACCEPTED, Optional.of(statusCode), Optional.empty(), "accepted");
    }

    static CaptureOutcome quotaLimited(int statusCode) {
        return new CaptureOutcome(
                Kind.QUOTA_LIMITED, Optional.of(statusCode), Optional.empty(), "accepted but quota-limited");
    }

    static CaptureOutcome transientFailure(
            Optional<Integer> statusCode, Optional<Duration> retryAfter, String summary) {
        return new CaptureOutcome(Kind.TRANSIENT, statusCode, retryAfter, summary);
    }

    /// The body was refused locally before any request; there is no HTTP status.
    static CaptureOutcome localFailure(String summary) {
        return new CaptureOutcome(Kind.PERMANENT, Optional.empty(), Optional.empty(), summary);
    }

    static CaptureOutcome permanentFailure(int statusCode, String summary) {
        return new CaptureOutcome(Kind.PERMANENT, Optional.of(statusCode), Optional.empty(), summary);
    }
}
