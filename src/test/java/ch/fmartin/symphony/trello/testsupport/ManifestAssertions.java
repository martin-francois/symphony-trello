package ch.fmartin.symphony.trello.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

public final class ManifestAssertions {
    private static final ObjectMapper JSON = manifestMapper();

    private final Path path;
    private final List<ManifestBoard> boards;

    private ManifestAssertions(Path path) {
        this.path = path;
        try {
            this.boards = JSON.readValue(path.toFile(), ManifestDocument.class).boards();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read connected-board manifest " + path, e);
        }
    }

    public static ManifestAssertions assertThatManifest(Path manifest) {
        return new ManifestAssertions(manifest);
    }

    public ManifestAssertions hasBoard(String name) {
        assertThat(boards).extracting(ManifestBoard::boardName).contains(name);
        return this;
    }

    public ManifestAssertions hasNoBoard(String name) {
        assertThat(boards).extracting(ManifestBoard::boardName).doesNotContain(name);
        return this;
    }

    public ManifestAssertions hasBoardWithPort(String name, int port) {
        assertThat(board(name).serverPort()).isEqualTo(port);
        return this;
    }

    public ManifestAssertions hasBoardId(String name, String id) {
        assertThat(board(name).boardId()).isEqualTo(id);
        return this;
    }

    public ManifestAssertions hasBoardKey(String name, String key) {
        assertThat(board(name).boardKey()).isEqualTo(key);
        return this;
    }

    public ManifestAssertions hasBoardUrl(String name, String url) {
        assertThat(board(name).boardUrl()).isEqualTo(url);
        return this;
    }

    public ManifestAssertions hasGithubEnabled(String name) {
        assertThat(board(name).githubEnabled()).isTrue();
        return this;
    }

    public ManifestAssertions hasGithubDisabled(String name) {
        assertThat(board(name).githubEnabled()).isFalse();
        return this;
    }

    public ManifestAssertions hasAdditionalWritableRoot(String boardName, Path root) {
        assertThat(board(boardName).additionalWritableRoots()).contains(root);
        return this;
    }

    public ManifestAssertions hasNoAdditionalWritableRoots(String boardName) {
        assertThat(board(boardName).additionalWritableRoots()).isEmpty();
        return this;
    }

    public ManifestAssertions hasDangerFullAccess(String boardName) {
        assertThat(board(boardName).dangerFullAccess()).isTrue();
        return this;
    }

    public ManifestAssertions hasNoDangerFullAccess(String boardName) {
        assertThat(board(boardName).dangerFullAccess()).isFalse();
        return this;
    }

    public ManifestAssertions hasWorkflowPath(String boardName, Path workflow) {
        assertThat(board(boardName).workflowPath()).isEqualTo(workflow);
        return this;
    }

    public ManifestAssertions hasEnvPath(String boardName, Path env) {
        assertThat(board(boardName).envPath()).isEqualTo(env);
        return this;
    }

    public ManifestAssertions hasWorkspaceRoot(String boardName, Path workspaceRoot) {
        assertThat(board(boardName).workspaceRoot()).isEqualTo(workspaceRoot);
        return this;
    }

    public ManifestAssertions hasBoardCount(int count) {
        assertThat(boards).hasSize(count);
        return this;
    }

    private ManifestBoard board(String name) {
        return boards.stream()
                .filter(board -> board.boardName().equals(name))
                .findAny()
                .orElseThrow(() -> new AssertionError("Expected board %s in %s".formatted(name, path)));
    }

    private static ObjectMapper manifestMapper() {
        return new ObjectMapper()
                .registerModule(new SimpleModule().addDeserializer(Path.class, new PathDeserializer()));
    }

    private static final class PathDeserializer extends JsonDeserializer<Path> {
        @Override
        public Path deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String value = parser.getValueAsString();
            if (value == null) {
                return null;
            }
            if (value.startsWith("file:")) {
                return Path.of(URI.create(value));
            }
            return Path.of(value);
        }
    }

    private record ManifestDocument(List<ManifestBoard> boards) {
        private ManifestDocument {
            boards = boards == null ? List.of() : List.copyOf(boards);
        }
    }

    private record ManifestBoard(
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
        private ManifestBoard {
            additionalWritableRoots =
                    additionalWritableRoots == null ? List.of() : List.copyOf(additionalWritableRoots);
        }
    }
}
