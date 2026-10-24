package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalHealthCheckerTest {
    /// Far above the 500 ms probe timeout, so only a checker that never re-probes reaches it.
    private static final Duration REPROBE_WAIT_BOUND = Duration.ofSeconds(10);

    private HttpServer server;

    @TempDir
    Path tempDir;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void workflowHealthAcceptsConfiguredBoardKeyWhenRuntimeReportsResolvedBoardId() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md").toAbsolutePath().normalize();
        startLocalStatusServer(
                """
                {"workflowPath":"%s","boardId":"full-board-id","configuredBoardId":"abc123"}
                """
                        .formatted(workflow));
        var checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());

        // when
        BoardHealth health = checker.workflowHealth(
                workflow, "abc123", "abc123", server.getAddress().getPort());

        // then
        assertThat(health.kind()).isEqualTo(BoardHealthKind.SAME_WORKFLOW);
        assertThat(health.actualBoardId()).hasValue("full-board-id");
        assertThat(health.workerPid()).isEmpty();
    }

    @Test
    void workflowHealthParsesReportedWorkerPid() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md").toAbsolutePath().normalize();
        startLocalStatusServer(
                """
                {"workflowPath":"%s","boardId":"full-board-id","configuredBoardId":"abc123","pid":4242}
                """
                        .formatted(workflow));
        var checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());

        // when
        BoardHealth health = checker.workflowHealth(
                workflow, "abc123", "abc123", server.getAddress().getPort());

        // then
        assertThat(health.kind()).isEqualTo(BoardHealthKind.SAME_WORKFLOW);
        assertThat(health.workerPid()).hasValue(4242L);
    }

    @Test
    void workflowHealthReprobesOnceWhenABusyWorkerMissesTheLocalStatusTimeout() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md").toAbsolutePath().normalize();
        var requests = new AtomicInteger();
        var reprobeArrived = new CountDownLatch(1);
        try (var handlers = Executors.newVirtualThreadPerTaskExecutor()) {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            // The default executor is the single dispatcher thread, which the stalled first
            // request would block, so the re-probe could never be answered.
            server.setExecutor(handlers);
            server.createContext("/api/v1/local-status", exchange -> {
                if (requests.incrementAndGet() == 1) {
                    // Like a worker frozen by a GC or CPU pause: the connection is accepted, but
                    // no answer comes until the checker has given up on this request.
                    awaitReprobe(reprobeArrived);
                    exchange.close();
                    return;
                }
                reprobeArrived.countDown();
                respondWithJson(
                        exchange,
                        """
                        {"workflowPath":"%s","boardId":"full-board-id","configuredBoardId":"abc123"}
                        """
                                .formatted(workflow));
            });
            server.start();
            var checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());

            // when
            BoardHealth health = checker.workflowHealth(
                    workflow, "abc123", "abc123", server.getAddress().getPort());

            // then
            assertThat(health.kind())
                    .as("a worker that answers the re-probe must not be reported as a foreign process")
                    .isEqualTo(BoardHealthKind.SAME_WORKFLOW);
            assertThat(requests).hasValue(2);
        }
    }

    @Test
    void workflowHealthReportsPortUsedForAForeignHttpServerAfterOneReprobe() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md").toAbsolutePath().normalize();
        var requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        var checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());

        // when
        BoardHealth health = checker.workflowHealth(
                workflow, "abc123", "abc123", server.getAddress().getPort());

        // then
        assertThat(health.kind()).isEqualTo(BoardHealthKind.PORT_USED);
        assertThat(requests).hasValue(2);
    }

    @Test
    void portProbeUsesSameIpv4LoopbackHostAsHealthUrls() {
        // given
        var loopback = LocalHealthChecker.loopbackIpv4ForTests();

        // when
        String hostAddress = loopback.getHostAddress();

        // then
        assertThat(hostAddress).isEqualTo("127.0.0.1");
    }

    @Test
    void managedHealthPortRejectsOutOfRangeHttpPortOverride() {
        // given
        var checker = new LocalHealthChecker(Map.of("SYMPHONY_HTTP_PORT", "70000"), new WorkflowConfigEditor());

        // when
        Throwable thrown = catchThrowable(() -> checker.managedHealthPort(tempDir.resolve("WORKFLOW.md"), 18080, null));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("SYMPHONY_HTTP_PORT/QUARKUS_HTTP_PORT must be between 1 and 65535");
    }

    @Test
    void managedHealthPortResolvesWorkflowServerPortFromDotenv() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Path dotenv = tempDir.resolve(".env");
        Files.writeString(
                workflow,
                """
                ---
                server:
                  port: $SYMPHONY_TEST_PORT
                ---
                Prompt
                """);
        Files.writeString(dotenv, "SYMPHONY_TEST_PORT=19091\n");
        var checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());

        // when
        int port = checker.managedHealthPort(workflow, 18080, dotenv);

        // then
        assertThat(port).isEqualTo(19091);
    }

    @Test
    void waitForSameWorkflowReturnsImmediatelyWhenTheProcessAlreadyDied() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md").toAbsolutePath().normalize();
        var checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());
        int unboundPort = unboundLoopbackPort();
        long started = System.nanoTime();

        // when
        BoardHealth health = checker.waitForSameWorkflow(
                board(workflow, unboundPort), unboundPort, () -> false, Duration.ofSeconds(30));

        // then
        assertThat(health.kind()).isEqualTo(BoardHealthKind.STOPPED);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("a dead worker process can never become healthy, so the wait budget must not be burned")
                .isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void waitForSameWorkflowStopsPollingWhenTheProcessDiesMidWait() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md").toAbsolutePath().normalize();
        var checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());
        int unboundPort = unboundLoopbackPort();
        var aliveProbes = new AtomicInteger();
        long started = System.nanoTime();

        // when
        BoardHealth health = checker.waitForSameWorkflow(
                board(workflow, unboundPort),
                unboundPort,
                () -> aliveProbes.incrementAndGet() <= 2,
                Duration.ofSeconds(30));

        // then
        assertThat(health.kind()).isEqualTo(BoardHealthKind.STOPPED);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void waitForSameWorkflowOutlastsSlowStartupWhileTheProcessIsAlive() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md").toAbsolutePath().normalize();
        var requests = new AtomicInteger();
        String healthyJson =
                """
                {"workflowPath":"%s","boardId":"board-1"}
                """.formatted(workflow);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/local-status", exchange -> {
            // The port is bound from the start, but the worker only becomes healthy after a few
            // probes, like a JVM that is still starting up.
            if (requests.incrementAndGet() <= 3) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            respondWithJson(exchange, healthyJson);
        });
        server.start();
        int port = server.getAddress().getPort();
        var checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());

        // when
        BoardHealth health =
                checker.waitForSameWorkflow(board(workflow, port), port, () -> true, Duration.ofSeconds(30));

        // then
        assertThat(health.kind()).isEqualTo(BoardHealthKind.SAME_WORKFLOW);
        assertThat(requests.get()).isGreaterThan(3);
    }

    private static ConnectedBoard board(Path workflow, int port) {
        return ConnectedBoardBuilder.connectedBoard(workflow)
                .withBoardId("board-1")
                .withBoardKey("board-1")
                .withBoardName("Queue")
                .withBoardUrl("https://trello.com/b/SYNTH001/synthetic-board")
                .withEnvPath(null)
                .withWorkspaceRoot(workflow.getParent())
                .withServerPort(port)
                .build();
    }

    /// A port that was just bound and released, so nothing accepts connections on it.
    private static int unboundLoopbackPort() throws IOException {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private void startLocalStatusServer(String responseJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/local-status", exchange -> respondWithJson(exchange, responseJson));
        server.start();
    }

    private static void respondWithJson(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    /// Blocks the stalled first request until the re-probe arrives, so the checker can only see it
    /// time out. The bound keeps a broken checker from hanging the test.
    private static void awaitReprobe(CountDownLatch reprobeArrived) {
        try {
            if (!reprobeArrived.await(REPROBE_WAIT_BOUND.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("the checker never sent a re-probe");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
