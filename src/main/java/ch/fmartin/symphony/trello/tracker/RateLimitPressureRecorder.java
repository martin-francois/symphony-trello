package ch.fmartin.symphony.trello.tracker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/// Collects Trello `429` responses from every thread that shares one `TrelloClient`: the poll tick
/// and Codex tool calls running at the same time. The orchestrator drains the batch once per tick.
final class RateLimitPressureRecorder {
    private final Clock clock;
    private final AtomicReference<RateLimitPressure> pending = new AtomicReference<>(RateLimitPressure.NONE);

    /// Runs inside the merge, after it read the pending batch and before it publishes the merged
    /// one. Tests use it to drain at exactly that point.
    Runnable mergeHookForTests = () -> {};

    RateLimitPressureRecorder(Clock clock) {
        this.clock = clock;
    }

    void record(Optional<Duration> retryAfter) {
        Instant observedAt = clock.instant();
        // updateAndGet merges again when a drain or another 429 replaced the batch in between, so
        // every response lands in exactly one drained batch.
        pending.updateAndGet(current -> {
            mergeHookForTests.run();
            return current.plus(observedAt, retryAfter);
        });
    }

    RateLimitPressure drain() {
        return pending.getAndSet(RateLimitPressure.NONE);
    }
}
