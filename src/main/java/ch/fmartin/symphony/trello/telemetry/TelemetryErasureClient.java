package ch.fmartin.symphony.trello.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Bounded HTTPS transport for ownership operations. Request bodies and raw errors are never logged.
final class TelemetryErasureClient implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // The erasure handler (infra/posthog/erasure-service.hog.tftpl) rejects longer validity windows.
    private static final Duration SIGNATURE_LIFETIME = Duration.ofSeconds(900);
    private final TelemetryErasureEndpoint endpoint;
    private final TelemetryDistribution distribution;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    TelemetryErasureClient(TelemetryErasureEndpoint endpoint, TelemetryDistribution distribution) {
        this.endpoint = endpoint;
        this.distribution = distribution;
    }

    /// Signed operations the erasure handler accepts; the wire name is the lower-case constant name.
    enum Action {
        ERASE,
        STATUS,
        ACK;

        String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public void close() {
        http.shutdownNow();
    }

    // Parsing causes can contain the issuance response, including its credential.
    @SuppressWarnings("PMD.PreserveStackTrace")
    TelemetryCredential issue() {
        JsonNode result = post("issue-v1");
        try {
            if (!"issued".equals(result.path("status").asText())) {
                throw new IllegalArgumentException("unexpected issuance response");
            }
            return new TelemetryCredential(
                    UUID.fromString(result.path("installation_id").asText()),
                    result.path("key_version").asText(),
                    result.path("secret").asText());
        } catch (IllegalArgumentException exception) {
            throw new TelemetryStateException("ownership issuance did not return a valid credential");
        }
    }

    void request(Action action, TelemetryOwnership ownership, TelemetryOwnership.Erasure erasure, Instant now) {
        String unsigned = String.join(
                "|",
                "h2",
                action.wireName(),
                endpoint.audience(),
                ownership.credential().subject(),
                ownership.period().toString(),
                erasure.operation().toString(),
                Long.toString(now.getEpochSecond()),
                Long.toString(now.plus(SIGNATURE_LIFETIME).getEpochSecond()));
        post(unsigned + "|" + ownership.credential().sign(unsigned));
    }

    /// Best effort: returns false instead of failing when the service cannot be reached.
    boolean acknowledge(TelemetryOwnership ownership, TelemetryOwnership.Erasure erasure, Instant now) {
        try {
            request(Action.ACK, ownership, erasure, now);
            return true;
        } catch (TelemetryStateException unreachable) {
            return false;
        }
    }

    private JsonNode post(String payload) {
        // All fields have a restricted alphabet; canonical JSON rejects duplicate/extra members server-side.
        String body = "{\"payload\":\"" + payload + "\"}";
        return post(endpoint.uri(), body);
    }

    /// The provider-confirmed phase, or empty while the service reports pending or an unknown value.
    Optional<TelemetryOwnership.Phase> status(TelemetryOwnership ownership) {
        String key = "erasure-"
                + ownership
                        .credential()
                        .sign("symphony-trello/status/v1|" + endpoint.audience() + "|" + ownership.period());
        var body = JSON.createObjectNode();
        body.put("api_key", distribution.projectToken().orElseThrow());
        body.put("distinct_id", "erasure-status");
        body.putArray("flag_keys_to_evaluate").add(key);
        JsonNode response = post(distribution.endpoint().resolve("/flags?v=2"), body.toString());
        if (response.path("errorsWhileComputingFlags").asBoolean(true)) {
            throw new TelemetryStateException("erasure status is temporarily unavailable");
        }
        return switch (response.path("flags").path(key).path("variant").asText()) {
            case "complete" -> Optional.of(TelemetryOwnership.Phase.COMPLETE);
            case "accepted" -> Optional.of(TelemetryOwnership.Phase.ACCEPTED);
            case "refused" -> Optional.of(TelemetryOwnership.Phase.REFUSED);
            default -> Optional.empty();
        };
    }

    // Transport and JSON causes can retain request/response bodies. Expose only fixed diagnostics.
    @SuppressWarnings("PMD.PreserveStackTrace")
    private JsonNode post(URI uri, String body) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("User-Agent", PostHogCaptureClient.USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        CompletableFuture<HttpResponse<InputStream>> exchange =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        try {
            HttpResponse<InputStream> response = exchange.get(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
            PostHogCaptureClient.BodyRead read = PostHogCaptureClient.readBounded(response.body(), deadline);
            boolean receipt = response.statusCode() == Status.CREATED.getStatusCode();
            if ((response.statusCode() != Status.OK.getStatusCode() && !receipt)
                    || !(read instanceof PostHogCaptureClient.BodyRead.Complete complete)) {
                throw new TelemetryStateException("erasure service did not confirm the request; retry later");
            }
            if (receipt || complete.text().isBlank()) {
                // A receipt carries no result; the handler continues in the background.
                return JSON.createObjectNode();
            }
            JsonNode result = JSON.readTree(complete.text());
            if (result == null || !result.isObject()) {
                throw new TelemetryStateException("erasure service returned an unrecognized response");
            }
            return result;
        } catch (InterruptedException exception) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new TelemetryStateException("erasure request interrupted; its outcome is unknown");
        } catch (ExecutionException | TimeoutException exception) {
            exchange.cancel(true);
            throw new TelemetryStateException("erasure service unavailable; its outcome is unknown");
        } catch (IOException exception) {
            throw new TelemetryStateException("erasure service returned an unreadable response");
        }
    }
}
