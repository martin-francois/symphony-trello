package ch.fmartin.symphony.trello.boardsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BoardSessionInstructionsTest {
    @Test
    void developerInstructionsNameTheBoardAndListRolesBeforeTheFirstRequest() {
        // given
        BoardSessionContext context = BoardSessionFixtures.context(BoardSessionFixtures.defaultRoles());

        // when
        String instructions = BoardSessionInstructions.developerInstructions(context);

        // then
        assertThat(instructions)
                .startsWith("Symphony for Trello started this Codex session")
                .contains(
                        "There is no current card",
                        "Selected board: Symphony Work Queue, short link SYNTH001",
                        "queue lists for new work: Ready for Codex.",
                        "in-progress list: In Progress.",
                        "review list: Human Review.",
                        "blocked list: Blocked.",
                        "terminal lists: Done.",
                        "default list for new cards: Ready for Codex.",
                        BoardSessionTools.BOARD_OVERVIEW,
                        "ask the user which list to use");
    }

    @Test
    void developerInstructionsStayCommandLineSafeForUnusualTrelloNames() {
        // given
        var roles = BoardListRoles.of(
                List.of("Ready \"now\" & later", "Back\\log"),
                List.of(),
                Optional.empty(),
                Optional.of("Review\n$(rm -rf ~)"),
                Optional.empty(),
                List.of("100% done!"));
        var context = new BoardSessionContext("Team's \"Q4\" board | ops", "abc123", "WORKFLOW.team.md", roles);

        // when
        String instructions = BoardSessionInstructions.developerInstructions(context);

        // then
        assertThat(BoardSessionInstructions.COMMAND_LINE_SAFE_CHARACTERS.matchesAllOf(instructions))
                .as("every character must survive command-line quoting unchanged: %s", instructions)
                .isTrue();
        assertThat(instructions)
                .contains(
                        "Selected board: Team s Q4 board ops,",
                        "active lists: Ready now later, Back log.",
                        "review list: Review rm -rf.",
                        "terminal lists: 100 done.",
                        "default list for new cards: none, ask the user.");
    }

    @Test
    void serverInstructionsKeepExactNamesOnSingleLines() {
        // given
        var roles = BoardListRoles.of(
                List.of("Ready \"now\""),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of("Done"));
        var context = new BoardSessionContext("Line\nbreak board", "abc123", "WORKFLOW.team.md", roles);

        // when
        String instructions = BoardSessionInstructions.serverInstructions(context);

        // then
        assertThat(instructions.lines())
                .contains(
                        "Selected board: Line break board (short link abc123, workflow file WORKFLOW.team.md).",
                        "Active lists: Ready \"now\".",
                        "Default list for new cards: Ready \"now\".");
    }

    @Test
    void commandLineNamesAreBoundedAndNeverEmpty() {
        // given
        String longName = "x".repeat(500);

        // when
        String bounded = BoardSessionInstructions.commandLineName(longName);
        String onlyQuotes = BoardSessionInstructions.commandLineName("\"\"");

        // then
        assertThat(bounded).hasSize(BoardSessionInstructions.COMMAND_LINE_NAME_LIMIT);
        assertThat(onlyQuotes).isEqualTo("unnamed");
    }
}
