package ch.fmartin.symphony.trello.setup;

final class ShellCommandRenderer {
    static final String DEFAULT_CLI_COMMAND = "symphony-trello";
    static final String SHELL_PROPERTY = "symphony.trello.shell";

    private ShellCommandRenderer() {}

    static String executable(String command, String shell) {
        if ("powershell".equalsIgnoreCase(shell) && !DEFAULT_CLI_COMMAND.equals(command)) {
            return "& " + argument(command, shell);
        }
        return DEFAULT_CLI_COMMAND.equals(command) ? command : argument(command, shell);
    }

    static String argument(String value, String shell) {
        if ("powershell".equalsIgnoreCase(shell)) {
            return "'" + value.replace("'", "''") + "'";
        }
        if ("cmd".equalsIgnoreCase(shell)) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
