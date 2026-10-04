package ch.fmartin.symphony.trello.testsupport;

/// Renders captured command output into the reviewable text stored in a terminal snapshot.
///
/// Control characters are written as visible markers so a snapshot shows ANSI sequences, carriage
/// returns, and other terminal control bytes instead of hiding them in the baseline file. Line feeds
/// and tabs stay literal because they are ordinary transcript layout.
public final class TerminalTranscript {
    static final char ESCAPE = '\u001B';
    private static final char CARRIAGE_RETURN = '\r';
    private static final char LINE_FEED = '\n';
    private static final char TAB = '\t';
    private static final String WINDOWS_LINE_ENDING = "\r\n";
    private static final String MISSING_FINAL_NEWLINE = "<no newline at end of stream>";

    private TerminalTranscript() {}

    /// Renders a child-process or in-process command result whose streams were captured separately.
    /// CRLF line endings become LF; any remaining carriage return is shown as `<CR>`.
    public static String ofStreams(int exitCode, String stdout, String stderr) {
        return "exitCode: " + exitCode + "\n"
                + "--- stdout ---\n"
                + stream(stdout.replace(WINDOWS_LINE_ENDING, "\n"))
                + "--- stderr ---\n"
                + stream(stderr.replace(WINDOWS_LINE_ENDING, "\n"));
    }

    /// Renders a pseudo-terminal session. The terminal merges both output streams and the echo of
    /// typed input into one transcript, so there is no stream split. Every carriage return the
    /// terminal emitted stays visible as `<CR>`.
    public static String ofTerminal(int exitCode, String transcript) {
        return "exitCode: " + exitCode + "\n" + "--- terminal ---\n" + stream(transcript);
    }

    private static String stream(String content) {
        if (content.isEmpty()) {
            return "";
        }
        String visible = visibleControlCharacters(content);
        return visible.endsWith("\n") ? visible : visible + MISSING_FINAL_NEWLINE + "\n";
    }

    static String visibleControlCharacters(String content) {
        var rendered = new StringBuilder(content.length());
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character == ESCAPE) {
                rendered.append("<ESC>");
            } else if (character == CARRIAGE_RETURN) {
                rendered.append("<CR>");
            } else if (character == LINE_FEED || character == TAB) {
                rendered.append(character);
            } else if (Character.isISOControl(character)) {
                rendered.append("<U+%04X>".formatted((int) character));
            } else {
                rendered.append(character);
            }
        }
        return rendered.toString();
    }
}
