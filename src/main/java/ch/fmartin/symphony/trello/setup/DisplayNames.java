package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_LINE_SEPARATOR;
import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_PARAGRAPH_SEPARATOR;

import com.google.common.base.Strings;
import java.util.List;
import java.util.stream.Collectors;

/// Display-quoting for external Trello names in CLI output, generated prose, and diagnostics.
/// Trello allows quotes, backslashes, control characters such as newlines, Unicode line separators,
/// and bidirectional controls in board, list, and card names, so rendered names escape them: one
/// logical message must stay on one physical line with unambiguous quoted boundaries. Bidirectional
/// controls can reorder the rest of a terminal line and move the closing quote.
final class DisplayNames {
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

    private DisplayNames() {}

    static String quotedName(String name) {
        return "\"" + escape(Strings.nullToEmpty(name)) + "\"";
    }

    static String quotedList(List<String> names) {
        return names.stream().map(DisplayNames::quotedName).collect(Collectors.joining(", "));
    }

    /// Escapes one line of external text for terminal output, like [#quotedName] but without the
    /// quotes.
    static String escapedText(String text) {
        return escape(Strings.nullToEmpty(text));
    }

    private static String escape(String name) {
        var safe = new StringBuilder(name.length());
        for (int index = 0; index < name.length(); index++) {
            char current = name.charAt(index);
            switch (current) {
                case '\\' -> safe.append("\\\\");
                case '"' -> safe.append("\\\"");
                case '\n' -> safe.append("\\n");
                case '\r' -> safe.append("\\r");
                case '\t' -> safe.append("\\t");
                default -> {
                    if (Character.isISOControl(current) || isDisplayLayoutControl(current)) {
                        safe.append("\\u%04X".formatted((int) current));
                    } else {
                        safe.append(current);
                    }
                }
            }
        }
        return safe.toString();
    }

    private static boolean isDisplayLayoutControl(char character) {
        return switch (character) {
            case UNICODE_LINE_SEPARATOR,
                    UNICODE_PARAGRAPH_SEPARATOR,
                    ARABIC_LETTER_MARK,
                    LEFT_TO_RIGHT_MARK,
                    RIGHT_TO_LEFT_MARK,
                    LEFT_TO_RIGHT_EMBEDDING,
                    RIGHT_TO_LEFT_EMBEDDING,
                    POP_DIRECTIONAL_FORMAT,
                    LEFT_TO_RIGHT_OVERRIDE,
                    RIGHT_TO_LEFT_OVERRIDE,
                    LEFT_TO_RIGHT_ISOLATE,
                    RIGHT_TO_LEFT_ISOLATE,
                    FIRST_STRONG_ISOLATE,
                    POP_DIRECTIONAL_ISOLATE -> true;
            default -> false;
        };
    }
}
