package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class PostHogCaptureClientTest {
    private static final String BODY = "{\"event\":\"installation_heartbeat\"}";
    private static final Duration SHORT_REQUEST_TIMEOUT = Duration.ofMillis(500);

    private HttpServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void acceptedResponseCarriesOnlyTheStatusAndTheBodyIsSentVerbatim() {
        // given
        respondWith(200, "{\"status\":\"Ok\"}", Map.of());
        PostHogCaptureClient client = new PostHogCaptureClient(endpoint("/i/v0/e/"));

        // when
        CaptureOutcome outcome = client.capture(BODY);

        // then
        assertThat(outcome.kind()).isEqualTo(CaptureOutcome.Kind.ACCEPTED);
        assertThat(outcome.statusCode()).contains(200);
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/i/v0/e/");
            assertThat(request.body()).isEqualTo(BODY);
            assertThat(request.headers()).containsEntry("Content-type", "application/json");
            assertThat(request.headers()).containsEntry("User-agent", PostHogCaptureClient.USER_AGENT);
            assertThat(request.headers().keySet())
                    .noneMatch(name -> name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Cookie"));
        });
    }

    @Test
    void quotaLimitedTwoHundredIsReportedSeparately() {
        // given
        respondWith(200, "{\"status\":1,\"quota_limited\":[\"events\"]}", Map.of());
        PostHogCaptureClient client = new PostHogCaptureClient(endpoint("/i/v0/e/"));

        // when
        CaptureOutcome outcome = client.capture(BODY);

        // then
        assertThat(outcome.kind()).isEqualTo(CaptureOutcome.Kind.QUOTA_LIMITED);
        assertThat(outcome.accepted())
                .as("a dropped event is not an accepted report")
                .isFalse();
    }

    @CsvSource({
        "400, PERMANENT",
        "401, PERMANENT",
        "413, PERMANENT",
        "408, TRANSIENT",
        "429, TRANSIENT",
        "503, TRANSIENT",
        "500, TRANSIENT"
    })
    @ParameterizedTest(name = "HTTP {0} is {1}")
    void statusCodesAreClassified(int status, CaptureOutcome.Kind expected) {
        // given
        respondWith(status, "{\"type\":\"error\",\"detail\":\"secret " + status + "\"}", Map.of());
        PostHogCaptureClient client = new PostHogCaptureClient(endpoint("/i/v0/e/"));

        // when
        CaptureOutcome outcome = client.capture(BODY);

        // then
        assertThat(outcome.kind()).isEqualTo(expected);
        assertThat(outcome.summary()).doesNotContain("secret");
    }

    @Test
    void retryAfterIsHonoredAndBounded() {
        // given
        respondWith(429, "", Map.of("Retry-After", "120"));
        PostHogCaptureClient client = new PostHogCaptureClient(endpoint("/i/v0/e/"));

        // when
        CaptureOutcome outcome = client.capture(BODY);
        Optional<Duration> huge = PostHogCaptureClient.retryAfter(Map.of("retry-after", List.of("999999")));
        Optional<Duration> date =
                PostHogCaptureClient.retryAfter(Map.of("Retry-After", List.of("Wed, 21 Oct 2026 07:28:00 GMT")));

        // then
        assertThat(outcome.retryAfter()).contains(Duration.ofSeconds(120));
        assertThat(huge).contains(PostHogCaptureClient.MAX_RETRY_AFTER);
        assertThat(date).isEmpty();
    }

    @Test
    void redirectsAreNotFollowed() {
        // given
        respondWith(307, "", Map.of("Location", "https://example.invalid/elsewhere"));
        PostHogCaptureClient client = new PostHogCaptureClient(endpoint("/i/v0/e/"));

        // when
        CaptureOutcome outcome = client.capture(BODY);

        // then
        assertThat(outcome.kind()).isEqualTo(CaptureOutcome.Kind.PERMANENT);
        assertThat(outcome.summary()).contains("redirect");
        assertThat(requests).hasSize(1);
    }

    @Test
    void anOversizedTwoHundredIsAFailedAttemptNotAnAcceptedReport() {
        // given
        respondWith(200, "{\"status\":1,\"padding\":\"" + "x".repeat(200_000) + "\"}", Map.of());
        PostHogCaptureClient client = new PostHogCaptureClient(endpoint("/i/v0/e/"));

        // when
        CaptureOutcome outcome = client.capture(BODY);

        // then
        assertThat(outcome.kind()).isEqualTo(CaptureOutcome.Kind.TRANSIENT);
        assertThat(outcome.summary()).isEqualTo("response too large");
        assertThat(outcome.statusCode()).contains(200);
    }

    @Test
    void aCompleteEmptyTwoHundredIsAccepted() {
        // given
        respondWith(200, "", Map.of());
        PostHogCaptureClient client = new PostHogCaptureClient(endpoint("/i/v0/e/"));

        // when
        CaptureOutcome outcome = client.capture(BODY);

        // then
        assertThat(outcome.kind()).isEqualTo(CaptureOutcome.Kind.ACCEPTED);
    }

    @Test
    void aCompleteTwoHundredThatIsNotJsonIsRetriedNotAccepted() {
        // given
        respondWith(200, "<html>maintenance</html>", Map.of());
        PostHogCaptureClient client = new PostHogCaptureClient(endpoint("/i/v0/e/"));

        // when
        CaptureOutcome outcome = client.capture(BODY);

        // then
        assertThat(outcome.kind()).isEqualTo(CaptureOutcome.Kind.TRANSIENT);
        assertThat(outcome.summary()).isEqualTo("unrecognized response");
    }

    @Test
    void headersFollowedByAStalledBodyTimeOutWithinTheBudgetAndReleaseTheConnection() throws Exception {
        // given
        var release = new CountDownLatch(1);
        var handlerFinished = new CountDownLatch(1);
        server.createContext("/stalled", exchange -> {
            exchange.sendResponseHeaders(200, 20);
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("{\"status\":\"Ok\"}     ".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // the client already closed the stream
            }
            handlerFinished.countDown();
        });
        PostHogCaptureClient client = new PostHogCaptureClient(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                endpoint("/stalled"),
                SHORT_REQUEST_TIMEOUT);

        // when
        long started = System.nanoTime();
        CaptureOutcome outcome = client.capture(BODY);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        release.countDown();

        // then
        assertThat(outcome.kind()).isEqualTo(CaptureOutcome.Kind.TRANSIENT);
        assertThat(outcome.summary()).isEqualTo("response body timed out");
        assertThat(outcome.statusCode()).contains(200);
        assertThat(elapsedMillis)
                .as(
                        "the whole attempt, body included, ends close to the %s ms budget",
                        SHORT_REQUEST_TIMEOUT.toMillis())
                .isLessThan(SHORT_REQUEST_TIMEOUT.toMillis() * 6);
        assertThat(handlerFinished.await(5, TimeUnit.SECONDS))
                .as("the server side unblocks after the client cancels")
                .isTrue();
    }

    @Test
    void aBodyCutOffInsideTheQuotaAnswerIsAFailedAttempt() {
        // given
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(200, 200);
            exchange.getResponseBody()
                    .write("{\"status\":\"Ok\",\"quota_limited\":[\"ev".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            exchange.close();
        });
        PostHogCaptureClient client = new PostHogCaptureClient(endpoint("/broken"));

        // when
        CaptureOutcome outcome = client.capture(BODY);

        // then
        assertThat(outcome.kind()).isEqualTo(CaptureOutcome.Kind.TRANSIENT);
        assertThat(outcome.summary()).isEqualTo("response body unreadable");
        assertThat(outcome.statusCode()).contains(200);
    }

    @Test
    void anImmediateEndOfStreamAgainstAnAdvertisedLengthIsAFailedAttempt() {
        // given
        server.createContext("/eof", exchange -> {
            exchange.sendResponseHeaders(200, 50);
            exchange.getResponseBody().flush();
            exchange.close();
        });
        PostHogCaptureClient client = new PostHogCaptureClient(endpoint("/eof"));

        // when
        CaptureOutcome outcome = client.capture(BODY);

        // then
        assertThat(outcome.kind()).isEqualTo(CaptureOutcome.Kind.TRANSIENT);
        assertThat(outcome.summary()).isEqualTo("response body unreadable");
    }

    @Test
    void aChunkedBodyThatBreaksMidwayIsAFailedAttempt() throws Exception {
        // given
        try (ServerSocket raw = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.startVirtualThread(() -> {
                try (Socket connection = raw.accept()) {
                    connection.getInputStream().read(new byte[1024]);
                    connection
                            .getOutputStream()
                            .write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                                            + "40\r\n{\"status\":\"Ok\",\"quota_lim")
                                    .getBytes(StandardCharsets.UTF_8));
                    connection.getOutputStream().flush();
                } catch (IOException ignored) {
                    // the client sees the broken body either way
                }
            });
            PostHogCaptureClient client =
                    new PostHogCaptureClient(URI.create("http://127.0.0.1:" + raw.getLocalPort() + "/i/v0/e/"));

            // when
            CaptureOutcome outcome = client.capture(BODY);

            // then
            assertThat(outcome.kind()).isEqualTo(CaptureOutcome.Kind.TRANSIENT);
            assertThat(outcome.summary()).isEqualTo("response body unreadable");
            assertThat(outcome.statusCode()).contains(200);
        }
    }

    @Test
    void anInterruptedCallerGetsAFailedAttemptAndReleasesTheConnection() throws Exception {
        // given
        var release = new CountDownLatch(1);
        var handlerFinished = new CountDownLatch(1);
        server.createContext("/interrupt", exchange -> {
            exchange.sendResponseHeaders(200, 20);
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("{\"status\":\"Ok\"}     ".getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // the client already closed the stream
            }
            handlerFinished.countDown();
        });
        PostHogCaptureClient client = new PostHogCaptureClient(endpoint("/interrupt"));
        var outcome = new AtomicReference<CaptureOutcome>();
        var interruptedFlag = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            outcome.set(client.capture(BODY));
            interruptedFlag.set(Thread.currentThread().isInterrupted());
        });

        // when
        caller.start();
        Thread.sleep(200);
        caller.interrupt();
        caller.join(5_000);
        release.countDown();

        // then
        assertThat(caller.isAlive())
                .as("the caller returns promptly after the interrupt")
                .isFalse();
        assertThat(outcome.get().kind()).isEqualTo(CaptureOutcome.Kind.TRANSIENT);
        assertThat(outcome.get().summary()).isEqualTo("interrupted");
        assertThat(interruptedFlag)
                .as("the interrupt flag is restored for the caller")
                .isTrue();
        assertThat(handlerFinished.await(5, TimeUnit.SECONDS))
                .as("the server side unblocks after the client closes the stream")
                .isTrue();
    }

    @Test
    void connectionFailureAndTimeoutAreTransient() throws Exception {
        // given
        var release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        PostHogCaptureClient slow = new PostHogCaptureClient(
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                endpoint("/slow"),
                SHORT_REQUEST_TIMEOUT);
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        PostHogCaptureClient unreachable =
                new PostHogCaptureClient(URI.create("http://127.0.0.1:" + closedPort + "/i/v0/e/"));

        // when
        CaptureOutcome timedOut = slow.capture(BODY);
        release.countDown();
        CaptureOutcome refused = unreachable.capture(BODY);

        // then
        assertThat(timedOut.kind()).isEqualTo(CaptureOutcome.Kind.TRANSIENT);
        assertThat(timedOut.summary()).isEqualTo("request timed out");
        assertThat(refused.kind()).isEqualTo(CaptureOutcome.Kind.TRANSIENT);
        assertThat(refused.statusCode()).isEmpty();
    }

    private URI endpoint(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private void respondWith(int status, String body, Map<String, String> headers) {
        server.createContext("/", exchange -> {
            record(exchange);
            headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
    }

    private void record(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, String.join(",", values)));
        requests.add(new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                headers));
    }

    private record RecordedRequest(String method, String path, String body, Map<String, String> headers) {}
}
