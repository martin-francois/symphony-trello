package ch.fmartin.symphony.trello.process;

import ch.fmartin.symphony.trello.TrelloEnvironment;
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

    /**
     * Stops Git repository discovery from ascending above the given boundary directory, so
     * per-card workspaces without a task repository do not inherit an unrelated parent Git
     * repository such as the operator's home directory.
     */
    public static void limitGitDiscovery(ProcessBuilder processBuilder, Path boundary) {
        limitGitDiscovery(processBuilder.environment(), boundary);
    }

    static void limitGitDiscovery(Map<String, String> environment, Path boundary) {
        environment.put(
                "GIT_CEILING_DIRECTORIES", boundary.toAbsolutePath().normalize().toString());
    }
}
