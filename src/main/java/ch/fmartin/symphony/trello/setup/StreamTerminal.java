package ch.fmartin.symphony.trello.setup;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.PrintStream;
import java.util.function.Supplier;

/// Reads setup prompts from an interactive console when one is attached and from `input` otherwise.
///
/// The console path gives line editing once [SystemConsole#enableLineEditing()] ran at startup. The
/// stream path keeps piped and test input unchanged.
final class StreamTerminal implements Terminal {
    /// Simple name of the exception the JDK line editor throws for Ctrl+C. The class lives in the
    /// non-exported `jdk.internal.le` module, so code cannot refer to the type itself.
    private static final String LINE_EDITOR_INTERRUPT = "UserInterruptException";

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
        Console console = SystemConsole.current();
        if (console != null) {
            flushOutput();
            return interruptible(() -> console.readLine("%s", prompt));
        }
        out.print(prompt);
        return input.readLine();
    }

    @Override
    public char[] readSecret(String prompt) throws IOException {
        Console console = SystemConsole.current();
        if (console != null) {
            flushOutput();
            return interruptible(() -> console.readPassword("%s", prompt));
        }
        String value = readLine(prompt);
        return value == null ? null : value.toCharArray();
    }

    /// Setup output goes through `out` and `err` while the console writes the prompt itself, so
    /// pending output must reach the terminal first or it would appear after the prompt.
    private void flushOutput() {
        out.flush();
        err.flush();
    }

    /// While the line editor reads a line it handles SIGINT itself, so Ctrl+C makes the editor throw
    /// instead of ending the JVM. End the process the same way SIGINT ends it: exit status 130 with
    /// shutdown hooks, and no stack trace or setup failure report.
    private static <T> T interruptible(Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            if (LINE_EDITOR_INTERRUPT.equals(e.getClass().getSimpleName())) {
                Runtime.getRuntime().exit(CommandResult.INTERRUPTED_EXIT_CODE);
            }
            throw e;
        }
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
