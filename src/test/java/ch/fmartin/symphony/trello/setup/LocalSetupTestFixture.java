package ch.fmartin.symphony.trello.setup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

final class LocalSetupTestFixture implements AutoCloseable {
    private static final int FIRST_FIXTURE_PORT = 20_000;
    private static final int LAST_FIXTURE_PORT = 29_999;
    private static final AtomicInteger NEXT_FIXTURE_PORT = new AtomicInteger(FIRST_FIXTURE_PORT);

    private final Path tempDir;
    private final FakeTrelloServer trello;
    private final FakeCommands commands;
    private final LocalWorkerManager workerManager = mock();
    private final LocalSetup setup;

    LocalSetupTestFixture(Path tempDir) throws IOException {
        this.tempDir = tempDir;
        commands = new FakeCommands(availablePort());
        trello = new FakeTrelloServer().start();
        when(workerManager.canStopManagedWorker(any(), any())).thenReturn(true);
        doAnswer(invocation -> {
                    ConnectedBoard board = invocation.getArgument(1);
                    Path env = invocation.getArgument(2);
                    commands.startedEnvFiles.add(env.toString());
                    commands.startedWorkflows.add(board.workflowPath().toString());
                    commands.commandEvents.add("start:" + board.workflowPath());
                    if (!commands.skipHealthStart) {
                        commands.startHealthServer(board.workflowPath());
                    }
                    return null;
                })
                .when(workerManager)
                .start(any(), any(), any(), any());
        doAnswer(invocation -> {
                    ConnectedBoard board = invocation.getArgument(1);
                    commands.stoppedWorkflows.add(board.workflowPath().toString());
                    commands.commandEvents.add("stop:" + board.workflowPath());
                    commands.stopHealthServer(board.workflowPath().toString());
                    return null;
                })
                .when(workerManager)
                .stop(any(), any(), any());
        setup = new LocalSetup(
                new TrelloBoardSetup(new ObjectMapper()),
                commands,
                Map.of(
                        "SYMPHONY_TRELLO_CONFIG_DIR",
                        configDir().toString(),
                        "SYMPHONY_TRELLO_COMMAND",
                        "symphony-trello"),
                new WorkflowConfigEditor(),
                workerManager);
    }

    Path configDir() {
        return tempDir.resolve("config");
    }

    Path workspaceRoot() {
        return tempDir.resolve("workspaces");
    }

    Path stateDir() {
        return tempDir.resolve("state");
    }

    Path envPath() {
        return configDir().resolve(".env");
    }

    Path workflowPath() {
        return configDir().resolve("WORKFLOW.md");
    }

    Path manifestPath() {
        return configDir().resolve("connected-boards.json");
    }

    String endpoint() {
        return trello.endpoint();
    }

    LocalSetup setup() {
        return setup;
    }

    FakeCommands commands() {
        return commands;
    }

    LocalWorkerManager workerManager() {
        return workerManager;
    }

    FakeTrelloServer trello() {
        return trello;
    }

    SetupRunResult runSetup(String... args) {
        return runSetupWithInput("", args);
    }

    SetupRunResult runSetupWithInput(String input, String... args) {
        return runSetupWithEffectiveArgs(setup, input, argsWithFixtureServerPort(args));
    }

    SetupRunResult runSetupWithProductionDefaultPort(String... args) {
        return runSetupWithEffectiveArgs(setup, "", args);
    }

    SetupRunResult runSetupWithProductionDefaultPort(LocalSetup localSetup, String... args) {
        return runSetupWithEffectiveArgs(localSetup, "", args);
    }

