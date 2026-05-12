package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalHealthCheckerTest {
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
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/local-status", exchange -> {
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
        assertThat(health.actualBoardId()).contains("full-board-id");
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
}
