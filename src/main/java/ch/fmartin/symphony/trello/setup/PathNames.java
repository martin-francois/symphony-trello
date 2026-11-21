package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class PathNames {
    private PathNames() {}

    static Path requireParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("cannot use file path: expected a path with a parent directory");
        }
        return parent;
    }

    static String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }
}
