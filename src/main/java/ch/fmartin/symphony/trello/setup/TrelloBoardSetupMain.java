package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.GitHubIntegration;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.ImportBoardRequest;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.NewBoardRequest;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.WorkspaceListRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

@Command(
        name = "symphony-trello",
        description = "Set up and run Symphony for Trello.",
        mixinStandardHelpOptions = true,
        versionProvider = TrelloBoardSetupMain.ProjectVersion.class,
        subcommands = {
            TrelloBoardSetupMain.NewBoardCommand.class,
            TrelloBoardSetupMain.ImportBoardCommand.class,
            TrelloBoardSetupMain.ListWorkspacesCommand.class,
            TrelloBoardSetupMain.StartCommand.class,
            TrelloBoardSetupMain.StopCommand.class,
            TrelloBoardSetupMain.StatusCommand.class,
            TrelloBoardSetupMain.LogsCommand.class
        })
public final class TrelloBoardSetupMain implements Callable<Integer> {
    private static final String CONFIG_DIR_PROPERTY = "symphony.trello.config.dir";
    private static final String SHELL_PROPERTY = "symphony.trello.shell";

    private final TrelloBoardSetupService boardSetup;
    private final LocalSetup localSetup;
    private final LocalWorkerManager workerManager;
    private final BufferedReader input;
    private final PrintStream out;
    private final PrintStream err;

    @Spec
    CommandSpec spec;

    TrelloBoardSetupMain(
            TrelloBoardSetupService boardSetup,
            LocalSetup localSetup,
            LocalWorkerManager workerManager,
            BufferedReader input,
            PrintStream out,
            PrintStream err) {
        this.boardSetup = boardSetup;
        this.localSetup = localSetup;
        this.workerManager = workerManager;
        this.input = input;
        this.out = out;
        this.err = err;
    }

