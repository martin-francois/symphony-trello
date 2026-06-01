package ch.fmartin.symphony.trello.setup;

import java.io.Console;

final class SystemConsole {
    private SystemConsole() {}

    @SuppressWarnings("SystemConsoleNull")
    static Console current() {
        // CLI commands and tests can run without an attached console; callers must keep the null fallback.
        return System.console();
    }
}
