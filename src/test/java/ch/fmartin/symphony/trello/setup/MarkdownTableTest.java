package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownTableTest {
    @Test
    void rendersBasicTable() {
        // given
        MarkdownTable table = MarkdownTable.of(
                        List.of("name", "status"), List.of(MarkdownTable.Alignment.LEFT, MarkdownTable.Alignment.LEFT))
                .row("git", "available")
                .row("codex", "missing");
        StringBuilder body = new StringBuilder();

        // when
        table.appendTo(body);

        // then
        assertThat(body.toString())
                .isEqualTo(
                        """
                        | name | status |
                        | --- | --- |
                        | git | available |
                        | codex | missing |
                        """);
    }

    @Test
    void rendersRightAlignmentMarker() {
        // given
        MarkdownTable table = MarkdownTable.of(
                        List.of("name", "count"), List.of(MarkdownTable.Alignment.LEFT, MarkdownTable.Alignment.RIGHT))
                .row("boards", 3);
        StringBuilder body = new StringBuilder();

        // when
        table.appendTo(body);

        // then
        assertThat(body.toString()).contains("| --- | ---: |");
    }

    @Test
    void escapesPipesInCellValues() {
        // given
        MarkdownTable table = MarkdownTable.of(List.of("value"), List.of(MarkdownTable.Alignment.LEFT))
                .row("a|b");
        StringBuilder body = new StringBuilder();

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
        StringBuilder body = new StringBuilder();

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
