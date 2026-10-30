package ch.fmartin.symphony.trello.agent;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.jspecify.annotations.NullMarked;

/// Applies `codex.turn_timeout_ms` the way upstream Symphony defines it: as the longest time a
/// running turn may go without app-server output. Every output line resets the interval, so a turn
/// that keeps streaming is never cut off, however long it runs (ADR 0136).
///
/// Reader threads call [#outputReceived()]; the session owner calls [#await]. The last output time
/// is one atomic value that only moves forward, so a thread that read the clock earlier but writes
/// later cannot pull the deadline back. The owner measures the silence again after every bounded
/// wait, so output that arrives while the owner waits always moves the deadline.
@NullMarked
final class TurnSilenceTimeout {
    private final LongSupplier nanoTime;
    private final AtomicLong lastOutputNanos;

    /// `nanoTime` is a monotonic time source in nanoseconds, such as [System#nanoTime()].
    TurnSilenceTimeout(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.lastOutputNanos = new AtomicLong(nanoTime.getAsLong());
    }

    /// Records one line of app-server output.
    void outputReceived() {
        lastOutputNanos.accumulateAndGet(nanoTime.getAsLong(), TurnSilenceTimeout::later);
    }

    /// The later of two [System#nanoTime()] readings; the difference stays correct across overflow.
    private static long later(long first, long second) {
        return second - first > 0 ? second : first;
    }

    /// Waits for `wait` to finish and fails once `limit` passes without app-server output. The
    /// interval starts when this method is called.
    <T> T await(BoundedWait<T> wait, Duration limit) throws InterruptedException, ExecutionException, TimeoutException {
        // The interval starts with the wait, as if the turn had just written output.
        outputReceived();
        long limitNanos = limit.toNanos();
        long remainingNanos = remainingNanos(limitNanos);
        while (remainingNanos > 0) {
            try {
                return wait.get(remainingNanos);
            } catch (TimeoutException ignored) {
                // Output during the wait moved the deadline, so the silence is measured again.
                remainingNanos = remainingNanos(limitNanos);
            }
        }
        throw new TimeoutException("no app-server output for " + limit.toMillis() + " ms");
    }

    private long remainingNanos(long limitNanos) {
        return limitNanos - (nanoTime.getAsLong() - lastOutputNanos.get());
    }

    /// A wait that returns the result or throws [TimeoutException] after at most `timeoutNanos`.
    @FunctionalInterface
    interface BoundedWait<T> {
        T get(long timeoutNanos) throws InterruptedException, ExecutionException, TimeoutException;
    }
}
