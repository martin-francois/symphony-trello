package ch.fmartin.symphony.trello.setup;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record LocalSetupRequest(
        Action action,
        boolean dryRun,
        boolean nonInteractive,
        boolean force,
        boolean forceNewSetup,
        Optional<Boolean> githubMode,
        Optional<String> apiKey,
        Optional<String> apiToken,
        Optional<String> boardName,
        Optional<String> existingBoardId,
        Optional<String> workspaceId,
        List<String> activeStates,
        List<String> terminalStates,
        String inProgressState,
        boolean detectInProgressState,
        String blockedState,
        Optional<Path> workflowPath,
        Optional<Path> workspaceRoot,
        Optional<Path> configDir,
        Optional<Path> manifestPath,
        Optional<Integer> serverPort,
        int maxAgents,
        boolean maxAgentsExplicit,
        Optional<String> codexModel,
        Optional<String> codexReasoningEffort,
        Optional<Path> envPath,
        List<Path> additionalWritableRoots,
        boolean allowAllPaths,
        boolean dangerFullAccess,
        boolean noStart,
        URI endpoint) {
    public LocalSetupRequest {
        activeStates = List.copyOf(activeStates);
        terminalStates = List.copyOf(terminalStates);
        additionalWritableRoots = List.copyOf(additionalWritableRoots);
    }

    public enum Action {
        SETUP,
        CHECK,
        REPAIR_PORT,
        CONFIGURE_GITHUB
    }
}
