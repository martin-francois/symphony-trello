package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.TrelloEnvironment;
import ch.fmartin.symphony.trello.boardsession.BoardListRoles;
import ch.fmartin.symphony.trello.boardsession.BoardSessionContext;
import ch.fmartin.symphony.trello.boardsession.BoardSessionInstructions;
import ch.fmartin.symphony.trello.boardsession.BoardSessionMcpServer;
import ch.fmartin.symphony.trello.boardsession.BoardSessionTools;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.EnvironmentReferences;
import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.config.StateNames;
import ch.fmartin.symphony.trello.process.ProcessEnvironment;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.tracker.TrelloException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Stream;

/// Launches the normal interactive Codex TUI for one connected Trello board.
///
/// The selected workflow's Trello credentials stay in this process: Codex reaches Trello only
/// through the loopback [BoardSessionMcpServer], which lives exactly as long as the Codex child.
/// Codex keeps the user's own login, sandbox, approval policy, and working directory. See
/// docs/adr/0090-board-aware-interactive-codex-session.md.
final class CodexBoardSession {
    /// Environment variable that carries the per-session MCP bearer token to Codex.
    static final String SESSION_TOKEN_ENVIRONMENT = "SYMPHONY_TRELLO_BOARD_SESSION_TOKEN";
    /// Shell convention for a command the user stopped.
    static final int USER_CANCELLED_EXIT_CODE = 130;
    static final Duration CODEX_STOP_GRACE = Duration.ofSeconds(5);

    private final Map<String, String> environment;
    private final WorkflowConfigEditor workflowConfig;
    private final LocalWorkerManager workerManager;
    private final PrerequisiteChecker prerequisites;
    private final List<String> codexCommand;
    private final CodexProcessStarter processStarter;
    private final BooleanSupplier interactiveTerminal;
    private final ObjectMapper json;

