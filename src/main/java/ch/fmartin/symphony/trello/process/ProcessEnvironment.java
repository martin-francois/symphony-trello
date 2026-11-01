package ch.fmartin.symphony.trello.process;

import ch.fmartin.symphony.trello.TrelloEnvironment;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public final class ProcessEnvironment {
    private static final Set<String> DEFAULT_SECRET_ENVIRONMENT_VARIABLES =
            Set.of(TrelloEnvironment.API_KEY, TrelloEnvironment.API_TOKEN);

    private ProcessEnvironment() {}

    public static void removeDefaultSecrets(ProcessBuilder processBuilder) {
        removeDefaultSecrets(processBuilder.environment());
    }

    static void removeDefaultSecrets(Map<String, String> environment) {
        DEFAULT_SECRET_ENVIRONMENT_VARIABLES.forEach(environment::remove);
    }

    public static void removeSecrets(ProcessBuilder builder, EffectiveConfig config) {
        removeSecrets(builder.environment(), config);
    }

    static void removeSecrets(Map<String, String> environment, EffectiveConfig config) {
        removeDefaultSecrets(environment);
        environment.remove("BASH_ENV");
        environment.remove("ENV");
        config.credentialSources().environmentVariables().forEach(environment::remove);
        environment
                .values()
                .removeIf(value -> containsSecret(value, config.tracker().apiKey())
                        || containsSecret(value, config.tracker().apiToken()));
    }

    private static boolean containsSecret(String value, String secret) {
        return secret != null && !secret.isBlank() && value.contains(secret);
    }

    /// Stops Git repository discovery from ascending above the given boundary directory, so
    /// per-card workspaces without a task repository do not inherit an unrelated parent Git
    /// repository such as the operator's home directory.
    public static void limitGitDiscovery(ProcessBuilder processBuilder, Path boundary) {
        limitGitDiscovery(processBuilder.environment(), boundary);
    }

    static void limitGitDiscovery(Map<String, String> environment, Path boundary) {
        environment.put(
                "GIT_CEILING_DIRECTORIES", boundary.toAbsolutePath().normalize().toString());
    }
}
