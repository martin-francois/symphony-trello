package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        // when
        String escaped = SetupReportMarkdown.escapeHtmlStart(text);

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
}
