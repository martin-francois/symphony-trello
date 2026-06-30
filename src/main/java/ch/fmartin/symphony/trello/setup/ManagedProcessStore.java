package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.Sha3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

final class ManagedProcessStore {
    private final Path stateHome;

    ManagedProcessStore(Path stateHome) {
        this.stateHome = Objects.requireNonNull(stateHome, "stateHome");
    }

    ManagedProcessFiles files(Path workflowPath) {
        String name = workflowStateName(workflowPath);
        return filesFor(stateHome, name);
    }

    List<Path> pidFiles() throws IOException {
        if (!Files.isDirectory(stateHome)) {
            return List.of();
        }
        try (var stream = Files.list(stateHome)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".pid"))
                    .sorted()
                    .toList();
        }
    }

    ManagedProcessFiles filesFromPidFile(Path pidFile) {
        String fileName = PathNames.fileName(pidFile);
        String name = fileName.substring(0, fileName.length() - ".pid".length());
        Path parent = pidFile.getParent();
        if (parent == null) {
            parent = stateHome;
        }
        return filesFor(parent, name, pidFile);
    }

    Long readPid(Path pidFile) {
        try {
            String text = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
            return text.isBlank() ? null : Long.parseLong(text);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    void writePid(Path pidFile, long pid) throws IOException {
        Path parent = pidFile.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(pidFile, Long.toString(pid), StandardCharsets.US_ASCII);
    }

    boolean deletePid(Path pidFile) throws IOException {
        return Files.deleteIfExists(pidFile);
    }

    /**
     * Moves the worker logs aside when a workflow path is reused for a different Trello board, so
     * diagnostics for the new board do not surface the previous board's history. Rotated files use
     * a suffix that the diagnostics log selection does not match.
     */
    void rotateLogsForNewBoardIdentity(Path workflowPath) throws IOException {
        ManagedProcessFiles files = files(workflowPath);
        rotateLog(files.stdoutLog());
        rotateLog(files.stderrLog());
    }

    private static void rotateLog(Path log) throws IOException {
        if (Files.isRegularFile(log)) {
            Files.move(
                    log,
                    log.resolveSibling(PathNames.fileName(log) + ".previous"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String workflowStateName(Path workflowPath) {
        Path resolved = resolvedWorkflowPath(workflowPath);
        String hash = Sha3.sha3_256(resolved.toString()).substring(0, 12);
        return PathNames.fileName(resolved) + "." + hash;
    }

    private static Path resolvedWorkflowPath(Path workflowPath) {
        Path absolute = workflowPath.toAbsolutePath().normalize();
        try {
            // Resolve file and directory symlinks so a symlinked workflow selector shares the
            // managed pid and log files of its target workflow.
            return absolute.toRealPath();
        } catch (IOException ignored) {
            Path parent = absolute.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                return absolute;
            }
            try {
                return parent.toRealPath().resolve(absolute.getFileName());
            } catch (IOException alsoIgnored) {
                return absolute;
            }
        }
    }

    private static ManagedProcessFiles filesFor(Path parent, String name) {
        return filesFor(parent, name, parent.resolve(name + ".pid"));
    }

    private static ManagedProcessFiles filesFor(Path parent, String name, Path pidFile) {
        return new ManagedProcessFiles(
                pidFile, parent.resolve(name + ".log"), parent.resolve(name + ".err"), parent.resolve(name + ".lock"));
    }

    record ManagedProcessFiles(Path pidFile, Path stdoutLog, Path stderrLog, Path processLockFile) {}
}
