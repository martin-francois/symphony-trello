package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.CodexModelDefaults;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

record CodexModelSelectionDefaults(
        CodexModelDefaults defaults,
        Map<String, String> reasoningEffortsByModel,
        boolean firstClassFieldsSupported,
        String fallbackReasoningEffort,
        boolean preserveConfiguredReasoningEffort,
        boolean preserveReasoningEffortOmission) {
    CodexModelSelectionDefaults {
        Objects.requireNonNull(defaults, "defaults");
        reasoningEffortsByModel = sanitize(reasoningEffortsByModel);
        fallbackReasoningEffort = blank(fallbackReasoningEffort) ? null : fallbackReasoningEffort.strip();
    }

    CodexModelSelectionDefaults(CodexModelDefaults defaults, Map<String, String> reasoningEffortsByModel) {
        this(
                defaults,
                reasoningEffortsByModel,
                defaults.firstClassFieldsSupported(),
                defaults.reasoningEffort(),
                false,
                false);
    }

    static CodexModelSelectionDefaults of(CodexModelDefaults defaults) {
        return new CodexModelSelectionDefaults(defaults, Map.of());
    }

    CodexModelSelectionDefaults withDefaults(CodexModelDefaults defaults) {
        return withDefaults(defaults, false);
    }

    CodexModelSelectionDefaults withDefaults(CodexModelDefaults defaults, boolean preserveConfiguredReasoningEffort) {
        return withDefaults(defaults, preserveConfiguredReasoningEffort, false);
    }

    CodexModelSelectionDefaults withDefaults(
            CodexModelDefaults defaults,
            boolean preserveConfiguredReasoningEffort,
            boolean preserveReasoningEffortOmission) {
        return new CodexModelSelectionDefaults(
                defaults,
                reasoningEffortsByModel,
                firstClassFieldsSupported,
                fallbackReasoningEffort,
                preserveConfiguredReasoningEffort,
                preserveReasoningEffortOmission);
    }

    Optional<String> reasoningEffortForModel(String model) {
        if (blank(model)) {
            return Optional.empty();
        }
        return Optional.ofNullable(reasoningEffortsByModel.get(model.strip()));
    }

    Optional<String> reasoningEffortForExplicitModelOverride(
            String model, CodexModelDefaults effectiveWorkflowDefaults, boolean preserveConfiguredReasoningEffort) {
        if (blank(model) || preserveConfiguredReasoningEffort) {
            return Optional.empty();
        }
        return reasoningEffortForModel(model)
                .or(() -> fallbackReasoningEffortForExplicitModelOverride(effectiveWorkflowDefaults));
    }

    Optional<String> fallbackReasoningEffortForExplicitModelOverride(CodexModelDefaults effectiveWorkflowDefaults) {
        if (firstClassFieldsSupported
                || (effectiveWorkflowDefaults != null && !blank(effectiveWorkflowDefaults.reasoningEffort()))
                || blank(fallbackReasoningEffort)) {
            return Optional.empty();
        }
        return Optional.of(fallbackReasoningEffort);
    }

    private static Map<String, String> sanitize(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        values.forEach((model, reasoningEffort) -> {
            if (!blank(model) && !blank(reasoningEffort)) {
                sanitized.put(model.strip(), reasoningEffort.strip());
            }
        });
        return Map.copyOf(sanitized);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
