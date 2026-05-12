package ch.fmartin.symphony.trello.setup;

record CommandResult(int exitCode, String output) {
    boolean success() {
        return exitCode == 0;
    }
}
