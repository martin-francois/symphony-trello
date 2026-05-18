package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TrelloBoardSetupMainTest {
    private static final String CONFIG_DIR_PROPERTY = "symphony.trello.config.dir";
    private static final String SHELL_PROPERTY = "symphony.trello.shell";

    private HttpServer server;
    private final List<String> createdLists = new ArrayList<>();
    private final AtomicReference<String> createdBoardName = new AtomicReference<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/1/members/me/organizations",
                exchange -> respond(
                        exchange,
                        """
                [
                  {"id":"workspace-1","name":"engineering","displayName":"Engineering","url":"https://trello.com/w/engineering"}
                ]
                """));
        server.createContext("/1/boards/", exchange -> {
            Map<String, String> query = query(exchange);
            createdBoardName.set(query.get("name"));
            respond(
                    exchange,
                    """
                    {"id":"board-1","name":"%s","shortLink":"abc123","url":"https://trello.com/b/abc123/board"}
                    """
                            .formatted(query.get("name")));
        });
        server.createContext("/1/lists", exchange -> {
            Map<String, String> query = query(exchange);
            createdLists.add(query.get("name"));
            respond(exchange, "{\"id\":\"list-" + createdLists.size() + "\",\"name\":\"" + query.get("name") + "\"}");
        });
        server.createContext(
                "/1/boards/input",
                exchange -> respond(
                        exchange,
                        """
                {"id":"board-1","name":"Existing Board","shortLink":"existing","url":"https://trello.com/b/existing/board","closed":false}
                """));
        server.createContext(
                "/1/boards/board-1/lists",
                exchange -> respond(
                        exchange,
                        """
                [
                  {"id":"list-ready","name":"Queue for Codex","closed":false,"pos":1},
                  {"id":"list-doing","name":"Doing","closed":false,"pos":2},
                  {"id":"list-review","name":"Review","closed":false,"pos":3},
                  {"id":"list-blocked","name":"Blocked","closed":false,"pos":4},
                  {"id":"list-done","name":"Released","closed":false,"pos":5}
                ]
                """));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void printsHelpWithoutRequiringCredentials() {
        // given
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode = run(stdout, stderr, "--help");

        // then
        assertThat(exitCode).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("new-board")
                .contains("import-board");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("commandsThatDoNotWriteWorkflows")
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("mainProcessExitCases")
    void exitsWithMainProcessStatus(MainProcessCase testCase) throws Exception {
        // given
        String[] arguments = testCase.arguments();

        // when
        MainProcessResult result = runMainProcess(arguments);

        // then
        assertThat(result.exitCode()).as(result.output()).isEqualTo(testCase.expectedExitCode());
        assertThat(result.output()).contains(testCase.expectedOutput());
    }

    @Test
    void printsSetupLocalHelpWithoutRequiringCredentials() {
        // given
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode = run(stdout, stderr, "setup-local", "--help");

        // then
        assertThat(exitCode).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("Usage: symphony-trello setup-local")
                .contains("Commands:")
                .contains("check")
                .contains("--no-github");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void printsNestedSetupLocalHelp() {
        // given
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int checkExitCode = run(stdout, stderr, "setup-local", "check", "--help");
        int repairExitCode = run(stdout, stderr, "setup-local", "repair-port", "--help");
        int configureExitCode = run(stdout, stderr, "setup-local", "configure-github", "--help");

        // then
        assertThat(checkExitCode).isZero();
        assertThat(repairExitCode).isZero();
        assertThat(configureExitCode).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("Usage: symphony-trello setup-local check")
                .contains("Usage: symphony-trello setup-local repair-port")
                .contains("Usage: symphony-trello setup-local configure-github");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @ParameterizedTest(name = "{0} help")
    @MethodSource("directCommandHelp")
    void printsDirectCommandHelp(String command, String expectedUsage) {
        // given
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode = run(stdout, stderr, command, "--help");

        // then
        assertThat(exitCode).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains(expectedUsage);
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @ParameterizedTest(name = "{0} version")
    @MethodSource("versionCommands")
    void printsVersionForCommands(String[] command) {
        // given
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode = run(stdout, stderr, command);

        // then
        assertThat(exitCode).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("symphony-trello");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void listsWorkspacesFromCommandLineCredentials() {
        // given
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode =
                run(stdout, stderr, "list-workspaces", "--endpoint", endpoint(), "--key", "key", "--token", "token");

        // then
        assertThat(exitCode).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("Trello workspaces:")
                .contains("workspace-1")
                .contains("Engineering");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void createsRecommendedBoardAndPrintsNextSteps() {
        // given
        Path workflow = tempDir.resolve("generated workflow.WORKFLOW.md");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode = run(
                stdout,
                stderr,
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
                "--server-port",
                "18081");

        // then
        assertThat(exitCode).isZero();
        assertThat(createdBoardName).hasValue("Symphony Work Queue");
        assertThat(createdLists)
                .containsExactly(
                        "Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Merging", "Done");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("board_id: \"abc123\"")
                .contains("port: 18081");
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("Created Trello board: Symphony Work Queue")
                .contains("Wrote workflow:")
                .contains("HTTP status port: 18081")
                .contains(
                        "symphony-trello start --env "
                                + shellQuote(LocalEnvironment.defaultDotenv()
                                        .toAbsolutePath()
                                        .normalize()
                                        .toString())
                                + " --workflow "
                                + shellQuote(
                                        workflow.toAbsolutePath().normalize().toString()),
                        "symphony-trello logs --workflow "
                                + shellQuote(
                                        workflow.toAbsolutePath().normalize().toString()))
                .doesNotContain("./mvnw quarkus:dev");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void newBoardWritesResolverBackedCodexModelDefaults() {
        // given
        Path workflow = tempDir.resolve("resolver-backed-model.WORKFLOW.md");

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
                workflow.toString());

        // then
        result.assertSuccess();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-test-maintainer\"")
                .contains("reasoning_effort: \"high\"");
    }

    @Test
    void usesConfiguredDefaultWorkflowDirectoryWithoutDisablingBoardNameFallback() throws IOException {
        // given
        Path configDirectory = tempDir.resolve("config");
        Files.createDirectories(configDirectory);
        Path defaultWorkflow = configDirectory.resolve("WORKFLOW.md");
        Files.writeString(defaultWorkflow, "existing", StandardCharsets.UTF_8);
        String previousConfigDir = System.getProperty(CONFIG_DIR_PROPERTY);
        System.setProperty(CONFIG_DIR_PROPERTY, configDirectory.toString());
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode;
        try {
            exitCode = run(
                    stdout,
                    stderr,
                    "new-board",
                    "--endpoint",
                    endpoint(),
                    "--key",
                    "key",
                    "--token",
                    "token",
                    "--name",
                    "Second Board");

        } finally {
            if (previousConfigDir == null) {
                System.clearProperty(CONFIG_DIR_PROPERTY);
            } else {
                System.setProperty(CONFIG_DIR_PROPERTY, previousConfigDir);
            }
        }

        // then
        assertThat(exitCode).isZero();
        assertThat(defaultWorkflow).content(StandardCharsets.UTF_8).isEqualTo("existing");
        assertThat(configDirectory.resolve("WORKFLOW.second-board.md"))
                .content(StandardCharsets.UTF_8)
                .contains("board_id: \"abc123\"");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void printsPowerShellSafeNextStepsWhenRequestedByWrapper() {
        // given
        Path workflow = tempDir.resolve("generated O'Brien.WORKFLOW.md");
        String previousShell = System.getProperty(SHELL_PROPERTY);
        System.setProperty(SHELL_PROPERTY, "powershell");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode;
        try {
            exitCode = run(
                    stdout,
                    stderr,
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
                    workflow.toString());
        } finally {
            if (previousShell == null) {
                System.clearProperty(SHELL_PROPERTY);
            } else {
                System.setProperty(SHELL_PROPERTY, previousShell);
            }
        }

        // then
        assertThat(exitCode).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains(
                        "symphony-trello start --env "
                                + powerShellQuote(LocalEnvironment.defaultDotenv()
                                        .toAbsolutePath()
                                        .normalize()
                                        .toString())
                                + " --workflow "
                                + powerShellQuote(
                                        workflow.toAbsolutePath().normalize().toString()),
                        "symphony-trello logs --workflow "
                                + powerShellQuote(
                                        workflow.toAbsolutePath().normalize().toString()));
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void printsCmdSafeNextStepsWhenRequestedByCmdShim() {
        // given
        Path workflow = tempDir.resolve("generated command prompt.WORKFLOW.md");
        String previousShell = System.getProperty(SHELL_PROPERTY);
        System.setProperty(SHELL_PROPERTY, "cmd");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode;
        try {
            exitCode = run(
                    stdout,
                    stderr,
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
                    workflow.toString());
        } finally {
            if (previousShell == null) {
                System.clearProperty(SHELL_PROPERTY);
            } else {
                System.setProperty(SHELL_PROPERTY, previousShell);
            }
        }

        // then
        assertThat(exitCode).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains(
                        "symphony-trello start --env "
                                + cmdQuote(LocalEnvironment.defaultDotenv()
                                        .toAbsolutePath()
                                        .normalize()
                                        .toString())
                                + " --workflow "
                                + cmdQuote(workflow.toAbsolutePath().normalize().toString()),
                        "symphony-trello logs --workflow "
                                + cmdQuote(workflow.toAbsolutePath().normalize().toString()));
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void createsNonGithubBoardWithoutMergingList() {
        // given
        Path workflow = tempDir.resolve("non-github.WORKFLOW.md");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode = run(
                stdout,
                stderr,
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
                "--no-github");

        // then
        assertThat(exitCode).isZero();
        assertThat(createdLists)
                .containsExactly("Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Done");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("## Local And Non-GitHub Repository Work")
                .contains("This workflow does not have GitHub PR integration configured")
                .doesNotContain("## Landing From \"Merging\"")
                .doesNotContain("## Pull Request Publication")
                .doesNotContain("Merging");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void explicitDefaultWorkflowPathDoesNotUseBoardNameFallback() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(workflow, "existing workflow", StandardCharsets.UTF_8);
        Path fallback = tempDir.resolve("WORKFLOW.symmetry-queue.md");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode = run(
                stdout,
                stderr,
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
                workflow.toString());

        // then
        assertThat(exitCode).isEqualTo(2);
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo("existing workflow");
        assertThat(fallback).doesNotExist();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .contains("setup_failed code=setup_workflow_exists")
                .contains("--force");
    }

    @Test
    void importsExistingBoardWithExplicitListsAndPrintsSelection() {
        // given
        Path workflow = tempDir.resolve("imported workflow.WORKFLOW.md");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode = run(
                stdout,
                stderr,
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
                "Queue for Codex, Doing,",
                "--in-progress",
                "Doing",
                "--terminal",
                "Released,",
                "--workflow",
                workflow.toString());

        // then
        assertThat(exitCode).isZero();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("- \"Queue for Codex\"")
                .contains("- \"Doing\"")
                .contains("- \"Released\"")
                .doesNotContain("- \" Doing\"", "- \"\"");
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("Imported Trello board: Existing Board")
                .contains("Active lists: Queue for Codex, Doing")
                .contains("In-progress list: Doing")
                .contains("Terminal lists: Released")
                .contains(
                        "Blocked list: Blocked",
                        "symphony-trello start --env "
                                + shellQuote(LocalEnvironment.defaultDotenv()
                                        .toAbsolutePath()
                                        .normalize()
                                        .toString())
                                + " --workflow "
                                + shellQuote(
                                        workflow.toAbsolutePath().normalize().toString()),
                        "symphony-trello logs --workflow "
                                + shellQuote(
                                        workflow.toAbsolutePath().normalize().toString()))
                .doesNotContain("./mvnw quarkus:dev");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCliArgumentScenarios")
    void rejectsInvalidCliArguments(String name, String[] command, int exitCode, String[] expectedError) {
        // given

        // when
        CliRunResult result = runCli(command);

        // then
        result.assertFailure(exitCode).stderrContains(expectedError);
        assertThat(result.stdout()).isEmpty();
    }

    private static Stream<Arguments> invalidCliArgumentScenarios() {
        return Stream.of(
                Arguments.of(
                        "unknown option", new String[] {"new-board", "--name", "Queue", "--unknown"}, 2, new String[] {
                            "setup_failed code=setup_invalid_arguments", "Unknown option: '--unknown'"
                        }),
                Arguments.of("unknown command", new String[] {"create-board"}, 2, new String[] {
                    "setup_failed code=setup_invalid_arguments", "Unmatched argument at index 0: 'create-board'"
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
                            "setup_failed code=setup_invalid_port", "--server-port must be between 1 and 65535"
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

    private CliRunResult runCli(String... args) {
        return runCli(() -> TrelloBoardSetup.CodexModelDefaults.fallback(), args);
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

    private int run(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr, String... args) {
        return TrelloBoardSetupMain.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                TrelloBoardSetup.CodexModelDefaults.fallback());
    }

    private static Stream<Arguments> directCommandHelp() {
        return Stream.of(
                Arguments.of("new-board", "Usage: symphony-trello new-board"),
                Arguments.of("import-board", "Usage: symphony-trello import-board"),
                Arguments.of("list-workspaces", "Usage: symphony-trello list-workspaces"));
    }

    private static Stream<Arguments> commandsThatDoNotWriteWorkflows() {
        return Stream.of(
                Arguments.of("root help", new String[] {"--help"}),
                Arguments.of("root version", new String[] {"--version"}),
                Arguments.of("status help", new String[] {"status", "--help"}),
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
                Arguments.of((Object) new String[] {"list-workspaces", "--version"}));
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
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", javaExecutable())
                .toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(TrelloBoardSetupMain.class.getName());
        command.addAll(Arrays.asList(arguments));
        Process process = new ProcessBuilder(command).start();
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

    private static String javaExecutable() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
    }

    private record MainProcessCase(String[] arguments, int expectedExitCode, String expectedOutput) {
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
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/1";
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new LinkedHashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String part : rawQuery.split("&")) {
            String[] pair = part.split("=", 2);
            values.put(decode(pair[0]), pair.length == 1 ? "" : decode(pair[1]));
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
