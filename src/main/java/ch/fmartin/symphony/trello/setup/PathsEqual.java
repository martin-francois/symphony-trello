package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.file.Path;

final class PathsEqual {
    private PathsEqual() {}

    static boolean samePath(Path first, Path second) {
        return canonical(first).equals(canonical(second));
    }

    /// Resolves symlinks when the path exists so selectors such as a symlinked workflow file match
    /// the connected board and managed worker of the real target path.
    static Path canonical(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException e) {
            return absolute;
        }
    }
}
