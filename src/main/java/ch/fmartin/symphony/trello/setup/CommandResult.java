package ch.fmartin.symphony.trello.setup;

record CommandResult(int exitCode, String output, boolean launchFailed) {
    static final int COMMAND_NOT_FOUND_EXIT_CODE = 127;

    CommandResult(int exitCode, String output) {
        this(exitCode, output, false);
    }

    static CommandResult launchFailed(String output) {
        return new CommandResult(COMMAND_NOT_FOUND_EXIT_CODE, output, true);
    }

    boolean success() {
        return exitCode == 0;
    }
}
