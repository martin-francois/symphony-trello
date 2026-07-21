package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;

@NullMarked
final class PathNames {
    private PathNames() {}

    static String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }
}
