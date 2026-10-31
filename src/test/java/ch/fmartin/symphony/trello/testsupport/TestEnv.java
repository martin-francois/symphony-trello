package ch.fmartin.symphony.trello.testsupport;

public final class TestEnv {
    private TestEnv() {}

    public static String trelloCredentials() {
        return trelloCredentials("key", "token");
    }

    public static String trelloCredentials(String key, String token) {
        return "TRELLO_API_KEY=%s%nTRELLO_API_TOKEN=%s%n".formatted(key, token);
    }

    /// Credentials bound to `endpoint`, as an env file for a fake Trello server needs them.
    public static String trelloCredentials(String key, String token, String endpoint) {
        return trelloCredentials(key, token) + trelloApiEndpoint(endpoint);
    }

    public static String trelloApiEndpoint(String endpoint) {
        return "TRELLO_API_ENDPOINT=%s%n".formatted(endpoint);
    }

    public static String trelloApiKey(String key) {
        return "TRELLO_API_KEY=%s%n".formatted(key);
    }

    public static String serverPort(String variableName, int port) {
        return "%s=%d%n".formatted(variableName, port);
    }
}
