package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

interface ManagedProcessPlatform {
    ManagedProcessHandle start(
            List<String> command, Path workingDirectory, Map<String, String> environment, Path stdout, Path stderr)
            throws IOException;

    boolean isAlive(long pid);

    boolean isManaged(long pid, Path appHome);

    boolean isManaged(long pid, Path appHome, Path workflowPath);

    boolean stop(long pid, Duration gracefulTimeout, Duration forcedTimeout);

    default boolean appendsToExistingLogs() {
        return true;
    }
}
