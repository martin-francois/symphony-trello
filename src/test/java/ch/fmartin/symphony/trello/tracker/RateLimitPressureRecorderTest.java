package ch.fmartin.symphony.trello.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class RateLimitPressureRecorderTest {
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Duration LONGEST_RETRY_AFTER = Duration.ofSeconds(12);
    private static final Duration SHORTER_RETRY_AFTER = Duration.ofSeconds(3);
    private static final Duration TEST_WAIT_LIMIT = Duration.ofSeconds(10);
    private static final Duration THREAD_STATE_POLL_INTERVAL = Duration.ofMillis(1);
    /// A drain that ran to completion, or one that waits for the merge to publish first.
    private static final Set<Thread.State> DRAIN_DONE_OR_WAITING =
            EnumSet.of(Thread.State.TERMINATED, Thread.State.BLOCKED, Thread.State.WAITING);

    private final RateLimitPressureRecorder recorder = new RateLimitPressureRecorder(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void batchKeepsTheLatestRetryAfterDeadlineAcrossResponses() {
        // given
        recorder.record(Optional.of(LONGEST_RETRY_AFTER));
        recorder.record(Optional.empty());
        recorder.record(Optional.of(SHORTER_RETRY_AFTER));

        // when
        RateLimitPressure pressure = recorder.drain();

        // then
        assertThat(pressure)
                .isEqualTo(new RateLimitPressure(3, Optional.of(NOW), Optional.of(NOW.plus(LONGEST_RETRY_AFTER))));
        assertThat(recorder.drain()).isEqualTo(RateLimitPressure.NONE);
    }

    @Test
    void drainBetweenReadingAndPublishingAMergeCountsEveryResponseInExactlyOneBatch() throws Exception {
        // given
        recorder.record(Optional.empty());
        var firstMerge = new AtomicBoolean(true);
        var concurrentDrain = new AtomicReference<RateLimitPressure>();
        var drainer = new AtomicReference<Thread>();
        recorder.mergeHookForTests = () -> {
            if (firstMerge.compareAndSet(true, false)) {
                Thread thread = Thread.ofPlatform().start(() -> concurrentDrain.set(recorder.drain()));
                drainer.set(thread);
                awaitDoneOrWaiting(thread);
            }
        };

        // when
        recorder.record(Optional.of(SHORTER_RETRY_AFTER));
        drainer.get().join(TEST_WAIT_LIMIT);
        RateLimitPressure remaining = recorder.drain();

        // then
        assertThat(drainer.get().isAlive())
                .as("the concurrent drain finishes within %s", TEST_WAIT_LIMIT)
                .isFalse();
        assertThat(concurrentDrain.get().rateLimitedResponses() + remaining.rateLimitedResponses())
                .as(
                        "two 429 responses were recorded; a drain racing the second merge must neither lose "
                                + "nor repeat one (concurrent drain %s, remaining %s)",
                        concurrentDrain.get(), remaining)
                .isEqualTo(2);
    }

    /// Waits until the drain either finished or waits for the merge, so the merge continues only
    /// after the drain had its chance to interleave.
    private static void awaitDoneOrWaiting(Thread drainer) {
        long deadline = System.nanoTime() + TEST_WAIT_LIMIT.toNanos();
        while (!DRAIN_DONE_OR_WAITING.contains(drainer.getState())) {
            assertThat(System.nanoTime() - deadline)
                    .as("the concurrent drain finishes or waits within %s", TEST_WAIT_LIMIT)
                    .isNegative();
            try {
                Thread.sleep(THREAD_STATE_POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for the concurrent drain", e);
            }
        }
    }
}
