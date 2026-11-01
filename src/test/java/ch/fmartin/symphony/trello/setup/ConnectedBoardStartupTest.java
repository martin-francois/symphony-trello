package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.fmartin.symphony.trello.testsupport.BoundedWait;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConnectedBoardStartupTest {
    @TempDir
    Path temporary;

    @Test
    void concurrentStartCannotLaunchTheStoppedConnectionBeforeReplacementPublication() throws Exception {
        // given
        var fixture = new LocalWorkerManagerTestFixture(temporary);
        ConnectedBoard previous = fixture.connectedBoard("board-1", "Queue");
        fixture.save(previous);
        fixture.stubHealthyStartedWorker(previous, 42);
        Path source = temporary.resolve("replacement.md");
        Files.copy(previous.workflowPath(), source);
        Path credentials = fixture.paths.configDir().resolve("new.env");
        fixture.writeEnv(credentials);
        var importer = new ManagedWorkflowImport(
                new WorkflowConfigEditor(),
                Map.of(),
                ignored -> new TrelloBoardSetup.BoardInfo(
                        previous.boardId(), previous.boardKey(), previous.boardName(), previous.boardUrl()));
        var importThread = new AtomicReference<Thread>();
        var startThread = new AtomicReference<Thread>();
        var startEntered = new CountDownLatch(1);
        try (var out = new PrintStream(OutputStream.nullOutputStream());
                var executor = Executors.newFixedThreadPool(2)) {
            var pause = new PausedStop(fixture, out, new CountDownLatch(1), new CountDownLatch(1));
            var importing = executor.submit(() -> {
                importThread.set(Thread.currentThread());
                return importer.connect(fixture.paths, source, Optional.of(credentials), pause::stop);
            });
            try {
                assertThat(pause.stopped().await(5, TimeUnit.SECONDS))
                        .as("replacement stops the old worker")
                        .isTrue();

                // when
                var starting = executor.submit(() -> {
                    startThread.set(Thread.currentThread());
                    startEntered.countDown();
                    return catchThrowable(
                            () -> fixture.manager.start(fixture.paths, previous, previous.envPath(), out));
                });
                assertThat(startEntered.await(5, TimeUnit.SECONDS))
                        .as("the competing startup enters")
                        .isTrue();
                BoundedWait.until(
                        Duration.ofSeconds(5),
                        () -> starting.isDone() || waitsFor(startThread.get(), importThread.get()),
                        "startup waits for replacement publication");

                // then
                assertThat(starting.isDone())
                        .as("startup cannot use the stopped connection before publication")
                        .isFalse();
                pause.publish().countDown();
                assertThat(importing.get(5, TimeUnit.SECONDS).envPath()).isEqualTo(credentials);
                assertThat(starting.get(5, TimeUnit.SECONDS))
                        .isInstanceOf(TrelloBoardSetupException.class)
                        .hasMessageContaining("connection changed");
                verify(fixture.platform, never()).start(any(), any(), any(), any(), any());
            } finally {
                pause.publish().countDown();
            }
        }
    }

    private static boolean waitsFor(Thread waiting, Thread owner) {
        var info = ManagementFactory.getThreadMXBean().getThreadInfo(waiting.threadId());
        return info != null && info.getLockOwnerId() == owner.threadId();
    }

    private record PausedStop(
            LocalWorkerManagerTestFixture fixture, PrintStream out, CountDownLatch stopped, CountDownLatch publish) {
        void stop(ConnectedBoard board) throws IOException {
            fixture.manager.stop(fixture.paths, board, out);
            stopped.countDown();
            try {
                if (!publish.await(15, TimeUnit.SECONDS)) {
                    throw new IOException("cannot resume import test: publication was not released");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("cannot resume import test: publication wait was interrupted", failure);
            }
        }
    }
}
