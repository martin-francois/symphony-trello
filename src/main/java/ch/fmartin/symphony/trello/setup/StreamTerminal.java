package ch.fmartin.symphony.trello.setup;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;

final class StreamTerminal implements Terminal {
    private final BufferedReader input;
    private final PrintStream out;
    private final PrintStream err;

    StreamTerminal(BufferedReader input, PrintStream out, PrintStream err) {
        this.input = input;
        this.out = out;
        this.err = err;
    }

    @Override
    public String readLine(String prompt) throws IOException {
        out.print(prompt);
        return input.readLine();
    }

    @Override
    public char[] readSecret(String prompt) throws IOException {
        Console console = System.console();
        if (console != null) {
            char[] password = console.readPassword(prompt);
            return password == null ? null : password;
        }
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
}
