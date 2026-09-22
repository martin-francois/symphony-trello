package ch.fmartin.symphony.trello.telemetry;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/// Where the live erasure experiment is allowed to run. Everything comes from the environment of
/// the invocation; there are no runnable defaults for project IDs, so a normal build can never bind
/// to a real project by accident. The production project is passed as forbidden and refused before
/// any request.
record ExperimentBinding(
        URI managementHost,
        URI captureEndpoint,
        long projectId,
        String expectedProjectName,
        Set<Long> forbiddenProjectIds,
        Path keyFile,
        Path experimentDir) {

    static final String ENABLE_VARIABLE = "SYMPHONY_TRELLO_TELEMETRY_LIVE_EXPERIMENT";
    static final String DIR_VARIABLE = "SYMPHONY_TRELLO_TELEMETRY_LIVE_EXPERIMENT_DIR";
    static final String KEY_FILE_VARIABLE = "SYMPHONY_TRELLO_POSTHOG_PERSONAL_API_KEY_FILE";
    static final String PROJECT_ID_VARIABLE = "SYMPHONY_TRELLO_POSTHOG_TEST_PROJECT_ID";
    static final String PROJECT_NAME_VARIABLE = "SYMPHONY_TRELLO_POSTHOG_TEST_PROJECT_NAME";
    static final String FORBIDDEN_PROJECTS_VARIABLE = "SYMPHONY_TRELLO_POSTHOG_FORBIDDEN_PROJECT_IDS";
    static final String MANAGEMENT_HOST_VARIABLE = "SYMPHONY_TRELLO_POSTHOG_MANAGEMENT_HOST";
    static final String CAPTURE_ENDPOINT_VARIABLE = "SYMPHONY_TRELLO_POSTHOG_CAPTURE_ENDPOINT";
    static final URI DEFAULT_MANAGEMENT_HOST = URI.create("https://eu.posthog.com");
    static final String PERSONAL_KEY_PREFIX = "phx_";
    static final String PROJECT_TOKEN_PREFIX = "phc_";

    static boolean enabled(Map<String, String> environment) {
        return "1".equals(environment.get(ENABLE_VARIABLE));
    }

    /// Fails with every missing variable named at once; nothing is guessed.
    static ExperimentBinding fromEnvironment(Map<String, String> environment) {
        List<String> missing = new ArrayList<>();
        for (String variable : List.of(
                DIR_VARIABLE,
                KEY_FILE_VARIABLE,
                PROJECT_ID_VARIABLE,
                PROJECT_NAME_VARIABLE,
                FORBIDDEN_PROJECTS_VARIABLE)) {
            if (environment.getOrDefault(variable, "").isBlank()) {
                missing.add(variable);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("live experiment needs " + String.join(", ", missing));
        }
        URI managementHost =
                URI.create(environment.getOrDefault(MANAGEMENT_HOST_VARIABLE, DEFAULT_MANAGEMENT_HOST.toString()));
        URI captureEndpoint = URI.create(environment.getOrDefault(
                CAPTURE_ENDPOINT_VARIABLE, TelemetryDistribution.PRODUCTION_ENDPOINT.toString()));
        Set<Long> forbidden = Arrays.stream(
                        environment.get(FORBIDDEN_PROJECTS_VARIABLE).split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toUnmodifiableSet());
        return new ExperimentBinding(
                managementHost,
                captureEndpoint,
                Long.parseLong(environment.get(PROJECT_ID_VARIABLE).strip()),
                environment.get(PROJECT_NAME_VARIABLE).strip(),
                forbidden,
                Path.of(environment.get(KEY_FILE_VARIABLE)),
                Path.of(environment.get(DIR_VARIABLE)));
    }

    /// Reads the key from its file on every use so it is never held in a field or printed.
    Supplier<String> keySupplier() {
        return () -> {
            String value;
            try {
                value = Files.readString(keyFile).strip();
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "personal API key file is unreadable: " + keyFile.getFileName(), exception);
            }
            if (!value.startsWith(PERSONAL_KEY_PREFIX)) {
                throw new IllegalStateException("personal API key file does not hold a personal key");
            }
            return value;
        };
    }

    /// The only way the experiment learns a capture token: from the verified project's own
    /// metadata. A forbidden ID, a different ID, or a different name stops the run before any
    /// mutation; the bundled release token is never consulted.
    String verifiedCaptureToken(PostHogManagementClient client) {
        if (forbiddenProjectIds.contains(projectId)) {
            throw new IllegalStateException("project " + projectId + " is forbidden for the experiment");
        }
        if (client.projectId() != projectId) {
            throw new IllegalStateException(
                    "client is bound to project " + client.projectId() + ", expected " + projectId);
        }
        PostHogManagementClient.Response response = client.project();
        if (!response.success()) {
            throw new IllegalStateException(
                    "project lookup failed with HTTP " + response.status() + ": " + response.detail());
        }
        long liveId = response.json().path("id").asLong(-1);
        String liveName = response.json().path("name").asText("");
        if (liveId != projectId || !expectedProjectName.equals(liveName)) {
            throw new IllegalStateException("project identity mismatch: live id " + liveId + " name \"" + liveName
                    + "\", expected id " + projectId + " name \"" + expectedProjectName + "\"");
        }
        String token = response.json().path("api_token").asText("");
        if (!token.startsWith(PROJECT_TOKEN_PREFIX)) {
            throw new IllegalStateException("verified project exposes no capture token");
        }
        return token;
    }
}
