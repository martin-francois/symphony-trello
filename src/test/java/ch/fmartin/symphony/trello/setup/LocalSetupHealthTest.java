package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LocalSetupHealthTest extends LocalSetupFixtureSupport {
    @ParameterizedTest
    @MethodSource("workerHealthScenarios")
    void checkReportsWorkerHealthState(WorkerHealthScenario scenario) throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW." + scenario.fileSuffix() + ".md");
        Path env = tempDir.resolve(".env." + scenario.fileSuffix());
        SetupRunResult setupResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                scenario.boardName(),
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // when
        try (AutoCloseable ignored = scenario.prepare(this, workflow, env)) {
            SetupRunResult result = runSetup("check", "--endpoint", endpoint());

            // then
            setupResult.assertSuccess();
            result.assertFailure(2)
                    .stderrEmpty()
                    .stdoutContains(scenario.expectedOutput(this, workflow, env))
                    .stdoutDoesNotContain(scenario.forbiddenOutput());
        }
    }

    private static Stream<WorkerHealthScenario> workerHealthScenarios() {
        return Stream.of(
                new WorkerHealthScenario(
                        "wrong-board",
                        "Wrong Board Queue",
                        (test, workflow, env) -> {
                            test.commands.stopHealthServer(workflow.toString());
                            test.commands.startHealthServer(workflow, "other-board");
                            return () -> {};
                        },
                        (test, workflow, env) -> new String[] {
                            "WARN  \"Wrong Board Queue\" local server: http://127.0.0.1:",
                            "(wrong Symphony workflow or board)",
                            "Expected workflow: " + workflow.toAbsolutePath().normalize(),
                            "Actual board: other-board",
                            "Suggested fix: symphony-trello setup-local repair-port --board \"Wrong Board Queue\""
                        },
                        "OK    \"Wrong Board Queue\" local server"),
                new WorkerHealthScenario(
                        "wrong-workflow",
                        "Wrong Workflow Queue",
                        (test, workflow, env) -> {
                            Path wrongWorkflow = test.tempDir.resolve("WORKFLOW.actual.md");
                            Files.writeString(
                                    wrongWorkflow,
                                    Files.readString(workflow, StandardCharsets.UTF_8),
                                    StandardCharsets.UTF_8);
                            test.commands.stopHealthServer(workflow.toString());
                            test.commands.startHealthServer(wrongWorkflow, "board-1");
                            return () -> {};
                        },
                        (test, workflow, env) -> new String[] {
                            "WARN  \"Wrong Workflow Queue\" local server: http://127.0.0.1:",
                            "(wrong Symphony workflow or board)",
                            "Actual workflow: "
                                    + test.tempDir
                                            .resolve("WORKFLOW.actual.md")
                                            .toAbsolutePath()
                                            .normalize(),
                            "Suggested fix: symphony-trello setup-local repair-port --board \"Wrong Workflow Queue\""
                        },
                        "OK    \"Wrong Workflow Queue\" local server"),
                new WorkerHealthScenario(
                        "port-used",
                        "Port Used Queue",
                        (test, workflow, env) -> {
                            test.commands.stopHealthServer(workflow.toString());
                            HttpServer otherServer = HttpServer.create(
                                    new InetSocketAddress(
                                            "127.0.0.1", LocalSetupTestFixture.FakeCommands.workflowPort(workflow)),
                                    0);
                            otherServer.createContext("/api/v1/local-status", exchange -> {
                                byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
                                exchange.sendResponseHeaders(404, bytes.length);
                                try (var body = exchange.getResponseBody()) {
                                    body.write(bytes);
                                }
                            });
                            otherServer.start();
                            return () -> otherServer.stop(0);
                        },
                        (test, workflow, env) -> new String[] {
                            "WARN  \"Port Used Queue\" configured port "
                                    + LocalSetupTestFixture.FakeCommands.workflowPort(workflow)
                                    + " is in use by another process",
                            "Suggested fix: symphony-trello setup-local repair-port --board \"Port Used Queue\""
                        },
                        "OK    \"Port Used Queue\" local server"),
                new WorkerHealthScenario(
                        "stopped",
                        "Stopped Queue",
                        (test, workflow, env) -> {
                            test.commands.stopHealthServer(workflow.toString());
                            return () -> {};
                        },
                        (test, workflow, env) -> new String[] {
                            "WARN  \"Stopped Queue\" local server is not running",
                            "Start: symphony-trello start --env " + env
                        },
                        "setup-local repair-port",
                        "OK    \"Stopped Queue\" local server"));
    }

    private record WorkerHealthScenario(
            String fileSuffix,
            String boardName,
            WorkerHealthPreparation preparation,
            WorkerHealthExpectation expectation,
            String... forbiddenOutput) {
        AutoCloseable prepare(LocalSetupHealthTest test, Path workflow, Path env) throws Exception {
            return preparation.prepare(test, workflow, env);
        }

        String[] expectedOutput(LocalSetupHealthTest test, Path workflow, Path env) {
            return expectation.fragments(test, workflow, env);
        }

        @Override
        public String toString() {
            return fileSuffix;
        }
    }

    private interface WorkerHealthPreparation {
        AutoCloseable prepare(LocalSetupHealthTest test, Path workflow, Path env) throws Exception;
    }

    private interface WorkerHealthExpectation {
        String[] fragments(LocalSetupHealthTest test, Path workflow, Path env);
    }

    @Test
    void setupPrefersRealHttpPortOverrideOverDotenvAlias() throws Exception {
        // given
        int envPort = availablePort();
        int dotenvPort = availablePort();
        commands.healthPortOverride = envPort;
        LocalSetup setupWithOverride = setupWithEnvironment(Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR",
                tempDir.resolve("config").toString(),
                "SYMPHONY_TRELLO_COMMAND",
                "symphony-trello",
                "QUARKUS_HTTP_PORT",
                String.valueOf(envPort)));
        Path workflow = tempDir.resolve("WORKFLOW.env-precedence.md");
        Path env = tempDir.resolve(".env.env-precedence");
        Files.writeString(
                env,
                """
                TRELLO_API_KEY=key
                TRELLO_API_TOKEN=token
                SYMPHONY_HTTP_PORT=%d
                """
                        .formatted(dotenvPort),
                StandardCharsets.UTF_8);

        // when
        SetupRunResult setupResult = runSetup(
                setupWithOverride,
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--board-name",
                "Env Precedence Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--server-port",
                "19080",
                "--no-github");
        SetupRunResult checkResult = runSetup(setupWithOverride, "check", "--endpoint", endpoint());

        // then
        setupResult.assertSuccess();
        checkResult
                .assertSuccess()
                .stdoutContains("\"Env Precedence Queue\" local server: http://127.0.0.1:", "(already running)")
                .stdoutDoesNotContain("configured port is used by another process");
    }

    @Test
    void checkReportsWorkflowBoardIdMismatchWithManifest() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.board-mismatch.md");
        Path env = tempDir.resolve(".env.board-mismatch");
        SetupRunResult setupResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Board Mismatch Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--no-github");
        Files.writeString(
                workflow,
                Files.readString(workflow, StandardCharsets.UTF_8)
                        .replace("board_id: \"abc123\"", "board_id: \"other-board\""),
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup("check", "--endpoint", endpoint());

        // then
        setupResult.assertSuccess();
        result.assertFailure(2)
                .stderrEmpty()
                .stdoutContains(
                        "Workflow tracker.board_id does not match the connected board",
                        "expected board-1 or abc123 but found other-board");
    }

    @Test
    void checkReportsWorkflowPortMismatchWithManifest() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.port-mismatch.md");
        Path env = tempDir.resolve(".env.port-mismatch");
        int port = availablePort();
        SetupRunResult setupResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Port Mismatch Queue",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--server-port",
                String.valueOf(port),
                "--no-github");
        Files.writeString(
                workflow,
                Files.readString(workflow, StandardCharsets.UTF_8).replace("port: " + port, "port: " + (port + 1)),
                StandardCharsets.UTF_8);

        // when
        SetupRunResult result = runSetup("check", "--endpoint", endpoint());

        // then
        setupResult.assertSuccess();
        result.assertFailure(2)
                .stderrEmpty()
                .stdoutContains(
                        "Workflow server.port does not match the connected board",
                        "expected " + port + " but found " + (port + 1));
    }

    @Test
    void setupRejectsExplicitServerPortReservedByConnectedBoard() {
        // given
        int reservedPort = availablePort();
        Path firstWorkflow = tempDir.resolve("WORKFLOW.first.md");
        Path secondWorkflow = tempDir.resolve("WORKFLOW.second.md");
        Path env = tempDir.resolve(".env");
        SetupRunResult firstResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "First Queue",
                "--workflow",
                firstWorkflow.toString(),
                "--env",
                env.toString(),
                "--server-port",
                String.valueOf(reservedPort),
                "--no-start",
                "--no-github");
        trello.createdLists().clear();

        // when
        SetupRunResult secondResult = runSetup(
                "--non-interactive",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board-name",
                "Second Queue",
                "--workflow",
                secondWorkflow.toString(),
                "--env",
                env.toString(),
                "--server-port",
                String.valueOf(reservedPort),
                "--no-start",
                "--no-github");

        // then
        firstResult.assertSuccess();
        secondResult
                .assertFailure(2)
                .stderrContains("setup_server_port_conflict", "already reserved by another connected workflow");
        assertThat(trello.createdLists()).isEmpty();
        assertThat(secondWorkflow).doesNotExist();
    }
}
