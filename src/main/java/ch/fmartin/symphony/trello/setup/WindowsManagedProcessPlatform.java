package ch.fmartin.symphony.trello.setup;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class WindowsManagedProcessPlatform extends ProcessHandleManagedProcessPlatform {
    private final CommandRunner processQuery;

    WindowsManagedProcessPlatform() {
        this(new ProcessCommandRunner());
    }

    WindowsManagedProcessPlatform(CommandRunner processQuery) {
        this.processQuery = processQuery;
    }

    @Override
    public ManagedProcessHandle start(
            List<String> command, Path workingDirectory, Map<String, String> environment, Path stdout, Path stderr)
            throws IOException {
        createParentDirectories(stdout);
        createParentDirectories(stderr);
        ProcessBuilder builder = launcher(command, workingDirectory, environment, stdout, stderr);
        CommandResult result = new ProcessCommandRunner().run(builder);
        if (!result.success()) {
            throw new IOException("cannot start Windows worker: launcher exited with code " + result.exitCode()
                    + "; check PowerShell availability and worker logs");
        }
        return new ManagedProcessHandle(parsePid(result.output()));
    }

    static ProcessBuilder launcher(
            List<String> command, Path workingDirectory, Map<String, String> environment, Path stdout, Path stderr) {
        Map<String, String> workerEnvironment = withoutInstallerCompletionEnvironment(environment);
        ProcessBuilder builder = new ProcessBuilder(
                        powershellExecutable(),
                        "-NoProfile",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-EncodedCommand",
                        encodedStartProcessScript(command, workingDirectory, Map.of(), stdout, stderr))
                .redirectErrorStream(true);
        configureWorkerEnvironment(builder.environment(), workerEnvironment);
        return builder;
    }

    @Override
    public Optional<List<String>> commandArguments(long pid) {
        if (pid <= 0) {
            return Optional.empty();
        }
        try (var input = WindowsManagedProcessPlatform.class.getResourceAsStream("/windows-process-arguments.ps1")) {
            if (input == null) {
                return Optional.empty();
            }
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("@PROCESS_ID@", Long.toString(pid));
            CommandResult result = processQuery.run(
                    powershellExecutable(),
                    "-NoProfile",
                    "-NonInteractive",
                    "-EncodedCommand",
                    Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)));
            if (!result.success()) {
                return Optional.empty();
            }
            var arguments = ConnectedBoardRepository.jsonMapper().readValue(result.output(), String[].class);
            return arguments == null ? Optional.empty() : Optional.of(List.of(arguments));
        } catch (IOException | IllegalArgumentException ignored) {
            // An unavailable process identity never authorizes stopping a process.
            return Optional.empty();
        }
    }

    @Override
    public boolean appendsToExistingLogs() {
        return false;
    }

    @Override
    public boolean stop(long pid, Duration gracefulTimeout, Duration forcedTimeout) {
        List<Long> processIds = Stream.concat(
                        Stream.of(pid),
                        ProcessHandle.of(pid).stream()
                                .flatMap(ProcessHandle::descendants)
                                .map(ProcessHandle::pid))
                .toList();
        if (!super.stop(pid, gracefulTimeout, forcedTimeout)) {
            return false;
        }
        return awaitExited(processIds, forcedTimeout);
    }

    boolean awaitExited(List<Long> processIds, Duration timeout) {
        try (var input = WindowsManagedProcessPlatform.class.getResourceAsStream("/windows-wait-for-exit.ps1")) {
            if (input == null) {
                return false;
            }
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(
                            "@PROCESS_IDS@",
                            processIds.stream().map(String::valueOf).collect(Collectors.joining(",")))
                    .replace("@TIMEOUT_MS@", Long.toString(Math.clamp(timeout.toMillis(), 0, Integer.MAX_VALUE)));
            // An exit code alone does not establish that Windows has released the process's handles.
            return new ProcessCommandRunner(timeout.plusSeconds(10))
                    .run(
                            powershellExecutable(),
                            "-NoProfile",
                            "-NonInteractive",
                            "-EncodedCommand",
                            Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
                    .success();
        } catch (IOException failure) {
            return false;
        }
    }

    static String encodedStartProcessScript(
            List<String> command, Path workingDirectory, Map<String, String> environment, Path stdout, Path stderr) {
        checkArgument(!command.isEmpty(), "command must not be empty");
        var script = new StringBuilder();
        script.append("$ErrorActionPreference = 'Stop'\n");
        script.append("$ProgressPreference = 'SilentlyContinue'\n");
        script.append("[System.Environment]::SetEnvironmentVariable(")
                .append(powerShellString(LocalSetup.INSTALLER_COMPLETION_ENV))
                .append(", $null, 'Process')\n");
        withoutInstallerCompletionEnvironment(environment)
                .forEach((key, value) -> script.append("[System.Environment]::SetEnvironmentVariable(")
                        .append(powerShellString(key))
                        .append(", ")
                        .append(powerShellString(value))
                        .append(", 'Process')\n"));
        script.append("$process = Start-Process -FilePath ")
                .append(powerShellString(command.getFirst()))
                .append(" -ArgumentList ")
                .append(powerShellString(windowsCommandLine(command.subList(1, command.size()))))
                .append(" -WorkingDirectory ")
                .append(powerShellString(
                        workingDirectory.toAbsolutePath().normalize().toString()))
                .append(" -RedirectStandardOutput ")
                .append(powerShellString(stdout.toAbsolutePath().normalize().toString()))
                .append(" -RedirectStandardError ")
                .append(powerShellString(stderr.toAbsolutePath().normalize().toString()))
                .append(" -WindowStyle Hidden -PassThru\n");
        script.append("[Console]::Out.WriteLine($process.Id)\n");
        return Base64.getEncoder().encodeToString(script.toString().getBytes(StandardCharsets.UTF_16LE));
    }

    static String windowsCommandLine(List<String> arguments) {
        return arguments.stream()
                .map(WindowsManagedProcessPlatform::windowsArgument)
                .collect(Collectors.joining(" "));
    }

    private static long parsePid(String output) throws IOException {
        String trimmed = output.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException exception) {
            throw new IOException("Windows worker launcher did not return a process id: " + output, exception);
        }
    }

    private static String powershellExecutable() {
        return "powershell.exe";
    }

    private static void createParentDirectories(Path path) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    private static String powerShellString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String windowsArgument(String argument) {
        if (argument.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuoting =
                argument.chars().anyMatch(character -> Character.isWhitespace(character) || character == '"');
        if (!needsQuoting) {
            return argument;
        }
        var quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < argument.length(); i++) {
            char character = argument.charAt(i);
            if (character == '\\') {
                backslashes++;
            } else if (character == '"') {
                quoted.repeat("\\", backslashes * 2 + 1).append('"');
                backslashes = 0;
            } else {
                quoted.repeat("\\", backslashes).append(character);
                backslashes = 0;
            }
        }
        quoted.repeat("\\", backslashes * 2).append('"');
        return quoted.toString();
    }
}
