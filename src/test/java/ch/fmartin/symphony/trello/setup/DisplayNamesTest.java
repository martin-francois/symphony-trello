package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_LINE_SEPARATOR;
import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_PARAGRAPH_SEPARATOR;
import static ch.fmartin.symphony.trello.setup.TerminalInputFixture.ESCAPE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class DisplayNamesTest {
    private static final int ARABIC_LETTER_MARK = 0x061C;
    private static final int LEFT_TO_RIGHT_MARK = 0x200E;
    private static final int RIGHT_TO_LEFT_MARK = 0x200F;
    private static final int LEFT_TO_RIGHT_EMBEDDING = 0x202A;
    private static final int RIGHT_TO_LEFT_EMBEDDING = 0x202B;
    private static final int POP_DIRECTIONAL_FORMAT = 0x202C;
    private static final int LEFT_TO_RIGHT_OVERRIDE = 0x202D;
    private static final int RIGHT_TO_LEFT_OVERRIDE = 0x202E;
    private static final int LEFT_TO_RIGHT_ISOLATE = 0x2066;
    private static final int RIGHT_TO_LEFT_ISOLATE = 0x2067;
    private static final int FIRST_STRONG_ISOLATE = 0x2068;
    private static final int POP_DIRECTIONAL_ISOLATE = 0x2069;
    private static final int WOMAN_EMOJI = 0x1F469;
    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int LAPTOP_EMOJI = 0x1F4BB;
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
        assertThat(quoted).doesNotContain("\n", "\r", "\t", ESCAPE, NUL_IN_DISPLAY_NAME);
    }

    private static Arguments escapedLayoutControl(String scenario, int codePoint) {
        String displayEscape = "\\u%04X".formatted(codePoint);
        return Arguments.of(scenario, "Before" + (char) codePoint + "After", "\"Before" + displayEscape + "After\"");
    }

    private static String emojiSequence() {
        return new String(Character.toChars(WOMAN_EMOJI))
                + (char) ZERO_WIDTH_JOINER
                + new String(Character.toChars(LAPTOP_EMOJI));
    }

    private static Stream<Arguments> escapedNames() {
        return Stream.of(
                Arguments.of("plain name", "Plain Board", "\"Plain Board\""),
                Arguments.of("quoted name", "Has \"quotes\"", "\"Has \\\"quotes\\\"\""),
                Arguments.of("backslash in name", "Back\\slash", "\"Back\\\\slash\""),
                Arguments.of("line feed in name", "Line\nBreak", "\"Line\\nBreak\""),
                Arguments.of("carriage return in name", "Carriage\rReturn", "\"Carriage\\rReturn\""),
                Arguments.of("tab in name", "Tab\tStop", "\"Tab\\tStop\""),
                Arguments.of("escape control in name", "Esc" + ESCAPE + "Seq", "\"Esc\\u001BSeq\""),
                Arguments.of("NUL in name", "Null" + NUL_IN_DISPLAY_NAME + "Byte", "\"Null\\u0000Byte\""),
                Arguments.of(
                        "non-ASCII display name",
                        "Unicode " + NON_ASCII_DISPLAY_NAME,
                        "\"Unicode " + NON_ASCII_DISPLAY_NAME + "\""),
                Arguments.of("slash in name", "Slash/Name", "\"Slash/Name\""),
                escapedLayoutControl("line separator in name", UNICODE_LINE_SEPARATOR),
                escapedLayoutControl("paragraph separator in name", UNICODE_PARAGRAPH_SEPARATOR),
                escapedLayoutControl("Arabic letter mark in name", ARABIC_LETTER_MARK),
                escapedLayoutControl("left-to-right mark in name", LEFT_TO_RIGHT_MARK),
                escapedLayoutControl("right-to-left mark in name", RIGHT_TO_LEFT_MARK),
                escapedLayoutControl("left-to-right embedding in name", LEFT_TO_RIGHT_EMBEDDING),
                escapedLayoutControl("right-to-left embedding in name", RIGHT_TO_LEFT_EMBEDDING),
                escapedLayoutControl("pop directional format in name", POP_DIRECTIONAL_FORMAT),
                escapedLayoutControl("left-to-right override in name", LEFT_TO_RIGHT_OVERRIDE),
                escapedLayoutControl("right-to-left override in name", RIGHT_TO_LEFT_OVERRIDE),
                escapedLayoutControl("left-to-right isolate in name", LEFT_TO_RIGHT_ISOLATE),
                escapedLayoutControl("right-to-left isolate in name", RIGHT_TO_LEFT_ISOLATE),
                escapedLayoutControl("first strong isolate in name", FIRST_STRONG_ISOLATE),
                escapedLayoutControl("pop directional isolate in name", POP_DIRECTIONAL_ISOLATE),
                Arguments.of("zero-width joiner in emoji name", emojiSequence(), "\"" + emojiSequence() + "\""),
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
