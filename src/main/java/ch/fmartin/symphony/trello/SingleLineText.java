package ch.fmartin.symphony.trello;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNSAFE_SINGLE_LINE_CHARACTERS;

/// Escapes external text so that a message that shows it stays one physical line: quotes and
/// backslashes get a backslash, line feed, carriage return, and tab become their backslash escapes,
/// and other control or line-separator characters become a backslash, `u`, and four hex digits.
public final class SingleLineText {
    private SingleLineText() {}

    public static String escaped(String text) {
        var safe = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            switch (current) {
                case '\\' -> safe.append("\\\\");
                case '"' -> safe.append("\\\"");
                case '\n' -> safe.append("\\n");
                case '\r' -> safe.append("\\r");
                case '\t' -> safe.append("\\t");
                default -> {
                    if (UNSAFE_SINGLE_LINE_CHARACTERS.matches(current)) {
                        safe.append("\\u%04X".formatted((int) current));
                    } else {
                        safe.append(current);
                    }
                }
            }
        }
        return safe.toString();
    }
}
