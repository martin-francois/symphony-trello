package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

final class WorkflowEnvironmentResolver {
    private WorkflowEnvironmentResolver() {}

    static Function<String, Optional<String>> resolver(Map<String, String> environment, Path envPath) {
        Path dotenvPath = envPath == null ? LocalEnvironment.defaultDotenv(environment) : envPath;
        Map<String, String> dotenv = LocalEnvironment.load(dotenvPath);
        return name -> value(environment, dotenv, name);
    }

    static Optional<String> externalHttpPortOverrideSource(Map<String, String> environment, Path envPath) {
        for (String name : LocalHealthChecker.HTTP_PORT_ENVIRONMENT_NAMES) {
            if (hasText(environment.get(name))) {
                return Optional.of(name + " environment variable");
            }
        }
        Path dotenvPath = envPath == null ? LocalEnvironment.defaultDotenv(environment) : envPath;
        Map<String, String> dotenv = LocalEnvironment.load(dotenvPath);
        for (String name : LocalHealthChecker.HTTP_PORT_ENVIRONMENT_NAMES) {
            if (hasText(dotenv.get(name))) {
                return Optional.of(name + " in " + dotenvPath);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> value(Map<String, String> environment, Map<String, String> dotenv, String name) {
        String environmentValue = environment.get(name);
        if (hasText(environmentValue)) {
            return Optional.of(environmentValue);
        }
        String dotenvValue = dotenv.get(name);
        return hasText(dotenvValue) ? Optional.of(dotenvValue) : Optional.empty();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
