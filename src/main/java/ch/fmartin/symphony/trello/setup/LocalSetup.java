package ch.fmartin.symphony.trello.setup;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import ch.fmartin.symphony.trello.CliExitCodes;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.GitHubIntegration;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import ch.fmartin.symphony.trello.setup.TrelloCredentialStore.CredentialSelection;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class LocalSetup {
    private static final Path DEFAULT_ENV_PATH = Path.of(".env");
    private static final String DEFAULT_COMMAND = "symphony-trello";
    private static final String CONFIG_DIR_ENV = "SYMPHONY_TRELLO_CONFIG_DIR";
    private static final String COMMAND_ENV = "SYMPHONY_TRELLO_COMMAND";
    private static final String CALLER_DIR_ENV = "SYMPHONY_TRELLO_CALLER_DIR";
    static final String INSTALLER_COMPLETION_ENV = "SYMPHONY_TRELLO_INSTALLER_COMPLETION";
    private static final String INSTALLER_COMPLETION_DEFER = "defer";
    private static final String INSTALLER_COMPLETION_PRINT = "print";
    private static final Duration LOCAL_STATUS_TIMEOUT = Duration.ofMillis(500);

    private final TrelloBoardSetup boardSetup;
    private final Map<String, String> environment;
    private final WorkflowConfigEditor workflowConfig;
    private final LocalHealthChecker healthChecker;
    private final LocalWorkerManager workerManager;
    private final PrerequisiteChecker prerequisiteChecker;
    private final CodexAuthFlow codexAuthFlow;
    private final TrelloCredentialStore credentialStore;
    private final GitHubConfigurator githubConfigurator;
    private final TrelloBoardConnector boardConnector;
    private final WorkspaceAccessFlow workspaceAccessFlow;
    private final CodexSandboxFlow codexSandboxFlow;
    private final SetupDiagnosticReporter diagnosticReporter;

    public LocalSetup(TrelloBoardSetup boardSetup, CommandRunner commands) {
        this(boardSetup, commands, System.getenv());
    }

    LocalSetup(TrelloBoardSetup boardSetup, CommandRunner commands, Map<String, String> environment) {
        this(boardSetup, commands, environment, new WorkflowConfigEditor(), null);
    }

    LocalSetup(
            TrelloBoardSetup boardSetup,
            CommandRunner commands,
            Map<String, String> environment,
            WorkflowConfigEditor workflowConfig,
            LocalWorkerManager workerManager) {
        this(boardSetup, commands, environment, workflowConfig, workerManager, () -> System.getProperty("os.name", ""));
    }

    LocalSetup(
            TrelloBoardSetup boardSetup,
            CommandRunner commands,
            Map<String, String> environment,
            WorkflowConfigEditor workflowConfig,
            LocalWorkerManager workerManager,
            Supplier<String> osName) {
        this.boardSetup = boardSetup;
        this.environment = Map.copyOf(environment);
        this.workflowConfig = workflowConfig;
        HttpClient httpClient = localStatusHttpClient(); // NOPMD - LocalSetup owns this client for its lifetime.
        this.healthChecker = new LocalHealthChecker(environment, workflowConfig, httpClient);
        this.workerManager =
                workerManager == null ? new LocalWorkerManager(environment, workflowConfig) : workerManager;
        this.prerequisiteChecker = new PrerequisiteChecker(commands);
        this.codexAuthFlow = new CodexAuthFlow(commands);
        this.credentialStore = new TrelloCredentialStore(this.environment);
        this.githubConfigurator = new GitHubConfigurator(commands, osName);
        this.boardConnector =
                new TrelloBoardConnector(boardSetup, workflowConfig, this.workerManager, this.environment);
        this.workspaceAccessFlow = new WorkspaceAccessFlow();
        this.codexSandboxFlow = new CodexSandboxFlow();
        this.diagnosticReporter = new SetupDiagnosticReporter(this.environment, commands);
    }

    public static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        ObjectMapper json = new ObjectMapper();
        return run(args, in, out, err, () -> new CodexModelDefaultsResolver(json).resolveSelectionDefaults());
    }

    static int run(
            String[] args,
            InputStream in,
            PrintStream out,
            PrintStream err,
            Supplier<CodexModelSelectionDefaults> codexModelSelectionDefaults) {
        ObjectMapper json = new ObjectMapper();
        return new SetupLocalCommandFactory()
                .execute(
                        args,
                        new LocalSetup(
                                new TrelloBoardSetup(json, codexModelSelectionDefaults), new ProcessCommandRunner()),
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)),
                        out,
                        err);
    }

    int run(String[] args, BufferedReader input, PrintStream out, PrintStream err) {
        return new SetupLocalCommandFactory().execute(args, this, input, out, err);
    }

    int run(LocalSetupRequest request, BufferedReader input, PrintStream out, PrintStream err) {
        return run(request, new StreamTerminal(input, out, err));
    }

    int run(LocalSetupRequest request, Terminal terminal) {
        PrintStream out = borrowedOut(terminal); // NOPMD - Terminal owns the stream.
        PrintStream err = borrowedErr(terminal); // NOPMD - Terminal owns the stream.
        Options options = null;
        boolean completionOnly = installerCompletionMode(INSTALLER_COMPLETION_PRINT);
        try {
            options = Options.from(request, environment);
            if (completionOnly) {
                printFinalHandoff(out, connectedBoards(options).loadForLifecycle(), options.command());
                return 0;
            }

            printHeader(out, options);
            if (!confirmWorkspaceRoot(options, terminal)) {
                return 0;
            }
            Prerequisites prerequisites = prerequisites();
            printPrerequisites(out, prerequisites, options);
            if (options.check()) {
                return runCheck(options, prerequisites, out);
            }
            if (options.repairPort()) {
                return repairPort(options, out);
            }
            if (options.dryRun()) {
                options = validatePreflightCodexReasoningEffort(options, prerequisites);
                rejectInvalidUnconnectedBoardSelector(connectedBoardsUnchecked(options), options);
                boardConnector.rejectDryRunNewBoardInProgress(options);
                preflightLocalWorkflowWrite(options);
                printDryRun(out, options, prerequisites);
                return 0;
            }
            ConnectedBoardRepository boards = connectedBoards(options);
            ConnectedBoardManifest manifest = boards.loadForLifecycle();
            rejectInvalidUnconnectedBoardSelector(manifest, options);
            if (!manifest.boards().isEmpty() && !options.forceNewSetup()) {
                ExistingSetupAction action = existingSetupAction(manifest, terminal, options);
                if (action == ExistingSetupAction.DISCONNECT) {
                    disconnectBoard(options, manifest, terminal);
                    return 0;
                }
                if (action != ExistingSetupAction.CONNECT) {
                    codexAuthFlow.ensureAuthenticated(prerequisites, options, terminal);
                    if (action == ExistingSetupAction.KEEP) {
                        out.println();
                        out.println("Keeping connected Trello boards.");
                        startExistingBoards(options, manifest, out);
                        printFinalHandoffUnlessDeferred(out, manifest, options.command());
                        return 0;
                    }
                    if (action == ExistingSetupAction.UPDATE_CODEX_ACCESS) {
                        updateExistingCodexAccess(options, manifest, terminal);
                        return 0;
                    }
                    if (action == ExistingSetupAction.UPGRADE_GITHUB) {
                        upgradeExistingBoardToGithub(options, manifest, terminal);
                        return 0;
                    }
                }
            }
            TrelloBoardConnector.BoardSetupChoice boardSetupChoice = boardConnector.chooseBoardSetup(options, terminal);
            options = configureRepositoryUrl(options, terminal);
            options = validatePreflightCodexReasoningEffort(options, prerequisites);
            boardConnector.preflightRequestedServerPort(options, manifest);
            codexAuthFlow.ensureAuthenticated(prerequisites, options, terminal);
            options = validatePreflightCodexReasoningEffort(options);

            preflightLocalWorkflowWrite(options);
            CredentialSelection credentialSelection = credentials(options, options.envPath(), terminal);
            TrelloCredentials credentials = credentialSelection.credentials();
            TrelloBoardSetup.MemberInfo memberInfo =
                    boardSetup.getMemberInfo(new TrelloBoardSetup.MemberInfoRequest(options.endpoint(), credentials));
            out.println();
            out.println("Validating Trello...");
            out.println("  OK  Connected to Trello as \"" + memberInfo.displayName() + "\"");
            if (!credentialSelection.persist()) {
                out.println();
                out.println("Using Trello credentials...");
                out.println(
                        "  OK  Credentials loaded from " + credentialSelection.sourceDescription(options.envPath()));
                out.println("  OK  Trello API key: " + TrelloCredentialStore.redact(credentials.apiKey()));
                out.println("  OK  Trello token: " + TrelloCredentialStore.redact(credentials.apiToken()));
            }

            Options githubOptions = options;
            TrelloBoardConnector.GitHubIntegrationResolution githubIntegrationResolution =
                    () -> resolveGitHubIntegration(githubOptions, prerequisites, terminal);
            Path credentialEnvPath = options.envPath();
            TrelloBoardConnector.CredentialPersistence credentialPersistence = credentialSelection.persist()
                    ? () -> writeEnv(credentialSelection, credentialEnvPath, terminal)
                    : () -> {};
            SetupResult result = boardConnector.createOrConnectBoard(
                    boardSetupChoice,
                    options,
                    credentials,
                    githubIntegrationResolution,
                    manifest,
                    credentialPersistence,
                    terminal);
            GitHubIntegration githubIntegration = result.githubIntegration();
            options = configureCodexAccess(options, terminal);
            applyCodexAccess(options, result.workflowPath());
            ConnectedBoard connectedBoard = ConnectedBoard.from(result, options, githubIntegration);
            List<ConnectedBoard> replacedBoards = manifest.boardsReplacedBy(connectedBoard);
            stopReplacedBoards(options, replacedBoards);
            workerManager.rotateLogsForReplacedBoards(localWorkerPaths(options), connectedBoard, replacedBoards);
            manifest = manifest.withBoard(connectedBoard);
            boards.save(manifest);

            out.println();
            out.println("Trello board");
            out.println("  OK  Board connected: " + DisplayNames.quotedName(result.boardName()));
            out.println("  OK  Board lists: " + quoted(result.lists()));
            out.println("  OK  Workflow written: "
                    + result.workflowPath().toAbsolutePath().normalize());
            RepositoryDefaultSummary.printGuided(
                    out, workflowConfig.repositoryDefaults(result.workflowPath()), result.workflowPath());
            out.println("  OK  Local server port selected for " + DisplayNames.quotedName(result.boardName()) + ": "
                    + result.serverPort());
            printWorkspaceAndSandboxSummary(options, out);
            startBoard(options, connectedBoard, out);
            out.println();
            out.println("Board:");
            out.println("  " + result.boardUrl());
            printFinalHandoffUnlessDeferred(out, manifest, options.command());
            return 0;
        } catch (TrelloBoardSetupException | IllegalArgumentException | IOException e) {
            err.println("setup_failed code=%s message=%s".formatted(errorCode(e), e.getMessage()));
            Optional<Path> hintEnvPath = Optional.ofNullable(options).map(Options::envPath);
            SetupDiagnosticReporter.userActionHint(e, hintEnvPath).ifPresent(hint -> err.println("Next step: " + hint));
            if (!completionOnly) {
                diagnosticReporter.reportFailure(e, request, terminal);
            }
            return CliExitCodes.SETUP_FAILURE;
        }
    }

    private boolean installerCompletionMode(String expected) {
        return expected.equals(environment.get(INSTALLER_COMPLETION_ENV));
    }

    private void printFinalHandoffUnlessDeferred(PrintStream out, ConnectedBoardManifest manifest, String cliCommand) {
        if (!installerCompletionMode(INSTALLER_COMPLETION_DEFER)) {
            printFinalHandoff(out, manifest, cliCommand);
        }
    }

    private void preflightLocalWorkflowWrite(Options options) {
        if (!options.workflowPathExplicit()) {
            return;
        }
        // Same workflow write preflight as direct new-board/import-board: directory paths,
        // file-valued parents, existing files without --force, and unwritable parents are
        // expected input errors before any dry-run plan or Trello member validation.
        boardSetup.preflightWorkflowWrite(options.workflowPath(), options.force());
    }

    private static ConnectedBoardRepository connectedBoards(Options options) {
        return new ConnectedBoardRepository(options.manifestPath());
    }

    private ConnectedBoardManifest connectedBoardsUnchecked(Options options) {
        try {
            return connectedBoards(options).load();
        } catch (IOException e) {
            return new ConnectedBoardManifest(List.of());
        }
    }

    private LocalWorkerPaths localWorkerPaths(Options options) {
        return LocalWorkerPaths.from(
                Optional.empty(),
                Optional.of(options.configDir()),
                Optional.of(options.workspaceRoot()),
                Optional.empty(),
                environment);
    }

    private int runCheck(Options options, Prerequisites prerequisites, PrintStream out) throws IOException {
        ConnectedBoardRepository.ManifestLoadResult manifestLoad =
                connectedBoards(options).loadForCheck();
        ConnectedBoardManifest manifest = manifestLoad.manifest();
        boolean ok = prerequisites.readyFor(options);
        if (prerequisites.readyFor(options)) {
            out.println("  OK      Local prerequisites ready");
        }
        for (String warning : manifestLoad.warnings()) {
            out.println("  WARN    " + warning);
            ok = false;
        }
        if (manifest.boards().isEmpty()) {
            if (!manifestLoad.warnings().isEmpty()) {
                return CliExitCodes.SETUP_FAILURE;
            }
            out.println("  WARN    No Trello boards connected to Symphony");
            return prerequisites.readyFor(options) ? 0 : CliExitCodes.SETUP_FAILURE;
        }
        List<ConnectedBoard> selectedBoards = boardsForCheck(manifest, options);
        for (ConnectedBoard board : selectedBoards) {
            ConnectedBoardLocalValidation localValidation = validateConnectedBoardLocalPaths(board);
            for (String warning : localValidation.warnings()) {
                out.println("  WARN    " + warning);
            }
            ConnectedBoard healthBoard = withWorkflowServerPort(board);
            BoardHealth health = localValidation.ok()
                    ? healthChecker.boardHealth(healthBoard)
                    : new BoardHealth(BoardHealthKind.STOPPED, board.serverPort(), Optional.empty(), Optional.empty());
            if (!localValidation.ok()) {
                ok = false;
            } else {
                Function<String, Optional<String>> workflowEnvironment =
                        WorkflowEnvironmentResolver.resolver(environment, board.envPath());
                WorkflowValidation workflow = workflowConfig.validate(board, workflowEnvironment);
                if (workflow.ok() || isHealthyStaleManifestPort(board, healthBoard, workflow, health)) {
                    out.println("  OK      Workflow: " + board.workflowPath());
                } else {
                    out.println("  WARN    " + workflow.message());
                    ok = false;
                }
            }
            if (board.githubEnabled() && !prerequisites.githubAuth().available()) {
                out.println(
                        "  WARN    GitHub CLI is not authenticated for " + DisplayNames.quotedName(board.boardName()));
                ok = false;
            }
            if (localValidation.envUsable() && !checkTrelloCredentials(options, board, out)) {
                ok = false;
            }
            if (localValidation.ok()) {
                printBoardHealth(options, manifest, healthBoard, health, out);
                ok = ok && health.kind() == BoardHealthKind.SAME_WORKFLOW;
            }
        }
        return ok ? 0 : CliExitCodes.SETUP_FAILURE;
    }

    private static boolean isHealthyStaleManifestPort(
            ConnectedBoard board, ConnectedBoard healthBoard, WorkflowValidation workflow, BoardHealth health) {
        return board.serverPort() != healthBoard.serverPort()
                && health.kind() == BoardHealthKind.SAME_WORKFLOW
                && workflow.message().startsWith("Workflow server.port does not match the connected board");
    }

    private static List<ConnectedBoard> boardsForCheck(ConnectedBoardManifest manifest, Options options) {
        return options.existingBoardId()
                .map(selector -> List.of(selectedConnectedBoard(manifest, selector)))
                .orElseGet(manifest::boards);
    }

    private static ConnectedBoardLocalValidation validateConnectedBoardLocalPaths(ConnectedBoard board) {
        List<String> warnings = new ArrayList<>();
        boolean envUsable = true;
        if (board.workflowPath() == null) {
            warnings.add("Workflow path for " + DisplayNames.quotedName(board.boardName())
                    + " is missing from "
                    + ConnectedBoardManifest.FILE_NAME
                    + ".");
        } else if (Files.isDirectory(board.workflowPath())) {
            warnings.add("Workflow path for " + DisplayNames.quotedName(board.boardName())
                    + " must be a workflow file, but it is a directory: " + board.workflowPath());
        }
        if (board.workspaceRoot() == null) {
            warnings.add("Workspace root for " + DisplayNames.quotedName(board.boardName())
                    + " is missing from "
                    + ConnectedBoardManifest.FILE_NAME
                    + ".");
        } else if (Files.exists(board.workspaceRoot()) && !Files.isDirectory(board.workspaceRoot())) {
            warnings.add("Workspace root for " + DisplayNames.quotedName(board.boardName()) + " must be a directory: "
                    + board.workspaceRoot());
        }
        if (!LocalPort.isValid(board.serverPort())) {
            warnings.add("Connected board " + DisplayNames.quotedName(board.boardName()) + " has invalid server port "
                    + board.serverPort() + "; expected " + LocalPort.MIN + " to " + LocalPort.MAX + ".");
        }
        if (board.envPath() == null) {
            warnings.add("Trello credential path for " + DisplayNames.quotedName(board.boardName())
                    + " is missing from "
                    + ConnectedBoardManifest.FILE_NAME
                    + ".");
            envUsable = false;
        } else if (Files.isDirectory(board.envPath())) {
            warnings.add("Trello credential path for " + DisplayNames.quotedName(board.boardName())
                    + " must be a dotenv file, but it is a directory: " + board.envPath());
            envUsable = false;
        }
        for (Path root : board.additionalWritableRoots()) {
            if (!root.isAbsolute()) {
                warnings.add("Additional writable root for " + DisplayNames.quotedName(board.boardName())
                        + " must be an absolute path: " + root);
            }
        }
        return new ConnectedBoardLocalValidation(List.copyOf(warnings), envUsable);
    }

    private static void printBoardHealth(
            Options options,
            ConnectedBoardManifest manifest,
            ConnectedBoard board,
            BoardHealth health,
            PrintStream out) {
        if (health.kind() == BoardHealthKind.SAME_WORKFLOW) {
            out.println("  OK    " + DisplayNames.quotedName(board.boardName()) + " local server: "
                    + LocalHealthChecker.localServerUrl(health.port()) + " (already running)");
            return;
        }
        if (health.kind() == BoardHealthKind.STOPPED) {
            out.println("  WARN  " + DisplayNames.quotedName(board.boardName()) + " local server is not running");
            out.println("        Start: " + options.command() + " start --env " + board.envPath() + " --workflow "
                    + board.workflowPath());
            return;
        }
        if (health.kind() == BoardHealthKind.PORT_USED) {
            out.println("  WARN  " + DisplayNames.quotedName(board.boardName()) + " configured port " + health.port()
                    + " is in use by another process");
            out.println("        Suggested fix: " + repairPortCommand(options, manifest, board));
            return;
        }
        out.println("  WARN  " + DisplayNames.quotedName(board.boardName()) + " local server: "
                + LocalHealthChecker.localServerUrl(health.port()) + " (wrong Symphony workflow or board)");
        out.println("        Expected workflow: "
                + board.workflowPath().toAbsolutePath().normalize());
        out.println("        Actual workflow: " + health.actualWorkflowPath().orElse("<unknown>"));
        out.println("        Expected board: " + board.boardId() + " or " + board.boardKey());
        out.println("        Actual board: " + health.actualBoardId().orElse("<unknown>"));
        out.println("        Suggested fix: " + repairPortCommand(options, manifest, board));
    }

    private static String repairPortCommand(Options options, ConnectedBoardManifest manifest, ConnectedBoard board) {
        return options.command() + " setup-local repair-port --board \"" + repairPortSelector(manifest, board) + "\"";
    }

    private static String repairPortSelector(ConnectedBoardManifest manifest, ConnectedBoard board) {
        long sameNameCount = manifest.boards().stream()
                .filter(candidate -> equalsIgnoreCase(candidate.boardName(), board.boardName()))
                .count();
        if (sameNameCount <= 1 && commandSafeSelector(board.boardName())) {
            return board.boardName();
        }
        if (!blank(board.boardKey()) && commandSafeSelector(board.boardKey())) {
            return board.boardKey();
        }
        return board.boardId();
    }

    /**
     * The suggested repair command wraps the selector in plain double quotes, where {@code $} and
     * backticks still expand in POSIX shells and PowerShell, {@code !} still triggers history
     * expansion in interactive bash, paired {@code %} still expands in cmd, backslashes and
     * embedded quotes break the quoting itself, and the CLI rejects control characters in its own
     * arguments. A board name containing any of those can never become a copyable runnable
     * suggestion, so the opaque board key or id selects the same board safely instead. All other
     * characters, including spaces and the remaining punctuation, are literal inside double quotes
     * in POSIX shells, PowerShell, and cmd.
     */
    private static boolean commandSafeSelector(String value) {
        return value != null
                && !value.isBlank()
                && value.chars()
                        .noneMatch(c -> c == '"'
                                || c == '\\'
                                || c == '$'
                                || c == '`'
                                || c == '!'
                                || c == '%'
                                || Character.isISOControl(c));
    }

    private boolean checkTrelloCredentials(Options options, ConnectedBoard board, PrintStream out) {
        try {
            CredentialSelection credentials = credentialStore.loadExisting(options, board.envPath());
            if (TrelloCredentialStore.blank(credentials.apiKeyValue())
                    || TrelloCredentialStore.blank(credentials.apiTokenValue())) {
                out.println("  WARN    Trello credentials are missing for " + DisplayNames.quotedName(board.boardName())
                        + ": " + board.envPath());
                return false;
            }
            TrelloBoardSetup.MemberInfo member = boardSetup.getMemberInfo(
                    new TrelloBoardSetup.MemberInfoRequest(options.endpoint(), credentials.credentials()));
            out.println("  OK      Trello credentials for " + DisplayNames.quotedName(board.boardName()) + " as "
                    + member.displayName());
            return true;
        } catch (RuntimeException e) {
            out.println("  WARN    Trello credential check failed for " + DisplayNames.quotedName(board.boardName())
                    + ": " + e.getMessage());
            return false;
        }
    }

    private int repairPort(Options options, PrintStream out) throws IOException {
        ConnectedBoardRepository boards = connectedBoards(options);
        ConnectedBoardManifest manifest = boards.loadForLifecycle();
        if (manifest.boards().isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_repair_board_not_found",
                    "No Trello boards are connected to Symphony in the selected manifest. Run setup-local or import-board first, or rerun repair-port with the correct --config-dir or --manifest.");
        }
        String boardSelector = options.repairBoardName()
                .orElseThrow(() -> new TrelloBoardSetupException(
                        "setup_repair_board_required", "repair-port requires --board NAME."));
        ConnectedBoard board = selectedConnectedBoard(
                manifest,
                boardSelector,
                "setup_repair_board_not_found",
                "No connected Trello board matches \"" + boardSelector + "\".");
        healthChecker.externalHttpPortOverrideSource(board.envPath()).ifPresent(overrideSource -> {
            throw new TrelloBoardSetupException(
                    "setup_repair_port_http_override",
                    "setup-local repair-port cannot update workflow server.port while "
                            + overrideSource
                            + " overrides the HTTP port. Remove or update SYMPHONY_HTTP_PORT/QUARKUS_HTTP_PORT in the board env file or service environment, then rerun setup-local repair-port.");
        });
        ConnectedBoard reconciledBoard = withWorkflowServerPort(board);
        BoardHealth health = healthChecker.boardHealth(reconciledBoard);
        if (health.kind() == BoardHealthKind.SAME_WORKFLOW
                && reconciledBoard.serverPort() != board.serverPort()
                && !serverPortReservedByOtherBoard(manifest, reconciledBoard)) {
            return repairManifestPort(options, out, boards, manifest, board, reconciledBoard);
        }
        if (reconciledBoard.serverPort() == board.serverPort()
                && !serverPortReservedByOtherBoard(manifest, reconciledBoard)
                && (health.kind() == BoardHealthKind.SAME_WORKFLOW || health.kind() == BoardHealthKind.STOPPED)) {
            out.println("  OK      No port repair needed. " + DisplayNames.quotedName(reconciledBoard.boardName())
                    + " is already configured for an available port: "
                    + LocalHealthChecker.localServerUrl(reconciledBoard.serverPort()));
            return 0;
        }
        boolean wasRunning = health.kind() == BoardHealthKind.SAME_WORKFLOW;
        int port = nextAvailablePort(options, manifest, reconciledBoard);
        if (options.dryRun()) {
            out.println();
            out.println("Dry run");
            out.println("  WOULD   update " + DisplayNames.quotedName(reconciledBoard.boardName()) + " to use "
                    + LocalHealthChecker.localServerUrl(port));
            if (wasRunning) {
                out.println("  WOULD   restart Symphony for " + DisplayNames.quotedName(reconciledBoard.boardName()));
            } else {
                out.println("          Restart: " + options.command() + " start --env " + reconciledBoard.envPath()
                        + " --workflow " + reconciledBoard.workflowPath());
            }
            return 0;
        }
        ensureManagedRestartPossible(options, reconciledBoard, health);
        if (wasRunning) {
            stopBoard(options, reconciledBoard.boardName(), reconciledBoard.workflowPath());
        }
        workflowConfig.updateServerPort(reconciledBoard.workflowPath(), port);
        boards.save(manifest.withBoard(reconciledBoard.withServerPort(port)));
        out.println("  OK      Updated " + DisplayNames.quotedName(reconciledBoard.boardName()) + " to use "
                + LocalHealthChecker.localServerUrl(port));
        if (wasRunning) {
            startBoard(options, reconciledBoard.withServerPort(port), out);
        } else {
            out.println("          Restart: " + options.command() + " start --env " + reconciledBoard.envPath()
                    + " --workflow " + reconciledBoard.workflowPath());
        }
        return 0;
    }

    private ConnectedBoard withWorkflowServerPort(ConnectedBoard board) {
        if (board.workflowPath() == null) {
            return board;
        }
        return workflowConfig
                .serverPort(board.workflowPath(), WorkflowEnvironmentResolver.resolver(environment, board.envPath()))
                .map(board::withServerPort)
                .orElse(board);
    }

    private static int repairManifestPort(
            Options options,
            PrintStream out,
            ConnectedBoardRepository boards,
            ConnectedBoardManifest manifest,
            ConnectedBoard staleBoard,
            ConnectedBoard reconciledBoard)
            throws IOException {
        if (options.dryRun()) {
            out.println();
            out.println("Dry run");
            out.println(
                    "  WOULD   update connected-board manifest for " + DisplayNames.quotedName(staleBoard.boardName())
                            + " to use " + LocalHealthChecker.localServerUrl(reconciledBoard.serverPort()));
            out.println("          Workflow and running Symphony worker already use this port.");
            return 0;
        }
        boards.save(manifest.withBoard(reconciledBoard));
        out.println("  OK      Updated connected-board manifest for " + DisplayNames.quotedName(staleBoard.boardName())
                + " to use " + LocalHealthChecker.localServerUrl(reconciledBoard.serverPort()));
        return 0;
    }

    private static boolean serverPortReservedByOtherBoard(ConnectedBoardManifest manifest, ConnectedBoard board) {
        return manifest.boards().stream()
                .filter(connectedBoard -> !connectedBoard.boardId().equals(board.boardId()))
                .anyMatch(connectedBoard -> connectedBoard.serverPort() == board.serverPort());
    }

    private int nextAvailablePort(Options options, ConnectedBoardManifest manifest, ConnectedBoard ignoredBoard) {
        Set<Integer> reserved = manifest.boards().stream()
                .filter(board -> !board.boardId().equals(ignoredBoard.boardId()))
                .map(ConnectedBoard::serverPort)
                // Use a mutable HashSet so file-discovered ports can be merged below and each port check is fast.
                .collect(Collectors.toCollection(HashSet::new));
        // Local workflow files outside the manifest still reserve their ports, so a repaired
        // board cannot collide with a stale or disconnected workflow that may be started later.
        reserved.addAll(localWorkflowFilePortReservations(options, ignoredBoard));
        for (int port = TrelloBoardSetup.DEFAULT_SERVER_PORT; port <= LocalPort.MAX; port++) {
            if (!reserved.contains(port) && !boardSetup.portInUse(port)) {
                return port;
            }
        }
        throw new TrelloBoardSetupException("setup_server_port_unavailable", "No free local server port was found.");
    }

    private Set<Integer> localWorkflowFilePortReservations(Options options, ConnectedBoard ignoredBoard) {
        Path configDir = options.configDir();
        if (configDir == null || !Files.isDirectory(configDir)) {
            return Set.of();
        }
        try (var files = Files.list(configDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> PathNames.fileName(file).endsWith(".md"))
                    .filter(file -> ignoredBoard.workflowPath() == null
                            || !PathsEqual.samePath(file, ignoredBoard.workflowPath()))
                    .map(file -> workflowConfig.serverPort(
                            file, WorkflowEnvironmentResolver.resolver(environment, options.envPath())))
                    .flatMap(Optional::stream)
                    .collect(toImmutableSet());
        } catch (IOException ignored) {
            // A config directory that cannot be listed leaves only the manifest and probe checks.
            return Set.of();
        }
    }

    private Prerequisites prerequisites() {
        return prerequisiteChecker.check();
    }

    private CredentialSelection credentials(Options options, Path envPath, Terminal terminal) throws IOException {
        return credentialStore.loadOrPrompt(options, envPath, terminal);
    }

    private void writeEnv(CredentialSelection credentials, Path envPath, Terminal terminal) throws IOException {
        credentialStore.write(credentials, envPath, terminal);
    }

    private GitHubIntegration resolveGitHubIntegration(Options options, Prerequisites prerequisites, Terminal terminal)
            throws IOException {
        return githubConfigurator.resolve(options, prerequisites, terminal);
    }

    static boolean isBroadAccessPathForCli(Path path) {
        return WorkspaceAccessFlow.isBroadAccessPath(path);
    }

    private Options configureCodexAccess(Options options, Terminal terminal) throws IOException {
        List<Path> allowedPaths = workspaceAccessFlow.resolve(options, terminal);
        boolean dangerFullAccess = codexSandboxFlow.resolve(options, terminal);
        return options.withCodexAccess(allowedPaths, dangerFullAccess);
    }

    private static Options configureRepositoryUrl(Options options, Terminal terminal) throws IOException {
        if (options.nonInteractive() || options.repositoryUrl().isPresent()) {
            return options;
        }
        return options.withRepositoryUrl(
                RepositoryUrlInput.fromPrompt(terminal.readLine(RepositoryUrlInput.PROMPT + " ")));
    }

    private Options configureCodexModel(Options options, Path workflowPath, Terminal terminal) throws IOException {
        CodexModelSelectionDefaults catalog =
                options.codexModelCatalog().orElseGet(boardSetup::resolvedCodexModelSelectionDefaults);
        CodexModelSelectionFlow.Selection selected = new CodexModelSelectionFlow()
                .resolve(options, boardSetup.codexModelSelectionDefaultsForWorkflow(workflowPath, catalog), terminal);
        return options.withCodexModelCatalog(catalog).withCodexModelSelection(selected);
    }

    private Options validatePreflightCodexReasoningEffort(Options options) {
        return options.codexReasoningEffort()
                .filter(ignored -> codexModelKnownDuringPreflight(options))
                .map(reasoningEffort -> validatePreflightCodexReasoningEffort(options, reasoningEffort))
                .orElse(options);
    }

    private Options validatePreflightCodexReasoningEffort(Options options, Prerequisites prerequisites) {
        if (!options.dryRun() && !prerequisites.codexAuth().available()) {
            // Login can refresh model access, so dynamic catalog validation waits until authentication.
            return options;
        }
        return validatePreflightCodexReasoningEffort(options);
    }

    private Options validatePreflightCodexReasoningEffort(Options options, String reasoningEffort) {
        Optional<Path> workflowPath = TrelloBoardConnector.preflightWorkflowPath(options);
        if (options.codexModel().isEmpty() && workflowPath.isEmpty() && !preflightUsesCatalogDefaultModel(options)) {
            return options;
        }
        if (options.dryRun()) {
            return boardSetup
                    .resolvedCodexModelSelectionDefaultsForDryRun()
                    .map(catalog ->
                            validatePreflightCodexReasoningEffort(options, reasoningEffort, workflowPath, catalog))
                    .orElse(options);
        }
        CodexModelSelectionDefaults catalog =
                options.codexModelCatalog().orElseGet(boardSetup::resolvedCodexModelSelectionDefaults);
        return validatePreflightCodexReasoningEffort(options, reasoningEffort, workflowPath, catalog);
    }

    private Options validatePreflightCodexReasoningEffort(
            Options options, String reasoningEffort, Optional<Path> workflowPath, CodexModelSelectionDefaults catalog) {
        CodexModelSelectionDefaults selectionDefaults = options.codexModel()
                .map(ignored -> catalog)
                .or(() -> workflowPath.map(path -> boardSetup.codexModelSelectionDefaultsForWorkflow(path, catalog)))
                .orElse(catalog);
        String model = options.codexModel().orElseGet(selectionDefaults.defaults()::model);
        selectionDefaults.validateReasoningEffortForModel(model, reasoningEffort);
        return options.withCodexModelCatalog(catalog);
    }

    private static boolean codexModelKnownDuringPreflight(Options options) {
        return options.codexModel().isPresent()
                || (!options.configureGithub() && (options.dryRun() || options.nonInteractive()));
    }

    private static boolean preflightUsesCatalogDefaultModel(Options options) {
        if (!options.force() || options.existingBoardId().isEmpty() || !Files.isDirectory(options.configDir())) {
            return true;
        }
        try (var files = Files.list(options.configDir())) {
            return files.filter(Files::isRegularFile)
                    .map(PathNames::fileName)
                    .noneMatch(WorkflowFileNames::isGeneratedFileName);
        } catch (IOException ignored) {
            // An unreadable directory could contain the forced board's workflow, so its model is unknown.
            return false;
        }
    }

    private static boolean confirmWorkspaceRoot(Options options, Terminal terminal) throws IOException {
        if (!options.workspaceRootExplicit()
                || options.nonInteractive()
                || !WorkspaceAccessFlow.isBroadAccessPath(options.workspaceRoot())) {
            return true;
        }
        terminal.info("");
        terminal.info(
                "Using / as the workspace root lets Symphony create per-card workspaces from the whole filesystem root.");
        terminal.info("This is unsafe unless you intentionally want that.");
        if (PromptSupport.yes(terminal, "Use / as the workspace root anyway? [y/N] ")) {
            return true;
        }
        terminal.info("Workspace-root selection cancelled.");
        return false;
    }

    private TrelloBoardSetup boardSetupWithCodexModel(Options options) {
        return options.codexModelDefaults()
                .map(defaults -> options.hasExplicitCodexModelRequest()
                        ? boardSetup.withCodexModelOverrides(
                                defaults, options.codexModel(), options.codexReasoningEffort())
                        : boardSetup.withCodexModelDefaults(defaults))
                .orElse(boardSetup);
    }

    private static ExistingSetupAction existingSetupAction(
            ConnectedBoardManifest manifest, Terminal terminal, Options options) throws IOException {
        PrintStream out = borrowedOut(terminal); // NOPMD - Terminal owns the stream.
        rejectAmbiguousConnectedBoardSelector(manifest, options);
        if (hasPotentialConnectedBoardCodexAccessTarget(manifest, options)) {
            rejectMixedCodexAccessUpdate(options);
        }
        if (options.nonInteractive()) {
            if (options.configureGithub()) {
                rejectConfigureGithubWorkflowSelector(options);
            } else {
                rejectNonInteractiveIgnoredWorkflowUpdate(options);
            }
        }
        if (options.githubMode().orElse(false)) {
            if (hasSelectedGithubBoardCodexAccessTarget(manifest, options)) {
                return ExistingSetupAction.UPDATE_CODEX_ACCESS;
            }
            if (options.configureGithub() || !options.hasExplicitBoardSetupRequest()) {
                List<ConnectedBoard> nonGithubBoards = nonGithubBoards(manifest);
                if (!nonGithubBoards.isEmpty()) {
                    rejectInvalidConfigureGithubUpgradeRequestBeforeAuth(options, nonGithubBoards);
                    return ExistingSetupAction.UPGRADE_GITHUB;
                }
                if (options.nonInteractive()) {
                    rejectNonInteractiveIgnoredConfigureGithubUpdate(options);
                }
                rejectConfigureGithubUpdateWithoutUpgrade(options);
                if (hasConnectedBoardCodexAccessTarget(manifest, options)) {
                    return ExistingSetupAction.UPDATE_CODEX_ACCESS;
                }
                return ExistingSetupAction.KEEP;
            }
            if (options.hasExplicitBoardSetupRequest()) {
                return ExistingSetupAction.CONNECT;
            }
            return ExistingSetupAction.KEEP;
        }
        if (hasConnectedBoardCodexAccessTarget(manifest, options)) {
            return ExistingSetupAction.UPDATE_CODEX_ACCESS;
        }
        if (options.hasExplicitBoardSetupRequest()) {
            return ExistingSetupAction.CONNECT;
        }
        if (options.nonInteractive()) {
            return ExistingSetupAction.KEEP;
        }
        out.println();
        out.println("Trello boards configured for Symphony:");
        for (int i = 0; i < manifest.boards().size(); i++) {
            ConnectedBoard board = manifest.boards().get(i);
            out.println(
                    "  " + (i + 1) + ". " + DisplayNames.quotedName(board.boardName()) + "     " + board.boardUrl());
        }
        out.println();
        out.println("What do you want to do?");
        out.println("  1. Keep connected Trello boards");
        out.println("  2. Connect another Trello board");
        out.println("  3. Disconnect a Trello board from Symphony");
        out.println();
        return switch (parseChoice(terminal.readLine("Choice [1]: "), 1, 3)) {
            case 2 -> ExistingSetupAction.CONNECT;
            case 3 -> ExistingSetupAction.DISCONNECT;
            default -> ExistingSetupAction.KEEP;
        };
    }

    private static boolean hasConnectedBoardCodexAccessTarget(ConnectedBoardManifest manifest, Options options) {
        return options.hasCodexAccessOnlyUpdateRequest()
                && hasPotentialConnectedBoardCodexAccessTarget(manifest, options);
    }

    private static boolean hasPotentialConnectedBoardCodexAccessTarget(
            ConnectedBoardManifest manifest, Options options) {
        if (!options.hasCodexAccessUpdateRequest() || options.boardName().isPresent()) {
            return false;
        }
        return options.existingBoardId()
                .map(selector -> manifest.findByBoard(selector).isPresent())
                .orElse(true);
    }

    private static void rejectAmbiguousConnectedBoardSelector(ConnectedBoardManifest manifest, Options options) {
        options.existingBoardId().ifPresent(selector -> rejectAmbiguousConnectedBoardSelector(manifest, selector));
    }

    private static void rejectInvalidUnconnectedBoardSelector(ConnectedBoardManifest manifest, Options options) {
        options.existingBoardId().ifPresent(selector -> rejectInvalidUnconnectedBoardSelector(manifest, selector));
    }

    private static void rejectInvalidUnconnectedBoardSelector(ConnectedBoardManifest manifest, String selectedBoard) {
        List<ConnectedBoard> connectedMatches = manifest.findAllByBoard(selectedBoard);
        if (connectedMatches.size() > 1) {
            throw ambiguousConnectedBoardSelector();
        }
        if (!connectedMatches.isEmpty()) {
            return;
        }
        try {
            TrelloBoardIds.parseImportBoardSelector(selectedBoard);
        } catch (TrelloBoardSetupException exception) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments",
                    "Invalid --board value. Use a Trello board URL, short link, board id, or a connected board name.",
                    exception);
        }
    }

    private static void rejectAmbiguousConnectedBoardSelector(ConnectedBoardManifest manifest, String selector) {
        if (manifest.findAllByBoard(selector).size() > 1) {
            throw ambiguousConnectedBoardSelector();
        }
    }

    private static boolean hasSelectedGithubBoardCodexAccessTarget(ConnectedBoardManifest manifest, Options options) {
        if (!options.hasCodexAccessOnlyUpdateRequest()
                || options.boardName().isPresent()
                || options.existingBoardId().isEmpty()) {
            return false;
        }
        return options.existingBoardId()
                .flatMap(manifest::findByBoard)
                .filter(ConnectedBoard::githubEnabled)
                .isPresent();
    }

    private void updateExistingCodexAccess(Options options, ConnectedBoardManifest manifest, Terminal terminal)
            throws IOException {
        PrintStream out = borrowedOut(terminal); // NOPMD - Terminal owns the stream.
        ConnectedBoard board = selectBoardForCodexAccessUpdate(options, manifest, terminal);
        ConnectedBoard updated = withRequestedCodexAccess(options, board, terminal);
        BoardHealth health = healthChecker.boardHealth(board);
        ensureManagedRestartPossible(options, board, health);
        applyCodexAccess(
                options.withCodexAccess(updated.additionalWritableRoots(), updated.dangerFullAccess()),
                board.workflowPath());
        connectedBoards(options).save(manifest.withBoard(updated));

        out.println();
        out.println("Codex access");
        out.println("  OK  Updated workflow: " + board.workflowPath());
        printWorkspaceAndSandboxSummary(
                options.withCodexAccess(updated.additionalWritableRoots(), updated.dangerFullAccess()), out);
        if (health.kind() == BoardHealthKind.SAME_WORKFLOW && !options.noStart()) {
            stopBoard(options, board.boardName(), board.workflowPath());
            startBoard(options, updated, out);
        } else if (health.kind() == BoardHealthKind.SAME_WORKFLOW) {
            out.println();
            out.println("Restart skipped. Restart Symphony for the updated workflow when you are ready:");
            out.println("  " + options.command() + " stop --workflow " + board.workflowPath());
            out.println("  " + options.command() + " start --env " + board.envPath() + " --workflow "
                    + board.workflowPath());
        } else {
            out.println();
            out.println("Start Symphony for the updated workflow:");
            out.println("  " + options.command() + " start --env " + board.envPath() + " --workflow "
                    + board.workflowPath());
        }
    }

    private ConnectedBoard withRequestedCodexAccess(Options options, ConnectedBoard board, Terminal terminal)
            throws IOException {
        PrintStream out = borrowedOut(terminal); // NOPMD - Terminal owns the stream.
        List<Path> requestedRoots = new ArrayList<>();
        for (Path root : options.additionalWritableRoots()) {
            if (WorkspaceAccessFlow.isBroadAccessPath(root) && !options.allowAllPaths() && !options.nonInteractive()) {
                out.println();
                out.println(
                        "Adding / grants broad recursive read/write access to all files and folders Symphony can normally access.");
                out.println(
                        "If you meant your home directory or one project, use ~ or that project directory instead.");
                if (!PromptSupport.yes(terminal, "Allow / anyway? [y/N] ")) {
                    continue;
                }
            } else {
                WorkspaceAccessFlow.rejectBroadAccessPath(root, options.allowAllPaths());
            }
            requestedRoots.add(root);
        }
        List<Path> updatedRoots = new ArrayList<>(board.additionalWritableRoots());
        for (Path root : requestedRoots) {
            if (updatedRoots.stream().noneMatch(existing -> PathsEqual.samePath(existing, root))) {
                updatedRoots.add(root);
            }
        }
        boolean requestedDangerFullAccess = options.dangerFullAccess();
        if (requestedDangerFullAccess && !board.dangerFullAccess()) {
            if (options.nonInteractive()) {
                CodexSandboxFlow.printWarning(terminal);
            } else {
                out.println();
                out.println("Codex execution");
                requestedDangerFullAccess = PromptSupport.yes(
                        terminal,
                        "Allow Codex to run without its command/filesystem sandbox for this workflow (danger-full-access)? [y/N] ");
                if (requestedDangerFullAccess) {
                    CodexSandboxFlow.printWarning(terminal);
                }
            }
        }
        boolean updatedDangerFullAccess = board.dangerFullAccess() || requestedDangerFullAccess;
        return board.withCodexAccess(List.copyOf(updatedRoots), updatedDangerFullAccess);
    }

    private static ConnectedBoard selectBoardForCodexAccessUpdate(
            Options options, ConnectedBoardManifest manifest, Terminal terminal) throws IOException {
        rejectMixedCodexAccessUpdate(options);
        Optional<String> selector = options.existingBoardId();
        if (selector.isEmpty()) {
            return selectBoardForCodexAccessUpdateWithoutSelector(options, manifest, terminal);
        }
        return selectedConnectedBoard(manifest, selector.get());
    }

    private static ConnectedBoard selectBoardForCodexAccessUpdateWithoutSelector(
            Options options, ConnectedBoardManifest manifest, Terminal terminal) throws IOException {
        PrintStream out = borrowedOut(terminal); // NOPMD - Terminal owns the stream.
        if (manifest.boards().size() == 1) {
            return manifest.boards().getFirst();
        }
        if (options.nonInteractive()) {
            throw new TrelloBoardSetupException(
                    "setup_board_selection_required",
                    "Multiple Trello boards are connected. Re-run with --board NAME to choose which connected board to update.");
        }
        out.println();
        out.println("Choose the Trello board to update:");
        for (int i = 0; i < manifest.boards().size(); i++) {
            out.println("  " + (i + 1) + ". "
                    + DisplayNames.quotedName(manifest.boards().get(i).boardName()));
        }
        return manifest.boards()
                .get(PromptSupport.requiredChoice(
                                terminal.readLine("Board: "),
                                manifest.boards().size(),
                                "setup_board_selection_required",
                                "Board selection is required.")
                        - 1);
    }

    private static ConnectedBoard selectedConnectedBoard(ConnectedBoardManifest manifest, String selector) {
        return selectedConnectedBoard(
                manifest,
                selector,
                "setup_board_selection_required",
                "No connected Trello board matches \"" + selector + "\".");
    }

    private static ConnectedBoard selectedConnectedBoard(
            ConnectedBoardManifest manifest, String selector, String notFoundCode, String notFoundMessage) {
        List<ConnectedBoard> matches = manifest.findAllByBoard(selector);
        if (matches.isEmpty()) {
            throw new TrelloBoardSetupException(notFoundCode, notFoundMessage);
        }
        if (matches.size() > 1) {
            throw ambiguousConnectedBoardSelector();
        }
        return matches.getFirst();
    }

    private static TrelloBoardSetupException ambiguousConnectedBoardSelector() {
        return new TrelloBoardSetupException(
                "setup_worker_board_ambiguous",
                "Multiple connected boards match --board. Re-run with a board id or short link.");
    }

    private static void rejectMixedCodexAccessUpdate(Options options) {
        if (options.hasCodexAccessUpdateRequest() && !options.hasCodexAccessOnlyUpdateRequest()) {
            throw new TrelloBoardSetupException(
                    "setup_mixed_codex_access_update",
                    "--add-path and --danger-full-access update Codex access only. Rerun without other workflow setup options such as --server-port, --active, --terminal, --in-progress, --blocked, --workspace-root, --workflow, or --max-agents.");
        }
    }

    private static void rejectNonInteractiveIgnoredWorkflowUpdate(Options options) {
        if (options.hasNonAccessWorkflowUpdateRequest() && !options.hasExplicitBoardSetupRequest()) {
            throw new TrelloBoardSetupException(
                    "setup_board_selection_required",
                    "Existing connected Trello boards cannot be updated by an implicit keep rerun with workflow setup options. Re-run with --board or --board-name so setup can apply options such as --server-port, --active, --terminal, --workspace-root, --workflow, or --max-agents.");
        }
    }

    private static void rejectNonInteractiveIgnoredConfigureGithubUpdate(Options options) {
        if (options.hasNonCodexModelWorkflowUpdateRequest()) {
            throw new TrelloBoardSetupException(
                    "setup_board_selection_required",
                    "setup-local configure-github can apply --max-agents, --codex-model, and --codex-reasoning-effort to the upgraded workflow, but other workflow setup options such as --server-port, --active, --terminal, --workspace-root, or --workflow are not applied on this path.");
        }
    }

    private static void rejectConfigureGithubWorkflowSelector(Options options) {
        if (options.workflowPathExplicit()) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments",
                    "setup-local configure-github selects connected Trello boards with --board; --workflow is not supported.");
        }
    }

    private static void rejectConfigureGithubUpdateWithoutUpgrade(Options options) {
        if (options.configureGithub() && options.hasConfigureGithubWorkflowUpdateRequest()) {
            throw new TrelloBoardSetupException(
                    "setup_github_upgrade_not_found",
                    "setup-local configure-github can apply --max-agents, --codex-model, and --codex-reasoning-effort only while upgrading a non-GitHub connected Trello board.");
        }
    }

    private static void rejectInvalidConfigureGithubUpgradeRequestBeforeAuth(
            Options options, List<ConnectedBoard> nonGithubBoards) {
        if (!options.nonInteractive()) {
            return;
        }
        options.existingBoardId().ifPresent(selector -> nonGithubBoard(nonGithubBoards, selector));
        rejectNonInteractiveIgnoredConfigureGithubUpdate(options);
    }

    private void upgradeExistingBoardToGithub(Options options, ConnectedBoardManifest manifest, Terminal terminal)
            throws IOException {
        PrintStream out = borrowedOut(terminal); // NOPMD - Terminal owns the stream.
        ConnectedBoard board = selectNonGithubBoardForUpgrade(options, manifest, terminal);
        if (!options.nonInteractive()
                && !PromptSupport.yesDefaultTrue(
                        terminal,
                        "Configure GitHub PR handling for " + DisplayNames.quotedName(board.boardName())
                                + "? [Y/n] ")) {
            out.println("GitHub upgrade cancelled.");
            return;
        }
        if (options.hasExplicitCodexModelRequest()) {
            options = configureCodexModel(options, board.workflowPath(), terminal);
        }

        CredentialSelection credentialSelection = credentials(options, board.envPath(), terminal);
        TrelloCredentials credentials = credentialSelection.credentials();
        TrelloBoardSetup.MemberInfo memberInfo =
                boardSetup.getMemberInfo(new TrelloBoardSetup.MemberInfoRequest(options.endpoint(), credentials));
        out.println();
        out.println("Validating Trello...");
        out.println("  OK  Connected to Trello as \"" + memberInfo.displayName() + "\"");
        if (credentialSelection.persist()) {
            writeEnv(credentialSelection, board.envPath(), terminal);
        }

        resolveGitHubIntegration(options, prerequisites(), terminal);
        BoardHealth previousHealth = healthChecker.boardHealth(board);
        ensureManagedRestartPossible(options, board, previousHealth);
        TrelloBoardSetup selectedBoardSetup = boardSetupWithCodexModel(options);
        List<String> openLists = selectedBoardSetup.getOpenBoardListNames(
                new TrelloBoardSetup.BoardInfoRequest(options.endpoint(), credentials, board.boardId()));
        WorkflowListConfiguration existingLists =
                workflowConfig.listConfiguration(board.workflowPath()).onlyOpenLists(openLists);
        if (openLists.stream().noneMatch(name -> name.equalsIgnoreCase(TrelloBoardSetup.RECOMMENDED_MERGING_STATE))) {
            out.println();
            out.println("GitHub mode needs one more Trello list:");
            out.println("  " + TrelloBoardSetup.RECOMMENDED_MERGING_STATE);
            if (!options.nonInteractive()
                    && !PromptSupport.yesDefaultTrue(terminal, "Create the missing GitHub list now? [Y/n] ")) {
                throw new TrelloBoardSetupException(
                        "setup_github_upgrade_list_declined",
                        "GitHub workflow upgrade needs a Merging list for merge approval.");
            }
        }

        MaxAgentsSelection maxAgents = configureGithubMaxAgents(options, board.workflowPath());
        TrelloBoardSetup.RepositoryDefaults repositoryDefaults =
                workflowConfig.repositoryDefaults(board.workflowPath());
        TrelloBoardSetup.ImportBoardResult result =
                selectedBoardSetup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                        options.endpoint(),
                        credentials,
                        board.boardId(),
                        existingLists.activeStates(),
                        existingLists.terminalStates(),
                        existingLists.inProgressState().orElse(null),
                        false,
                        existingLists.blockedState().orElse(null),
                        board.workflowPath(),
                        board.workspaceRoot(),
                        board.serverPort(),
                        maxAgents.value(),
                        true,
                        GitHubIntegration.ENABLED,
                        true,
                        board.envPath(),
                        maxAgents.preservedFromWorkflow(),
                        repositoryDefaults));
        ConnectedBoard access = withRequestedCodexAccess(options, board, terminal);
        applyCodexAccess(
                options.withCodexAccess(access.additionalWritableRoots(), access.dangerFullAccess()),
                board.workflowPath());
        ConnectedBoard upgraded = new ConnectedBoard(
                board.boardId(),
                board.boardKey(),
                board.boardName(),
                board.boardUrl(),
                board.workflowPath(),
                board.envPath(),
                board.workspaceRoot(),
                result.serverPort(),
                true,
                access.additionalWritableRoots(),
                access.dangerFullAccess());
        connectedBoards(options).save(manifest.withBoard(upgraded));
        out.println();
        out.println("  OK  GitHub workflow enabled for " + DisplayNames.quotedName(board.boardName()));
        if (previousHealth.kind() == BoardHealthKind.SAME_WORKFLOW && !options.noStart()) {
            stopBoard(options, board.boardName(), board.workflowPath());
            startBoard(options, upgraded, out);
        } else if (previousHealth.kind() == BoardHealthKind.SAME_WORKFLOW) {
            out.println();
            out.println("Restart skipped. Restart Symphony for the updated workflow when you are ready:");
            out.println("  " + options.command() + " stop --workflow " + board.workflowPath());
            out.println("  " + options.command() + " start --env " + board.envPath() + " --workflow "
                    + board.workflowPath());
        } else {
            out.println("          Restart: " + options.command() + " start --env " + board.envPath() + " --workflow "
                    + board.workflowPath());
        }
    }

    private MaxAgentsSelection configureGithubMaxAgents(Options options, Path workflowPath) {
        if (options.maxAgentsExplicit()) {
            return new MaxAgentsSelection(options.maxAgents(), false);
        }
        return workflowConfig
                .maxAgents(workflowPath)
                .map(maxAgents -> new MaxAgentsSelection(maxAgents, true))
                .orElseGet(() -> new MaxAgentsSelection(options.maxAgents(), false));
    }

    private static ConnectedBoard selectNonGithubBoardForUpgrade(
            Options options, ConnectedBoardManifest manifest, Terminal terminal) throws IOException {
        List<ConnectedBoard> candidates = nonGithubBoards(manifest);
        if (candidates.isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_github_upgrade_not_found", "No non-GitHub connected board is available to upgrade.");
        }
        Optional<String> requested = options.existingBoardId();
        if (requested.isEmpty()) {
            return selectNonGithubBoardForUpgradeWithoutSelector(options, candidates, terminal);
        }
        return nonGithubBoard(candidates, requested.get());
    }

    private static List<ConnectedBoard> nonGithubBoards(ConnectedBoardManifest manifest) {
        return manifest.boards().stream()
                .filter(board -> !board.githubEnabled())
                .toList();
    }

    private static ConnectedBoard selectNonGithubBoardForUpgradeWithoutSelector(
            Options options, List<ConnectedBoard> candidates, Terminal terminal) throws IOException {
        PrintStream out = borrowedOut(terminal); // NOPMD - Terminal owns the stream.
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        if (options.nonInteractive()) {
            throw new TrelloBoardSetupException(
                    "setup_github_upgrade_board_required",
                    "Multiple non-GitHub boards are connected. Re-run with --board NAME.");
        }
        out.println();
        out.println("Choose the Trello board to configure for GitHub:");
        for (int i = 0; i < candidates.size(); i++) {
            out.println("  " + (i + 1) + ". "
                    + DisplayNames.quotedName(candidates.get(i).boardName()));
        }
        return candidates.get(PromptSupport.requiredChoice(
                        terminal.readLine("Board: "),
                        candidates.size(),
                        "setup_github_upgrade_board_required",
                        "Board selection is required.")
                - 1);
    }

    private static ConnectedBoard nonGithubBoard(List<ConnectedBoard> candidates, String requested) {
        List<ConnectedBoard> exactMatches = candidates.stream()
                .filter(board -> board.boardName().equalsIgnoreCase(requested)
                        || board.boardId().equalsIgnoreCase(requested)
                        || board.boardKey().equalsIgnoreCase(requested))
                .toList();
        if (!exactMatches.isEmpty()) {
            return singleNonGithubBoardMatch(exactMatches, requested);
        }
        String requestedBoardId = TrelloBoardIds.parse(requested);
        List<ConnectedBoard> matches = candidates.stream()
                .filter(board -> board.boardId().equalsIgnoreCase(requestedBoardId)
                        || board.boardKey().equalsIgnoreCase(requestedBoardId))
                .toList();
        return singleNonGithubBoardMatch(matches, requested);
    }

    private static ConnectedBoard singleNonGithubBoardMatch(List<ConnectedBoard> matches, String requested) {
        if (matches.isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_github_upgrade_not_found", "No connected non-GitHub board matches \"" + requested + "\".");
        }
        if (matches.size() > 1) {
            throw ambiguousConnectedBoardSelector();
        }
        return matches.getFirst();
    }

    private void disconnectBoard(Options options, ConnectedBoardManifest manifest, Terminal terminal)
            throws IOException {
        PrintStream out = borrowedOut(terminal); // NOPMD - Terminal owns the stream.
        out.println();
        out.println("Disconnecting a Trello board from Symphony only removes it from Symphony's local config.");
        out.println("Note: this will NOT delete or archive the Trello board.");
        out.println();
        String answer = terminal.readLine("Disconnect which Trello board from Symphony? [1-"
                + manifest.boards().size() + "]: ");
        if (blank(answer)) {
            out.println("Disconnect cancelled.");
            return;
        }
        int selected = parseChoice(answer, 1, manifest.boards().size());
        ConnectedBoard removed = manifest.boards().get(selected - 1);
        stopBoard(options, removed.boardName(), removed.workflowPath());
        connectedBoards(options).save(manifest.withoutBoard(removed.boardId()));
        out.println("  OK  Symphony will stop managing " + DisplayNames.quotedName(removed.boardName()));
        out.println("  Trello board unchanged: " + removed.boardUrl());
    }

    private void startExistingBoards(Options options, ConnectedBoardManifest manifest, PrintStream out) {
        for (ConnectedBoard board : manifest.boards()) {
            startBoard(options, board, out);
        }
    }

    private void startBoard(Options options, ConnectedBoard board, PrintStream out) {
        if (options.noStart()) {
            out.println();
            out.println("Start skipped. Start Symphony before moving Trello cards:");
            out.println("  " + options.command() + " start --env " + board.envPath() + " --workflow "
                    + board.workflowPath());
            return;
        }
        out.println();
        out.println("Starting Symphony...");
        try {
            workerManager.start(localWorkerPaths(options), board, board.envPath(), out);
        } catch (IOException e) {
            throw new TrelloBoardSetupException(
                    "setup_start_failed",
                    "Could not start Symphony for " + DisplayNames.quotedName(board.boardName()) + ": "
                            + e.getMessage(),
                    e);
        }
        out.println("  OK  Symphony is connected to " + DisplayNames.quotedName(board.boardName()));
    }

    private void stopBoard(Options options, String boardName, Path workflowPath) {
        ConnectedBoard board = connectedBoardsUnchecked(options)
                .findByWorkflow(workflowPath)
                .orElseGet(() -> new ConnectedBoard(
                        PathNames.fileName(workflowPath),
                        PathNames.fileName(workflowPath),
                        boardName,
                        "",
                        workflowPath,
                        options.envPath(),
                        options.workspaceRoot(),
                        TrelloBoardSetup.DEFAULT_SERVER_PORT,
                        false,
                        List.of(),
                        false));
        try {
            try (PrintStream out = new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8)) {
                workerManager.stop(localWorkerPaths(options), board, out);
            }
        } catch (IOException e) {
            throw new TrelloBoardSetupException(
                    "setup_stop_failed",
                    "Could not stop Symphony for " + DisplayNames.quotedName(boardName) + ": " + e.getMessage(),
                    e);
        }
    }

    private void ensureManagedRestartPossible(Options options, ConnectedBoard board, BoardHealth health) {
        if (health.kind() != BoardHealthKind.SAME_WORKFLOW || options.noStart()) {
            return;
        }
        try {
            if (workerManager.canStopManagedWorker(localWorkerPaths(options), board)) {
                return;
            }
        } catch (IOException e) {
            throw new TrelloBoardSetupException(
                    "setup_worker_state_unreadable",
                    "Could not inspect managed worker state for " + DisplayNames.quotedName(board.boardName()) + ": "
                            + e.getMessage(),
                    e);
        }
        throw new TrelloBoardSetupException(
                "setup_worker_untracked",
                "Symphony is already running for " + DisplayNames.quotedName(board.boardName()) + " at "
                        + LocalHealthChecker.localServerUrl(health.port())
                        + ", but this checkout has no managed pid for it. Stop that process manually, then rerun the setup command so Symphony can apply the workflow change and restart it.");
    }

    private static HttpClient localStatusHttpClient() {
        // LocalSetup owns LocalHealthChecker for the process lifetime; closing this client after a run would break
        // reuse.
        return HttpClient.newBuilder().connectTimeout(LOCAL_STATUS_TIMEOUT).build(); // NOPMD - owned by LocalSetup.
    }

    private static PrintStream borrowedOut(Terminal terminal) {
        // Terminal owns the stream. Setup code must write to it but must not close it.
        return terminal.out();
    }

    private static PrintStream borrowedErr(Terminal terminal) {
        // Terminal owns the stream. Setup code must write to it but must not close it.
        return terminal.err();
    }

    private void stopReplacedBoards(Options options, List<ConnectedBoard> replacedBoards) {
        if (replacedBoards.isEmpty()) {
            return;
        }
        List<Path> stoppedWorkflowPaths = new ArrayList<>();
        for (ConnectedBoard board : replacedBoards) {
            if (stoppedWorkflowPaths.stream().anyMatch(stopped -> PathsEqual.samePath(stopped, board.workflowPath()))) {
                continue;
            }
            stopBoard(options, board.boardName(), board.workflowPath());
            stoppedWorkflowPaths.add(board.workflowPath());
        }
    }

    private static void printWorkspaceAndSandboxSummary(Options options, PrintStream out) {
        out.println();
        out.println("Workspace access");
        out.println("This controls which files/folders sandboxed card runs may use.");
        out.println("Codex execution sandboxing is configured separately.");
        out.println("Cards run in per-card workspaces under:");
        out.println("  " + options.workspaceRoot());
        out.println("  OK  Additional read/write paths allowed: "
                + options.additionalWritableRoots().size());
        out.println();
        out.println("Codex execution");
        if (options.dangerFullAccess()) {
            out.println("  WARN  Codex sandbox disabled: danger-full-access enabled for this workflow");
        } else {
            out.println("  OK  Codex sandbox: workspace-limited");
        }
    }

    private void applyCodexAccess(Options options, Path workflowPath) throws IOException {
        workflowConfig.applyCodexAccess(workflowPath, options.additionalWritableRoots(), options.dangerFullAccess());
    }

    private static void printHeader(PrintStream out, Options options) {
        out.println(options.check() ? "Symphony setup check" : "Symphony for Trello setup");
    }

    private static void printPrerequisites(PrintStream out, Prerequisites prerequisites, Options options) {
        out.println();
        out.println("Checking prerequisites...");
        out.println(statusLine("Git", prerequisites.git().available()));
        out.println(statusLine("Java 25+ JDK", prerequisites.java().available()));
        out.println(statusLine("Codex CLI", prerequisites.codex().available()));
        out.println(
                statusLine("Codex CLI authenticated", prerequisites.codexAuth().available()));
        if (prerequisites.githubAuth().available()) {
            out.println(statusLine("GitHub CLI authenticated", true));
        } else if (options.githubMode().orElse(false)) {
            out.println(statusLine("GitHub CLI authenticated", false));
        } else {
            out.println("  OPTIONAL GitHub CLI authenticated");
        }
    }

    private static String statusLine(String name, boolean ok) {
        return "  " + (ok ? "OK      " : "NEEDED  ") + name;
    }

    private static void printDryRun(PrintStream out, Options options, Prerequisites prerequisites) {
        out.println();
        out.println("Dry run");
        if (!prerequisites.git().available()) {
            out.println("  WOULD require Git installation");
        }
        if (!prerequisites.java().available()) {
            out.println("  WOULD require Java 25+ JDK installation");
        }
        if (!prerequisites.codex().available()) {
            out.println("  WOULD require Codex CLI installation");
        }
        out.println("  WOULD configure Trello credentials: " + options.envPath());
        if (options.workflowPathExplicit()) {
            out.println("  WOULD write workflow: " + plannedWorkflowPath(options));
        } else {
            out.println("  WOULD write workflow under: " + options.configDir());
        }
        out.println("  WOULD update connected-board manifest: " + options.manifestPath());
        if (!options.additionalWritableRoots().isEmpty()) {
            out.println("  WOULD allow Codex read/write access to:");
            options.additionalWritableRoots().forEach(root -> out.println("    " + root));
        }
        if (options.dangerFullAccess()) {
            out.println("  WOULD disable Codex command/filesystem sandbox with danger-full-access");
        }
        if (options.noStart()) {
            out.println("  WOULD NOT start Symphony after setup because --no-start is set");
        } else {
            out.println("  WOULD start Symphony after setup unless --no-start is used");
        }
    }

    private static Path plannedWorkflowPath(Options options) {
        if (options.workflowPath().isAbsolute()) {
            return options.workflowPath().normalize();
        }
        return options.configDir().resolve(options.workflowPath()).normalize();
    }

    private static void printFinalHandoff(PrintStream out, ConnectedBoardManifest manifest, String cliCommand) {
        if (manifest.boards().isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_manifest_unavailable",
                    "No connected Trello boards are available for the installer completion handoff.");
        }
        out.println();
        if (manifest.boards().size() == 1) {
            ConnectedBoard board = manifest.boards().getFirst();
            out.println("You're good to go - your Trello board is now a queue for Codex work.");
            out.println("Connected board: " + DisplayNames.quotedName(board.boardName()));
            out.println("Workflow: " + board.workflowPath().toAbsolutePath().normalize());
            out.println();
            out.println("Create a Trello card with a clear task and move it to this workflow's configured queue list.");
        } else {
            out.println("You're good to go - your Trello boards are now queues for Codex work.");
            out.println("Connected boards and workflows:");
            manifest.boards().forEach(board -> {
                out.println("  " + DisplayNames.quotedName(board.boardName()));
                out.println(
                        "    Workflow: " + board.workflowPath().toAbsolutePath().normalize());
            });
            out.println();
            out.println(
                    "Create a Trello card with a clear task and move it to the matching workflow's configured queue list.");
        }
        out.println();
        out.println("Useful commands:");
        String shell = SetupSystemProperties.get(ShellCommandRenderer.SHELL_PROPERTY);
        String command = ShellCommandRenderer.executable(cliCommand, shell);
        out.println("  " + command + " status");
        manifest.boards()
                .forEach(board -> out.println("  " + command + " logs --workflow "
                        + ShellCommandRenderer.argument(
                                board.workflowPath()
                                        .toAbsolutePath()
                                        .normalize()
                                        .toString(),
                                shell)));
    }

    private static String errorCode(Exception e) {
        return e instanceof TrelloBoardSetupException setupException ? setupException.code() : "setup_local_failed";
    }

    private static String quoted(List<String> values) {
        return DisplayNames.quotedList(values);
    }

    private static int parseChoice(String answer, int defaultChoice, int maxChoice) {
        return PromptSupport.choice(answer, defaultChoice, maxChoice);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean equalsIgnoreCase(String actual, String expected) {
        return actual != null && expected != null && actual.equalsIgnoreCase(expected);
    }

    record Options(
            boolean check,
            boolean dryRun,
            boolean repairPort,
            boolean nonInteractive,
            boolean force,
            boolean forceNewSetup,
            boolean configureGithub,
            Optional<Boolean> githubMode,
            Optional<String> apiKey,
            Optional<String> apiToken,
            Optional<String> boardName,
            Optional<String> existingBoardId,
            Optional<String> workspaceId,
            Optional<String> repositoryUrl,
            List<String> activeStates,
            List<String> terminalStates,
            String inProgressState,
            boolean detectInProgressState,
            String blockedState,
            Path workflowPath,
            boolean workflowPathExplicit,
            Path workspaceRoot,
            boolean workspaceRootExplicit,
            Path configDir,
            Path manifestPath,
            Optional<Integer> serverPort,
            int maxAgents,
            boolean maxAgentsExplicit,
            Optional<String> codexModel,
            Optional<String> codexReasoningEffort,
            Optional<CodexModelSelectionDefaults> codexModelCatalog,
            Optional<TrelloBoardSetup.CodexModelDefaults> codexModelDefaults,
            Path envPath,
            List<Path> additionalWritableRoots,
            boolean allowAllPaths,
            boolean dangerFullAccess,
            boolean noStart,
            String command,
            URI endpoint,
            Path callerDirectory) {
        static Options from(LocalSetupRequest request, Map<String, String> environment) {
            boolean check = request.action() == LocalSetupRequest.Action.CHECK;
            boolean repairPort = request.action() == LocalSetupRequest.Action.REPAIR_PORT;
            boolean configureGithub = request.action() == LocalSetupRequest.Action.CONFIGURE_GITHUB;
            Path configDir = request.configDir().orElseGet(() -> defaultConfigDir(environment));
            Path workflow = request.workflowPath().orElse(TrelloBoardSetup.DEFAULT_WORKFLOW_PATH);
            boolean workflowPathExplicit = request.workflowPath().isPresent();
            Path workspaceRoot = request.workspaceRoot().orElseGet(() -> defaultWorkspaceRoot(environment));
            Path manifest = request.manifestPath().orElse(Path.of(ConnectedBoardManifest.FILE_NAME));
            boolean manifestPathExplicit = request.manifestPath().isPresent();
            Path envPath = request.envPath().orElse(DEFAULT_ENV_PATH);
            List<Path> additionalWritableRoots = request.additionalWritableRoots().stream()
                    .map(path -> WorkspaceAccessFlow.resolveAccessPath(path, callerDirectory(environment)))
                    .toList();
            request.serverPort().ifPresent(LocalPort::validateCliServerPort);
            Boolean githubMode = request.githubMode().orElse(null);
            if (configureGithub) {
                githubMode = true;
            }
            configDir = configDir.toAbsolutePath().normalize();
            envPath = resolveUserDataPath(envPath, configDir);
            manifest = resolveUserDataPath(manifest, configDir);
            validateResolvedSetupPaths(configDir, manifest, manifestPathExplicit, request.action());
            additionalWritableRoots =
                    additionalWritableRoots.stream().map(Path::normalize).toList();
            if (request.nonInteractive()) {
                boolean broadPathAllowed = request.allowAllPaths();
                additionalWritableRoots.forEach(
                        path -> WorkspaceAccessFlow.rejectBroadAccessPath(path, broadPathAllowed));
            }
            if (workflowPathExplicit) {
                workflow = resolveUserDataPath(workflow, configDir);
            }
            boolean workspaceRootExplicit = request.workspaceRoot().isPresent();
            TrelloCredentialStore.validateEnvPath(envPath);
            String command = environment.getOrDefault(COMMAND_ENV, DEFAULT_COMMAND);
            return new Options(
                    check,
                    request.dryRun(),
                    repairPort,
                    request.nonInteractive(),
                    request.force(),
                    request.forceNewSetup(),
                    configureGithub,
                    Optional.ofNullable(githubMode),
                    request.apiKey(),
                    request.apiToken(),
                    request.boardName(),
                    request.existingBoardId(),
                    request.workspaceId(),
                    request.repositoryUrl(),
                    List.copyOf(request.activeStates()),
                    List.copyOf(request.terminalStates()),
                    request.inProgressState(),
                    request.detectInProgressState(),
                    request.blockedState(),
                    workflow,
                    workflowPathExplicit,
                    workspaceRoot,
                    workspaceRootExplicit,
                    configDir,
                    manifest,
                    request.serverPort(),
                    request.maxAgents(),
                    request.maxAgentsExplicit(),
                    request.codexModel(),
                    request.codexReasoningEffort(),
                    Optional.empty(),
                    Optional.empty(),
                    envPath,
                    List.copyOf(additionalWritableRoots),
                    request.allowAllPaths(),
                    request.dangerFullAccess(),
                    request.noStart(),
                    command,
                    request.endpoint(),
                    callerDirectory(environment));
        }

        private static void validateResolvedSetupPaths(
                Path configDir, Path manifest, boolean manifestPathExplicit, LocalSetupRequest.Action action) {
            CliInputValidation.rejectExistingNonDirectoryPath("--config-dir", configDir);
            if (!manifestPathExplicit) {
                return;
            }
            // repair-port operates on the existing manifest like the lifecycle commands, so
            // corrupt manifest content is an expected setup_manifest_unavailable error from the
            // strict load, not an invalid --manifest argument.
            if (action == LocalSetupRequest.Action.CHECK || action == LocalSetupRequest.Action.REPAIR_PORT) {
                ConnectedBoardRepository.validateManifestPathForCheck(manifest);
            } else {
                ConnectedBoardRepository.validateManifestPathForSetup(manifest);
            }
        }

        Options withCodexAccess(List<Path> additionalWritableRoots, boolean dangerFullAccess) {
            return new Options(
                    check,
                    dryRun,
                    repairPort,
                    nonInteractive,
                    force,
                    forceNewSetup,
                    configureGithub,
                    githubMode,
                    apiKey,
                    apiToken,
                    boardName,
                    existingBoardId,
                    workspaceId,
                    repositoryUrl,
                    activeStates,
                    terminalStates,
                    inProgressState,
                    detectInProgressState,
                    blockedState,
                    workflowPath,
                    workflowPathExplicit,
                    workspaceRoot,
                    workspaceRootExplicit,
                    configDir,
                    manifestPath,
                    serverPort,
                    maxAgents,
                    maxAgentsExplicit,
                    codexModel,
                    codexReasoningEffort,
                    codexModelCatalog,
                    codexModelDefaults,
                    envPath,
                    additionalWritableRoots,
                    allowAllPaths,
                    dangerFullAccess,
                    noStart,
                    command,
                    endpoint,
                    callerDirectory);
        }

        Options withRepositoryUrl(Optional<String> repositoryUrl) {
            return new Options(
                    check,
                    dryRun,
                    repairPort,
                    nonInteractive,
                    force,
                    forceNewSetup,
                    configureGithub,
                    githubMode,
                    apiKey,
                    apiToken,
                    boardName,
                    existingBoardId,
                    workspaceId,
                    repositoryUrl,
                    activeStates,
                    terminalStates,
                    inProgressState,
                    detectInProgressState,
                    blockedState,
                    workflowPath,
                    workflowPathExplicit,
                    workspaceRoot,
                    workspaceRootExplicit,
                    configDir,
                    manifestPath,
                    serverPort,
                    maxAgents,
                    maxAgentsExplicit,
                    codexModel,
                    codexReasoningEffort,
                    codexModelCatalog,
                    codexModelDefaults,
                    envPath,
                    additionalWritableRoots,
                    allowAllPaths,
                    dangerFullAccess,
                    noStart,
                    command,
                    endpoint,
                    callerDirectory);
        }

        private static Path resolveUserDataPath(Path path, Path configDir) {
            return path.isAbsolute()
                    ? path.normalize()
                    : configDir.resolve(path).normalize();
        }

        private static Path defaultConfigDir(Map<String, String> environment) {
            String configured = environment.get(CONFIG_DIR_ENV);
            if (!blank(configured)) {
                return Path.of(configured);
            }
            return Path.of(".").toAbsolutePath().normalize();
        }

        private static Path callerDirectory(Map<String, String> environment) {
            return configuredPath(environment, CALLER_DIR_ENV)
                    .map(path -> path.toAbsolutePath().normalize())
                    .orElseGet(() -> Path.of(".").toAbsolutePath().normalize());
        }

        private static Path defaultWorkspaceRoot(Map<String, String> environment) {
            return configuredPath(environment, "SYMPHONY_TRELLO_WORKSPACE_ROOT")
                    .orElse(TrelloBoardSetup.DEFAULT_WORKSPACE_ROOT);
        }

        private static Optional<Path> configuredPath(Map<String, String> environment, String name) {
            return Optional.ofNullable(environment.get(name))
                    .filter(value -> !blank(value))
                    .map(Path::of);
        }

        boolean hasExplicitBoardSetupRequest() {
            return boardName.isPresent() || existingBoardId.isPresent();
        }

        boolean hasCodexAccessUpdateRequest() {
            return !additionalWritableRoots.isEmpty() || dangerFullAccess;
        }

        boolean hasCodexAccessOnlyUpdateRequest() {
            return hasCodexAccessUpdateRequest() && !hasNonAccessWorkflowUpdateRequest();
        }

        private boolean hasNonAccessWorkflowUpdateRequest() {
            return hasBoardWorkflowUpdateRequest()
                    || maxAgentsExplicit
                    || codexModel.isPresent()
                    || codexReasoningEffort.isPresent();
        }

        private boolean hasNonCodexModelWorkflowUpdateRequest() {
            return hasBoardWorkflowUpdateRequest();
        }

        private boolean hasBoardWorkflowUpdateRequest() {
            return workspaceId.isPresent()
                    || repositoryUrl.isPresent()
                    || !activeStates.isEmpty()
                    || !terminalStates.isEmpty()
                    || inProgressState != null
                    || !detectInProgressState
                    || !blank(blockedState)
                    || workflowPathExplicit
                    || workspaceRootExplicit
                    || serverPort.isPresent();
        }

        private boolean hasConfigureGithubWorkflowUpdateRequest() {
            return maxAgentsExplicit || codexModel.isPresent() || codexReasoningEffort.isPresent();
        }

        Optional<String> repairBoardName() {
            return existingBoardId;
        }

        boolean hasExplicitCodexModelRequest() {
            return codexModel.isPresent() || codexReasoningEffort.isPresent();
        }

        Options withCodexModelCatalog(CodexModelSelectionDefaults catalog) {
            return new Options(
                    check,
                    dryRun,
                    repairPort,
                    nonInteractive,
                    force,
                    forceNewSetup,
                    configureGithub,
                    githubMode,
                    apiKey,
                    apiToken,
                    boardName,
                    existingBoardId,
                    workspaceId,
                    repositoryUrl,
                    activeStates,
                    terminalStates,
                    inProgressState,
                    detectInProgressState,
                    blockedState,
                    workflowPath,
                    workflowPathExplicit,
                    workspaceRoot,
                    workspaceRootExplicit,
                    configDir,
                    manifestPath,
                    serverPort,
                    maxAgents,
                    maxAgentsExplicit,
                    codexModel,
                    codexReasoningEffort,
                    Optional.of(catalog),
                    codexModelDefaults,
                    envPath,
                    additionalWritableRoots,
                    allowAllPaths,
                    dangerFullAccess,
                    noStart,
                    command,
                    endpoint,
                    callerDirectory);
        }

        Options withCodexModelSelection(CodexModelSelectionFlow.Selection selected) {
            return new Options(
                    check,
                    dryRun,
                    repairPort,
                    nonInteractive,
                    force,
                    forceNewSetup,
                    configureGithub,
                    githubMode,
                    apiKey,
                    apiToken,
                    boardName,
                    existingBoardId,
                    workspaceId,
                    repositoryUrl,
                    activeStates,
                    terminalStates,
                    inProgressState,
                    detectInProgressState,
                    blockedState,
                    workflowPath,
                    workflowPathExplicit,
                    workspaceRoot,
                    workspaceRootExplicit,
                    configDir,
                    manifestPath,
                    serverPort,
                    maxAgents,
                    maxAgentsExplicit,
                    codexModel.or(selected::modelOverride),
                    codexReasoningEffort.or(selected::reasoningEffortOverride),
                    codexModelCatalog,
                    Optional.of(selected.defaults()),
                    envPath,
                    additionalWritableRoots,
                    allowAllPaths,
                    dangerFullAccess,
                    noStart,
                    command,
                    endpoint,
                    callerDirectory);
        }
    }

    record SetupResult(
            String boardId,
            String boardKey,
            String boardName,
            String boardUrl,
            List<String> lists,
            Path workflowPath,
            int serverPort,
            GitHubIntegration githubIntegration) {
        static SetupResult from(TrelloBoardSetup.NewBoardResult result, GitHubIntegration githubIntegration) {
            return new SetupResult(
                    result.boardId(),
                    result.boardKey(),
                    result.boardName(),
                    result.boardUrl(),
                    result.lists(),
                    result.workflowPath(),
                    result.serverPort(),
                    githubIntegration);
        }

        static SetupResult from(TrelloBoardSetup.ImportBoardResult result, GitHubIntegration githubIntegration) {
            return new SetupResult(
                    result.boardId(),
                    result.boardKey(),
                    result.boardName(),
                    result.boardUrl(),
                    result.openLists(),
                    result.workflowPath(),
                    result.serverPort(),
                    githubIntegration);
        }
    }

    private record ConnectedBoardLocalValidation(List<String> warnings, boolean envUsable) {
        boolean ok() {
            return warnings.isEmpty();
        }
    }

    private record MaxAgentsSelection(int value, boolean preservedFromWorkflow) {}

    private enum ExistingSetupAction {
        KEEP,
        CONNECT,
        DISCONNECT,
        UPGRADE_GITHUB,
        UPDATE_CODEX_ACCESS
    }
}
