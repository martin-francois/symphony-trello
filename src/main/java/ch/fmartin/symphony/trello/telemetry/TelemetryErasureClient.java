package ch.fmartin.symphony.trello.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Bounded HTTPS transport for ownership operations. Request bodies and raw errors are never logged.
final class TelemetryErasureClient implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private final TelemetryErasureEndpoint endpoint;
    private final TelemetryDistribution distribution;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    TelemetryErasureClient(TelemetryErasureEndpoint endpoint, TelemetryDistribution distribution) {
        this.endpoint = endpoint;
        this.distribution = distribution;
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

    JsonNode request(String action, TelemetryOwnership ownership, TelemetryOwnership.Erasure erasure, Instant now) {
        String unsigned = String.join(
                "|",
                "h2",
                action,
                endpoint.audience(),
                ownership.credential().subject(),
                ownership.period().toString(),
                erasure.operation().toString(),
                Long.toString(now.getEpochSecond()),
                Long.toString(now.plusSeconds(900).getEpochSecond()));
        return post(unsigned + "|" + ownership.credential().sign(unsigned));
    }

    private JsonNode post(String payload) {
        // All fields have a restricted alphabet; canonical JSON rejects duplicate/extra members server-side.
        String body = "{\"payload\":\"" + payload + "\"}";
        return post(endpoint.uri(), body);
    }

    JsonNode status(TelemetryOwnership ownership) {
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
        String status = response.path("flags").path(key).path("variant").asText("pending");
        return JSON.createObjectNode().put("status", status);
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
            if ((response.statusCode() != 200 && response.statusCode() != 201)
                    || !(read instanceof PostHogCaptureClient.BodyRead.Complete complete)) {
                throw new TelemetryStateException("erasure service did not confirm the request; retry later");
            }
            if (response.statusCode() == 201 || complete.text().isBlank()) {
                return JSON.createObjectNode().put("status", "queued");
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
