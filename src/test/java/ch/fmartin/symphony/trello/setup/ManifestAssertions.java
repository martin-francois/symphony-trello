package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

final class ManifestAssertions {
    private final Path path;
    private final ConnectedBoardManifest manifest;

    private ManifestAssertions(Path path) {
        this.path = path;
        try {
            this.manifest =
                    ConnectedBoardRepository.jsonMapper().readValue(path.toFile(), ConnectedBoardManifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read connected-board manifest " + path, e);
        }
    }

    static ManifestAssertions assertThatManifest(Path manifest) {
        return new ManifestAssertions(manifest);
    }

    ManifestAssertions hasBoard(String name) {
        assertThat(manifest.boards()).extracting(ConnectedBoard::boardName).contains(name);
        return this;
    }

    ManifestAssertions hasNoBoard(String name) {
        assertThat(manifest.boards()).extracting(ConnectedBoard::boardName).doesNotContain(name);
        return this;
    }

    ManifestAssertions hasBoardWithPort(String name, int port) {
        assertThat(board(name).serverPort()).isEqualTo(port);
        return this;
    }

    ManifestAssertions hasBoardId(String name, String id) {
        assertThat(board(name).boardId()).isEqualTo(id);
        return this;
    }

    ManifestAssertions hasBoardKey(String name, String key) {
        assertThat(board(name).boardKey()).isEqualTo(key);
        return this;
    }

    ManifestAssertions hasBoardUrl(String name, String url) {
        assertThat(board(name).boardUrl()).isEqualTo(url);
        return this;
    }

    ManifestAssertions hasGithubEnabled(String name) {
        assertThat(board(name).githubEnabled()).isTrue();
        return this;
    }

    ManifestAssertions hasGithubDisabled(String name) {
        assertThat(board(name).githubEnabled()).isFalse();
        return this;
    }

    ManifestAssertions hasAdditionalWritableRoot(String boardName, Path root) {
        assertThat(board(boardName).additionalWritableRoots()).contains(root);
        return this;
    }

    ManifestAssertions hasNoAdditionalWritableRoots(String boardName) {
        assertThat(board(boardName).additionalWritableRoots()).isEmpty();
        return this;
    }

    ManifestAssertions hasDangerFullAccess(String boardName) {
        assertThat(board(boardName).dangerFullAccess()).isTrue();
        return this;
    }

    ManifestAssertions hasNoDangerFullAccess(String boardName) {
        assertThat(board(boardName).dangerFullAccess()).isFalse();
        return this;
    }

    ManifestAssertions hasWorkflowPath(String boardName, Path workflow) {
        assertThat(board(boardName).workflowPath()).isEqualTo(workflow);
        return this;
    }

    ManifestAssertions hasEnvPath(String boardName, Path env) {
        assertThat(board(boardName).envPath()).isEqualTo(env);
        return this;
    }

    ManifestAssertions hasWorkspaceRoot(String boardName, Path workspaceRoot) {
        assertThat(board(boardName).workspaceRoot()).isEqualTo(workspaceRoot);
        return this;
    }

    ManifestAssertions hasBoardCount(int count) {
        assertThat(manifest.boards()).hasSize(count);
        return this;
    }

    private ConnectedBoard board(String name) {
        return manifest.boards().stream()
                .filter(board -> board.boardName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected board %s in %s".formatted(name, path)));
    }
}
