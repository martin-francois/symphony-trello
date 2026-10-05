package ch.fmartin.symphony.trello.telemetry;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jboss.logging.Logger;

/// The JSON POST exchange every telemetry client uses: no redirects, fixed headers, one time budget
/// for the headers and the body, and a size bound on the answer. Callers see a status and a body
/// read, never exception text, because transport causes can carry request or response bodies.
final class BoundedHttp {
    static final String USER_AGENT = "symphony-trello-telemetry/1";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final int MAX_RESPONSE_BYTES = 4096;
    private static final Logger LOG = Logger.getLogger(BoundedHttp.class);

    private BoundedHttp() {}

    static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /// The only headers a telemetry request carries besides what the JDK adds for the transport.
    static Map<String, String> requestHeaders() {
        return Map.of("Content-Type", "application/json", "User-Agent", USER_AGENT);
    }

    /// Sends the body and reads the answer, bounded to [#MAX_RESPONSE_BYTES], inside one time
    /// budget. The JDK request timeout covers only the response headers, so the body read runs on
    /// its own thread against the same deadline; on timeout the exchange is cancelled and the
    /// stream closed.
    static Exchange post(HttpClient http, URI uri, Duration timeout, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        requestHeaders().forEach(request::header);
        long deadline = System.nanoTime() + timeout.toNanos();
        CompletableFuture<HttpResponse<InputStream>> exchange =
                http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try {
            response = exchange.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            exchange.cancel(true);
            return new Exchange.Unanswered(Failure.TIMED_OUT);
        } catch (ExecutionException exception) {
            return new Exchange.Unanswered(
                    exception.getCause() instanceof HttpTimeoutException
                            ? Failure.TIMED_OUT
                            : Failure.CONNECTION_FAILED);
        } catch (InterruptedException exception) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            return new Exchange.Unanswered(Failure.INTERRUPTED);
        }
        return new Exchange.Answered(response.statusCode(), response.headers(), readBounded(response.body(), deadline));
    }

    private static long remaining(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    /// Reads the whole body on a virtual thread so a stalled body cannot hold the caller past the
    /// deadline. One byte more than the bound is requested: a read that fills it means the answer
    /// is larger than any documented response, and no prefix of it is trusted. A read that fails,
    /// times out, or is interrupted is a failed attempt, never an empty answer; the stream is closed
    /// on those paths, which cancels the exchange.
    static BodyRead readBounded(InputStream body, long deadline) {
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
            } catch (RuntimeException exception) {
                // Without this the caller would wait out the whole deadline for a read that already ended.
                read.completeExceptionally(exception);
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

    /// Why no response headers arrived; the summary is safe to store and print.
    enum Failure {
        TIMED_OUT("request timed out"),
        CONNECTION_FAILED("connection failed"),
        INTERRUPTED("interrupted");

        private final String summary;

        Failure(String summary) {
            this.summary = summary;
        }

        String summary() {
            return summary;
        }
    }

    /// The result of one exchange: an answer with its status, or the reason none arrived.
    sealed interface Exchange {
        record Answered(int status, HttpHeaders headers, BodyRead body) implements Exchange {}

        record Unanswered(Failure failure) implements Exchange {}
    }

    /// What the body read produced: every byte of a bounded answer, or the reason it is not one.
    sealed interface BodyRead {
        record Complete(String text) implements BodyRead {}

        record Failed(String summary) implements BodyRead {}
    }
}
