package ch.fmartin.symphony.trello.process;

import java.util.Map;
import java.util.Set;

public final class ProcessEnvironment {
    private static final Set<String> DEFAULT_SECRET_ENVIRONMENT_VARIABLES =
            Set.of("TRELLO_API_KEY", "TRELLO_API_TOKEN");

    private ProcessEnvironment() {}

    public static void removeDefaultSecrets(ProcessBuilder processBuilder) {
        removeDefaultSecrets(processBuilder.environment());
    }

    static void removeDefaultSecrets(Map<String, String> environment) {
        DEFAULT_SECRET_ENVIRONMENT_VARIABLES.forEach(environment::remove);
    }
}
