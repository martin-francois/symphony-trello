package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

final class ManagedProcessStore {
    private final Path stateHome;

    ManagedProcessStore(Path stateHome) {
        this.stateHome = Objects.requireNonNull(stateHome, "stateHome");
    }

    ManagedProcessFiles files(Path workflowPath) {
        String name = workflowStateName(workflowPath);
        return new ManagedProcessFiles(
                stateHome.resolve(name + ".pid"),
                stateHome.resolve(name + ".log"),
                stateHome.resolve(name + ".err"),
                stateHome.resolve(name + ".lock"));
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
        return new ManagedProcessFiles(
                pidFile, parent.resolve(name + ".log"), parent.resolve(name + ".err"), parent.resolve(name + ".lock"));
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

    void deletePid(Path pidFile) throws IOException {
        Files.deleteIfExists(pidFile);
    }

    private static String workflowStateName(Path workflowPath) {
        Path resolved = resolvedWorkflowPath(workflowPath);
        String hash = sha256(resolved.toString()).substring(0, 12);
        return PathNames.fileName(workflowPath) + "." + hash;
    }

    private static Path resolvedWorkflowPath(Path workflowPath) {
        Path absolute = workflowPath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return absolute;
        }
        try {
            return parent.toRealPath().resolve(absolute.getFileName());
        } catch (IOException ignored) {
            return absolute;
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    record ManagedProcessFiles(Path pidFile, Path stdoutLog, Path stderrLog, Path startLockFile) {
        String displayName() {
            String fileName = PathNames.fileName(pidFile);
            return fileName.substring(0, fileName.length() - ".pid".length());
        }
    }
}
