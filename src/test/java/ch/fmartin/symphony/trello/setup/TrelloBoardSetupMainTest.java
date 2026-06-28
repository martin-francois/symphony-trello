package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.CliExitCodes.SETUP_FAILURE;
import static ch.fmartin.symphony.trello.TestHttpExchange.query;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.boardJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.createdListJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.listsJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.respond;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.trelloList;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.workspaceJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.workspacesJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.testsupport.CliRunResult;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import ch.fmartin.symphony.trello.testsupport.SetupCommandBuilder;
import ch.fmartin.symphony.trello.testsupport.TestEnv;
import ch.fmartin.symphony.trello.testsupport.TestWorkflows;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

final class TrelloBoardSetupMainTest {
    private static final String CONFIG_DIR_PROPERTY = "symphony.trello.config.dir";
    private static final String SHELL_PROPERTY = "symphony.trello.shell";
    private static final String CLI_COMMAND_PROPERTY = "symphony.trello.command";

    private FakeTrelloServer trello;
    private final List<String> createdLists = new ArrayList<>();
    private final AtomicReference<String> createdBoardName = new AtomicReference<>();
    private final AtomicReference<String> boardListsResponse = new AtomicReference<>(listsJson(
            trelloList("list-ready", "Queue for Codex", 1),
            trelloList("list-doing", "Doing", 2),
            trelloList("list-review", "Review", 3),
            trelloList("list-blocked", "Blocked", 4),
            trelloList("list-done", "Released", 5)));
    private final AtomicInteger boardInfoLookups = new AtomicInteger();
    private final AtomicInteger workspaceLookups = new AtomicInteger();

