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
        return firstPresentHttpPortName(environment)
                .map(name -> name + " environment variable")
                .or(() -> dotenvHttpPortOverrideSource(environment, envPath));
    }

    private static Optional<String> dotenvHttpPortOverrideSource(Map<String, String> environment, Path envPath) {
        Path dotenvPath = envPath == null ? LocalEnvironment.defaultDotenv(environment) : envPath;
        Map<String, String> dotenv = LocalEnvironment.load(dotenvPath);
        return firstPresentHttpPortName(dotenv).map(name -> name + " in " + dotenvPath);
    }

    private static Optional<String> firstPresentHttpPortName(Map<String, String> values) {
        return LocalHealthChecker.HTTP_PORT_ENVIRONMENT_NAMES.stream()
                .filter(name -> hasText(values.get(name)))
                .findFirst();
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
