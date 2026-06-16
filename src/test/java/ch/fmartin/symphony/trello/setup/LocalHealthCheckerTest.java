package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class LocalHealthCheckerTest {
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
        LocalHealthChecker checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());

        // when
        BoardHealth health = checker.workflowHealth(
                workflow, "abc123", "abc123", server.getAddress().getPort());

        // then
        assertThat(health.kind()).isEqualTo(BoardHealthKind.SAME_WORKFLOW);
        assertThat(health.actualBoardId()).contains("full-board-id");
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
        LocalHealthChecker checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());

        // when
        BoardHealth health = checker.workflowHealth(
                workflow, "abc123", "abc123", server.getAddress().getPort());

        // then
        assertThat(health.kind()).isEqualTo(BoardHealthKind.SAME_WORKFLOW);
        assertThat(health.workerPid()).contains(4242L);
    }

    @Test
    void workflowHealthRetriesTransientLocalStatusFailureBeforeReportingPortUsed() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md").toAbsolutePath().normalize();
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/local-status", exchange -> {
            if (requests.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            byte[] body =
                    """
                    {"workflowPath":"%s","boardId":"full-board-id","configuredBoardId":"abc123"}
                    """
                            .formatted(workflow)
                            .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        LocalHealthChecker checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());

        // when
        BoardHealth health = checker.workflowHealth(
                workflow, "abc123", "abc123", server.getAddress().getPort());

        // then
        assertThat(health.kind()).isEqualTo(BoardHealthKind.SAME_WORKFLOW);
        assertThat(requests.get()).isEqualTo(2);
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
        LocalHealthChecker checker =
                new LocalHealthChecker(Map.of("SYMPHONY_HTTP_PORT", "70000"), new WorkflowConfigEditor());

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
                """,
                StandardCharsets.UTF_8);
        Files.writeString(dotenv, "SYMPHONY_TEST_PORT=19091\n", StandardCharsets.UTF_8);
        LocalHealthChecker checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());

        // when
        int port = checker.managedHealthPort(workflow, 18080, dotenv);

        // then
        assertThat(port).isEqualTo(19091);
    }

    @Test
    void waitForSameWorkflowReturnsImmediatelyWhenTheProcessAlreadyDied() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md").toAbsolutePath().normalize();
        LocalHealthChecker checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());
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
        LocalHealthChecker checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());
        int unboundPort = unboundLoopbackPort();
        AtomicInteger aliveProbes = new AtomicInteger();
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
        AtomicInteger requests = new AtomicInteger();
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
            byte[] body = healthyJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        LocalHealthChecker checker = new LocalHealthChecker(Map.of(), new WorkflowConfigEditor());

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

    /** A port that was just bound and released, so nothing accepts connections on it. */
    private static int unboundLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private void startLocalStatusServer(String responseJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/local-status", exchange -> {
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }
}
