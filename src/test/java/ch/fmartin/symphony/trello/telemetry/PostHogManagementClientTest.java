package ch.fmartin.symphony.trello.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.telemetry.PostHogManagementClient.ManagementException;
import ch.fmartin.symphony.trello.telemetry.PostHogManagementClient.Response;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/// The management client against a loopback server that misbehaves: it must stay bounded in time
/// and bytes, close what it abandons, honor the invocation budget, and never repeat a secret.
final class PostHogManagementClientTest {
    private static final String KEY = "phx_unit_test_key_0123456789";
    private static final long PROJECT_ID = 7;
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(300);
    private static final Duration GENEROUS_WAIT = Duration.ofSeconds(30);
    private static final int LARGE_BODY_BYTES = 300 * 1024;

    private HttpServer server;
    private URI host;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
        host = URI.create("http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
    }

    @Test
    void aStalledBodyFailsWithinTheDeadlineAndTheReadIsAbandoned() {
        // given
        server.createContext("/api/projects/", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write('{');
                body.flush();
                awaitRelease();
            }
        });
        ExperimentBudget budget = new ExperimentBudget(10, GENEROUS_WAIT, Thread::sleep);
        PostHogManagementClient client = client(budget, SHORT_TIMEOUT);
        long started = System.nanoTime();

        // when
        Throwable thrown = catchThrowable(client::project);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // then
        assertThat(thrown).isInstanceOf(ManagementException.class).hasMessageContaining("body timed out");
        assertThat(elapsed).isLessThan(SHORT_TIMEOUT.multipliedBy(8));
        assertThat(budget.waited())
                .as("the stalled read was charged to the wait budget")
                .isGreaterThanOrEqualTo(SHORT_TIMEOUT.dividedBy(2));
    }

    @Test
    void anOversizedBodyIsRefused() {
        // given
        server.createContext("/api/projects/", exchange -> respond(exchange, 200, "x".repeat(LARGE_BODY_BYTES)));
        PostHogManagementClient client = client(new ExperimentBudget(10, GENEROUS_WAIT, Thread::sleep), SHORT_TIMEOUT);

        // when
        Throwable thrown = catchThrowable(client::project);

        // then
        assertThat(thrown).isInstanceOf(ManagementException.class).hasMessageContaining("bytes, refused");
    }

    @Test
    void aRedirectWithABodyIsRefusedAndNotFollowed() {
        // given
        List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/api/projects/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            exchange.getResponseHeaders()
                    .add("Location", host.resolve("/elsewhere/").toString());
            respond(exchange, 302, "{\"detail\":\"moved\"}");
        });
        server.createContext("/elsewhere/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            respond(exchange, 200, "{}");
        });
        PostHogManagementClient client = client(new ExperimentBudget(10, GENEROUS_WAIT, Thread::sleep), SHORT_TIMEOUT);

        // when
        Throwable thrown = catchThrowable(client::project);

        // then
        assertThat(thrown).isInstanceOf(ManagementException.class).hasMessageContaining("redirect (302)");
        assertThat(paths).containsExactly("/api/projects/" + PROJECT_ID + "/");
    }

    @Test
    void aMalformedAnswerIsNotJsonAndKeepsItsRawDetail() {
        // given
        server.createContext("/api/projects/", exchange -> respond(exchange, 200, "<html>maintenance</html>"));
        PostHogManagementClient client = client(new ExperimentBudget(10, GENEROUS_WAIT, Thread::sleep), SHORT_TIMEOUT);

        // when
        Response response = client.project();

        // then
        assertThat(response.json().isMissingNode())
                .as("malformed JSON must not look like an object")
                .isTrue();
        assertThat(response.detail()).isEqualTo("<html>maintenance</html>");
    }

    @Test
    void anExhaustedWaitBudgetStopsARequestBeforeItStalls() {
        // given
        server.createContext("/api/projects/", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write('{');
                body.flush();
                awaitRelease();
            }
        });
        ExperimentBudget budget = new ExperimentBudget(10, Duration.ofMillis(150), Thread::sleep);
        PostHogManagementClient client = client(budget, Duration.ofSeconds(30));
        long started = System.nanoTime();

        // when
        Throwable first = catchThrowable(client::project);
        Throwable second = catchThrowable(client::project);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // then
        assertThat(first).isInstanceOf(RuntimeException.class);
        assertThat(second).isInstanceOf(ExperimentBudget.BudgetExhaustedException.class);
        assertThat(elapsed)
                .as("the budget, not the 30 s request timeout, bounded the calls")
                .isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aLargeRetryAfterIsNotWaitedForBeyondTheBudget() {
        // given
        server.createContext("/api/projects/", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "3600");
            respond(exchange, 429, "{\"detail\":\"slow down\"}");
        });
        List<Duration> sleeps = new java.util.concurrent.CopyOnWriteArrayList<>();
        ExperimentBudget budget = new ExperimentBudget(10, Duration.ofSeconds(10), sleeps::add);
        PostHogManagementClient client = client(budget, SHORT_TIMEOUT);

        // when
        Throwable thrown = catchThrowable(client::project);

        // then
        assertThat(thrown)
                .isInstanceOf(ExperimentBudget.BudgetExhaustedException.class)
                .hasMessageContaining("rate limited");
        assertThat(sleeps)
                .as("no sleep happens once the budget refuses the delay")
                .isEmpty();
    }

    @Test
    void anAnswerThatEchoesTheKeyIsRedacted() {
        // given
        server.createContext(
                "/api/projects/", exchange -> respond(exchange, 400, "{\"detail\":\"bad key " + KEY + "\"}"));
        PostHogManagementClient client = client(new ExperimentBudget(10, GENEROUS_WAIT, Thread::sleep), SHORT_TIMEOUT);

        // when
        Response response = client.project();

        // then
        assertThat(response.detail()).contains("[redacted]").doesNotContain(KEY);
        assertThat(response.body()).doesNotContain(KEY);
    }

    @Test
    void anInterruptedCallerGetsAnExceptionAndKeepsItsInterruptFlag() {
        // given
        server.createContext("/api/projects/", exchange -> respond(exchange, 200, "{}"));
        PostHogManagementClient client = client(new ExperimentBudget(10, GENEROUS_WAIT, Thread::sleep), SHORT_TIMEOUT);
        Thread.currentThread().interrupt();

        // when
        Throwable thrown = catchThrowable(client::project);
        boolean interrupted = Thread.interrupted();

        // then
        assertThat(thrown).isInstanceOf(ManagementException.class).hasMessageContaining("interrupted");
        assertThat(interrupted)
                .as("the interrupt flag survives the failed call")
                .isTrue();
    }

    private PostHogManagementClient client(ExperimentBudget budget, Duration timeout) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new PostHogManagementClient(http, host, PROJECT_ID, () -> KEY, budget, timeout);
    }

    private void awaitRelease() {
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
