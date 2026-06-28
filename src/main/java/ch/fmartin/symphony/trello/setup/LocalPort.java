package ch.fmartin.symphony.trello.setup;

import static com.google.common.base.Preconditions.checkArgument;

record LocalPort(int value) {
    static final int MIN = 1;
    static final int MIN_USER_SERVICE = 1024;
    static final int MAX = 65_535;
    private static final String CLI_SERVER_PORT_RANGE =
            "--server-port must be between 1024 and 65535 for local HTTP status.";
    private static final String WORKFLOW_SERVER_PORT_RANGE = "must be between 0 and " + MAX;

    LocalPort {
        checkArgument(isValid(value), "Local port must be between %s and %s: %s", MIN, MAX, value);
    }

    static boolean isValid(int value) {
        return value >= MIN && value <= MAX;
    }

    static void validateCliServerPort(int value) {
        if (!isValid(value)) {
            throw new TrelloBoardSetupException("setup_invalid_port", CLI_SERVER_PORT_RANGE);
        }
        if (value < MIN_USER_SERVICE) {
            throw new TrelloBoardSetupException("setup_invalid_server_port", CLI_SERVER_PORT_RANGE);
        }
    }

    static void validateWorkflowServerPort(int value, String label) {
        if (value < 0 || value > MAX) {
            throw new TrelloBoardSetupException("setup_invalid_server_port", label + " " + WORKFLOW_SERVER_PORT_RANGE);
        }
    }
}
