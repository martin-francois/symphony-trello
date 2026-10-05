package ch.fmartin.symphony.trello.telemetry;

import static ch.fmartin.symphony.trello.telemetry.BoundedHttp.HTTP_CLIENT_ERROR_START;
import static ch.fmartin.symphony.trello.telemetry.BoundedHttp.HTTP_OK;
import static ch.fmartin.symphony.trello.telemetry.BoundedHttp.HTTP_REDIRECT_START;
import static ch.fmartin.symphony.trello.telemetry.BoundedHttp.HTTP_REQUEST_TIMEOUT;
import static ch.fmartin.symphony.trello.telemetry.BoundedHttp.HTTP_SERVER_ERROR_START;
import static ch.fmartin.symphony.trello.telemetry.BoundedHttp.HTTP_TOO_MANY_REQUESTS;
import static ch.fmartin.symphony.trello.telemetry.BoundedHttp.HTTP_UNAUTHORIZED;

import ch.fmartin.symphony.trello.telemetry.BoundedHttp.BodyRead;
import ch.fmartin.symphony.trello.telemetry.BoundedHttp.Exchange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/// A minimal typed capture adapter for the PostHog single-event endpoint. It sends exactly the
/// bytes it is given, never follows redirects, bounds every timeout and the response size, and
/// reports a [CaptureOutcome] instead of exposing raw responses or exception text.
public final class PostHogCaptureClient {
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);
    private static final String QUOTA_LIMITED_FIELD = "quota_limited";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration requestTimeout;

    public PostHogCaptureClient(URI endpoint) {
        this(BoundedHttp.newClient(), endpoint, REQUEST_TIMEOUT);
    }

    PostHogCaptureClient(HttpClient httpClient, URI endpoint, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
    }

    Duration requestTimeout() {
        return requestTimeout;
    }

    public URI endpoint() {
        return endpoint;
    }

    /// The only headers this client sends besides what the JDK adds for the transport itself.
    public static Map<String, String> requestHeaders() {
        return BoundedHttp.requestHeaders();
    }

    /// Sends the body within the request timeout and classifies the answer.
    public CaptureOutcome capture(String body) {
        return switch (BoundedHttp.post(httpClient, endpoint, requestTimeout, body)) {
            case Exchange.Unanswered unanswered ->
                CaptureOutcome.transientFailure(
                        Optional.empty(), Optional.empty(), unanswered.failure().summary());
            case Exchange.Answered answered ->
                switch (answered.body()) {
                    case BodyRead.Complete complete -> classify(answered, complete.text());
                    case BodyRead.Failed failed -> classify(answered, failed);
                };
        };
    }

    /// A body that never arrived whole cannot prove acceptance: on a 200 the attempt is retried.
    /// Any other status is classified by the status alone, which the headers already settled.
    private CaptureOutcome classify(Exchange.Answered response, BodyRead.Failed failed) {
        int status = response.status();
        if (status == HTTP_OK) {
            return CaptureOutcome.transientFailure(Optional.of(status), Optional.empty(), failed.summary());
        }
        return classify(response, "");
    }

    private CaptureOutcome classify(Exchange.Answered response, String body) {
        int status = response.status();
        if (status == HTTP_OK) {
            return classifyOk(body);
        }
        if (status == HTTP_TOO_MANY_REQUESTS || status == HTTP_REQUEST_TIMEOUT || status >= HTTP_SERVER_ERROR_START) {
            return CaptureOutcome.transientFailure(
                    Optional.of(status), retryAfter(response.headers().map()), "HTTP " + status);
        }
        if (status >= HTTP_CLIENT_ERROR_START) {
            String summary =
                    status == HTTP_UNAUTHORIZED ? "project token rejected (HTTP " + status + ")" : "HTTP " + status;
            return CaptureOutcome.permanentFailure(status, summary);
        }
        if (status >= HTTP_REDIRECT_START) {
            // Redirects are never followed: a moved endpoint is a configuration change, not a retry.
            return CaptureOutcome.permanentFailure(status, "redirect refused (HTTP " + status + ")");
        }
        return CaptureOutcome.permanentFailure(status, "unexpected HTTP " + status);
    }

    /// PostHog documents the 200 status as the success signal and leaves the body form
    /// undocumented, so a complete empty body counts as accepted. A body that is present must be
    /// JSON: a non-empty `quota_limited` array is a drop, anything unparseable is not a proof of
    /// acceptance and is retried.
    private static CaptureOutcome classifyOk(String body) {
        if (body.isEmpty()) {
            return CaptureOutcome.accepted(HTTP_OK);
        }
        JsonNode node;
        try {
            node = JSON.readTree(body);
        } catch (IOException | RuntimeException exception) {
            return CaptureOutcome.transientFailure(Optional.of(HTTP_OK), Optional.empty(), "unrecognized response");
        }
        JsonNode quota = node == null ? null : node.get(QUOTA_LIMITED_FIELD);
        return quota != null && quota.isArray() && !quota.isEmpty()
                ? CaptureOutcome.quotaLimited(HTTP_OK)
                : CaptureOutcome.accepted(HTTP_OK);
    }

    static Optional<Duration> retryAfter(Map<String, List<String>> headers) {
        return headers.entrySet().stream()
                .filter(entry -> "retry-after".equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .map(PostHogCaptureClient::parseRetryAfterSeconds)
                .filter(Objects::nonNull)
                .findAny();
    }

    private static @Nullable Duration parseRetryAfterSeconds(String value) {
        try {
            long seconds = Long.parseLong(value.strip());
            if (seconds <= 0) {
                return null;
            }
            Duration parsed = Duration.ofSeconds(seconds);
            return parsed.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : parsed;
        } catch (NumberFormatException exception) {
            // HTTP-date forms are ignored; the bounded backoff applies instead.
            return null;
        }
    }
}
