package ch.fmartin.symphony.trello.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class SymphonyCommentFooterTest {
    private static final String PLAIN_FOOTER = "_Managed by Symphony_";
    private static final String MIDDLE_DOT = "\u00B7";
    private static final String LINK_DETAIL = "[View the blocker](https://trello.com/c/SYNTH101#comment-action-1)";

    @Test
    void appendsPlainFooterAsItsOwnFinalParagraph() {
        // given
        String body = "Ready for review.\n\nPR: <https://github.com/example/project/pull/1>\n";

        // when
        String text = SymphonyCommentFooter.append(body);

        // then
        assertThat(text)
                .isEqualTo("Ready for review.\n\nPR: <https://github.com/example/project/pull/1>\n\n" + PLAIN_FOOTER);
    }

    @Test
    void appendsDetailedFooterWithMiddleDotSeparator() {
        // given
        String body = "Checking.";

        // when
        String text = SymphonyCommentFooter.append(body, LINK_DETAIL);

        // then
        assertThat(text).isEqualTo("Checking.\n\n_Managed by Symphony " + MIDDLE_DOT + " " + LINK_DETAIL + "_");
    }

    @Test
    void parsesPlainAndDetailedFootersBackToBodyAndDetail() {
        // given
        String plain = SymphonyCommentFooter.append("Plain body");
        String detailed = SymphonyCommentFooter.append("Detailed body", LINK_DETAIL);

        // when
        var parsedPlain = SymphonyCommentFooter.parse(plain);
        var parsedDetailed = SymphonyCommentFooter.parse(detailed);

        // then
        assertThat(parsedPlain).contains(new SymphonyCommentFooter.Footer("Plain body", null));
        assertThat(parsedDetailed).contains(new SymphonyCommentFooter.Footer("Detailed body", LINK_DETAIL));
    }

    @Test
    void footerOnlyCommentParsesToAnEmptyBody() {
        // given
        String footerOnly = PLAIN_FOOTER;

        // when
        var parsed = SymphonyCommentFooter.parse(footerOnly);

        // then
        assertThat(parsed).contains(new SymphonyCommentFooter.Footer("", null));
    }

    @MethodSource("textsWithoutCanonicalFooter")
    @ParameterizedTest(name = "{0}")
    void malformedMisplacedOrSimilarFooterTextIsNotAFooter(String scenario, String text) {
        // given

        // when
        var parsed = SymphonyCommentFooter.parse(text);

        // then
        assertThat(parsed).as(scenario).isEmpty();
        assertThat(SymphonyCommentFooter.withoutTrailingFooters(text))
                .as("%s stays byte-for-byte unchanged", scenario)
                .isEqualTo(text);
    }

    private static Stream<Arguments> textsWithoutCanonicalFooter() {
        return Stream.of(
                Arguments.of("ordinary comment", "Looks good to me."),
                Arguments.of("footer copied into the middle", "Note\n\n" + PLAIN_FOOTER + "\n\nMore human text"),
                Arguments.of("footer in the same paragraph", "Human text\n" + PLAIN_FOOTER),
                Arguments.of("footer on the same line", "Human text " + PLAIN_FOOTER),
                Arguments.of("missing emphasis", "Human text\n\nManaged by Symphony"),
                Arguments.of("missing closing emphasis", "Human text\n\n_Managed by Symphony"),
                Arguments.of("bold instead of italic", "Human text\n\n**Managed by Symphony**"),
                Arguments.of("different capitalization", "Human text\n\n_managed by Symphony_"),
                Arguments.of("trailing whitespace after footer", "Human text\n\n" + PLAIN_FOOTER + " "),
                Arguments.of("trailing newline after footer", "Human text\n\n" + PLAIN_FOOTER + "\n"),
                Arguments.of("empty detail", "Human text\n\n_Managed by Symphony " + MIDDLE_DOT + " _"),
                Arguments.of("blank detail", "Human text\n\n_Managed by Symphony " + MIDDLE_DOT + "   _"),
                Arguments.of("hyphen instead of middle dot", "Human text\n\n_Managed by Symphony - detail_"),
                Arguments.of("longer attribution", "Human text\n\n_Managed by Symphony and me_"));
    }

    @Test
    void parseIgnoresNullText() {
        // given
        String missingText = null;

        // when
        var parsed = SymphonyCommentFooter.parse(missingText);

        // then
        assertThat(parsed).isEmpty();
    }

    @Test
    void stripsEveryDuplicateTrailingFooterSoReformattingProducesExactlyOne() {
        // given
        String duplicated = "Body\n\n" + PLAIN_FOOTER + "\n\n" + PLAIN_FOOTER + "\n\n"
                + SymphonyCommentFooter.append("", LINK_DETAIL);

        // when
        String reformatted = SymphonyCommentFooter.append(SymphonyCommentFooter.withoutTrailingFooters(duplicated));

        // then
        assertThat(reformatted).isEqualTo("Body\n\n" + PLAIN_FOOTER);
    }

    @Test
    void reformattingAnAlreadyFooteredCommentIsIdempotent() {
        // given
        String once = SymphonyCommentFooter.append("Body");

        // when
        String twice = SymphonyCommentFooter.append(SymphonyCommentFooter.withoutTrailingFooters(once));

        // then
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void acceptsWindowsLineBreaksBeforeTheFooterParagraph() {
        // given
        String text = "Body\r\n\r\n" + PLAIN_FOOTER;

        // when
        var parsed = SymphonyCommentFooter.parse(text);

        // then
        assertThat(parsed).contains(new SymphonyCommentFooter.Footer("Body", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "two\nlines", " padded", "_emphasis edge", "emphasis edge_"})
    void rejectsFooterDetailThatWouldNotRenderAsOneReadableLine(String detail) {
        // given

        // when
        Throwable thrown = catchThrowable(() -> SymphonyCommentFooter.append("Body", detail));

        // then
        assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Footer detail");
    }
}
