package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNSAFE_SINGLE_LINE_CHARACTERS;
import static com.google.common.base.Preconditions.checkArgument;

import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.CodexModelDefaults;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

record CodexModelSelectionDefaults(
        CodexModelDefaults defaults,
        Map<String, String> reasoningEffortsByModel,
        Map<String, List<ReasoningEffortOption>> reasoningEffortOptionsByModel,
        boolean firstClassFieldsSupported,
        String fallbackReasoningEffort,
        boolean preserveConfiguredReasoningEffort,
        boolean preserveReasoningEffortOmission) {
    CodexModelSelectionDefaults {
        Objects.requireNonNull(defaults, "defaults");
        reasoningEffortsByModel = sanitizeReasoningEfforts(reasoningEffortsByModel);
        reasoningEffortOptionsByModel = sanitizeReasoningEffortOptions(reasoningEffortOptionsByModel);
        fallbackReasoningEffort = blank(fallbackReasoningEffort) ? null : fallbackReasoningEffort.strip();
    }

    CodexModelSelectionDefaults(CodexModelDefaults defaults, Map<String, String> reasoningEffortsByModel) {
        this(defaults, reasoningEffortsByModel, Map.of());
    }

    CodexModelSelectionDefaults(
            CodexModelDefaults defaults,
            Map<String, String> reasoningEffortsByModel,
            Map<String, List<String>> reasoningEffortChoicesByModel) {
        this(
                defaults,
                reasoningEffortsByModel,
                toReasoningEffortOptions(reasoningEffortChoicesByModel),
                defaults.firstClassFieldsSupported(),
                defaults.reasoningEffort(),
                false,
                false);
    }

    static CodexModelSelectionDefaults withReasoningEffortOptions(
            CodexModelDefaults defaults,
            Map<String, String> reasoningEffortsByModel,
            Map<String, List<ReasoningEffortOption>> reasoningEffortOptionsByModel) {
        return new CodexModelSelectionDefaults(
                defaults,
                reasoningEffortsByModel,
                reasoningEffortOptionsByModel,
                defaults.firstClassFieldsSupported(),
                defaults.reasoningEffort(),
                false,
                false);
    }

    static CodexModelSelectionDefaults of(CodexModelDefaults defaults) {
        return new CodexModelSelectionDefaults(defaults, Map.of(), Map.of());
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
                reasoningEffortOptionsByModel,
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

    Optional<List<String>> reasoningEffortChoicesForModel(String model) {
        return reasoningEffortOptionsForModel(model).map(CodexModelSelectionDefaults::reasoningEffortNames);
    }

    Optional<List<ReasoningEffortOption>> reasoningEffortOptionsForModel(String model) {
        if (blank(model)) {
            return Optional.empty();
        }
        return Optional.ofNullable(reasoningEffortOptionsByModel.get(model.strip()));
    }

    Optional<String> reasoningEffortDescriptionForModel(String model, String reasoningEffort) {
        if (blank(reasoningEffort)) {
            return Optional.empty();
        }
        return reasoningEffortOptionsForModel(model)
                .flatMap(options -> reasoningEffortOption(options, reasoningEffort.strip()))
                .map(ReasoningEffortOption::description)
                .filter(description -> !blank(description));
    }

    private static List<String> reasoningEffortNames(List<ReasoningEffortOption> options) {
        return options.stream().map(ReasoningEffortOption::reasoningEffort).toList();
    }

    private static Optional<ReasoningEffortOption> reasoningEffortOption(
            List<ReasoningEffortOption> options, String reasoningEffort) {
        return options.stream()
                .filter(option -> option.reasoningEffort().equals(reasoningEffort))
                .findAny();
    }

    String validateReasoningEffortForModel(String model, String reasoningEffort) {
        return reasoningEffortChoicesForModel(model)
                .map(choices -> requireAdvertisedReasoningEffort(reasoningEffort, choices))
                .orElse(reasoningEffort);
    }

    private static String requireAdvertisedReasoningEffort(String reasoningEffort, List<String> advertisedChoices) {
        if (advertisedChoices.contains(reasoningEffort)) {
            return reasoningEffort;
        }
        throw new TrelloBoardSetupException(
                "setup_invalid_choice",
                "Reasoning effort must be one of the values advertised for the selected model: "
                        + String.join(", ", advertisedChoices)
                        + ".");
    }

    Optional<String> reasoningEffortForSelectedModel(
            String model, CodexModelDefaults effectiveWorkflowDefaults, boolean preserveConfiguredReasoningEffort) {
        if (blank(model)) {
            return Optional.empty();
        }
        if (preserveConfiguredReasoningEffort) {
            return Optional.ofNullable(effectiveWorkflowDefaults).map(CodexModelDefaults::reasoningEffort);
        }
        return reasoningEffortForModel(model).or(this::compatibilityFallbackReasoningEffort);
    }

    private Optional<String> compatibilityFallbackReasoningEffort() {
        if (firstClassFieldsSupported || blank(fallbackReasoningEffort)) {
            return Optional.empty();
        }
        return Optional.of(fallbackReasoningEffort);
    }

    private static Map<String, String> sanitizeReasoningEfforts(Map<String, String> values) {
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

    private static Map<String, List<ReasoningEffortOption>> toReasoningEffortOptions(Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<ReasoningEffortOption>> optionsByModel = new LinkedHashMap<>();
        values.forEach((model, choices) -> {
            if (!blank(model) && choices != null) {
                List<ReasoningEffortOption> options = choices.stream()
                        .filter(choice -> !blank(choice))
                        .map(choice -> new ReasoningEffortOption(choice, null))
                        .toList();
                if (!options.isEmpty()) {
                    optionsByModel.put(model.strip(), options);
                }
            }
        });
        return Map.copyOf(optionsByModel);
    }

    private static Map<String, List<ReasoningEffortOption>> sanitizeReasoningEffortOptions(
            Map<String, List<ReasoningEffortOption>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<ReasoningEffortOption>> sanitized = new LinkedHashMap<>();
        values.forEach((model, options) -> {
            if (!blank(model) && options != null) {
                Map<String, ReasoningEffortOption> uniqueOptionsInCatalogOrder = options.stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(
                                ReasoningEffortOption::reasoningEffort,
                                Function.identity(),
                                (first, ignored) -> first,
                                LinkedHashMap::new));
                if (!uniqueOptionsInCatalogOrder.isEmpty()) {
                    sanitized.put(model.strip(), List.copyOf(uniqueOptionsInCatalogOrder.values()));
                }
            }
        });
        return Map.copyOf(sanitized);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    static void checkNoControlCharacters(String value, String name) {
        checkArgument(
                value == null || UNSAFE_SINGLE_LINE_CHARACTERS.matchesNoneOf(value),
                "%s must not contain control characters",
                name);
    }

    record ReasoningEffortOption(String reasoningEffort, String description) {
        ReasoningEffortOption {
            checkArgument(!blank(reasoningEffort), "reasoningEffort must not be blank");
            reasoningEffort = reasoningEffort.strip();
            description = blank(description) ? null : description.strip();
            checkNoControlCharacters(reasoningEffort, "reasoningEffort");
            checkNoControlCharacters(description, "description");
        }
    }
}
