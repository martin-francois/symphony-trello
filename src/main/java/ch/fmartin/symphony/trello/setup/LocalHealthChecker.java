package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

final class LocalHealthChecker {
    static final List<String> HTTP_PORT_ENVIRONMENT_NAMES = List.of("SYMPHONY_HTTP_PORT", "QUARKUS_HTTP_PORT");
    static final String LOOPBACK_HOST = "127.0.0.1";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_MAP_TYPE = new TypeReference<>() {};
    private static final Duration LOCAL_STATUS_TIMEOUT = Duration.ofMillis(500);
    private static final Duration PORT_USED_RETRY_DELAY = Duration.ofMillis(150);
    private static final Duration SAME_WORKFLOW_POLL_DELAY = Duration.ofMillis(200);
    // Covers cold JVM startup on slow shared-CPU hosts; a healthy container worker was observed
    // binding after 9.4 seconds, just past the previous fixed 10-second window.
    private static final Duration STARTUP_ALIVE_WAIT = Duration.ofSeconds(60);
    private static final InetAddress LOOPBACK_IPV4 = loopbackIpv4();

    private final Map<String, String> environment;
    private final HttpClient httpClient;
    private final WorkflowConfigEditor workflowConfig;

    LocalHealthChecker(Map<String, String> environment, WorkflowConfigEditor workflowConfig) {
        this(
                environment,
                workflowConfig,
                HttpClient.newBuilder().connectTimeout(LOCAL_STATUS_TIMEOUT).build());
    }

    LocalHealthChecker(Map<String, String> environment, WorkflowConfigEditor workflowConfig, HttpClient httpClient) {
        this.environment = Map.copyOf(environment);
        this.workflowConfig = workflowConfig;
        this.httpClient = httpClient;
    }

