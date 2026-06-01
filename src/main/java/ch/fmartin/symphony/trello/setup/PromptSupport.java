package ch.fmartin.symphony.trello.setup;

import static com.google.common.base.Preconditions.checkArgument;

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
        int parsed = Integer.parseInt(answer.strip());
        checkArgument(parsed >= 1 && parsed <= maxChoice, "Choice must be between 1 and %s", maxChoice);
        return parsed;
    }
}
