package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.setup.SetupDiagnosticReporter.DiagnosticsRequest;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.GitHubIntegration;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.ImportBoardRequest;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.NewBoardRequest;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.WorkspaceListRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
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
            TrelloBoardSetupMain.LogsCommand.class,
            TrelloBoardSetupMain.DiagnosticsCommand.class
        })
public final class TrelloBoardSetupMain implements Callable<Integer> {
    private static final String CONFIG_DIR_PROPERTY = "symphony.trello.config.dir";
    private static final String SHELL_PROPERTY = "symphony.trello.shell";
    private static final String CLI_COMMAND_PROPERTY = "symphony.trello.command";
    private static final ThreadLocal<PropertyLookup> PROPERTY_LOOKUP =
            ThreadLocal.withInitial(() -> System::getProperty);

    private final TrelloBoardSetupService boardSetup;
    private final LocalWorkerManager workerManager;
    private final BufferedReader input;
    private final PrintStream out;
    private final PrintStream err;

    @Spec
    CommandSpec spec;

    TrelloBoardSetupMain(
            TrelloBoardSetupService boardSetup,
            LocalWorkerManager workerManager,
            BufferedReader input,
            PrintStream out,
            PrintStream err) {
        this.boardSetup = boardSetup;
        this.workerManager = workerManager;
        this.input = input;
        this.out = out;
        this.err = err;
    }

    public static void main(String... args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        ObjectMapper json = new ObjectMapper();
        return runWithSelectionDefaults(
                args, out, err, () -> new CodexModelDefaultsResolver(json).resolveSelectionDefaults());
    }

    static int run(
            String[] args, PrintStream out, PrintStream err, TrelloBoardSetup.CodexModelDefaults codexModelDefaults) {
        return runWithSelectionDefaults(args, out, err, () -> codexModelSelectionDefaults(codexModelDefaults));
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            TrelloBoardSetup.CodexModelDefaults codexModelDefaults,
            Map<String, String> properties) {
        return runWithPropertyLookup(properties::get, () -> run(args, out, err, codexModelDefaults));
    }

    static int run(
            String[] args,
            PrintStream out,
            PrintStream err,
            Supplier<TrelloBoardSetup.CodexModelDefaults> codexModelDefaults) {
        return runWithSelectionDefaults(args, out, err, () -> CodexModelSelectionDefaults.of(codexModelDefaults.get()));
    }

    private static int runWithSelectionDefaults(
            String[] args,
            PrintStream out,
            PrintStream err,
            Supplier<CodexModelSelectionDefaults> codexModelSelectionDefaults) {
        ObjectMapper json = new ObjectMapper();
        TrelloBoardSetup boardSetup = new TrelloBoardSetup(json, codexModelSelectionDefaults);
        LocalWorkerManager workerManager = new LocalWorkerManager(System.getenv());
        return run(
                args,
                new TrelloBoardSetupService(boardSetup, workerManager, System.getenv()),
                new LocalSetup(boardSetup, new ProcessCommandRunner()),
                workerManager,
                out,
                err);
    }

    private static CodexModelSelectionDefaults codexModelSelectionDefaults(
            TrelloBoardSetup.CodexModelDefaults codexModelDefaults) {
        return CodexModelSelectionDefaults.of(codexModelDefaults);
    }

    private static int runWithPropertyLookup(PropertyLookup properties, IntSupplier run) {
        PROPERTY_LOOKUP.set(properties);
        try {
            return run.getAsInt();
        } finally {
            PROPERTY_LOOKUP.remove();
        }
    }

