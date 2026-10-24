package ch.fmartin.symphony.trello.testsupport;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/// Waits for a condition that another thread makes true, failing the test once the timeout passes.
public final class BoundedWait {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    private BoundedWait() {}

    public static void until(Duration timeout, BooleanSupplier condition, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                throw new AssertionError("%s did not happen within %s".formatted(description, timeout));
            }
            Thread.sleep(POLL_INTERVAL);
        }
    }
}
