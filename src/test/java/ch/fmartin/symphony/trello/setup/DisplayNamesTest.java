package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class DisplayNamesTest {
    @MethodSource("escapedNames")
    @ParameterizedTest
    void quotedNameEscapesAmbiguousAndControlCharacters(String name, String expected) {
        // given
        String input = name;

        // when
        String quoted = DisplayNames.quotedName(input);

        // then
        assertThat(quoted).isEqualTo(expected);
        assertThat(quoted).doesNotContain("\n", "\r", "\t", "\u001B", "\u0000");
    }

    private static Stream<Arguments> escapedNames() {
        return Stream.of(
                Arguments.of("Plain Board", "\"Plain Board\""),
                Arguments.of("Has \"quotes\"", "\"Has \\\"quotes\\\"\""),
                Arguments.of("Back\\slash", "\"Back\\\\slash\""),
                Arguments.of("Line\nBreak", "\"Line\\nBreak\""),
                Arguments.of("Carriage\rReturn", "\"Carriage\\rReturn\""),
                Arguments.of("Tab\tStop", "\"Tab\\tStop\""),
                Arguments.of("Esc\u001BSeq", "\"Esc\\u001BSeq\""),
                Arguments.of("Null\u0000Byte", "\"Null\\u0000Byte\""),
                Arguments.of("Unicode \u00E9\u00FC\u4E16", "\"Unicode \u00E9\u00FC\u4E16\""),
                Arguments.of("Slash/Name", "\"Slash/Name\""),
                Arguments.of(null, "\"\""));
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
