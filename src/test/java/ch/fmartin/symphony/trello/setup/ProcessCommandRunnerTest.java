package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ProcessCommandRunnerTest {
    private static final String BASH = "/bin/bash";
    private static final String OVERLAY_KEY = "SYMPHONY_TRELLO_COMMAND_RUNNER_TEST_OVERLAY";

    @BeforeAll
    static void assumeBash() {
        assumeTrue(Files.isExecutable(Path.of(BASH)));
    }

    @Test
    void runCapturesLargeOutputWithoutBlockingOnProcessPipe() {
        // given
        ProcessCommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(2));

        // when
        CommandResult result = runner.run(BASH, "-lc", "yes output | head -c 200000");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).hasSize(200000);
    }

    @Test
    void runAppliesEnvironmentOverlayWithoutReplacingInheritedEnvironment() {
        // given
        assumeTrue(System.getenv(OVERLAY_KEY) == null);
        ProcessCommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(2));

        // when
        CommandResult result = runner.run(
                Map.of(OVERLAY_KEY, "overlay-value"),
                BASH,
                "-c",
                "printf '%s|%s' \"$" + OVERLAY_KEY + "\" \"${PATH:+path-present}\"");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("overlay-value|path-present");
        assertThat(System.getenv(OVERLAY_KEY)).isNull();
    }

    @Test
    void runRemovesSelectedInheritedEnvironmentValuesBeforeApplyingOverrides() {
        // given
        assumeTrue(System.getenv("HOME") != null);
        assumeTrue(System.getenv(OVERLAY_KEY) == null);
        ProcessCommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(2));
        CommandEnvironment environment = new CommandEnvironment(Map.of(OVERLAY_KEY, "overlay-value"), Set.of("HOME"));

        // when
        CommandResult result = runner.run(
                environment,
                BASH,
                "-c",
                "printf '%s|%s|%s' \"$" + OVERLAY_KEY + "\" \"${HOME-unset}\" \"${PATH:+path-present}\"");

        // then
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("overlay-value|unset|path-present");
        assertThat(System.getenv("HOME")).isNotNull();
        assertThat(System.getenv(OVERLAY_KEY)).isNull();
    }

    @Test
    void runTimesOutHungCommands() {
        // given
        ProcessCommandRunner runner = new ProcessCommandRunner(Duration.ofMillis(50));

        // when
        CommandResult result = runner.run(BASH, "-lc", "while :; do :; done");

        // then
        assertThat(result.exitCode()).isEqualTo(CommandResult.TIMED_OUT_EXIT_CODE);
    }

    @Test
    void runDestroysChildProcessWhenInterrupted() throws Exception {
        // given
        Path pidFile = Files.createTempFile("symphony-trello-command-pid-", ".txt");
        Files.deleteIfExists(pidFile);
        try {
            ProcessCommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(30));
            AtomicReference<CommandResult> result = new AtomicReference<>();
            Thread runThread = new Thread(
                    () -> result.set(runner.run(
                            BASH, "-lc", "echo $$ > " + shellQuote(pidFile) + "; while :; do sleep 1; done")),
                    "process-command-runner-interrupt-test");

            // when
            runThread.start();
            long childPid = waitForPid(pidFile);
            runThread.interrupt();
            runThread.join(TimeUnit.SECONDS.toMillis(5));

            // then
            assertThat(runThread.isAlive()).isFalse();
            assertThat(result.get().exitCode()).isEqualTo(CommandResult.INTERRUPTED_EXIT_CODE);
            assertThat(processIsAlive(childPid)).isFalse();
        } finally {
            Files.deleteIfExists(pidFile);
        }
    }

    private static long waitForPid(Path pidFile) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(pidFile)) {
                String pid = Files.readString(pidFile).trim();
                if (isNumericPid(pid)) {
                    return Long.parseLong(pid);
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("command did not write a pid file");
    }

    private static boolean isNumericPid(String pid) {
        if (pid.isEmpty()) {
            return false;
        }
        for (int i = 0; i < pid.length(); i++) {
            if (!Character.isDigit(pid.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean processIsAlive(long pid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            if (!alive) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    private static String shellQuote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }
}
