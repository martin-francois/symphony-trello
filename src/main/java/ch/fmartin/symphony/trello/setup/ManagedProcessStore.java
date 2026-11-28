package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.Sha3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class ManagedProcessStore {
    private static final String WORKFLOW_PATH_SUFFIX = ".workflow";

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
            return stream.filter(path -> PathNames.fileName(path).endsWith(".pid"))
                    .sorted()
                    .toList();
        }
    }

    /// Includes failed-start records without a PID so stop can finish their lifecycle too.
    List<ManagedProcessFiles> stoppableFiles() throws IOException {
        if (!Files.isDirectory(stateHome)) {
            return List.of();
        }
        try (var stream = Files.list(stateHome)) {
            return stream.filter(ManagedProcessStore::isProcessStateFile)
                    .sorted()
                    .map(this::filesFromStateFile)
                    .distinct()
                    .toList();
        }
    }

    private static boolean isProcessStateFile(Path path) {
        String name = PathNames.fileName(path);
        return name.endsWith(".pid") || name.endsWith(WORKFLOW_PATH_SUFFIX);
    }

    private ManagedProcessFiles filesFromStateFile(Path path) {
        String name = PathNames.fileName(path);
        String stem = name.substring(0, name.lastIndexOf('.'));
        return filesFor(stateHome, stem);
    }

    boolean deleteStoppedProcessFiles(ManagedProcessFiles files) throws IOException {
        boolean deletedPid = deletePid(files.pidFile());
        deleteWorkflowPathRecord(files);
        return deletedPid;
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
            String text = Files.readString(pidFile).trim();
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

    void rotateLogsForReplacedBoards(ConnectedBoard board, List<ConnectedBoard> replacedBoards) throws IOException {
        for (ConnectedBoard replaced : replacedBoards) {
            if (PathsEqual.samePath(replaced.workflowPath(), board.workflowPath())
                    && !replaced.boardId().equals(board.boardId())) {
                rotateLogsForNewBoardIdentity(replaced.workflowPath());
            }
        }
    }

    /// Moves the worker logs aside when a workflow path is reused for a different Trello board, so
    /// diagnostics for the new board do not surface the previous board's history. Rotated files use
    /// a suffix that the diagnostics log selection does not match.
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
                pidFile,
                parent.resolve(name + ".log"),
                parent.resolve(name + ".err"),
                parent.resolve(name + ".lock"),
                parent.resolve(name + WORKFLOW_PATH_SUFFIX));
    }

    /// Remembers which workflow file a start attempt was for, next to the pid and log files whose
    /// names hash that path. Diagnostics without a selector reads the record back, so the lookup
    /// hint that a failed start of an unconnected workflow prints resolves as printed. The record
    /// keeps the path as the start was given it, absolute and normalized but with symbolic links
    /// in place, because the tokens in that hint come from the same spelling. The write is atomic,
    /// so a reader never sees a half-written record; see
    /// docs/adr/0154-record-start-attempts-in-the-state-home.md.
    void recordWorkflowPath(Path workflowPath) throws IOException {
        Path record = files(workflowPath).workflowPathFile();
        Path temporary = AtomicFiles.siblingTemporaryFile(record);
        try {
            Files.writeString(
                    temporary, workflowPath.toAbsolutePath().normalize().toString());
            AtomicFiles.replace(temporary, record);
        } finally {
            AtomicFiles.deleteQuietly(temporary);
        }
    }

    /// Forgets a start attempt once its worker is stopped, so the state home does not keep
    /// records of workflows nobody runs any more.
    boolean deleteWorkflowPathRecord(ManagedProcessFiles files) throws IOException {
        return Files.deleteIfExists(files.workflowPathFile());
    }

    /// Every workflow file a start attempt recorded, in file name order, missing files included.
    List<Path> recordedWorkflowPaths() throws IOException {
        if (!Files.isDirectory(stateHome)) {
            return List.of();
        }
        List<Path> recorded = new ArrayList<>();
        try (var stream = Files.list(stateHome)) {
            for (Path record : stream.filter(path -> PathNames.fileName(path).endsWith(WORKFLOW_PATH_SUFFIX))
                    .sorted()
                    .toList()) {
                recordedWorkflowPath(record).ifPresent(recorded::add);
            }
        }
        return List.copyOf(recorded);
    }

    /// A record that cannot be read or does not hold a path is skipped: it is a hint for
    /// diagnostics, not state the worker depends on.
    private static Optional<Path> recordedWorkflowPath(Path record) {
        try {
            String text = Files.readString(record);
            if (text.isEmpty()) {
                return Optional.empty();
            }
            Path path = Path.of(text);
            return path.isAbsolute() ? Optional.of(path) : Optional.empty();
        } catch (IOException | InvalidPathException unreadable) {
            return Optional.empty();
        }
    }

    record ManagedProcessFiles(
            Path pidFile, Path stdoutLog, Path stderrLog, Path processLockFile, Path workflowPathFile) {}
}
