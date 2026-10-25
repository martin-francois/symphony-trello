package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
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
}
