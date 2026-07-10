package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.setup.CodexModelSelectionDefaults.ReasoningEffortOption;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class CodexModelSelectionFlow {
    Selection resolve(LocalSetup.Options options, CodexModelSelectionDefaults selectionDefaults, Terminal terminal)
            throws IOException {
        TrelloBoardSetup.CodexModelDefaults defaults = selectionDefaults.defaults();
        if (!selectionDefaults.firstClassFieldsSupported() && !options.hasExplicitCodexModelRequest()) {
            return new Selection(defaults, Optional.empty(), Optional.empty());
        }
        String model = valueOrDefault(options.codexModel(), defaults.model());
        String reasoningEffort = reasoningEffortDefault(options, selectionDefaults, model);
        Optional<String> modelOverride = options.codexModel();
        Optional<String> reasoningEffortOverride =
                reasoningEffortOverride(options, selectionDefaults, defaults, modelOverride, model, reasoningEffort);
        if (options.nonInteractive()) {
            validateExplicitReasoningEffort(options, selectionDefaults, model, reasoningEffort);
            return new Selection(
                    TrelloBoardSetup.CodexModelDefaults.partial(model, reasoningEffort),
                    modelOverride,
                    reasoningEffortOverride);
        }

        terminal.info("");
        terminal.info("Codex model");
        if (options.codexModel().isEmpty()) {
            String answer = terminal.readLine("Model [" + defaultLabel(model) + "]: ");
            if (!blank(answer)) {
                model = answer.strip();
                modelOverride = Optional.of(model);
            }
        }
        reasoningEffort = reasoningEffortDefault(options, selectionDefaults, defaults, modelOverride, model);
        reasoningEffortOverride =
                reasoningEffortOverride(options, selectionDefaults, defaults, modelOverride, model, reasoningEffort);
        if (options.codexReasoningEffort().isEmpty()) {
            displayReasoningEffortOptions(terminal, selectionDefaults, model, reasoningEffort);
            String answer = terminal.readLine(reasoningEffortPrompt(reasoningEffort));
            if (!blank(answer)) {
                reasoningEffort = selectionDefaults.validateReasoningEffortForModel(model, answer.strip());
                reasoningEffortOverride = Optional.of(reasoningEffort);
            }
        }
        validateExplicitReasoningEffort(options, selectionDefaults, model, reasoningEffort);
        return new Selection(
                TrelloBoardSetup.CodexModelDefaults.partial(model, reasoningEffort),
                modelOverride,
                reasoningEffortOverride);
    }

    private static String reasoningEffortDefault(
            LocalSetup.Options options, CodexModelSelectionDefaults selectionDefaults, String model) {
        return reasoningEffortDefault(
                options, selectionDefaults, selectionDefaults.defaults(), options.codexModel(), model);
    }

    private static String reasoningEffortDefault(
            LocalSetup.Options options,
            CodexModelSelectionDefaults selectionDefaults,
            TrelloBoardSetup.CodexModelDefaults defaults,
            Optional<String> modelOverride,
            String model) {
        if (options.codexReasoningEffort().isPresent()) {
            return valueOrDefault(options.codexReasoningEffort(), defaults.reasoningEffort());
        }
        if (selectionDefaults.preserveConfiguredReasoningEffort()) {
            return defaults.reasoningEffort();
        }
        if (modelOverride.isPresent() || blank(defaults.reasoningEffort())) {
            if (modelOverride.isEmpty() && selectionDefaults.preserveReasoningEffortOmission()) {
                return defaults.reasoningEffort();
            }
            return selectionDefaults
                    .reasoningEffortForSelectedModel(
                            model, defaults, selectionDefaults.preserveConfiguredReasoningEffort())
                    .orElse(null);
        }
        return defaults.reasoningEffort();
    }

    private static Optional<String> reasoningEffortOverride(
            LocalSetup.Options options,
            CodexModelSelectionDefaults selectionDefaults,
            TrelloBoardSetup.CodexModelDefaults defaults,
            Optional<String> modelOverride,
            String model,
            String reasoningEffort) {
        if (options.codexReasoningEffort().isPresent()) {
            return options.codexReasoningEffort();
        }
        if (selectionDefaults.preserveConfiguredReasoningEffort()) {
            return Optional.empty();
        }
        if (modelOverride.isPresent() || blank(defaults.reasoningEffort())) {
            if (modelOverride.isEmpty() && selectionDefaults.preserveReasoningEffortOmission()) {
                return Optional.empty();
            }
            if (modelOverride.isPresent()) {
                return selectionDefaults
                        .reasoningEffortForSelectedModel(model, defaults, false)
                        .map(ignored -> reasoningEffort);
            }
            return selectionDefaults.reasoningEffortForModel(model).map(ignored -> reasoningEffort);
        }
        return Optional.empty();
    }

    private static void displayReasoningEffortOptions(
            Terminal terminal,
            CodexModelSelectionDefaults selectionDefaults,
            String model,
            String currentReasoningEffort) {
        selectionDefaults
                .reasoningEffortOptionsForModel(model)
                .ifPresentOrElse(
                        options -> displayAdvertisedReasoningEffortOptions(
                                terminal, selectionDefaults, model, currentReasoningEffort, options),
                        () -> terminal.info(
                                "Reasoning effort choices: not advertised for this model by the installed Codex CLI"));
    }

    private static void displayAdvertisedReasoningEffortOptions(
            Terminal terminal,
            CodexModelSelectionDefaults selectionDefaults,
            String model,
            String currentReasoningEffort,
            List<ReasoningEffortOption> options) {
        terminal.info("Reasoning effort choices for " + model + ":");
        Optional<String> defaultReasoningEffort = selectionDefaults.reasoningEffortForModel(model);
        for (ReasoningEffortOption option : options) {
            terminal.info(reasoningEffortOptionLine(option, defaultReasoningEffort, currentReasoningEffort));
        }
        if (!blank(currentReasoningEffort)
                && options.stream().noneMatch(option -> option.reasoningEffort().equals(currentReasoningEffort))) {
            terminal.info("  " + currentReasoningEffort + " (current, not advertised; preserving workflow value)");
        }
    }

    private static String reasoningEffortOptionLine(
            ReasoningEffortOption option, Optional<String> defaultReasoningEffort, String currentReasoningEffort) {
        String markers =
                reasoningEffortMarkers(option.reasoningEffort(), defaultReasoningEffort, currentReasoningEffort);
        String description = blank(option.description()) ? "" : " - " + option.description();
        return "  " + option.reasoningEffort() + markers + description;
    }

    private static String reasoningEffortMarkers(
            String reasoningEffort, Optional<String> defaultReasoningEffort, String currentReasoningEffort) {
        boolean isDefault = defaultReasoningEffort.map(reasoningEffort::equals).orElse(false);
        boolean isCurrent = Objects.equals(reasoningEffort, currentReasoningEffort);
        if (isDefault && isCurrent) {
            return " (default, current)";
        }
        if (isDefault) {
            return " (default)";
        }
        return isCurrent ? " (current)" : "";
    }

    private static void validateExplicitReasoningEffort(
            LocalSetup.Options options,
            CodexModelSelectionDefaults selectionDefaults,
            String model,
            String reasoningEffort) {
        options.codexReasoningEffort()
                .ifPresent(ignored -> selectionDefaults.validateReasoningEffortForModel(model, reasoningEffort));
    }

    private static String valueOrDefault(Optional<String> value, String defaultValue) {
        return value.filter(text -> !blank(text)).orElse(defaultValue);
    }

    private static String defaultLabel(String value) {
        return blank(value) ? "keep workflow default" : value;
    }

    private static String reasoningEffortPrompt(String reasoningEffort) {
        return blank(reasoningEffort) ? "Reasoning effort: " : "Reasoning effort [" + reasoningEffort + "]: ";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    record Selection(
            TrelloBoardSetup.CodexModelDefaults defaults,
            Optional<String> modelOverride,
            Optional<String> reasoningEffortOverride) {}
}
