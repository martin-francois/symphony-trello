package ch.fmartin.symphony.trello;

/**
 * Blanks string literals, text blocks, char literals, and comments out of Java source so brace
 * scanning sees only structural code braces. Every blanked character becomes a space and newlines
 * are kept, so the result has exactly the same line structure as the input. Multi-line annotations
 * whose structural braces continue on non-annotation lines remain a known scanner limitation; this
 * pass only removes brace noise from data and comments.
 */
final class TestSourceLexer {
    private enum State {
        CODE,
        LINE_COMMENT,
        BLOCK_COMMENT,
        STRING,
        TEXT_BLOCK,
        CHAR
    }

    private TestSourceLexer() {}

    static String stripNonCode(String source) {
        StringBuilder out = new StringBuilder(source.length());
        State state = State.CODE;
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            switch (state) {
                case CODE -> {
                    if (lookingAt(source, index, "//")) {
                        state = State.LINE_COMMENT;
                        index += blank(out, source, index, 2);
                    } else if (lookingAt(source, index, "/*")) {
                        state = State.BLOCK_COMMENT;
                        index += blank(out, source, index, 2);
                    } else if (lookingAt(source, index, "\"\"\"")) {
                        state = State.TEXT_BLOCK;
                        index += blank(out, source, index, 3);
                    } else if (current == '"') {
                        state = State.STRING;
                        index += blank(out, source, index, 1);
                    } else if (current == '\'') {
                        state = State.CHAR;
                        index += blank(out, source, index, 1);
                    } else {
                        out.append(current);
                        index++;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n') {
                        state = State.CODE;
                    }
                    index += blank(out, source, index, 1);
                }
                case BLOCK_COMMENT -> {
                    if (lookingAt(source, index, "*/")) {
                        state = State.CODE;
                        index += blank(out, source, index, 2);
                    } else {
                        index += blank(out, source, index, 1);
                    }
                }
                case STRING -> {
                    if (current == '\\' && index + 1 < source.length()) {
                        index += blank(out, source, index, 2);
                    } else if (current == '"' || current == '\n') {
                        // A newline before the closing quote is invalid Java; recovering here
                        // keeps the scan line-aligned instead of swallowing the rest of the file.
                        state = State.CODE;
                        index += blank(out, source, index, 1);
                    } else {
                        index += blank(out, source, index, 1);
                    }
                }
                case TEXT_BLOCK -> {
                    if (current == '\\' && index + 1 < source.length()) {
                        index += blank(out, source, index, 2);
                    } else if (lookingAt(source, index, "\"\"\"")) {
                        state = State.CODE;
                        index += blank(out, source, index, 3);
                    } else {
                        index += blank(out, source, index, 1);
                    }
                }
                case CHAR -> {
                    if (current == '\\' && index + 1 < source.length()) {
                        index += blank(out, source, index, 2);
                    } else if (current == '\'' || current == '\n') {
                        state = State.CODE;
                        index += blank(out, source, index, 1);
                    } else {
                        index += blank(out, source, index, 1);
                    }
                }
            }
        }
        return out.toString();
    }

    private static boolean lookingAt(String source, int index, String token) {
        return source.startsWith(token, index);
    }

    /** Emits spaces for the next {@code length} characters, preserving newlines, and returns the consumed count. */
    private static int blank(StringBuilder out, String source, int index, int length) {
        int end = Math.min(index + length, source.length());
        for (int position = index; position < end; position++) {
            out.append(source.charAt(position) == '\n' ? '\n' : ' ');
        }
        return end - index;
    }
}
