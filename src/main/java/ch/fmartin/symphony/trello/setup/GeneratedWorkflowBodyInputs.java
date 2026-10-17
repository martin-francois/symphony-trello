package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Everything the generated workflow body depends on. Setup records these inputs next to the exact
/// generated body so a later Symphony version can render its own body for the same workflow. The
/// JSON shape is a permanent compatibility contract (ADR 0095): older versions ignore fields that
/// newer versions add, and newer versions must give missing fields a default that reproduces the
/// older body inputs.
@JsonIgnoreProperties(ignoreUnknown = true)
@NullMarked
record GeneratedWorkflowBodyInputs(
        List<String> activeStates,
        List<String> terminalStates,
        @Nullable String inProgressState,
        @Nullable String reviewState,
        @Nullable String blockedState,
        @Nullable String mergingState,
        boolean githubEnabled) {
    GeneratedWorkflowBodyInputs {
        activeStates = activeStates == null ? List.of() : List.copyOf(activeStates);
        terminalStates = terminalStates == null ? List.of() : List.copyOf(terminalStates);
    }

    /// Best-effort inputs for a workflow that has no recorded provenance, read from its current
    /// metadata in the shape setup writes. The result is only trusted when the body it renders occurs
    /// verbatim in the workflow; otherwise it only feeds the manual-migration preview.
    static Optional<GeneratedWorkflowBodyInputs> fromMetadata(
            Map<String, Object> metadata, Optional<Boolean> connectedBoardGithubEnabled) {
        if (!(metadata.get("tracker") instanceof Map<?, ?> tracker)) {
            return Optional.empty();
        }
        List<String> activeStates = strings(tracker.get("active_states"));
        List<String> terminalStates = strings(tracker.get("terminal_states")).stream()
                .filter(state -> !TrelloBoardSetup.isSystemTerminalState(state))
                .toList();
        String inProgressState = text(tracker.get("in_progress_state"));
        String blockedState = text(tracker.get("blocked_state"));
        boolean githubEnabled = connectedBoardGithubEnabled.orElseGet(
                () -> containsIgnoreCase(activeStates, TrelloBoardSetup.RECOMMENDED_MERGING_STATE));
        String doneState = TrelloBoardSetup.landingDoneState(terminalStates);
        String mergingState = githubEnabled && doneState != null
                ? activeStates.stream()
                        .filter(state -> state.equalsIgnoreCase(TrelloBoardSetup.RECOMMENDED_MERGING_STATE))
                        .findAny()
                        .orElse(null)
                : null;
        String reviewState = reviewState(metadata, inProgressState, blockedState, doneState);
        return Optional.of(new GeneratedWorkflowBodyInputs(
                activeStates, terminalStates, inProgressState, reviewState, blockedState, mergingState, githubEnabled));
    }

    /// Setup lists the handoff targets in `trello_tools.allowed_move_list_names`; the review list is
    /// the one entry that fills no other role. Anything else is not a setup-written shape.
    private static @Nullable String reviewState(
            Map<String, Object> metadata,
            @Nullable String inProgressState,
            @Nullable String blockedState,
            @Nullable String doneState) {
        if (!(metadata.get("trello_tools") instanceof Map<?, ?> trelloTools)) {
            return null;
        }
        List<String> reviewCandidates = strings(trelloTools.get("allowed_move_list_names")).stream()
                .filter(state -> !sameListName(state, inProgressState))
                .filter(state -> !sameListName(state, blockedState))
                .filter(state -> !sameListName(state, doneState))
                .limit(2)
                .toList();
        return reviewCandidates.size() == 1 ? reviewCandidates.getFirst() : null;
    }

    private static boolean sameListName(String state, @Nullable String other) {
        return other != null && state.equalsIgnoreCase(other);
    }

    private static List<String> strings(@Nullable Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .map(GeneratedWorkflowBodyInputs::text)
                .filter(Objects::nonNull)
                .toList();
    }

    private static @Nullable String text(@Nullable Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static boolean containsIgnoreCase(List<String> values, String expected) {
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(expected));
    }
}
