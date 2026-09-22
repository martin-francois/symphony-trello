package ch.fmartin.symphony.trello.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

/// A minimal typed capture adapter for the PostHog single-event endpoint. It sends exactly the
/// bytes it is given, never follows redirects, bounds every timeout and the response size, and
/// reports a [CaptureOutcome] instead of exposing raw responses or exception text.
public final class PostHogCaptureClient {
    public static final String USER_AGENT = "symphony-trello-telemetry/1";
    private static final Logger LOG = Logger.getLogger(PostHogCaptureClient.class);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    static final int MAX_RESPONSE_BYTES = 4096;
    static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);
    private static final int HTTP_OK = 200;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_SERVER_ERROR_START = 500;
    private static final int HTTP_CLIENT_ERROR_START = 400;
    private static final int HTTP_REDIRECT_START = 300;
    private static final String QUOTA_LIMITED_FIELD = "quota_limited";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI endpoint;
    private final Duration requestTimeout;

    public PostHogCaptureClient(URI endpoint) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                endpoint,
                REQUEST_TIMEOUT);
    }

    PostHogCaptureClient(HttpClient httpClient, URI endpoint, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
    }

    public URI endpoint() {
        return endpoint;
    }

    /// The only headers this client sends besides what the JDK adds for the transport itself.
    public static Map<String, String> requestHeaders() {
        return Map.of("Content-Type", "application/json", "User-Agent", USER_AGENT);
    }

    /// Sends the body and reads the answer, bounded to [#MAX_RESPONSE_BYTES], inside one time
    /// budget. The JDK request timeout covers only the response headers, so the body read runs on
    /// its own thread against the same deadline; on timeout the exchange is cancelled and the
    /// stream closed.
    public CaptureOutcome capture(String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        requestHeaders().forEach(request::header);
        long deadline = System.nanoTime() + requestTimeout.toNanos();
        CompletableFuture<HttpResponse<InputStream>> exchange =
                httpClient.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try {
            response = exchange.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            exchange.cancel(true);
            return CaptureOutcome.transientFailure(Optional.empty(), Optional.empty(), "request timed out");
        } catch (ExecutionException exception) {
            return CaptureOutcome.transientFailure(
                    Optional.empty(),
                    Optional.empty(),
                    exception.getCause() instanceof HttpTimeoutException ? "request timed out" : "connection failed");
        } catch (InterruptedException exception) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            return CaptureOutcome.transientFailure(Optional.empty(), Optional.empty(), "interrupted");
        }
        return switch (readBounded(response.body(), deadline)) {
            case BodyRead.Complete complete -> classify(response, complete.text());
            case BodyRead.Failed failed -> classify(response, failed);
        };
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    /// Reads the whole body on a virtual thread so a stalled body cannot hold the caller past the
    /// deadline. One byte more than the bound is requested: a read that fills it means the answer
    /// is larger than any documented response, and no prefix of it is trusted. A read that fails,
    /// times out, or is interrupted is a failed attempt, never an empty answer; the stream is closed
    /// on those paths, which cancels the exchange.
    private static BodyRead readBounded(InputStream body, long deadline) {
        var read = new CompletableFuture<BodyRead>();
        Thread.startVirtualThread(() -> {
            try (InputStream stream = body) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                read.complete(
                        bytes.length > MAX_RESPONSE_BYTES
                                ? new BodyRead.Failed("response too large")
                                : new BodyRead.Complete(new String(bytes, StandardCharsets.UTF_8)));
            } catch (IOException exception) {
                read.complete(new BodyRead.Failed("response body unreadable"));
            }
        });
        try {
            return read.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            read.cancel(true);
            closeQuietly(body);
            return new BodyRead.Failed("response body timed out");
        } catch (ExecutionException exception) {
            closeQuietly(body);
            return new BodyRead.Failed("response body unreadable");
        } catch (InterruptedException exception) {
            read.cancel(true);
            closeQuietly(body);
            Thread.currentThread().interrupt();
            return new BodyRead.Failed("interrupted");
        }
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException exception) {
            // Closing only serves to cancel the exchange; a failure to close changes nothing.
            LOG.debugf(exception, "telemetry response stream close failed");
        }
    }

    /// A body that never arrived whole cannot prove acceptance: on a 200 the attempt is retried.
    /// Any other status is classified by the status alone, which the headers already settled.
    private CaptureOutcome classify(HttpResponse<InputStream> response, BodyRead.Failed failed) {
        int status = response.statusCode();
        if (status == HTTP_OK) {
            return CaptureOutcome.transientFailure(Optional.of(status), Optional.empty(), failed.summary());
        }
        return classify(response, "");
    }

    private CaptureOutcome classify(HttpResponse<InputStream> response, String body) {
        int status = response.statusCode();
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

    /// What the body read produced: every byte of a bounded answer, or the reason it is not one.
    private sealed interface BodyRead {
        record Complete(String text) implements BodyRead {}

        record Failed(String summary) implements BodyRead {}
    }
}
