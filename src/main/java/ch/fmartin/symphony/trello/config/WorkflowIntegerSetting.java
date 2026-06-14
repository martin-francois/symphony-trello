package ch.fmartin.symphony.trello.config;

import java.util.Optional;

public record WorkflowIntegerSetting(
        Optional<Integer> value, Optional<WorkflowConfigFinding> finding, boolean present) {
    public static WorkflowIntegerSetting valid(int value) {
        return new WorkflowIntegerSetting(Optional.of(value), Optional.empty(), true);
    }

    public static WorkflowIntegerSetting omitted() {
        return new WorkflowIntegerSetting(Optional.empty(), Optional.empty(), false);
    }

    public static WorkflowIntegerSetting invalid(WorkflowConfigFinding finding) {
        return new WorkflowIntegerSetting(Optional.empty(), Optional.of(finding), true);
    }

    public boolean invalid() {
        return finding.isPresent();
    }

    public String diagnosticsCell() {
        return value.map(String::valueOf).orElseGet(() -> invalid() ? "invalid" : "");
    }
}
