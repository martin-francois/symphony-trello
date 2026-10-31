package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.EnvironmentSources;
import ch.fmartin.symphony.trello.config.LocalEnvironment;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

final class WorkflowEnvironmentResolver {
    private WorkflowEnvironmentResolver() {}

    /// The shell environment first, then the board's env file, as a worker started with that file
    /// would read them.
    static EnvironmentSources resolver(Map<String, String> environment, Path envPath) {
        Path dotenvPath = envPath == null ? LocalEnvironment.defaultDotenv(environment) : envPath;
        return LocalEnvironment.sources(environment, dotenvPath);
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

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
