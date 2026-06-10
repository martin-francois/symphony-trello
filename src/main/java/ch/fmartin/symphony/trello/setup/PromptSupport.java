package ch.fmartin.symphony.trello.setup;

import java.io.IOException;
import java.util.Locale;

final class PromptSupport {
    private PromptSupport() {}

    static boolean yes(Terminal terminal, String prompt) throws IOException {
        String answer = terminal.readLine(prompt);
        return answer != null && answer.trim().toLowerCase(Locale.ROOT).startsWith("y");
    }

    static boolean yesDefaultTrue(Terminal terminal, String prompt) throws IOException {
        String answer = terminal.readLine(prompt);
        return answer == null
                || answer.isBlank()
                || answer.trim().toLowerCase(Locale.ROOT).startsWith("y");
    }

    static int choice(String answer, int defaultChoice, int maxChoice) {
        if (answer == null || answer.isBlank()) {
            return defaultChoice;
        }
        return parseBoundedChoice(answer, maxChoice);
    }

    static int requiredChoice(String answer, int maxChoice, String errorCode, String errorMessage) {
        if (answer == null || answer.isBlank()) {
            throw new TrelloBoardSetupException(errorCode, errorMessage);
        }
        return parseBoundedChoice(answer, maxChoice);
    }

    /**
     * Parses a finite prompt choice as an expected input error, so a typo never produces a raw
     * Java parse message or an unexpected troubleshooting report.
     */
    static int parseBoundedChoice(String answer, int maxChoice) {
        int parsed;
        try {
            parsed = Integer.parseInt(answer.strip());
        } catch (NumberFormatException e) {
            throw invalidChoice(maxChoice, e);
        }
        if (parsed < 1 || parsed > maxChoice) {
            throw invalidChoice(maxChoice, null);
        }
        return parsed;
    }

    private static TrelloBoardSetupException invalidChoice(int maxChoice, Throwable cause) {
        return new TrelloBoardSetupException(
                "setup_invalid_choice", "Choice must be a number between 1 and " + maxChoice + ".", cause);
    }
}
