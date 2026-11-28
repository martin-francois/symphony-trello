package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.testsupport.BoundedWait;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ManagedFileLockTest {
    @TempDir
    Path temporary;

    @Test
    void nestedTransactionKeepsCompetingThreadBlockedUntilOuterClose() throws Exception {
        // given
        Path path = temporary.resolve("connection.lock");
        var started = new CountDownLatch(1);
        var competitor = new AtomicReference<Thread>();
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> acquired;
            try (var _ = ManagedFileLock.acquire(path)) {
                try (var _ = ManagedFileLock.acquire(path)) {
                    assertThat(path).isRegularFile();
                }

                // when
                acquired = executor.submit(() -> {
                    competitor.set(Thread.currentThread());
                    started.countDown();
                    try (var ignored = ManagedFileLock.acquire(path)) {
                        return true;
                    }
                });
                assertThat(started.await(5, TimeUnit.SECONDS))
                        .as("competing transaction starts")
                        .isTrue();
                BoundedWait.until(
                        Duration.ofSeconds(5),
                        () -> acquired.isDone() || competitor.get().getState() == Thread.State.WAITING,
                        "competing transaction reaches the connection lock");

                // then
                assertThat(acquired.isDone())
                        .as("closing the nested transaction does not release the outer lock")
                        .isFalse();
            }
            assertThat(acquired.get(5, TimeUnit.SECONDS))
                    .as("outer close admits the competing transaction")
                    .isTrue();
        }
    }
}
