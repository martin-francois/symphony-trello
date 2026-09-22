package ch.fmartin.symphony.trello.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/// Test-only client for the PostHog management API, pinned to one numeric project. It never
/// follows redirects, never puts its key into messages or evidence, and charges every request to
/// the run budget. Only the endpoints the erasure experiment needs exist here.
final class PostHogManagementClient {
    static final String USER_AGENT = "symphony-trello-telemetry-experiment/1";
    static final String REFRESH_MODE = "force_blocking";
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final int RATE_LIMIT_ATTEMPTS = 3;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_REDIRECT_START = 300;
    private static final int HTTP_CLIENT_ERROR_START = 400;
    private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI host;
    private final long projectId;
    private final Supplier<String> key;
    private final ExperimentBudget budget;

    PostHogManagementClient(URI host, long projectId, Supplier<String> key, ExperimentBudget budget) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                host,
                projectId,
                key,
                budget);
    }

    PostHogManagementClient(HttpClient http, URI host, long projectId, Supplier<String> key, ExperimentBudget budget) {
        this.http = http;
        this.host = host;
        this.projectId = projectId;
        this.key = key;
        this.budget = budget;
    }

    long projectId() {
        return projectId;
    }

    /// `GET /api/projects/{id}/`: the project's identity and its public capture token.
    Response project() {
        return send("GET", projectPath(""), null);
    }

    /// `GET /api/projects/{id}/persons/?distinct_id=`: the persons currently mapped to one ID.
    Response persons(String distinctId) {
        return send("GET", projectPath("persons/?distinct_id=" + encode(distinctId)), null);
    }

    /// `POST /api/projects/{id}/persons/bulk_delete/` by distinct IDs, always with event deletion.
    Response bulkDelete(List<String> distinctIds) {
        return send(
                "POST",
                projectPath("persons/bulk_delete/"),
                Map.of("distinct_ids", distinctIds, "delete_events", true));
    }

    /// `GET /api/projects/{id}/persons/deletion_status/` for one person UUID, all statuses.
    Response deletionStatus(String personUuid) {
        return send("GET", projectPath("persons/deletion_status/?status=all&person_uuid=" + encode(personUuid)), null);
    }

    /// `POST /api/projects/{id}/persons/reset_person_distinct_id/` with the body the source reads.
    Response resetPersonDistinctId(String distinctId) {
        return send("POST", projectPath("persons/reset_person_distinct_id/"), Map.of("distinct_id", distinctId));
    }

    /// `POST /api/projects/{id}/query/` with a HogQL query, always recalculated, never from cache.
    Response query(String hogql) {
        Response response = send(
                "POST",
                projectPath("query/"),
                Map.of("query", Map.of("kind", "HogQLQuery", "query", hogql), "refresh", REFRESH_MODE));
        if (response.success() && response.json().path("is_cached").asBoolean(false)) {
            throw new ManagementException("query answered from cache despite refresh=" + REFRESH_MODE);
        }
        return response;
    }

    private String projectPath(String suffix) {
        return "/api/projects/" + projectId + "/" + suffix;
    }

    private Response send(String method, String path, @Nullable Object body) {
        for (int attempt = 1; ; attempt++) {
            budget.chargeRequest(method + " " + path);
            Response response = exchange(method, path, body);
            if (response.status() != HTTP_TOO_MANY_REQUESTS || attempt >= RATE_LIMIT_ATTEMPTS) {
                return response;
            }
            budget.await(response.retryAfter().orElse(DEFAULT_RETRY_AFTER), "rate limited on " + method + " " + path);
        }
    }

    private Response exchange(String method, String path, @Nullable Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(host.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + key.get())
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(serialize(body), StandardCharsets.UTF_8));
        }
        HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException exception) {
            throw new ManagementException(
                    method + " " + path + " failed: " + exception.getClass().getSimpleName(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ManagementException(method + " " + path + " interrupted", exception);
        }
        int status = response.statusCode();
        if (status >= HTTP_REDIRECT_START && status < HTTP_CLIENT_ERROR_START) {
            throw new ManagementException(method + " " + path + " answered a redirect (" + status + "), refused");
        }
        String text;
        try (InputStream stream = response.body()) {
            text = new String(stream.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new ManagementException(
                    method + " " + path + " body unreadable: "
                            + exception.getClass().getSimpleName(),
                    exception);
        }
        Optional<Duration> retryAfter = response.headers().firstValueAsLong("Retry-After").stream()
                .mapToObj(Duration::ofSeconds)
                .findFirst();
        return new Response(status, text, retryAfter);
    }

    private static String serialize(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (IOException exception) {
            throw new ManagementException("request body serialization failed", exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /// A bounded management answer. The body is kept in memory only; callers copy the fields they
    /// need into evidence.
    record Response(int status, String body, Optional<Duration> retryAfter) {
        static final int HTTP_OK = 200;
        static final int HTTP_ACCEPTED = 202;
        static final int HTTP_FORBIDDEN = 403;
        static final int HTTP_NOT_FOUND = 404;

        boolean success() {
            return status >= HTTP_OK && status < HTTP_REDIRECT_START;
        }

        JsonNode json() {
            try {
                return body.isBlank() ? MissingNode.getInstance() : JSON.readTree(body);
            } catch (IOException exception) {
                return MissingNode.getInstance();
            }
        }

        /// The provider's own error text when there is one, bounded so a stray page cannot flood
        /// the evidence.
        String detail() {
            JsonNode detail = json().path("detail");
            String text = detail.isMissingNode() ? body : detail.asText();
            return text.length() > 200 ? text.substring(0, 200) : text;
        }
    }

    /// A transport or contract failure. Messages name the method and path, never a credential.
    static final class ManagementException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ManagementException(String message) {
            super(message);
        }

        ManagementException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
