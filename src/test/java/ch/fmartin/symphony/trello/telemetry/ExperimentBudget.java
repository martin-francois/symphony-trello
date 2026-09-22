package ch.fmartin.symphony.trello.telemetry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/// The per-invocation limits of the live erasure experiment: how many management requests it may
/// send and how long it may wait in total. Both are experiment limits, not provider promises; when
/// one is exhausted the experiment stops with a resumable checkpoint instead of polling forever.
final class ExperimentBudget {
    private final int maxRequests;
    private final Duration maxWait;
    private final Sleeper sleeper;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong waitedMillis = new AtomicLong();

    ExperimentBudget(int maxRequests, Duration maxWait, Sleeper sleeper) {
        this.maxRequests = maxRequests;
        this.maxWait = maxWait;
        this.sleeper = sleeper;
    }

    static ExperimentBudget realTime(int maxRequests, Duration maxWait) {
        return new ExperimentBudget(maxRequests, maxWait, Thread::sleep);
    }

    /// Charges one management request; fails before the request leaves when none is left.
    void chargeRequest(String what) {
        if (requests.incrementAndGet() > maxRequests) {
            throw new BudgetExhaustedException("request budget of " + maxRequests + " exhausted before " + what);
        }
    }

    /// Waits the given time, or fails without sleeping when the wait budget would be exceeded.
    void await(Duration duration, String reason) {
        long total = waitedMillis.addAndGet(duration.toMillis());
        if (total > maxWait.toMillis()) {
            throw new BudgetExhaustedException("wait budget of " + maxWait + " exhausted while " + reason);
        }
        try {
            sleeper.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BudgetExhaustedException("interrupted while " + reason, exception);
        }
    }

    /// Charges time already spent waiting on I/O against the same wait budget as polling sleeps.
    void chargeElapsed(Duration duration, String reason) {
        long total = waitedMillis.addAndGet(Math.max(0, duration.toMillis()));
        if (total > maxWait.toMillis()) {
            throw new BudgetExhaustedException("wait budget of " + maxWait + " exhausted while " + reason);
        }
    }

    /// How long this invocation may still wait; a request may not block longer than this.
    Duration remainingWait() {
        return Duration.ofMillis(Math.max(0, maxWait.toMillis() - waitedMillis.get()));
    }

    int requests() {
        return requests.get();
    }

    Duration waited() {
        return Duration.ofMillis(waitedMillis.get());
    }

    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /// The invocation reached one of its limits; the checkpoint says where to resume.
    static final class BudgetExhaustedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BudgetExhaustedException(String message) {
            super(message);
        }

        BudgetExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