    private SetupRunResult runSetupWithEffectiveArgs(LocalSetup localSetup, String input, String[] args) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exitCode = localSetup.run(
                args,
                new BufferedReader(new StringReader(input)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new SetupRunResult(
                exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    String[] argsWithFixtureServerPort(String... args) {
        if (hasServerPort(args)
                || invokesLifecycleSubcommand(args)
                || hasDryRun(args)
                || !hasNewBoardSetupShape(args)) {
            return args;
        }
        String[] effectiveArgs = new String[args.length + 2];
        effectiveArgs[0] = "--server-port";
        effectiveArgs[1] = String.valueOf(availablePort());
        System.arraycopy(args, 0, effectiveArgs, 2, args.length);
        return effectiveArgs;
    }

    SetupRunResult runSetup(LocalSetupRequest request) {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exitCode = setup.run(
                request,
                new BufferedReader(new StringReader("")),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new SetupRunResult(
                exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    void givenConnectedBoard(String boardName, Path workflow, Path env, int serverPort, boolean githubEnabled)
            throws IOException {
        givenManifest(
                """
                {"boards":[{"boardId":"board-1","boardKey":"abc123","boardName":"%s","boardUrl":"https://trello.example/board","workflowPath":"%s","envPath":"%s","workspaceRoot":"%s","serverPort":%d,"githubEnabled":%s,"additionalWritableRoots":[],"dangerFullAccess":false}]}
                """
                        .formatted(
                                boardName,
                                json(workflow),
                                json(env),
                                json(workspaceRoot()),
                                serverPort,
                                githubEnabled));
    }

    void givenWorkflow(Path workflow, String boardId, int port) throws IOException {
        Files.createDirectories(workflow.getParent());
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "%s"
                server:
                  port: %d
                codex:
                  model: gpt-5.2
                ---
                # Test workflow
                """
                        .formatted(boardId, port),
                StandardCharsets.UTF_8);
    }

    void givenWorkflowWithGithub(Path workflow, String boardId, int port) throws IOException {
        givenWorkflow(workflow, boardId, port);
        Files.writeString(
                workflow, Files.readString(workflow, StandardCharsets.UTF_8) + "\n## Pull Request Publication\n");
    }

    void givenWorkflowWithoutGithub(Path workflow, String boardId, int port) throws IOException {
        givenWorkflow(workflow, boardId, port);
    }

    void givenManifest(String content) throws IOException {
        Files.createDirectories(manifestPath().getParent());
        Files.writeString(manifestPath(), content, StandardCharsets.UTF_8);
    }

    void givenCodexAuthenticated() {
        commands.codexAuthenticated = true;
    }

    void givenCodexUnauthenticated() {
        commands.codexAuthenticated = false;
    }

    void givenGithubAuthenticated() {
        commands.githubAuthenticated = true;
    }

    void givenGithubMissing() {
        commands.githubCliAvailable = false;
    }

    void givenWorkerRunning(Path workflow) {
        commands.startedWorkflows.add(workflow.toString());
        commands.startHealthServer(workflow);
    }

    void givenWorkerStopped(Path workflow) {
        commands.stopHealthServer(workflow.toString());
    }

    void givenWorkerWrongWorkflow(Path expectedWorkflow, Path actualWorkflow) {
        commands.stopHealthServer(expectedWorkflow.toString());
        commands.startHealthServer(actualWorkflow, "board-1");
    }

    void givenWorkerWrongBoard(Path workflow, String boardId) {
        commands.stopHealthServer(workflow.toString());
        commands.startHealthServer(workflow, boardId);
    }

    void givenPortOccupied(Path workflow, int port) {
        commands.healthPortOverride = port;
        commands.startHealthServer(workflow);
    }

    @Override
    public void close() {
        trello.stop();
        commands.close();
    }

    private static String json(Path path) {
        return path.toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean hasServerPort(String... args) {
        for (String arg : args) {
            if ("--server-port".equals(arg) || arg.startsWith("--server-port=")) {
                return true;
            }
        }
        return false;
    }

    private static boolean invokesLifecycleSubcommand(String... args) {
        for (String arg : args) {
            if ("check".equals(arg) || "repair-port".equals(arg) || "configure-github".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDryRun(String... args) {
        for (String arg : args) {
            if ("--dry-run".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasNewBoardSetupShape(String... args) {
        return hasWorkflowOrEnvTarget(args)
                || (hasDirectCredentials(args) && (hasBoardSelector(args) || !Files.isRegularFile(manifestPath())));
    }

    private static boolean hasWorkflowOrEnvTarget(String... args) {
        for (String arg : args) {
            if ("--workflow".equals(arg)
                    || arg.startsWith("--workflow=")
                    || "--env".equals(arg)
                    || arg.startsWith("--env=")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDirectCredentials(String... args) {
        for (String arg : args) {
            if ("--key".equals(arg)
                    || arg.startsWith("--key=")
                    || "--token".equals(arg)
                    || arg.startsWith("--token=")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBoardSelector(String... args) {
        for (String arg : args) {
            if ("--board-name".equals(arg)
                    || arg.startsWith("--board-name=")
                    || "--board".equals(arg)
                    || arg.startsWith("--board=")) {
                return true;
            }
        }
        return false;
    }

    private static int availablePort() {
        for (int attempt = FIRST_FIXTURE_PORT; attempt <= LAST_FIXTURE_PORT; attempt++) {
            int port = nextFixturePort();
            if (!LocalHealthChecker.portAcceptsConnections(port)) {
                return port;
            }
        }
        throw new AssertionError("Could not allocate test port");
    }

    private static int nextFixturePort() {
        return NEXT_FIXTURE_PORT.getAndUpdate(
                current -> current >= LAST_FIXTURE_PORT ? FIRST_FIXTURE_PORT : current + 1);
    }

    static final class FakeCommands implements CommandRunner {
        private final int defaultServerPort;
        boolean githubAuthenticated;
        boolean githubCliAvailable = true;
        boolean wingetAvailable;
        boolean javacAvailable = true;
        boolean codexAuthenticated = true;
        boolean managedCommandAvailable = true;
        boolean skipHealthStart;
        Integer healthPortOverride;
        final List<String> startedWorkflows = new ArrayList<>();
        final List<String> startedEnvFiles = new ArrayList<>();
        final List<String> stoppedWorkflows = new ArrayList<>();
        final List<String> commandEvents = new ArrayList<>();
        final List<String> codexLoginCommands = new ArrayList<>();
        final List<String> githubLoginCommands = new ArrayList<>();
        final Map<String, String> statusByWorkflow = new LinkedHashMap<>();
        private final Map<String, HttpServer> healthServersByWorkflow = new LinkedHashMap<>();

        FakeCommands(int defaultServerPort) {
            this.defaultServerPort = defaultServerPort;
        }

        @Override
        public CommandResult run(String... command) {
            String joined = String.join(" ", command);
            if ("java -version".equals(joined)) {
                return new CommandResult(0, "openjdk version \"25.0.1\" 2026-01-01");
            }
            if ("javac -version".equals(joined)) {
                return new CommandResult(javacAvailable ? 0 : 127, javacAvailable ? "javac 25.0.1" : "missing");
            }
            if ("git --version".equals(joined)) {
                return new CommandResult(0, "git version 2.51.0");
            }
            if ("codex --version".equals(joined)) {
                return new CommandResult(0, "codex 0.1.0");
            }
            if ("codex login status".equals(joined)) {
                return new CommandResult(codexAuthenticated ? 0 : 1, codexAuthenticated ? "authenticated" : "missing");
            }
            if ("codex login --device-auth".equals(joined) || "codex login".equals(joined)) {
                codexLoginCommands.add(joined);
                codexAuthenticated = true;
                return new CommandResult(0, "authenticated");
            }
            if ("gh --version".equals(joined)) {
                return new CommandResult(
                        githubCliAvailable ? 0 : 127, githubCliAvailable ? "gh version 2.83.0" : "missing");
            }
            if ("apt-get --version".equals(joined)) {
                return new CommandResult(0, "apt 3.0.0");
            }
            if ("id -u".equals(joined)) {
                return new CommandResult(0, "0");
            }
            if ("winget --version".equals(joined)) {
                return new CommandResult(wingetAvailable ? 0 : 127, wingetAvailable ? "v1.12.0" : "missing");
            }
            if ("gh auth status".equals(joined)) {
                return new CommandResult(githubAuthenticated ? 0 : 1, githubAuthenticated ? "ok" : "not logged in");
            }
            if ("gh auth login".equals(joined)) {
                githubLoginCommands.add(joined);
                githubAuthenticated = true;
                return new CommandResult(0, "authenticated");
            }
            if ("gh api user --jq .login // \"\"".equals(joined)) {
                return new CommandResult(0, "alex-example");
            }
            if ("bash -lc apt-get update && apt-get install -y gh".equals(joined)
                    || "bash -lc sudo apt-get update && sudo apt-get install -y gh".equals(joined)
                    || "bash -lc doas apt-get update && doas apt-get install -y gh".equals(joined)) {
                commandEvents.add("install-gh");
                githubCliAvailable = true;
                return new CommandResult(0, "installed gh");
            }
            if ("cmd /c winget install --id GitHub.cli --source winget".equals(joined)) {
                commandEvents.add("install-gh-winget");
                githubCliAvailable = true;
                return new CommandResult(0, "installed gh");
            }
            if (command.length == 2 && "symphony-trello".equals(command[0]) && "status".equals(command[1])) {
                return new CommandResult(managedCommandAvailable ? 0 : 127, managedCommandAvailable ? "ok" : "missing");
            }
            if (command.length == 3 && "symphony-trello".equals(command[0]) && "status".equals(command[1])) {
                String overriddenStatus = statusByWorkflow.get(command[2]);
                if (overriddenStatus != null) {
                    return new CommandResult(managedCommandAvailable ? 0 : 127, overriddenStatus);
                }
                boolean running = startedWorkflows.contains(command[2]);
                return new CommandResult(
                        managedCommandAvailable ? 0 : 127,
                        running ? "running " + command[2] : "No managed Symphony process found");
            }
            if (command.length >= 4 && "symphony-trello".equals(command[0]) && "start".equals(command[1])) {
                startedEnvFiles.add(command[3]);
                startedWorkflows.add(command[command.length - 1]);
                commandEvents.add("start:" + command[command.length - 1]);
                if (!skipHealthStart) {
                    startHealthServer(Path.of(command[command.length - 1]));
                }
                return new CommandResult(0, "Started Symphony for Trello");
            }
            if (command.length == 3 && "symphony-trello".equals(command[0]) && "stop".equals(command[1])) {
                stoppedWorkflows.add(command[2]);
                commandEvents.add("stop:" + command[2]);
                stopHealthServer(command[2]);
                return new CommandResult(0, "Stopped Symphony for Trello");
            }
            return new CommandResult(127, "missing");
        }

        void startHealthServer(Path workflowPath) {
            startHealthServer(workflowPath, "board-1");
        }

        void startHealthServer(Path workflowPath, String boardId) {
            int port = healthPortOverride == null ? workflowPort(workflowPath) : healthPortOverride;
            try {
                stopHealthServer(workflowPath.toString());
                HttpServer healthServer = createHealthServer(port);
                String workflow = workflowPath.toAbsolutePath().normalize().toString();
                healthServer.createContext(
                        "/api/v1/local-status",
                        exchange -> FakeTrelloServer.respond(
                                exchange,
                                """
                        {"workflowPath":"%s","boardId":"%s"}
                        """
                                        .formatted(workflow.replace("\\", "\\\\"), boardId)));
                healthServer.createContext(
                        "/api/v1/state",
                        exchange -> FakeTrelloServer.respond(
                                exchange,
                                """
                        {"counts":{"running":0,"retrying":0}}
                        """));
                healthServer.start();
                healthServersByWorkflow.put(workflowPath.toString(), healthServer);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        static HttpServer createHealthServer(int port) throws IOException {
            IOException failure = null;
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
                } catch (IOException e) {
                    failure = e;
                    sleepAfterPortStop();
                }
            }
            throw failure;
        }

        private static void sleepAfterPortStop() {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void stopHealthServer(String workflowPath) {
            HttpServer healthServer = healthServersByWorkflow.remove(workflowPath);
            if (healthServer != null) {
                healthServer.stop(0);
            }
        }

        void close() {
            healthServersByWorkflow.values().forEach(server -> server.stop(0));
            healthServersByWorkflow.clear();
        }

        int workflowPort(Path workflowPath) {
            try {
                var matcher = Pattern.compile("(?m)^\\s*port:\\s*(\\d+)\\s*$")
                        .matcher(Files.readString(workflowPath, StandardCharsets.UTF_8));
                return matcher.find() ? Integer.parseInt(matcher.group(1)) : defaultServerPort;
            } catch (IOException e) {
                return defaultServerPort;
            }
        }
    }
}
