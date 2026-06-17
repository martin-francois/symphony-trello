package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ProcessCommandRunner implements CommandRunner {
    @Override
    public CommandResult run(String... command) {
        try {
            Process process =
                    new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(process.waitFor(), output);
        } catch (IOException e) {
            return CommandResult.launchFailed(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(130, e.getMessage());
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
            return new CommandResult(130, e.getMessage());
        }
    }
}
