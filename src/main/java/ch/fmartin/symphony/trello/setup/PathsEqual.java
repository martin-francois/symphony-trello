package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;

final class PathsEqual {
    private PathsEqual() {}

    static boolean samePath(Path first, Path second) {
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
    }
}
