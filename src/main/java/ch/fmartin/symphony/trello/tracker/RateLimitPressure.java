package ch.fmartin.symphony.trello.tracker;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;

/// Trello `429` responses a worker process received since the orchestrator last drained them.
/// The poll scheduler turns them into a slower effective poll interval; see
/// docs/adr/0098-adaptive-trello-polling-interval.md.
///
/// @param rateLimitedResponses number of `429` responses, from reads and writes alike
/// @param lastRateLimitedAt when the newest `429` response arrived
/// @param retryNotBefore the latest instant a `Retry-After` header asked Symphony to wait for
@NullMarked
public record RateLimitPressure(
        int rateLimitedResponses, Optional<Instant> lastRateLimitedAt, Optional<Instant> retryNotBefore) {
    /// The longest wait a Trello rate limit can cause: it bounds each `Retry-After` value and the
    /// slowed poll interval, so a misbehaving proxy cannot stall a worker or its shutdown.
    public static final Duration MAX_WAIT = Duration.ofMillis(30_000);

    public static final RateLimitPressure NONE = new RateLimitPressure(0, Optional.empty(), Optional.empty());

    public boolean rateLimited() {
        return rateLimitedResponses > 0;
    }

    RateLimitPressure plus(Instant observedAt, Optional<Duration> retryAfter) {
        return new RateLimitPressure(
                rateLimitedResponses + 1,
                later(lastRateLimitedAt, Optional.of(observedAt)),
                later(retryNotBefore, retryAfter.map(observedAt::plus)));
    }

    private static Optional<Instant> later(Optional<Instant> first, Optional<Instant> second) {
        return Stream.of(first, second).flatMap(Optional::stream).max(Comparator.naturalOrder());
    }
}
