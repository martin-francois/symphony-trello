package ch.fmartin.symphony.trello.setup;

import java.io.IOException;

final class CodexSandboxFlow {
    static final String DANGER_FULL_ACCESS_WARNING =
            "danger-full-access disables Codex's command/filesystem sandbox for this workflow; model-driven commands run with the same permissions as the Symphony app/service; they can affect files, processes, and network destinations the app/service can access; they can delete or overwrite data; this does not grant root/admin privileges and does not bypass OS permissions or stricter service-manager restrictions such as systemd ReadWritePaths.";

    boolean resolve(LocalSetup.Options options, Terminal terminal) throws IOException {
        if (options.nonInteractive()) {
            if (options.dangerFullAccess()) {
                terminal.info("");
                terminal.info("Codex execution");
                printWarning(terminal);
            }
            return options.dangerFullAccess();
        }
        terminal.info("");
        terminal.info("Codex execution");
        if (options.dangerFullAccess()) {
            printWarning(terminal);
            return true;
        }
        boolean accepted = PromptSupport.yes(
                terminal,
                "Allow Codex to run without its command/filesystem sandbox for this workflow (danger-full-access)? [y/N] ");
        if (accepted) {
            printWarning(terminal);
        }
        return accepted;
    }

    static void printWarning(Terminal terminal) {
        terminal.info("  WARN  " + DANGER_FULL_ACCESS_WARNING);
    }
}
