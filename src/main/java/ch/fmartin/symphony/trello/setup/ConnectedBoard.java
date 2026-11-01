package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
        boolean dangerFullAccess,
        @Nullable Path sourceWorkflowPath) {
    ConnectedBoard(
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
        this(
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
                dangerFullAccess,
                null);
    }

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

    static ConnectedBoard from(
            TrelloBoardSetup.NewBoardResult result,
            Path envPath,
            Path workspaceRoot,
            TrelloBoardSetup.GitHubIntegration githubIntegration) {
        return new ConnectedBoard(
                result.boardId(),
                result.boardKey(),
                result.boardName(),
                result.boardUrl(),
                absolute(result.workflowPath()),
                absolute(envPath),
                absolute(workspaceRoot),
                result.serverPort(),
                githubIntegration.enabled(),
                List.of(),
                false);
    }

    static ConnectedBoard from(
            TrelloBoardSetup.ImportBoardResult result,
            Path envPath,
            Path workspaceRoot,
            TrelloBoardSetup.GitHubIntegration githubIntegration) {
        return new ConnectedBoard(
                result.boardId(),
                result.boardKey(),
                result.boardName(),
                result.boardUrl(),
                absolute(result.workflowPath()),
                absolute(envPath),
                absolute(workspaceRoot),
                result.serverPort(),
                githubIntegration.enabled(),
                List.of(),
                false);
    }

    private static Path absolute(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
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
                dangerFullAccess,
                sourceWorkflowPath);
    }

    ConnectedBoard withWorkflowPath(Path path) {
        return new ConnectedBoard(
                boardId,
                boardKey,
                boardName,
                boardUrl,
                path,
                envPath,
                workspaceRoot,
                serverPort,
                githubEnabled,
                additionalWritableRoots,
                dangerFullAccess,
                sourceWorkflowPath);
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
                dangerFullAccess,
                sourceWorkflowPath);
    }

    Path referenceDirectory() {
        return (sourceWorkflowPath == null ? workflowPath : sourceWorkflowPath)
                .toAbsolutePath()
                .normalize()
                .getParent();
    }
}
