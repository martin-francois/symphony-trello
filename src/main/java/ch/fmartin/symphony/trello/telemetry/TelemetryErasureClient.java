package ch.fmartin.symphony.trello.telemetry;

import ch.fmartin.symphony.trello.telemetry.BoundedHttp.BodyRead;
import ch.fmartin.symphony.trello.telemetry.BoundedHttp.Exchange;
import ch.fmartin.symphony.trello.telemetry.BoundedHttp.Failure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/// Bounded HTTPS transport for ownership operations. Request bodies and raw errors are never logged.
final class TelemetryErasureClient implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(TelemetryErasureClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    // The erasure handler (infra/posthog/erasure-service.hog.tftpl) rejects longer validity windows.
    static final Duration SIGNATURE_LIFETIME = Duration.ofSeconds(900);
    // Wire values the handler and scripts/erasure-lifecycle-live.ts repeat; TelemetryErasureContractTest
    // pins each of them on every side.
    static final String ISSUE_PAYLOAD = "issue-v1";
    static final String SIGNED_FORMAT = "h2";
    static final String STATUS_KEY_CONTEXT = "symphony-trello/status/v1|";
    static final String STATUS_FLAG_PREFIX = "erasure-";
    private static final String NOT_CONFIRMED = "erasure service did not confirm the request; retry later";
    // The handler answers 401 to a bad signature and to a timestamp outside its clock tolerance,
    // so a fast or slow clock is the common cause a user can fix; retries keep their backoff.
    static final String SIGNATURE_REJECTED = "erasure service rejected the signed request; check that the system"
            + " clock is correct, and if it is, contact the maintainer because the stored credential may be invalid";
    private final TelemetryErasureEndpoint endpoint;
    private final TelemetryDistribution distribution;
    private final HttpClient http = BoundedHttp.newClient();

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
        JsonNode result = post(ISSUE_PAYLOAD, NOT_CONFIRMED);
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
                SIGNED_FORMAT,
                action.wireName(),
                endpoint.audience(),
                ownership.credential().subject(),
                ownership.period().toString(),
                erasure.operation().toString(),
                Long.toString(now.getEpochSecond()),
                Long.toString(now.plus(SIGNATURE_LIFETIME).getEpochSecond()));
        post(unsigned + "|" + ownership.credential().sign(unsigned), SIGNATURE_REJECTED);
    }

    /// Best effort: a lost acknowledgment leaves only a status flag for the operator to archive.
    void acknowledge(TelemetryOwnership ownership, TelemetryOwnership.Erasure erasure, Instant now) {
        try {
            request(Action.ACK, ownership, erasure, now);
        } catch (TelemetryStateException unreachable) {
            // Completion is already durable locally, so the user has nothing to retry.
            LOG.debugf("erasure acknowledgment not delivered: %s", unreachable.getMessage());
        }
    }

    private JsonNode post(String payload, String rejected) {
        // All fields have a restricted alphabet; canonical JSON rejects duplicate/extra members server-side.
        String body = "{\"payload\":\"" + payload + "\"}";
        return post(endpoint.uri(), body, rejected);
    }

    /// The provider-confirmed phase, or empty while the service reports pending or an unknown value.
    Optional<TelemetryOwnership.Phase> status(TelemetryOwnership ownership) {
        String key = STATUS_FLAG_PREFIX
                + ownership.credential().sign(STATUS_KEY_CONTEXT + endpoint.audience() + "|" + ownership.period());
        var body = JSON.createObjectNode();
        body.put("api_key", distribution.projectToken().orElseThrow());
        body.put("distinct_id", "erasure-status");
        body.putArray("flag_keys_to_evaluate").add(key);
        JsonNode response = post(distribution.endpoint().resolve("/flags?v=2"), body.toString(), NOT_CONFIRMED);
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

    /// `rejected` is the diagnostic for a 401, whose likely cause depends on the request.
    private JsonNode post(URI uri, String body, String rejected) {
        return switch (BoundedHttp.post(http, uri, TIMEOUT, body)) {
            case Exchange.Unanswered unanswered ->
                throw new TelemetryStateException(
                        unanswered.failure() == Failure.INTERRUPTED
                                ? "erasure request interrupted; its outcome is unknown"
                                : "erasure service unavailable; its outcome is unknown");
            case Exchange.Answered answered -> answer(answered, rejected);
        };
    }

    // JSON parsing causes can retain the response body. Expose only fixed diagnostics.
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static JsonNode answer(Exchange.Answered answered, String rejected) {
        if (answered.status() == Status.UNAUTHORIZED.getStatusCode()) {
            throw new TelemetryStateException(rejected);
        }
        boolean receipt = answered.status() == Status.CREATED.getStatusCode();
        if ((answered.status() != Status.OK.getStatusCode() && !receipt)
                || !(answered.body() instanceof BodyRead.Complete complete)) {
            throw new TelemetryStateException(NOT_CONFIRMED);
        }
        if (receipt || complete.text().isBlank()) {
            // A receipt carries no result; the handler continues in the background.
            return JSON.createObjectNode();
        }
        try {
            JsonNode result = JSON.readTree(complete.text());
            if (result == null || !result.isObject()) {
                throw new TelemetryStateException("erasure service returned an unrecognized response");
            }
            return result;
        } catch (IOException exception) {
            throw new TelemetryStateException("erasure service returned an unreadable response");
        }
    }
}
