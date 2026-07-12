package ch.fmartin.symphony.trello.setup;

import java.util.Map;
import java.util.Optional;

final class UserIdResolver {
    private static final String TEST_USER_ID = "SYMPHONY_TRELLO_TEST_UID";

    private UserIdResolver() {}

    static Optional<String> resolve(Map<String, String> environment, CommandRunner commandRunner) {
        return numericValue(environment.get(TEST_USER_ID)).or(() -> effectiveUserId(commandRunner));
    }

    private static Optional<String> effectiveUserId(CommandRunner commandRunner) {
        CommandResult result = commandRunner.run("id", "-u");
        return result.success() ? numericValue(result.output().trim()) : Optional.empty();
    }

    private static Optional<String> numericValue(String value) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return Optional.empty();
            }
        }
        return Optional.of(value);
    }
}
