package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Same-folder temporary files and atomic replacement for setup-owned files next to a workflow or in
/// the config directory. Keeping the temporary file in the target's folder keeps the final move on one
/// file system, so it can be atomic.
@NullMarked
final class AtomicFiles {
    private AtomicFiles() {}

    static Path siblingTemporaryFile(Path target) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("the file has no parent folder");
        }
        return Files.createTempFile(parent, "." + PathNames.fileName(target) + ".", ".tmp");
    }

    /// Moves `source` over `target` atomically. Moving replaces a link at `target` instead of writing
    /// through it.
    static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("atomic replacement is not supported in this folder", e);
        }
    }

    /// Deletes a leftover temporary file. A failure leaves a hidden file behind, which is harmless and
    /// must not hide the result of the operation that created it.
    static boolean deleteQuietly(@Nullable Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            return false;
        }
    }
}
