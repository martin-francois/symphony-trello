package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class DisplayNamesTest {
    private static final String ESCAPE_IN_DISPLAY_NAME = "\u001B";
    private static final String NUL_IN_DISPLAY_NAME = "\u0000";
    private static final String NON_ASCII_DISPLAY_NAME = "\u00E9\u00FC\u4E16";

    @MethodSource("escapedNames")
    @ParameterizedTest(name = "{0}")
    void quotedNameEscapesAmbiguousAndControlCharacters(String scenario, String name, String expected) {
        // given
        String input = name;

        // when
        String quoted = DisplayNames.quotedName(input);

        // then
        assertThat(quoted).as(scenario).isEqualTo(expected);
        assertThat(quoted).doesNotContain("\n", "\r", "\t", ESCAPE_IN_DISPLAY_NAME, NUL_IN_DISPLAY_NAME);
    }

    private static Stream<Arguments> escapedNames() {
        return Stream.of(
                Arguments.of("plain name", "Plain Board", "\"Plain Board\""),
                Arguments.of("quoted name", "Has \"quotes\"", "\"Has \\\"quotes\\\"\""),
                Arguments.of("backslash in name", "Back\\slash", "\"Back\\\\slash\""),
                Arguments.of("line feed in name", "Line\nBreak", "\"Line\\nBreak\""),
                Arguments.of("carriage return in name", "Carriage\rReturn", "\"Carriage\\rReturn\""),
                Arguments.of("tab in name", "Tab\tStop", "\"Tab\\tStop\""),
                Arguments.of("escape control in name", "Esc" + ESCAPE_IN_DISPLAY_NAME + "Seq", "\"Esc\\u001BSeq\""),
                Arguments.of("NUL in name", "Null" + NUL_IN_DISPLAY_NAME + "Byte", "\"Null\\u0000Byte\""),
                Arguments.of(
                        "non-ASCII display name",
                        "Unicode " + NON_ASCII_DISPLAY_NAME,
                        "\"Unicode " + NON_ASCII_DISPLAY_NAME + "\""),
                Arguments.of("slash in name", "Slash/Name", "\"Slash/Name\""),
                Arguments.of("missing name", null, "\"\""));
    }

    @MethodSource("escapedLists")
    @ParameterizedTest
    void quotedListJoinsEscapedEntries(List<String> names, String expected) {
        // given
        List<String> input = names;

        // when
        String quoted = DisplayNames.quotedList(input);

        // then
        assertThat(quoted).isEqualTo(expected);
    }

    private static Stream<Arguments> escapedLists() {
        return Stream.of(
                Arguments.of(List.of(), ""),
                Arguments.of(List.of("One"), "\"One\""),
                Arguments.of(List.of("A\nB", "C \"D\""), "\"A\\nB\", \"C \\\"D\\\"\""));
    }
}
