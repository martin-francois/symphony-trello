package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.testsupport.WindowsShimFixtures.NPM_PATHEXT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.process.ExecutableResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InteractiveCommandCancellationTest {
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(5);
    private static final String SHIM_NAME = "interactive-waiter";

    @Test
    void interruptionStopsTheInteractiveWrapperAndItsTool(@TempDir Path directory) throws Exception {
        // given
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path bash = Path.of("/bin/bash");
        assumeTrue(windows || Files.isExecutable(bash));
        Path ready = directory.resolve("ready.pids");
        Path wrapper = directory.resolve(SHIM_NAME + (windows ? ".cmd" : ".sh"));
        String java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toString();
        List<String> tool = List.of(
                java,
                "-cp",
                Path.of(InteractiveWaitingTool.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI())
                        .toString(),
                InteractiveWaitingTool.class.getName(),
                ready.toString());
        String command = tool.stream().map(value -> quote(value, windows)).collect(Collectors.joining(" "));
        // A background child plus wait keeps Bash as the wrapper, matching cmd.exe's process ownership.
        Files.writeString(wrapper, windows ? "@echo off\r\n" + command + "\r\n" : command + " &\nwait\n");
        var runner = new ProcessCommandRunner(
                WAIT_LIMIT,
                new ExecutableResolver(
                        Map.of("PATH", directory.toString(), "PATHEXT", NPM_PATHEXT), System.getProperty("os.name")));
        String[] launch = windows ? new String[] {SHIM_NAME} : new String[] {bash.toString(), wrapper.toString()};
        var result = new AtomicReference<CommandResult>();
        var interruptPreserved = new AtomicBoolean();
        var caller = new Thread(() -> {
            result.set(runner.runInteractive(launch));
            interruptPreserved.set(Thread.currentThread().isInterrupted());
        });
        List<ProcessHandle> owned = List.of();

        // when
        try {
            caller.start();
            owned = awaitOwnedProcesses(ready);
            caller.interrupt();
            boolean returned = caller.join(WAIT_LIMIT);
            boolean stopped = awaitStopped(owned);

            // then
            assertThat(returned)
                    .as("the interrupted caller returns within the cleanup bound")
                    .isTrue();
            assertThat(result.get().exitCode()).isEqualTo(CommandResult.INTERRUPTED_EXIT_CODE);
            assertThat(interruptPreserved.get())
                    .as("the caller keeps its interrupted status")
                    .isTrue();
            assertThat(stopped)
                    .as("both the interactive wrapper and its tool terminate")
                    .isTrue();
        } finally {
            // Cleanup also runs when the old implementation leaves both fixture processes alive.
            for (ProcessHandle process : owned) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.onExit().get(WAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
            caller.interrupt();
            caller.join(WAIT_LIMIT);
        }
    }

    private static List<ProcessHandle> awaitOwnedProcesses(Path ready) throws Exception {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(ready)) {
                List<String> pids = Files.readAllLines(ready);
                if (pids.size() == 2) {
                    return pids.stream()
                            .map(Long::parseLong)
                            .map(pid -> ProcessHandle.of(pid).orElseThrow())
                            .toList();
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the interactive tool did not publish its process IDs within " + WAIT_LIMIT);
    }

    private static boolean awaitStopped(List<ProcessHandle> processes) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (processes.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        return processes.stream().noneMatch(ProcessHandle::isAlive);
    }

    private static String quote(String value, boolean windows) {
        return windows ? "\"" + value + "\"" : "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
