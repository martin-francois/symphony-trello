package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

final class WindowsManagedProcessPlatform extends ProcessHandleManagedProcessPlatform {
    @Override
    public ManagedProcessHandle start(
            List<String> command, Path workingDirectory, Map<String, String> environment, Path stdout, Path stderr)
            throws IOException {
        createParentDirectories(stdout);
        createParentDirectories(stderr);
        ProcessBuilder builder = new ProcessBuilder(
                        powershellExecutable(),
                        "-NoProfile",
                        "-NonInteractive",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-EncodedCommand",
                        encodedStartProcessScript(command, workingDirectory, environment, stdout, stderr))
                .redirectErrorStream(true);
        Process launcher = builder.start();
        String output = new String(launcher.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            int exitCode = launcher.waitFor();
            if (exitCode != 0) {
                throw new IOException("Windows worker launcher failed with exit code " + exitCode + ": " + output);
            }
            return new ManagedProcessHandle(parsePid(output));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting Windows worker", exception);
        }
    }

    @Override
    public boolean appendsToExistingLogs() {
        return false;
    }

    static String encodedStartProcessScript(
            List<String> command, Path workingDirectory, Map<String, String> environment, Path stdout, Path stderr) {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        StringBuilder script = new StringBuilder();
        script.append("$ErrorActionPreference = 'Stop'\n");
        environment.forEach((key, value) -> script.append("[System.Environment]::SetEnvironmentVariable(")
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
                .reduce("", (left, right) -> {
                    if (left.isEmpty()) {
                        return right;
                    }
                    return left + " " + right;
                });
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
        StringBuilder quoted = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < argument.length(); i++) {
            char character = argument.charAt(i);
            if (character == '\\') {
                backslashes++;
            } else if (character == '"') {
                quoted.append("\\".repeat(backslashes * 2 + 1)).append('"');
                backslashes = 0;
            } else {
                quoted.append("\\".repeat(backslashes)).append(character);
                backslashes = 0;
            }
        }
        quoted.append("\\".repeat(backslashes * 2)).append('"');
        return quoted.toString();
    }
}
