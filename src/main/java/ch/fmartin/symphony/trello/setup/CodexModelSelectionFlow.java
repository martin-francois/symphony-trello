package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

final class CodexModelSelectionFlow {
    private static final List<String> REASONING_EFFORTS = List.of("minimal", "low", "medium", "high");

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
            terminal.info("Reasoning effort choices: " + String.join(", ", reasoningChoices(reasoningEffort)));
            String answer = terminal.readLine("Reasoning effort [" + defaultLabel(reasoningEffort) + "]: ");
            if (!blank(answer)) {
                reasoningEffort = answer.strip();
                reasoningEffortOverride = Optional.of(reasoningEffort);
            }
        }
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
                    .reasoningEffortForExplicitModelOverride(
                            model, defaults, selectionDefaults.preserveConfiguredReasoningEffort())
                    .orElseGet(defaults::reasoningEffort);
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
                        .reasoningEffortForExplicitModelOverride(model, defaults, false)
                        .map(ignored -> reasoningEffort);
            }
            return selectionDefaults.reasoningEffortForModel(model).map(ignored -> reasoningEffort);
        }
        return Optional.empty();
    }

    private static List<String> reasoningChoices(String selected) {
        if (blank(selected)) {
            return REASONING_EFFORTS;
        }
        if (REASONING_EFFORTS.contains(selected)) {
            return REASONING_EFFORTS;
        }
        return Stream.concat(Stream.of(selected), REASONING_EFFORTS.stream())
                .distinct()
                .toList();
    }

    private static String valueOrDefault(Optional<String> value, String defaultValue) {
        return value.filter(text -> !blank(text)).orElse(defaultValue);
    }

    private static String defaultLabel(String value) {
        return blank(value) ? "keep workflow default" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    record Selection(
            TrelloBoardSetup.CodexModelDefaults defaults,
            Optional<String> modelOverride,
            Optional<String> reasoningEffortOverride) {}
}
