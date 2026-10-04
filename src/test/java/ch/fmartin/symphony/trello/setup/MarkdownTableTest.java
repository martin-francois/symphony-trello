package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class MarkdownTableTest {
    @Test
    void rendersBasicTable() {
        // given
        MarkdownTable table = MarkdownTable.of(
                        List.of("name", "status"), List.of(MarkdownTable.Alignment.LEFT, MarkdownTable.Alignment.LEFT))
                .row("git", "available")
                .row("codex", "missing");
        var body = new StringBuilder();

        // when
        table.appendTo(body);

        // then
        assertThat(body)
                .hasToString(
                        """
                | name | status |
                | --- | --- |
                | git | available |
                | codex | missing |
                """);
    }

    @Test
    void leftAlignedRendersLeftMarkerForEveryColumn() {
        // given
        MarkdownTable table =
                MarkdownTable.leftAligned(List.of("name", "status", "detail")).row("git", "available", "system");
        var body = new StringBuilder();

        // when
        table.appendTo(body);

        // then
        assertThat(body.toString()).contains("| --- | --- | --- |");
    }

    @CsvSource({"RIGHT, '| --- | ---: |'", "CENTER, '| --- | :---: |'"})
    @ParameterizedTest
    void rendersAlignmentMarker(MarkdownTable.Alignment alignment, String separatorRow) {
        // given
        MarkdownTable table = MarkdownTable.of(
                        List.of("name", "count"), List.of(MarkdownTable.Alignment.LEFT, alignment))
                .row("boards", 3);
        var body = new StringBuilder();

        // when
        table.appendTo(body);

        // then
        assertThat(body).contains(separatorRow);
    }

    @Test
    void rendersNullAndEmptyValuesAsEmptyCells() {
        // given
        MarkdownTable table = MarkdownTable.leftAligned(List.of("board_hash", "key_hash", "port"))
                .row(null, "", 19301);
        var body = new StringBuilder();

        // when
        table.appendTo(body);

        // then
        assertThat(body).endsWith("|  |  | 19301 |\n");
    }

    @Test
    void keepsMarkdownCharactersOtherThanPipesVerbatim() {
        // given
        String cell = "Bearer <redacted> `corepack` *_draft_* [x] & C:\\work";
        MarkdownTable table = MarkdownTable.leftAligned(List.of("danger_full_access", "detail"))
                .row(false, cell);
        var body = new StringBuilder();

        // when
        table.appendTo(body);

        // then
        assertThat(body)
                .as("diagnostics reports are pasted as plain text, so only table syntax may be escaped")
                .hasToString(
                        """
                | danger_full_access | detail |
                | --- | --- |
                | false | %s |
                """
                                .formatted(cell));
    }

    @Test
    void escapesPipesInCellValues() {
        // given
        MarkdownTable table = MarkdownTable.of(List.of("value"), List.of(MarkdownTable.Alignment.LEFT))
                .row("a|b");
        var body = new StringBuilder();

        // when
        table.appendTo(body);

        // then
        assertThat(body.toString()).contains("| a\\|b |");
    }

    @Test
    void replacesNewlinesWithSpacesInCellValues() {
        // given
        MarkdownTable table = MarkdownTable.of(List.of("value"), List.of(MarkdownTable.Alignment.LEFT))
                .row("first\r\nsecond\nthird");
        var body = new StringBuilder();

        // when
        table.appendTo(body);

        // then
        assertThat(body.toString()).contains("| first  second third |");
    }

    @Test
    void rejectsRowsWithTooFewCells() {
        // given
        MarkdownTable table = MarkdownTable.of(
                List.of("name", "status"), List.of(MarkdownTable.Alignment.LEFT, MarkdownTable.Alignment.LEFT));

        // when
        var thrown = assertThatThrownBy(() -> table.row("git"));

        // then
        thrown.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expected 2 but got 1");
    }

    @Test
    void rejectsRowsWithTooManyCells() {
        // given
        MarkdownTable table = MarkdownTable.of(List.of("name"), List.of(MarkdownTable.Alignment.LEFT));

        // when
        var thrown = assertThatThrownBy(() -> table.row("git", "available"));

        // then
        thrown.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expected 1 but got 2");
    }
}
