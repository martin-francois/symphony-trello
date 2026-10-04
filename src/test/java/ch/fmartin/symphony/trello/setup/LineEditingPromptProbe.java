package ch.fmartin.symphony.trello.setup;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/// Child-process entry point for [StreamTerminalLineEditingTest]. It prepares the console the way
/// `TrelloBoardSetupMain.main` does and then asks setup-style prompts through [StreamTerminal], so the
/// test can type real key sequences into a pseudo-terminal.
final class LineEditingPromptProbe {
    static final String PATH_PROMPT = "Additional paths, comma-separated: ";
    static final String SECRET_PROMPT = "Trello token: ";
    static final String RECALL_PROMPT = "Board: ";

    /// The escape character that starts every terminal key and control sequence.
    static final String ESCAPE = "\u001B";

    /// Stands in for an answer that ended at end of input.
    private static final String NO_ANSWER = "<EOF>";

    private LineEditingPromptProbe() {}

    public static void main(String... args) throws IOException {
        SystemConsole.enableLineEditing();
        var terminal = new StreamTerminal(
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out, System.err);
        String path = terminal.readLine(PATH_PROMPT);
        char[] secret = terminal.readSecret(SECRET_PROMPT);
        String recalled = terminal.readLine(RECALL_PROMPT);
        terminal.info(pathResult(visible(path)));
        terminal.info(secretLengthResult(secret == null ? NO_ANSWER : String.valueOf(secret.length)));
        terminal.info(recalledResult(visible(recalled)));
    }

    static String pathResult(String path) {
        return "path=[" + path + "]";
    }

    static String secretLengthResult(String length) {
        return "secret-length=" + length;
    }

    static String recalledResult(String answer) {
        return "recalled=[" + answer + "]";
    }

    /// Shows escape bytes as `<ESC>` so the test can tell an edited line from raw key sequences.
    static String visible(String value) {
        return value == null ? NO_ANSWER : value.replace(ESCAPE, "<ESC>");
    }
}