    private static BufferedReader standardInputReader() {
        // System.in is process-owned. Picocli/setup commands borrow it and must not close it.
        return new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)); // NOPMD - borrows System.in.
    }

    static int run(
            String[] args,
            TrelloBoardSetupService boardSetup,
            LocalSetup localSetup,
            LocalWorkerManager workerManager,
            PrintStream out,
            PrintStream err) {
        String[] effectiveArgs = InstalledCliDefaults.apply(args, System.getenv());
        BufferedReader input = standardInputReader(); // NOPMD - System.in is process-owned.
        CommandLine commandLine = new CommandLine(new TrelloBoardSetupMain(boardSetup, workerManager, input, out, err))
                .addSubcommand(
                        "setup-local", new SetupLocalCommandFactory.SetupLocalCommand(localSetup, input, out, err))
                .setOut(new PrintWriter(out, true, StandardCharsets.UTF_8))
                .setErr(new PrintWriter(err, true, StandardCharsets.UTF_8))
                .setExecutionExceptionHandler((exception, ignored, parseResult) -> {
                    SetupLocalCommandFactory.printExecutionFailure(err, exception, errorCode(exception));
                    if (!(exception instanceof ParameterException)) {
                        SetupDiagnosticReporter.reportFailure(exception, effectiveArgs, input, out, err);
                    }
                    return 2;
                })
                .setParameterExceptionHandler(SetupLocalCommandFactory.usageErrors());
        SetupLocalCommandFactory.hideUnsupportedSubcommandOptions(
                commandLine.getSubcommands().get("setup-local"));
        return commandLine.execute(effectiveArgs);
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
        public Integer call() throws IOException {
            try {
                CliInputValidation.rejectBlankText("--name", boardName);
                CliInputValidation.rejectBlankText("--workspace-id", workspaceId);
                CliInputValidation.rejectControlCharacters("--name", boardName);
                CliInputValidation.rejectControlCharacters("--workspace-id", workspaceId);
                CliInputValidation.rejectWorkspaceIdReference("--workspace-id", workspaceId);
                options.validateCliPaths();
                options.validateRuntimeEnvTarget();
                parent.boardSetup.preflightConnectedBoardManifest(options.manifestPath());
                parent.boardSetup.preflightRequestedServerPort(
                        options.serverPort, options.workflowPath, options.force, options.manifestPath());
                NewBoardRequest request = options.newBoardRequest(boardName, workspaceId);
                TrelloBoardSetup.NewBoardResult result = parent.boardSetup.createRecommendedBoard(request, options);
                options.persistRuntimeCredentials(parent.input, parent.out, parent.err);
                parent.boardSetup.persistConnectedBoard(
                        result,
                        options.runtimeEnvPath(),
                        options.workspaceRoot,
                        options.gitHubIntegration(),
                        options.manifestPath());
                printNewBoardResult(parent.out, result, options.runtimeEnvPath());
                return 0;
            } catch (TrelloBoardSetupException exception) {
                throw options.withRuntimeEnvHint(exception);
            }
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

        @Option(names = "--active", description = "Queued-work Trello list name.")
        void activeState(String value) {
            activeStates.addAll(CliValueNormalizer.commaSeparatedValues(value));
        }

        List<String> activeStates = new ArrayList<>();

        @Option(names = "--terminal", description = "Terminal Trello list name.")
        void terminalState(String value) {
            terminalStates.addAll(CliValueNormalizer.commaSeparatedValues(value));
        }

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
        public Integer call() throws IOException {
            if (noInProgress && inProgressState != null) {
                throw new ParameterException(spec.commandLine(), "--in-progress cannot be used with --no-in-progress.");
            }
            try {
                CliInputValidation.rejectBlankText("--board", boardId);
                CliInputValidation.rejectControlCharacters("--board", boardId);
                CliInputValidation.rejectBlankTextValues("--active", activeStates);
                CliInputValidation.rejectBlankTextValues("--terminal", terminalStates);
                CliInputValidation.rejectBlankText("--in-progress", inProgressState);
                CliInputValidation.rejectBlankText("--blocked", blockedState);
                CliInputValidation.rejectControlCharactersInTextValues("--active", activeStates);
                CliInputValidation.rejectControlCharactersInTextValues("--terminal", terminalStates);
                CliInputValidation.rejectControlCharacters("--in-progress", inProgressState);
                CliInputValidation.rejectControlCharacters("--blocked", blockedState);
                options.validateCliPaths();
                options.validateRuntimeEnvTarget();
                parent.boardSetup.preflightConnectedBoardManifest(options.manifestPath());
                parent.boardSetup.preflightWorkflowWrite(options.workflowPath, options.force);
                parent.boardSetup.preflightRequestedServerPort(
                        options.serverPort, options.workflowPath, options.force, options.manifestPath());
                ImportBoardRequest request = options.importBoardRequest(
                        TrelloBoardIds.parseImportBoardSelector(boardId),
                        activeStates,
                        terminalStates,
                        inProgressState,
                        !noInProgress && inProgressState == null,
                        blockedState);
                TrelloBoardSetup.ImportBoardResult result = parent.boardSetup.importExistingBoard(request, options);
                options.persistRuntimeCredentials(parent.input, parent.out, parent.err);
                parent.boardSetup.persistConnectedBoard(
                        result,
                        options.runtimeEnvPath(),
                        options.workspaceRoot,
                        options.gitHubIntegration(),
                        options.manifestPath());
                printImportBoardResult(parent.out, result, options.runtimeEnvPath());
                return 0;
            } catch (TrelloBoardSetupException exception) {
                throw options.withRuntimeEnvHint(exception);
            }
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
            parent.boardSetup.listWorkspaces(
                    new WorkspaceListRequest(TrelloApiEndpoint.normalize(endpoint), auth.credentials()), parent.out);
            return 0;
        }
    }

    @Command(
            name = "start",
            description = "Start managed local Symphony workers.",
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
            options.validateCliPaths();
            CliInputValidation.rejectControlCharacters("--env", envPath);
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
            options.validateCliPaths();
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
            options.validateCliPaths();
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
            options.validateCliPaths();
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

    @Command(
            name = "diagnostics",
            description = "Print sanitized diagnostics for issue reports.",
            versionProvider = TrelloBoardSetupMain.ProjectVersion.class,
            mixinStandardHelpOptions = true)
    static final class DiagnosticsCommand implements Callable<Integer> {
        @ParentCommand
        TrelloBoardSetupMain parent;

        @Mixin
        DiagnosticsOptions options = new DiagnosticsOptions();

        @Option(names = "--output", description = "Write diagnostics to this file instead of stdout.")
        Optional<Path> output = Optional.empty();

        @Option(names = "--json", description = "Write a JSON object containing the sanitized diagnostics.")
        boolean json;

        @Option(
                names = "--deep",
                description = "Run deeper public-safe diagnostics, including Codex and GitHub auth-status commands.")
        boolean deep;

        @Option(
                names = "--show-private-context",
                description = "Show private Trello identifiers, URLs, and local paths for local troubleshooting.")
        boolean privateContext;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            options.validateCliPaths();
            CliInputValidation.rejectControlCharacters("--output", output);
            CliInputValidation.rejectDashOutputFile(output);
            CliInputValidation.rejectStandardStreamOutputFile(output);
            CliInputValidation.rejectNonRegularOutputFile(output);
            var reporter = new SetupDiagnosticReporter(System.getenv(), new ProcessCommandRunner());
            var request = new DiagnosticsRequest(
                    options.board(),
                    output,
                    json,
                    deep,
                    options.appHome,
                    options.configDir,
                    options.workspaceRoot,
                    options.stateHome,
                    options.manifest,
                    options.workflow());
            String report = reporter.renderReport(request, privateContext);
            String body = json ? jsonReport(report) : report;
            output.ifPresentOrElse(path -> writeOutputFile(path, body), () -> printDiagnostics(body));
            return 0;
        }

        private void writeOutputFile(Path path, String body) {
            try {
                writeOutput(path, body);
            } catch (IOException e) {
                throw new ParameterException(
                        spec.commandLine(), "Could not write diagnostics output. Choose a writable file.", e);
            }
            parent.out.println("Diagnostics written.");
            if (privateContext) {
                parent.out.println("Review it before sharing. It contains private diagnostics context.");
            } else {
                parent.out.println("Review it before sharing. It is intended to omit secrets and private identifiers.");
            }
        }

        private void printDiagnostics(String body) {
            parent.out.print(body);
            if (!body.endsWith("\n")) {
                parent.out.println();
            }
        }

        private static String jsonReport(String report) throws IOException {
            return new ObjectMapper().writeValueAsString(Map.of("format", "markdown", "report", report))
                    + System.lineSeparator();
        }

        private static void writeOutput(Path path, String body) throws IOException {
            Path absolute = path.toAbsolutePath().normalize();
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(absolute, body, StandardCharsets.UTF_8);
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

        private void validateCliPaths() {
            CliInputValidation.rejectBlankBoardSelector(board);
            CliInputValidation.rejectBlankWorkflowSelector(workflow);
            CliInputValidation.rejectControlCharactersInText("--board", board);
            CliInputValidation.rejectControlCharacters("--workflow", workflow);
            CliInputValidation.rejectBlankPath("--config-dir", configDir, "--config-dir must not be empty.");
            CliInputValidation.rejectBlankPath(
                    "--workspace-root", workspaceRoot, "--workspace-root must not be empty.");
            CliInputValidation.rejectBlankPath("--state-home", stateHome, "--state-home must not be empty.");
            CliInputValidation.rejectBlankPath("--app-home", appHome, "--app-home must not be empty.");
            CliInputValidation.rejectControlCharacters("--config-dir", configDir);
            CliInputValidation.rejectControlCharacters("--workspace-root", workspaceRoot);
            CliInputValidation.rejectControlCharacters("--state-home", stateHome);
            CliInputValidation.rejectControlCharacters("--app-home", appHome);
        }
    }

    static final class DiagnosticsOptions {
        @ArgGroup(exclusive = true, multiplicity = "0..1")
        DiagnosticsSelector selector = new DiagnosticsSelector();

        @Option(names = "--config-dir", description = "Directory for local .env, workflows, and board manifest.")
        Optional<Path> configDir = Optional.empty();

        @Option(names = "--workspace-root", description = "Directory for per-card workspaces.")
        Optional<Path> workspaceRoot = Optional.empty();

        @Option(names = "--state-home", description = "Directory for managed worker PID and log files.")
        Optional<Path> stateHome = Optional.empty();

        @Option(names = "--manifest", description = "Connected-board manifest path.")
        Optional<Path> manifest = Optional.empty();

        @Option(names = "--app-home", hidden = true)
        Optional<Path> appHome = Optional.empty();

        Optional<String> board() {
            return selector.board;
        }

        Optional<Path> workflow() {
            return selector.workflow;
        }

        private void validateCliPaths() {
            CliInputValidation.rejectBlankBoardSelector(board());
            CliInputValidation.rejectControlCharactersInText("--board", board());
            rejectBlankDiagnosticsPath("--config-dir", configDir);
            rejectBlankDiagnosticsPath("--workspace-root", workspaceRoot);
            rejectBlankDiagnosticsPath("--state-home", stateHome);
            rejectBlankDiagnosticsPath("--manifest", manifest);
            CliInputValidation.rejectBlankWorkflowSelector(workflow());
            CliInputValidation.rejectControlCharacters("--config-dir", configDir);
            CliInputValidation.rejectControlCharacters("--workspace-root", workspaceRoot);
            CliInputValidation.rejectControlCharacters("--state-home", stateHome);
            CliInputValidation.rejectControlCharacters("--manifest", manifest);
            CliInputValidation.rejectControlCharacters("--app-home", appHome);
            CliInputValidation.rejectControlCharacters("--workflow", workflow());
            CliInputValidation.rejectExistingNonDirectoryPath("--config-dir", configDir);
        }

        private static void rejectBlankDiagnosticsPath(String optionName, Optional<Path> path) {
            CliInputValidation.rejectBlankPath(optionName, path, optionName + " must not be empty.");
        }
    }

    static final class DiagnosticsSelector {
        @Option(names = "--board", description = "Connected Trello board name, id, or short link.")
        Optional<String> board = Optional.empty();

        @Option(names = "--workflow", description = "Workflow file to include.")
        Optional<Path> workflow = Optional.empty();
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

        Path workspaceRoot = TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT;

        boolean workspaceRootExplicit;

        @Option(names = "--workspace-root", description = "Directory for per-card workspaces.")
        void workspaceRoot(Path workspaceRoot) {
            this.workspaceRoot = workspaceRoot;
            this.workspaceRootExplicit = true;
        }

        @Option(names = "--env", description = "Dotenv file to save Trello credentials for the generated worker.")
        Optional<Path> envPath = Optional.empty();

        @Option(names = "--server-port", description = "Local HTTP status port.")
        Integer serverPort;

        @Option(names = "--max-agents", description = "Maximum cards from this board that may run at once.")
        int maxConcurrentAgents = TrelloBoardSetup.DEFAULT_MAX_CONCURRENT_AGENTS;

        @Option(names = "--codex-model", description = "Codex model to write into generated workflows.")
        Optional<String> codexModel = Optional.empty();

        @Option(
                names = "--codex-reasoning-effort",
                description = "Codex reasoning effort to write into generated workflows.")
        Optional<String> codexReasoningEffort = Optional.empty();

        @Option(names = "--force", description = "Overwrite the workflow file when needed.")
        boolean force;

        NewBoardRequest newBoardRequest(String boardName, String workspaceId) {
            validate();
            return new NewBoardRequest(
                    endpoint,
                    auth.credentials(runtimeEnvPath()),
                    boardName,
                    workspaceId,
                    workflowPath,
                    workspaceRoot,
                    serverPort,
                    maxConcurrentAgents,
                    force,
                    !workflowPathExplicit,
                    gitHubIntegration(),
                    runtimeEnvPath());
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
                    auth.credentials(runtimeEnvPath()),
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
                    gitHubIntegration(),
                    false,
                    runtimeEnvPath());
        }

        private GitHubIntegration gitHubIntegration() {
            return github.selected().orElse(true) ? GitHubIntegration.ENABLED : GitHubIntegration.DISABLED;
        }

        private void validateRuntimeEnvTarget() throws IOException {
            if (!shouldUseRuntimeEnvTarget()) {
                return;
            }
            TrelloCredentialStore.validateEnvPath(runtimeEnvPath());
            TrelloCredentialStore.CredentialSelection credentials = auth.credentialSelection(runtimeEnvPath());
            if (credentials.persist()) {
                try {
                    TrelloCredentialStore.validateWritableEnvUpdate(credentials, runtimeEnvPath(), true);
                } catch (IOException exception) {
                    throw new TrelloBoardSetupException(
                            "setup_env_write_failed",
                            "Could not write Trello credentials to the selected .env file. Choose a writable .env or .env.NAME file.",
                            exception);
                }
            }
        }

        private void persistRuntimeCredentials(BufferedReader input, PrintStream out, PrintStream err)
                throws IOException {
            if (!shouldUseRuntimeEnvTarget()) {
                return;
            }
            TrelloCredentialStore.CredentialSelection credentials = auth.credentialSelection(runtimeEnvPath());
            if (!credentials.persist()) {
                return;
            }
            try {
                new TrelloCredentialStore(System.getenv())
                        .write(credentials, runtimeEnvPath(), new StreamTerminal(input, out, err), true);
            } catch (IOException exception) {
                throw new TrelloBoardSetupException(
                        "setup_env_write_failed",
                        "Could not write Trello credentials to the selected .env file. Choose a writable .env or .env.NAME file.",
                        exception);
            }
        }

        private Path manifestPath() {
            Path workflow = workflowPath.toAbsolutePath().normalize();
            Path parent = workflow.getParent();
            return (parent == null ? Path.of(".").toAbsolutePath().normalize() : parent)
                    .resolve("connected-boards.json");
        }

        private boolean shouldUseRuntimeEnvTarget() {
            return envPath.isPresent()
                    || LocalEnvironment.configuredDotenv().isPresent()
                    || auth.hasDirectCredentials();
        }

        private Path runtimeEnvPath() {
            return envPath.orElseGet(LocalEnvironment::defaultDotenv)
                    .toAbsolutePath()
                    .normalize();
        }

        private TrelloBoardSetupException withRuntimeEnvHint(TrelloBoardSetupException exception) {
            return switch (exception.code()) {
                case "setup_missing_api_key", "setup_missing_api_token", "setup_missing_trello_credentials" ->
                    exception.withDotenvPath(runtimeEnvPath());
                default -> exception;
            };
        }

        boolean hasExplicitCodexModelRequest() {
            return codexModel().isPresent() || codexReasoningEffort().isPresent();
        }

        private void validateCliPaths() {
            CliInputValidation.rejectControlCharacters("--workflow", workflowPath);
            CliInputValidation.rejectBlankPath("--workflow", workflowPath);
            CliInputValidation.rejectControlCharacters("--workspace-root", workspaceRoot);
            if (workspaceRootExplicit) {
                CliInputValidation.rejectBlankPath(
                        "--workspace-root", workspaceRoot, "--workspace-root must not be empty.");
                CliInputValidation.rejectRelativePath(
                        "--workspace-root", workspaceRoot, "--workspace-root must be an absolute path.");
            }
            CliInputValidation.rejectExistingNonDirectoryPath("--workspace-root", workspaceRoot);
            TrelloCredentialStore.validateEnvPathOption(envPath);
            validateCodexModelOverrides();
            if (serverPort != null) {
                LocalPort.validateCliServerPort(serverPort);
            }
        }

        Optional<String> codexModel() {
            return codexModel.map(String::strip).filter(value -> !value.isBlank());
        }

        Optional<String> codexReasoningEffort() {
            return codexReasoningEffort.map(String::strip).filter(value -> !value.isBlank());
        }

        private void validate() {
            endpoint = TrelloApiEndpoint.normalize(endpoint);
            github.validate();
            validateCodexModelOverrides();
            if (maxConcurrentAgents < 1) {
                throw new TrelloBoardSetupException("setup_invalid_max_agents", "--max-agents must be at least 1.");
            }
        }

        private void validateCodexModelOverrides() {
            CliInputValidation.rejectBlankText("--codex-model", codexModel);
            CliInputValidation.rejectBlankText("--codex-reasoning-effort", codexReasoningEffort);
            CliInputValidation.rejectControlCharactersInText("--codex-model", codexModel);
            CliInputValidation.rejectControlCharactersInText("--codex-reasoning-effort", codexReasoningEffort);
        }
    }

    static final class TrelloAuthOptions {
        @Option(names = "--key", description = "Trello API key. Defaults to TRELLO_API_KEY or .env.")
        String key;

        @Option(names = "--token", description = "Trello API token. Defaults to TRELLO_API_TOKEN or .env.")
        String token;

        TrelloCredentials credentials() {
            return credentials(LocalEnvironment.defaultDotenv());
        }

        TrelloCredentials credentials(Path dotenv) {
            TrelloCredentialStore.CredentialSelection selection = credentialSelection(dotenv);
            return selection.credentials();
        }

        TrelloCredentialStore.CredentialSelection credentialSelection() {
            return credentialSelection(LocalEnvironment.defaultDotenv());
        }

        TrelloCredentialStore.CredentialSelection credentialSelection(Path dotenv) {
            return new TrelloCredentialStore.CredentialSelection(
                    credentialValue(key, "TRELLO_API_KEY", dotenv), credentialValue(token, "TRELLO_API_TOKEN", dotenv));
        }

        boolean hasDirectCredentials() {
            return CliValueNormalizer.trimmedOrNull(key) != null || CliValueNormalizer.trimmedOrNull(token) != null;
        }

        private static TrelloCredentialStore.CredentialValue credentialValue(
                String directValue, String envName, Path dotenv) {
            if (directValue != null) {
                return TrelloCredentialStore.CredentialValue.direct(directValue);
            }
            return processEnv(envName)
                    .map(TrelloCredentialStore.CredentialValue::environment)
                    .orElseGet(() -> TrelloCredentialStore.CredentialValue.dotenv(
                            LocalEnvironment.get(envName, dotenv).orElse(null)));
        }
    }

    private static Optional<String> processEnv(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static Path defaultWorkflowPath() {
        String configDir = property(CONFIG_DIR_PROPERTY);
        if (configDir == null || configDir.isBlank()) {
            return TrelloBoardSetup.DEFAULT_WORKFLOW_PATH;
        }
        return Path.of(configDir).resolve(TrelloBoardSetup.DEFAULT_WORKFLOW_PATH);
    }

    private static void printNewBoardResult(PrintStream out, TrelloBoardSetup.NewBoardResult result, Path envPath) {
        out.println("Created Trello board: " + result.boardName());
        if (result.boardUrl() != null && !result.boardUrl().isBlank()) {
            out.println("Board URL: " + result.boardUrl());
        }
        out.println("Board identifier for WORKFLOW.md: " + result.boardKey());
        out.println("Created lists: " + String.join(", ", result.lists()));
        out.println("Wrote workflow: " + result.workflowPath().toAbsolutePath().normalize());
        out.println("HTTP status port: " + result.serverPort());
        out.println();
        out.println("Next:");
        printInstalledCliNextSteps(out, envPath, result.workflowPath());
    }

    private static void printImportBoardResult(
            PrintStream out, TrelloBoardSetup.ImportBoardResult result, Path envPath) {
        out.println("Imported Trello board: " + result.boardName());
        if (result.boardUrl() != null && !result.boardUrl().isBlank()) {
            out.println("Board URL: " + result.boardUrl());
        }
        out.println("Board identifier for WORKFLOW.md: " + result.boardKey());
        out.println("Open lists: " + String.join(", ", result.openLists()));
        out.println("Active lists: " + String.join(", ", result.activeStates()));
        out.println("In-progress list: " + optionalListName(result.inProgressState()));
        out.println("Terminal lists: " + String.join(", ", result.terminalStates()));
        out.println("Blocked list: " + optionalListName(result.blockedState()));
        out.println("Wrote workflow: " + result.workflowPath().toAbsolutePath().normalize());
        out.println("HTTP status port: " + result.serverPort());
        out.println();
        out.println("Next:");
        printInstalledCliNextSteps(out, envPath, result.workflowPath());
    }

    private static void printInstalledCliNextSteps(PrintStream out, Path envPath, Path workflowPath) {
        Path dotenv = envPath.toAbsolutePath().normalize();
        Path workflow = workflowPath.toAbsolutePath().normalize();
        String command = shellCommand(installedCliCommand());
        out.println("  " + command + " start --env " + shellQuote(dotenv.toString()) + " --workflow "
                + shellQuote(workflow.toString()));
        out.println("  " + command + " logs --workflow " + shellQuote(workflow.toString()));
    }

    private static String installedCliCommand() {
        String configured = property(CLI_COMMAND_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return "symphony-trello";
        }
        return configured;
    }

    private static String shellCommand(String command) {
        if ("powershell".equalsIgnoreCase(property(SHELL_PROPERTY)) && !"symphony-trello".equals(command)) {
            return "& " + shellQuote(command);
        }
        return "symphony-trello".equals(command) ? command : shellQuote(command);
    }

    private static String shellQuote(String value) {
        String shell = property(SHELL_PROPERTY);
        if ("powershell".equalsIgnoreCase(shell)) {
            return "'" + value.replace("'", "''") + "'";
        }
        if ("cmd".equalsIgnoreCase(shell)) {
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String property(String name) {
        return PROPERTY_LOOKUP.get().get(name);
    }

    private interface PropertyLookup {
        String get(String name);
    }

    private static String optionalListName(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }
}
