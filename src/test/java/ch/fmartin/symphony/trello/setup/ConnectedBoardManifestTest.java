package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
    void findByBoardMatchesShortLinkFromBoardUrlWhenBoardKeyIsFullBoardId() {
        // given
        ConnectedBoard board = board(
                "000000000000000000000001",
                "000000000000000000000001",
                "Queue",
                "https://trello.com/b/SYNTH003/synthetic-board",
                Path.of("WORKFLOW.md"));
        ConnectedBoardManifest manifest = new ConnectedBoardManifest(List.of(board));

        // when
        var byShortLink = manifest.findByBoard("SYNTH003");
        var byUrl = manifest.findByBoard("https://trello.com/b/SYNTH003/synthetic-board");

        // then
        assertThat(byShortLink).contains(board);
        assertThat(byUrl).contains(board);
    }

    @Test
    void findByBoardStripsAccidentalQueryAndFragmentFromBareShortLinkSelectors() {
        // given
        ConnectedBoard board = board(
                "000000000000000000000001",
                "SYNTH003",
                "Queue",
                "https://trello.com/b/SYNTH003/synthetic-board",
                Path.of("WORKFLOW.md"));
        ConnectedBoardManifest manifest = new ConnectedBoardManifest(List.of(board));

        // when
        var byQuery = manifest.findByBoard("SYNTH003?utm=test");
        var byFragment = manifest.findByBoard("SYNTH003#fragment");
        var byQueryAfterSlash = manifest.findByBoard("SYNTH003/?utm=test");

        // then
        assertThat(byQuery).contains(board);
        assertThat(byFragment).contains(board);
        assertThat(byQueryAfterSlash).contains(board);
    }

    @Test
    void findByBoardPrefersExactNameMatchOverQueryStrippedSelector() {
        // given
        ConnectedBoard literallyNamed = board("board-1", "first", "SYNTH003?utm=test", Path.of("WORKFLOW.first.md"));
        ConnectedBoard shortLinked = board(
                "000000000000000000000002",
                "SYNTH003",
                "Queue",
                "https://trello.com/b/SYNTH003/synthetic-board",
                Path.of("WORKFLOW.second.md"));
        ConnectedBoardManifest manifest = new ConnectedBoardManifest(List.of(literallyNamed, shortLinked));

        // when
        var selected = manifest.findAllByBoard("SYNTH003?utm=test");

        // then
        assertThat(selected).containsExactly(literallyNamed);
    }

    @Test
    void findByBoardLeavesNameLikeSelectorsContainingQuestionMarksUnmatched() {
        // given
        ConnectedBoard board = board("board-1", "SYNTH003", "Queue", Path.of("WORKFLOW.md"));
        ConnectedBoardManifest manifest = new ConnectedBoardManifest(List.of(board));

        // when
        var unmatched = manifest.findByBoard("What? Board");

        // then
        assertThat(unmatched).isEmpty();
    }

    @Test
    void findByBoardRejectsNonTrelloUrlHosts() {
        // given
        ConnectedBoard board = board(
                "000000000000000000000001",
                "SYNTH003",
                "Queue",
                "https://trello.com/b/SYNTH003/synthetic-board",
                Path.of("WORKFLOW.md"));
        ConnectedBoardManifest manifest = new ConnectedBoardManifest(List.of(board));

        // when
        Throwable notTrello = catchThrowable(() -> manifest.findByBoard("https://not-trello.com/b/SYNTH003/anything"));
        Throwable prefixed = catchThrowable(() -> manifest.findByBoard("https://mytrello.com/b/SYNTH003/anything"));
        Throwable hyphenated =
                catchThrowable(() -> manifest.findByBoard("https://company-trello.com/b/SYNTH003/anything"));
        Throwable embedded =
                catchThrowable(() -> manifest.findByBoard("https://evil.example/https://trello.com/b/SYNTH003/x"));
        Throwable missingHost = catchThrowable(() -> manifest.findByBoard("https:///b/SYNTH003/anything"));

        // then
        assertThat(notTrello)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessage(
                        "Invalid --board value. Use a Trello board URL, short link, board id, or a connected board name.");
        assertThat(prefixed)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessage(
                        "Invalid --board value. Use a Trello board URL, short link, board id, or a connected board name.");
        assertThat(hyphenated)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessage(
                        "Invalid --board value. Use a Trello board URL, short link, board id, or a connected board name.");
        assertThat(embedded)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessage(
                        "Invalid --board value. Use a Trello board URL, short link, board id, or a connected board name.");
        assertThat(missingHost)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessage(
                        "Invalid --board value. Use a Trello board URL, short link, board id, or a connected board name.");
    }

    @Test
    void findByBoardRejectsTrelloCardUrlSelectors() {
        // given
        ConnectedBoard board = board(
                "000000000000000000000001",
                "SYNTH003",
                "Queue",
                "https://trello.com/b/SYNTH003/synthetic-board",
                Path.of("WORKFLOW.md"));
        ConnectedBoardManifest manifest = new ConnectedBoardManifest(List.of(board));

        // when
        Throwable cardUrl = catchThrowable(() -> manifest.findByBoard("https://trello.com/c/SYNTH003/not-a-board"));

        // then
        assertThat(cardUrl)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessage(
                        "Invalid --board value. Use a Trello board URL, short link, board id, or a connected board name.");
    }

    @Test
    void findByBoardPreservesExactUrlLikeBoardNameMatches() {
        // given
        ConnectedBoard board = board(
                "000000000000000000000001",
                "SYNTH003",
                "https://not-trello.com/b/team",
                "https://trello.com/b/SYNTH003/synthetic-board",
                Path.of("WORKFLOW.md"));
        ConnectedBoardManifest manifest = new ConnectedBoardManifest(List.of(board));

        // when
        var selected = manifest.findByBoard("https://not-trello.com/b/team");

        // then
        assertThat(selected).contains(board);
    }

    @Test
    void findByBoardAcceptsTrelloUrlHostsCaseInsensitively() {
        // given
        ConnectedBoard board = board(
                "000000000000000000000001",
                "SYNTH003",
                "Queue",
                "https://trello.com/b/SYNTH003/synthetic-board",
                Path.of("WORKFLOW.md"));
        ConnectedBoardManifest manifest = new ConnectedBoardManifest(List.of(board));

        // when
        var byUppercaseTrelloUrl = manifest.findByBoard("HTTPS://TRELLO.COM/b/SYNTH003/anything");
        var byWwwTrelloUrl = manifest.findByBoard("https://www.trello.com/b/SYNTH003/anything");

        // then
        assertThat(byUppercaseTrelloUrl).contains(board);
        assertThat(byWwwTrelloUrl).contains(board);
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
        var matches = manifest.findAllByWorkflow(workflow);

        // then
        assertThat(selected).contains(first);
        assertThat(matches).containsExactly(first, second);
    }

    private static ConnectedBoard board(String boardId, String boardKey, String boardName, Path workflowPath) {
        return board(boardId, boardKey, boardName, "https://trello.example/" + boardId, workflowPath);
    }

    private static ConnectedBoard board(
            String boardId, String boardKey, String boardName, String boardUrl, Path workflowPath) {
        return new ConnectedBoard(
                boardId,
                boardKey,
                boardName,
                boardUrl,
                workflowPath,
                Path.of(".env"),
                Path.of("workspaces"),
                ConfigDefaults.DEFAULT_SERVER_PORT,
                false,
                List.of(),
                false);
    }
}
