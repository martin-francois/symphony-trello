package ch.fmartin.symphony.trello.process;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/// Finds a tool on `PATH` the way the platform shell does and builds the command that starts it.
///
/// On Windows, `ProcessBuilder` hands a bare name to `CreateProcess`, which appends only `.exe`.
/// npm installs Codex as a `codex.cmd` batch shim, so a bare `codex` does not start even though it
/// works in PowerShell. This class searches `PATH` with `PATHEXT` and starts batch shims through
/// `cmd.exe`. ADR 0114 records why.
public final class ExecutableResolver {
    private static final Splitter POSIX_PATH_SPLITTER = Splitter.on(File.pathSeparator);
    private static final Splitter WINDOWS_LIST_SPLITTER = Splitter.on(';');
    private static final List<String> DEFAULT_WINDOWS_PATH_EXTENSIONS = List.of(".COM", ".EXE", ".BAT", ".CMD");
    private static final List<String> WINDOWS_BATCH_EXTENSIONS = List.of(".cmd", ".bat");

    /// `cmd.exe` expands `%` and, with delayed expansion, `!` inside double quotes, and a quote or
    /// line break ends the quoted argument. On a Windows CI runner it also split quoted arguments at
    /// `&`, `|`, `<`, `>` and `^`. An argument with any of these cannot reach a batch shim unchanged;
    /// ADR 0114 lists the characters that were shown to pass.
    private static final CharMatcher CMD_UNSAFE_CHARACTERS = CharMatcher.anyOf("\"%!&|<>^\r\n");

    private static final CharMatcher BACKSLASH = CharMatcher.is('\\');

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
    /// directly or found on `PATH`, starts through `cmd.exe /d /s /c` with every argument quoted.
    /// Every other command is returned unchanged.
    ///
    /// @throws IOException when a batch-file argument contains a character that `cmd.exe` would
    ///     change, so the launch fails instead of passing a different argument
    public List<String> launchCommand(List<String> command) throws IOException {
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
        List<String> quoted = new ArrayList<>();
        for (String part : parts) {
            quoted.add(quoteForCmd(part));
        }
        // With /s, cmd.exe strips exactly the outer quote pair and runs the rest as written.
        return List.of(commandInterpreter(), "/d", "/s", "/c", "\"" + String.join(" ", quoted) + "\"");
    }

    /// `ComSpec` names the system's `cmd.exe`. A bare `cmd.exe` would let `CreateProcess` pick up a
    /// copy from the current directory first.
    private String commandInterpreter() {
        return environment
                .value("ComSpec")
                .filter(value -> validPath(value).map(Path::isAbsolute).orElse(false))
                .orElse("cmd.exe");
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

    private static String quoteForCmd(String value) throws IOException {
        if (CMD_UNSAFE_CHARACTERS.matchesAnyOf(value)) {
            throw new IOException("cannot pass an argument containing any of \" % ! & | < > ^ or a line break "
                    + "to a Windows batch file through cmd.exe");
        }
        // The program reads `\"` as a literal quote, so trailing backslashes are doubled to keep
        // them in front of the closing quote.
        int trailingBackslashes =
                value.length() - BACKSLASH.trimTrailingFrom(value).length();
        return "\"" + value + "\\".repeat(trailingBackslashes) + "\"";
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