    public static void main(String... args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, (TrelloBoardSetup.CodexModelDefaults) null);
    }

    static int run(
            String[] args, PrintStream out, PrintStream err, TrelloBoardSetup.CodexModelDefaults codexModelDefaults) {
        ObjectMapper json = new ObjectMapper();
        return run(args, out, err, () -> codexModelDefaults(json, codexModelDefaults));
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            Supplier<TrelloBoardSetup.CodexModelDefaults> codexModelDefaults) {
        ObjectMapper json = new ObjectMapper();
        TrelloBoardSetup boardSetup = new TrelloBoardSetup(json, codexModelDefaults);
        return run(
                args,
                new TrelloBoardSetupService(boardSetup),
                new LocalSetup(boardSetup, new ProcessCommandRunner()),
                new LocalWorkerManager(System.getenv()),
                out,
                err);
    }

    private static TrelloBoardSetup.CodexModelDefaults codexModelDefaults(
            ObjectMapper json, TrelloBoardSetup.CodexModelDefaults codexModelDefaults) {
        return codexModelDefaults != null ? codexModelDefaults : new CodexModelDefaultsResolver(json).resolve();
    }

    static int run(
            String[] args,
            TrelloBoardSetupService boardSetup,
            LocalSetup localSetup,
            LocalWorkerManager workerManager,
            PrintStream out,
            PrintStream err) {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        CommandLine commandLine = new CommandLine(
                        new TrelloBoardSetupMain(boardSetup, localSetup, workerManager, input, out, err))
                .addSubcommand(
                        "setup-local", new SetupLocalCommandFactory.SetupLocalCommand(localSetup, input, out, err))
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .setExecutionExceptionHandler((exception, ignored, parseResult) -> {
                    err.println(
                            "setup_failed code=%s message=%s".formatted(errorCode(exception), exception.getMessage()));
                    if (!(exception instanceof ParameterException)) {
                        SetupDiagnosticReporter.reportFailure(exception, args, input, out, err);
                    }
                    return 2;
                })
                .setParameterExceptionHandler((ParameterException exception, String[] ignored) -> {
                    CommandLine failed = exception.getCommandLine();
                    err.println("setup_failed code=setup_invalid_arguments message=" + exception.getMessage());
                    err.println("Try '" + failed.getCommandName() + " --help' for usage.");
                    return 2;
                });
        return commandLine.execute(args);
    }

    @Override
    public Integer call() {
        spec.commandLine().usage(out);
        return 0;
    }

    private static String errorCode(Exception e) {
        return e instanceof TrelloBoardSetupException setupException
                ? setupException.code()
                : "setup_invalid_arguments";
    }

    static final class ProjectVersion implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = TrelloBoardSetupMain.class.getPackage().getImplementationVersion();
            return new String[] {"symphony-trello " + (version == null ? "0.1.0-SNAPSHOT" : version)};
        }
    }

    static final class TrelloBoardSetupService {
        private final TrelloBoardSetup setup;

        TrelloBoardSetupService(TrelloBoardSetup setup) {
            this.setup = setup;
        }

        void createRecommendedBoard(NewBoardRequest request, PrintStream out) {
            printNewBoardResult(out, setup.createRecommendedBoard(request));
        }

        void importExistingBoard(ImportBoardRequest request, PrintStream out) {
            printImportBoardResult(out, setup.importExistingBoard(request));
        }

        void listWorkspaces(WorkspaceListRequest request, PrintStream out) {
            printWorkspaces(out, setup.listWorkspaces(request));
        }
    }

    @Command(
            name = "new-board",
            description = "Create the recommended Trello board and workflow.",
            versionProvider = TrelloBoardSetupMain.ProjectVersion.class,
            mixinStandardHelpOptions = true)
    static final class NewBoardCommand implements Callable<Integer> {
        @ParentCommand
        TrelloBoardSetupMain parent;

        @Mixin
        BoardSetupOptions options = new BoardSetupOptions();

        @Option(names = "--name", required = true, description = "Trello board name to create.")
        String boardName;

        @Option(names = "--workspace-id", description = "Trello Workspace id for the new board.")
        String workspaceId;

        @Override
        public Integer call() {
            parent.boardSetup.createRecommendedBoard(options.newBoardRequest(boardName, workspaceId), parent.out);
            return 0;
        }
    }

    @Command(
            name = "import-board",
            description = "Create a workflow for an existing Trello board.",
            versionProvider = TrelloBoardSetupMain.ProjectVersion.class,
            mixinStandardHelpOptions = true)
    static final class ImportBoardCommand implements Callable<Integer> {
        @ParentCommand
        TrelloBoardSetupMain parent;

        @Mixin
        BoardSetupOptions options = new BoardSetupOptions();

        @Option(names = "--board", required = true, description = "Trello board URL, short link, or id.")
        String boardId;

        @Option(names = "--active", split = ",", description = "Queued-work Trello list name.")
        List<String> activeStates = new ArrayList<>();

        @Option(names = "--terminal", split = ",", description = "Terminal Trello list name.")
        List<String> terminalStates = new ArrayList<>();

        @Option(names = "--in-progress", description = "In-progress Trello list name.")
        String inProgressState;

        @Option(names = "--no-in-progress", description = "Do not configure an in-progress list.")
        boolean noInProgress;

        @Option(names = "--blocked", description = "Blocked Trello list name.")
        String blockedState;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            if (noInProgress && inProgressState != null) {
                throw new ParameterException(spec.commandLine(), "--in-progress cannot be used with --no-in-progress.");
            }
            parent.boardSetup.importExistingBoard(
                    options.importBoardRequest(
                            TrelloBoardIds.parse(boardId),
                            activeStates,
                            terminalStates,
                            inProgressState,
                            !noInProgress && inProgressState == null,
                            blockedState),
                    parent.out);
            return 0;
        }
    }

    @Command(
            name = "list-workspaces",
            description = "List Trello Workspaces visible to the token.",
            versionProvider = TrelloBoardSetupMain.ProjectVersion.class,
            mixinStandardHelpOptions = true)
    static final class ListWorkspacesCommand implements Callable<Integer> {
        @ParentCommand
        TrelloBoardSetupMain parent;

        @Mixin
        TrelloAuthOptions auth = new TrelloAuthOptions();

        @Option(names = "--endpoint", description = "Trello API endpoint.")
        URI endpoint = TrelloBoardSetup.DEFAULT_ENDPOINT;

        @Override
        public Integer call() {
            parent.boardSetup.listWorkspaces(new WorkspaceListRequest(endpoint, auth.credentials()), parent.out);
            return 0;
        }
    }

    @Command(
            name = "start",
            description = "Start a managed local Symphony worker.",
            versionProvider = TrelloBoardSetupMain.ProjectVersion.class,
            mixinStandardHelpOptions = true)
    static final class StartCommand implements Callable<Integer> {
        @ParentCommand
        TrelloBoardSetupMain parent;

        @Mixin
        LifecycleOptions options = new LifecycleOptions();

        @Option(names = "--env", description = "Dotenv file with Trello credentials for this worker.")
        Optional<Path> envPath = Optional.empty();

        @Option(names = "--all", hidden = true)
        boolean all;

        @Override
        public Integer call() throws Exception {
            return parent.workerManager.start(
                    new StartWorkerRequest(
                            options.board,
                            options.workflow,
                            envPath,
                            options.appHome,
                            options.configDir,
                            options.workspaceRoot,
                            options.stateHome,
                            all),
                    parent.out);
        }
    }

    @Command(
            name = "stop",
            description = "Stop managed local Symphony workers.",
            versionProvider = TrelloBoardSetupMain.ProjectVersion.class,
            mixinStandardHelpOptions = true)
    static final class StopCommand implements Callable<Integer> {
        @ParentCommand
        TrelloBoardSetupMain parent;

        @Mixin
        LifecycleOptions options = new LifecycleOptions();

        @Override
        public Integer call() throws Exception {
            return parent.workerManager.stop(
                    new StopWorkerRequest(
                            options.board,
                            options.workflow,
                            options.appHome,
                            options.configDir,
                            options.workspaceRoot,
                            options.stateHome),
                    parent.out);
        }
    }

    @Command(
            name = "status",
            description = "Show managed local Symphony worker status.",
            versionProvider = TrelloBoardSetupMain.ProjectVersion.class,
            mixinStandardHelpOptions = true)
    static final class StatusCommand implements Callable<Integer> {
        @ParentCommand
        TrelloBoardSetupMain parent;

        @Mixin
        LifecycleOptions options = new LifecycleOptions();

        @Override
        public Integer call() throws Exception {
            return parent.workerManager.status(
                    new WorkerStatusRequest(
                            options.board,
                            options.workflow,
                            options.appHome,
                            options.configDir,
                            options.workspaceRoot,
                            options.stateHome),
                    parent.out);
        }
    }

    @Command(
            name = "logs",
            description = "Show managed local Symphony worker logs.",
            versionProvider = TrelloBoardSetupMain.ProjectVersion.class,
            mixinStandardHelpOptions = true)
    static final class LogsCommand implements Callable<Integer> {
        @ParentCommand
        TrelloBoardSetupMain parent;

        @Mixin
        LifecycleOptions options = new LifecycleOptions();

        @Option(names = "--follow", description = "Keep streaming appended log lines.")
        boolean follow;

        @Override
        public Integer call() throws Exception {
            return parent.workerManager.logs(
                    new WorkerLogsRequest(
                            options.board,
                            options.workflow,
                            follow,
                            options.appHome,
                            options.configDir,
                            options.workspaceRoot,
                            options.stateHome),
                    parent.out);
        }
    }

    static final class LifecycleOptions {
        @Option(names = "--board", description = "Connected Trello board name, id, or short link.")
        Optional<String> board = Optional.empty();

        @Option(names = "--workflow", description = "Workflow file to manage.")
        Optional<Path> workflow = Optional.empty();

        @Option(names = "--config-dir", description = "Directory for local .env, workflows, and board manifest.")
        Optional<Path> configDir = Optional.empty();

        @Option(names = "--workspace-root", description = "Directory for per-card workspaces.")
        Optional<Path> workspaceRoot = Optional.empty();

        @Option(names = "--state-home", description = "Directory for managed worker PID and log files.")
        Optional<Path> stateHome = Optional.empty();

        @Option(names = "--app-home", hidden = true)
        Optional<Path> appHome = Optional.empty();
    }

    static final class BoardSetupOptions {
        @Mixin
        TrelloAuthOptions auth = new TrelloAuthOptions();

        @Mixin
        GitHubMode github = new GitHubMode();

        @Option(names = "--endpoint", description = "Trello API endpoint.")
        URI endpoint = TrelloBoardSetup.DEFAULT_ENDPOINT;

        Path workflowPath = defaultWorkflowPath();

        boolean workflowPathExplicit;

        @Option(names = "--workflow", description = "Workflow file to write.")
        void workflowPath(Path workflowPath) {
            this.workflowPath = workflowPath;
            this.workflowPathExplicit = true;
        }

        @Option(names = "--workspace-root", description = "Directory for per-card workspaces.")
        Path workspaceRoot = TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT;

        @Option(names = "--server-port", description = "Local HTTP status port.")
        Integer serverPort;

        @Option(names = "--max-agents", description = "Maximum cards from this board that may run at once.")
        int maxConcurrentAgents = TrelloBoardSetup.DEFAULT_MAX_CONCURRENT_AGENTS;

        @Option(names = "--force", description = "Overwrite the workflow file when needed.")
        boolean force;

        NewBoardRequest newBoardRequest(String boardName, String workspaceId) {
            validate();
            return new NewBoardRequest(
                    endpoint,
                    auth.credentials(),
                    boardName,
                    workspaceId,
                    workflowPath,
                    workspaceRoot,
                    serverPort,
                    maxConcurrentAgents,
                    force,
                    !workflowPathExplicit,
                    gitHubIntegration());
        }

        ImportBoardRequest importBoardRequest(
                String boardId,
                List<String> activeStates,
                List<String> terminalStates,
                String inProgressState,
                boolean detectInProgressState,
                String blockedState) {
            validate();
            return new ImportBoardRequest(
                    endpoint,
                    auth.credentials(),
                    boardId,
                    CliValueNormalizer.nonBlankTrimmed(activeStates),
                    CliValueNormalizer.nonBlankTrimmed(terminalStates),
                    CliValueNormalizer.trimmedOrNull(inProgressState),
                    detectInProgressState,
                    CliValueNormalizer.trimmedOrNull(blockedState),
                    workflowPath,
                    workspaceRoot,
                    serverPort,
                    maxConcurrentAgents,
                    force,
                    gitHubIntegration());
        }

        private GitHubIntegration gitHubIntegration() {
            return github.selected().orElse(true) ? GitHubIntegration.ENABLED : GitHubIntegration.DISABLED;
        }

        private void validate() {
            if (serverPort != null) {
                LocalPort.validateCliServerPort(serverPort);
            }
            if (maxConcurrentAgents < 1) {
                throw new TrelloBoardSetupException("setup_invalid_max_agents", "--max-agents must be at least 1.");
            }
        }
    }

    static final class TrelloAuthOptions {
        @Option(names = "--key", description = "Trello API key. Defaults to TRELLO_API_KEY or .env.")
        String key;

        @Option(names = "--token", description = "Trello API token. Defaults to TRELLO_API_TOKEN or .env.")
        String token;

        TrelloCredentials credentials() {
            return new TrelloCredentials(
                    Optional.ofNullable(key).or(() -> env("TRELLO_API_KEY")).orElse(null),
                    Optional.ofNullable(token).or(() -> env("TRELLO_API_TOKEN")).orElse(null));
        }
    }

    private static Optional<String> env(String name) {
        return LocalEnvironment.get(name);
    }

    private static Path defaultWorkflowPath() {
        String configDir = System.getProperty(CONFIG_DIR_PROPERTY);
        if (configDir == null || configDir.isBlank()) {
            return TrelloBoardSetup.DEFAULT_WORKFLOW_PATH;
        }
        return Path.of(configDir).resolve(TrelloBoardSetup.DEFAULT_WORKFLOW_PATH);
    }

    private static void printNewBoardResult(PrintStream out, TrelloBoardSetup.NewBoardResult result) {
        out.println("Created Trello board: " + result.boardName());
        if (result.boardUrl() != null && !result.boardUrl().isBlank()) {
            out.println("Board URL: " + result.boardUrl());
        }
        out.println("Board ID for WORKFLOW.md: " + result.boardKey());
        out.println("Created lists: " + String.join(", ", result.lists()));
        out.println("Wrote workflow: " + result.workflowPath().toAbsolutePath().normalize());
        out.println("HTTP status port: " + result.serverPort());
        out.println();
        out.println("Next:");
        printInstalledCliNextSteps(out, result.workflowPath());
    }

    private static void printWorkspaces(PrintStream out, List<TrelloBoardSetup.WorkspaceInfo> workspaces) {
        if (workspaces.isEmpty()) {
            out.println("No Trello workspaces found for this token.");
            return;
        }
        out.println("Trello workspaces:");
        for (TrelloBoardSetup.WorkspaceInfo workspace : workspaces) {
            out.println("  %s  %s".formatted(workspace.id(), workspace.displayName()));
        }
    }

    private static void printImportBoardResult(PrintStream out, TrelloBoardSetup.ImportBoardResult result) {
        out.println("Imported Trello board: " + result.boardName());
        if (result.boardUrl() != null && !result.boardUrl().isBlank()) {
            out.println("Board URL: " + result.boardUrl());
        }
        out.println("Board ID for WORKFLOW.md: " + result.boardKey());
        out.println("Open lists: " + String.join(", ", result.openLists()));
        out.println("Active lists: " + String.join(", ", result.activeStates()));
        out.println("In-progress list: " + optionalListName(result.inProgressState()));
        out.println("Terminal lists: " + String.join(", ", result.terminalStates()));
        out.println("Blocked list: " + optionalListName(result.blockedState()));
        out.println("Wrote workflow: " + result.workflowPath().toAbsolutePath().normalize());
        out.println("HTTP status port: " + result.serverPort());
        out.println();
        out.println("Next:");
        printInstalledCliNextSteps(out, result.workflowPath());
    }

    private static void printInstalledCliNextSteps(PrintStream out, Path workflowPath) {
        Path dotenv = LocalEnvironment.defaultDotenv().toAbsolutePath().normalize();
        Path workflow = workflowPath.toAbsolutePath().normalize();
        out.println("  symphony-trello start --env " + shellQuote(dotenv.toString()) + " --workflow "
                + shellQuote(workflow.toString()));
        out.println("  symphony-trello logs --workflow " + shellQuote(workflow.toString()));
    }

    private static String shellQuote(String value) {
        String shell = System.getProperty(SHELL_PROPERTY);
        if ("powershell".equalsIgnoreCase(shell)) {
            return "'" + value.replace("'", "''") + "'";
        }
        if ("cmd".equalsIgnoreCase(shell)) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String optionalListName(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }
}
