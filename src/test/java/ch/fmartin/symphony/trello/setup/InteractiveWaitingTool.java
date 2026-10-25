package ch.fmartin.symphony.trello.setup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/// Publishes both owned PIDs before waiting, so cancellation tests interrupt a running tool.
public final class InteractiveWaitingTool {
    private InteractiveWaitingTool() {}

    public static void main(String[] arguments) throws Exception {
        ProcessHandle tool = ProcessHandle.current();
        Path ready = Path.of(arguments[0]);
        Path pending = ready.resolveSibling("ready.tmp");
        Files.writeString(
                pending, tool.pid() + "\n" + tool.parent().orElseThrow().pid() + "\n");
        Files.move(pending, ready, StandardCopyOption.ATOMIC_MOVE);
        Thread.sleep(Duration.ofMinutes(1));
    }
}
