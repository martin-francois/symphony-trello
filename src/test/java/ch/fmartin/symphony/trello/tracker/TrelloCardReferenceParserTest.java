package ch.fmartin.symphony.trello.tracker;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_LINE_SEPARATOR;
import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_NEXT_LINE;
import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_PARAGRAPH_SEPARATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

final class TrelloCardReferenceParserTest {
    private static final String VERTICAL_TAB = "\u000B";
    private static final String FIRST_URL = "https://trello.com/c/SYNTH101";
    private static final String SECOND_URL = "https://trello.com/c/SYNTH102";

    @MethodSource("lineBreaks")
    @ParameterizedTest
    void keepsTheSourceLineForEachReference(String lineBreak) {
        // given
        String firstLine = "  See [first](" + FIRST_URL + "/details).\t";
        String secondLine = "Also see " + SECOND_URL;
        String text = "Introduction" + lineBreak + firstLine + lineBreak + secondLine + lineBreak + "End";

        // when
        var references = TrelloCardReferenceParser.referencesWithSourceLinesIn(text);

        // then
        assertThat(references)
                .extracting(line -> line.reference().lookupId(), TrelloCardReferenceParser.ReferencedLine::text)
                .containsExactly(tuple("SYNTH101", firstLine), tuple("SYNTH102", secondLine));
        assertThat(references)
                .extracting(TrelloCardReferenceParser.ReferencedLine::reference)
                .containsExactlyElementsOf(TrelloCardReferenceParser.referencesIn(text));
    }

    @Test
    void preservesMultipleUrlsOnOneLineAndRepeatedOccurrences() {
        // given
        String line = FIRST_URL + " and " + SECOND_URL;
        String text = line + "\n" + FIRST_URL;

        // when
        var references = TrelloCardReferenceParser.referencesWithSourceLinesIn(text);

        // then
        assertThat(references)
                .extracting(entry -> entry.reference().lookupId(), TrelloCardReferenceParser.ReferencedLine::text)
                .containsExactly(tuple("SYNTH101", line), tuple("SYNTH102", line), tuple("SYNTH101", FIRST_URL));
    }

    @NullAndEmptySource
    @ParameterizedTest
    @ValueSource(strings = {"  \r\n", "No card reference"})
    void emptyAndUnmatchedTextHasNoSourceLines(String text) {
        // given
        String candidate = text;

        // when
        var references = TrelloCardReferenceParser.referencesWithSourceLinesIn(candidate);
        var exact = TrelloCardReferenceParser.exactReferenceWithSourceLine(candidate);

        // then
        assertThat(references).isEmpty();
        assertThat(exact).isEmpty();
    }

    private static Stream<String> lineBreaks() {
        return Stream.of(
                "\n",
                "\r\n",
                "\r",
                "\f",
                VERTICAL_TAB,
                UNICODE_NEXT_LINE,
                String.valueOf(UNICODE_LINE_SEPARATOR),
                String.valueOf(UNICODE_PARAGRAPH_SEPARATOR));
    }
}
