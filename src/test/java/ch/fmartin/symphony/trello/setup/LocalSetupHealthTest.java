package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.testsupport.SetupRunResult;
import ch.fmartin.symphony.trello.testsupport.TestEnv;
import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class LocalSetupHealthTest extends LocalSetupFixtureSupport {
    @MethodSource("workerHealthScenarios")
    @ParameterizedTest
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
                    .stdoutDoesNotContain(scenario.forbiddenOutputFragments());
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
                            HttpServer otherServer = LocalSetupTestFixture.FakeCommands.createHealthServer(
                                    test.commands.workflowPort(workflow));
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
                                    + test.commands.workflowPort(workflow)
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
            List<String> forbiddenOutput) {
        private WorkerHealthScenario(
                String fileSuffix,
                String boardName,
                WorkerHealthPreparation preparation,
                WorkerHealthExpectation expectation,
                String... forbiddenOutput) {
            this(fileSuffix, boardName, preparation, expectation, List.of(forbiddenOutput));
        }

        AutoCloseable prepare(LocalSetupHealthTest test, Path workflow, Path env) throws Exception {
            return preparation.prepare(test, workflow, env);
        }

        String[] expectedOutput(LocalSetupHealthTest test, Path workflow, Path env) {
            return expectation.fragments(test, workflow, env);
        }

        String[] forbiddenOutputFragments() {
            return forbiddenOutput.toArray(String[]::new);
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
    void checkReportsInvalidConnectedBoardLocalPathsWithoutStartHint() throws Exception {
        // given
        Path configDir = fixture.configDir();
        Path workflow = configDir.resolve("WORKFLOW.invalid-local-paths.md");
        Path envDirectory = tempDir.resolve("env-dir");
        Path workspaceFile = tempDir.resolve("workspace-file");
        Files.createDirectories(configDir);
        Files.createDirectories(envDirectory);
        Files.createDirectories(fixture.workspaceRoot());
        Files.writeString(workspaceFile, "not a directory", StandardCharsets.UTF_8);
        writeWorkflow(workflow, "synthetic-board", 20451);
        fixture.givenManifest(
                """
                {"boards":[
                  {"boardId":"synthetic-board","boardKey":"synthetic","boardName":"Workspace File","boardUrl":"https://trello.example/workspace-file","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":20451,"githubEnabled":false,"dangerFullAccess":false,"additionalWritableRoots":[]},
                  {"boardId":"synthetic-board","boardKey":"synthetic","boardName":"Env Directory","boardUrl":"https://trello.example/env-directory","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":20453,"githubEnabled":false,"dangerFullAccess":false,"additionalWritableRoots":["relative/path"]}
                ]}
                """
                        .formatted(
                                json(workflow),
                                json(configDir.resolve(".env.workspace-file")),
                                json(workspaceFile),
                                json(workflow),
                                json(envDirectory),
                                json(fixture.workspaceRoot())));

        // when
        SetupRunResult result = runSetup("check", "--endpoint", endpoint());

        // then
        result.assertFailure(2)
                .stderrEmpty()
                .stdoutContains(
                        "Workspace root for \"Workspace File\" must be a directory: " + workspaceFile,
                        "Trello credential path for \"Env Directory\" must be a dotenv file, but it is a directory: "
                                + envDirectory,
                        "Additional writable root for \"Env Directory\" must be an absolute path: relative/path")
                .stdoutDoesNotContain("Start: symphony-trello start");
    }

    @Test
    void checkEscapesControlCharacterBoardNamesInManifestWarnings() throws Exception {
        // given
        // The manifest is hand-editable, so persisted board names may contain quotes and
        // control characters; a warning that embeds such a name must stay one logical line
        // with escaped, unambiguous quoting instead of splitting the warning across lines.
        Path configDir = fixture.configDir();
        Path workflow = configDir.resolve("WORKFLOW.dirty-name-warning.md");
        Files.createDirectories(fixture.workspaceRoot());
        writeWorkflow(workflow, "synthetic-board", 20455);
        fixture.givenManifest(
                """
                {"boards":[{"boardId":"synthetic-board","boardKey":"synthetic","boardName":"Sneaky \\"Q\\"\\nBoard","boardUrl":"https://trello.example/synthetic","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":20455,"githubEnabled":"false","dangerFullAccess":false,"additionalWritableRoots":[]}]}
                """
                        .formatted(json(workflow), json(configDir.resolve(".env")), json(fixture.workspaceRoot())));

        // when
        SetupRunResult result = runSetup("check", "--endpoint", endpoint());

        // then
        result.assertFailure(2)
                .stderrEmpty()
                .stdoutContains(
                        "Connected-board manifest entry \"Sneaky \\\"Q\\\"\\nBoard\" field githubEnabled must be true or false.")
                .stdoutDoesNotContain("Sneaky \"Q\"\nBoard", "\"Q\"\nBoard");
    }

    @Test
    void checkReportsInvalidConnectedBoardManifestValueShapes() throws Exception {
        // given
        Path configDir = fixture.configDir();
        Path workflow = configDir.resolve("WORKFLOW.invalid-shapes.md");
        Files.createDirectories(fixture.workspaceRoot());
        writeWorkflow(workflow, "synthetic-board", 20454);
        fixture.givenManifest(
                """
                {"boards":[{"boardId":"synthetic-board","boardKey":"synthetic","boardName":"Synthetic Board","boardUrl":"https://trello.example/synthetic","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":"20454","githubEnabled":"false","dangerFullAccess":"false","additionalWritableRoots":"relative/path"}]}
                """
                        .formatted(json(workflow), json(configDir.resolve(".env")), json(fixture.workspaceRoot())));

        // when
        SetupRunResult result = runSetup("check", "--endpoint", endpoint());

        // then
        result.assertFailure(2)
                .stderrEmpty()
                .stdoutContains(
                        "Connected-board manifest entry \"Synthetic Board\" field githubEnabled must be true or false.",
                        "Connected-board manifest entry \"Synthetic Board\" field dangerFullAccess must be true or false.",
                        "Connected-board manifest entry \"Synthetic Board\" field serverPort must be a JSON number from 1 to 65535.",
                        "Connected-board manifest entry \"Synthetic Board\" field additionalWritableRoots must be an array of path strings.",
                        "Connected-board manifest contains invalid values and could not be loaded for checks.")
                .stdoutDoesNotContain(
                        "Cannot deserialize", "Troubleshooting report written", "Start: symphony-trello start");
    }

    @Test
    void checkReportsNullConnectedBoardManifestShapesWithoutRawException() throws Exception {
        // given
        Path configDir = fixture.configDir();
        Files.createDirectories(configDir);
        fixture.givenManifest(
                """
                {"boards":[null,{"boardId":"synthetic-board","boardKey":"synthetic","boardName":"Synthetic Board","boardUrl":"https://trello.example/synthetic","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":20454,"additionalWritableRoots":[null]}]}
                """
                        .formatted(
                                json(configDir.resolve("WORKFLOW.null-shapes.md")),
                                json(configDir.resolve(".env")),
                                json(fixture.workspaceRoot())));

        // when
        SetupRunResult result = runSetup("check", "--endpoint", endpoint());

        // then
        result.assertFailure(2)
                .stderrEmpty()
                .stdoutContains(
                        "Connected-board manifest entry 1 must be an object.",
                        "Connected-board manifest entry \"Synthetic Board\" field additionalWritableRoots must contain only non-blank path strings.",
                        "Connected-board manifest contains invalid values and could not be loaded for checks.")
                .stdoutDoesNotContain("NullPointerException", "Cannot deserialize", "Troubleshooting report written");
    }

    @Test
    void checkReportsMissingConnectedBoardManifestPathsWithoutRawException() throws Exception {
        // given
        Path configDir = fixture.configDir();
        Files.createDirectories(configDir);
        fixture.givenManifest(
                """
                {"boards":[{"boardId":"synthetic-board","boardKey":"synthetic","boardName":"Synthetic Board","boardUrl":"https://trello.example/synthetic","workspaceRoot":"%s","serverPort":20456,"githubEnabled":false,"dangerFullAccess":false,"additionalWritableRoots":[]}]}
                """
                        .formatted(json(fixture.workspaceRoot())));

        // when
        SetupRunResult result = runSetup("check", "--endpoint", endpoint());

        // then
        result.assertFailure(2)
                .stderrEmpty()
                .stdoutContains(
                        "Connected-board manifest entry \"Synthetic Board\" field workflowPath must be a non-blank string.",
                        "Connected-board manifest entry \"Synthetic Board\" field envPath must be a non-blank string.",
                        "Workflow path for \"Synthetic Board\" is missing from connected-boards.json.",
                        "Trello credential path for \"Synthetic Board\" is missing from connected-boards.json.")
                .stdoutDoesNotContain("NullPointerException", "Troubleshooting report written");
    }

    @Test
    void checkBoardSelectorToleratesMalformedManifestEntries() throws Exception {
        // given
        Path configDir = fixture.configDir();
        Path workflow = configDir.resolve("WORKFLOW.valid.md");
        Path env = configDir.resolve(".env.valid");
        Files.createDirectories(configDir);
        Files.createDirectories(fixture.workspaceRoot());
        Files.writeString(env, TestEnv.trelloCredentials(), StandardCharsets.UTF_8);
        writeWorkflow(workflow, "valid-board-id", 20457);
        commands.startHealthServer(workflow, "other-board");
        fixture.givenManifest(
                """
                {"boards":[
                  {"boardId":"missing-name-id","boardKey":"missing-name-key","boardUrl":"https://trello.example/missing-name","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":20458,"githubEnabled":false,"dangerFullAccess":false,"additionalWritableRoots":[]},
                  {"boardId":"valid-board-id","boardKey":"valid-key","boardName":"Valid Board","boardUrl":"https://trello.example/valid","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":20457,"githubEnabled":false,"dangerFullAccess":false,"additionalWritableRoots":[]}
                ]}
                """
                        .formatted(
                                json(configDir.resolve("WORKFLOW.missing-name.md")),
                                json(configDir.resolve(".env.missing-name")),
                                json(fixture.workspaceRoot()),
                                json(workflow),
                                json(env),
                                json(fixture.workspaceRoot())));

        // when
        SetupRunResult result = runSetup("check", "--board", "valid-key", "--endpoint", endpoint());

        // then
        result.assertFailure(2)
                .stderrEmpty()
                .stdoutContains(
                        "Connected-board manifest entry 1 field boardName must be a non-blank string.",
                        "WARN  \"Valid Board\" local server:",
                        "Suggested fix: symphony-trello setup-local repair-port --board \"Valid Board\"")
                .stdoutDoesNotContain("NullPointerException", "Troubleshooting report written");
    }

    @Test
    void checkBoardSelectorReportsManifestWarningsBeforeMissingSelectorError() throws Exception {
        // given
        Path configDir = fixture.configDir();
        Path workflow = configDir.resolve("WORKFLOW.valid.md");
        Path env = configDir.resolve(".env.valid");
        Files.createDirectories(configDir);
        Files.createDirectories(fixture.workspaceRoot());
        Files.writeString(env, TestEnv.trelloCredentials(), StandardCharsets.UTF_8);
        writeWorkflow(workflow, "valid-board-id", 20457);
        fixture.givenManifest(
                """
                {"boards":[
                  {"boardId":"missing-name-id","boardKey":"missing-name-key","boardUrl":"https://trello.example/missing-name","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":20458,"githubEnabled":false,"dangerFullAccess":false,"additionalWritableRoots":[]},
                  {"boardId":"valid-board-id","boardKey":"valid-key","boardName":"Valid Board","boardUrl":"https://trello.example/valid","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":20457,"githubEnabled":false,"dangerFullAccess":false,"additionalWritableRoots":[]}
                ]}
                """
                        .formatted(
                                json(configDir.resolve("WORKFLOW.missing-name.md")),
                                json(configDir.resolve(".env.missing-name")),
                                json(fixture.workspaceRoot()),
                                json(workflow),
                                json(env),
                                json(fixture.workspaceRoot())));

        // when
        SetupRunResult result = runSetup("check", "--board", "missing-private-selector", "--endpoint", endpoint());

        // then
        result.assertFailure(2)
                .stdoutContains("Connected-board manifest entry 1 field boardName must be a non-blank string.")
                .stderrContains(
                        "setup_failed code=setup_board_selection_required",
                        "No connected Trello board matches \"missing-private-selector\".")
                .stderrDoesNotContain("Troubleshooting report written");
    }

    @Test
    void checkReportsOutOfRangeConnectedBoardManifestPort() throws Exception {
        // given
        Path configDir = fixture.configDir();
        Path workflow = configDir.resolve("WORKFLOW.invalid-port.md");
        Files.createDirectories(fixture.workspaceRoot());
        writeWorkflow(workflow, "synthetic-board", 20455);
        fixture.givenManifest(
                """
                {"boards":[{"boardId":"synthetic-board","boardKey":"synthetic","boardName":"Synthetic Board","boardUrl":"https://trello.example/synthetic","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":65536,"githubEnabled":false,"dangerFullAccess":false,"additionalWritableRoots":[]}]}
                """
                        .formatted(json(workflow), json(configDir.resolve(".env")), json(fixture.workspaceRoot())));

        // when
        SetupRunResult result = runSetup("check", "--endpoint", endpoint());

        // then
        result.assertFailure(2)
                .stderrEmpty()
                .stdoutContains(
                        "Connected-board manifest entry \"Synthetic Board\" field serverPort must be between 1 and 65535.")
                .stdoutDoesNotContain("expected 65536", "http://127.0.0.1:65536", "Start: symphony-trello start");
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
