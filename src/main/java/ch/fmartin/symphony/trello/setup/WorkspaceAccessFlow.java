package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class WorkspaceAccessFlow {
    List<Path> resolve(LocalSetup.Options options, Terminal terminal) throws IOException {
        if (options.nonInteractive()) {
            for (Path root : options.additionalWritableRoots()) {
                rejectBroadAccessPath(root, options.allowAllPaths());
            }
            return options.additionalWritableRoots();
        }

        List<Path> allowedPaths = new ArrayList<>();
        terminal.info("");
        terminal.info("Workspace access");
        terminal.info("This controls which files/folders sandboxed Trello card runs may use.");
        terminal.info("Default workspace path:");
        terminal.info("  " + options.workspaceRoot());
        for (Path path : options.additionalWritableRoots()) {
            if (isBroadAccessPath(path) && !options.allowAllPaths() && !confirmBroadAccess(terminal)) {
                continue;
            }
            allowedPaths.add(path);
        }
        if (allowedPaths.isEmpty()
                && PromptSupport.yes(
                        terminal, "Allow cards to access local paths outside Symphony's default workspace? [y/N] ")) {
            terminal.info("  Added paths grant read/write access.");
            terminal.info("  Directories apply recursively.");
            terminal.info("  Use absolute paths, ~, or paths relative to the current directory.");
            terminal.info(
                    "  Local setup relies on Codex sandbox behavior and normal OS permissions, not OS-level filesystem isolation.");
            String answer = terminal.readLine("Additional paths, comma-separated: ");
            for (String part : csv(answer)) {
                Path path = resolveAccessPath(part, options.callerDirectory());
                if (isBroadAccessPath(path) && !confirmBroadAccess(terminal)) {
                    continue;
                }
                allowedPaths.add(path);
            }
        }
        if (!allowedPaths.isEmpty()) {
            terminal.info("  OK  Extra allowed paths: " + allowedPaths);
        }
        return List.copyOf(allowedPaths);
    }

    static Path resolveAccessPath(String value, Path callerDirectory) {
        if ("~".equals(value)) {
            return Path.of(System.getProperty("user.home")).normalize();
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            String remainder = value.substring(2).replace('\\', '/');
            return Path.of(System.getProperty("user.home")).resolve(remainder).normalize();
        }
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : callerDirectory.resolve(path)).normalize();
    }

    static Path resolveAccessPath(Path value, Path callerDirectory) {
        return resolveAccessPath(value.toString(), callerDirectory);
    }

    static void rejectBroadAccessPath(Path path, boolean allowAllPaths) {
        if (isBroadAccessPath(path) && !allowAllPaths) {
            throw new TrelloBoardSetupException(
                    "setup_broad_path_requires_confirmation",
                    "Refusing to allow the whole filesystem. Re-run with --allow-all-paths if that is intentional.");
        }
    }

    static boolean isBroadAccessPath(Path path) {
        return path.getParent() == null && path.isAbsolute();
    }

    private static boolean confirmBroadAccess(Terminal terminal) throws IOException {
        terminal.info("");
        terminal.info(
                "Adding / grants broad recursive read/write access to all files and folders Symphony can normally access.");
        terminal.info("If you meant your home directory or one project, use ~ or that project directory instead.");
        return PromptSupport.yes(terminal, "Allow / anyway? [y/N] ");
    }

    private static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Pattern.compile(",")
                .splitAsStream(value)
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }
}
