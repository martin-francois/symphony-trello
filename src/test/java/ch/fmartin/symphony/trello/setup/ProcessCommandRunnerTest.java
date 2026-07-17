package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.common.base.CharMatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class ProcessCommandRunnerTest {
    private static final String BASH = "/bin/bash";
    private static final CharMatcher BMP_DIGIT = CharMatcher.forPredicate(Character::isDigit);

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
            assertThat(runThread.join(Duration.ofSeconds(5)))
                    .as("the interrupted command-runner thread terminates within 5 seconds")
                    .isTrue();

            // then
            assertThat(result.get().exitCode()).isEqualTo(CommandResult.INTERRUPTED_EXIT_CODE);
            assertThat(processIsAlive(childPid))
                    .as("the interrupted command runner terminates its child process")
                    .isFalse();
        } finally {
            Files.deleteIfExists(pidFile);
        }
    }

    @CsvSource(
            delimiter = '|',
            value = {"1|true", "00123|true", "١٢٣|true", "''|false", "12a|false", "-1|false", "12 3|false", "𝟙|false"})
    @ParameterizedTest(name = "[{index}] PID candidate <{0}> is numeric: {1}")
    void numericPidMatchesBmpDigitContract(String pid, boolean expected) {
        // given

        // when
        boolean numeric = isNumericPid(pid);

        // then
        assertThat(numeric).as("PID candidate <%s> numeric classification", pid).isEqualTo(expected);
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
        return !pid.isEmpty() && BMP_DIGIT.matchesAllOf(pid);
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
