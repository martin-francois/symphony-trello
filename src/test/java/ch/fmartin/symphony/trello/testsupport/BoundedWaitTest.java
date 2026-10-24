package ch.fmartin.symphony.trello.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class BoundedWaitTest {
    private static final Duration GENEROUS_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(50);
    private static final int CHECKS_BEFORE_CONDITION_HOLDS = 3;

    @Test
    void returnsOnceTheConditionHolds() throws InterruptedException {
        // given
        var checks = new AtomicInteger();

        // when
        BoundedWait.until(
                GENEROUS_TIMEOUT, () -> checks.incrementAndGet() == CHECKS_BEFORE_CONDITION_HOLDS, "the third check");

        // then
        assertThat(checks).hasValue(CHECKS_BEFORE_CONDITION_HOLDS);
    }

    @Test
    void failsWithTheDescriptionWhenTheTimeoutPasses() {
        // given

        // when
        AssertionError failure = catchThrowableOfType(
                AssertionError.class, () -> BoundedWait.until(SHORT_TIMEOUT, () -> false, "the worker start"));

        // then
        assertThat(failure).hasMessage("the worker start did not happen within %s", SHORT_TIMEOUT);
    }
}
