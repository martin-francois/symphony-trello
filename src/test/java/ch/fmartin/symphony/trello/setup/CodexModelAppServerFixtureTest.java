package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.testsupport.BoundedWait;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

final class CodexModelAppServerFixtureTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final String STUCK_PROBE = """
            echo $$ > "${0}.pid.tmp"
            mv "${0}.pid.tmp" "${0}.pid"
            exec sleep 30
            """;

    @TempDir
    Path directory;

    @EnabledOnOs(OS.LINUX)
    @Test
    void waitsForAnInheritedWritableHandleToClose() throws Exception {
        // given
        Path script = probe("exit 0\n");
        var busy = new CountDownLatch(1);
        var task = new FutureTask<Void>(() -> {
            CodexModelAppServerFixture.awaitExecutable(script, WAIT, busy::countDown);
            return null;
        });
        Thread thread = Thread.ofPlatform().unstarted(task);

        try {
            try (var writer = FileChannel.open(script, StandardOpenOption.WRITE)) {
                assertThat(writer.isOpen())
                        .as("a writable descriptor must prevent executable launch")
                        .isTrue();

                // when
                thread.start();
                assertThat(busy.await(5, TimeUnit.SECONDS))
                        .as("probe must observe ETXTBSY while the writable descriptor is held")
                        .isTrue();
                assertThat(task.isDone())
                        .as("busy fixture must not be returned before its writer closes")
                        .isFalse();
            }

            // then
            assertThat(task.get(5, TimeUnit.SECONDS)).isNull();
        } finally {
            thread.interrupt();
            thread.join(WAIT);
        }
    }

    @Test
    void preparationDoesNotRunTheCatalogScenario() throws Exception {
        // given
        String scenario = "echo scenario > \"${0}.scenario\"\n";

        // when
        Path script = CodexModelAppServerFixture.create(directory, scenario);

        // then
        Path marker = script.resolveSibling(script.getFileName() + ".scenario");
        assertThat(marker).doesNotExist();
        assertThat(new ProcessCommandRunner(WAIT).run(script.toString()).exitCode())
                .isZero();
        assertThat(marker).hasContent("scenario\n");
    }

    @Test
    void rejectsANonzeroProbeExit() throws Exception {
        // given
        Path script = probe("exit 7\n");

        // when
        Throwable failure = catchThrowable(() -> CodexModelAppServerFixture.awaitExecutable(script, WAIT));

        // then
        assertThat(failure).isInstanceOf(IOException.class).hasMessageContaining("expected exit code zero");
    }

    @Test
    void boundsAndTerminatesAStuckProbe() throws Exception {
        // given
        Path script = probe(STUCK_PROBE);

        // when
        try {
            Throwable failure =
                    catchThrowable(() -> CodexModelAppServerFixture.awaitExecutable(script, Duration.ofSeconds(1)));

            // then
            assertThat(failure)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("expected completion before the deadline");
            long pid = probePid(script);
            BoundedWait.until(
                    WAIT,
                    () -> ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty(),
                    "timed-out fixture probe must terminate");
        } finally {
            cleanupProbe(script);
        }
    }

    @Test
    void terminatesAProbeWhenInterrupted() throws Exception {
        // given
        Path script = probe(STUCK_PROBE);
        var task = new FutureTask<Void>(() -> {
            CodexModelAppServerFixture.awaitExecutable(script, Duration.ofSeconds(30));
            return null;
        });
        Thread thread = Thread.ofPlatform().start(task);

        try {
            BoundedWait.until(
                    WAIT, () -> Files.exists(pidFile(script)), "fixture probe must start before interruption");
            long pid = probePid(script);

            // when
            thread.interrupt();

            // then
            assertThatThrownBy(() -> task.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IOException.class)
                    .hasRootCauseInstanceOf(InterruptedException.class);
            BoundedWait.until(
                    WAIT,
                    () -> ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty(),
                    "interrupted fixture probe must terminate");
        } finally {
            thread.interrupt();
            thread.join(WAIT);
            cleanupProbe(script);
        }
    }

    private Path probe(String body) throws IOException {
        Path script = directory.resolve("probe");
        Files.writeString(script, "#!/usr/bin/env bash\n" + body);
        assertThat(script.toFile().setExecutable(true))
                .as("probe script must be executable")
                .isTrue();
        return script;
    }

    private static Path pidFile(Path script) {
        return script.resolveSibling(script.getFileName() + ".pid");
    }

    private static long probePid(Path script) throws IOException {
        return Long.parseLong(Files.readString(pidFile(script)).strip());
    }

    private static void cleanupProbe(Path script) throws IOException {
        if (Files.exists(pidFile(script))) {
            ProcessHandle.of(Long.parseLong(Files.readString(pidFile(script)).strip()))
                    .ifPresent(ProcessHandle::destroyForcibly);
        }
    }
}
