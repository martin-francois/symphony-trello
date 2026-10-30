package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import java.util.Optional;

/// Setup options that name the pre-handoff reviewer's model and reasoning effort. Without them the
/// generated workflow leaves both review keys commented out, so the review follows `codex.model`
/// and `codex.reasoning_effort`; see ADR 0121.
public record CodexReviewerOverrides(Optional<String> model, Optional<String> reasoningEffort) {
    public static final CodexReviewerOverrides NONE = new CodexReviewerOverrides(Optional.empty(), Optional.empty());

    static final String MODEL_OPTION = "--codex-review-model";
    static final String REASONING_EFFORT_OPTION = "--codex-review-reasoning-effort";

    /// Validates the raw option values and keeps them stripped.
    static CodexReviewerOverrides fromOptions(Optional<String> model, Optional<String> reasoningEffort) {
        return new CodexReviewerOverrides(
                validated(MODEL_OPTION, model), validated(REASONING_EFFORT_OPTION, reasoningEffort));
    }

    boolean present() {
        return model.isPresent() || reasoningEffort.isPresent();
    }

    private static Optional<String> validated(String optionName, Optional<String> value) {
        CliInputValidation.rejectBlankText(optionName, value);
        CliInputValidation.rejectControlCharactersInText(optionName, value);
        Optional<String> stripped = value.map(String::strip);
        boolean oneWord =
                stripped.map(EffectiveConfig.CodexReviewConfig::commandToken).orElse(true);
        if (!oneWord) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments",
                    optionName + " must be " + EffectiveConfig.CodexReviewConfig.COMMAND_TOKEN_DESCRIPTION + ".");
        }
        return stripped;
    }
}
