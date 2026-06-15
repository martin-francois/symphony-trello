package ch.fmartin.symphony.trello.setup;

import java.nio.file.Path;
import java.util.List;

/**
 * Fluent test builder for {@link ConnectedBoard}.
 *
 * <p>Provides sensible defaults for the fields that are constant across most tests ({@code
 * githubEnabled=false}, {@code additionalWritableRoots=List.of()}, {@code dangerFullAccess=false})
 * while keeping the frequently varying fields as fluent {@code withX(...)} overrides. The builder
 * produces values identical to invoking the {@link ConnectedBoard} canonical constructor directly.
 */
final class ConnectedBoardBuilder {

    private String boardId;
    private String boardKey;
    private String boardName;
    private String boardUrl;
    private Path workflowPath;
    private Path envPath;
    private Path workspaceRoot;
    private int serverPort;
    private boolean githubEnabled = false;
    private List<Path> additionalWritableRoots = List.of();
    private boolean dangerFullAccess = false;

    private ConnectedBoardBuilder() {}

    static ConnectedBoardBuilder connectedBoard() {
        return new ConnectedBoardBuilder();
    }

    /** Seeds the builder from an existing board so a single field can be overridden. */
    static ConnectedBoardBuilder from(ConnectedBoard board) {
        ConnectedBoardBuilder builder = new ConnectedBoardBuilder();
        builder.boardId = board.boardId();
        builder.boardKey = board.boardKey();
        builder.boardName = board.boardName();
        builder.boardUrl = board.boardUrl();
        builder.workflowPath = board.workflowPath();
        builder.envPath = board.envPath();
        builder.workspaceRoot = board.workspaceRoot();
        builder.serverPort = board.serverPort();
        builder.githubEnabled = board.githubEnabled();
        builder.additionalWritableRoots = board.additionalWritableRoots();
        builder.dangerFullAccess = board.dangerFullAccess();
        return builder;
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

    ConnectedBoardBuilder withWorkflowPath(Path workflowPath) {
        this.workflowPath = workflowPath;
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