    private final AtomicReference<String> workspaceAuthorization = new AtomicReference<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        trello = new FakeTrelloServer();
        trello.on("/1/members/me/organizations", exchange -> {
            workspaceLookups.incrementAndGet();
            workspaceAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(
                    exchange,
                    workspacesJson(workspaceJson("workspace-1", "engineering", "Engineering", "engineering")));
        });
        trello.on("/1/boards/", exchange -> {
            Map<String, String> query = query(exchange);
            createdBoardName.set(query.get("name"));
            respond(exchange, boardJson("board-1", query.get("name"), "abc123", "https://trello.com/b/abc123/board"));
        });
        trello.on("/1/lists", exchange -> {
            Map<String, String> query = query(exchange);
            createdLists.add(query.get("name"));
            respond(exchange, createdListJson("list-" + createdLists.size()));
        });
        trello.on("/1/boards/input", exchange -> {
            boardInfoLookups.incrementAndGet();
            respond(
                    exchange,
                    boardJson("board-1", "Existing Board", "SYNTH001", "https://trello.com/b/SYNTH001/board", false));
        });
        trello.on("/1/boards/board-1/lists", exchange -> respond(exchange, boardListsResponse.get()));
        trello.startEmpty();
    }

    @AfterEach
    void stopServer() {
        trello.stop();
    }

    @Test
    void importBoardDisplaysTrelloProvidedDirtyListNamesEscaped() throws Exception {
        // given
        // Trello allows control characters in list names; the CLI rejects them in its own
        // arguments, so the dirty name arrives from the Trello API and must render escaped on
        // one physical line.
        boardListsResponse.set(
                """
                [
                  {"id":"list-1","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-2","name":"Sneaky\\nList\\t\\"Q\\"","closed":false,"pos":2},
                  {"id":"list-3","name":"Released","closed":false,"pos":3}
                ]
        """);
        Path workflow = tempDir.resolve("dirty-lists.WORKFLOW.md");
        Path env = tempDir.resolve(".env.dirty-lists");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Ready for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertSuccess();
        String openListsLine = result.stdout()
                .lines()
                .filter(line -> line.startsWith("Open lists: "))
                .findAny()
                .orElseThrow();
        assertThat(openListsLine)
                .as("one logical message must stay on one physical line with unambiguous quoting")
                .isEqualTo("Open lists: \"Ready for Codex\", \"Sneaky\\nList\\t\\\"Q\\\"\", \"Released\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"input?utm=test", "input#fragment", "input/?utm=test"})
    void importBoardStripsAccidentalQueryOrFragmentFromBareBoardSelectors(String selector) {
        // given
        Path workflow = tempDir.resolve("decorated-selector.WORKFLOW.md");
        Path env = tempDir.resolve(".env.decorated-selector");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                selector,
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertSuccess().stdoutContains("Imported Trello board: \"Existing Board\"");
        assertThat(boardInfoLookups).hasValue(1);
        assertThat(workflow).exists();
    }

    @Test
    void importBoardRejectsNameLikeBoardSelectorsWithoutContactingTrello() {
        // given
        // A selector that merely contains '?' is not URI-parseable, so it is not a decorated
        // short link; import-board has no board-name matching and must fail locally instead of
        // sending the bad selector to Trello.
        Path workflow = tempDir.resolve("name-like-selector.WORKFLOW.md");
        Path env = tempDir.resolve(".env.name-like-selector");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "What? Board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("setup_failed code=setup_invalid_arguments", "Invalid --board value.")
                .stderrDoesNotContain("trello_invalid_request")
                .stdoutDoesNotContain("Imported Trello board");
        assertThat(boardInfoLookups).hasValue(0);
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
    }

    @Test
    void printsHelpWithoutRequiringCredentials() {
        // given

        // when
        CliRunResult result = runCli("--help");

        // then
        result.assertSuccess().stdoutContains("new-board", "import-board").stderrEmpty();
    }

    @MethodSource("commandsThatDoNotWriteWorkflows")
    @ParameterizedTest(name = "{0}")
    void doesNotResolveCodexModelDefaultsForCommandsThatDoNotWriteWorkflows(String name, String[] args) {
        // given
        AtomicBoolean resolved = new AtomicBoolean();

        // when
        CliRunResult result = runCli(
                () -> {
                    resolved.set(true);
                    return TrelloBoardSetup.CodexModelDefaults.fallback();
                },
                args);

        // then
        result.assertSuccess();
        assertThat(resolved).as(name).isFalse();
    }

    @MethodSource("mainProcessExitCases")
    @ParameterizedTest(name = "{0}")
    void exitsWithMainProcessStatus(MainProcessCase testCase) throws Exception {
        // given
        String[] arguments = testCase.argumentsArray();

        // when
        MainProcessResult result = runMainProcess(arguments);

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(testCase.expectedExitCode());
        assertThat(result.output()).contains(testCase.expectedOutput());
    }

    @Test
    void printsSetupLocalHelpWithoutRequiringCredentials() {
        // given

        // when
        CliRunResult result = runCli("setup-local", "--help");

        // then
        result.assertSuccess()
                .stdoutContains(
                        "Usage: symphony-trello setup-local",
                        "--env=<envPath>",
                        "Dotenv file for Trello credentials.",
                        "Commands:",
                        "check",
                        "--no-github")
                .stdoutDoesNotContain("Ignored dotenv file")
                .stderrEmpty();
    }

    @Test
    void printsNestedSetupLocalHelp() {
        // given

        // when
        CliRunResult check = runCli("setup-local", "check", "--help");
        CliRunResult repair = runCli("setup-local", "repair-port", "--help");
        CliRunResult configure = runCli("setup-local", "configure-github", "--help");

        // then
        check.assertSuccess()
                .stdoutContains("Usage: symphony-trello setup-local check", "--config-dir", "--manifest")
                .stdoutDoesNotContain("Ignored dotenv file", "--server-port", "--active", "--codex-model")
                .stderrEmpty();
        repair.assertSuccess()
                .stdoutContains("Usage: symphony-trello setup-local repair-port", "--board", "--dry-run")
                .stdoutDoesNotContain("Ignored dotenv file", "--workspace-root", "--server-port", "--active")
                .stderrEmpty();
        configure
                .assertSuccess()
                .stdoutContains("Usage: symphony-trello setup-local configure-github", "--board", "--max-agents")
                .stdoutDoesNotContain("--workflow", "--server-port", "--active", "--dry-run", "--env")
                .stderrEmpty();
    }

    @Test
    void diagnosticsWritesSanitizedJsonOutputFile() throws Exception {
        // given
        Path configDir = tempDir.resolve("diagnostics-config");
        Path workspaceRoot = tempDir.resolve("diagnostics-workspaces");
        Path stateHome = tempDir.resolve("diagnostics-state");
        Path workflow = configDir.resolve("WORKFLOW.private.md");
        Path output = tempDir.resolve("Users")
                .resolve("Jane Doe")
                .resolve("private checkout")
                .resolve("diagnostics.json");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  board_id: "private-board-id"
                server:
                  port: 19183
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "private-board-id",
                        "private-key",
                        "Private Board",
                        "https://trello.com/b/private-key/private-board",
                        workflow,
                        configDir.resolve(".env"),
                        workspaceRoot,
                        19183,
                        false,
                        List.of(tempDir.resolve("client checkout")),
                        false))));
        Files.writeString(
                stateHome.resolve("WORKFLOW.private.err"),
                "token=secret-token\npath=" + tempDir.resolve("client checkout") + "\n",
                StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "diagnostics",
                "--config-dir",
                configDir.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--state-home",
                stateHome.toString(),
                "--board",
                "Private Board",
                "--output",
                output.toString(),
                "--json");

        // then
        result.assertSuccess()
                .stdoutContains("Diagnostics written.", "Review it before sharing", "private context")
                .stdoutDoesNotContain(
                        output.toString(),
                        output.toAbsolutePath().normalize().toString(),
                        tempDir.toString(),
                        "Jane Doe",
                        "private checkout")
                .stderrEmpty();
        assertThat(output)
                .content(StandardCharsets.UTF_8)
                .contains("\"format\":\"markdown\"", "Symphony for Trello Diagnostics", "board_count:** 1", "19183")
                .doesNotContain(
                        "Private Board",
                        "private-board-id",
                        "private-key",
                        "secret-token",
                        "Jane Doe",
                        "client checkout",
                        "private checkout",
                        tempDir.toString());
    }

    @Test
    void statusRejectsNonTrelloBoardUrlSelectors() throws Exception {
        // given
        Path configDir = tempDir.resolve("status-config");
        Path workspaceRoot = tempDir.resolve("status-workspaces");
        Path stateHome = tempDir.resolve("status-state");
        Path workflow = configDir.resolve("WORKFLOW.queue.md");
        Path env = configDir.resolve(".env");
        Files.createDirectories(configDir);
        Files.writeString(workflow, TestWorkflows.workflowWithBoardAndPort("board-id", 19191), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "board-id",
                        "abc123",
                        "Queue",
                        "https://trello.com/b/abc123/queue",
                        workflow,
                        env,
                        workspaceRoot,
                        19191,
                        false,
                        List.of(),
                        false))));

        // when
        CliRunResult result = runCli(
                "status",
                "--config-dir",
                configDir.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--state-home",
                stateHome.toString(),
                "--board",
                "https://not-trello.com/b/abc123/anything");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "Invalid --board value. Use a Trello board URL, short link, board id, or a connected board name.")
                .stderrDoesNotContain("Queue", "abc123", "Troubleshooting report written")
                .stdoutDoesNotContain("running", "stopped", "Queue", "abc123");
    }

    @Test
    void statusRejectsTrelloCardUrlSelectors() throws Exception {
        // given
        Path configDir = tempDir.resolve("status-card-url-config");
        Path workspaceRoot = tempDir.resolve("status-card-url-workspaces");
        Path stateHome = tempDir.resolve("status-card-url-state");
        Path workflow = configDir.resolve("WORKFLOW.queue.md");
        Path env = configDir.resolve(".env");
        Files.createDirectories(configDir);
        Files.writeString(workflow, TestWorkflows.workflowWithBoardAndPort("board-id", 19192), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "board-id",
                        "abc123",
                        "Queue",
                        "https://trello.com/b/abc123/queue",
                        workflow,
                        env,
                        workspaceRoot,
                        19192,
                        false,
                        List.of(),
                        false))));

        // when
        CliRunResult result = runCli(
                "status",
                "--config-dir",
                configDir.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--state-home",
                stateHome.toString(),
                "--board",
                "https://trello.com/c/abc123/not-a-board");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "Invalid --board value. Use a Trello board URL, short link, board id, or a connected board name.")
                .stderrDoesNotContain("Queue", "abc123", "Troubleshooting report written")
                .stdoutDoesNotContain("running", "stopped", "Queue", "abc123");
    }

    @Test
    void diagnosticsPrivateContextWritesPrivateTroubleshootingContextWithoutPrintingOutputPath() throws Exception {
        // given
        Path configDir = tempDir.resolve("private-context-config");
        Path workspaceRoot = tempDir.resolve("private-context-workspaces");
        Path stateHome = tempDir.resolve("private-context-state");
        Path workflow = configDir.resolve("WORKFLOW.private.md");
        Path env = configDir.resolve(".env");
        Path output = tempDir.resolve("Users")
                .resolve("Jane Doe")
                .resolve("private checkout")
                .resolve("private-context.json");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(
                workflow, TestWorkflows.workflowWithBoardAndPort("private-board-id", 19184), StandardCharsets.UTF_8);
        Files.writeString(env, "TRELLO_API_TOKEN=secret-token\n", StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "private-board-id",
                        "private-key",
                        "Private Board",
                        "https://trello.com/b/private-key/private-board",
                        workflow,
                        env,
                        workspaceRoot,
                        19184,
                        false,
                        List.of(tempDir.resolve("client checkout")),
                        false))));
        ManagedProcessStore.ManagedProcessFiles logs = new ManagedProcessStore(stateHome).files(workflow);
        Files.writeString(logs.stderrLog(), "secret log content\n", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "diagnostics",
                "--config-dir",
                configDir.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--state-home",
                stateHome.toString(),
                "--board",
                "Private Board",
                "--show-private-context",
                "--output",
                output.toString(),
                "--json");

        // then
        result.assertSuccess()
                .stdoutContains("Diagnostics written.", "contains private diagnostics context")
                .stdoutDoesNotContain(output.toString(), tempDir.toString(), "Jane Doe", "private checkout")
                .stderrEmpty();
        assertThat(output)
                .content(StandardCharsets.UTF_8)
                .contains(
                        "\"format\":\"markdown\"",
                        "Symphony for Trello Private Context",
                        "Do not paste this output into public issues",
                        "Private Board",
                        "private-board-id",
                        "private-key",
                        "https://trello.com/b/private-key/private-board",
                        workflow.toString(),
                        env.toString(),
                        workspaceRoot.toString(),
                        logs.stderrLog().toString(),
                        "19184")
                .doesNotContain("secret-token", "secret log content");
    }

    @Test
    void diagnosticsPrivateContextLookupResolvesOneToken() throws Exception {
        // given
        Path configDir = tempDir.resolve("private-context-lookup-config");
        Path workspaceRoot = tempDir.resolve("private-context-lookup-workspaces");
        Path stateHome = tempDir.resolve("private-context-lookup-state");
        Path workflow = configDir.resolve("WORKFLOW.private-lookup.md");
        Path env = configDir.resolve(".env.private-lookup");
        Files.createDirectories(configDir);
        Files.writeString(
                workflow, TestWorkflows.workflowWithBoardAndPort("lookup-board-id", 19185), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "lookup-board-id",
                        "lookup-key",
                        "Lookup Board",
                        "https://trello.com/b/lookup-key/lookup-board",
                        workflow,
                        env,
                        workspaceRoot,
                        19185,
                        false,
                        List.of(),
                        false))));
        String lookupToken = "<path:" + DiagnosticsTokenHasher.load(configDir).token(workflow.toString()) + ">";

        // when
        CliRunResult result = runCli(
                "diagnostics",
                "--config-dir",
                configDir.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--state-home",
                stateHome.toString(),
                "--board",
                "Lookup Board",
                "--show-private-context",
                "--lookup",
                lookupToken);

        // then
        result.assertSuccess()
                .stdoutContains(
                        "# Symphony for Trello Private Context",
                        "## Lookup",
                        "- **lookup_status:** found",
                        lookupToken,
                        workflow.toString())
                .stdoutDoesNotContain("lookup-key", "https://trello.com/b/lookup-key/lookup-board")
                .stderrEmpty();
    }

    @Test
    void diagnosticsLookupRequiresPrivateContext() throws Exception {
        // given
        Path output = tempDir.resolve("lookup-without-private-context.md");

        // when
        CliRunResult result = runCli("diagnostics", "--lookup", "<path:000000000000>", "--output", output.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stdoutDoesNotContain("# Symphony for Trello Diagnostics", "Diagnostics written")
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "--lookup requires --show-private-context",
                        "Try 'diagnostics --help' for usage.")
                .stderrDoesNotContain(output.toString(), tempDir.toString());
        assertThat(output).doesNotExist();
    }

    @Test
    void diagnosticsRejectsBoardAndWorkflowTogetherWithoutWritingReport() throws Exception {
        // given
        Path configDir = tempDir.resolve("diagnostics-selector-config");
        Path workspaceRoot = tempDir.resolve("diagnostics-selector-workspaces");
        Path stateHome = tempDir.resolve("diagnostics-selector-state");
        Path workflowA = configDir.resolve("WORKFLOW.board-a.md");
        Path workflowB = configDir.resolve("WORKFLOW.board-b.md");
        Path output = tempDir.resolve("diagnostics-selector-output.txt");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(
                workflowA, TestWorkflows.workflowWithBoardAndPort("board-a-id", 19188), StandardCharsets.UTF_8);
        Files.writeString(
                workflowB, TestWorkflows.workflowWithBoardAndPort("board-b-id", 19189), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(
                        new ConnectedBoard(
                                "board-a-id",
                                "board-a-key",
                                "Board A",
                                "https://trello.com/b/board-a-key/board-a",
                                workflowA,
                                configDir.resolve(".env"),
                                workspaceRoot,
                                19188,
                                false,
                                List.of(),
                                false),
                        new ConnectedBoard(
                                "board-b-id",
                                "board-b-key",
                                "Board B",
                                "https://trello.com/b/board-b-key/board-b",
                                workflowB,
                                configDir.resolve(".env"),
                                workspaceRoot,
                                19189,
                                false,
                                List.of(),
                                false))));
        ManagedProcessStore store = new ManagedProcessStore(stateHome);
        Files.writeString(store.files(workflowA).stdoutLog(), "board A log\n", StandardCharsets.UTF_8);
        Files.writeString(store.files(workflowB).stdoutLog(), "board B log\n", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "diagnostics",
                "--config-dir",
                configDir.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--state-home",
                stateHome.toString(),
                "--board",
                "Board A",
                "--workflow",
                workflowB.toString(),
                "--output",
                output.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stdoutDoesNotContain(
                        "# Symphony for Trello Diagnostics", "board A log", "board B log", "19188", "19189")
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "Only one diagnostics selector may be provided.",
                        "Try 'diagnostics --help' for usage.")
                .stderrDoesNotContain("Board A", "Board B", workflowB.toString(), tempDir.toString());
        assertThat(output).doesNotExist();
    }

    @Test
    void diagnosticsRejectsRepeatedBoardSelectorsWithoutLeakingValues() {
        // given
        Path output = tempDir.resolve("repeated-board-selector-output.txt");
        String firstBoard = "Jane Doe Private Board";
        String secondBoard = "Internal Launch Board";

        // when
        CliRunResult result =
                runCli("diagnostics", "--board", firstBoard, "--board", secondBoard, "--output", output.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stdoutDoesNotContain("# Symphony for Trello Diagnostics", "Diagnostics written")
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "Only one diagnostics selector may be provided.",
                        "Try 'diagnostics --help' for usage.")
                .stderrDoesNotContain(
                        firstBoard,
                        secondBoard,
                        output.toString(),
                        tempDir.toString(),
                        "Troubleshooting report written");
        assertThat(output).doesNotExist();
    }

    @Test
    void diagnosticsRejectsRepeatedWorkflowSelectorsWithoutLeakingValues() {
        // given
        Path privateDir = tempDir.resolve("Jane Doe").resolve("private-checkout");
        Path firstWorkflow = privateDir.resolve("WORKFLOW.private-a.md");
        Path secondWorkflow = privateDir.resolve("WORKFLOW.private-b.md");
        Path output = tempDir.resolve("repeated-workflow-selector-output.txt");

        // when
        CliRunResult result = runCli(
                "diagnostics",
                "--workflow",
                firstWorkflow.toString(),
                "--workflow",
                secondWorkflow.toString(),
                "--output",
                output.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stdoutDoesNotContain("# Symphony for Trello Diagnostics", "Diagnostics written")
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "Only one diagnostics selector may be provided.",
                        "Try 'diagnostics --help' for usage.")
                .stderrDoesNotContain(
                        firstWorkflow.toString(),
                        secondWorkflow.toString(),
                        privateDir.toString(),
                        output.toString(),
                        tempDir.toString(),
                        "Jane Doe",
                        "Troubleshooting report written");
        assertThat(output).doesNotExist();
    }

    @Test
    void diagnosticsRejectsUnusableWorkflowSelectorsWithoutWritingReport() throws Exception {
        // given
        Path configDir = tempDir.resolve("diagnostics-unusable-workflow-config");
        Path workspaceRoot = tempDir.resolve("diagnostics-unusable-workflow-workspaces");
        Path stateHome = tempDir.resolve("diagnostics-unusable-workflow-state");
        Path directory = configDir.resolve("WORKFLOW.directory.md");
        Path missing = configDir.resolve("WORKFLOW.missing.md");
        Path empty = configDir.resolve("WORKFLOW.empty.md");
        Path noFrontMatter = configDir.resolve("WORKFLOW.no-frontmatter.md");
        Path invalidPort = configDir.resolve("WORKFLOW.invalid-port.md");
        Files.createDirectories(directory);
        Files.createDirectories(stateHome);
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        Files.writeString(noFrontMatter, "Body only\n", StandardCharsets.UTF_8);
        Files.writeString(
                invalidPort,
                """
                ---
                tracker:
                  board_id: "private-board-id"
                server:
                  port: "not-a-port"
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        record UnusableWorkflowSelector(String name, Path workflow) {}
        // Invalid server.port values stay selectable: diagnostics is the inspection tool for such
        // workflows and reports the port problem inside the rendered report instead.
        List<UnusableWorkflowSelector> selectors = List.of(
                new UnusableWorkflowSelector("directory", directory),
                new UnusableWorkflowSelector("missing", missing),
                new UnusableWorkflowSelector("empty", empty),
                new UnusableWorkflowSelector("no-frontmatter", noFrontMatter));

        // when
        List<CliRunResult> results = selectors.stream()
                .map(selector -> runCli(
                        "diagnostics",
                        "--config-dir",
                        configDir.toString(),
                        "--workspace-root",
                        workspaceRoot.toString(),
                        "--state-home",
                        stateHome.toString(),
                        "--workflow",
                        selector.workflow().toString(),
                        "--output",
                        tempDir.resolve(selector.name() + "-diagnostics.md").toString()))
                .toList();

        // then
        assertThat(results).allSatisfy(result -> result.assertFailure(SETUP_FAILURE)
                .stdoutDoesNotContain("# Symphony for Trello Diagnostics", "selected_workflow_file_count")
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "--workflow must reference a readable workflow file with usable workflow front matter.")
                .stderrDoesNotContain(
                        "Troubleshooting report written",
                        tempDir.toString(),
                        configDir.toString(),
                        directory.toString(),
                        missing.toString(),
                        empty.toString(),
                        noFrontMatter.toString(),
                        invalidPort.toString(),
                        "private-board-id",
                        "not-a-port"));
        assertThat(selectors).allSatisfy(selector -> assertThat(tempDir.resolve(selector.name() + "-diagnostics.md"))
                .doesNotExist());
        CliRunResult invalidPortResult = runCli(
                "diagnostics",
                "--config-dir",
                configDir.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--state-home",
                stateHome.toString(),
                "--workflow",
                invalidPort.toString(),
                "--output",
                tempDir.resolve("invalid-port-diagnostics.md").toString());
        invalidPortResult.assertSuccess();
        assertThat(Files.readString(tempDir.resolve("invalid-port-diagnostics.md"), StandardCharsets.UTF_8))
                .contains("invalid server.port")
                .doesNotContain("IllegalArgumentException", "private-board-id");
    }

    @Test
    void diagnosticsRejectsAmbiguousBoardNameWithoutWritingReport() throws Exception {
        // given
        Path configDir = tempDir.resolve("diagnostics-ambiguous-board-config");
        Path workspaceRoot = tempDir.resolve("diagnostics-ambiguous-board-workspaces");
        Path stateHome = tempDir.resolve("diagnostics-ambiguous-board-state");
        Path workflowA = configDir.resolve("WORKFLOW.duplicate-a.md");
        Path workflowB = configDir.resolve("WORKFLOW.duplicate-b.md");
        Path output = tempDir.resolve("diagnostics-ambiguous-board-output.txt");
        String privateBoardName = "Private Duplicate Board";
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(
                workflowA, TestWorkflows.workflowWithBoardAndPort("private-board-a-id", 19190), StandardCharsets.UTF_8);
        Files.writeString(
                workflowB, TestWorkflows.workflowWithBoardAndPort("private-board-b-id", 19191), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(
                        new ConnectedBoard(
                                "private-board-a-id",
                                "private-a-key",
                                privateBoardName,
                                "https://trello.com/b/private-a-key/private-a",
                                workflowA,
                                configDir.resolve(".env"),
                                workspaceRoot,
                                19190,
                                false,
                                List.of(),
                                false),
                        new ConnectedBoard(
                                "private-board-b-id",
                                "private-b-key",
                                privateBoardName,
                                "https://trello.com/b/private-b-key/private-b",
                                workflowB,
                                configDir.resolve(".env"),
                                workspaceRoot,
                                19191,
                                false,
                                List.of(),
                                false))));
        ManagedProcessStore store = new ManagedProcessStore(stateHome);
        Files.writeString(store.files(workflowA).stdoutLog(), "private board A log\n", StandardCharsets.UTF_8);
        Files.writeString(store.files(workflowB).stdoutLog(), "private board B log\n", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "diagnostics",
                "--config-dir",
                configDir.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--state-home",
                stateHome.toString(),
                "--board",
                privateBoardName,
                "--output",
                output.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stdoutDoesNotContain(
                        "# Symphony for Trello Diagnostics",
                        "Troubleshooting report written",
                        "private board A log",
                        "private board B log",
                        "19190",
                        "19191")
                .stderrContains("setup_failed code=setup_invalid_arguments", "Multiple connected boards match --board")
                .stderrDoesNotContain(
                        privateBoardName,
                        "private-board-a-id",
                        "private-board-b-id",
                        "private-a-key",
                        "private-b-key",
                        "https://trello.com/b/private-a-key/private-a",
                        "https://trello.com/b/private-b-key/private-b",
                        workflowA.toString(),
                        workflowB.toString(),
                        "private board A log",
                        "private board B log",
                        tempDir.toString());
        assertThat(output).doesNotExist();
    }

    @Test
    void logsDoesNotReadSymlinkedWorkerLogTargets() throws Exception {
        // given
        Path configDir = tempDir.resolve("logs-symlink-config");
        Path workspaceRoot = tempDir.resolve("logs-symlink-workspaces");
        Path stateHome = tempDir.resolve("logs-symlink-state");
        Path workflow = configDir.resolve("WORKFLOW.symlink-log.md");
        Path privateHostFile = tempDir.resolve("private-host-file.txt");
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.writeString(workflow, TestWorkflows.workflowWithBoardAndPort("board-id", 19194), StandardCharsets.UTF_8);
        Files.writeString(privateHostFile, "PRIVATE_HOST_FILE_MARKER_SHOULD_NOT_APPEAR\n", StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "board-id",
                        "board-key",
                        "Board A",
                        "https://trello.com/b/board-key/board-a",
                        workflow,
                        configDir.resolve(".env"),
                        workspaceRoot,
                        19194,
                        false,
                        List.of(),
                        false))));
        ManagedProcessStore.ManagedProcessFiles logs = new ManagedProcessStore(stateHome).files(workflow);
        createSymbolicLinkOrSkip(logs.stdoutLog(), privateHostFile);
        Files.writeString(logs.stderrLog(), "", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "logs",
                "--config-dir",
                configDir.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--state-home",
                stateHome.toString(),
                "--board",
                "Board A");

        // then
        result.assertSuccess()
                .stdoutDoesNotContain("PRIVATE_HOST_FILE_MARKER_SHOULD_NOT_APPEAR", privateHostFile.toString())
                .stderrEmpty();
    }

    @Test
    void diagnosticsOutputWriteFailureDoesNotLeakPrivatePath() throws Exception {
        // given
        Path configDir = tempDir.resolve("diagnostics-output-failure-config");
        Path privatePathComponent = tempDir.resolve("Jane Doe");
        Path output = privatePathComponent.resolve("diagnostics.txt");
        Files.createDirectories(configDir);
        Files.writeString(privatePathComponent, "not a directory", StandardCharsets.UTF_8);

        // when
        CliRunResult result =
                runCli("diagnostics", "--config-dir", configDir.toString(), "--output", output.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stdoutDoesNotContain("# Symphony for Trello Diagnostics")
                .stderrContains("Could not write diagnostics output")
                .stderrDoesNotContain(
                        output.toString(), privatePathComponent.toString(), tempDir.toString(), "Jane Doe");
    }

    @MethodSource("diagnosticsControlCharacterPathOptions")
    @ParameterizedTest(name = "{0}")
    void diagnosticsRejectsControlCharactersInPathOptionsWithoutRenderingReport(InvalidPathOptionCase invalidCase) {
        // given

        // when
        CliRunResult result = runCli(invalidCase.commandArray());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        invalidCase.optionName() + " must not contain control characters")
                .stdoutDoesNotContain(invalidCase.forbiddenOutput(), invalidCase.rawValue())
                .stderrDoesNotContain(invalidCase.rawValue(), "Troubleshooting report written");
        assertThat(tempDir.resolve(invalidCase.rawValue())).doesNotExist();
    }

    private static Stream<InvalidPathOptionCase> diagnosticsControlCharacterPathOptions() {
        return Stream.of(
                invalidPathOptionCase(
                        "diagnostics output",
                        "--output",
                        "bad\noutput.md",
                        "# Symphony for Trello Diagnostics",
                        "diagnostics",
                        "--output",
                        "bad\noutput.md"),
                invalidPathOptionCase(
                        "diagnostics manifest",
                        "--manifest",
                        "bad\nmanifest.json",
                        "# Symphony for Trello Diagnostics",
                        "diagnostics",
                        "--manifest",
                        "bad\nmanifest.json"),
                invalidPathOptionCase(
                        "diagnostics workflow",
                        "--workflow",
                        "bad\nworkflow.md",
                        "# Symphony for Trello Diagnostics",
                        "diagnostics",
                        "--workflow",
                        "bad\nworkflow.md"),
                invalidPathOptionCase(
                        "diagnostics config dir",
                        "--config-dir",
                        "bad\nconfig",
                        "# Symphony for Trello Diagnostics",
                        "diagnostics",
                        "--config-dir",
                        "bad\nconfig"),
                invalidPathOptionCase(
                        "diagnostics workspace root",
                        "--workspace-root",
                        "bad\nworkspace-root",
                        "# Symphony for Trello Diagnostics",
                        "diagnostics",
                        "--workspace-root",
                        "bad\nworkspace-root"),
                invalidPathOptionCase(
                        "diagnostics state home",
                        "--state-home",
                        "bad\nstate",
                        "# Symphony for Trello Diagnostics",
                        "diagnostics",
                        "--state-home",
                        "bad\nstate"));
    }

    @Test
    void diagnosticsRejectsDashOutputWithoutCreatingDashFile() throws Exception {
        // given
        Path workingDir = tempDir.resolve("dash-output-workdir");
        Path dashFile = workingDir.resolve("-");
        Files.createDirectories(workingDir);

        // when
        MainProcessResult result = runMainProcess(workingDir, Map.of(), List.of(), "diagnostics", "--output", "-");

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.stdout()).doesNotContain("# Symphony for Trello Diagnostics", "Diagnostics written");
        assertThat(result.stderr())
                .contains("setup_failed code=setup_invalid_arguments", "--output - is not supported")
                .doesNotContain("Troubleshooting report written", dashFile.toString(), tempDir.toString());
        assertThat(dashFile).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/dev/stdout", "/dev/stderr", "/dev/fd/1", "/proc/self/fd/1", "/proc/thread-self/fd/1"})
    void diagnosticsRejectsStandardStreamOutputPathsWithoutRenderingReport(String outputPath) throws Exception {
        // given
        Path workingDir = tempDir.resolve("stream-output-workdir");
        Files.createDirectories(workingDir);

        // when
        MainProcessResult result = runDiagnosticsOutput(workingDir, outputPath);

        // then
        assertDiagnosticsStreamOutputRejected(result, outputPath, tempDir.toString());
    }

    @Test
    void diagnosticsRejectsFifoOutputWithoutRenderingReport() throws Exception {
        // given
        assumeTrue(!javaExecutable().endsWith(".exe"), "POSIX FIFO support is required");
        Path workingDir = tempDir.resolve("fifo-output-workdir");
        Path privateDir = tempDir.resolve("Jane Doe");
        Path outputPath = privateDir.resolve("diagnostics-output.fifo");
        Files.createDirectories(workingDir);
        Files.createDirectories(privateDir);
        createFifo(outputPath, workingDir);

        // when
        MainProcessResult result = runDiagnosticsOutput(workingDir, outputPath.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.stdout()).doesNotContain("# Symphony for Trello Diagnostics", "Diagnostics written");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_invalid_arguments",
                        "Could not write diagnostics output. Choose a writable regular file.")
                .doesNotContain(
                        "Troubleshooting report written",
                        outputPath.toString(),
                        privateDir.toString(),
                        tempDir.toString(),
                        "Jane Doe");
    }

    @Test
    void diagnosticsRejectsNumericProcFdStandardStreamOutputPath() throws Exception {
        // given
        long currentPid = ProcessHandle.current().pid();
        String outputPath = "/proc/" + currentPid + "/fd/1";
        assumeTrue(Files.exists(Path.of(outputPath)), outputPath + " is not available on this platform");
        Path workingDir = tempDir.resolve("numeric-proc-fd-stream-output-workdir");
        Files.createDirectories(workingDir);

        // when
        MainProcessResult result = runDiagnosticsOutput(workingDir, outputPath);

        // then
        assertDiagnosticsStreamOutputRejected(result, outputPath, tempDir.toString());
    }

    @Test
    void diagnosticsAllowsPosixOutputFilenameContainingBackslashes() throws Exception {
        // given
        assumeTrue(!javaExecutable().endsWith(".exe"), "POSIX path semantics are required");
        Path workingDir = tempDir.resolve("backslash-output-workdir");
        Files.createDirectories(workingDir);
        Path outputPath = workingDir.resolve("\\dev\\stdout");

        // when
        MainProcessResult result = runDiagnosticsOutput(workingDir, outputPath.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.stdout()).contains("Diagnostics written.");
        assertThat(result.stderr()).isEmpty();
        assertThat(outputPath).content(StandardCharsets.UTF_8).contains("# Symphony for Trello Diagnostics");
    }

    @Test
    void diagnosticsCapturesAndSanitizesToolProbeStderr() throws Exception {
        // given
        assumeTrue(!javaExecutable().endsWith(".exe"), "POSIX executable scripts are required");
        Path workingDir = tempDir.resolve("tool-probe-stderr-workdir");
        Files.createDirectories(workingDir);
        Path fakeBin = tempDir.resolve("tool-probe-bin");
        Files.createDirectories(fakeBin);
        writeExecutable(
                fakeBin.resolve("java"),
                """
                #!/usr/bin/env sh
                echo 'openjdk version "25" api_token=ATTAsecretsecretsecret board=000000000000000000000002 path=/home/Jane Doe/private' >&2
                """);
        writeExecutable(
                fakeBin.resolve("javac"),
                """
                #!/usr/bin/env sh
                echo 'javac 25'
                """);
        writeExecutable(
                fakeBin.resolve("git"),
                """
                #!/usr/bin/env sh
                echo 'git version 2.0 token=%s path=/home/Jane Doe/private'
                """
                        .formatted("ghp_" + "1234567890abcdef1234567890abcdef123456"));
        Map<String, String> environment =
                Map.of("PATH", fakeBin + File.pathSeparator + System.getenv().getOrDefault("PATH", ""));

        // when
        MainProcessResult result = runMainProcess(workingDir, environment, List.of(), "diagnostics");

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.stderr()).isEmpty();
        assertThat(result.stdout())
                .contains(
                        "# Symphony for Trello Diagnostics",
                        "private context",
                        "## Tool Availability",
                        "openjdk version",
                        "git version")
                .doesNotContain(
                        "api_token=ATTAsecretsecretsecret",
                        "ATTAsecretsecretsecret",
                        "000000000000000000000002",
                        "ghp_" + "1234567890abcdef1234567890abcdef123456",
                        "/home/Jane Doe/private",
                        "Jane Doe",
                        tempDir.toString());
    }

    @Test
    void diagnosticsRejectsSpecialFileConfigDirWithoutRenderingReport() throws Exception {
        // given
        assumeTrue(!javaExecutable().endsWith(".exe"), "POSIX FIFO support is required");
        Path workingDir = tempDir.resolve("special-config-workdir");
        Path privateDir = tempDir.resolve("Jane Doe");
        Path configFifo = privateDir.resolve("config.fifo");
        Path stateHome = tempDir.resolve("special-config-state");
        Path workspaceRoot = tempDir.resolve("special-config-workspace");
        Files.createDirectories(workingDir);
        Files.createDirectories(privateDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(workspaceRoot);
        createFifo(configFifo, workingDir);

        // when
        MainProcessResult result = runMainProcess(
                workingDir,
                Map.of(),
                List.of(),
                "diagnostics",
                "--config-dir",
                configFifo.toString(),
                "--state-home",
                stateHome.toString(),
                "--workspace-root",
                workspaceRoot.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.stdout()).doesNotContain("# Symphony for Trello Diagnostics", "Diagnostics written");
        assertThat(result.stderr())
                .contains("setup_failed code=setup_invalid_arguments", "--config-dir must be a directory.")
                .doesNotContain(
                        "Troubleshooting report written",
                        configFifo.toString(),
                        privateDir.toString(),
                        tempDir.toString(),
                        "Jane Doe");
    }

    @Test
    void diagnosticsRejectsSpecialFileWorkflowWithoutRenderingReport() throws Exception {
        // given
        assumeTrue(!javaExecutable().endsWith(".exe"), "POSIX FIFO support is required");
        Path workingDir = tempDir.resolve("special-workflow-workdir");
        Path privateDir = tempDir.resolve("Jane Doe");
        Path configDir = tempDir.resolve("special-workflow-config");
        Path stateHome = tempDir.resolve("special-workflow-state");
        Path workspaceRoot = tempDir.resolve("special-workflow-workspace");
        Path workflow = privateDir.resolve("WORKFLOW.private.fifo.md");
        Path output = tempDir.resolve("special-workflow-output.md");
        Files.createDirectories(workingDir);
        Files.createDirectories(privateDir);
        Files.createDirectories(configDir);
        Files.createDirectories(stateHome);
        Files.createDirectories(workspaceRoot);
        createFifo(workflow, workingDir);

        // when
        MainProcessResult result = runMainProcess(
                workingDir,
                Map.of(),
                List.of(),
                "diagnostics",
                "--config-dir",
                configDir.toString(),
                "--state-home",
                stateHome.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--workflow",
                workflow.toString(),
                "--output",
                output.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.stdout()).doesNotContain("# Symphony for Trello Diagnostics", "Diagnostics written");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_invalid_arguments",
                        "--workflow must reference a readable workflow file")
                .doesNotContain(
                        "Troubleshooting report written",
                        workflow.toString(),
                        output.toString(),
                        privateDir.toString(),
                        tempDir.toString(),
                        "Jane Doe");
        assertThat(output).doesNotExist();
    }

    @Test
    void diagnosticsRejectsRelativeOutputPathResolvingToStandardStreamWithoutRenderingReport() throws Exception {
        // given
        Path streamPath = Path.of("/dev/stdout");
        assumeTrue(Files.exists(streamPath), "/dev/stdout is not available on this platform");
        Path workingDir = tempDir.resolve("relative-stream-output-workdir");
        Files.createDirectories(workingDir);
        Path outputPath = workingDir.relativize(streamPath);

        // when
        MainProcessResult result = runDiagnosticsOutput(workingDir, outputPath.toString());

        // then
        assertDiagnosticsStreamOutputRejected(result, outputPath.toString(), streamPath.toString());
    }

    @Test
    void diagnosticsRejectsOutputSymlinkResolvingToStandardStreamWithoutRenderingReport() throws Exception {
        // given
        Path streamPath = Path.of("/dev/stdout");
        assumeTrue(Files.exists(streamPath), "/dev/stdout is not available on this platform");
        Path workingDir = tempDir.resolve("symlink-stream-output-workdir");
        Files.createDirectories(workingDir);
        Path outputPath = workingDir.resolve("diagnostics-output-link");
        createSymbolicLinkOrSkip(outputPath, streamPath);

        // when
        MainProcessResult result = runDiagnosticsOutput(workingDir, outputPath.toString());

        // then
        assertDiagnosticsStreamOutputRejected(result, outputPath.toString(), streamPath.toString());
    }

    @Test
    void diagnosticsRejectsOutputSymlinkChainResolvingToStandardStreamWithoutRenderingReport() throws Exception {
        // given
        Path streamPath = Path.of("/dev/stdout");
        assumeTrue(Files.exists(streamPath), "/dev/stdout is not available on this platform");
        Path workingDir = tempDir.resolve("symlink-chain-stream-output-workdir");
        Files.createDirectories(workingDir);
        Path intermediatePath = workingDir.resolve("intermediate-output-link");
        Path outputPath = workingDir.resolve("diagnostics-output-link");
        createSymbolicLinkOrSkip(intermediatePath, streamPath);
        createSymbolicLinkOrSkip(outputPath, intermediatePath);

        // when
        MainProcessResult result = runDiagnosticsOutput(workingDir, outputPath.toString());

        // then
        assertDiagnosticsStreamOutputRejected(result, outputPath.toString(), streamPath.toString());
    }

    @Test
    void diagnosticsRejectsOutputPathThroughSymlinkedParentResolvingToStandardStream() throws Exception {
        // given
        Path devPath = Path.of("/dev");
        assumeTrue(Files.isDirectory(devPath), "/dev is not available on this platform");
        Path workingDir = tempDir.resolve("symlink-parent-stream-output-workdir");
        Files.createDirectories(workingDir);
        Path linkedParent = workingDir.resolve("linked-device-directory");
        Path outputPath = linkedParent.resolve("fd").resolve("1");
        createSymbolicLinkOrSkip(linkedParent, devPath);
        try {

            // when
            MainProcessResult result = runDiagnosticsOutput(workingDir, outputPath.toString());

            // then
            assertDiagnosticsStreamOutputRejected(result, outputPath.toString(), devPath.toString());
        } finally {
            // Remove the /dev link before @TempDir cleanup: JUnit warns in the build log whenever
            // it deletes a symlink that resolves to a location outside the temp dir.
            Files.deleteIfExists(linkedParent);
        }
    }

    @Test
    void diagnosticsRejectsOverDeepOutputSymlinkChainWithoutRenderingReport() throws Exception {
        // given
        Path streamPath = Path.of("/dev/stdout");
        assumeTrue(Files.exists(streamPath), "/dev/stdout is not available on this platform");
        Path workingDir = tempDir.resolve("deep-symlink-chain-stream-output-workdir");
        Files.createDirectories(workingDir);
        Path previousPath = streamPath;
        for (int index = 0; index < 18; index++) {
            Path nextPath = workingDir.resolve("diagnostics-output-link-" + index);
            createSymbolicLinkOrSkip(nextPath, previousPath);
            previousPath = nextPath;
        }

        // when
        MainProcessResult result = runDiagnosticsOutput(workingDir, previousPath.toString());

        // then
        assertDiagnosticsStreamOutputRejected(result, previousPath.toString(), streamPath.toString());
    }

    @Test
    void diagnosticsAllowsDeepOutputSymlinkChainResolvingToRegularFile() throws Exception {
        // given
        Path workingDir = tempDir.resolve("deep-symlink-chain-file-output-workdir");
        Files.createDirectories(workingDir);
        Path outputFile = workingDir.resolve("diagnostics-output.md");
        Path previousPath = outputFile;
        for (int index = 0; index < 18; index++) {
            Path nextPath = workingDir.resolve("diagnostics-file-output-link-" + index);
            createSymbolicLinkOrSkip(nextPath, previousPath);
            previousPath = nextPath;
        }

        // when
        MainProcessResult result = runDiagnosticsOutput(workingDir, previousPath.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.stdout()).contains("Diagnostics written.");
        assertThat(result.stderr()).isEmpty();
        assertThat(outputFile)
                .content(StandardCharsets.UTF_8)
                .contains("# Symphony for Trello Diagnostics", "private context");
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException exception) {
            assumeTrue(false, "symbolic links are not available on this platform: " + exception.getMessage());
        }
    }

    private static MainProcessResult runDiagnosticsOutput(Path workingDir, String outputPath)
            throws IOException, InterruptedException {
        return runMainProcess(workingDir, Map.of(), List.of(), "diagnostics", "--output", outputPath);
    }

    private void assertDiagnosticsStreamOutputRejected(MainProcessResult result, String... forbiddenStderrValues) {
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.stdout()).doesNotContain("# Symphony for Trello Diagnostics", "Diagnostics written");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_invalid_arguments", "--output standard stream paths are not supported")
                .doesNotContain("Troubleshooting report written", tempDir.toString())
                .doesNotContain(forbiddenStderrValues);
    }

    @MethodSource("blankDiagnosticsSelectors")
    @ParameterizedTest(name = "{0} {1}")
    void diagnosticsRejectsBlankSelectorsWithoutRenderingReport(String optionName, String optionValue)
            throws Exception {
        // given
        BlankDiagnosticsOptionRun run = blankDiagnosticsOptionRun("blank-diagnostics-selector-workdir");

        // when
        MainProcessResult result = runBlankDiagnosticsOption(run, optionName, optionValue);

        // then
        assertBlankDiagnosticsOptionRejected(run, result, optionName);
    }

    @MethodSource("blankDiagnosticsPathOptions")
    @ParameterizedTest(name = "{0} {1}")
    void diagnosticsRejectsBlankPathOptionsWithoutRenderingReport(String optionName, String optionValue)
            throws Exception {
        // given
        BlankDiagnosticsOptionRun run = blankDiagnosticsOptionRun("blank-diagnostics-path-workdir");

        // when
        MainProcessResult result = runBlankDiagnosticsOption(run, optionName, optionValue);

        // then
        assertBlankDiagnosticsOptionRejected(run, result, optionName);
    }

    private BlankDiagnosticsOptionRun blankDiagnosticsOptionRun(String workingDirectoryName) throws IOException {
        Path workingDir = tempDir.resolve(workingDirectoryName);
        Path diagnosticsKey = workingDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME);
        Files.createDirectories(workingDir);
        Path output = workingDir.resolve("diagnostics.md");
        return new BlankDiagnosticsOptionRun(workingDir, diagnosticsKey, output);
    }

    private MainProcessResult runBlankDiagnosticsOption(
            BlankDiagnosticsOptionRun run, String optionName, String optionValue) throws Exception {
        return runMainProcess(
                run.workingDir(),
                Map.of(),
                List.of(),
                "diagnostics",
                optionName,
                optionValue,
                "--output",
                run.output().toString());
    }

    private static void assertBlankDiagnosticsOptionRejected(
            BlankDiagnosticsOptionRun run, MainProcessResult result, String optionName) {
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.stdout()).doesNotContain("# Symphony for Trello Diagnostics", "Diagnostics written");
        assertThat(result.stderr())
                .contains("setup_failed code=setup_invalid_arguments", optionName + " must not be empty.")
                .doesNotContain("Troubleshooting report written", DiagnosticsTokenHasher.KEY_FILE_NAME);
        assertThat(run.diagnosticsKey()).doesNotExist();
        assertThat(run.output()).doesNotExist();
    }

    private record BlankDiagnosticsOptionRun(Path workingDir, Path diagnosticsKey, Path output) {}

    @Test
    void diagnosticsWithoutConfigDirCreatesTokenKeyInDefaultWorkingDirectory() throws Exception {
        // given
        Path workingDir = tempDir.resolve("default-config-workdir");
        Path diagnosticsKey = workingDir.resolve(DiagnosticsTokenHasher.KEY_FILE_NAME);
        Files.createDirectories(workingDir);

        // when
        MainProcessResult result = runMainProcess(workingDir, Map.of(), List.of(), "diagnostics");

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.stdout())
                .contains("# Symphony for Trello Diagnostics", "diagnostics_token_key:** local")
                .doesNotContain(DiagnosticsTokenHasher.KEY_FILE_NAME, workingDir.toString());
        assertThat(result.stderr()).isEmpty();
        assertThat(diagnosticsKey).isRegularFile();
    }

    private static Stream<Arguments> blankDiagnosticsSelectors() {
        return Stream.of(
                Arguments.of("--board", ""),
                Arguments.of("--board", "   "),
                Arguments.of("--workflow", ""),
                Arguments.of("--workflow", "   "));
    }

    private static Stream<Arguments> blankDiagnosticsPathOptions() {
        return Stream.of(
                Arguments.of("--config-dir", ""),
                Arguments.of("--config-dir", "   "),
                Arguments.of("--state-home", ""),
                Arguments.of("--state-home", "   "),
                Arguments.of("--workspace-root", ""),
                Arguments.of("--workspace-root", "   "),
                Arguments.of("--manifest", ""),
                Arguments.of("--manifest", "   "));
    }

    @MethodSource("missingWorkerCredentialDotenvContents")
    @ParameterizedTest(name = "{0}")
    void startReportsMissingWorkerCredentialsBeforeLaunchingWorker(String scenario, String dotenvContent)
            throws Exception {
        // given
        Path configDir = tempDir.resolve("start-missing-credentials-config");
        Path workspaceRoot = tempDir.resolve("start-missing-credentials-workspaces");
        Path stateHome = tempDir.resolve("start-missing-credentials-state");
        Path appHome = tempDir.resolve("start-missing-credentials-app");
        Path workflow = configDir.resolve("WORKFLOW.queue.md");
        Path env = configDir.resolve(".env");
        Files.createDirectories(configDir);
        Files.writeString(
                workflow, TestWorkflows.workflowWithBoardAndPort("board-start-id", 19192), StandardCharsets.UTF_8);
        Files.writeString(env, dotenvContent, StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "board-start-id",
                        "board-start-key",
                        "Queue",
                        "https://trello.com/b/board-start-key/queue",
                        workflow,
                        env,
                        workspaceRoot,
                        19192,
                        false,
                        List.of(),
                        false))));

        // when
        MainProcessResult result = runMainProcessWithoutTrelloCredentials(
                tempDir,
                "start",
                "--config-dir",
                configDir.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--state-home",
                stateHome.toString(),
                "--app-home",
                appHome.toString(),
                "--board",
                "Queue",
                "--env",
                env.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.stdout()).doesNotContain("Started Symphony", "Troubleshooting report written");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_worker_missing_trello_credentials",
                        "Missing Trello credentials for worker start.",
                        "Next step: Set these Trello credential variables",
                        "TRELLO_API_KEY=<your Trello API key>",
                        "TRELLO_API_TOKEN=<your Trello token>",
                        "File:",
                        env.toAbsolutePath().normalize().toString())
                .doesNotContain("--key", "--token", "referenced by the workflow");
    }

    private static Stream<Arguments> missingWorkerCredentialDotenvContents() {
        return Stream.of(
                Arguments.of("missing dotenv values", "# no Trello credentials\n"),
                Arguments.of(
                        "empty dotenv values",
                        """
                        TRELLO_API_KEY=
                        TRELLO_API_TOKEN=
                        """),
                Arguments.of("whitespace-only dotenv values", "TRELLO_API_KEY=   \nTRELLO_API_TOKEN=   \n"));
    }

    @Test
    void startRejectsSpecialFileEnvPathBeforeLaunchingWorker() throws Exception {
        // given
        assumeTrue(!javaExecutable().endsWith(".exe"), "POSIX FIFO support is required");
        Path configDir = tempDir.resolve("start-special-env-config");
        Path privateDir = tempDir.resolve("Jane Doe");
        Path workspaceRoot = tempDir.resolve("start-special-env-workspaces");
        Path stateHome = tempDir.resolve("start-special-env-state");
        Path appHome = tempDir.resolve("start-special-env-app");
        Path workflow = configDir.resolve("WORKFLOW.queue.md");
        Path env = privateDir.resolve(".env.fifo");
        Files.createDirectories(configDir);
        Files.createDirectories(privateDir);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "board-start-id"
                server:
                  port: $WORKER_PORT
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        createFifo(env, tempDir);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "board-start-id",
                        "board-start-key",
                        "Queue",
                        "https://trello.com/b/board-start-key/queue",
                        workflow,
                        env,
                        workspaceRoot,
                        19193,
                        false,
                        List.of(),
                        false))));

        // when
        MainProcessResult result =
                runStartWithoutTrelloCredentials(configDir, workspaceRoot, stateHome, appHome, workflow, env);

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.stdout()).doesNotContain("Started Symphony", "Troubleshooting report written");
        assertThat(result.stderr())
                .contains("setup_failed code=setup_invalid_arguments", "--env must point to a regular dotenv file.")
                .doesNotContain(
                        "Troubleshooting report written",
                        env.toString(),
                        privateDir.toString(),
                        tempDir.toString(),
                        "Jane Doe");
        assertThat(stateHome).doesNotExist();
    }

    @Test
    void startRejectsDirectoryEnvPathBeforeLaunchingWorker() throws Exception {
        // given
        Path configDir = tempDir.resolve("start-directory-env-config");
        Path workspaceRoot = tempDir.resolve("start-directory-env-workspaces");
        Path stateHome = tempDir.resolve("start-directory-env-state");
        Path appHome = tempDir.resolve("start-directory-env-app");
        Path workflow = configDir.resolve("WORKFLOW.queue.md");
        Path envDirectory = tempDir.resolve("start-directory-env");
        Files.createDirectories(configDir);
        Files.createDirectories(workspaceRoot);
        Files.createDirectories(envDirectory);
        Files.writeString(
                workflow, TestWorkflows.workflowWithBoardAndPort("board-start-id", 19194), StandardCharsets.UTF_8);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(new ConnectedBoard(
                        "board-start-id",
                        "board-start-key",
                        "Queue",
                        "https://trello.com/b/board-start-key/queue",
                        workflow,
                        envDirectory,
                        workspaceRoot,
                        19194,
                        false,
                        List.of(),
                        false))));

        // when
        MainProcessResult result =
                runStartWithoutTrelloCredentials(configDir, workspaceRoot, stateHome, appHome, workflow, envDirectory);

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.stdout()).doesNotContain("Started Symphony", "Troubleshooting report written");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_invalid_arguments",
                        "--env must point to a dotenv file path, not a directory.")
                .doesNotContain("Troubleshooting report written", envDirectory.toString(), tempDir.toString());
    }

    @Test
    void startRejectsSpecialFileWorkflowBeforeLaunchingWorker() throws Exception {
        // given
        assumeTrue(!javaExecutable().endsWith(".exe"), "POSIX FIFO support is required");
        Path configDir = tempDir.resolve("start-special-workflow-config");
        Path privateDir = tempDir.resolve("Jane Doe");
        Path workspaceRoot = tempDir.resolve("start-special-workflow-workspaces");
        Path stateHome = tempDir.resolve("start-special-workflow-state");
        Path appHome = tempDir.resolve("start-special-workflow-app");
        Path workflow = privateDir.resolve("WORKFLOW.queue.fifo.md");
        Path env = configDir.resolve(".env");
        Files.createDirectories(configDir);
        Files.createDirectories(privateDir);
        Files.writeString(env, TestEnv.trelloCredentials("dummy", "dummy"), StandardCharsets.UTF_8);
        createFifo(workflow, tempDir);

        // when
        MainProcessResult result =
                runStartWithoutTrelloCredentials(configDir, workspaceRoot, stateHome, appHome, workflow, env);

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.stdout()).doesNotContain("Started Symphony", "Troubleshooting report written");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_workflow_invalid",
                        "workflow path is not a regular workflow file",
                        "Fix the workflow front matter")
                .doesNotContain("Troubleshooting report written");
        assertThat(stateHome).doesNotExist();
    }

    @ParameterizedTest(name = "{0} rejects special workflow files")
    @ValueSource(strings = {"logs", "status"})
    void lifecycleCommandsRejectSpecialFileWorkflowBeforeReading(String command) throws Exception {
        // given
        assumeTrue(!javaExecutable().endsWith(".exe"), "POSIX FIFO support is required");
        Path configDir = tempDir.resolve(command + "-special-workflow-config");
        Path privateDir = tempDir.resolve("Jane Doe " + command);
        Path workspaceRoot = tempDir.resolve(command + "-special-workflow-workspaces");
        Path stateHome = tempDir.resolve(command + "-special-workflow-state");
        Path workflow = privateDir.resolve("WORKFLOW.queue.fifo.md");
        Files.createDirectories(configDir);
        Files.createDirectories(privateDir);
        createFifo(workflow, tempDir);

        // when
        MainProcessResult result = runMainProcessWithoutTrelloCredentials(
                tempDir,
                command,
                "--config-dir",
                configDir.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--state-home",
                stateHome.toString(),
                "--workflow",
                workflow.toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.stdout()).doesNotContain("Troubleshooting report written");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_invalid_arguments",
                        "--workflow must point to a regular workflow file.")
                .doesNotContain(
                        "Troubleshooting report written",
                        workflow.toString(),
                        privateDir.toString(),
                        tempDir.toString(),
                        "Jane Doe");
    }

    @MethodSource("directCommandHelp")
    @ParameterizedTest(name = "{0} help")
    void printsDirectCommandHelp(String command, String expectedUsage) {
        // given

        // when
        CliRunResult result = runCli(command, "--help");

        // then
        result.assertSuccess().stdoutContains(expectedUsage).stderrEmpty();
    }

    @MethodSource("versionCommands")
    @ParameterizedTest(name = "{0} version")
    void printsVersionForCommands(String[] command) {
        // given

        // when
        CliRunResult result = runCli(command);

        // then
        result.assertSuccess().stdoutContains("symphony-trello").stderrEmpty();
    }

    @Test
    void listsWorkspacesFromCommandLineCredentials() {
        // given

        // when
        CliRunResult result = runCli("list-workspaces", "--endpoint", endpoint(), "--key", "key", "--token", "token");

        // then
        result.assertSuccess()
                .stdoutContains("Trello workspaces:", "workspace-1", "Engineering")
                .stderrEmpty();
    }

    @Test
    void listWorkspacesNormalizesRootEndpointToTrelloRestApiBase() {
        // given

        // when
        CliRunResult result =
                runCli("list-workspaces", "--endpoint", endpointRoot(), "--key", "key", "--token", "token");

        // then
        result.assertSuccess()
                .stdoutContains("Trello workspaces:", "workspace-1", "Engineering")
                .stderrEmpty();
        assertThat(workspaceLookups).hasValue(1);
    }

    @MethodSource("credentialControlCharacterCliScenarios")
    @ParameterizedTest(name = "{0}")
    void listWorkspacesRejectsControlCharactersInCredentialsBeforeHttpHeaderConstruction(
            String scenario, String optionName, String apiKey, String apiToken) throws Exception {
        // given
        Path workingDir = tempDir.resolve("credential-control-character-workdir");
        Files.createDirectories(workingDir);

        // when
        MainProcessResult result = runMainProcess(
                workingDir,
                Map.of(),
                List.of(),
                "list-workspaces",
                "--endpoint",
                "http://127.0.0.1:9",
                "--key",
                apiKey,
                "--token",
                apiToken);

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(2);
        assertThat(result.stdout()).doesNotContain("Troubleshooting report written");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_invalid_arguments",
                        optionName + " must not contain control characters.")
                .doesNotContain(
                        "OAuth",
                        "oauth_consumer_key",
                        "oauth_token",
                        apiKey,
                        apiToken,
                        "Troubleshooting report written");
    }

    private static Stream<Arguments> credentialControlCharacterCliScenarios() {
        return Stream.of(
                Arguments.of("api key contains newline", "Trello API key", "k\ney", "token"),
                Arguments.of("api token contains newline", "Trello API token", "key", "t\noken"),
                Arguments.of("api key contains tab", "Trello API key", "k\tey", "token"),
                Arguments.of("api token contains tab", "Trello API token", "key", "t\token"));
    }

    @MethodSource("ch.fmartin.symphony.trello.testsupport.TestEndpointValues#invalidTrelloApiBaseEndpoints")
    @ParameterizedTest(name = "{0}")
    void listWorkspacesRejectsInvalidEndpointsBeforeTrelloRequest(String name, String endpoint) {
        // given

        // when
        CliRunResult result = runCli("list-workspaces", "--endpoint", endpoint, "--key", "key", "--token", "token");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "--endpoint must point to the Trello REST API base, for example https://api.trello.com/1")
                .stderrDoesNotContain(endpoint, "Troubleshooting report written")
                .stdoutDoesNotContain("Trello workspaces:");
        assertThat(workspaceLookups).hasValue(0);
    }

    @Test
    void listWorkspacesTreatsUnreachableEndpointAsExpectedFailureWithoutReport() {
        // given
        String endpoint = "http://127.0.0.1:" + firstAvailableManagedPort();

        // when
        CliRunResult result = runCli("list-workspaces", "--endpoint", endpoint, "--key", "key", "--token", "token");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=trello_api_request message=Trello request failed",
                        "Next step: Check the Trello API endpoint URL and network connection")
                .stderrDoesNotContain("Troubleshooting report written", "Open a GitHub issue", endpoint)
                .stdoutDoesNotContain("Trello workspaces:");
        assertThat(workspaceLookups).hasValue(0);
    }

    @Test
    void listWorkspacesTreatsMalformedTrelloPayloadAsUnexpectedFailureWithReport() {
        // given
        trello.remove("/1/members/me/organizations").on("/1/members/me/organizations", exchange -> {
            workspaceLookups.incrementAndGet();
            respond(exchange, "<html>not json</html>");
        });

        // when
        CliRunResult result = runCli("list-workspaces", "--endpoint", endpoint(), "--key", "key", "--token", "token");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=trello_unknown_payload",
                        "Trello response payload could not be parsed",
                        "Troubleshooting report written")
                .stdoutDoesNotContain("Trello workspaces:");
        assertThat(workspaceLookups).hasValue(1);
    }

    @Test
    void newBoardTreatsDroppedPostResponseAsUnknownWriteOutcomeWithoutWritingWorkflow() throws Exception {
        // given
        Path workflow = tempDir.resolve("dropped-board-post.WORKFLOW.md");
        Path manifest = tempDir.resolve(ConnectedBoardManifest.FILE_NAME);
        AtomicInteger boardCreateRequests = new AtomicInteger();
        trello.remove("/1/boards/").on("/1/boards/", exchange -> {
            boardCreateRequests.incrementAndGet();
            createdBoardName.set(query(exchange).get("name"));
            exchange.close();
        });

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Potentially Created Board",
                "--workflow",
                workflow.toString(),
                "--manifest",
                manifest.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=trello_write_outcome_unknown",
                        "Inspect Trello before retrying setup",
                        "Next step: Check Trello for any board or list that may already have been created")
                .stderrDoesNotContain("Troubleshooting report written", workflow.toString(), manifest.toString())
                .stdoutDoesNotContain("Created Trello board", "Wrote workflow");
        assertThat(boardCreateRequests).hasValue(1);
        assertThat(createdBoardName).hasValue("Potentially Created Board");
        assertThat(workflow).doesNotExist();
        assertThat(manifest).doesNotExist();
    }

    @Test
    void newBoardTreatsMalformedPostResponseAsUnknownWriteOutcomeWithoutWritingWorkflow() throws Exception {
        // given
        Path workflow = tempDir.resolve("malformed-board-post.WORKFLOW.md");
        Path manifest = tempDir.resolve(ConnectedBoardManifest.FILE_NAME);
        AtomicInteger boardCreateRequests = new AtomicInteger();
        trello.remove("/1/boards/").on("/1/boards/", exchange -> {
            boardCreateRequests.incrementAndGet();
            createdBoardName.set(query(exchange).get("name"));
            respond(exchange, "<html>not json</html>");
        });

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Malformed Response Board",
                "--workflow",
                workflow.toString(),
                "--manifest",
                manifest.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=trello_write_outcome_unknown", "Inspect Trello before retrying setup")
                .stderrDoesNotContain("trello_unknown_payload", "Troubleshooting report written")
                .stdoutDoesNotContain("Created Trello board", "Wrote workflow");
        assertThat(boardCreateRequests).hasValue(1);
        assertThat(workflow).doesNotExist();
        assertThat(manifest).doesNotExist();
    }

    @Test
    void newBoardTreatsIncompletePostResponseAsUnknownWriteOutcomeWithoutWritingWorkflow() throws Exception {
        // given
        Path workflow = tempDir.resolve("incomplete-board-post.WORKFLOW.md");
        Path manifest = tempDir.resolve(ConnectedBoardManifest.FILE_NAME);
        AtomicInteger boardCreateRequests = new AtomicInteger();
        trello.remove("/1/boards/").on("/1/boards/", exchange -> {
            boardCreateRequests.incrementAndGet();
            createdBoardName.set(query(exchange).get("name"));
            respond(exchange, "{}");
        });

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Incomplete Response Board",
                "--workflow",
                workflow.toString(),
                "--manifest",
                manifest.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=trello_write_outcome_unknown", "Inspect Trello before retrying setup")
                .stderrDoesNotContain("trello_unknown_payload", "Troubleshooting report written")
                .stdoutDoesNotContain("Created Trello board", "Wrote workflow");
        assertThat(boardCreateRequests).hasValue(1);
        assertThat(workflow).doesNotExist();
        assertThat(manifest).doesNotExist();
    }

    @Test
    void newBoardTreatsPostServerErrorAsUnknownWriteOutcomeWithoutWritingWorkflow() throws Exception {
        // given
        Path workflow = tempDir.resolve("server-error-board-post.WORKFLOW.md");
        Path manifest = tempDir.resolve(ConnectedBoardManifest.FILE_NAME);
        AtomicInteger boardCreateRequests = new AtomicInteger();
        trello.remove("/1/boards/").on("/1/boards/", exchange -> {
            boardCreateRequests.incrementAndGet();
            createdBoardName.set(query(exchange).get("name"));
            respond(exchange, 500, "temporary Trello server error");
        });

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Server Error Board",
                "--workflow",
                workflow.toString(),
                "--manifest",
                manifest.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=trello_write_outcome_unknown", "Inspect Trello before retrying setup")
                .stderrDoesNotContain(
                        "setup_failed code=trello_api_status",
                        "temporary Trello server error",
                        "Troubleshooting report written")
                .stdoutDoesNotContain("Created Trello board", "Wrote workflow");
        assertThat(boardCreateRequests).hasValue(1);
        assertThat(createdBoardName).hasValue("Server Error Board");
        assertThat(workflow).doesNotExist();
        assertThat(manifest).doesNotExist();
    }

    @Test
    void createsRecommendedBoardAndPrintsNextSteps() throws Exception {
        // given
        Path workflow = tempDir.resolve("generated workflow.WORKFLOW.md");
        Path env = tempDir.resolve(".env.next-steps");
        int expectedPort = firstAvailableManagedPort();

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Symphony Work Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString(),
                "--server-port",
                String.valueOf(expectedPort));

        // then
        result.assertSuccess();
        assertThat(createdBoardName).hasValue("Symphony Work Queue");
        assertThat(createdLists)
                .containsExactly(
                        "Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Merging", "Done");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("board_id: \"abc123\"")
                .contains("port: " + expectedPort);
        ConnectedBoardManifest manifest =
                new ConnectedBoardRepository(tempDir.resolve(ConnectedBoardManifest.FILE_NAME)).load();
        assertThat(manifest.boards()).singleElement().satisfies(board -> {
            assertThat(board.boardId()).isEqualTo("board-1");
            assertThat(board.boardKey()).isEqualTo("abc123");
            assertThat(board.boardName()).isEqualTo("Symphony Work Queue");
            assertThat(board.boardUrl()).isEqualTo("https://trello.com/b/abc123/board");
            assertThat(board.workflowPath()).isEqualTo(workflow.toAbsolutePath().normalize());
            assertThat(board.envPath()).isEqualTo(env.toAbsolutePath().normalize());
            assertThat(board.workspaceRoot())
                    .isEqualTo(TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT
                            .toAbsolutePath()
                            .normalize());
            assertThat(board.serverPort()).isEqualTo(expectedPort);
        });
        assertThat(result.stdout())
                .contains(
                        "Created Trello board: \"Symphony Work Queue\"",
                        "Board identifier for WORKFLOW.md: abc123",
                        "Wrote workflow:",
                        "HTTP status port: " + expectedPort,
                        "symphony-trello start --env "
                                + shellQuote(env.toAbsolutePath().normalize().toString())
                                + " --workflow "
                                + shellQuote(
                                        workflow.toAbsolutePath().normalize().toString()),
                        "symphony-trello logs --workflow "
                                + shellQuote(
                                        workflow.toAbsolutePath().normalize().toString()))
                .doesNotContain("./mvnw quarkus:dev");
    }

    @Test
    void newBoardRejectsReservedServerPortBeforeCreatingTrelloBoard() throws Exception {
        // given
        Path workflow = tempDir.resolve("reserved-port.WORKFLOW.md");
        Path env = tempDir.resolve(".env.reserved-port");

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "direct-key",
                "--token",
                "direct-token",
                "--name",
                "Reserved Port Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString(),
                "--server-port",
                "1");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_server_port",
                        "--server-port must be between 1024 and 65535 for local HTTP status.")
                .stdoutDoesNotContain(
                        "Created Trello board", "Saving Trello credentials", "Troubleshooting report written");
        assertThat(createdBoardName.get()).isNull();
        assertThat(workspaceLookups).hasValue(0);
        assertThat(createdLists).isEmpty();
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
    }

    @Test
    void newBoardRejectsManifestReservedServerPortBeforeCreatingTrelloBoard() throws Exception {
        // given
        Path workflow = tempDir.resolve("manifest-port-conflict.WORKFLOW.md");
        Path env = tempDir.resolve(".env.manifest-port-conflict");
        Path existingWorkflow = tempDir.resolve("existing-port-conflict.WORKFLOW.md");
        new ConnectedBoardRepository(tempDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(connectedBoard(existingWorkflow, 18081))));

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "direct-key",
                "--token",
                "direct-token",
                "--name",
                "Manifest Port Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString(),
                "--server-port",
                "18081");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_server_port_conflict",
                        "--server-port 18081 is already reserved by another connected workflow.")
                .stdoutDoesNotContain(
                        "Created Trello board", "Saving Trello credentials", "Troubleshooting report written");
        assertThat(createdBoardName.get()).isNull();
        assertThat(workspaceLookups).hasValue(0);
        assertThat(createdLists).isEmpty();
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
    }

    @Test
    void newBoardRejectsLiveServerPortBeforeCreatingTrelloBoard() throws Exception {
        // given
        Path workflow = tempDir.resolve("live-port-conflict.WORKFLOW.md");
        Path env = tempDir.resolve(".env.live-port-conflict");
        HttpServer listeningServer = startLoopbackServer();
        int listeningPort = listeningServer.getAddress().getPort();

        // when
        CliRunResult result;
        try {
            result = runCli(
                    "new-board",
                    "--endpoint",
                    endpoint(),
                    "--key",
                    "direct-key",
                    "--token",
                    "direct-token",
                    "--name",
                    "Live Port Queue",
                    "--workflow",
                    workflow.toString(),
                    "--manifest",
                    tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                    "--env",
                    env.toString(),
                    "--server-port",
                    String.valueOf(listeningPort));
        } finally {
            listeningServer.stop(0);
        }

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_server_port_conflict",
                        "--server-port %d is already in use on 127.0.0.1.".formatted(listeningPort))
                .stdoutDoesNotContain(
                        "Created Trello board", "Saving Trello credentials", "Troubleshooting report written");
        assertThat(createdBoardName.get()).isNull();
        assertThat(workspaceLookups).hasValue(0);
        assertThat(createdLists).isEmpty();
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
    }

    @Test
    void importBoardRejectsReservedServerPortBeforeContactingTrello() throws Exception {
        // given
        Path workflow = tempDir.resolve("import-reserved-port.WORKFLOW.md");
        Path env = tempDir.resolve(".env.import-reserved-port");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "direct-key",
                "--token",
                "direct-token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString(),
                "--server-port",
                "2");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_server_port",
                        "--server-port must be between 1024 and 65535 for local HTTP status.")
                .stdoutDoesNotContain(
                        "Imported Trello board", "Saving Trello credentials", "Troubleshooting report written");
        assertThat(boardInfoLookups).hasValue(0);
        assertThat(createdLists).isEmpty();
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
    }

    @Test
    void importBoardRejectsManifestReservedServerPortBeforeContactingTrello() throws Exception {
        // given
        Path workflow = tempDir.resolve("import-manifest-port-conflict.WORKFLOW.md");
        Path env = tempDir.resolve(".env.import-manifest-port-conflict");
        Path existingWorkflow = tempDir.resolve("existing-import-port-conflict.WORKFLOW.md");
        new ConnectedBoardRepository(tempDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(connectedBoard(existingWorkflow, 18082))));

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "direct-key",
                "--token",
                "direct-token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString(),
                "--server-port",
                "18082");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_server_port_conflict",
                        "--server-port 18082 is already reserved by another connected workflow.")
                .stdoutDoesNotContain(
                        "Imported Trello board", "Saving Trello credentials", "Troubleshooting report written");
        assertThat(boardInfoLookups).hasValue(0);
        assertThat(createdLists).isEmpty();
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
    }

    @Test
    void importBoardTreatsUnreachableEndpointAsExpectedFailureWithoutReport() throws Exception {
        // given
        Path workflow = tempDir.resolve("unreachable-import.WORKFLOW.md");
        Path env = tempDir.resolve(".env.unreachable-import");
        String endpoint = "http://127.0.0.1:" + firstAvailableManagedPort();

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint,
                "--key",
                "direct-key",
                "--token",
                "direct-token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=trello_api_request message=Trello request failed",
                        "Next step: Check the Trello API endpoint URL and network connection")
                .stderrDoesNotContain(
                        "Troubleshooting report written",
                        "Open a GitHub issue",
                        endpoint,
                        "direct-key",
                        "direct-token",
                        workflow.toString(),
                        env.toString())
                .stdoutDoesNotContain("Imported Trello board", "Saving Trello credentials", "Wrote workflow");
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
    }

    @Test
    void importBoardRejectsLiveServerPortBeforeContactingTrello() throws Exception {
        // given
        Path workflow = tempDir.resolve("import-live-port-conflict.WORKFLOW.md");
        Path env = tempDir.resolve(".env.import-live-port-conflict");
        HttpServer listeningServer = startLoopbackServer();
        int listeningPort = listeningServer.getAddress().getPort();

        // when
        CliRunResult result;
        try {
            result = runCli(
                    "import-board",
                    "--endpoint",
                    endpoint(),
                    "--key",
                    "direct-key",
                    "--token",
                    "direct-token",
                    "--board",
                    "https://trello.com/b/input/existing-board",
                    "--active",
                    "Queue for Codex",
                    "--terminal",
                    "Released",
                    "--workflow",
                    workflow.toString(),
                    "--manifest",
                    tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                    "--env",
                    env.toString(),
                    "--server-port",
                    String.valueOf(listeningPort));
        } finally {
            listeningServer.stop(0);
        }

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_server_port_conflict",
                        "--server-port %d is already in use on 127.0.0.1.".formatted(listeningPort))
                .stdoutDoesNotContain(
                        "Imported Trello board", "Saving Trello credentials", "Troubleshooting report written");
        assertThat(boardInfoLookups).hasValue(0);
        assertThat(createdLists).isEmpty();
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
    }

    @Test
    void importBoardForceAllowsCurrentManagedWorkerServerPortForSameWorkflow() throws Exception {
        // given
        Path workflow = tempDir.resolve("force-existing-live-port.WORKFLOW.md");
        Path env = tempDir.resolve(".env.force-existing-live-port");
        HttpServer listeningServer = startLoopbackServer();
        int listeningPort = listeningServer.getAddress().getPort();
        Files.writeString(
                workflow, TestWorkflows.workflowWithBoardAndPort("board-1", listeningPort), StandardCharsets.UTF_8);
        ConnectedBoard previousBoard = ConnectedBoardBuilder.connectedBoard(
                        workflow.toAbsolutePath().normalize())
                .withBoardId("board-1")
                .withBoardKey("SYNTH002")
                .withBoardName("Existing Board")
                .withBoardUrl("https://trello.com/b/SYNTH002/board")
                .withEnvPath(env.toAbsolutePath().normalize())
                .withWorkspaceRoot(
                        TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT.toAbsolutePath().normalize())
                .withServerPort(listeningPort)
                .withGithubEnabled(true)
                .build();
        new ConnectedBoardRepository(tempDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(previousBoard)));
        LocalWorkerManager workerManager = mock();
        when(workerManager.canStopRunningWorker(any(LocalWorkerPaths.class), eq(previousBoard)))
                .thenReturn(true);
        TrelloBoardSetup boardSetup = new TrelloBoardSetup(
                new ObjectMapper(),
                () -> CodexModelSelectionDefaults.of(TrelloBoardSetup.CodexModelDefaults.fallback()));

        // when
        CliRunResult result;
        try {
            result = runCli(
                    boardSetup,
                    workerManager,
                    "import-board",
                    "--endpoint",
                    endpoint(),
                    "--key",
                    "key",
                    "--token",
                    "token",
                    "--board",
                    "https://trello.com/b/input/existing-board",
                    "--active",
                    "Queue for Codex",
                    "--terminal",
                    "Released",
                    "--workflow",
                    workflow.toString(),
                    "--manifest",
                    tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                    "--env",
                    env.toString(),
                    "--server-port",
                    String.valueOf(listeningPort),
                    "--force");
        } finally {
            listeningServer.stop(0);
        }

        // then
        result.assertSuccess()
                .stdoutContains("Imported Trello board: \"Existing Board\"", "HTTP status port: " + listeningPort);
        verify(workerManager, times(2)).canStopRunningWorker(any(LocalWorkerPaths.class), eq(previousBoard));
        verify(workerManager).stop(any(LocalWorkerPaths.class), eq(previousBoard), any(PrintStream.class));
    }

    @Test
    void newBoardPreflightsConnectedBoardManifestBeforeCreatingTrelloBoard() throws Exception {
        // given
        Path workflow = tempDir.resolve("manifest-preflight.WORKFLOW.md");
        Path env = tempDir.resolve(".env.manifest-preflight");
        Files.writeString(tempDir.resolve(ConnectedBoardManifest.FILE_NAME), "{not-json", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Manifest Preflight Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_manifest_unavailable",
                        "Connected-board manifest is not valid JSON",
                        "Next step: Check that the workflow directory is writable and "
                                + ConnectedBoardManifest.FILE_NAME + " is valid JSON.")
                .stdoutDoesNotContain(
                        "Created Trello board", "Saving Trello credentials", "Troubleshooting report written");
        assertThat(createdBoardName.get()).isNull();
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
    }

    @Test
    void newBoardPreflightsUnusableConnectedBoardManifestBeforeCreatingTrelloBoard() throws Exception {
        // given
        Path workflow = tempDir.resolve("unusable-manifest-preflight.WORKFLOW.md");
        Path env = tempDir.resolve(".env.unusable-manifest-preflight");
        Files.writeString(
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME), "{\"boards\":[{}]}", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Unusable Manifest Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_manifest_unavailable",
                        "Connected-board manifest is not valid connected-board JSON",
                        "Next step: Check that the workflow directory is writable and "
                                + ConnectedBoardManifest.FILE_NAME + " is valid JSON.")
                .stdoutDoesNotContain(
                        "Created Trello board", "Saving Trello credentials", "Troubleshooting report written");
        assertThat(createdBoardName.get()).isNull();
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"boards\":null}"})
    void newBoardRejectsMissingOrNullBoardsManifestFieldBeforeCreatingTrelloBoard(String manifestContent)
            throws Exception {
        // given
        Path workflow = tempDir.resolve("null-boards-preflight.WORKFLOW.md");
        Path env = tempDir.resolve(".env.null-boards-preflight");
        Path manifest = tempDir.resolve(ConnectedBoardManifest.FILE_NAME);
        Files.writeString(manifest, manifestContent, StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Null Boards Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                manifest.toString(),
                "--env",
                env.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_manifest_unavailable",
                        "Connected-board manifest is not valid connected-board JSON")
                .stderrDoesNotContain(
                        "NullPointerException",
                        "Cannot invoke",
                        "JsonParseException",
                        "MismatchedInputException",
                        "com.fasterxml")
                .stdoutDoesNotContain("Created Trello board", "Workflow written", "Troubleshooting report written");
        assertThat(createdBoardName.get()).isNull();
        assertThat(workflow).doesNotExist();
        assertThat(env).doesNotExist();
        assertThat(Files.readString(manifest, StandardCharsets.UTF_8)).isEqualTo(manifestContent);
    }

    @Test
    void newBoardPersistsAbsolutePathsWhenWorkflowAndEnvOptionsAreRelative() throws Exception {
        // given
        Path relativeDirectory = Path.of(
                "target", "trello-board-setup-main-test", tempDir.getFileName().toString());
        Path relativeWorkflow = relativeDirectory.resolve("relative.WORKFLOW.md");
        Path relativeEnv = relativeDirectory.resolve(".env.relative");
        Files.createDirectories(relativeDirectory);

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Relative Paths Queue",
                "--workflow",
                relativeWorkflow.toString(),
                "--manifest",
                relativeDirectory.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                relativeEnv.toString());

        // then
        result.assertSuccess();
        ConnectedBoardManifest manifest =
                new ConnectedBoardRepository(relativeDirectory.resolve(ConnectedBoardManifest.FILE_NAME)).load();
        assertThat(manifest.boards()).singleElement().satisfies(board -> {
            assertThat(board.workflowPath())
                    .isEqualTo(relativeWorkflow.toAbsolutePath().normalize());
            assertThat(board.envPath()).isEqualTo(relativeEnv.toAbsolutePath().normalize());
            assertThat(board.workspaceRoot())
                    .isEqualTo(TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT
                            .toAbsolutePath()
                            .normalize());
        });
    }

    @Test
    void importBoardPersistsExternalWorkflowIntoInstalledManifestByDefault() throws Exception {
        // given
        Path externalDir = tempDir.resolve("external-default-import");
        Files.createDirectories(externalDir);
        Path externalWorkflow = externalDir.resolve("WORKFLOW.external-default.md");
        Path installedConfig = tempDir.resolve("installed-default-config");
        Files.createDirectories(installedConfig);
        Path env = installedConfig.resolve(".env");

        // when
        CliRunResult result = runCliWithEnvironment(
                Map.of("SYMPHONY_TRELLO_CONFIG_DIR", installedConfig.toString()),
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                externalWorkflow.toString(),
                "--env",
                env.toString());

        // then
        result.assertSuccess();
        ConnectedBoardManifest manifest =
                new ConnectedBoardRepository(installedConfig.resolve(ConnectedBoardManifest.FILE_NAME)).load();
        assertThat(manifest.boards()).singleElement().satisfies(board -> assertThat(board.workflowPath())
                .isEqualTo(externalWorkflow.toAbsolutePath().normalize()));
        assertThat(externalDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .as("no stray manifest beside the external workflow")
                .doesNotExist();
    }

    @Test
    void newBoardPersistsExternalWorkflowIntoInstalledManifestByDefault() throws Exception {
        // given
        Path externalDir = tempDir.resolve("external-default-new");
        Files.createDirectories(externalDir);
        Path externalWorkflow = externalDir.resolve("WORKFLOW.external-new.md");
        Path installedConfig = tempDir.resolve("installed-default-new-config");
        Files.createDirectories(installedConfig);
        Path env = installedConfig.resolve(".env");

        // when
        CliRunResult result = runCliWithEnvironment(
                Map.of("SYMPHONY_TRELLO_CONFIG_DIR", installedConfig.toString()),
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "External Default Queue",
                "--workflow",
                externalWorkflow.toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        ConnectedBoardManifest manifest =
                new ConnectedBoardRepository(installedConfig.resolve(ConnectedBoardManifest.FILE_NAME)).load();
        assertThat(manifest.boards()).singleElement().satisfies(board -> assertThat(board.workflowPath())
                .isEqualTo(externalWorkflow.toAbsolutePath().normalize()));
        assertThat(externalDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .as("no stray manifest beside the external workflow")
                .doesNotExist();
    }

    @Test
    void importBoardRejectsDirectoryWorkflowPathAsExpectedInputError() throws Exception {
        // given
        Path workflowDirectory = tempDir.resolve("workflow-dir");
        Files.createDirectories(workflowDirectory);
        Path env = tempDir.resolve(".env.directory-workflow");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflowDirectory.toString(),
                "--env",
                env.toString(),
                "--force");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("setup_failed code=setup_invalid_path", "directory")
                .stderrDoesNotContain("Troubleshooting report written");
    }

    @Test
    void importBoardRejectsWorkflowUnderFileParentWithoutBlamingManifest() throws Exception {
        // given
        Path plainFile = tempDir.resolve("not-a-directory");
        Files.writeString(plainFile, "plain", StandardCharsets.UTF_8);
        Path workflow = plainFile.resolve("WORKFLOW.import.md");
        Path env = tempDir.resolve(".env.file-parent-workflow");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--force");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("setup_failed code=setup_invalid_path", "not a directory")
                .stderrDoesNotContain("setup_manifest_unavailable", "Troubleshooting report written");
    }

    @Test
    void unmatchedArgumentErrorsOmitInternalArgumentIndexes() {
        // given
        String extra = "extra";

        // when
        CliRunResult result = runCli("diagnostics", extra);

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("Unmatched argument: 'extra'")
                .stderrDoesNotContain("at index");
    }

    @Test
    void startHelpDocumentsTheAllOption() {
        // given
        String command = "start";

        // when
        CliRunResult result = runCli(command, "--help");

        // then
        result.assertSuccess().stdoutContains("--all", "Start all connected Trello boards");
    }

    @Test
    void listWorkspacesReadsCredentialsFromExplicitEnvFile() throws Exception {
        // given
        Path env = tempDir.resolve("custom-env-dir").resolve(".env.custom");
        Files.createDirectories(env.getParent());
        Files.writeString(env, TestEnv.trelloCredentials("env-key", "env-token"), StandardCharsets.UTF_8);

        // when
        CliRunResult result =
                runCliWithoutTrelloCredentials("list-workspaces", "--endpoint", endpoint(), "--env", env.toString());

        // then
        result.assertSuccess().stdoutContains("Trello workspaces:", "workspace-1");
        assertThat(workspaceAuthorization.get())
                .contains("oauth_consumer_key=\"env-key\"", "oauth_token=\"env-token\"");
    }

    @Test
    void listWorkspacesReadsCredentialsBehindAByteOrderMark() throws Exception {
        // given
        Path env = tempDir.resolve(".env.bom");
        Files.writeString(env, "\uFEFFTRELLO_API_KEY=bom-key\nTRELLO_API_TOKEN=bom-token\n", StandardCharsets.UTF_8);

        // when
        CliRunResult result =
                runCliWithoutTrelloCredentials("list-workspaces", "--endpoint", endpoint(), "--env", env.toString());

        // then
        result.assertSuccess().stdoutContains("Trello workspaces:");
        assertThat(workspaceAuthorization.get())
                .contains("oauth_consumer_key=\"bom-key\"", "oauth_token=\"bom-token\"");
    }

    @MethodSource("listWorkspacesMissingCredentialSources")
    @ParameterizedTest(name = "{0}")
    void listWorkspacesMissingCredentialsHintUsesSelectedEnvFile(ListWorkspacesMissingCredentialSource credentialSource)
            throws Exception {
        // given
        Path env = credentialSource.envPath(tempDir);
        Files.createDirectories(env.getParent());
        Files.writeString(env, "# no Trello credentials yet\n", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCliWithoutTrelloCredentials(
                credentialSource.commandArgs(endpoint(), env).toArray(String[]::new));

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_missing_api_key",
                        env.toAbsolutePath().normalize().toString())
                .stderrDoesNotContain(
                        LocalEnvironment.defaultDotenv()
                                .toAbsolutePath()
                                .normalize()
                                .toString(),
                        "Troubleshooting report written");
        assertThat(workspaceAuthorization.get())
                .as("the expected local error must fire before any Trello request")
                .isNull();
    }

    private static Stream<ListWorkspacesMissingCredentialSource> listWorkspacesMissingCredentialSources() {
        return Stream.of(ListWorkspacesMissingCredentialSource.values());
    }

    private enum ListWorkspacesMissingCredentialSource {
        EXPLICIT_ENV("explicit --env") {
            @Override
            Path envPath(Path tempDir) {
                return tempDir.resolve("custom-env-dir").resolve(".env.empty");
            }

            @Override
            List<String> commandArgs(String endpoint, Path env) {
                return List.of("list-workspaces", "--endpoint", endpoint, "--env", env.toString());
            }
        },
        CONFIG_DIR_ENV("config-dir .env") {
            @Override
            Path envPath(Path tempDir) {
                return tempDir.resolve("empty-config-dir").resolve(".env");
            }

            @Override
            List<String> commandArgs(String endpoint, Path env) {
                return List.of(
                        "list-workspaces",
                        "--endpoint",
                        endpoint,
                        "--config-dir",
                        env.getParent().toString());
            }
        };

        private final String displayName;

        ListWorkspacesMissingCredentialSource(String displayName) {
            this.displayName = displayName;
        }

        abstract Path envPath(Path tempDir);

        abstract List<String> commandArgs(String endpoint, Path env);

        @Override
        public String toString() {
            return displayName;
        }
    }

    @CsvSource({"$REAL_KEY", "${REAL_KEY}", "${REAL_KEY:-fallback}"})
    @ParameterizedTest
    void rejectsReferenceLookingCredentialFileValuesBeforeAnyTrelloRequest(String dotenvValue) throws Exception {
        // given
        Path env = tempDir.resolve(".env.reference");
        Files.writeString(
                env, "TRELLO_API_KEY=" + dotenvValue + "\nTRELLO_API_TOKEN=real-token\n", StandardCharsets.UTF_8);

        // when
        CliRunResult result =
                runCliWithoutTrelloCredentials("list-workspaces", "--endpoint", endpoint(), "--env", env.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_credentials_environment_reference",
                        "credential file values are used literally",
                        "export TRELLO_API_KEY in the shell environment")
                .stderrDoesNotContain(
                        "trello_auth_failed", "Troubleshooting report written", "export $", "{TRELLO_API_KEY}");
        assertThat(workspaceAuthorization.get())
                .as("the expected local error must fire before any Trello request")
                .isNull();
    }

    @Test
    void directCredentialOptionsWinOverReferenceLookingCredentialFileValues() throws Exception {
        // given
        Path env = tempDir.resolve(".env.reference-overridden");
        Files.writeString(env, "TRELLO_API_KEY=${REAL_KEY}\nTRELLO_API_TOKEN=${REAL_TOKEN}\n", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "list-workspaces",
                "--endpoint",
                endpoint(),
                "--key",
                "direct-key",
                "--token",
                "direct-token",
                "--env",
                env.toString());

        // then
        result.assertSuccess().stdoutContains("Trello workspaces:");
        assertThat(workspaceAuthorization.get())
                .contains("oauth_consumer_key=\"direct-key\"", "oauth_token=\"direct-token\"");
    }

    @Test
    void listWorkspacesReadsCredentialsFromConfigDirEnvFile() throws Exception {
        // given
        Path configDir = tempDir.resolve("installed-config-dir");
        Files.createDirectories(configDir);
        Files.writeString(
                configDir.resolve(".env"),
                "TRELLO_API_KEY=config-dir-key\nTRELLO_API_TOKEN=config-dir-token\n",
                StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCliWithoutTrelloCredentials(
                "list-workspaces", "--endpoint", endpoint(), "--config-dir", configDir.toString());

        // then
        result.assertSuccess().stdoutContains("Trello workspaces:", "workspace-1");
        assertThat(workspaceAuthorization.get())
                .contains("oauth_consumer_key=\"config-dir-key\"", "oauth_token=\"config-dir-token\"");
    }

    @Test
    void explicitEnvFileWinsOverConfigDirCredentials() throws Exception {
        // given
        Path configDir = tempDir.resolve("losing-config-dir");
        Files.createDirectories(configDir);
        Files.writeString(
                configDir.resolve(".env"),
                "TRELLO_API_KEY=config-dir-key\nTRELLO_API_TOKEN=config-dir-token\n",
                StandardCharsets.UTF_8);
        Path env = tempDir.resolve(".env.winning");
        Files.writeString(env, TestEnv.trelloCredentials("env-key", "env-token"), StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCliWithoutTrelloCredentials(
                "list-workspaces",
                "--endpoint",
                endpoint(),
                "--env",
                env.toString(),
                "--config-dir",
                configDir.toString());

        // then
        result.assertSuccess().stdoutContains("Trello workspaces:");
        assertThat(workspaceAuthorization.get())
                .contains("oauth_consumer_key=\"env-key\"", "oauth_token=\"env-token\"");
    }

    @Test
    void directCredentialOptionsWinOverEnvFileAndConfigDirCredentials() throws Exception {
        // given
        Path configDir = tempDir.resolve("overridden-config-dir");
        Files.createDirectories(configDir);
        Files.writeString(
                configDir.resolve(".env"),
                "TRELLO_API_KEY=config-dir-key\nTRELLO_API_TOKEN=config-dir-token\n",
                StandardCharsets.UTF_8);
        Path env = tempDir.resolve(".env.overridden");
        Files.writeString(env, TestEnv.trelloCredentials("env-key", "env-token"), StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "list-workspaces",
                "--endpoint",
                endpoint(),
                "--key",
                "direct-key",
                "--token",
                "direct-token",
                "--env",
                env.toString(),
                "--config-dir",
                configDir.toString());

        // then
        result.assertSuccess().stdoutContains("Trello workspaces:");
        assertThat(workspaceAuthorization.get())
                .contains("oauth_consumer_key=\"direct-key\"", "oauth_token=\"direct-token\"");
    }

    @Test
    void listWorkspacesRejectsConfigDirPointingAtAFileBeforeAnyTrelloRequest() throws Exception {
        // given
        Path notADirectory = tempDir.resolve("config-dir-as-file");
        Files.writeString(notADirectory, "not a directory", StandardCharsets.UTF_8);

        // when
        CliRunResult result =
                runCli("list-workspaces", "--endpoint", endpoint(), "--config-dir", notADirectory.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("setup_failed code=setup_invalid_arguments", "--config-dir must be a directory.")
                .stderrDoesNotContain("Troubleshooting report written");
        assertThat(workspaceAuthorization.get())
                .as("the expected local error must fire before any Trello request")
                .isNull();
    }

    @Test
    void importBoardPersistsExternalWorkflowIntoExplicitManifest() throws Exception {
        // given
        Path externalDir = tempDir.resolve("external-workflows");
        Files.createDirectories(externalDir);
        Path externalWorkflow = externalDir.resolve("WORKFLOW.external-import.md");
        Path installedManifest = tempDir.resolve("installed-config").resolve(ConnectedBoardManifest.FILE_NAME);
        Files.createDirectories(installedManifest.getParent());
        Path env = tempDir.resolve(".env.external-import");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                externalWorkflow.toString(),
                "--manifest",
                installedManifest.toString(),
                "--env",
                env.toString());

        // then
        result.assertSuccess();
        ConnectedBoardManifest manifest = new ConnectedBoardRepository(installedManifest).load();
        assertThat(manifest.boards()).singleElement().satisfies(board -> {
            assertThat(board.boardId()).isEqualTo("board-1");
            assertThat(board.workflowPath())
                    .isEqualTo(externalWorkflow.toAbsolutePath().normalize());
        });
        assertThat(externalDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .as("no stray manifest beside the external workflow")
                .doesNotExist();
    }

    @Test
    void importBoardStopsReplacedManifestWorkflowBeforeSavingReplacement() throws Exception {
        // given
        Path previousWorkflow = tempDir.resolve("previous-existing-board.WORKFLOW.md");
        Path newWorkflow = tempDir.resolve("new-existing-board.WORKFLOW.md");
        Path previousEnv = tempDir.resolve(".env.previous-existing");
        Path newEnv = tempDir.resolve(".env.new-existing");
        Files.writeString(previousWorkflow, "previous workflow", StandardCharsets.UTF_8);
        ConnectedBoard previousBoard = ConnectedBoardBuilder.connectedBoard(
                        previousWorkflow.toAbsolutePath().normalize())
                .withBoardId("board-1")
                .withBoardName("Existing Board")
                .withBoardUrl("https://trello.com/b/SYNTH001/board")
                .withEnvPath(previousEnv.toAbsolutePath().normalize())
                .withWorkspaceRoot(
                        TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT.toAbsolutePath().normalize())
                .withGithubEnabled(true)
                .build();
        new ConnectedBoardRepository(tempDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(previousBoard)));
        LocalWorkerManager workerManager = mock();
        TrelloBoardSetup boardSetup = new TrelloBoardSetup(
                new ObjectMapper(),
                () -> CodexModelSelectionDefaults.of(TrelloBoardSetup.CodexModelDefaults.fallback()));

        // when
        CliRunResult result = runCli(
                boardSetup,
                workerManager,
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                newWorkflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                newEnv.toString(),
                "--force");

        // then
        result.assertSuccess();
        verify(workerManager).stop(any(LocalWorkerPaths.class), eq(previousBoard), any(PrintStream.class));
        verify(workerManager)
                .rotateLogsForReplacedBoards(
                        any(LocalWorkerPaths.class), any(ConnectedBoard.class), eq(List.of(previousBoard)));
        verify(workerManager, never())
                .start(any(LocalWorkerPaths.class), any(ConnectedBoard.class), any(Path.class), any(PrintStream.class));
        ConnectedBoardManifest manifest =
                new ConnectedBoardRepository(tempDir.resolve(ConnectedBoardManifest.FILE_NAME)).load();
        assertThat(manifest.boards()).singleElement().satisfies(board -> {
            assertThat(board.boardId()).isEqualTo("board-1");
            assertThat(board.workflowPath())
                    .isEqualTo(newWorkflow.toAbsolutePath().normalize());
        });
    }

    @Test
    void importBoardRestartsPreviouslyRunningReplacedWorker() throws Exception {
        // given
        ReplacedRunningWorkerFixture fixture = replacedRunningWorkerFixture("restart");

        // when
        CliRunResult run = runForcedImportBoard(fixture);

        // then
        assertThat(run.exitCode()).isZero();
        assertThat(run.stdout())
                .contains(
                        "This update stopped the running worker for \"Existing Board\". Restarting it with the updated workflow.");
        verify(fixture.workerManager())
                .stop(any(LocalWorkerPaths.class), eq(fixture.previousBoard()), any(PrintStream.class));
        ArgumentCaptor<ConnectedBoard> restartedBoard = ArgumentCaptor.forClass(ConnectedBoard.class);
        verify(fixture.workerManager())
                .start(any(LocalWorkerPaths.class), restartedBoard.capture(), any(Path.class), any(PrintStream.class));
        assertThat(restartedBoard.getValue().boardId()).isEqualTo("board-1");
        assertThat(restartedBoard.getValue().workflowPath())
                .isEqualTo(fixture.newWorkflow().toAbsolutePath().normalize());
    }

    @Test
    void importBoardPrintsRecoveryStepWhenReplacedWorkerRestartFails() throws Exception {
        // given
        ReplacedRunningWorkerFixture fixture = replacedRunningWorkerFixture("restart-fail");
        doThrow(new TrelloBoardSetupException("setup_start_unhealthy", "worker did not become healthy"))
                .when(fixture.workerManager())
                .start(any(LocalWorkerPaths.class), any(ConnectedBoard.class), any(Path.class), any(PrintStream.class));

        // when
        CliRunResult run = runForcedImportBoard(fixture);

        // then
        assertThat(run.exitCode()).isZero();
        assertThat(run.stdout())
                .contains(
                        "Could not restart the worker for \"Existing Board\": worker did not become healthy",
                        "Start it again with the start command shown under Next.");
    }

    @Test
    void newBoardNextStepsUseWrapperCommandWhenProvided() {
        // given
        Path workflow = tempDir.resolve("wrapped-command.WORKFLOW.md");
        Path env = tempDir.resolve(".env.wrapped-command");
        Path command = tempDir.resolve("bin/symphony-trello");

        // when
        CliRunResult result = runCli(
                Map.of(CLI_COMMAND_PROPERTY, command.toString()),
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Wrapped Command Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertSuccess();
        assertThat(result.stdout())
                .contains(
                        shellQuote(command.toString())
                                + " start --env "
                                + shellQuote(env.toAbsolutePath().normalize().toString())
                                + " --workflow "
                                + shellQuote(
                                        workflow.toAbsolutePath().normalize().toString()),
                        shellQuote(command.toString())
                                + " logs --workflow "
                                + shellQuote(
                                        workflow.toAbsolutePath().normalize().toString()))
                .doesNotContain("\n  symphony-trello start", "\n  symphony-trello logs");
    }

    @Test
    void newBoardPersistsCommandLineCredentialsToRuntimeEnvFile() {
        // given
        Path workflow = tempDir.resolve("runtime-env.WORKFLOW.md");
        Path env = tempDir.resolve(".env.runtime");

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "direct-key",
                "--token",
                "direct-token",
                "--name",
                "Runtime Env Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertSuccess();
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .contains("TRELLO_API_KEY=direct-key", "TRELLO_API_TOKEN=direct-token");
        assertThat(result.stdout())
                .contains(
                        "Saving Trello credentials...",
                        "Credentials saved: " + env.toAbsolutePath().normalize(),
                        "symphony-trello start --env "
                                + shellQuote(env.toAbsolutePath().normalize().toString())
                                + " --workflow "
                                + shellQuote(
                                        workflow.toAbsolutePath().normalize().toString()))
                .doesNotContain("direct-token");
    }

    @Test
    void newBoardPersistsCommandLineCredentialsToDefaultRuntimeEnvFile() throws Exception {
        // given
        Path workingDir = tempDir.resolve("default-runtime-env-working-dir");
        Path workflow = tempDir.resolve("default-runtime-env.WORKFLOW.md");
        Path env = workingDir.resolve(".env");
        Files.createDirectories(workingDir);

        // when
        MainProcessResult result = runMainProcess(
                workingDir,
                Map.of(),
                List.of("TRELLO_API_KEY", "TRELLO_API_TOKEN", "SYMPHONY_TRELLO_DOTENV"),
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "direct-key",
                "--token",
                "direct-token",
                "--name",
                "Default Runtime Env Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .contains("TRELLO_API_KEY=direct-key", "TRELLO_API_TOKEN=direct-token");
        assertThat(result.stdout())
                .contains(
                        "Saving Trello credentials...",
                        "symphony-trello start --env "
                                + shellQuote(env.toAbsolutePath().normalize().toString())
                                + " --workflow "
                                + shellQuote(
                                        workflow.toAbsolutePath().normalize().toString()))
                .doesNotContain("direct-token");
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void newBoardUsesSelectedRuntimeEnvFileAsCredentialSource() throws Exception {
        // given
        Path workflow = tempDir.resolve("runtime-env-source.WORKFLOW.md");
        Path env = tempDir.resolve(".env.runtime");
        Files.writeString(env, "TRELLO_API_KEY=dotenv-key\nTRELLO_API_TOKEN=dotenv-token\n", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--name",
                "Runtime Env Source Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertSuccess();
        assertThat(createdBoardName).hasValue("Runtime Env Source Queue");
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .isEqualTo("TRELLO_API_KEY=dotenv-key\nTRELLO_API_TOKEN=dotenv-token\n");
        assertThat(result.stdout())
                .contains("symphony-trello start --env "
                        + shellQuote(env.toAbsolutePath().normalize().toString()))
                .doesNotContain("Saving Trello credentials", "dotenv-key", "dotenv-token");
    }

    @Test
    void newBoardPersistsCommandLineCredentialsToConfiguredRuntimeEnvFile() throws Exception {
        // given
        Path workflow = tempDir.resolve("configured-runtime-env.WORKFLOW.md");
        Path env = tempDir.resolve(".env.runtime");

        // when
        MainProcessResult result = runMainProcess(
                Path.of(".").toAbsolutePath().normalize(),
                Map.of("SYMPHONY_TRELLO_DOTENV", env.toString()),
                List.of(),
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "direct-key",
                "--token",
                "direct-token",
                "--name",
                "Configured Runtime Env Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString());

        // then
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(createdBoardName).hasValue("Configured Runtime Env Queue");
        assertThat(env)
                .content(StandardCharsets.UTF_8)
                .contains("TRELLO_API_KEY=direct-key", "TRELLO_API_TOKEN=direct-token");
        assertThat(result.stdout())
                .contains(
                        "Saving Trello credentials...",
                        "symphony-trello start --env "
                                + shellQuote(env.toAbsolutePath().normalize().toString())
                                + " --workflow "
                                + shellQuote(
                                        workflow.toAbsolutePath().normalize().toString()))
                .doesNotContain("direct-token");
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void newBoardRejectsUnsafeConfiguredRuntimeEnvPathBeforeWritingCredentials() throws Exception {
        // given
        Path workflow = tempDir.resolve("unsafe-configured-runtime-env.WORKFLOW.md");
        Path env = tempDir.resolve("README.md");
        Files.writeString(env, "readme", StandardCharsets.UTF_8);

        // when
        MainProcessResult result = runMainProcess(
                Path.of(".").toAbsolutePath().normalize(),
                Map.of("SYMPHONY_TRELLO_DOTENV", env.toString()),
                List.of(),
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "direct-key",
                "--token",
                "direct-token",
                "--name",
                "Configured Runtime Env Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString());

        // then
        assertRejectedUnsafeRuntimeEnvPath(
                result.exitCode(), result.stdout(), result.stderr(), workflow, env, "direct-key", "direct-token");
    }

    @Test
    void newBoardRejectsUnsafeConfiguredRuntimeEnvPathEvenWhenCredentialsComeFromEnvironment() throws Exception {
        // given
        Path workflow = tempDir.resolve("unsafe-configured-runtime-env-with-env-credentials.WORKFLOW.md");
        Path env = tempDir.resolve("README.md");
        Files.writeString(env, "readme", StandardCharsets.UTF_8);

        // when
        MainProcessResult result = runMainProcess(
                Path.of(".").toAbsolutePath().normalize(),
                Map.of(
                        "SYMPHONY_TRELLO_DOTENV",
                        env.toString(),
                        "TRELLO_API_KEY",
                        "environment-key",
                        "TRELLO_API_TOKEN",
                        "environment-token"),
                List.of(),
                "new-board",
                "--endpoint",
                endpoint(),
                "--name",
                "Configured Runtime Env Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString());

        // then
        assertRejectedUnsafeRuntimeEnvPath(
                result.exitCode(),
                result.stdout(),
                result.stderr(),
                workflow,
                env,
                "environment-key",
                "environment-token");
    }

    @Test
    void newBoardRejectsUnwritableRuntimeEnvFileBeforeCreatingBoard() throws Exception {
        // given
        Path notDirectory = tempDir.resolve("not-a-directory");
        Files.writeString(notDirectory, "not a directory", StandardCharsets.UTF_8);
        Path env = notDirectory.resolve(".env.runtime");
        Path workflow = tempDir.resolve("unwritable-runtime-env.WORKFLOW.md");

        // when
        CliRunResult result = runNewBoardWithRuntimeEnv(workflow, env, true);

        // then
        assertThat(result.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(createdBoardName.get()).isNull();
        assertThat(workflow).doesNotExist();
        assertThat(result.stdout()).doesNotContain("Created Trello board", "Troubleshooting report written");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_env_write_failed",
                        "Choose a writable .env or .env.NAME file",
                        "(IOException: Selected dotenv parent is not a directory.)")
                .doesNotContain(env.toString(), notDirectory.toString(), tempDir.toString());
    }

    @Test
    void newBoardRejectsMissingRuntimeEnvParentBeforeCreatingBoard() throws Exception {
        // given
        Path missingParent = tempDir.resolve("missing-parent");
        Path env = missingParent.resolve(".env.runtime");
        Path workflow = tempDir.resolve("missing-parent-runtime-env.WORKFLOW.md");

        // when
        CliRunResult result = runNewBoardWithRuntimeEnv(workflow, env, true);

        // then
        assertThat(result.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(createdBoardName.get()).isNull();
        assertThat(workflow).doesNotExist();
        assertThat(result.stdout()).doesNotContain("Created Trello board", "Troubleshooting report written");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_env_write_failed",
                        "Choose a writable .env or .env.NAME file",
                        "(IOException: Selected dotenv parent directory does not exist.)")
                .doesNotContain(env.toString(), missingParent.toString(), tempDir.toString());
    }

    @Test
    void newBoardReportsEnvWriteCauseWhenRuntimeEnvParentBecomesFileAfterValidation() throws Exception {
        // given
        Path envParent = Files.createDirectory(tempDir.resolve("runtime-env-holder"));
        Path env = envParent.resolve(".env.runtime");
        Path workflow = tempDir.resolve("parent-becomes-file-runtime-env.WORKFLOW.md");
        trello.remove("/1/boards/");
        trello.on("/1/boards/", exchange -> {
            Map<String, String> query = query(exchange);
            createdBoardName.set(query.get("name"));
            Files.delete(envParent);
            Files.writeString(envParent, "not a directory", StandardCharsets.UTF_8);
            respond(exchange, boardJson("board-1", query.get("name"), "abc123", "https://trello.com/b/abc123/board"));
        });

        // when
        CliRunResult result = runNewBoardWithRuntimeEnv(workflow, env, true);

        // then
        assertThat(result.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(env).doesNotExist();
        assertThat(result.stdout()).doesNotContain("Created Trello board", "Troubleshooting report written");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_env_write_failed",
                        "Choose a writable .env or .env.NAME file",
                        "(FileAlreadyExistsException")
                .doesNotContain("direct-key", "direct-token", envParent.toString(), tempDir.toString());
    }

    @Test
    void newBoardRejectsUnsafeRuntimeEnvPathBeforeWritingCredentials() throws Exception {
        // given
        Path env = tempDir.resolve("README.md");
        Path workflow = tempDir.resolve("unsafe-runtime-env.WORKFLOW.md");
        Files.writeString(env, "readme", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runNewBoardWithRuntimeEnv(workflow, env, true);

        // then
        assertRejectedUnsafeRuntimeEnvPath(
                result.exitCode(), result.stdout(), result.stderr(), workflow, env, "direct-key", "direct-token");
    }

    @Test
    void newBoardRejectsUnsafeRuntimeEnvPathEvenWhenCredentialsWouldNotBePersisted() throws Exception {
        // given
        Path env = tempDir.resolve("README.md");
        Path workflow = tempDir.resolve("unsafe-runtime-env-without-direct-credentials.WORKFLOW.md");
        Files.writeString(env, "readme", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runNewBoardWithRuntimeEnv(workflow, env, false);

        // then
        assertThat(result.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(createdBoardName.get()).isNull();
        assertThat(workflow).doesNotExist();
        assertThat(env).content(StandardCharsets.UTF_8).isEqualTo("readme");
        assertThat(result.stdout()).doesNotContain("Created Trello board", "Troubleshooting report written");
        assertThat(result.stderr())
                .contains("setup_failed code=setup_env_path_not_ignored", ".env or .env.NAME")
                .doesNotContain("setup_missing_api_key");
    }

    @MethodSource("invalidRuntimeEnvPathScenarios")
    @ParameterizedTest(name = "{0} {1}")
    void setupCommandsRejectInvalidRuntimeEnvPathWithSpecificMessage(
            String command, InvalidRuntimeEnvPathScenario scenario) throws Exception {
        // given
        Path workflow = tempDir.resolve(command + "-" + scenario.name() + ".WORKFLOW.md");
        String envValue = scenario.resolveEnvValue(tempDir);

        // when
        CliRunResult result = runCli(setupCommandWithRuntimeEnv(command, workflow, envValue));

        // then
        assertThat(result.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(createdBoardName.get()).isNull();
        assertThat(workflow).doesNotExist();
        assertThat(result.stdout()).doesNotContain("Created Trello board", "Troubleshooting report written");
        assertThat(result.stderr())
                .contains(scenario.expectedErrorFragments().toArray(String[]::new))
                .doesNotContain("direct-key", "direct-token", tempDir.toString(), "Troubleshooting report written");
        assertThat(scenario.rawPrivateValues())
                .allSatisfy(value -> assertThat(result.stderr()).doesNotContain(value));
    }

    private static Stream<Arguments> invalidRuntimeEnvPathScenarios() {
        return Stream.of("new-board", "import-board").flatMap(command -> Stream.of(
                        InvalidRuntimeEnvPathScenario.plain(
                                "blank",
                                "",
                                "setup_failed code=setup_invalid_arguments",
                                "--env must point to a dotenv file path."),
                        InvalidRuntimeEnvPathScenario.plain(
                                "whitespace",
                                "   ",
                                "setup_failed code=setup_invalid_arguments",
                                "--env must point to a dotenv file path."),
                        InvalidRuntimeEnvPathScenario.directoryScenario(),
                        InvalidRuntimeEnvPathScenario.plain(
                                "control-character",
                                "env\nfile",
                                "setup_failed code=setup_invalid_arguments",
                                "--env must not contain control characters."))
                .map(scenario -> Arguments.of(command, scenario)));
    }

    @MethodSource("blankWorkflowPathScenarios")
    @ParameterizedTest(name = "{0} {1}")
    void setupCommandsRejectBlankWorkflowPathBeforeTrelloRequest(String command, String workflowValue) {
        // given
        Path env = tempDir.resolve("missing-workflow-env-parent").resolve(".env");

        // when
        CliRunResult result = runCli(setupCommandWithWorkflow(command, workflowValue, env));

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("setup_failed code=setup_invalid_arguments", "--workflow must be a file path.")
                .stderrDoesNotContain("Troubleshooting report written");
        assertThat(result.stdout())
                .doesNotContain("Created Trello board", "Imported Trello board", "Saving Trello credentials");
        assertThat(createdBoardName.get()).isNull();
        assertThat(createdLists).isEmpty();
        assertThat(env.getParent()).doesNotExist();
        assertThat(tempDir.resolve("   ")).doesNotExist();
    }

    private static Stream<Arguments> blankWorkflowPathScenarios() {
        return Stream.of("new-board", "import-board")
                .flatMap(command -> Stream.of("", "   ").map(workflowValue -> Arguments.of(command, workflowValue)));
    }

    @Test
    void newBoardRejectsMalformedRuntimeEnvFileBeforeCreatingBoard() throws Exception {
        // given
        Path env = tempDir.resolve(".env.runtime");
        Files.write(env, new byte[] {(byte) 0xc3, (byte) 0x28});
        Path workflow = tempDir.resolve("malformed-runtime-env.WORKFLOW.md");

        // when
        CliRunResult result = runNewBoardWithRuntimeEnv(workflow, env, true);

        // then
        assertThat(result.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(createdBoardName.get()).isNull();
        assertThat(workflow).doesNotExist();
        assertThat(result.stdout()).doesNotContain("Created Trello board", "Troubleshooting report written");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_env_write_failed",
                        "Choose a writable .env or .env.NAME file",
                        "(MalformedInputException: Input length = 1)")
                .doesNotContain(env.toString(), tempDir.toString(), "direct-key", "direct-token");
    }

    @Test
    void newBoardRejectsMultilineRuntimeCredentialBeforeCreatingBoard() {
        // given
        Path env = tempDir.resolve(".env.runtime");
        Path workflow = tempDir.resolve("multiline-runtime-env.WORKFLOW.md");

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "direct-key\ninvalid",
                "--token",
                "direct-token",
                "--name",
                "Runtime Env Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertFailure(SETUP_FAILURE);
        assertThat(createdBoardName.get()).isNull();
        assertThat(env).doesNotExist();
        assertThat(workflow).doesNotExist();
        result.stdoutDoesNotContain("Created Trello board", "Troubleshooting report written")
                .stderrContains("setup_failed code=setup_env_value_multiline")
                .stderrDoesNotContain("direct-key", "direct-token");
    }

    @Test
    void newBoardMissingCredentialsHintUsesSelectedRuntimeEnvFile() throws Exception {
        // given
        Path env = tempDir.resolve(".env.runtime");
        Path workflow = tempDir.resolve("missing-credentials-runtime-env.WORKFLOW.md");
        Files.writeString(env, "# no credentials yet\n", StandardCharsets.UTF_8);

        // when
        MainProcessResult result = runMainProcessWithoutTrelloCredentials(
                tempDir,
                "new-board",
                "--endpoint",
                endpoint(),
                "--name",
                "Runtime Env Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        assertThat(result.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(createdBoardName.get()).isNull();
        assertThat(workflow).doesNotExist();
        assertThat(result.stdout()).doesNotContain("Troubleshooting report written");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_missing_api_key",
                        env.toAbsolutePath().normalize().toString())
                .doesNotContain(LocalEnvironment.defaultDotenv()
                        .toAbsolutePath()
                        .normalize()
                        .toString());
    }

    @Test
    void newBoardDoesNotWriteRuntimeEnvWhenWorkflowPreflightFails() throws Exception {
        // given
        Path workflow = tempDir.resolve("existing-runtime-env.WORKFLOW.md");
        Files.writeString(workflow, "existing workflow", StandardCharsets.UTF_8);
        Path env = tempDir.resolve(".env.runtime");

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "direct-key",
                "--token",
                "direct-token",
                "--name",
                "Runtime Env Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertFailure(SETUP_FAILURE);
        assertThat(createdBoardName.get()).isNull();
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo("existing workflow");
        assertThat(env).doesNotExist();
        result.stdoutDoesNotContain(
                        "Created Trello board", "Saving Trello credentials", "Troubleshooting report written")
                .stderrContains("setup_failed code=setup_workflow_exists");
        assertThat(result.stderr())
                .contains(workflow.toAbsolutePath().normalize() + "\nRe-run with --force")
                .doesNotContain(workflow.toAbsolutePath().normalize() + ".")
                .doesNotContain("direct-key", "direct-token");
    }

    @Test
    void importBoardDoesNotContactTrelloWhenWorkflowPreflightFails() throws Exception {
        // given
        Path workflow = tempDir.resolve("existing-import-runtime-env.WORKFLOW.md");
        Files.writeString(workflow, "existing workflow", StandardCharsets.UTF_8);
        Path env = tempDir.resolve(".env.import-runtime");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "direct-key",
                "--token",
                "direct-token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertFailure(SETUP_FAILURE);
        assertThat(boardInfoLookups).hasValue(0);
        assertThat(createdLists).isEmpty();
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo("existing workflow");
        assertThat(env).doesNotExist();
        result.stdoutDoesNotContain(
                        "Imported Trello board", "Saving Trello credentials", "Troubleshooting report written")
                .stderrContains("setup_failed code=setup_workflow_exists");
        assertThat(result.stderr())
                .contains(workflow.toAbsolutePath().normalize() + "\nRe-run with --force")
                .doesNotContain(workflow.toAbsolutePath().normalize() + ".")
                .doesNotContain("direct-key", "direct-token", "trello_api_request", "trello_auth_failed");
    }

    @Test
    void importBoardRejectsMissingRuntimeEnvParentBeforeImportingBoard() throws Exception {
        // given
        Path missingParent = tempDir.resolve("missing-import-parent");
        Path env = missingParent.resolve(".env.runtime");
        Path workflow = tempDir.resolve("missing-parent-import-runtime-env.WORKFLOW.md");

        // when
        CliRunResult result = runImportBoardWithRuntimeEnv(workflow, env, true);

        // then
        assertThat(result.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(boardInfoLookups).hasValue(0);
        assertThat(createdBoardName.get()).isNull();
        assertThat(workflow).doesNotExist();
        assertThat(result.stdout())
                .doesNotContain("Imported Trello board", "Troubleshooting report written", "Saving Trello credentials");
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_env_write_failed",
                        "Choose a writable .env or .env.NAME file",
                        "(IOException: Selected dotenv parent directory does not exist.)")
                .doesNotContain(
                        env.toString(), missingParent.toString(), tempDir.toString(), "direct-key", "direct-token");
    }

    @Test
    void newBoardWritesResolverBackedCodexModelDefaults() {
        // given
        Path workflow = tempDir.resolve("resolver-backed-model.WORKFLOW.md");
        Path env = tempDir.resolve(".env.resolver-backed-model");

        // when
        CliRunResult result = runCli(
                () -> new TrelloBoardSetup.CodexModelDefaults("gpt-test-maintainer", "high"),
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Resolver Backed Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-test-maintainer\"")
                .contains("reasoning_effort: \"high\"");
    }

    @Test
    void newBoardWritesExplicitCodexModelOverrides() {
        // given
        Path workflow = tempDir.resolve("explicit-new-board-model.WORKFLOW.md");

        // when
        CliRunResult result = runCli(
                () -> new TrelloBoardSetup.CodexModelDefaults("gpt-default", "medium"),
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Explicit New Board Model",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--codex-model",
                "gpt-explicit",
                "--codex-reasoning-effort",
                "high");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-explicit\"", "reasoning_effort: \"high\"")
                .doesNotContain("gpt-default");
    }

    @Test
    void newBoardWritesFallbackReasoningForExplicitModelWhenDiscoveryDoesNotSupportFirstClassFields() {
        // given
        Path workflow = tempDir.resolve("unsupported-explicit-new-board-model.WORKFLOW.md");

        // when
        CliRunResult result = runCli(
                () -> TrelloBoardSetup.CodexModelDefaults.unsupportedFirstClassFields(),
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Unsupported Explicit New Board Model",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--codex-model",
                "gpt-explicit");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-explicit\"", "reasoning_effort: \"medium\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--codex-model", "--codex-reasoning-effort"})
    void newBoardRejectsBlankCodexModelOverridesBeforeTrelloRequest(String optionName) {
        // given
        Path workflow = tempDir.resolve("blank-codex-new-board.WORKFLOW.md");
        Path env = tempDir.resolve("missing-env-parent").resolve(".env");

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Blank Codex New Board",
                "--workspace-id",
                "workspace-1",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString(),
                optionName,
                " ");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("setup_failed code=setup_invalid_arguments", optionName + " must not be blank.")
                .stderrDoesNotContain("Troubleshooting report written");
        assertThat(result.stdout()).doesNotContain("Created Trello board", "Saving Trello credentials");
        assertThat(createdBoardName.get()).isNull();
        assertThat(createdLists).isEmpty();
        assertThat(env.getParent()).doesNotExist();
        assertThat(workflow).doesNotExist();
    }

    @MethodSource("blankNewBoardSetupOptions")
    @ParameterizedTest
    void newBoardRejectsBlankSetupOptionValuesBeforeTrelloRequest(BlankDirectSetupOption option) {
        // given
        Path workflow = tempDir.resolve("blank-new-board-" + option.name() + ".WORKFLOW.md");
        Path env = tempDir.resolve("blank-new-board-env").resolve(option.name()).resolve(".env");

        // when
        CliRunResult result = runCli(option.command(workflow, env));

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments", option.optionName() + " must not be blank.")
                .stderrDoesNotContain("Troubleshooting report written");
        assertThat(result.stdout()).doesNotContain("Created Trello board", "Saving Trello credentials");
        assertThat(createdBoardName.get()).isNull();
        assertThat(createdLists).isEmpty();
        assertThat(env.getParent()).doesNotExist();
        assertThat(workflow).doesNotExist();
    }

    private static Stream<BlankDirectSetupOption> blankNewBoardSetupOptions() {
        return Stream.of(
                new BlankDirectSetupOption(
                        "name",
                        "--name",
                        "new-board",
                        "--endpoint",
                        "http://127.0.0.1:9",
                        "--key",
                        "key",
                        "--token",
                        "token",
                        "--name",
                        " ",
                        "--workflow",
                        "<workflow>",
                        "--env",
                        "<env>"),
                new BlankDirectSetupOption(
                        "workspace-id",
                        "--workspace-id",
                        "new-board",
                        "--endpoint",
                        "http://127.0.0.1:9",
                        "--key",
                        "key",
                        "--token",
                        "token",
                        "--name",
                        "Blank Workspace",
                        "--workspace-id",
                        "",
                        "--workflow",
                        "<workflow>",
                        "--env",
                        "<env>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"--codex-model", "--codex-reasoning-effort"})
    void importBoardRejectsBlankCodexModelOverridesBeforeTrelloRequest(String optionName) {
        // given
        Path workflow = tempDir.resolve("blank-codex-import-board.WORKFLOW.md");
        Path env = tempDir.resolve("missing-import-env-parent").resolve(".env");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "input",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString(),
                optionName,
                " ");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("setup_failed code=setup_invalid_arguments", optionName + " must not be blank.")
                .stderrDoesNotContain("Troubleshooting report written");
        assertThat(result.stdout()).doesNotContain("Imported Trello board", "Saving Trello credentials");
        assertThat(createdLists).isEmpty();
        assertThat(env.getParent()).doesNotExist();
        assertThat(workflow).doesNotExist();
    }

    @MethodSource("blankImportBoardSetupOptions")
    @ParameterizedTest
    void importBoardRejectsBlankSetupOptionValuesBeforeTrelloRequest(BlankDirectSetupOption option) {
        // given
        Path workflow = tempDir.resolve("blank-import-board-" + option.name() + ".WORKFLOW.md");
        Path env =
                tempDir.resolve("blank-import-board-env").resolve(option.name()).resolve(".env");

        // when
        CliRunResult result = runCli(option.command(workflow, env));

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments", option.optionName() + " must not be blank.")
                .stderrDoesNotContain("trello_api_request", "Troubleshooting report written");
        assertThat(result.stdout()).doesNotContain("Imported Trello board", "Saving Trello credentials");
        assertThat(createdLists).isEmpty();
        assertThat(env.getParent()).doesNotExist();
        assertThat(workflow).doesNotExist();
    }

    private static Stream<BlankDirectSetupOption> blankImportBoardSetupOptions() {
        return Stream.of(
                blankImportBoardOption("active-empty", "--active", "--active", ""),
                blankImportBoardOption("active-comma", "--active", "--active", ","),
                blankImportBoardOption("active-middle-empty", "--active", "--active", "Ready for Codex, ,Inbox"),
                blankImportBoardOption("terminal-empty", "--terminal", "--terminal", ""),
                blankImportBoardOption("terminal-trailing-empty", "--terminal", "--terminal", "Done,   "),
                blankImportBoardOption("in-progress", "--in-progress", "--in-progress", ""),
                blankImportBoardOption("blocked", "--blocked", "--blocked", " "));
    }

    private static BlankDirectSetupOption blankImportBoardOption(
            String name, String optionName, String option, String value) {
        List<String> command = new ArrayList<>(List.of(
                "import-board",
                "--endpoint",
                "http://127.0.0.1:9",
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "input"));
        if (!"--active".equals(optionName)) {
            command.addAll(List.of("--active", "Queue for Codex"));
        }
        if (!"--terminal".equals(optionName)) {
            command.addAll(List.of("--terminal", "Released"));
        }
        command.addAll(List.of(option, value, "--workflow", "<workflow>", "--env", "<env>"));
        return new BlankDirectSetupOption(name, optionName, command);
    }

    @MethodSource("directSetupCodexModelOptions")
    @ParameterizedTest(name = "{0} {1}")
    void directSetupRejectsControlCharactersInCodexModelOverridesBeforeTrelloRequest(
            String command, String optionName) {
        // given
        Path workflow = tempDir.resolve("control-character-codex-" + command + ".WORKFLOW.md");
        Path env = tempDir.resolve("missing-control-character-env-parent").resolve(".env");
        String invalidValue = "bad\nvalue";

        // when
        CliRunResult result = runCli(setupCommandWithCodexOverride(command, workflow, env, optionName, invalidValue));

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        optionName + " must not contain control characters.")
                .stderrDoesNotContain(invalidValue, "Troubleshooting report written");
        assertThat(result.stdout())
                .doesNotContain("Created Trello board", "Imported Trello board", "Saving Trello credentials");
        assertThat(createdBoardName.get()).isNull();
        assertThat(createdLists).isEmpty();
        assertThat(env.getParent()).doesNotExist();
        assertThat(workflow).doesNotExist();
    }

    private static Stream<Arguments> directSetupCodexModelOptions() {
        return Stream.of("new-board", "import-board")
                .flatMap(command -> Stream.of("--codex-model", "--codex-reasoning-effort")
                        .map(optionName -> Arguments.of(command, optionName)));
    }

    private record BlankDirectSetupOption(String name, String optionName, List<String> commandTemplate) {
        BlankDirectSetupOption(String name, String optionName, String... commandTemplate) {
            this(name, optionName, List.of(commandTemplate));
        }

        String[] command(Path workflow, Path env) {
            return commandTemplate.stream()
                    .map(value -> switch (value) {
                        case "<workflow>" -> workflow.toString();
                        case "<env>" -> env.toString();
                        default -> value;
                    })
                    .toArray(String[]::new);
        }
    }

    @Test
    void usesConfiguredDefaultWorkflowDirectoryWithoutDisablingBoardNameFallback() throws IOException {
        // given
        Path configDirectory = tempDir.resolve("config");
        Files.createDirectories(configDirectory);
        Path defaultWorkflow = configDirectory.resolve("WORKFLOW.md");
        Path env = configDirectory.resolve(".env.second-board");
        Files.writeString(defaultWorkflow, "existing", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                Map.of(CONFIG_DIR_PROPERTY, configDirectory.toString()),
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Second Board",
                "--env",
                env.toString());

        // then
        result.assertSuccess();
        assertThat(defaultWorkflow).content(StandardCharsets.UTF_8).isEqualTo("existing");
        assertThat(configDirectory.resolve("WORKFLOW.second-board.md"))
                .content(StandardCharsets.UTF_8)
                .contains("board_id: \"abc123\"");
        ConnectedBoardManifest manifest =
                new ConnectedBoardRepository(configDirectory.resolve(ConnectedBoardManifest.FILE_NAME)).load();
        assertThat(manifest.boards()).singleElement().satisfies(board -> {
            assertThat(board.boardName()).isEqualTo("Second Board");
            assertThat(board.workflowPath())
                    .isEqualTo(configDirectory
                            .resolve("WORKFLOW.second-board.md")
                            .toAbsolutePath()
                            .normalize());
            assertThat(board.envPath()).isEqualTo(env.toAbsolutePath().normalize());
        });
    }

    @Test
    void printsPowerShellSafeNextStepsWhenRequestedByWrapper() {
        // given
        Path workflow = tempDir.resolve("generated O'Brien.WORKFLOW.md");
        Path env = tempDir.resolve(".env.powershell");
        Path command = tempDir.resolve("bin/Symphony O'Brien/symphony-trello.ps1");

        // when
        CliRunResult result = runCli(
                Map.of(SHELL_PROPERTY, "powershell", CLI_COMMAND_PROPERTY, command.toString()),
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "PowerShell Work Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertSuccess();
        assertThat(result.stdout())
                .contains(
                        "& "
                                + powerShellQuote(command.toString())
                                + " start --env "
                                + powerShellQuote(
                                        env.toAbsolutePath().normalize().toString())
                                + " --workflow "
                                + powerShellQuote(
                                        workflow.toAbsolutePath().normalize().toString()),
                        "& "
                                + powerShellQuote(command.toString())
                                + " logs --workflow "
                                + powerShellQuote(
                                        workflow.toAbsolutePath().normalize().toString()));
    }

    @Test
    void printsCmdSafeNextStepsWhenRequestedByCmdShim() {
        // given
        Path workflow = tempDir.resolve("generated command prompt.WORKFLOW.md");
        Path env = tempDir.resolve(".env.cmd");

        // when
        CliRunResult result = runCli(
                Map.of(SHELL_PROPERTY, "cmd"),
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Command Prompt Work Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertSuccess();
        assertThat(result.stdout())
                .contains(
                        "symphony-trello start --env "
                                + cmdQuote(env.toAbsolutePath().normalize().toString())
                                + " --workflow "
                                + cmdQuote(workflow.toAbsolutePath().normalize().toString()),
                        "symphony-trello logs --workflow "
                                + cmdQuote(workflow.toAbsolutePath().normalize().toString()));
    }

    @Test
    void createsNonGithubBoardWithoutMergingList() {
        // given
        Path workflow = tempDir.resolve("non-github.WORKFLOW.md");
        Path env = tempDir.resolve(".env.non-github");

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Local Work Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString(),
                "--no-github");

        // then
        result.assertSuccess();
        assertThat(createdLists)
                .containsExactly("Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Done");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("## Local And Non-GitHub Repository Work")
                .contains("This workflow does not have GitHub PR integration configured")
                .doesNotContain("## Landing From \"Merging\"")
                .doesNotContain("## Pull Request Publication")
                .doesNotContain("Merging");
    }

    @Test
    void explicitDefaultWorkflowPathDoesNotUseBoardNameFallback() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(workflow, "existing workflow", StandardCharsets.UTF_8);
        Path fallback = tempDir.resolve("WORKFLOW.symmetry-queue.md");
        Path env = tempDir.resolve(".env.explicit-workflow");

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Symmetry Queue",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertFailure(SETUP_FAILURE);
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo("existing workflow");
        assertThat(fallback).doesNotExist();
        assertThat(result.stdout()).isEmpty();
        result.stderrContains("setup_failed code=setup_workflow_exists", "--force");
    }

    @Test
    void importsExistingBoardWithExplicitListsAndPrintsSelection() throws Exception {
        // given
        Path workflow = tempDir.resolve("imported workflow.WORKFLOW.md");
        Path env = tempDir.resolve(".env.imported");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex,Doing",
                "--in-progress",
                "Doing",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("- \"Queue for Codex\"")
                .contains("- \"Doing\"")
                .contains("- \"Released\"")
                .doesNotContain("- \" Doing\"", "- \"\"");
        ConnectedBoardManifest manifest =
                new ConnectedBoardRepository(tempDir.resolve(ConnectedBoardManifest.FILE_NAME)).load();
        assertThat(manifest.boards()).singleElement().satisfies(board -> {
            assertThat(board.boardId()).isEqualTo("board-1");
            assertThat(board.boardKey()).isEqualTo("SYNTH001");
            assertThat(board.boardName()).isEqualTo("Existing Board");
            assertThat(board.boardUrl()).isEqualTo("https://trello.com/b/SYNTH001/board");
            assertThat(board.workflowPath()).isEqualTo(workflow.toAbsolutePath().normalize());
            assertThat(board.envPath()).isEqualTo(env.toAbsolutePath().normalize());
            assertThat(board.workspaceRoot())
                    .isEqualTo(TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT
                            .toAbsolutePath()
                            .normalize());
            assertThat(board.serverPort()).isGreaterThanOrEqualTo(TrelloBoardSetup.DEFAULT_SERVER_PORT);
        });
        assertThat(result.stdout())
                .contains(
                        "Imported Trello board: \"Existing Board\"",
                        "Board identifier for WORKFLOW.md: SYNTH001",
                        "Active lists: \"Queue for Codex\", \"Doing\"",
                        "In-progress list: \"Doing\"",
                        "Terminal lists: \"Released\"",
                        "Blocked list: \"Blocked\"",
                        "symphony-trello start --env "
                                + shellQuote(env.toAbsolutePath().normalize().toString())
                                + " --workflow "
                                + shellQuote(
                                        workflow.toAbsolutePath().normalize().toString()),
                        "symphony-trello logs --workflow "
                                + shellQuote(
                                        workflow.toAbsolutePath().normalize().toString()))
                .doesNotContain("./mvnw quarkus:dev");
    }

    @Test
    void importBoardPreservesWhitespaceInListSelectors() throws Exception {
        // given
        Path workflow = tempDir.resolve("imported-space-lists.WORKFLOW.md");
        Path env = tempDir.resolve(".env.imported-space");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                " Queue for Codex ,Released ",
                "--terminal",
                " Doing ",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("- \" Queue for Codex \"")
                .contains("- \"Released \"")
                .contains("- \" Doing \"");
        result.stdoutContains("Imported Trello board: \"Existing Board\"");
    }

    @Test
    void forceImportBoardPreservesEnvironmentBackedServerPortFromSelectedEnv() throws Exception {
        // given
        Path workflow = tempDir.resolve("imported-env-port.WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "existing-board"
                server:
                  port: $SYNTHETIC_IMPORT_STATUS_PORT
                ---
                Existing body
                """,
                StandardCharsets.UTF_8);
        Path env = tempDir.resolve(".env.imported-port");
        Files.writeString(env, "SYNTHETIC_IMPORT_STATUS_PORT=19091\n", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString(),
                "--force");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("port: 19091")
                .doesNotContain("port: 18080");
        ConnectedBoardManifest manifest =
                new ConnectedBoardRepository(tempDir.resolve(ConnectedBoardManifest.FILE_NAME)).load();
        assertThat(manifest.boards()).singleElement().satisfies(board -> {
            assertThat(board.workflowPath()).isEqualTo(workflow.toAbsolutePath().normalize());
            assertThat(board.envPath()).isEqualTo(env.toAbsolutePath().normalize());
            assertThat(board.serverPort()).isEqualTo(19091);
        });
        result.stdoutContains("HTTP status port: 19091");
    }

    @Test
    void importBoardSkipsEnvironmentBackedSiblingWorkflowPortFromSelectedEnv() throws Exception {
        // given
        Path siblingWorkflow = tempDir.resolve("existing-env-port.WORKFLOW.md");
        Files.writeString(
                siblingWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "existing-board"
                server:
                  port: $SYNTHETIC_IMPORT_STATUS_PORT
                ---
                Existing body
                """,
                StandardCharsets.UTF_8);
        Path workflow = tempDir.resolve("new-import.WORKFLOW.md");
        Path env = tempDir.resolve(".env.import-port-scan");
        Files.writeString(env, "SYNTHETIC_IMPORT_STATUS_PORT=18080\n", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        assertSiblingWorkflowPortUsesEnvironmentVariable(result, workflow, env);
    }

    @Test
    void newBoardSkipsEnvironmentBackedSiblingWorkflowPortFromSelectedEnv() throws Exception {
        // given
        Path siblingWorkflow = tempDir.resolve("existing-new-board-env-port.WORKFLOW.md");
        Files.writeString(
                siblingWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "existing-board"
                server:
                  port: $SYNTHETIC_NEW_BOARD_STATUS_PORT
                ---
                Existing body
                """,
                StandardCharsets.UTF_8);
        Path workflow = tempDir.resolve("new-board-env-port.WORKFLOW.md");
        Path env = tempDir.resolve(".env.new-board-port-scan");
        Files.writeString(env, "SYNTHETIC_NEW_BOARD_STATUS_PORT=18080\n", StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "New Port Queue",
                "--workspace-id",
                "workspace-id",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                env.toString());

        // then
        assertSiblingWorkflowPortUsesEnvironmentVariable(result, workflow, env);
    }

    private void assertSiblingWorkflowPortUsesEnvironmentVariable(CliRunResult result, Path workflow, Path env)
            throws Exception {
        int expectedPort = firstAvailableManagedPort(18080);

        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("port: " + expectedPort)
                .doesNotContain("port: 18080");
        assertConnectedBoardUsesWorkflowEnvAndPort(workflow, env, expectedPort);
        result.stdoutContains("HTTP status port: " + expectedPort);
    }

    @Test
    void importBoardReportsUnknownInProgressListAsInProgressError() {
        // given
        Path workflow = tempDir.resolve("bad-in-progress.WORKFLOW.md");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--force",
                "--in-progress",
                "No Such List 123");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_unknown_in_progress_state",
                        "Unknown in-progress list(s): \"No Such List 123\"")
                .stderrDoesNotContain("setup_unknown_active_state", "Unknown active list(s)");
        assertThat(workflow).doesNotExist();
    }

    @Test
    void importBoardWritesExplicitCodexReasoningOverride() {
        // given
        Path workflow = tempDir.resolve("explicit-import-model.WORKFLOW.md");

        // when
        CliRunResult result = runCli(
                () -> new TrelloBoardSetup.CodexModelDefaults("gpt-default", "medium"),
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--codex-reasoning-effort",
                "high");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-default\"", "reasoning_effort: \"high\"");
    }

    @Test
    void importBoardWritesFallbackReasoningForExplicitModelWhenUnsupportedDiscoveryPreservesExistingOmission()
            throws IOException {
        // given
        Path workflow = tempDir.resolve("explicit-import-unsupported-existing-omitted.WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                () -> TrelloBoardSetup.CodexModelDefaults.unsupportedFirstClassFields(),
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--force",
                "--codex-model",
                "gpt-explicit");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-explicit\"", "reasoning_effort: \"medium\"");
    }

    @Test
    void importBoardPreservesExistingReasoningForExplicitModelOverride() throws IOException {
        // given
        Path workflow = tempDir.resolve("explicit-import-preserve-existing-reasoning.WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                  model: "gpt-old"
                  reasoning_effort: "low"
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCliWithSelectionDefaults(
                new CodexModelSelectionDefaults(
                        new TrelloBoardSetup.CodexModelDefaults("gpt-default", "medium"),
                        Map.of("gpt-default", "medium", "gpt-new", "high")),
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--force",
                "--codex-model",
                "gpt-new");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-new\"", "reasoning_effort: \"low\"")
                .doesNotContain("model: \"gpt-old\"", "reasoning_effort: \"high\"");
    }

    @Test
    void importBoardPreservesReasoningOmissionForUnknownExplicitModelWhenDiscoverySupportsFirstClassFields()
            throws IOException {
        // given
        Path workflow = tempDir.resolve("explicit-import-supported-existing-omitted.WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                codex:
                  command: codex app-server
                ---
                Old body
                """,
                StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCliWithSelectionDefaults(
                new CodexModelSelectionDefaults(
                        new TrelloBoardSetup.CodexModelDefaults("gpt-default", "medium"),
                        Map.of("gpt-default", "medium", "gpt-known", "high")),
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--force",
                "--codex-model",
                "gpt-custom");

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-custom\"")
                .doesNotContain("reasoning_effort:");
    }

    @MethodSource("invalidCliArgumentScenarios")
    @ParameterizedTest(name = "{0}")
    void rejectsInvalidCliArguments(String name, String[] command, int exitCode, String[] expectedError) {
        // given

        // when
        CliRunResult result = runCli(command);

        // then
        result.assertFailure(exitCode).stderrContains(expectedError);
        assertThat(result.stdout()).isEmpty();
    }

    @MethodSource("directSetupAndLifecycleControlCharacterPathOptions")
    @ParameterizedTest(name = "{0}")
    void rejectsControlCharactersInDirectSetupAndLifecyclePathOptions(InvalidPathOptionCase invalidCase) {
        // given

        // when
        CliRunResult result = runCli(invalidCase.commandArray(Map.of("<endpoint>", endpoint())));

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        invalidCase.optionName() + " must not contain control characters")
                .stdoutDoesNotContain(invalidCase.forbiddenOutput(), invalidCase.rawValue())
                .stderrDoesNotContain(invalidCase.rawValue(), "Troubleshooting report written");
        assertThat(createdBoardName).hasValue(null);
        assertThat(tempDir.resolve(invalidCase.rawValue())).doesNotExist();
    }

    private static Stream<InvalidPathOptionCase> directSetupAndLifecycleControlCharacterPathOptions() {
        return Stream.of(
                invalidPathOptionCase(
                        "new-board workflow",
                        "--workflow",
                        "bad\nworkflow.WORKFLOW.md",
                        "Created Trello board",
                        "new-board",
                        "--endpoint",
                        "<endpoint>",
                        "--key",
                        "key",
                        "--token",
                        "token",
                        "--name",
                        "Queue",
                        "--workflow",
                        "bad\nworkflow.WORKFLOW.md"),
                invalidPathOptionCase(
                        "import-board workspace root",
                        "--workspace-root",
                        "bad\nworkspace-root",
                        "Imported Trello board",
                        "import-board",
                        "--endpoint",
                        "<endpoint>",
                        "--key",
                        "key",
                        "--token",
                        "token",
                        "--board",
                        "https://trello.com/b/input/existing-board",
                        "--active",
                        "Queue for Codex",
                        "--terminal",
                        "Released",
                        "--workspace-root",
                        "bad\nworkspace-root",
                        "--force"),
                invalidPathOptionCase(
                        "new-board env",
                        "--env",
                        "bad\nenv",
                        "Created Trello board",
                        "new-board",
                        "--endpoint",
                        "<endpoint>",
                        "--key",
                        "key",
                        "--token",
                        "token",
                        "--name",
                        "Queue",
                        "--env",
                        "bad\nenv"),
                invalidPathOptionCase(
                        "status workflow",
                        "--workflow",
                        "bad\nworkflow.WORKFLOW.md",
                        "stopped",
                        "status",
                        "--workflow",
                        "bad\nworkflow.WORKFLOW.md"));
    }

    @MethodSource("invalidDirectWorkspaceRootScenarios")
    @ParameterizedTest(name = "{0}")
    void directSetupRejectsInvalidWorkspaceRootBeforeTrelloWork(InvalidDirectWorkspaceRootScenario scenario)
            throws Exception {
        // given
        Path workspaceFile = tempDir.resolve("direct-workspace-root-file");
        Files.writeString(workspaceFile, "not a directory", StandardCharsets.UTF_8);
        Path importWorkflow = tempDir.resolve("direct-invalid-workspace-root.WORKFLOW.md");
        Map<String, String> replacements = Map.of(
                "<endpoint>",
                endpoint(),
                "<importWorkflow>",
                importWorkflow.toString(),
                "<manifest>",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "<workspaceFile>",
                workspaceFile.toString());

        // when
        CliRunResult result = runCli(scenario.commandArray(replacements));

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("setup_failed code=setup_invalid_arguments", scenario.expectedMessage())
                .stderrDoesNotContain("Troubleshooting report written", workspaceFile.toString(), "not a directory")
                .stdoutDoesNotContain("Created Trello board", "Imported Trello board");
        assertThat(createdBoardName).hasValue(null);
        assertThat(importWorkflow).doesNotExist();
    }

    private static Stream<InvalidDirectWorkspaceRootScenario> invalidDirectWorkspaceRootScenarios() {
        return Stream.of(
                directWorkspaceRootScenario(
                        "new-board blank",
                        "--workspace-root must not be empty.",
                        "new-board",
                        "--endpoint",
                        "<endpoint>",
                        "--key",
                        "key",
                        "--token",
                        "token",
                        "--name",
                        "Invalid Workspace",
                        "--workspace-id",
                        "workspace-1",
                        "--workspace-root",
                        ""),
                directWorkspaceRootScenario(
                        "new-board whitespace",
                        "--workspace-root must not be empty.",
                        "new-board",
                        "--endpoint",
                        "<endpoint>",
                        "--key",
                        "key",
                        "--token",
                        "token",
                        "--name",
                        "Invalid Workspace",
                        "--workspace-id",
                        "workspace-1",
                        "--workspace-root",
                        "   "),
                directWorkspaceRootScenario(
                        "import-board dot",
                        "--workspace-root must be an absolute path.",
                        "import-board",
                        "--endpoint",
                        "<endpoint>",
                        "--key",
                        "key",
                        "--token",
                        "token",
                        "--board",
                        "https://trello.com/b/input/existing-board",
                        "--active",
                        "Queue for Codex",
                        "--terminal",
                        "Released",
                        "--workflow",
                        "<importWorkflow>",
                        "--manifest",
                        "<manifest>",
                        "--force",
                        "--workspace-root",
                        "."),
                directWorkspaceRootScenario(
                        "import-board relative",
                        "--workspace-root must be an absolute path.",
                        "import-board",
                        "--endpoint",
                        "<endpoint>",
                        "--key",
                        "key",
                        "--token",
                        "token",
                        "--board",
                        "https://trello.com/b/input/existing-board",
                        "--active",
                        "Queue for Codex",
                        "--terminal",
                        "Released",
                        "--workflow",
                        "<importWorkflow>",
                        "--force",
                        "--workspace-root",
                        " ./relative "),
                directWorkspaceRootScenario(
                        "import-board file",
                        "--workspace-root must be a directory.",
                        "import-board",
                        "--endpoint",
                        "<endpoint>",
                        "--key",
                        "key",
                        "--token",
                        "token",
                        "--board",
                        "https://trello.com/b/input/existing-board",
                        "--active",
                        "Queue for Codex",
                        "--terminal",
                        "Released",
                        "--workflow",
                        "<importWorkflow>",
                        "--force",
                        "--workspace-root",
                        "<workspaceFile>"));
    }

    private static InvalidDirectWorkspaceRootScenario directWorkspaceRootScenario(
            String name, String expectedMessage, String... command) {
        return new InvalidDirectWorkspaceRootScenario(name, expectedMessage, List.of(command));
    }

    @Test
    void directImportBoardAllowsFilesystemRootWorkspaceRoot() throws Exception {
        // given
        Path workflow = tempDir.resolve("direct-root-workspace.WORKFLOW.md");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--force",
                "--workspace-root",
                "/");

        // then
        result.assertSuccess();
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("root: \"/\"");
    }

    @MethodSource("invalidLifecycleDirectoryOptionScenarios")
    @ParameterizedTest(name = "{0}")
    void lifecycleCommandsRejectBlankAndFileDirectoryOptionsBeforeWorkerHandling(
            InvalidLifecycleDirectoryOptionScenario scenario) throws Exception {
        // given
        Path configFile = tempDir.resolve("lifecycle-config-file");
        Path workspaceFile = tempDir.resolve("lifecycle-workspace-file");
        Path stateFile = tempDir.resolve("lifecycle-state-file");
        Files.writeString(configFile, "file", StandardCharsets.UTF_8);
        Files.writeString(workspaceFile, "file", StandardCharsets.UTF_8);
        Files.writeString(stateFile, "file", StandardCharsets.UTF_8);
        Map<String, String> replacements = Map.of(
                "<configFile>",
                configFile.toString(),
                "<workspaceFile>",
                workspaceFile.toString(),
                "<stateFile>",
                stateFile.toString());

        // when
        CliRunResult result = runCli(scenario.commandArray(replacements));

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("setup_failed code=setup_invalid_arguments", scenario.expectedMessage())
                .stderrDoesNotContain(
                        "Troubleshooting report written",
                        configFile.toString(),
                        workspaceFile.toString(),
                        stateFile.toString(),
                        "Not a directory")
                .stdoutDoesNotContain(
                        "running ",
                        "stopped ",
                        "Started Symphony",
                        "Trello authentication failed",
                        "untracked, no managed pid");
    }

    private static Stream<InvalidLifecycleDirectoryOptionScenario> invalidLifecycleDirectoryOptionScenarios() {
        return Stream.of("start", "stop", "status", "logs")
                .flatMap(command -> Stream.of(
                        lifecycleDirectoryScenario(
                                command + " blank config dir",
                                "--config-dir",
                                "--config-dir must not be empty.",
                                command,
                                "--config-dir",
                                ""),
                        lifecycleDirectoryScenario(
                                command + " blank workspace root",
                                "--workspace-root",
                                "--workspace-root must not be empty.",
                                command,
                                "--workspace-root",
                                ""),
                        lifecycleDirectoryScenario(
                                command + " blank state home",
                                "--state-home",
                                "--state-home must not be empty.",
                                command,
                                "--state-home",
                                ""),
                        lifecycleDirectoryScenario(
                                command + " config file",
                                "--config-dir",
                                "--config-dir must be a directory.",
                                command,
                                "--config-dir",
                                "<configFile>"),
                        lifecycleDirectoryScenario(
                                command + " workspace file",
                                "--workspace-root",
                                "--workspace-root must be a directory.",
                                command,
                                "--workspace-root",
                                "<workspaceFile>"),
                        lifecycleDirectoryScenario(
                                command + " state file",
                                "--state-home",
                                "--state-home must be a directory.",
                                command,
                                "--state-home",
                                "<stateFile>")));
    }

    @Test
    void rejectsControlCharactersInDirectNewBoardNameBeforeTrelloRequest() {
        // given
        String badBoardName = "Name\nWith newline";

        // when
        CliRunResult result = runCli(
                "new-board", "--endpoint", endpoint(), "--key", "key", "--token", "token", "--name", badBoardName);

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments", "--name must not contain control characters")
                .stderrDoesNotContain(badBoardName, "Troubleshooting report written")
                .stdoutDoesNotContain("Created Trello board", badBoardName);
        assertThat(createdBoardName).hasValue(null);
    }

    @Test
    void rejectsControlCharactersInDirectWorkspaceIdBeforeTrelloRequest() {
        // given
        String badWorkspaceId = "workspace\nId";

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Queue",
                "--workspace-id",
                badWorkspaceId);

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "--workspace-id must not contain control characters")
                .stderrDoesNotContain(badWorkspaceId, "Troubleshooting report written")
                .stdoutDoesNotContain("Created Trello board", badWorkspaceId);
        assertThat(createdBoardName).hasValue(null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"workspace/path", "https://trello.com/w/workspace", "C:\\workspace"})
    void rejectsUrlOrPathWorkspaceIdBeforeTrelloRequest(String badWorkspaceId) {
        // given

        // when
        CliRunResult result = runCli(
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Queue",
                "--workspace-id",
                badWorkspaceId);

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "--workspace-id must be a Trello Workspace id, not a URL or path.")
                .stderrDoesNotContain(badWorkspaceId, "Troubleshooting report written")
                .stdoutDoesNotContain("Created Trello board", badWorkspaceId);
        assertThat(workspaceLookups).hasValue(0);
        assertThat(createdBoardName).hasValue(null);
    }

    @Test
    void rejectsControlCharactersInDirectBoardSelectorBeforeTrelloRequest() {
        // given
        String badBoardSelector = "board\nselector";
        Path workflow = tempDir.resolve("control-selector.WORKFLOW.md");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                badBoardSelector,
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--force");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments", "--board must not contain control characters")
                .stderrDoesNotContain(badBoardSelector, "Troubleshooting report written")
                .stdoutDoesNotContain("Imported Trello board", badBoardSelector);
        assertThat(workflow).doesNotExist();
        assertThat(createdLists).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"--active", "--terminal", "--in-progress", "--blocked"})
    void rejectsControlCharactersInDirectImportListSelectorsBeforeTrelloRequest(String optionName) {
        // given
        String badListSelector = "Bad\n# injected\u001B[31mred\u001B[0m";
        Path workflow = tempDir.resolve("control-list-selector.WORKFLOW.md");
        List<String> args = new ArrayList<>(List.of(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board"));
        if (!"--active".equals(optionName)) {
            args.addAll(List.of("--active", "Queue for Codex"));
        }
        if (!"--terminal".equals(optionName)) {
            args.addAll(List.of("--terminal", "Released"));
        }
        args.addAll(List.of(optionName, badListSelector, "--workflow", workflow.toString(), "--force"));

        // when
        CliRunResult result = runCli(args.toArray(String[]::new));

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        optionName + " must not contain control characters")
                .stderrDoesNotContain(badListSelector, "\n# injected", "\u001B", "Troubleshooting report written")
                .stdoutDoesNotContain("Imported Trello board", badListSelector);
        assertThat(workflow).doesNotExist();
        assertThat(createdLists).isEmpty();
    }

    @MethodSource("ambiguousDirectImportListSelectors")
    @ParameterizedTest(name = "{0}")
    void importBoardRejectsAmbiguousDuplicateOpenListNames(
            String name, String optionName, String duplicatedName, String expectedCode, String expectedMessage) {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready","name":"Queue for Codex","closed":false,"pos":1},
                  {"id":"list-doing","name":"Doing","closed":false,"pos":2},
                  {"id":"list-review","name":"Review","closed":false,"pos":3},
                  {"id":"list-blocked","name":"Blocked","closed":false,"pos":4},
                  {"id":"list-done","name":"Released","closed":false,"pos":5},
                  {"id":"list-duplicate","name":"%s","closed":false,"pos":6}
                ]
                """
                        .formatted(duplicatedName));
        Path workflow = tempDir.resolve(name + ".WORKFLOW.md");
        Path manifest = workflow.getParent().resolve(ConnectedBoardManifest.FILE_NAME);
        List<String> args = new ArrayList<>(List.of(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--no-github",
                "--force"));
        if (!"--active".equals(optionName)) {
            args.addAll(List.of("--active", "Queue for Codex"));
        }
        if (!"--terminal".equals(optionName)) {
            args.addAll(List.of("--terminal", "Released"));
        }
        args.addAll(List.of(optionName, duplicatedName));

        // when
        CliRunResult result = runCli(args.toArray(String[]::new));

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("setup_failed code=" + expectedCode, expectedMessage)
                .stderrDoesNotContain("Troubleshooting report written")
                .stdoutDoesNotContain("Imported Trello board", "Wrote workflow");
        assertThat(workflow).doesNotExist();
        assertThat(manifest).doesNotExist();
        assertThat(createdLists).isEmpty();
    }

    private static Stream<Arguments> ambiguousDirectImportListSelectors() {
        return Stream.of(
                Arguments.of(
                        "ambiguous-active-list",
                        "--active",
                        "Queue for Codex",
                        "setup_ambiguous_active_state",
                        "Multiple open Trello lists match active list selector(s): \"Queue for Codex\""),
                Arguments.of(
                        "ambiguous-terminal-list",
                        "--terminal",
                        "Released",
                        "setup_ambiguous_terminal_state",
                        "Multiple open Trello lists match terminal list selector(s): \"Released\""),
                Arguments.of(
                        "ambiguous-in-progress-list",
                        "--in-progress",
                        "Doing",
                        "setup_ambiguous_in_progress_state",
                        "Multiple open Trello lists match in-progress list selector(s): \"Doing\""),
                Arguments.of(
                        "ambiguous-blocked-list",
                        "--blocked",
                        "Blocked",
                        "setup_ambiguous_blocked_state",
                        "Multiple open Trello lists match blocked list selector(s): \"Blocked\""));
    }

    @Test
    void importBoardRejectsAmbiguousDefaultActiveListName() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready-a","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-ready-b","name":"Ready  for Codex","closed":false,"pos":2},
                  {"id":"list-doing","name":"Doing","closed":false,"pos":3},
                  {"id":"list-blocked","name":"Blocked","closed":false,"pos":4},
                  {"id":"list-done","name":"Released","closed":false,"pos":5}
                ]
                """);
        Path workflow = tempDir.resolve("ambiguous-default-active.WORKFLOW.md");
        Path manifest = workflow.getParent().resolve(ConnectedBoardManifest.FILE_NAME);

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--no-github",
                "--force");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_ambiguous_active_state",
                        "Multiple open Trello lists match active list selector(s): \"Ready for Codex\"")
                .stderrDoesNotContain("Troubleshooting report written")
                .stdoutDoesNotContain("Imported Trello board", "Wrote workflow");
        assertThat(workflow).doesNotExist();
        assertThat(manifest).doesNotExist();
        assertThat(createdLists).isEmpty();
    }

    @MethodSource("overlappingDirectImportListRoles")
    @ParameterizedTest(name = "{0}")
    void importBoardRejectsOverlappingListRoles(String name, List<String> roleArgs, String expectedMessage) {
        // given
        if ("overlapping-github-merging-terminal".equals(name) || "overlapping-active-system-terminal".equals(name)) {
            boardListsResponse.set(
                    """
                    [
                      {"id":"list-ready","name":"Queue for Codex","closed":false,"pos":1},
                      {"id":"list-doing","name":"Doing","closed":false,"pos":2},
                      {"id":"list-review","name":"Review","closed":false,"pos":3},
                      {"id":"list-blocked","name":"Blocked","closed":false,"pos":4},
                      {"id":"list-merging","name":"Merging","closed":false,"pos":5},
                      {"id":"list-done","name":"Released","closed":false,"pos":6},
                      {"id":"list-archived","name":"Archived","closed":false,"pos":7}
                    ]
                    """);
        }
        Path workflow = tempDir.resolve(name + ".WORKFLOW.md");
        Path manifest = workflow.getParent().resolve(ConnectedBoardManifest.FILE_NAME);
        List<String> args = new ArrayList<>(List.of(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--force"));
        if (!"overlapping-github-merging-terminal".equals(name)) {
            args.add("--no-github");
        }
        args.addAll(roleArgs);

        // when
        CliRunResult result = runCli(args.toArray(String[]::new));

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("setup_failed code=setup_overlapping_list_roles", expectedMessage)
                .stderrDoesNotContain("Troubleshooting report written")
                .stdoutDoesNotContain("Imported Trello board", "Wrote workflow");
        assertThat(workflow).doesNotExist();
        assertThat(manifest).doesNotExist();
        assertThat(createdLists).isEmpty();
    }

    private static Stream<Arguments> overlappingDirectImportListRoles() {
        return Stream.of(
                Arguments.of(
                        "overlapping-active-terminal",
                        List.of("--active", "Queue for Codex", "--terminal", "Queue  for Codex"),
                        "active and terminal both use Queue for Codex"),
                Arguments.of(
                        "overlapping-active-blocked",
                        List.of(
                                "--active",
                                "Queue for Codex",
                                "--terminal",
                                "Released",
                                "--blocked",
                                "Queue for Codex"),
                        "active and blocked both use Queue for Codex"),
                Arguments.of(
                        "overlapping-in-progress-terminal",
                        List.of("--active", "Queue for Codex", "--terminal", "Doing", "--in-progress", "Doing"),
                        "terminal and in-progress both use Doing"),
                Arguments.of(
                        "overlapping-github-merging-terminal",
                        List.of("--active", "Queue for Codex", "--terminal", "Merging"),
                        "active and terminal both use Merging"),
                Arguments.of(
                        "overlapping-active-system-terminal",
                        List.of("--active", "Archived", "--terminal", "Released", "--blocked", "Blocked"),
                        "active and terminal both use Archived"));
    }

    @MethodSource("malformedDirectImportBoardSelectors")
    @ParameterizedTest(name = "{0}")
    void rejectsMalformedDirectImportBoardSelectorsBeforeTrelloRequest(String name, String badBoardSelector) {
        // given
        Path workflow = tempDir.resolve(name + ".WORKFLOW.md");

        // when
        CliRunResult result = runCli(
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                badBoardSelector,
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--force");

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "Invalid --board value. Use a Trello board URL, short link, or board id.")
                .stderrDoesNotContain(badBoardSelector, "Troubleshooting report written")
                .stdoutDoesNotContain("Imported Trello board", badBoardSelector);
        assertThat(workflow).doesNotExist();
        assertThat(createdLists).isEmpty();
    }

    @ParameterizedTest(name = "{0} rejects control characters in board selector")
    @ValueSource(strings = {"start", "stop", "status", "logs"})
    void lifecycleCommandsRejectControlCharactersInBoardSelectorBeforeSelection(String command) {
        // given
        String badBoardSelector = "No such board\n# injected\u001B[31mred\u001B[0m";

        // when
        CliRunResult result = runCli(command, "--board", badBoardSelector);

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments", "--board must not contain control characters")
                .stderrDoesNotContain(badBoardSelector, "\n# injected", "\u001B", "Troubleshooting report written")
                .stdoutDoesNotContain("running ", "stopped ", badBoardSelector);
    }

    @MethodSource("blankLifecycleSelectors")
    @ParameterizedTest(name = "{0} rejects blank {1}")
    void lifecycleCommandsRejectBlankSelectorsBeforeSelection(
            String command, String optionName, String optionValue, String expectedMessage) {
        // given

        // when
        CliRunResult result = runCli(command, optionName, optionValue);

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("setup_failed code=setup_invalid_arguments", expectedMessage)
                .stderrDoesNotContain("Troubleshooting report written")
                .stdoutDoesNotContain("running ", "stopped ", "Logs for ");
    }

    private static Stream<Arguments> blankLifecycleSelectors() {
        return Stream.of(
                Arguments.of("start", "--board", "", "--board must not be empty."),
                Arguments.of("stop", "--board", "   ", "--board must not be empty."),
                Arguments.of("status", "--board", "", "--board must not be empty."),
                Arguments.of("logs", "--board", "   ", "--board must not be empty."),
                Arguments.of("start", "--workflow", "", "--workflow must not be empty."),
                Arguments.of("stop", "--workflow", "   ", "--workflow must not be empty."),
                Arguments.of("status", "--workflow", "", "--workflow must not be empty."),
                Arguments.of("logs", "--workflow", "   ", "--workflow must not be empty."));
    }

    @Test
    void startRejectsDuplicateRunningBoardWithoutLeakingWorkflowPaths() throws Exception {
        // given
        Path configDir = tempDir.resolve("duplicate-board-config");
        Path stateHome = tempDir.resolve("duplicate-board-state");
        Path runningWorkflow = configDir.resolve("WORKFLOW.running.md");
        Path requestedWorkflow = configDir.resolve("WORKFLOW.requested.md");
        Path env = configDir.resolve(".env");
        Files.createDirectories(configDir);
        Files.writeString(env, TestEnv.trelloCredentials(), StandardCharsets.UTF_8);
        HttpServer runningWorker = startLoopbackServer();
        int runningPort = runningWorker.getAddress().getPort();
        Files.writeString(
                runningWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  endpoint: "%s"
                  board_id: board-conflict
                server:
                  port: %d
                ---
                # Running workflow
                """
                        .formatted(endpoint(), runningPort),
                StandardCharsets.UTF_8);
        Files.writeString(
                requestedWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  endpoint: "%s"
                  board_id: board-conflict
                server:
                  port: 19091
                ---
                # Requested workflow
                """
                        .formatted(endpoint()),
                StandardCharsets.UTF_8);
        ConnectedBoard runningBoard = connectedBoard(runningWorkflow, runningPort);
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(runningBoard)));
        runningWorker.createContext(
                "/api/v1/local-status",
                exchange -> respond(
                        exchange,
                        """
                        {"workflowPath":%s,"boardId":"board-conflict","configuredBoardId":"board-conflict","pid":4242}
                        """
                                .formatted(new ObjectMapper().writeValueAsString(runningWorkflow.toString()))));

        // when
        CliRunResult result;
        try {
            result = runCli(
                    "start",
                    "--workflow",
                    requestedWorkflow.toString(),
                    "--env",
                    env.toString(),
                    "--config-dir",
                    configDir.toString(),
                    "--state-home",
                    stateHome.toString());
        } finally {
            runningWorker.stop(0);
        }

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_worker_board_already_managed",
                        "Conflicting Trello Board",
                        "already managed by a running worker for another workflow",
                        "symphony-trello stop --workflow")
                .stderrDoesNotContain(
                        runningWorkflow.toString(),
                        requestedWorkflow.toString(),
                        configDir.toString(),
                        stateHome.toString(),
                        env.toString(),
                        tempDir.toString(),
                        ".log",
                        ".err");
    }

    @Test
    void startRejectsInvalidLiteralServerPortWithoutLeakingWorkflowPath() throws Exception {
        // given
        Path configDir = tempDir.resolve("literal-port-config");
        Path privateWorkflowDir = tempDir.resolve("Jane Doe workflows");
        Path stateHome = tempDir.resolve("literal-port-state");
        Files.createDirectories(configDir);
        Files.createDirectories(privateWorkflowDir);
        Path workflow = privateWorkflowDir.resolve("WORKFLOW.literal-port.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                server:
                  port: 18123.5
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        Path env = configDir.resolve(".env");
        Files.writeString(env, TestEnv.trelloCredentials(), StandardCharsets.UTF_8);

        // when
        CliRunResult result = runCli(
                "start",
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString(),
                "--config-dir",
                configDir.toString(),
                "--state-home",
                stateHome.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_workflow_invalid",
                        "server.port",
                        "whole number",
                        "selected workflow file")
                .stderrDoesNotContain(
                        "Troubleshooting report written",
                        ".log",
                        ".err",
                        workflow.toString(),
                        privateWorkflowDir.toString(),
                        tempDir.toString(),
                        "Jane Doe")
                .stdoutDoesNotContain("Started Symphony for Trello");
        try (var stateFiles = Files.list(stateHome)) {
            assertThat(stateFiles.filter(file -> file.getFileName().toString().endsWith(".pid")))
                    .as("rejected start writes no managed pid state")
                    .isEmpty();
        }
    }

    @Test
    void startRejectsMissingExplicitWorkflowWithoutTroubleshootingReport() {
        // given
        Path configDir = tempDir.resolve("missing-start-config");
        Path stateHome = tempDir.resolve("missing-start-state");
        Path workspaceRoot = tempDir.resolve("missing-start-workspaces");
        Path workflow = configDir.resolve("missing.WORKFLOW.md");

        // when
        CliRunResult result = runCli(
                "start",
                "--config-dir",
                configDir.toString(),
                "--state-home",
                stateHome.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--workflow",
                workflow.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_workflow_invalid",
                        "missing workflow file",
                        "Fix the workflow front matter")
                .stderrDoesNotContain("Troubleshooting report written")
                .stdoutDoesNotContain("Started Symphony for Trello", "running ", "stopped ", "Logs for ");
        assertThat(stateHome).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"status", "stop", "logs"})
    void lifecycleCommandsRejectMissingExplicitWorkflowWithoutLeakingPath(String command) {
        // given
        Path configDir = tempDir.resolve("missing-lifecycle-config");
        Path stateHome = tempDir.resolve("missing-lifecycle-state");
        Path workflow = configDir.resolve("missing.WORKFLOW.md");

        // when
        CliRunResult result = runCli(
                command,
                "--config-dir",
                configDir.toString(),
                "--state-home",
                stateHome.toString(),
                "--workflow",
                workflow.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "--workflow must point to an existing workflow file.")
                .stderrDoesNotContain(workflow.toString(), configDir.toString(), tempDir.toString())
                .stdoutDoesNotContain("running ", "stopped ", "already stopped", "Logs for ");
        assertThat(stateHome).doesNotExist();
    }

    @ParameterizedTest(name = "{0} rejects a missing explicit workflow despite stale managed state")
    @ValueSource(strings = {"status", "stop", "logs"})
    void lifecycleCommandsRejectMissingExplicitWorkflowBeforeReadingManagedState(String command) throws Exception {
        // given
        Path configDir = tempDir.resolve("missing-lifecycle-state-config");
        Path stateHome = tempDir.resolve("missing-lifecycle-state-home");
        Path workflow = configDir.resolve("missing.WORKFLOW.md");
        ManagedProcessStore.ManagedProcessFiles files = new ManagedProcessStore(stateHome).files(workflow);
        Files.createDirectories(stateHome);
        Files.writeString(files.pidFile(), "42", StandardCharsets.UTF_8);
        Files.writeString(files.stdoutLog(), "stdout should not be printed\n", StandardCharsets.UTF_8);
        Files.writeString(files.stderrLog(), "stderr should not be printed\n", StandardCharsets.UTF_8);
        Files.writeString(files.processLockFile(), "lock should remain\n", StandardCharsets.UTF_8);
        String pidBefore = Files.readString(files.pidFile(), StandardCharsets.UTF_8);
        String stdoutBefore = Files.readString(files.stdoutLog(), StandardCharsets.UTF_8);
        String stderrBefore = Files.readString(files.stderrLog(), StandardCharsets.UTF_8);
        String lockBefore = Files.readString(files.processLockFile(), StandardCharsets.UTF_8);
        assertThat(workflow).doesNotExist();

        // when
        CliRunResult result = runCli(
                command,
                "--config-dir",
                configDir.toString(),
                "--state-home",
                stateHome.toString(),
                "--workflow",
                workflow.toString());

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments",
                        "--workflow must point to an existing workflow file.")
                .stderrDoesNotContain(
                        workflow.toString(),
                        configDir.toString(),
                        stateHome.toString(),
                        tempDir.toString(),
                        files.pidFile().getFileName().toString(),
                        files.stdoutLog().getFileName().toString(),
                        files.stderrLog().getFileName().toString(),
                        "Troubleshooting report written")
                .stdoutDoesNotContain(
                        "running ",
                        "stopped ",
                        "already stopped",
                        "Logs for ",
                        "stdout should not be printed",
                        "stderr should not be printed");
        assertThat(Files.readString(files.pidFile(), StandardCharsets.UTF_8)).isEqualTo(pidBefore);
        assertThat(Files.readString(files.stdoutLog(), StandardCharsets.UTF_8)).isEqualTo(stdoutBefore);
        assertThat(Files.readString(files.stderrLog(), StandardCharsets.UTF_8)).isEqualTo(stderrBefore);
        assertThat(Files.readString(files.processLockFile(), StandardCharsets.UTF_8))
                .isEqualTo(lockBefore);
    }

    @Test
    void diagnosticsRejectsControlCharactersInBoardSelectorWithoutRenderingReport() {
        // given
        String badBoardSelector = "No such board\n# injected\u001B[31mred\u001B[0m";

        // when
        CliRunResult result = runCli("diagnostics", "--board", badBoardSelector);

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains(
                        "setup_failed code=setup_invalid_arguments", "--board must not contain control characters")
                .stderrDoesNotContain(badBoardSelector, "\n# injected", "\u001B", "Troubleshooting report written")
                .stdoutDoesNotContain("# Symphony for Trello Diagnostics", badBoardSelector);
    }

    @Test
    void parameterErrorsNeutralizeControlCharactersInMessages() {
        // given
        String badCommand = "bad\n# injected\u001B[31mred\u001B[0m";

        // when
        CliRunResult result = runCli(badCommand);

        // then
        result.assertFailure(SETUP_FAILURE)
                .stderrContains("setup_failed code=setup_invalid_arguments", "bad\\n# injected\\u001B[31mred\\u001B[0m")
                .stderrDoesNotContain(badCommand, "\n# injected", "\u001B", "Troubleshooting report written")
                .stdoutDoesNotContain(badCommand);
    }

    @Test
    void missingTrelloApiKeyPrintsHintWithoutTroubleshootingReport() throws Exception {
        // given
        Path workingDir = tempDir.resolve("missing-credentials-run");
        Files.createDirectories(workingDir);

        // when
        MainProcessResult result =
                runMainProcessWithoutTrelloCredentials(workingDir, "new-board", "--name", "Test Board 1");

        // then
        assertThat(result.exitCode()).isEqualTo(SETUP_FAILURE);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr())
                .contains(
                        "setup_failed code=setup_missing_api_key message=Missing Trello API key",
                        "Next step: Provide Trello credentials with --key and --token")
                .doesNotContain(
                        "Troubleshooting report written",
                        "Open a GitHub issue",
                        "Issue body:",
                        "# Symphony for Trello Setup Failure");
    }

    private static Stream<Arguments> invalidCliArgumentScenarios() {
        return Stream.of(
                Arguments.of(
                        "unknown option", new String[] {"new-board", "--name", "Queue", "--unknown"}, 2, new String[] {
                            "setup_failed code=setup_invalid_arguments", "Unknown option: '--unknown'"
                        }),
                Arguments.of("unknown command", new String[] {"create-board"}, 2, new String[] {
                    "setup_failed code=setup_invalid_arguments", "Unmatched argument: 'create-board'"
                }),
                Arguments.of("repair-port missing board", new String[] {"setup-local", "repair-port"}, 2, new String[] {
                    "setup_failed code=setup_invalid_arguments", "Missing required option: '--board=<board>'"
                }),
                Arguments.of(
                        "github mode conflict",
                        new String[] {"new-board", "--name", "Queue", "--github", "--no-github"},
                        2,
                        new String[] {"setup_failed code=setup_invalid_arguments", "--github", "--no-github"}),
                Arguments.of(
                        "board selector conflict",
                        new String[] {"setup-local", "--board", "input", "--board-name", "Queue", "--dry-run"},
                        2,
                        new String[] {
                            "setup_failed code=setup_invalid_arguments",
                            "--board and --board-name cannot be used together"
                        }),
                Arguments.of(
                        "configure-github parent no-github conflict",
                        new String[] {"setup-local", "--no-github", "configure-github"},
                        2,
                        new String[] {
                            "setup_failed code=setup_invalid_arguments",
                            "--no-github cannot be used with configure-github"
                        }),
                Arguments.of(
                        "configure-github child no-github conflict",
                        new String[] {"setup-local", "configure-github", "--no-github"},
                        2,
                        new String[] {
                            "setup_failed code=setup_invalid_arguments",
                            "--no-github cannot be used with configure-github"
                        }),
                Arguments.of(
                        "invalid port",
                        new String[] {"new-board", "--name", "Queue", "--server-port", "70000"},
                        2,
                        new String[] {
                            "setup_failed code=setup_invalid_port",
                            "--server-port must be between 1024 and 65535 for local HTTP status."
                        }),
                Arguments.of(
                        "allow all paths without root",
                        new String[] {"setup-local", "--allow-all-paths", "--dry-run"},
                        2,
                        new String[] {
                            "setup_failed code=setup_allow_all_paths_without_root",
                            "--allow-all-paths is only valid together with --add-path /"
                        }),
                Arguments.of("missing option value", new String[] {"new-board", "--name"}, 2, new String[] {
                    "setup_failed code=setup_invalid_arguments", "Missing required parameter"
                }));
    }

    private record InvalidPathOptionCase(
            String name, String optionName, String rawValue, String forbiddenOutput, List<String> command) {
        private InvalidPathOptionCase(
                String name, String optionName, String rawValue, String forbiddenOutput, String... command) {
            this(name, optionName, rawValue, forbiddenOutput, List.of(command));
        }

        private String[] commandArray() {
            return commandArray(Map.of());
        }

        private String[] commandArray(Map<String, String> replacements) {
            return command.stream()
                    .map(value -> replacements.getOrDefault(value, value))
                    .toArray(String[]::new);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static InvalidPathOptionCase invalidPathOptionCase(
            String name, String optionName, String rawValue, String forbiddenOutput, String... command) {
        return new InvalidPathOptionCase(name, optionName, rawValue, forbiddenOutput, command);
    }

    private record InvalidDirectWorkspaceRootScenario(String name, String expectedMessage, List<String> command) {
        private String[] commandArray(Map<String, String> replacements) {
            return command.stream()
                    .map(value -> replacements.getOrDefault(value, value))
                    .toArray(String[]::new);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private record InvalidLifecycleDirectoryOptionScenario(
            String name, String optionName, String expectedMessage, List<String> command) {
        private String[] commandArray() {
            return commandArray(Map.of());
        }

        private String[] commandArray(Map<String, String> replacements) {
            return command.stream()
                    .map(value -> replacements.getOrDefault(value, value))
                    .toArray(String[]::new);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static InvalidLifecycleDirectoryOptionScenario lifecycleDirectoryScenario(
            String name, String optionName, String expectedMessage, String... command) {
        return new InvalidLifecycleDirectoryOptionScenario(name, optionName, expectedMessage, List.of(command));
    }

    private CliRunResult runCli(String... args) {
        return runCli(() -> TrelloBoardSetup.CodexModelDefaults.fallback(), args);
    }

    private CliRunResult runCliWithoutTrelloCredentials(String... args) throws IOException, InterruptedException {
        MainProcessResult result = runMainProcessWithoutTrelloCredentials(tempDir, args);
        return new CliRunResult(result.exitCode(), result.stdout(), result.stderr());
    }

    private CliRunResult runCli(Map<String, String> properties, String... args) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exitCode = TrelloBoardSetupMain.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                TrelloBoardSetup.CodexModelDefaults.fallback(),
                properties);
        return new CliRunResult(
                exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private CliRunResult runNewBoardWithRuntimeEnv(Path workflow, Path env, boolean includeCredentials) {
        SetupCommandBuilder command = SetupCommandBuilder.newBoard(endpoint());
        if (includeCredentials) {
            command.credentials("direct-key", "direct-token");
        }
        return runCli(command.name("Runtime Env Queue")
                .workflow(workflow)
                .manifest(workflow.resolveSibling(ConnectedBoardManifest.FILE_NAME))
                .env(env)
                .build());
    }

    private CliRunResult runImportBoardWithRuntimeEnv(Path workflow, Path env, boolean includeCredentials) {
        SetupCommandBuilder command = SetupCommandBuilder.command("import-board");
        if (includeCredentials) {
            command.credentials("direct-key", "direct-token");
        }
        return runCli(command.endpoint(endpoint())
                .board("https://trello.com/b/input/existing-board")
                .active("Queue for Codex")
                .terminal("Released")
                .workflow(workflow)
                .manifest(workflow.resolveSibling(ConnectedBoardManifest.FILE_NAME))
                .env(env)
                .build());
    }

    private String[] setupCommandWithRuntimeEnv(String command, Path workflow, String envValue) {
        SetupCommandBuilder builder = directSetupCommand(command).credentials("direct-key", "direct-token");
        if ("new-board".equals(command)) {
            builder.name("Runtime Env Queue");
        } else {
            builder.board("https://trello.com/b/input/existing-board")
                    .active("Queue for Codex")
                    .terminal("Released");
        }
        return builder.workflow(workflow).env(envValue).build();
    }

    private String[] setupCommandWithWorkflow(String command, String workflowValue, Path env) {
        SetupCommandBuilder builder = directSetupCommand(command).credentials("direct-key", "direct-token");
        if ("new-board".equals(command)) {
            builder.name("Workflow Path Queue").workspaceId("workspace-1");
        } else {
            builder.board("input").active("Queue for Codex").terminal("Released");
        }
        return builder.workflow(workflowValue).env(env).build();
    }

    private String[] setupCommandWithCodexOverride(
            String command, Path workflow, Path env, String optionName, String optionValue) {
        SetupCommandBuilder builder = directSetupCommand(command).credentials("key", "token");
        if ("new-board".equals(command)) {
            builder.name("Codex Scalar Queue").workspaceId("workspace-1");
        } else {
            builder.board("input").active("Queue for Codex").terminal("Released");
        }
        return builder.workflow(workflow)
                .env(env)
                .option(optionName, optionValue)
                .build();
    }

    private SetupCommandBuilder directSetupCommand(String command) {
        if ("new-board".equals(command)) {
            return SetupCommandBuilder.newBoard(endpoint());
        }
        if ("import-board".equals(command)) {
            return SetupCommandBuilder.importBoard(endpoint());
        }
        throw new IllegalArgumentException("Unsupported direct setup command: " + command);
    }

    private record InvalidRuntimeEnvPathScenario(
            String name, String envValue, boolean directory, List<String> expectedErrorFragments) {
        private static InvalidRuntimeEnvPathScenario plain(
                String name, String envValue, String... expectedErrorFragments) {
            return new InvalidRuntimeEnvPathScenario(name, envValue, false, List.of(expectedErrorFragments));
        }

        private static InvalidRuntimeEnvPathScenario directoryScenario() {
            return new InvalidRuntimeEnvPathScenario(
                    "directory",
                    "envdir",
                    true,
                    List.of(
                            "setup_failed code=setup_invalid_arguments",
                            "--env must point to a dotenv file path, not a directory."));
        }

        private String resolveEnvValue(Path base) throws IOException {
            if (!directory) {
                return envValue;
            }
            Path envDirectory = base.resolve(envValue);
            Files.createDirectories(envDirectory);
            return envDirectory.toString();
        }

        private List<String> rawPrivateValues() {
            return envValue.isBlank() ? List.of() : List.of(envValue);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private void assertRejectedUnsafeRuntimeEnvPath(
            int exitCode, String stdout, String stderr, Path workflow, Path env, String... secrets) {
        assertThat(exitCode).isEqualTo(2);
        assertThat(createdBoardName.get()).isNull();
        assertThat(workflow).doesNotExist();
        assertThat(env).content(StandardCharsets.UTF_8).isEqualTo("readme");
        assertThat(stdout).doesNotContain("Created Trello board", "Troubleshooting report written");
        assertThat(stderr)
                .contains("setup_failed code=setup_env_path_not_ignored", ".env or .env.NAME")
                .doesNotContain(secrets);
    }

    private CliRunResult runCliWithEnvironment(Map<String, String> environment, String... args) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        TrelloBoardSetup setup = new TrelloBoardSetup(
                new ObjectMapper(),
                () -> CodexModelSelectionDefaults.of(TrelloBoardSetup.CodexModelDefaults.fallback()));
        LocalWorkerManager workerManager = new LocalWorkerManager(environment);
        int exitCode = TrelloBoardSetupMain.run(
                args,
                new TrelloBoardSetupService(setup, workerManager, environment),
                new LocalSetup(setup, new ProcessCommandRunner()),
                workerManager,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new CliRunResult(
                exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private CliRunResult runCli(TrelloBoardSetup setup, LocalWorkerManager workerManager, String... args) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exitCode = TrelloBoardSetupMain.run(
                args,
                new TrelloBoardSetupService(setup, workerManager, Map.of()),
                new LocalSetup(setup, new ProcessCommandRunner()),
                workerManager,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new CliRunResult(
                exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private CliRunResult runCli(Supplier<TrelloBoardSetup.CodexModelDefaults> codexModelDefaults, String... args) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exitCode = TrelloBoardSetupMain.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                codexModelDefaults);
        return new CliRunResult(
                exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private CliRunResult runCliWithSelectionDefaults(
            CodexModelSelectionDefaults codexModelSelectionDefaults, String... args) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        TrelloBoardSetup setup = new TrelloBoardSetup(new ObjectMapper(), codexModelSelectionDefaults);
        int exitCode = TrelloBoardSetupMain.run(
                args,
                new TrelloBoardSetupService(setup),
                new LocalSetup(setup, new ProcessCommandRunner()),
                new LocalWorkerManager(System.getenv()),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new CliRunResult(
                exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static Stream<Arguments> directCommandHelp() {
        return Stream.of(
                Arguments.of("new-board", "Usage: symphony-trello new-board"),
                Arguments.of("import-board", "Usage: symphony-trello import-board"),
                Arguments.of("list-workspaces", "Usage: symphony-trello list-workspaces"),
                Arguments.of("diagnostics", "Usage: symphony-trello diagnostics"));
    }

    private static Stream<Arguments> commandsThatDoNotWriteWorkflows() {
        return Stream.of(
                Arguments.of("root help", new String[] {"--help"}),
                Arguments.of("root version", new String[] {"--version"}),
                Arguments.of("status help", new String[] {"status", "--help"}),
                Arguments.of("diagnostics help", new String[] {"diagnostics", "--help"}),
                Arguments.of("setup-local help", new String[] {"setup-local", "--help"}));
    }

    private static Stream<Arguments> versionCommands() {
        return Stream.of(
                Arguments.of((Object) new String[] {"--version"}),
                Arguments.of((Object) new String[] {"setup-local", "--version"}),
                Arguments.of((Object) new String[] {"setup-local", "check", "--version"}),
                Arguments.of((Object) new String[] {"setup-local", "repair-port", "--version"}),
                Arguments.of((Object) new String[] {"setup-local", "configure-github", "--version"}),
                Arguments.of((Object) new String[] {"new-board", "--version"}),
                Arguments.of((Object) new String[] {"import-board", "--version"}),
                Arguments.of((Object) new String[] {"list-workspaces", "--version"}),
                Arguments.of((Object) new String[] {"diagnostics", "--version"}));
    }

    private static Stream<Arguments> mainProcessExitCases() {
        return Stream.of(
                Arguments.of(new MainProcessCase(new String[] {"--help"}, 0, "Usage: symphony-trello")),
                Arguments.of(new MainProcessCase(
                        new String[] {"definitely-not-a-command"}, 2, "setup_failed code=setup_invalid_arguments")),
                Arguments.of(new MainProcessCase(
                        new String[] {"setup-local", "repair-port"}, 2, "Missing required option: '--board=<board>'")),
                Arguments.of(new MainProcessCase(
                        new String[] {"setup-local", "configure-github", "--no-github"},
                        2,
                        "--no-github cannot be used with configure-github")),
                Arguments.of(new MainProcessCase(
                        new String[] {
                            "import-board", "--board", "abc123", "--in-progress", "Working", "--no-in-progress"
                        },
                        2,
                        "--in-progress cannot be used with --no-in-progress")),
                Arguments.of(new MainProcessCase(
                        new String[] {"setup-local", "--board", "abc123", "--in-progress", "Working", "--no-in-progress"
                        },
                        2,
                        "--in-progress cannot be used with --no-in-progress")));
    }

    private static Stream<Arguments> malformedDirectImportBoardSelectors() {
        return Stream.of(
                Arguments.of("trello-root-url", "https://trello.com/"),
                Arguments.of("trello-board-url-without-id", "https://trello.com/b/"),
                Arguments.of("trello-card-url", "https://trello.com/c/abc123/not-a-board"),
                Arguments.of("non-trello-url", "https://example.com/b/abc123/name"),
                Arguments.of("not-trello-url", "https://not-trello.com/b/abc123/name"),
                Arguments.of("prefixed-trello-url", "https://mytrello.com/b/abc123/name"),
                Arguments.of("hyphenated-trello-url", "https://company-trello.com/b/abc123/name"),
                Arguments.of("embedded-trello-url", "https://evil.example/https://trello.com/b/abc123/name"),
                Arguments.of("missing-host-url", "https:///b/abc123/name"),
                Arguments.of("slash-containing-opaque-selector", "abc/def"));
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String powerShellQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String cmdQuote(String value) {
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }

    private static MainProcessResult runMainProcess(String... arguments) throws IOException, InterruptedException {
        return runMainProcess(Path.of(".").toAbsolutePath().normalize(), Map.of(), List.of(), arguments);
    }

    private static MainProcessResult runMainProcessWithoutTrelloCredentials(Path workingDir, String... arguments)
            throws IOException, InterruptedException {
        return runMainProcess(workingDir, Map.of(), List.of("TRELLO_API_KEY", "TRELLO_API_TOKEN"), arguments);
    }

    private MainProcessResult runStartWithoutTrelloCredentials(
            Path configDir, Path workspaceRoot, Path stateHome, Path appHome, Path workflow, Path env)
            throws IOException, InterruptedException {
        return runMainProcessWithoutTrelloCredentials(
                tempDir,
                "start",
                "--config-dir",
                configDir.toString(),
                "--workspace-root",
                workspaceRoot.toString(),
                "--state-home",
                stateHome.toString(),
                "--app-home",
                appHome.toString(),
                "--workflow",
                workflow.toString(),
                "--env",
                env.toString());
    }

    private static MainProcessResult runMainProcess(
            Path workingDir, Map<String, String> environment, List<String> environmentToRemove, String... arguments)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", javaExecutable())
                .toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(TrelloBoardSetupMain.class.getName());
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder processBuilder = new ProcessBuilder(command).directory(workingDir.toFile());
        environmentToRemove.forEach(processBuilder.environment()::remove);
        processBuilder.environment().putAll(environment);
        Process process = processBuilder.start();
        byte[] stdout;
        byte[] stderr;
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("Timed out waiting for " + String.join(" ", command));
            }
            stdout = process.getInputStream().readAllBytes();
            stderr = process.getErrorStream().readAllBytes();
        } finally {
            process.destroyForcibly();
        }
        return new MainProcessResult(
                process.exitValue(),
                new String(stdout, StandardCharsets.UTF_8),
                new String(stderr, StandardCharsets.UTF_8));
    }

    private static void createFifo(Path path, Path workingDir) throws IOException, InterruptedException {
        assertThat(new ProcessBuilder("mkfifo", path.toString())
                        .directory(workingDir.toFile())
                        .start()
                        .waitFor())
                .isZero();
    }

    private static void writeExecutable(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        assertThat(path.toFile().setExecutable(true)).isTrue();
    }

    private static String javaExecutable() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
    }

    private static ConnectedBoard connectedBoard(Path workflowPath, int serverPort) {
        Path normalizedWorkflow = workflowPath.toAbsolutePath().normalize();
        Path parent = normalizedWorkflow.getParent();
        Path base = parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
        return ConnectedBoardBuilder.connectedBoard(normalizedWorkflow)
                .withBoardId("board-conflict")
                .withBoardKey("SYNTH004")
                .withBoardName("Conflicting Trello Board")
                .withBoardUrl("https://trello.com/b/SYNTH004/board")
                .withEnvPath(base.resolve(".env.conflict"))
                .withWorkspaceRoot(base.resolve("workspaces"))
                .withServerPort(serverPort)
                .withGithubEnabled(true)
                .build();
    }

    private void assertConnectedBoardUsesWorkflowEnvAndPort(Path workflow, Path env, int serverPort)
            throws IOException {
        ConnectedBoardManifest manifest =
                new ConnectedBoardRepository(tempDir.resolve(ConnectedBoardManifest.FILE_NAME)).load();
        assertThat(manifest.boards()).singleElement().satisfies(board -> {
            assertThat(board.workflowPath()).isEqualTo(workflow.toAbsolutePath().normalize());
            assertThat(board.envPath()).isEqualTo(env.toAbsolutePath().normalize());
            assertThat(board.serverPort()).isEqualTo(serverPort);
        });
    }

    private static int firstAvailableManagedPort(int... reservedPorts) {
        for (int port = ConfigDefaults.DEFAULT_SERVER_PORT; port <= LocalPort.MAX; port++) {
            if (!contains(reservedPorts, port) && !LocalHealthChecker.portAcceptsConnections(port)) {
                return port;
            }
        }
        throw new AssertionError("No free managed test port found.");
    }

    private static boolean contains(int[] ports, int candidate) {
        for (int port : ports) {
            if (port == candidate) {
                return true;
            }
        }
        return false;
    }

    private static HttpServer startLoopbackServer() throws IOException {
        HttpServer listeningServer =
                HttpServer.create(new InetSocketAddress(LocalHealthChecker.loopbackIpv4ForTests(), 0), 0);
        listeningServer.start();
        return listeningServer;
    }

    private record MainProcessCase(List<String> arguments, int expectedExitCode, String expectedOutput) {
        private MainProcessCase(String[] arguments, int expectedExitCode, String expectedOutput) {
            this(List.of(arguments), expectedExitCode, expectedOutput);
        }

        String[] argumentsArray() {
            return arguments.toArray(String[]::new);
        }

        @Override
        public String toString() {
            return String.join(" ", arguments);
        }
    }

    private record MainProcessResult(int exitCode, String stdout, String stderr) {
        String output() {
            return stdout + stderr;
        }
    }

    private String endpoint() {
        return "http://127.0.0.1:" + trello.endpointUri().getPort() + "/1";
    }

    private String endpointRoot() {
        return "http://127.0.0.1:" + trello.endpointUri().getPort();
    }

    private record ReplacedRunningWorkerFixture(
            ConnectedBoard previousBoard, Path newWorkflow, Path env, LocalWorkerManager workerManager) {}

    private ReplacedRunningWorkerFixture replacedRunningWorkerFixture(String slug) throws Exception {
        Path previousWorkflow = tempDir.resolve(slug + "-previous.WORKFLOW.md");
        Path newWorkflow = tempDir.resolve(slug + "-new.WORKFLOW.md");
        Path env = tempDir.resolve(".env." + slug);
        Files.writeString(previousWorkflow, "previous workflow", StandardCharsets.UTF_8);
        ConnectedBoard previousBoard = ConnectedBoardBuilder.connectedBoard(
                        previousWorkflow.toAbsolutePath().normalize())
                .withBoardId("board-1")
                .withBoardName("Existing Board")
                .withBoardUrl("https://trello.com/b/SYNTH001/board")
                .withEnvPath(env.toAbsolutePath().normalize())
                .withWorkspaceRoot(
                        TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT.toAbsolutePath().normalize())
                .withGithubEnabled(true)
                .build();
        new ConnectedBoardRepository(tempDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(List.of(previousBoard)));
        LocalWorkerManager workerManager = mock();
        when(workerManager.canStopRunningWorker(any(LocalWorkerPaths.class), eq(previousBoard)))
                .thenReturn(true);
        return new ReplacedRunningWorkerFixture(previousBoard, newWorkflow, env, workerManager);
    }

    private CliRunResult runForcedImportBoard(ReplacedRunningWorkerFixture fixture) throws Exception {
        TrelloBoardSetup boardSetup = new TrelloBoardSetup(
                new ObjectMapper(),
                () -> CodexModelSelectionDefaults.of(TrelloBoardSetup.CodexModelDefaults.fallback()));
        return runCli(
                boardSetup,
                fixture.workerManager(),
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "https://trello.com/b/input/existing-board",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                fixture.newWorkflow().toString(),
                "--manifest",
                tempDir.resolve(ConnectedBoardManifest.FILE_NAME).toString(),
                "--env",
                fixture.env().toString(),
                "--force");
    }
}