    /// Starts the configured Codex process. Production code inherits the caller's terminal; tests
    /// replace the starter to observe the process without a real terminal.
    @FunctionalInterface
    interface CodexProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }

    CodexBoardSession(
            Map<String, String> environment,
            WorkflowConfigEditor workflowConfig,
            LocalWorkerManager workerManager,
            CommandRunner commands,
            List<String> codexCommand,
            CodexProcessStarter processStarter,
            BooleanSupplier interactiveTerminal,
            ObjectMapper json) {
        this.environment = Map.copyOf(environment);
        this.workflowConfig = workflowConfig;
        this.workerManager = workerManager;
        this.prerequisites = new PrerequisiteChecker(commands);
        this.codexCommand = List.copyOf(codexCommand);
        this.processStarter = processStarter;
        this.interactiveTerminal = interactiveTerminal;
        this.json = json;
    }

    /// Session launcher for the installed CLI: the real `codex` executable on PATH, attached to this
    /// process's terminal.
    static CodexBoardSession forCurrentProcess(LocalWorkerManager workerManager) {
        return new CodexBoardSession(
                System.getenv(),
                new WorkflowConfigEditor(),
                workerManager,
                new ProcessCommandRunner(),
                List.of("codex"),
                ProcessBuilder::start,
                CodexBoardSession::hasInteractiveTerminal,
                new ObjectMapper());
    }

    static boolean hasInteractiveTerminal() {
        var console = SystemConsole.current();
        return console != null && console.isTerminal();
    }

    int run(CodexSessionRequest request, Terminal terminal) throws IOException {
        ConnectedBoardSelection.rejectConflictingSelectors(request.board(), request.workflow());
        LocalWorkerPaths paths = LocalWorkerPaths.from(
                request.appHome(), request.configDir(), request.workspaceRoot(), request.stateHome(), environment);
        ConnectedBoardManifest manifest = new ConnectedBoardRepository(paths.manifestPath()).loadForLifecycle();
        Optional<ConnectedBoard> selected = select(manifest, request, paths, terminal);
        if (selected.isEmpty()) {
            terminal.info("Cancelled. Codex was not started.");
            return USER_CANCELLED_EXIT_CODE;
        }
        ConnectedBoard board = selected.orElseThrow();
        requireCodexReady();
        EffectiveConfig workflow = workerManager.resolveVerifiedWorkflowConfig(paths, board);
        var trello = new TrelloClient(json);
        EffectiveConfig config;
        BoardSessionContext context;
        try {
            config = workflow.withResolvedBoardId(trello.resolveBoardId(workflow));
            context = sessionContext(board, config, trello.fetchBoardLists(config));
        } catch (TrelloException e) {
            throw boardReadFailure(e);
        }
        var tools = new BoardSessionTools(json, trello, config, context);
        try (var server = BoardSessionMcpServer.start(
                json,
                tools,
                BoardSessionInstructions.serverInstructions(context),
                CodexModelDefaultsResolver.implementationVersion())) {
            terminal.info("Starting Codex for Trello board " + DisplayNames.quotedName(board.boardName()) + ".");
            return runCodex(codexProcess(server, context, config));
        }
    }

    private static TrelloBoardSetupException boardReadFailure(TrelloException e) {
        if (e.statusCode() == Status.NOT_FOUND.getStatusCode()) {
            return new TrelloBoardSetupException(
                    "trello_resource_not_found",
                    "Trello did not find the selected board. Check the workflow's tracker.board_id and that the "
                            + "Trello token can read the board.",
                    e);
        }
        return new TrelloBoardSetupException(
                e.code(), "Could not read the selected Trello board: " + e.getMessage(), e);
    }

    private Optional<ConnectedBoard> select(
            ConnectedBoardManifest manifest, CodexSessionRequest request, LocalWorkerPaths paths, Terminal terminal)
            throws IOException {
        if (request.board().isPresent()) {
            return Optional.of(
                    ConnectedBoardSelection.byBoard(manifest, request.board().orElseThrow()));
        }
        if (request.workflow().isPresent()) {
            Path workflowPath =
                    request.workflow().orElseThrow().toAbsolutePath().normalize();
            return Optional.of(ConnectedBoardSelection.byWorkflow(manifest, workflowPath)
                    .orElseThrow(() -> new TrelloBoardSetupException(
                            "setup_codex_workflow_not_connected",
                            "--workflow is not the workflow of a connected Trello board. Connect the board with "
                                    + "symphony-trello setup-local, or choose a connected board with --board.")));
        }
        return selectEligible(manifest, paths, terminal);
    }

    private Optional<ConnectedBoard> selectEligible(
            ConnectedBoardManifest manifest, LocalWorkerPaths paths, Terminal terminal) throws IOException {
        if (manifest.boards().isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_codex_no_connected_board",
                    "No Trello boards are connected to Symphony. Connect one with symphony-trello setup-local, "
                            + "then rerun symphony-trello codex.");
        }
        List<ConnectedBoard> eligible = new ArrayList<>();
        for (ConnectedBoard board : manifest.boards()) {
            ineligibilityReason(paths, board)
                    .ifPresentOrElse(
                            reason -> terminal.warn("Skipped connected board " + boardLabel(board) + ": " + reason),
                            () -> eligible.add(board));
        }
        if (eligible.isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_codex_no_connected_board",
                    "No connected Trello board has a usable workflow. Fix the workflows listed above, or reconnect "
                            + "the board with symphony-trello setup-local, then rerun symphony-trello codex.");
        }
        if (eligible.size() == 1) {
            return Optional.of(eligible.getFirst());
        }
        if (!interactiveTerminal.getAsBoolean()) {
            throw new TrelloBoardSetupException(
                    "setup_codex_board_required",
                    "Multiple Trello boards are connected. Re-run with --board NAME or --workflow PATH to choose "
                            + "the board for this Codex session.");
        }
        return pick(eligible, terminal);
    }

    /// Reports why a connected board's workflow cannot be loaded, using the same workflow loading as
    /// `start`. Credential and Trello checks run only after the user has chosen a board, so a missing
    /// credential is reported for that board instead of hiding it from the picker.
    private Optional<String> ineligibilityReason(LocalWorkerPaths paths, ConnectedBoard board) {
        Path envPath = paths.envPath(board);
        try {
            workflowConfig.prepareLaunchWorkflow(
                    board.workflowPath(), WorkflowEnvironmentResolver.resolver(environment, envPath), false);
            return Optional.empty();
        } catch (TrelloBoardSetupException e) {
            return Optional.of(e.getMessage().lines().findFirst().orElse(e.code()));
        }
    }

    private static Optional<ConnectedBoard> pick(List<ConnectedBoard> boards, Terminal terminal) throws IOException {
        terminal.info("Choose the Trello board for this Codex session:");
        for (int index = 0; index < boards.size(); index++) {
            terminal.info("  " + (index + 1) + ". " + boardLabel(boards.get(index)));
        }
        while (true) {
            String answer = terminal.readLine("Board number (press Enter to cancel): ");
            if (answer == null || answer.isBlank()) {
                return Optional.empty();
            }
            Optional<Integer> choice = boardNumber(answer.strip(), boards.size());
            if (choice.isPresent()) {
                return Optional.of(boards.get(choice.orElseThrow() - 1));
            }
            terminal.warn("Enter a number from 1 to " + boards.size() + ", or press Enter to cancel.");
        }
    }

    private static Optional<Integer> boardNumber(String answer, int boardCount) {
        try {
            int number = Integer.parseInt(answer);
            return number >= 1 && number <= boardCount ? Optional.of(number) : Optional.empty();
        } catch (NumberFormatException ignored) {
            // Anything other than a listed number is answered with the retry hint below.
            return Optional.empty();
        }
    }

    /// Board name, short link, and workflow file name: enough to tell apart boards with the same name
    /// without printing credentials or full local paths.
    private static String boardLabel(ConnectedBoard board) {
        return DisplayNames.quotedName(board.boardName()) + " (" + shortLinkOrBoardId(board) + ", "
                + PathNames.fileName(board.workflowPath()) + ")";
    }

    private static String shortLinkOrBoardId(ConnectedBoard board) {
        return board.boardKey() == null || board.boardKey().isBlank() ? board.boardId() : board.boardKey();
    }

    private void requireCodexReady() {
        if (!prerequisites.codexInstalled().available()) {
            throw new TrelloBoardSetupException(
                    "setup_codex_missing",
                    "The Codex CLI is not installed or is not on PATH. Install Codex, check that `codex --version` "
                            + "works, then rerun symphony-trello codex.");
        }
        if (!prerequisites.codexLoggedIn().available()) {
            throw new TrelloBoardSetupException(
                    "setup_codex_auth_required",
                    "Codex is not logged in. Run `codex login`, then rerun symphony-trello codex.");
        }
    }

    private BoardSessionContext sessionContext(
            ConnectedBoard board, EffectiveConfig config, List<TrelloClient.BoardList> lists) {
        List<TrelloClient.BoardList> openLists =
                lists.stream().filter(list -> !list.closed()).toList();
        Predicate<String> openListName = name -> openLists.stream()
                .anyMatch(list -> StateNames.normalize(list.name()).equals(StateNames.normalize(name)));
        EffectiveConfig.TrackerConfig tracker = config.tracker();
        WorkflowConfigEditor.GeneratedRoutingLists routing = workflowConfig.generatedRoutingLists(board.workflowPath());
        BoardListRoles roles = BoardListRoles.of(
                openRoleLists(tracker.activeStates(), tracker.activeListIds(), openLists, openListName),
                routing.queueLists().stream().filter(openListName).toList(),
                nonBlank(tracker.inProgressState()).filter(openListName),
                routing.reviewList().filter(openListName),
                nonBlank(tracker.blockedState())
                        .or(() -> workflowConfig
                                .listConfiguration(board.workflowPath())
                                .blockedState())
                        .filter(openListName),
                openRoleLists(tracker.terminalStates(), tracker.terminalListIds(), openLists, openListName));
        return new BoardSessionContext(
                board.boardName(), shortLinkOrBoardId(board), PathNames.fileName(board.workflowPath()), roles);
    }

    /// Names of the open lists that a role selects by name or by list id, in board order for ids.
    private static List<String> openRoleLists(
            List<String> names,
            List<String> listIds,
            List<TrelloClient.BoardList> openLists,
            Predicate<String> openListName) {
        Stream<String> byName = names.stream().filter(openListName);
        Stream<String> byId =
                openLists.stream().filter(list -> listIds.contains(list.id())).map(TrelloClient.BoardList::name);
        return Stream.concat(byName, byId).distinct().toList();
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private ProcessBuilder codexProcess(
            BoardSessionMcpServer server, BoardSessionContext context, EffectiveConfig config) {
        List<String> command = new ArrayList<>(codexCommand);
        command.addAll(codexArguments(server, context));
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        Map<String, String> childEnvironment = builder.environment();
        // Start from the environment this session resolved credentials from, so the removal below
        // covers exactly the variables that could have supplied them.
        childEnvironment.clear();
        childEnvironment.putAll(environment);
        ProcessEnvironment.removeDefaultSecrets(builder);
        workflowCredentialEnvironmentNames(config).forEach(childEnvironment::remove);
        // This variable names the credential file itself; Codex has no use for it.
        childEnvironment.remove(LocalEnvironment.DOTENV_PATH_ENV);
        childEnvironment.put(SESSION_TOKEN_ENVIRONMENT, server.bearerToken());
        return builder;
    }

    /// Configuration overrides for this Codex run only. Codex reads a value that is not valid TOML
    /// as plain text, so the values carry no quotes that platform command-line parsing could strip.
    /// No sandbox, approval, or model option is passed: the user's Codex configuration decides.
    private static List<String> codexArguments(BoardSessionMcpServer server, BoardSessionContext context) {
        String serverKey = "mcp_servers." + BoardSessionInstructions.MCP_SERVER_NAME;
        return List.of(
                "-c",
                serverKey + ".url=" + server.endpoint(),
                "-c",
                serverKey + ".bearer_token_env_var=" + SESSION_TOKEN_ENVIRONMENT,
                "-c",
                "developer_instructions=" + BoardSessionInstructions.developerInstructions(context));
    }

    /// Environment names the workflow reads its Trello credentials from, such as a custom `$NAME`
    /// reference, so Codex does not inherit them even when they differ from the default names.
    private List<String> workflowCredentialEnvironmentNames(EffectiveConfig config) {
        return workflowConfig
                .trackerCredentialReferences(config.workflowPath())
                .map(references -> Stream.of(references.apiKey(), references.apiToken())
                        .flatMap(Optional::stream)
                        .map(String::strip)
                        .map(EnvironmentReferences::referenceName)
                        .flatMap(Optional::stream)
                        .toList())
                .orElse(List.of(TrelloEnvironment.API_KEY, TrelloEnvironment.API_TOKEN));
    }

    private int runCodex(ProcessBuilder builder) throws IOException {
        Process process;
        try {
            process = processStarter.start(builder);
        } catch (IOException e) {
            throw new TrelloBoardSetupException(
                    "setup_codex_missing",
                    "The Codex CLI could not be started. Check that `codex --version` works, then rerun "
                            + "symphony-trello codex.",
                    e);
        }
        // A JVM shutdown, for example from a signal sent to this process only, must not leave Codex
        // running after the board tools it depends on have stopped.
        var stopCodexOnShutdown = new Thread(() -> stop(process), "codex-board-session-shutdown");
        Runtime.getRuntime().addShutdownHook(stopCodexOnShutdown);
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            // Stop Codex first: the grace-period wait below needs a thread without a pending interrupt.
            stop(process);
            Thread.currentThread().interrupt();
            return USER_CANCELLED_EXIT_CODE;
        } finally {
            removeShutdownHook(stopCodexOnShutdown);
        }
    }

    /// Asks Codex to exit, then forces it and any processes it left behind after a short grace
    /// period, so no part of the session outlives the board tools.
    static void stop(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        process.destroy();
        try {
            if (!process.waitFor(CODEX_STOP_GRACE)) {
                process.destroyForcibly();
                process.waitFor(CODEX_STOP_GRACE);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }

    private static void removeShutdownHook(Thread hook) {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // The JVM is already shutting down and runs the hook itself.
        }
    }
}
