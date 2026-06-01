package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ConnectedBoardManifestTest {
    @Test
    void findByBoardHandlesMissingBoardKey() {
        // given
        ConnectedBoard board = board("board-1", null, "Queue", Path.of("WORKFLOW.md"));
        ConnectedBoardManifest manifest = new ConnectedBoardManifest(List.of(board));

        // when
        var byName = manifest.findByBoard("Queue");
        var byId = manifest.findByBoard("board-1");
        var missing = manifest.findByBoard("other");

        // then
        assertThat(byName).contains(board);
        assertThat(byId).contains(board);
        assertThat(missing).isEmpty();
    }

    @Test
    void findByBoardKeepsManifestOrderWhenNamesAreDuplicated() {
        // given
        ConnectedBoard first = board("board-1", "first", "Queue", Path.of("WORKFLOW.first.md"));
        ConnectedBoard second = board("board-2", "second", "Queue", Path.of("WORKFLOW.second.md"));
        ConnectedBoardManifest manifest = new ConnectedBoardManifest(List.of(first, second));

        // when
        var selected = manifest.findByBoard("Queue");

        // then
        assertThat(selected).contains(first);
    }

    @Test
    void findByWorkflowKeepsManifestOrderWhenWorkflowPathsAreDuplicated() {
        // given
        Path workflow = Path.of("WORKFLOW.md");
        ConnectedBoard first = board("board-1", "first", "First Queue", workflow);
        ConnectedBoard second = board("board-2", "second", "Second Queue", workflow);
        ConnectedBoardManifest manifest = new ConnectedBoardManifest(List.of(first, second));

        // when
        var selected = manifest.findByWorkflow(workflow);

        // then
        assertThat(selected).contains(first);
    }

    private static ConnectedBoard board(String boardId, String boardKey, String boardName, Path workflowPath) {
        return new ConnectedBoard(
                boardId,
                boardKey,
                boardName,
                "https://trello.example/" + boardId,
                workflowPath,
                Path.of(".env"),
                Path.of("workspaces"),
                ConfigDefaults.DEFAULT_SERVER_PORT,
                false,
                List.of(),
                false);
    }
}
