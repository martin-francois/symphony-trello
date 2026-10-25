package ch.fmartin.symphony.trello.setup;

import java.io.IOException;

/// Keeps an optional tutorial outside the caller's already completed setup or installation.
final class TutorialOffer {
    private TutorialOffer() {}

    @FunctionalInterface
    interface Tutorial {
        void run() throws IOException;
    }

    static void run(Terminal terminal, String command, String retryIntroduction, Tutorial tutorial) {
        try {
            terminal.info("");
            if (!PromptSupport.yes(terminal, GuidedTutorial.OFFER_PROMPT)) {
                terminal.info("You can start it later with:");
                terminal.info("  " + GuidedTutorial.commandLine(command));
                return;
            }
            tutorial.run();
        } catch (IOException | RuntimeException e) {
            // Optional work must not turn a completed setup into a failure or expose private paths.
            terminal.error("The guided tutorial stopped: " + SetupFailureCauses.commandBoundaryMessage(e));
            terminal.error(retryIntroduction);
            terminal.error("  " + GuidedTutorial.commandLine(command));
        }
    }
}
