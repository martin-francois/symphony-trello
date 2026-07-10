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
        Optional<String> repositoryUrl,
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
        // Centralized bound so programmatic construction cannot bypass the CLI option check and
        // reach Trello lookups or local writes with an unbounded agent count.
        TrelloBoardSetup.validateSetupMaxAgents(maxAgents);
        repositoryUrl = RepositoryUrlInput.validateExplicit(repositoryUrl);
        activeStates = List.copyOf(activeStates);
        terminalStates = List.copyOf(terminalStates);
        additionalWritableRoots = List.copyOf(additionalWritableRoots);
    }

    public LocalSetupRequest(
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
        this(
                action,
                dryRun,
                nonInteractive,
                force,
                forceNewSetup,
                githubMode,
                apiKey,
                apiToken,
                boardName,
                existingBoardId,
                workspaceId,
                Optional.empty(),
                activeStates,
                terminalStates,
                inProgressState,
                detectInProgressState,
                blockedState,
                workflowPath,
                workspaceRoot,
                configDir,
                manifestPath,
                serverPort,
                maxAgents,
                maxAgentsExplicit,
                codexModel,
                codexReasoningEffort,
                envPath,
                additionalWritableRoots,
                allowAllPaths,
                dangerFullAccess,
                noStart,
                endpoint);
    }

    public enum Action {
        SETUP,
        CHECK,
        REPAIR_PORT,
        CONFIGURE_GITHUB
    }
}
