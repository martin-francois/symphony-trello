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

    /// Sends the body and reads at most [#MAX_RESPONSE_BYTES] of the answer inside one time budget.
    /// The JDK request timeout covers only the response headers, so the body read runs on its own
    /// thread against the same deadline; on timeout the exchange is cancelled and the stream closed.
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
        return readBounded(response.body(), deadline)
                .map(bodyPrefix -> classify(response, bodyPrefix))
                .orElseGet(() -> CaptureOutcome.transientFailure(
                        Optional.of(response.statusCode()), Optional.empty(), "response body timed out"));
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    /// Reads the bounded body prefix on a virtual thread so a stalled body cannot hold the caller
    /// past the deadline. Empty means the read did not finish in time; the stream is closed, which
    /// cancels the exchange. A body that fails mid-read yields the bytes read so far.
    private static Optional<byte[]> readBounded(InputStream body, long deadline) {
        var read = new CompletableFuture<byte[]>();
        Thread.startVirtualThread(() -> {
            try (InputStream stream = body) {
                read.complete(stream.readNBytes(MAX_RESPONSE_BYTES));
            } catch (IOException exception) {
                read.complete(new byte[0]);
            }
        });
        try {
            return Optional.of(read.get(remaining(deadline), TimeUnit.NANOSECONDS));
        } catch (TimeoutException exception) {
            closeQuietly(body);
            return Optional.empty();
        } catch (ExecutionException exception) {
            return Optional.of(new byte[0]);
        } catch (InterruptedException exception) {
            closeQuietly(body);
            Thread.currentThread().interrupt();
            return Optional.empty();
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

    private CaptureOutcome classify(HttpResponse<InputStream> response, byte[] bodyPrefix) {
        int status = response.statusCode();
        if (status == HTTP_OK) {
            return quotaLimited(bodyPrefix) ? CaptureOutcome.quotaLimited(status) : CaptureOutcome.accepted(status);
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

    private static boolean quotaLimited(byte[] bodyPrefix) {
        try {
            JsonNode node = JSON.readTree(bodyPrefix);
            JsonNode quota = node == null ? null : node.get(QUOTA_LIMITED_FIELD);
            return quota != null && quota.isArray() && !quota.isEmpty();
        } catch (IOException | RuntimeException exception) {
            return false;
        }
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
