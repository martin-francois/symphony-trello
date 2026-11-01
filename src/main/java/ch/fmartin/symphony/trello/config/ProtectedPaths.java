package ch.fmartin.symphony.trello.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/// Shared path rules for worker and external-client profiles. Nested denies must be collapsed:
/// bubblewrap cannot mount a denied file beneath an already denied directory.
public final class ProtectedPaths {
    private ProtectedPaths() {}

    public static Set<Path> aliases(Set<Path> paths) throws IOException {
        Set<Path> result = new HashSet<>();
        for (Path path : paths) {
            Path absolute = path.toAbsolutePath().normalize();
            result.add(absolute);
            Path parent = absolute.getParent();
            Path name = absolute.getFileName();
            if (Files.exists(absolute)) {
                result.add(absolute.toRealPath());
            } else if (parent != null && name != null && Files.exists(parent)) {
                result.add(parent.toRealPath().resolve(name));
            }
        }
        return Set.copyOf(result);
    }

    public static Set<Path> outermost(Set<Path> paths) {
        Set<Path> result = new HashSet<>(paths);
        result.removeIf(path -> hasAncestor(path, paths));
        return Set.copyOf(result);
    }

    private static boolean hasAncestor(Path path, Set<Path> paths) {
        return paths.stream().anyMatch(parent -> !parent.equals(path) && path.startsWith(parent));
    }
}
