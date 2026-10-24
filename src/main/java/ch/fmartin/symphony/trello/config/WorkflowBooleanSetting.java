package ch.fmartin.symphony.trello.config;

import java.util.Optional;

public record WorkflowBooleanSetting(Optional<Boolean> value, Optional<WorkflowConfigFinding> finding) {
    public static WorkflowBooleanSetting valid(boolean value) {
        return new WorkflowBooleanSetting(Optional.of(value), Optional.empty());
    }

    public static WorkflowBooleanSetting omitted() {
        return new WorkflowBooleanSetting(Optional.empty(), Optional.empty());
    }

    public static WorkflowBooleanSetting invalid(WorkflowConfigFinding finding) {
        return new WorkflowBooleanSetting(Optional.empty(), Optional.of(finding));
    }
}
