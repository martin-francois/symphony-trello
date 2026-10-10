package ch.fmartin.symphony.trello.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestratorTestSupport.MutableClock;
import ch.fmartin.symphony.trello.tracker.RateLimitPressure;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongUnaryOperator;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class AdaptivePollIntervalTest {
    private static final Instant START = Instant.parse("2026-10-04T10:00:00Z");
    private static final Duration CONFIGURED = Duration.ofSeconds(5);
    private static final int PERCENT = 100;
    private static final RandomGenerator NO_JITTER = new FixedRandom(bound -> 0);
    private static final RandomGenerator MAX_JITTER = new FixedRandom(bound -> bound - 1);

    private final MutableClock clock = new MutableClock(START);

    @MethodSource("jitterDraws")
    @ParameterizedTest(name = "{0}")
    void jitterOnlyLengthensTheDelayAndStaysBelowItsShare(
            String scenario, RandomGenerator random, Duration expectedDelay) {
        // given
        var interval = new AdaptivePollInterval(clock, random);

        // when
        Duration delay = interval.nextDelay(CONFIGURED, RateLimitPressure.NONE);

        // then
        assertThat(delay).as(scenario).isEqualTo(expectedDelay);
    }

    private static Stream<Arguments> jitterDraws() {
        return Stream.of(
                Arguments.of("smallest draw keeps the configured interval", NO_JITTER, CONFIGURED),
                Arguments.of(
                        "largest draw stays below the jitter share on top", MAX_JITTER, withLargestJitter(CONFIGURED)));
    }

    @Test
    void eachRateLimitedTickDoublesTheEffectiveIntervalUntilTheThirtySecondCap() {
        // given
        var interval = new AdaptivePollInterval(clock, NO_JITTER);
        List<Duration> delays = new ArrayList<>();

        // when
        for (int tick = 0; tick < 4; tick++) {
            delays.add(interval.nextDelay(CONFIGURED, rateLimited(Optional.empty())));
        }

        // then
        assertThat(delays)
                .containsExactly(
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(20),
                        RateLimitPressure.MAX_WAIT,
                        RateLimitPressure.MAX_WAIT);
        assertThat(interval.status(CONFIGURED))
                .isEqualTo(new RuntimeSnapshot.Polling(
                        CONFIGURED,
                        RateLimitPressure.MAX_WAIT,
                        Optional.of(AdaptivePollInterval.TRELLO_RATE_LIMITED),
                        Optional.of(START)));
    }

    @Test
    void retryAfterStillAheadDelaysTheNextTickBeyondTheEffectiveInterval() {
        // given
        var interval = new AdaptivePollInterval(clock, NO_JITTER);
        Duration retryAfter = Duration.ofSeconds(25);

        // when
        Duration delay = interval.nextDelay(CONFIGURED, rateLimited(Optional.of(START.plus(retryAfter))));

        // then
        assertThat(delay).isEqualTo(retryAfter);
        assertThat(interval.status(CONFIGURED).effectiveInterval()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void retryAfterARequestAlreadyWaitedOutIsNotWaitedAgain() {
        // given
        var interval = new AdaptivePollInterval(clock, NO_JITTER);
        Instant retryNotBefore = START.plusSeconds(25);
        clock.advance(Duration.ofSeconds(26));

        // when
        Duration delay = interval.nextDelay(CONFIGURED, rateLimited(Optional.of(retryNotBefore)));

        // then
        assertThat(delay)
                .as("the read already slept until %s, so only the slowed interval remains", retryNotBefore)
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void retryAfterGetsJitterOnTopSoWorkersDoNotRetryInStep() {
        // given
        var interval = new AdaptivePollInterval(clock, MAX_JITTER);
        Duration retryAfter = Duration.ofSeconds(20);

        // when
        Duration delay = interval.nextDelay(CONFIGURED, rateLimited(Optional.of(START.plus(retryAfter))));

        // then
        assertThat(delay).isEqualTo(withLargestJitter(retryAfter));
    }

    @Test
    void quietPeriodsHalveTheIntervalStepByStepBackToTheConfiguredInterval() {
        // given
        var interval = new AdaptivePollInterval(clock, NO_JITTER);
        for (int tick = 0; tick < 3; tick++) {
            interval.nextDelay(CONFIGURED, rateLimited(Optional.empty()));
        }
        List<Duration> effective = new ArrayList<>();

        // when
        clock.advance(AdaptivePollInterval.QUIET_PERIOD.minusMillis(1));
        effective.add(interval.nextDelay(CONFIGURED, RateLimitPressure.NONE));
        for (int quietPeriod = 0; quietPeriod < 3; quietPeriod++) {
            clock.advance(AdaptivePollInterval.QUIET_PERIOD);
            effective.add(interval.nextDelay(CONFIGURED, RateLimitPressure.NONE));
        }

        // then
        assertThat(effective)
                .containsExactly(
                        RateLimitPressure.MAX_WAIT, Duration.ofSeconds(15), Duration.ofMillis(7_500), CONFIGURED);
        assertThat(interval.status(CONFIGURED))
                .isEqualTo(new RuntimeSnapshot.Polling(CONFIGURED, CONFIGURED, Optional.empty(), Optional.of(START)));
    }

    @Test
    void rateLimitDuringRecoveryRestartsTheQuietPeriod() {
        // given
        var interval = new AdaptivePollInterval(clock, NO_JITTER);
        interval.nextDelay(CONFIGURED, rateLimited(Optional.empty()));
        clock.advance(AdaptivePollInterval.QUIET_PERIOD.dividedBy(2));
        interval.nextDelay(CONFIGURED, rateLimited(Optional.empty()));

        // when
        clock.advance(AdaptivePollInterval.QUIET_PERIOD.dividedBy(2));
        Duration delay = interval.nextDelay(CONFIGURED, RateLimitPressure.NONE);

        // then
        assertThat(delay).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void configuredIntervalAtTheCapIsNotSlowedDown() {
        // given
        var interval = new AdaptivePollInterval(clock, NO_JITTER);
        Duration configured = RateLimitPressure.MAX_WAIT;

        // when
        Duration delay = interval.nextDelay(configured, rateLimited(Optional.empty()));

        // then
        assertThat(delay).isEqualTo(configured);
        assertThat(interval.status(configured))
                .isEqualTo(new RuntimeSnapshot.Polling(configured, configured, Optional.empty(), Optional.of(START)));
    }

    @Test
    void reloadedIntervalAboveTheSlowedIntervalReplacesTheSlowdown() {
        // given
        var interval = new AdaptivePollInterval(clock, NO_JITTER);
        interval.nextDelay(CONFIGURED, rateLimited(Optional.empty()));
        Duration reloaded = Duration.ofSeconds(20);

        // when
        Duration delay = interval.nextDelay(reloaded, RateLimitPressure.NONE);

        // then
        assertThat(delay).isEqualTo(reloaded);
        assertThat(interval.status(reloaded).slowdownReason()).isEmpty();
    }

    private static Duration withLargestJitter(Duration delay) {
        return delay.plus(delay.multipliedBy(AdaptivePollInterval.MAX_JITTER_PERCENT)
                        .dividedBy(PERCENT))
                .minusMillis(1);
    }

    private static RateLimitPressure rateLimited(Optional<Instant> retryNotBefore) {
        return new RateLimitPressure(1, Optional.of(START), retryNotBefore);
    }

    /// Returns a chosen draw for every bound, so a test can pin the smallest or largest jitter.
    private record FixedRandom(LongUnaryOperator drawForBound) implements RandomGenerator {
        @Override
        public long nextLong() {
            throw new UnsupportedOperationException("the poll interval only draws bounded values");
        }

        @Override
        public long nextLong(long bound) {
            return drawForBound.applyAsLong(bound);
        }
    }
}
