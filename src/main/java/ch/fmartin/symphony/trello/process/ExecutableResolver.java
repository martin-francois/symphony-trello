package ch.fmartin.symphony.trello.process;

import com.google.common.base.Splitter;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Finds a tool on `PATH` the way the platform shell does and builds the command that starts it.
/// On Windows the search uses `PATHEXT`, and batch shims start through `cmd.exe`.
public final class ExecutableResolver {
    private static final Splitter POSIX_PATH_SPLITTER = Splitter.on(File.pathSeparator);
    private static final Splitter WINDOWS_LIST_SPLITTER = Splitter.on(';');
    private static final List<String> DEFAULT_WINDOWS_PATH_EXTENSIONS = List.of(".COM", ".EXE", ".BAT", ".CMD");
    private static final List<String> WINDOWS_BATCH_EXTENSIONS = List.of(".cmd", ".bat");

    private final PlatformEnvironment environment;
    private final boolean windows;

    public ExecutableResolver(PlatformEnvironment environment) {
        this.environment = environment;
        this.windows = environment.windows();
    }

    public ExecutableResolver(Map<String, String> environment, String osName) {
        this(PlatformEnvironment.of(environment, osName));
    }

    public static ExecutableResolver forCurrentProcess() {
        return new ExecutableResolver(PlatformEnvironment.current());
    }

    /// Returns the file the shell would start for `tool`, or empty when `PATH` has no match. On
    /// Windows the search tries each `PATHEXT` extension in each `PATH` directory in order.
    public Optional<Path> find(String tool) {
        return validPath(tool)
                .flatMap(toolPath -> windows ? findOnWindows(tool, toolPath) : findOnPosix(tool, toolPath));
    }

    /// Returns `command` in the form `ProcessBuilder` can start. On Windows, a batch file, named
    /// directly or found on `PATH`, starts through `cmd.exe /d /s /c`. Every other command is
    /// returned unchanged.
    public List<String> launchCommand(List<String> command) {
        if (!windows || command.isEmpty()) {
            return command;
        }
        String program = command.getFirst();
        String executable = find(program).map(Path::toString).orElse(program);
        if (!isWindowsBatchFile(executable)) {
            return command;
        }
        List<String> parts = new ArrayList<>();
        parts.add(executable);
        parts.addAll(command.subList(1, command.size()));
        return List.of(
                "cmd.exe",
                "/d",
                "/s",
                "/c",
                "\"" + parts.stream().map(ExecutableResolver::quoteForCmd).collect(Collectors.joining(" ")) + "\"");
    }

    private Optional<Path> findOnWindows(String tool, Path toolPath) {
        if (toolPath.getParent() != null) {
            return Optional.empty();
        }
        return environment.value("PATH").stream()
                .flatMap(this::windowsPathEntries)
                .flatMap(directory -> windowsCandidates(directory, tool))
                .filter(Files::isRegularFile)
                .findFirst();
    }

    private Optional<Path> findOnPosix(String tool, Path toolPath) {
        if (toolPath.getParent() != null) {
            return Files.exists(toolPath) ? Optional.of(toolPath) : Optional.empty();
        }
        return environment.value("PATH").stream()
                .flatMap(path -> POSIX_PATH_SPLITTER.splitToStream(path).map(String::trim))
                .filter(entry -> !entry.isBlank())
                .flatMap(entry -> validPath(entry).stream())
                .map(directory -> directory.resolve(tool))
                .filter(path -> Files.isRegularFile(path) && Files.isExecutable(path))
                .findFirst();
    }

    private Stream<Path> windowsPathEntries(String path) {
        return WINDOWS_LIST_SPLITTER
                .splitToStream(path)
                .map(String::trim)
                .filter(entry -> !entry.isBlank())
                .map(ExecutableResolver::unquote)
                .flatMap(entry -> validPath(entry).stream());
    }

    private Stream<Path> windowsCandidates(Path directory, String tool) {
        if (tool.contains(".")) {
            return Stream.of(directory.resolve(tool));
        }
        return windowsPathExtensions().stream().map(extension -> directory.resolve(tool + extension));
    }

    private List<String> windowsPathExtensions() {
        return environment
                .value("PATHEXT")
                .filter(value -> !value.isBlank())
                .map(value -> WINDOWS_LIST_SPLITTER
                        .splitToStream(value)
                        .map(String::trim)
                        .filter(extension -> !extension.isBlank())
                        .toList())
                .orElse(DEFAULT_WINDOWS_PATH_EXTENSIONS);
    }

    private static boolean isWindowsBatchFile(String executable) {
        String lowerCase = executable.toLowerCase(Locale.ROOT);
        return WINDOWS_BATCH_EXTENSIONS.stream().anyMatch(lowerCase::endsWith);
    }

    private static String quoteForCmd(String value) {
        return "\"" + value.replace("%", "%%").replace("\"", "\\\"") + "\"";
    }

    private static Optional<Path> validPath(String value) {
        try {
            return Optional.of(Path.of(value));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static String unquote(String value) {
        String stripped = value.strip();
        if (stripped.length() < 2) {
            return stripped;
        }
        char first = stripped.charAt(0);
        char last = stripped.charAt(stripped.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }
}
