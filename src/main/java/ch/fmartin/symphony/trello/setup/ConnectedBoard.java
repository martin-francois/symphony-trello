package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.List;

record ConnectedBoard(
        String boardId,
        String boardKey,
        String boardName,
        String boardUrl,
        Path workflowPath,
        Path envPath,
        Path workspaceRoot,
        int serverPort,
        boolean githubEnabled,
        List<Path> additionalWritableRoots,
        boolean dangerFullAccess) {
    ConnectedBoard {
        additionalWritableRoots = additionalWritableRoots == null ? List.of() : List.copyOf(additionalWritableRoots);
    }

    static ConnectedBoard from(
            LocalSetup.SetupResult result,
            LocalSetup.Options options,
            TrelloBoardSetup.GitHubIntegration githubIntegration) {
        return new ConnectedBoard(
                result.boardId(),
                result.boardKey(),
                result.boardName(),
                result.boardUrl(),
                result.workflowPath(),
                options.envPath(),
                options.workspaceRoot(),
                result.serverPort(),
                githubIntegration.enabled(),
                options.additionalWritableRoots(),
                options.dangerFullAccess());
    }

    ConnectedBoard withServerPort(int serverPort) {
        return new ConnectedBoard(
                boardId,
                boardKey,
                boardName,
                boardUrl,
                workflowPath,
                envPath,
                workspaceRoot,
                serverPort,
                githubEnabled,
                additionalWritableRoots,
                dangerFullAccess);
    }

    ConnectedBoard withCodexAccess(List<Path> additionalWritableRoots, boolean dangerFullAccess) {
        return new ConnectedBoard(
                boardId,
                boardKey,
                boardName,
                boardUrl,
                workflowPath,
                envPath,
                workspaceRoot,
                serverPort,
                githubEnabled,
                additionalWritableRoots,
                dangerFullAccess);
    }
}
