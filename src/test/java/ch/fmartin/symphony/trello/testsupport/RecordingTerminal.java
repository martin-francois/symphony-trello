package ch.fmartin.symphony.trello.testsupport;

import ch.fmartin.symphony.trello.setup.Terminal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Queue;

public final class RecordingTerminal implements Terminal {
    private final Queue<String> input = new ArrayDeque<>();
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8);

    public RecordingTerminal(String... input) {
        Collections.addAll(this.input, input);
    }

    @Override
    public String readLine(String prompt) {
        out.print(prompt);
        return input.poll();
    }

    @Override
    public char[] readSecret(String prompt) throws IOException {
        String value = readLine(prompt);
        return value == null ? null : value.toCharArray();
    }

    @Override
    public void info(String line) {
        out.println(line);
    }

    @Override
    public void warn(String line) {
        out.println(line);
    }

    @Override
    public void error(String line) {
        err.println(line);
    }

    @Override
    public PrintStream out() {
        return out;
    }

    @Override
    public PrintStream err() {
        return err;
    }

    public String stdout() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    public String stderr() {
        return stderr.toString(StandardCharsets.UTF_8);
    }
}
