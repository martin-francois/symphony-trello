package ch.fmartin.symphony.trello;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PackagedAppSmokeIT {
    private static final Path QUARKUS_RUNNER = Path.of("target/quarkus-app/quarkus-run.jar");
    private static final String BOARD_ID = "smoke-board-id";
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration STARTUP_POLL_INTERVAL = Duration.ofMillis(250);

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @TempDir
    Path tempDir;

    @Test
    void packagedRunnerServesLocalStatusForMinimalWorkflow() throws Exception {
        // given
        assertThat(QUARKUS_RUNNER)
                .as("Run through Maven verify so the Quarkus runner has been packaged.")
                .isRegularFile();
        int trelloPort = freePort();
        int appPort = freePortExcept(trelloPort);
        Path workflow = tempDir.resolve("WORKFLOW.smoke.md");
        Path workspaceRoot = tempDir.resolve("workspaces");
        Path stdout = tempDir.resolve("symphony.log");
        Path stderr = tempDir.resolve("symphony.err");
        Files.writeString(workflow, workflow(trelloPort, appPort, workspaceRoot));

        FakeTrelloEndpoint trello = FakeTrelloEndpoint.start(trelloPort);
        try (trello) {
            Process process = startPackagedRunner(workflow, stdout, stderr);
            try {

                // when
                JsonNode localStatus = waitForLocalStatus(appPort, stdout, stderr);
                assertDoesNotExitAfterHealthyStartup(process, Duration.ofSeconds(2));

                // then
                assertThat(localStatus.get("boardId").asText()).isEqualTo(BOARD_ID);
                assertThat(localStatus.get("configuredBoardId").asText()).isEqualTo(BOARD_ID);
                assertThat(localStatus.get("workflowPath").asText())
                        .isEqualTo(workflow.toAbsolutePath().normalize().toString());
            } finally {
                stop(process);
            }
        }
    }

    private Process startPackagedRunner(Path workflow, Path stdout, Path stderr) throws IOException {
        Path java = Path.of(System.getProperty("java.home"), "bin", executable("java"));
        ProcessBuilder builder = new ProcessBuilder(
                        java.toString(), "-jar", QUARKUS_RUNNER.toString(), workflow.toString())
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile());
        builder.environment().remove("SYMPHONY_HTTP_PORT");
        builder.environment().remove("QUARKUS_HTTP_PORT");
        return builder.start();
    }

    private JsonNode waitForLocalStatus(int port, Path stdout, Path stderr) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + "/api/v1/local-status");
        HttpRequest request =
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build();
        Instant deadline = now().plus(STARTUP_TIMEOUT);
        Throwable lastFailure = null;
        while (now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return json.readTree(response.body());
                }
                lastFailure = new AssertionError("HTTP " + response.statusCode() + ": " + response.body());
            } catch (IOException e) {
                lastFailure = e;
            }
            pollDelayForBoundedStartupWait();
        }
        throw new AssertionError(
                "Packaged app did not serve /api/v1/local-status. stdout=" + read(stdout) + "\nstderr=" + read(stderr),
                lastFailure);
    }

    private static void pollDelayForBoundedStartupWait() throws InterruptedException {
        Thread.sleep(STARTUP_POLL_INTERVAL);
    }

    private static void assertDoesNotExitAfterHealthyStartup(Process process, Duration duration)
            throws InterruptedException {
        try {
            ProcessHandle exited = process.toHandle().onExit().get(duration.toMillis(), TimeUnit.MILLISECONDS);
            fail("Packaged worker exited within %s after first healthy startup response, pid=%s"
                    .formatted(duration, exited.pid()));
        } catch (TimeoutException expected) {
            assertThat(process.isAlive())
                    .as("packaged worker should stay alive after first healthy startup response")
                    .isTrue();
        } catch (ExecutionException e) {
            throw new AssertionError("Could not observe packaged worker process lifetime", e);
        }
    }

    private static String workflow(int trelloPort, int appPort, Path workspaceRoot) {
        return """
                ---
                tracker:
                  kind: trello
                  endpoint: http://127.0.0.1:%d/1
                  api_key: test-key
                  api_token: test-token
                  board_id: %s
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Done"
                polling:
                  interval_ms: 600000
                workspace:
                  root: %s
                server:
                  port: %d
                agent:
                  max_concurrent_agents: 1
                codex:
                  command: codex app-server
                ---
                Smoke test workflow.
                """
                .formatted(trelloPort, BOARD_ID, workspaceRoot, appPort);
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static int freePortExcept(int reservedPort) throws IOException {
        int port = freePort();
        while (port == reservedPort) {
            port = freePort();
        }
        return port;
    }

    private static void stop(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(Duration.ofSeconds(5))) {
            process.destroyForcibly();
            process.waitFor(Duration.ofSeconds(5));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return "<unreadable: " + e.getMessage() + ">";
        }
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? name + ".exe" : name;
    }

    private static Instant now() {
        return Instant.ofEpochMilli(System.currentTimeMillis());
    }

    private static final class FakeTrelloEndpoint implements AutoCloseable {
        private final HttpServer server;

        private FakeTrelloEndpoint(HttpServer server) {
            this.server = server;
        }

        static FakeTrelloEndpoint start(int port) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            server.createContext("/1/boards/" + BOARD_ID, exchange -> {
                byte[] body = json(Map.of("id", BOARD_ID, "name", "Smoke Board", "closed", false));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream response = exchange.getResponseBody()) {
                    response.write(body);
                }
            });
            server.start();
            return new FakeTrelloEndpoint(server);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static byte[] json(Map<String, Object> value) throws IOException {
            return new ObjectMapper().writeValueAsBytes(value);
        }
    }
}
