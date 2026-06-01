package ch.fmartin.symphony.trello.setup;

import static com.google.common.base.Preconditions.checkArgument;

record LocalPort(int value) {
    static final int MIN = 1;
    static final int MAX = 65_535;

    LocalPort {
        checkArgument(isValid(value), "Local port must be between %s and %s: %s", MIN, MAX, value);
    }

    static boolean isValid(int value) {
        return value >= MIN && value <= MAX;
    }

    static void validateCliServerPort(int value) {
        if (!isValid(value)) {
            throw new TrelloBoardSetupException("setup_invalid_port", "--server-port must be between 1 and 65535.");
        }
    }

    static void validateWorkflowServerPort(int value, String label) {
        if (value < 0 || value > MAX) {
            throw new TrelloBoardSetupException("setup_invalid_server_port", label + " must be between 0 and 65535");
        }
    }
}
