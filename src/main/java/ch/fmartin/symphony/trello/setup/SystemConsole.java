package ch.fmartin.symphony.trello.setup;

import java.io.Console;

final class SystemConsole {
    /// JDK system property that selects the `System.console()` implementation. The JDK reads it once,
    /// when `java.io.Console` initializes.
    static final String PROVIDER_PROPERTY = "jdk.console";

    /// The JDK's own JLine-based console provider. It gives prompts readline-style editing (arrow
    /// keys, Home, End, history) and keeps password input hidden. JDK 25 ships it as an opt-in; see
    /// ADR 0107.
    static final String LINE_EDITING_PROVIDER = "jdk.internal.le";

    private SystemConsole() {}

    /// Selects the line-editing console provider unless the launch already chose one, so an operator
    /// can still pass `-Djdk.console=java.base` to get plain prompts back. Call it before anything
    /// touches `System.console()`; a later call has no effect.
    static void enableLineEditing() {
        if (System.getProperty(PROVIDER_PROPERTY) == null) {
            System.setProperty(PROVIDER_PROPERTY, LINE_EDITING_PROVIDER);
        }
    }

    /// Returns the console only when standard input and output are an interactive terminal. The
    /// line-editing provider can return a console for redirected streams, and the `isTerminal()`
    /// check keeps piped setup on the plain stream path.
    @SuppressWarnings("SystemConsoleNull")
    static Console current() {
        // CLI commands and tests can run without an attached console; callers must keep the null fallback.
        Console console = System.console();
        return console != null && console.isTerminal() ? console : null;
    }
}
