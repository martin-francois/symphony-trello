package ch.fmartin.symphony.trello.setup;

record CommandResult(int exitCode, String output, boolean launchFailed) {
    CommandResult(int exitCode, String output) {
        this(exitCode, output, false);
    }

    static CommandResult launchFailed(String output) {
        return new CommandResult(127, output, true);
    }

    boolean success() {
        return exitCode == 0;
    }
}
