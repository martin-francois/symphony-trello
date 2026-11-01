package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.ProtectedPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// Launch inputs shared by worker and external-agent profiles. The wrapper directory stays
/// read-only so an agent cannot replace a trusted wrapper or create its sibling launcher.
final class InstalledLaunchPaths {
    private static final List<String> WRAPPERS =
            List.of("symphony-trello", "symphony-trello.cmd", "symphony-trello.ps1");

    private InstalledLaunchPaths() {}

    static Set<Path> resolve(LocalWorkerPaths paths, Map<String, String> environment) throws IOException {
        Set<Path> launchPaths = new HashSet<>(Set.of(paths.appHome()));
        addCommand(launchPaths, environment.get(LocalSetup.COMMAND_ENV));
        addCommand(launchPaths, environment.get("SYMPHONY_TRELLO_WRAPPER_COMMAND"));
        addInstallerContext(launchPaths, paths);
        return ProtectedPaths.outermost(ProtectedPaths.aliases(launchPaths));
    }

    private static void addCommand(Set<Path> paths, String command) throws IOException {
        if (command == null || command.isBlank()) {
            return;
        }
        Path path = absolute(command);
        paths.add(path);
        addBinDirectory(paths, PathNames.requireParent(path));
    }

    private static void addBinDirectory(Set<Path> paths, Path bin) {
        paths.add(bin);
        for (String wrapper : WRAPPERS) {
            paths.add(bin.resolve(wrapper));
        }
    }

    private static void addInstallerContext(Set<Path> launchPaths, LocalWorkerPaths paths) throws IOException {
        Path context = paths.stateHome().resolve("install-context.properties");
        if (!Files.exists(context)) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        // Installer context is literal key=value text, not Java Properties escape syntax.
        for (String line : Files.readAllLines(context)) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        String app = values.get("app_dir");
        String bin = values.get("bin_dir");
        if (app == null || bin == null) {
            throw new IOException(
                    "cannot resolve installed launch paths: repair app_dir and bin_dir in the installer context");
        }
        if (PathsEqual.samePath(absolute(app), paths.appHome())) {
            addBinDirectory(launchPaths, absolute(bin));
        }
    }

    private static Path absolute(String value) throws IOException {
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) {
                throw new IOException("cannot resolve installed launch paths: use absolute installer paths");
            }
            return path.normalize();
        } catch (InvalidPathException failure) {
            throw new IOException("cannot resolve installed launch paths: repair the installer path", failure);
        }
    }
}
