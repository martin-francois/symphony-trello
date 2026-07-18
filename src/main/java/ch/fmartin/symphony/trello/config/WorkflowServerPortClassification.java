package ch.fmartin.symphony.trello.config;

import java.util.Optional;

public record WorkflowServerPortClassification(Kind kind, Optional<Integer> port) {
    public enum Kind {
        VALID,
        OMITTED,
        OUT_OF_RANGE,
        INVALID_VALUE,
        UNREADABLE
    }

    static WorkflowServerPortClassification numericPort(int port) {
        Kind kind = WorkflowConfigIngestion.localServerPortInRange(port) ? Kind.VALID : Kind.OUT_OF_RANGE;
        return new WorkflowServerPortClassification(kind, Optional.of(port));
    }

    public static WorkflowServerPortClassification omitted() {
        return new WorkflowServerPortClassification(Kind.OMITTED, Optional.empty());
    }

    static WorkflowServerPortClassification invalidValue() {
        return new WorkflowServerPortClassification(Kind.INVALID_VALUE, Optional.empty());
    }

    public static WorkflowServerPortClassification unreadable() {
        return new WorkflowServerPortClassification(Kind.UNREADABLE, Optional.empty());
    }

    /// Ports the diagnostics report should list: valid ports get probed, out-of-range ports render
    /// the safe skip line. Omitted, unreadable, and non-numeric settings list nothing.
    public Optional<Integer> probeOrSkipPort() {
        return kind == Kind.VALID || kind == Kind.OUT_OF_RANGE ? port : Optional.empty();
    }
}
