package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import java.nio.file.Path;
import java.util.List;

final class ConnectedBoardBuilder {
    private String boardId = "board-1";
    private String boardKey = "SYNTH001";
    private String boardName = "Queue";
    private String boardUrl = "https://trello.com/b/SYNTH001/synthetic-board";
    private Path workflowPath;
    private Path envPath;
    private Path workspaceRoot;
    private int serverPort = ConfigDefaults.DEFAULT_SERVER_PORT;
    private boolean githubEnabled;
    private List<Path> additionalWritableRoots = List.of();
    private boolean dangerFullAccess;

    private ConnectedBoardBuilder(Path workflowPath) {
        this.workflowPath = workflowPath;
        Path base = workflowPath.getParent() == null ? Path.of(".") : workflowPath.getParent();
        this.envPath = base.resolve(".env");
        this.workspaceRoot = base.resolve("workspaces");
    }

    static ConnectedBoardBuilder connectedBoard(Path workflowPath) {
        return new ConnectedBoardBuilder(workflowPath);
    }

    static ConnectedBoardBuilder from(ConnectedBoard board) {
        return connectedBoard(board.workflowPath())
                .withBoardId(board.boardId())
                .withBoardKey(board.boardKey())
                .withBoardName(board.boardName())
                .withBoardUrl(board.boardUrl())
                .withEnvPath(board.envPath())
                .withWorkspaceRoot(board.workspaceRoot())
                .withServerPort(board.serverPort())
                .withGithubEnabled(board.githubEnabled())
                .withAdditionalWritableRoots(board.additionalWritableRoots())
                .withDangerFullAccess(board.dangerFullAccess());
    }

    ConnectedBoardBuilder withBoardId(String boardId) {
        this.boardId = boardId;
        return this;
    }

    ConnectedBoardBuilder withBoardKey(String boardKey) {
        this.boardKey = boardKey;
        return this;
    }

    ConnectedBoardBuilder withBoardName(String boardName) {
        this.boardName = boardName;
        return this;
    }

    ConnectedBoardBuilder withBoardUrl(String boardUrl) {
        this.boardUrl = boardUrl;
        return this;
    }

    ConnectedBoardBuilder withEnvPath(Path envPath) {
        this.envPath = envPath;
        return this;
    }

    ConnectedBoardBuilder withWorkspaceRoot(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
        return this;
    }

    ConnectedBoardBuilder withServerPort(int serverPort) {
        this.serverPort = serverPort;
        return this;
    }

    ConnectedBoardBuilder withGithubEnabled(boolean githubEnabled) {
        this.githubEnabled = githubEnabled;
        return this;
    }

    ConnectedBoardBuilder withAdditionalWritableRoots(List<Path> additionalWritableRoots) {
        this.additionalWritableRoots = additionalWritableRoots;
        return this;
    }

    ConnectedBoardBuilder withDangerFullAccess(boolean dangerFullAccess) {
        this.dangerFullAccess = dangerFullAccess;
        return this;
    }

    ConnectedBoard build() {
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
