package ch.fmartin.symphony.trello.orchestrator;

import ch.fmartin.symphony.trello.tracker.RateLimitPressure;
import com.google.common.collect.Comparators;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.jboss.logging.Logger;

/// Chooses the delay before the next poll tick from the configured `polling.interval_ms` and the
/// Trello `429` responses seen since the previous tick. See
/// docs/adr/0098-adaptive-trello-polling-interval.md.
///
/// Not thread-safe: the orchestrator calls it only while holding its instance monitor.
final class AdaptivePollInterval {
    static final String TRELLO_RATE_LIMITED = "trello_rate_limited";
    /// How long the worker must see no `429` before each recovery step.
    static final Duration QUIET_PERIOD = Duration.ofSeconds(60);
    /// Each tick with a `429` multiplies the effective interval by this; each quiet period divides it.
    static final int SLOWDOWN_FACTOR = 2;
    /// Jitter adds up to this share of the delay, so workers sharing a Trello token drift apart.
    static final int MAX_JITTER_PERCENT = 10;

    private static final Logger LOG = Logger.getLogger(AdaptivePollInterval.class);
    private static final int PERCENT = 100;

    private final Clock clock;
    private final RandomGenerator random;
    /// Zero while not slowed down; otherwise the slowed interval, at most [RateLimitPressure#MAX_WAIT].
    private Duration slowedInterval = Duration.ZERO;
    /// When the slowed interval last grew or shrank; recovery waits a quiet period from here.
    private Instant slowedIntervalChangedAt = Instant.MIN;

    private Optional<Instant> lastRateLimitedAt = Optional.empty();

    AdaptivePollInterval(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    /// Updates the effective interval for the pressure one tick saw and returns how long to wait
    /// before the next tick. A `Retry-After` wait that a request already sat out has ended by now,
    /// so only the part still ahead extends the delay.
    Duration nextDelay(Duration configured, RateLimitPressure pressure) {
        Instant now = clock.instant();
        Duration previous = effectiveInterval(configured);
        if (pressure.rateLimited()) {
            slowedInterval = Comparators.min(previous.multipliedBy(SLOWDOWN_FACTOR), RateLimitPressure.MAX_WAIT);
            slowedIntervalChangedAt = now;
            lastRateLimitedAt = pressure.lastRateLimitedAt().or(() -> lastRateLimitedAt);
        } else if (isSlowedDown(configured) && !now.isBefore(slowedIntervalChangedAt.plus(QUIET_PERIOD))) {
            slowedInterval = slowedInterval.dividedBy(SLOWDOWN_FACTOR);
            slowedIntervalChangedAt = now;
        }
        if (!isSlowedDown(configured)) {
            slowedInterval = Duration.ZERO;
        }
        Duration effective = effectiveInterval(configured);
        logChange(configured, previous, effective);
        Duration retryAfterLeft = pressure.retryNotBefore()
                .map(notBefore -> Duration.between(now, notBefore))
                .filter(Duration::isPositive)
                .orElse(Duration.ZERO);
        return withJitter(Comparators.max(effective, retryAfterLeft));
    }

    RuntimeSnapshot.Polling status(Duration configured) {
        return new RuntimeSnapshot.Polling(
                configured,
                effectiveInterval(configured),
                isSlowedDown(configured) ? Optional.of(TRELLO_RATE_LIMITED) : Optional.empty(),
                lastRateLimitedAt);
    }

    private Duration effectiveInterval(Duration configured) {
        return Comparators.max(configured, slowedInterval);
    }

    private boolean isSlowedDown(Duration configured) {
        return slowedInterval.compareTo(configured) > 0;
    }

    /// Jitter only lengthens the delay, so the configured interval and any `Retry-After` stay
    /// minimums.
    private Duration withJitter(Duration delay) {
        long maxJitterMillis = delay.toMillis() * MAX_JITTER_PERCENT / PERCENT;
        return maxJitterMillis > 0 ? delay.plusMillis(random.nextLong(maxJitterMillis)) : delay;
    }

    private static void logChange(Duration configured, Duration previous, Duration effective) {
        int change = effective.compareTo(previous);
        if (change > 0) {
            LOG.warnf(
                    "polling outcome=slowed_down reason=%s configured_interval_ms=%d effective_interval_ms=%d",
                    TRELLO_RATE_LIMITED, configured.toMillis(), effective.toMillis());
        } else if (change < 0) {
            LOG.infof(
                    "polling outcome=recovering configured_interval_ms=%d effective_interval_ms=%d",
                    configured.toMillis(), effective.toMillis());
        }
    }
}
