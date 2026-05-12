package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.util.Locale;

final class CodexAuthFlow {
    private final CommandRunner commands;

    CodexAuthFlow(CommandRunner commands) {
        this.commands = commands;
    }

    void ensureAuthenticated(Prerequisites prerequisites, LocalSetup.Options options, Terminal terminal)
            throws IOException {
        if (!prerequisites.git().available()
                || !prerequisites.java().available()
                || !prerequisites.codex().available()) {
            throw new TrelloBoardSetupException(
                    "setup_prerequisite_missing",
                    "Install missing prerequisites, then rerun setup-local. Source installs require Git, a Java 25+ JDK, and Codex CLI.");
        }
        if (prerequisites.codexAuth().available()) {
            return;
        }
        if (options.nonInteractive()) {
            throw new TrelloBoardSetupException(
                    "setup_codex_auth_required", "Run `codex login`, then rerun setup-local.");
        }
        terminal.info("");
        String[] loginCommand = codexLoginCommand(terminal);
        CommandResult login = commands.runInteractive(loginCommand);
        if (!login.success()) {
            throw new TrelloBoardSetupException(
                    "setup_codex_login_failed",
                    "Codex login did not complete successfully. Run `" + String.join(" ", loginCommand)
                            + "`, then rerun setup-local.");
        }
        if (!commands.available("codex", "login", "status").available()) {
            throw new TrelloBoardSetupException(
                    "setup_codex_auth_required",
                    "Codex login did not complete successfully. Run `" + String.join(" ", loginCommand)
                            + "`, then rerun setup-local.");
        }
        terminal.info("  OK  Codex CLI authenticated");
    }

    private static String[] codexLoginCommand(Terminal terminal) throws IOException {
        String answer = terminal.readLine("Can this machine open a browser for Codex login? [Y/n] ");
        if (answer != null && answer.trim().toLowerCase(Locale.ROOT).startsWith("n")) {
            return new String[] {"codex", "login", "--device-auth"};
        }
        return new String[] {"codex", "login"};
    }
}
