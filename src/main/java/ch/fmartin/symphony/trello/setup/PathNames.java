package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;

final class PathNames {
    private PathNames() {}

    static String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }
}
