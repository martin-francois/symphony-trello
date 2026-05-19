package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConnectedBoardManifestTest {
    @Test
    void findByBoardHandlesMissingBoardKey() {
        // given
        ConnectedBoard board = new ConnectedBoard(
                "board-1",
                null,
                "Queue",
                "https://trello.example/board-1",
                Path.of("WORKFLOW.md"),
                Path.of(".env"),
                Path.of("workspaces"),
                ConfigDefaults.DEFAULT_SERVER_PORT,
                false,
                List.of(),
                false);
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
}
