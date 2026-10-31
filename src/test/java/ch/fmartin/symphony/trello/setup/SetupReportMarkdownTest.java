package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

final class SetupReportMarkdownTest {
    @CsvSource(
            delimiter = '|',
            quoteCharacter = '"',
            textBlock =
                    """
                # text                                          | escaped text
                start --config-dir <redacted> --app-home <path:5c43464e7e19> | start --config-dir \\<redacted> --app-home \\<path:5c43464e7e19>
                <id:0123456789ab> and <value:0123456789ab>      | \\<id:0123456789ab> and \\<value:0123456789ab>
                run `--lookup '<path:5c43464e7e19>'` first      | run `--lookup '<path:5c43464e7e19>'` first
                ``a ` <b>`` then <c>                            | ``a ` <b>`` then \\<c>
                unclosed ` <path:5c43464e7e19>                  | unclosed ` \\<path:5c43464e7e19>
                C:\\<redacted>                                  | C:\\\\\\<redacted>
                a < b and c > d                                 | a \\< b and c > d
                no markers here                                 | no markers here
                """)
    @ParameterizedTest
    void escapesHtmlStartsOutsideCodeSpans(String text, String escapedText) {
        // given
        String reportText = text;

        // when
        String escaped = SetupReportMarkdown.escapeHtmlStart(reportText);

        // then
        assertThat(escaped).isEqualTo(escapedText);
    }

    @CsvSource(
            delimiter = '|',
            textBlock =
                    """
                command | start --config-dir <redacted> | - **command:** start --config-dir \\<redacted>
                lookup_token | <path:5c43464e7e19> | - **lookup_token:** \\<path:5c43464e7e19>
                port | 19300 | - **port:** 19300
                """)
    @ParameterizedTest
    void lineEscapesHtmlStartsInTheValue(String label, String value, String expectedLine) {
        // given
        var body = new StringBuilder();

        // when
        SetupReportMarkdown.line(body, label, value);

        // then
        assertThat(body).hasToString(expectedLine + "\n");
    }

    @CsvSource({
        "1, redacted", "2, redacted", "3, redacted", "4, redacted",
        "1, path:0123456789ab", "2, path:0123456789ab", "3, path:0123456789ab", "4, path:0123456789ab"
    })
    @ParameterizedTest
    void renderedListsAndTablesPreserveBackslashesBeforeMarkers(int count, String marker) {
        // given
        String backslashes = "\\".repeat(count);
        String value = backslashes + "<" + marker + ">";

        // when
        List<String> rendered = renderListAndTable(value);

        // then
        assertThat(rendered).allSatisfy(html -> assertThat(html)
                .contains(backslashes + "&lt;" + marker + "&gt;")
                .doesNotContain("<redacted>", "<a "));
    }

    @MethodSource("backtickCases")
    @ParameterizedTest
    void renderedListsAndTablesRespectEscapedOpeningBackticks(String value, String visibleHtml) {
        // given
        String reportValue = value;

        // when
        List<String> rendered = renderListAndTable(reportValue);

        // then
        assertThat(rendered)
                .allSatisfy(html -> assertThat(html).contains(visibleHtml).doesNotContain("<redacted>", "<a "));
    }

    private static List<Arguments> backtickCases() {
        return List.of(
                Arguments.of("\\`<redacted>`", "`&lt;redacted&gt;`"),
                Arguments.of("\\`<path:0123456789ab>`", "`&lt;path:0123456789ab&gt;`"),
                Arguments.of("\\``<redacted>``", "``&lt;redacted&gt;``"),
                Arguments.of("\\``<redacted>`", "`<code>&lt;redacted&gt;</code>"),
                Arguments.of("\\\\`<redacted>`", "\\<code>&lt;redacted&gt;</code>"),
                Arguments.of("`\\<redacted>`", "<code>\\&lt;redacted&gt;</code>"),
                Arguments.of("``a ` <redacted>``", "<code>a ` &lt;redacted&gt;</code>"),
                Arguments.of("`<redacted>\\`", "<code>&lt;redacted&gt;\\</code>"));
    }

    private static List<String> renderListAndTable(String value) {
        var list = new StringBuilder();
        SetupReportMarkdown.line(list, "value", value);
        var table = new StringBuilder();
        MarkdownTable.leftAligned(List.of("value")).row(value).appendTo(table);
        return List.of(MarkdownRendering.render(list.toString()), MarkdownRendering.render(table.toString()));
    }
}
