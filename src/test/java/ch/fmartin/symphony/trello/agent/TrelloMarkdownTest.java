package ch.fmartin.symphony.trello.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TrelloMarkdownTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("leadingHashtagCases")
    void escapesLeadingHashtagsThatTrelloWouldRenderAsHeadings(String displayName, String markdown, String expected) {
        // given
        String inputMarkdown = markdown;

        // when
        String escaped = TrelloMarkdown.escapeLeadingHashtags(inputMarkdown);

        // then
        assertThat(escaped).as(displayName).isEqualTo(expected);
    }

    private static Stream<Arguments> leadingHashtagCases() {
        return Stream.of(
                Arguments.of("paragraph starts with issue reference", "#2076: Fix it", "\\#2076: Fix it"),
                Arguments.of("paragraph keeps indentation", "  #2076: Fix it", "  \\#2076: Fix it"),
                Arguments.of("dash bullet starts with issue reference", "- #2076: Fix it", "- \\#2076: Fix it"),
                Arguments.of("star bullet starts with issue reference", "* #2076: Fix it", "* \\#2076: Fix it"),
                Arguments.of("plus bullet starts with issue reference", "+ #2076: Fix it", "+ \\#2076: Fix it"),
                Arguments.of(
                        "nested dash bullet starts with issue reference",
                        "- Parent\n    - #2076: Fix it",
                        "- Parent\n    - \\#2076: Fix it"),
                Arguments.of(
                        "nested dash bullet after blank line starts with issue reference",
                        "- Parent\n\n    - #2076: Fix it",
                        "- Parent\n\n    - \\#2076: Fix it"),
                Arguments.of(
                        "nested dash bullet after continuation starts with issue reference",
                        "- Parent\n    continuation\n    - #2076: Fix it",
                        "- Parent\n    continuation\n    - \\#2076: Fix it"),
                Arguments.of(
                        "list continuation starts with issue reference",
                        "- Parent\n    #2076: Details",
                        "- Parent\n    \\#2076: Details"),
                Arguments.of(
                        "list-indented code block is unchanged",
                        "- Parent\n      #2076: literal\n    #2077: Details",
                        "- Parent\n      #2076: literal\n    \\#2077: Details"),
                Arguments.of(
                        "list-indented fenced code block is unchanged",
                        "- Parent\n    ```\n    #2076: literal\n    ```\n    #2077: Details",
                        "- Parent\n    ```\n    #2076: literal\n    ```\n    \\#2077: Details"),
                Arguments.of("later issue reference is unchanged", "- Fix #2076 next", "- Fix #2076 next"),
                Arguments.of("markdown heading is unchanged", "### Validation", "### Validation"),
                Arguments.of("bullet heading is unchanged", "- ### Validation", "- ### Validation"),
                Arguments.of(
                        "fenced code block is unchanged",
                        "```text\n#2076: literal\n- #2077: literal\n```\n#2078: prose",
                        "```text\n#2076: literal\n- #2077: literal\n```\n\\#2078: prose"),
                Arguments.of(
                        "fence with supported indentation is unchanged",
                        "   ```text\n#2076: literal\n   ```\n#2077: prose",
                        "   ```text\n#2076: literal\n   ```\n\\#2077: prose"),
                Arguments.of(
                        "tilde fenced code block is unchanged",
                        "~~~\n#2076: literal\n~~~\n- #2077: prose",
                        "~~~\n#2076: literal\n~~~\n- \\#2077: prose"),
                Arguments.of(
                        "indented code block is unchanged",
                        "    #2076: literal\n    - #2077: literal\n\t- #2078: literal\n  #2079: prose",
                        "    #2076: literal\n    - #2077: literal\n\t- #2078: literal\n  \\#2079: prose"),
                Arguments.of(
                        "indented fence-looking code does not start a fence",
                        "    ```text\n#2076: prose",
                        "    ```text\n\\#2076: prose"),
                Arguments.of(
                        "fence marker with trailing text does not close code block",
                        "```\n```text\n#2076: literal\n```\n#2077: prose",
                        "```\n```text\n#2076: literal\n```\n\\#2077: prose"),
                Arguments.of("already escaped paragraph is unchanged", "\\#2076: Fix it", "\\#2076: Fix it"),
                Arguments.of("already escaped bullet is unchanged", "- \\#2076: Fix it", "- \\#2076: Fix it"),
                Arguments.of(
                        "preserves line separators",
                        "#2076: Fix it\r\nNo change #2077\n- #2078: Also fix it",
                        "\\#2076: Fix it\r\nNo change #2077\n- \\#2078: Also fix it"));
    }
}
