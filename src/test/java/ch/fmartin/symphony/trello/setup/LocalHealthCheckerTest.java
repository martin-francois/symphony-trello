package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
