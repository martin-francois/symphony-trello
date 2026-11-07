package ch.fmartin.symphony.trello.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;

/// Confirms native process-object signaling, which follows the exit-code change on Windows.
public final class WindowsProcessExit {
    private WindowsProcessExit() {}

    public static boolean awaitExited(List<Long> processIds, Duration timeout) {
        boolean interrupted = Thread.interrupted();
        Process waiter = null;
        try (var input = WindowsProcessExit.class.getResourceAsStream("/windows-wait-for-exit.ps1")) {
            if (input == null) {
                return false;
            }
            var ids = new StringJoiner(",");
            for (long pid : processIds) {
                ids.add(Long.toString(pid));
            }
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("@PROCESS_IDS@", ids.toString())
                    .replace("@TIMEOUT_MS@", Long.toString(Math.clamp(timeout.toMillis(), 0, Integer.MAX_VALUE)));
            waiter = new ProcessBuilder(
                            "powershell.exe",
                            "-NoProfile",
                            "-NonInteractive",
                            "-EncodedCommand",
                            Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            long deadline = System.nanoTime() + timeout.plusSeconds(10).toNanos();
            while (true) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    return waiter.waitFor(Duration.ofNanos(remaining)) && waiter.exitValue() == 0;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } catch (IOException failure) {
            return false;
        } finally {
            if (waiter != null && waiter.isAlive()) {
                waiter.descendants().forEach(ProcessHandle::destroyForcibly);
                waiter.destroyForcibly();
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
