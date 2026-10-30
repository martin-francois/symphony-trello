package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

final class CardCommandTextTest {
    private static final String TERMINAL_ESCAPE = "\u001B";
    private static final BoardCardCommands.ListView INBOX =
            new BoardCardCommands.ListView("list-inbox", "Inbox", List.of(), false, true);

    @Test
    void urlsAndShortLinksAreEscapedLikeTrelloText() {
        // given
        var board = new BoardCardCommands.BoardView(
                "Queue", "board-1", "https://trello.com/b/SYNTH001\n" + TERMINAL_ESCAPE + "[2J", "WORKFLOW.md");
        var card = new BoardCardCommands.CardSummary(
                "SYNTH0000000000000000101",
                "SYNTH101\nX",
                "https://trello.com/c/SYNTH101" + TERMINAL_ESCAPE + "[31m",
                "Fix");
        var listed = new BoardCardCommands.Listed(
                BoardCardCommands.ReadStatus.OK,
                board,
                List.of(new BoardCardCommands.ListWithCards(INBOX, List.of(card))));
        var created = new BoardCardCommands.Changed(BoardCardCommands.Change.CARD_CREATED, board, card, INBOX);

        // when
        String listText = CardCommandText.render(listed);
        String createdText = CardCommandText.render(created);

        // then
        assertThat(listText.lines())
                .contains("Board \"Queue\" https://trello.com/b/SYNTH001\\n\\u001B[2J", "  SYNTH101\\nX  \"Fix\"");
        assertThat(createdText)
                .isEqualTo("Created card \"Fix\" in \"Inbox\": https://trello.com/c/SYNTH101\\u001B[31m");
    }

    @Test
    void showEscapesTheCardUrl() {
        // given
        var board = new BoardCardCommands.BoardView("Queue", "board-1", "https://trello.com/b/SYNTH001", "WORKFLOW.md");
        var card = new BoardCardCommands.CardDetails(
                "SYNTH0000000000000000101",
                null,
                "https://trello.com/c/SYNTH0000000000000000101\r" + TERMINAL_ESCAPE + "[1A",
                "Fix",
                "",
                INBOX,
                false,
                List.of(),
                List.of(),
                List.of(),
                List.of());

        // when
        String text = CardCommandText.render(new BoardCardCommands.Shown(BoardCardCommands.ReadStatus.OK, board, card));

        // then
        assertThat(text.lines()).contains("  URL: https://trello.com/c/SYNTH0000000000000000101\\r\\u001B[1A");
    }
}
