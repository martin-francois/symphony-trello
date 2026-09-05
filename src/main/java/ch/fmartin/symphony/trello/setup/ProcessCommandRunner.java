package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

final class ProcessCommandRunner implements CommandRunner {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

    private final Duration commandTimeout;

    ProcessCommandRunner() {
        this(COMMAND_TIMEOUT);
    }

    ProcessCommandRunner(Duration commandTimeout) {
        this.commandTimeout = commandTimeout;
    }

    @Override
    public CommandResult run(String... command) {
        Path outputFile = null;
        Process process = null;
        try {
            outputFile = Files.createTempFile("symphony-trello-command-", ".log");
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();
            if (!process.waitFor(commandTimeout)) {
                process.destroyForcibly();
                process.waitFor(Duration.ofSeconds(1));
                return new CommandResult(CommandResult.TIMED_OUT_EXIT_CODE, "command timed out");
            }
            String output = Files.readString(outputFile);
            return new CommandResult(process.exitValue(), output);
        } catch (IOException e) {
            return CommandResult.launchFailed(e.getMessage());
        } catch (InterruptedException e) {
            destroy(process);
            Thread.currentThread().interrupt();
            return new CommandResult(CommandResult.INTERRUPTED_EXIT_CODE, e.getMessage());
        } finally {
            deleteTemporaryOutput(outputFile);
        }
    }

    private static void destroy(Process process) {
        if (process != null) {
            process.destroyForcibly();
        }
    }

    private static void deleteTemporaryOutput(Path outputFile) {
        if (outputFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(outputFile);
        } catch (IOException ignored) {
            // Best effort: command output is diagnostic-only and the OS temp cleaner can remove it later.
        }
    }

    @Override
    public CommandResult runInteractive(String... command) {
        try {
            Process process = new ProcessBuilder(command).inheritIO().start();
            return new CommandResult(process.waitFor(), "");
        } catch (IOException e) {
            return CommandResult.launchFailed(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(CommandResult.INTERRUPTED_EXIT_CODE, e.getMessage());
        }
    }
}
