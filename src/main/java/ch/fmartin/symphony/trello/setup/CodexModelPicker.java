package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNSAFE_SINGLE_LINE_CHARACTERS;
import static ch.fmartin.symphony.trello.setup.CodexModelSelectionDefaults.blank;

import ch.fmartin.symphony.trello.setup.CodexModelSelectionDefaults.CatalogModel;
import com.google.common.base.CharMatcher;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// Line-based Codex model picker for guided setup.
///
/// It lists the visible models of the installed Codex catalog in catalog order, preselects the
/// current value, and always offers an "other model" entry, so a model that is missing from the
/// catalog never blocks setup. A typed model id is accepted at the same prompt. See
/// docs/adr/0094-codex-catalog-model-picker.md.
final class CodexModelPicker {
    static final String MODEL_ID_PROMPT = "Model ID: ";
    static final String OTHER_MODEL_LABEL = "Other model ID";
    static final String KEEP_WORKFLOW_DEFAULT_LABEL = "Keep the workflow default (Codex chooses the model)";
    /// Codex display names are free text. Long ones are shortened so a picker line stays readable in
    /// an 80-column terminal; the model id itself is never shortened because the operator may need it.
    static final int MAX_DISPLAY_NAME_LENGTH = 40;

    private static final String ELLIPSIS = "...";
    private static final CharMatcher ASCII_DIGITS = CharMatcher.inRange('0', '9');

    /// Shows the picker and returns the newly chosen model id.
    ///
    /// @param catalogModels visible catalog models in catalog order; must not be empty
    /// @param currentModel the value that Enter keeps, or `null` when the workflow omits the model
    /// @return the chosen model id, or empty when the operator keeps `currentModel`
    Optional<String> choose(Terminal terminal, List<CatalogModel> catalogModels, @Nullable String currentModel)
            throws IOException {
        List<Choice> choices = choices(catalogModels, currentModel);
        int preselectedChoice = preselectedChoice(choices, currentModel);
        terminal.info("Models listed by the installed Codex CLI:");
        for (int i = 0; i < choices.size(); i++) {
            terminal.info("  " + (i + 1) + ". " + choices.get(i).label(currentModel));
        }
        terminal.info("Press Enter to keep " + preselectedChoice + ", type another number, or type a model ID.");
        String answer = terminal.readLine(modelPrompt(String.valueOf(preselectedChoice)));
        if (blank(answer)) {
            return Optional.empty();
        }
        String trimmedAnswer = answer.strip();
        Optional<String> chosenModel = ASCII_DIGITS.matchesAllOf(trimmedAnswer)
                ? choices.get(PromptSupport.parseBoundedChoice(trimmedAnswer, choices.size()) - 1)
                        .chosenModel(terminal)
                : Optional.of(typedModelId(answer));
        return chosenModel.filter(model -> !model.equals(currentModel));
    }

    /// The model prompt shared by the picker and the free-text fallback, so both read the same way.
    static String modelPrompt(String preselected) {
        return "Model [" + preselected + "]: ";
    }

    /// Validates a model id typed at a setup prompt. Any non-blank single-line value is accepted,
    /// because the installed catalog does not list every model that Codex can run.
    static String typedModelId(String answer) {
        if (blank(answer)) {
            throw new TrelloBoardSetupException("setup_invalid_choice", "Model ID must not be blank.");
        }
        if (UNSAFE_SINGLE_LINE_CHARACTERS.matchesAnyOf(answer)) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_choice", "Model ID must not contain control characters or line breaks.");
        }
        return answer.strip();
    }

    private static List<Choice> choices(List<CatalogModel> catalogModels, @Nullable String currentModel) {
        List<Choice> choices = new ArrayList<>();
        catalogModels.forEach(model -> choices.add(new Choice.Listed(model)));
        if (blank(currentModel)) {
            choices.add(new Choice.KeepWorkflowDefault());
        } else if (catalogModels.stream().noneMatch(model -> model.model().equals(currentModel))) {
            choices.add(new Choice.UnlistedCurrent(currentModel));
        }
        choices.add(new Choice.OtherModelId());
        return List.copyOf(choices);
    }

    private static int preselectedChoice(List<Choice> choices, @Nullable String currentModel) {
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).keepsCurrentModel(currentModel)) {
                return i + 1;
            }
        }
        throw new IllegalStateException("The model picker always lists the current model.");
    }

    private static String shortened(String displayName) {
        if (displayName.length() <= MAX_DISPLAY_NAME_LENGTH) {
            return displayName;
        }
        return displayName
                        .substring(0, MAX_DISPLAY_NAME_LENGTH - ELLIPSIS.length())
                        .stripTrailing()
                + ELLIPSIS;
    }

    private sealed interface Choice {
        boolean keepsCurrentModel(@Nullable String currentModel);

        /// Returns the chosen model id, or empty when the choice keeps the workflow's omitted model.
        Optional<String> chosenModel(Terminal terminal) throws IOException;

        String label(@Nullable String currentModel);

        record Listed(CatalogModel model) implements Choice {
            @Override
            public boolean keepsCurrentModel(@Nullable String currentModel) {
                return model.model().equals(currentModel);
            }

            @Override
            public Optional<String> chosenModel(Terminal terminal) {
                return Optional.of(model.model());
            }

            @Override
            public String label(@Nullable String currentModel) {
                return model.model() + marker(currentModel) + displayNameSuffix();
            }

            private String marker(@Nullable String currentModel) {
                if (model.recommended()) {
                    return " (recommended)";
                }
                // A preselected entry that is not the recommendation can only come from the workflow.
                return keepsCurrentModel(currentModel) ? " (current)" : "";
            }

            private String displayNameSuffix() {
                String displayName = model.displayName();
                if (displayName == null || displayName.equalsIgnoreCase(model.model())) {
                    return "";
                }
                return " - " + shortened(displayName);
            }
        }

        record UnlistedCurrent(String model) implements Choice {
            @Override
            public boolean keepsCurrentModel(@Nullable String currentModel) {
                return true;
            }

            @Override
            public Optional<String> chosenModel(Terminal terminal) {
                return Optional.of(model);
            }

            @Override
            public String label(@Nullable String currentModel) {
                return model + " (current, not listed by Codex)";
            }
        }

        record KeepWorkflowDefault() implements Choice {
            @Override
            public boolean keepsCurrentModel(@Nullable String currentModel) {
                return true;
            }

            @Override
            public Optional<String> chosenModel(Terminal terminal) {
                return Optional.empty();
            }

            @Override
            public String label(@Nullable String currentModel) {
                return KEEP_WORKFLOW_DEFAULT_LABEL;
            }
        }

        record OtherModelId() implements Choice {
            @Override
            public boolean keepsCurrentModel(@Nullable String currentModel) {
                return false;
            }

            @Override
            public Optional<String> chosenModel(Terminal terminal) throws IOException {
                return Optional.of(typedModelId(terminal.readLine(MODEL_ID_PROMPT)));
            }

            @Override
            public String label(@Nullable String currentModel) {
                return OTHER_MODEL_LABEL;
            }
        }
    }
}
