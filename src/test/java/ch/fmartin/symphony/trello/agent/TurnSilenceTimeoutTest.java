package ch.fmartin.symphony.trello.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class TurnSilenceTimeoutTest {
    private static final Duration LIMIT = Duration.ofSeconds(10);
    private static final Duration OUTPUT_GAP = Duration.ofSeconds(9);
    private static final Duration OUTPUT_DURING_FIRST_WAIT = Duration.ofSeconds(8);
    private static final Duration STALE_READING = Duration.ofSeconds(5);
    private static final long THREAD_WAIT_SECONDS = 10;
    private static final Duration STREAMING_TURN_LENGTH = LIMIT.multipliedBy(10);

    private final AtomicLong now = new AtomicLong();
    private final AtomicReference<Thread> staleReader = new AtomicReference<>();
    private final CountDownLatch freshOutputRecorded = new CountDownLatch(1);
    private final TurnSilenceTimeout silence = new TurnSilenceTimeout(this::nanoTime);
    private final List<Duration> requestedWaits = new ArrayList<>();

    @Test
    void failsOnceTheLimitPassesWithoutOutput() {
        // given
        TurnSilenceTimeout.BoundedWait<String> silentTurn = this::silentTurn;

        // when
        TimeoutException failure = catchThrowableOfType(TimeoutException.class, () -> silence.await(silentTurn, LIMIT));

        // then
        assertThat(failure).hasMessage("no app-server output for 10000 ms");
        assertThat(requestedWaits).containsExactly(LIMIT);
    }

    @Test
    void outputDuringTheWaitMovesTheDeadline() throws Exception {
        // given
        TurnSilenceTimeout.BoundedWait<String> turn = this::outputAfterEightSecondsThenCompletion;

        // when
        String result = silence.await(turn, LIMIT);

        // then
        assertThat(result).isEqualTo("completed");
        assertThat(requestedWaits).containsExactly(LIMIT, OUTPUT_DURING_FIRST_WAIT);
    }

    @Test
    void aReaderThatReadTheClockEarlierCannotMoveTheDeadlineBack() throws Exception {
        // given
        TurnSilenceTimeout.BoundedWait<String> turn = this::staleAndFreshOutputFromTwoReaders;

        // when
        String result = silence.await(turn, LIMIT);

        // then
        assertThat(result).isEqualTo("completed");
        assertThat(requestedWaits)
                .as("the second wait must count from the newer output at 8 s, not the stale reading at 5 s")
                .containsExactly(LIMIT, OUTPUT_DURING_FIRST_WAIT);
    }

    @Test
    void aTurnThatKeepsStreamingRunsFarPastTheLimit() throws Exception {
        // given
        TurnSilenceTimeout.BoundedWait<String> streamingTurn = this::streamingTurn;

        // when
        String result = silence.await(streamingTurn, LIMIT);

        // then
        assertThat(result).isEqualTo("completed");
        assertThat(Duration.ofNanos(now.get())).isGreaterThanOrEqualTo(STREAMING_TURN_LENGTH);
    }

    @Test
    void theIntervalStartsWhenTheWaitStarts() throws Exception {
        // given
        now.addAndGet(Duration.ofMinutes(5).toNanos());
        TurnSilenceTimeout.BoundedWait<String> turn = this::immediateCompletion;

        // when
        String result = silence.await(turn, LIMIT);

        // then
        assertThat(result).isEqualTo("completed");
        assertThat(requestedWaits).containsExactly(LIMIT);
    }

    /// Lets the whole requested time pass without output.
    private String silentTurn(long timeoutNanos) throws TimeoutException {
        requestedWaits.add(Duration.ofNanos(timeoutNanos));
        now.addAndGet(timeoutNanos);
        throw new TimeoutException();
    }

    /// Prints output 8 seconds into the first wait, then completes during the second wait.
    private String outputAfterEightSecondsThenCompletion(long timeoutNanos) throws TimeoutException {
        requestedWaits.add(Duration.ofNanos(timeoutNanos));
        if (requestedWaits.size() > 1) {
            return "completed";
        }
        now.set(OUTPUT_DURING_FIRST_WAIT.toNanos());
        silence.outputReceived();
        now.set(LIMIT.toNanos());
        throw new TimeoutException();
    }

    /// During the first wait, one reader reads the clock at 5 s and is held before it records the
    /// output, while a second reader records output at 8 s. The held reader then records its older
    /// reading. The second wait completes.
    private String staleAndFreshOutputFromTwoReaders(long timeoutNanos) throws InterruptedException, TimeoutException {
        requestedWaits.add(Duration.ofNanos(timeoutNanos));
        if (requestedWaits.size() > 1) {
            return "completed";
        }
        // Own platform threads, so the held reader cannot block the fresh one on a shared pool.
        Thread stale = Thread.ofPlatform().unstarted(silence::outputReceived);
        staleReader.set(stale);
        stale.start();
        now.set(OUTPUT_DURING_FIRST_WAIT.toNanos());
        Thread fresh = Thread.ofPlatform().start(this::recordFreshOutput);
        assertThat(fresh.join(Duration.ofSeconds(THREAD_WAIT_SECONDS)))
                .as("the fresh reader finished")
                .isTrue();
        assertThat(stale.join(Duration.ofSeconds(THREAD_WAIT_SECONDS)))
                .as("the stale reader finished")
                .isTrue();
        now.set(LIMIT.toNanos());
        throw new TimeoutException();
    }

    private void recordFreshOutput() {
        silence.outputReceived();
        freshOutputRecorded.countDown();
    }

    /// The fake monotonic clock. The held reader gets the stale reading and returns it only after the
    /// fresh output is recorded, which forces the late write of an early reading.
    private long nanoTime() {
        if (!Thread.currentThread().equals(staleReader.get())) {
            return now.get();
        }
        try {
            if (!freshOutputRecorded.await(THREAD_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the fresh reader never recorded its output");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding the stale reader", e);
        }
        return STALE_READING.toNanos();
    }

    /// Prints output every 9 seconds and completes after ten times the limit.
    private String streamingTurn(long timeoutNanos) throws TimeoutException {
        if (now.get() >= STREAMING_TURN_LENGTH.toNanos()) {
            return "completed";
        }
        now.addAndGet(Math.min(timeoutNanos, OUTPUT_GAP.toNanos()));
        silence.outputReceived();
        throw new TimeoutException();
    }

    private String immediateCompletion(long timeoutNanos) {
        requestedWaits.add(Duration.ofNanos(timeoutNanos));
        return "completed";
    }
}
