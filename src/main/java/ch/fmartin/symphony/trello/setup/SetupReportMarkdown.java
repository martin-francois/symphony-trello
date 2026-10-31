package ch.fmartin.symphony.trello.setup;

/// Markdown building blocks shared by the troubleshooting report and the setup-failure issue draft,
/// so both render fields and fences the same way.
final class SetupReportMarkdown {
    private SetupReportMarkdown() {}

    static String firstLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.lines().findFirst().orElse("").trim();
    }

    /// Returns a backtick fence longer than any backtick run in `content`, so the content cannot
    /// close the fence early.
    static String markdownFence(String content) {
        int longestRun = 0;
        int currentRun = 0;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '`') {
                currentRun++;
                longestRun = Math.max(longestRun, currentRun);
            } else {
                currentRun = 0;
            }
        }
        return "`".repeat(Math.max(3, longestRun + 1));
    }

    static void line(StringBuilder body, String label, Object value) {
        body.append("- **")
                .append(label)
                .append(":** ")
                .append(escapeHtmlStart(String.valueOf(value)))
                .append('\n');
    }

    /// Escapes every `<` outside code spans as `\<`, so a Markdown renderer shows redaction markers
    /// such as `<redacted>` and `<path:0123456789ab>` instead of reading them as HTML tags or
    /// autolinks. A backslash that already precedes a `<` is escaped as well, so it stays visible.
    /// Code spans keep their bytes, because a renderer shows their content literally. See
    /// docs/adr/0155-escape-angle-brackets-in-diagnostics-markdown-text.md.
    static String escapeHtmlStart(String text) {
        var escaped = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (character == '`') {
                int codeSpanEnd = codeSpanEnd(text, index);
                escaped.append(text, index, codeSpanEnd);
                index = codeSpanEnd;
            } else {
                if (character == '<' || (character == '\\' && nextIs(text, index, '<'))) {
                    escaped.append('\\');
                }
                escaped.append(character);
                index++;
            }
        }
        return escaped.toString();
    }

    /// Returns the index after the code span that opens at `start`, or after the backtick run when no
    /// run of the same length closes it, as CommonMark reads an unmatched run as literal backticks.
    private static int codeSpanEnd(String text, int start) {
        int openingLength = backtickRunLength(text, start);
        int index = start + openingLength;
        while (index < text.length()) {
            if (text.charAt(index) == '`') {
                int runLength = backtickRunLength(text, index);
                if (runLength == openingLength) {
                    return index + runLength;
                }
                index += runLength;
            } else {
                index++;
            }
        }
        return start + openingLength;
    }

    private static int backtickRunLength(String text, int start) {
        int end = start;
        while (end < text.length() && text.charAt(end) == '`') {
            end++;
        }
        return end - start;
    }

    private static boolean nextIs(String text, int index, char expected) {
        return index + 1 < text.length() && text.charAt(index + 1) == expected;
    }
}