    BoardHealth boardHealth(ConnectedBoard board) {
        int port = managedHealthPort(board.workflowPath(), board.serverPort(), board.envPath());
        return workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), port);
    }

    BoardHealth waitForSameWorkflow(ConnectedBoard board, int port, BooleanSupplier processAlive) {
        return waitForSameWorkflow(board, port, processAlive, STARTUP_ALIVE_WAIT);
    }

    /**
     * Waits for the freshly started worker to report the expected workflow. A cold JVM on a slow
     * host can need well over ten seconds to bind its HTTP port, so the budget is generous while
     * the process is still alive; a worker whose process has died can never become healthy, so
     * that case returns immediately instead of burning the remaining budget.
     *
     * <p>This polls instead of waiting for a worker-side readiness event. A readiness marker file
     * watched with {@code WatchService} was considered and deferred: the HTTP probe stays
     * mandatory because only the answer on the port proves the listener is the expected worker
     * for this workflow and board, and the event would save at most one poll interval on a wait
     * dominated by multi-second JVM startup. See
     * docs/adr/0053-sleep-based-waits-kept-as-polling-boundaries.md.
     */
    BoardHealth waitForSameWorkflow(ConnectedBoard board, int port, BooleanSupplier processAlive, Duration aliveWait) {
        long deadline = System.nanoTime() + aliveWait.toNanos();
        while (true) {
            BoardHealth last = workflowHealth(board.workflowPath(), board.boardId(), board.boardKey(), port);
            if (last.kind() == BoardHealthKind.SAME_WORKFLOW
                    || !processAlive.getAsBoolean()
                    || System.nanoTime() >= deadline
                    || !sleptWithoutInterrupt(SAME_WORKFLOW_POLL_DELAY)) {
                return last;
            }
        }
    }

    BoardHealth workflowHealth(Path expectedWorkflowPath, String expectedBoardId, String expectedBoardKey, int port) {
        BoardHealth health = probeWorkflowHealth(expectedWorkflowPath, expectedBoardId, expectedBoardKey, port);
        if (health.kind() != BoardHealthKind.PORT_USED) {
            return health;
        }
        // A briefly busy worker can miss the short local-status timeout; one delayed re-probe keeps
        // healthy managed workers from transiently showing up as PORT_USED. A longer timeout would
        // slow every probe of a genuinely foreign port instead. This covers occasional GC or CPU
        // pauses without weakening the normal port-conflict signal.
        if (!sleptWithoutInterrupt(PORT_USED_RETRY_DELAY)) {
            return health;
        }
        return probeWorkflowHealth(expectedWorkflowPath, expectedBoardId, expectedBoardKey, port);
    }

    /** Returns false when interrupted, so callers report the freshest health instead of probing on. */
    private static boolean sleptWithoutInterrupt(Duration delay) {
        try {
            Thread.sleep(delay);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private BoardHealth probeWorkflowHealth(
            Path expectedWorkflowPath, String expectedBoardId, String expectedBoardKey, int port) {
        if (port <= 0 || !portAcceptsConnections(port)) {
            return new BoardHealth(BoardHealthKind.STOPPED, port, Optional.empty(), Optional.empty());
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(localStatusUri(port))
                    .timeout(LOCAL_STATUS_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != Status.OK.getStatusCode()) {
                return new BoardHealth(BoardHealthKind.PORT_USED, port, Optional.empty(), Optional.empty());
            }
            Map<String, Object> status = JSON.readValue(response.body(), JSON_MAP_TYPE);
            Optional<String> actualWorkflowPath = optionalString(status.get("workflowPath"));
            Optional<String> actualBoardId = optionalString(status.get("boardId"));
            Optional<String> actualConfiguredBoardId = optionalString(status.get("configuredBoardId"));
            Optional<Long> workerPid = optionalPid(status.get("pid"));
            if (actualWorkflowPath
                            .filter(path -> PathsEqual.samePath(Path.of(path), expectedWorkflowPath))
                            .isPresent()
                    && boardIdMatches(actualBoardId, actualConfiguredBoardId, expectedBoardId, expectedBoardKey)) {
                return new BoardHealth(
                        BoardHealthKind.SAME_WORKFLOW, port, actualWorkflowPath, actualBoardId, workerPid);
            }
            return new BoardHealth(BoardHealthKind.WRONG_WORKFLOW, port, actualWorkflowPath, actualBoardId, workerPid);
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new BoardHealth(BoardHealthKind.PORT_USED, port, Optional.empty(), Optional.empty());
        }
    }

    int managedHealthPort(Path workflowPath) {
        return managedHealthPort(workflowPath, TrelloBoardSetup.DEFAULT_SERVER_PORT, null);
    }

    int managedHealthPort(Path workflowPath, int fallbackPort) {
        return managedHealthPort(workflowPath, fallbackPort, null);
    }

    int managedHealthPort(Path workflowPath, int fallbackPort, Path envPath) {
        return externalHttpPortOverride(envPath)
                .map(LocalHealthChecker::requireStableExternalPort)
                .orElseGet(() -> stableWorkflowPort(workflowPath, fallbackPort, envPath));
    }

    private int stableWorkflowPort(Path workflowPath, int fallbackPort, Path envPath) {
        int port = workflowConfig
                .serverPort(workflowPath, WorkflowEnvironmentResolver.resolver(environment, envPath))
                .orElse(fallbackPort);
        if (port == 0) {
            throw new TrelloBoardSetupException(
                    "setup_managed_port_required",
                    "Managed local setup needs a stable HTTP port. Use a positive server.port for setup-local managed workers.");
        }
        return port;
    }

    Optional<String> externalHttpPortOverrideSource(Path envPath) {
        return WorkflowEnvironmentResolver.externalHttpPortOverrideSource(environment, envPath);
    }

    static boolean portAcceptsConnections(int port) {
        if (port <= 0) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOOPBACK_IPV4, port), 150);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static InetAddress loopbackIpv4ForTests() {
        return LOOPBACK_IPV4;
    }

    private static InetAddress loopbackIpv4() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static URI localStatusUri(int port) {
        return URI.create(localServerUrl(port) + "/api/v1/local-status");
    }

    static String localStateUrl(int port) {
        return localServerUrl(port) + "/api/v1/state";
    }

    static String localServerUrl(int port) {
        return "http://" + LOOPBACK_HOST + ":" + port;
    }

    private Optional<Integer> externalHttpPortOverride(Path envPath) {
        Path dotenv = envPath == null ? Path.of(".env") : envPath;
        return LocalEnvironment.firstPresent(dotenv, environment, HTTP_PORT_ENVIRONMENT_NAMES.toArray(String[]::new))
                .filter(value -> !blank(value))
                .map(value -> {
                    try {
                        int port = Integer.parseInt(value.trim());
                        if (!LocalPort.isValid(port)) {
                            throw new TrelloBoardSetupException(
                                    "setup_invalid_http_port_override",
                                    "SYMPHONY_HTTP_PORT/QUARKUS_HTTP_PORT must be between 1 and 65535.");
                        }
                        return port;
                    } catch (NumberFormatException e) {
                        throw new TrelloBoardSetupException(
                                "setup_invalid_http_port_override",
                                "SYMPHONY_HTTP_PORT/QUARKUS_HTTP_PORT must be an integer.",
                                e);
                    }
                });
    }

    private static boolean boardIdMatches(
            Optional<String> actualBoardId,
            Optional<String> actualConfiguredBoardId,
            String expectedBoardId,
            String expectedBoardKey) {
        return actualBoardId
                        .filter(actual -> boardIdMatches(actual, expectedBoardId, expectedBoardKey))
                        .isPresent()
                || actualConfiguredBoardId
                        .filter(actual -> boardIdMatches(actual, expectedBoardId, expectedBoardKey))
                        .isPresent();
    }

    private static boolean boardIdMatches(String actualBoardId, String expectedBoardId, String expectedBoardKey) {
        return actualBoardId.equals(expectedBoardId) || actualBoardId.equals(expectedBoardKey);
    }

    private static int requireStableExternalPort(int port) {
        if (port == 0) {
            throw new TrelloBoardSetupException(
                    "setup_managed_port_required",
                    "Managed local setup needs a stable HTTP port. Remove SYMPHONY_HTTP_PORT/QUARKUS_HTTP_PORT=0 or set a positive workflow server.port.");
        }
        return port;
    }

    private static Optional<Long> optionalPid(Object value) {
        if (value instanceof Number number) {
            long pid = number.longValue();
            return pid > 0 ? Optional.of(pid) : Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<String> optionalString(Object value) {
        String text = value == null ? null : String.valueOf(value);
        return blank(text) ? Optional.empty() : Optional.of(text);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
