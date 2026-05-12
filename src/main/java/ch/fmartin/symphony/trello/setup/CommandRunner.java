package ch.fmartin.symphony.trello.setup;

interface CommandRunner {
    CommandResult run(String... command);

    default CommandResult runInteractive(String... command) {
        return run(command);
    }

    default ToolStatus available(String... command) {
        return run(command).success() ? ToolStatus.found() : ToolStatus.unavailable();
    }
}
