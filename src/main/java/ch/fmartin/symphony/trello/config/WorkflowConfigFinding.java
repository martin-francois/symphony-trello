package ch.fmartin.symphony.trello.config;

public record WorkflowConfigFinding(String path, Kind kind, String input, String message, boolean strict) {
    public enum Kind {
        FRACTIONAL,
        OUT_OF_INT_RANGE,
        NOT_A_NUMBER,
        NON_POSITIVE,
        NEGATIVE,
        IGNORED_MAP_VALUE
    }

    static WorkflowConfigFinding strict(String path, Kind kind, String input, String message) {
        return new WorkflowConfigFinding(path, kind, input, message, true);
    }

    static WorkflowConfigFinding ignored(String path, String input) {
        return new WorkflowConfigFinding(path, Kind.IGNORED_MAP_VALUE, input, path + " value was ignored", false);
    }
}
