package ch.fmartin.symphony.trello.setup;

import java.util.Optional;
import picocli.CommandLine.Option;

/// The reviewer model and effort options, shared by `setup-local`, `new-board`, and `import-board`.
final class CodexReviewerOptions {
    @Option(names = CodexReviewerOverrides.MODEL_OPTION, description = SetupOptionHelp.CODEX_REVIEW_MODEL)
    Optional<String> model = Optional.empty();

    @Option(
            names = CodexReviewerOverrides.REASONING_EFFORT_OPTION,
            description = SetupOptionHelp.CODEX_REVIEW_REASONING_EFFORT)
    Optional<String> reasoningEffort = Optional.empty();

    CodexReviewerOverrides overrides() {
        return CodexReviewerOverrides.fromOptions(model, reasoningEffort);
    }

    CodexReviewerOptions copy() {
        return new CodexReviewerOptions().mergedWith(this);
    }

    /// Returns a copy where each option the child set replaces this one.
    CodexReviewerOptions mergedWith(CodexReviewerOptions child) {
        var merged = new CodexReviewerOptions();
        merged.model = child.model.or(() -> model);
        merged.reasoningEffort = child.reasoningEffort.or(() -> reasoningEffort);
        return merged;
    }
}
