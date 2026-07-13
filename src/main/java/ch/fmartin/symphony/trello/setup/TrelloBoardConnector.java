package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.CommaSeparatedValues.javaTrimmedNonEmptyFields;

import ch.fmartin.symphony.trello.config.LocalEnvironment;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.GitHubIntegration;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

final class TrelloBoardConnector {
    private static final String DEFAULT_BOARD_NAME = "Symphony Work Queue";

    private final TrelloBoardSetup boardSetup;
    private final WorkflowConfigEditor workflowConfig;
    private final LocalWorkerManager workerManager;
    private final Map<String, String> environment;
    private final CodexModelSelectionFlow codexModelSelectionFlow;

    TrelloBoardConnector(
            TrelloBoardSetup boardSetup,
            WorkflowConfigEditor workflowConfig,
            LocalWorkerManager workerManager,
            Map<String, String> environment) {
        this.boardSetup = boardSetup;
        this.workflowConfig = workflowConfig;
        this.workerManager = workerManager;
        this.environment = Map.copyOf(environment);
        this.codexModelSelectionFlow = new CodexModelSelectionFlow();
    }

    LocalSetup.SetupResult createOrConnectBoard(
            BoardSetupChoice choice,
            LocalSetup.Options options,
            TrelloCredentials credentials,
            GitHubIntegrationResolution githubIntegrationResolution,
            ConnectedBoardManifest manifest,
            CredentialPersistence credentialPersistence,
            Terminal terminal)
            throws IOException {
        if (choice == BoardSetupChoice.EXISTING) {
            return importExistingBoard(
                    options, credentials, githubIntegrationResolution, manifest, credentialPersistence, terminal);
        }
        return createRecommendedBoard(
                options, credentials, githubIntegrationResolution, manifest, credentialPersistence, terminal);
    }

    BoardSetupChoice chooseBoardSetup(LocalSetup.Options options, Terminal terminal) throws IOException {
        BoardSetupChoice choice = boardSetupChoice(options, terminal);
        rejectNewBoardInProgress(options, choice);
        return choice;
    }

    void preflightRequestedServerPort(LocalSetup.Options options, ConnectedBoardManifest manifest) {
        options.serverPort().ifPresent(ignored -> preflightWorkflowPath(options)
                .ifPresent(workflowPath -> localSetupServerPort(options, manifest, workflowPath)));
    }

    void rejectDryRunNewBoardInProgress(LocalSetup.Options options) {
        BoardSetupChoice dryRunChoice = options.existingBoardId()
                .map(ignored -> BoardSetupChoice.EXISTING)
                .orElse(BoardSetupChoice.NEW);
        rejectNewBoardInProgress(options, dryRunChoice);
    }

    private static void rejectNewBoardInProgress(LocalSetup.Options options, BoardSetupChoice choice) {
        if (choice == BoardSetupChoice.NEW && !blank(options.inProgressState())) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_arguments",
                    "--in-progress is only supported when connecting an existing Trello board.");
        }
    }

    static Optional<Path> preflightWorkflowPath(LocalSetup.Options options) {
        if (options.workflowPathExplicit()) {
            return Optional.of(options.workflowPath());
        }
        if (options.existingBoardId().isPresent()) {
            return Optional.empty();
        }
        return options.boardName()
                .map(name -> resolveWorkflowPath(options, name))
                .or(() -> options.nonInteractive()
                        ? Optional.of(resolveWorkflowPath(options, DEFAULT_BOARD_NAME))
                        : Optional.empty());
    }

    private LocalSetup.SetupResult importExistingBoard(
            LocalSetup.Options options,
            TrelloCredentials credentials,
            GitHubIntegrationResolution githubIntegrationResolution,
            ConnectedBoardManifest manifest,
            CredentialPersistence credentialPersistence,
            Terminal terminal)
            throws IOException {
        String boardId = requestedBoardSelector(options, terminal);
        String selector = boardId;
        List<ConnectedBoard> connectedMatches = manifest.boards().stream()
                .filter(board -> board.boardName().equalsIgnoreCase(selector))
                .toList();
        if (connectedMatches.size() > 1) {
            throw new TrelloBoardSetupException(
                    "setup_worker_board_ambiguous",
                    "Multiple connected boards match --board. Re-run with a board id or short link.");
        }
        boardId =
                connectedMatches.stream().findAny().map(ConnectedBoard::boardId).orElse(selector);
        String parsedBoardId = TrelloBoardIds.parseImportBoardSelector(boardId);
        TrelloBoardSetup.BoardInfo boardInfo = boardSetup.getBoardInfo(
                new TrelloBoardSetup.BoardInfoRequest(options.endpoint(), credentials, parsedBoardId));
        List<String> openLists = boardSetup.getOpenBoardListNames(
                new TrelloBoardSetup.BoardInfoRequest(options.endpoint(), credentials, boardInfo.boardId()));
        Path workflowPath = resolveWorkflowPath(options, boardInfo.boardName());
        options = configureCodexModel(options, workflowPath, terminal);
        GitHubIntegration githubIntegration = githubIntegrationResolution.resolve();
        ExistingBoardLists configuredLists = existingBoardLists(options, terminal, openLists, githubIntegration);
        MaxAgentsSelection maxAgents = configureMaxAgents(options, workflowPath, terminal);
        credentialPersistence.persist();
        TrelloBoardSetup.ImportBoardResult result = boardSetup(options)
                .importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                        options.endpoint(),
                        credentials,
                        boardInfo.boardId(),
                        configuredLists.activeStates(),
                        configuredLists.terminalStates(),
                        configuredLists.inProgressState(),
                        configuredLists.detectInProgressState(),
                        configuredLists.blockedState(),
                        workflowPath,
                        options.workspaceRoot(),
                        localSetupImportServerPort(options, manifest, workflowPath),
                        maxAgents.value(),
                        options.force(),
                        githubIntegration,
                        configuredLists.createMissingGithubLists(),
                        options.envPath(),
                        maxAgents.preservedFromWorkflow(),
                        repositoryDefaults(workflowPath, options.repositoryUrl())));
        return LocalSetup.SetupResult.from(result, githubIntegration);
    }

    private static String requestedBoardSelector(LocalSetup.Options options, Terminal terminal) throws IOException {
        Optional<String> boardId = options.existingBoardId().filter(id -> !blank(id));
        if (boardId.isEmpty()) {
            return terminal.readLine("Trello board URL, shortlink, or id: ");
        }
        return boardId.get();
    }

    private LocalSetup.SetupResult createRecommendedBoard(
            LocalSetup.Options options,
            TrelloCredentials credentials,
            GitHubIntegrationResolution githubIntegrationResolution,
            ConnectedBoardManifest manifest,
            CredentialPersistence credentialPersistence,
            Terminal terminal)
            throws IOException {
        Optional<String> configuredBoardName = options.boardName().filter(name -> !blank(name));
        if (configuredBoardName.isEmpty() && !options.nonInteractive()) {
            String answer = terminal.readLine("Board name [\"" + DEFAULT_BOARD_NAME + "\"]: ");
            configuredBoardName = Optional.ofNullable(answer).filter(name -> !blank(name));
        }
        String boardName = configuredBoardName.orElse(DEFAULT_BOARD_NAME);
        List<TrelloBoardSetup.WorkspaceInfo> workspaces =
                boardSetup.listWorkspaces(new TrelloBoardSetup.WorkspaceListRequest(options.endpoint(), credentials));
        String workspaceId = workspaceId(options, workspaces, terminal);
        Path workflowPath = resolveWorkflowPath(options, boardName);
        options = configureCodexModel(options, workflowPath, terminal);
        GitHubIntegration githubIntegration = githubIntegrationResolution.resolve();
        MaxAgentsSelection maxAgents = configureMaxAgents(options, workflowPath, terminal);
        credentialPersistence.persist();
        return LocalSetup.SetupResult.from(
                boardSetup(options)
                        .createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                                options.endpoint(),
                                credentials,
                                boardName,
                                workspaceId,
                                workflowPath,
                                options.workspaceRoot(),
                                localSetupServerPort(options, manifest, workflowPath),
                                maxAgents.value(),
                                options.force(),
                                !options.workflowPathExplicit(),
                                githubIntegration,
                                options.envPath(),
                                options.detectInProgressState(),
                                maxAgents.preservedFromWorkflow(),
                                repositoryDefaults(workflowPath, options.repositoryUrl()))),
                githubIntegration);
    }

    private TrelloBoardSetup.RepositoryDefaults repositoryDefaults(
            Path workflowPath, Optional<String> explicitRepositoryUrl) {
        return workflowConfig.repositoryDefaults(workflowPath).withExplicitDefaultUrl(explicitRepositoryUrl);
    }

    private LocalSetup.Options configureCodexModel(LocalSetup.Options options, Path workflowPath, Terminal terminal)
            throws IOException {
        CodexModelSelectionDefaults catalog =
                options.codexModelCatalog().orElseGet(boardSetup::resolvedCodexModelSelectionDefaults);
        CodexModelSelectionFlow.Selection selected = codexModelSelectionFlow.resolve(
                options, boardSetup.codexModelSelectionDefaultsForWorkflow(workflowPath, catalog), terminal);
        return options.withCodexModelCatalog(catalog).withCodexModelSelection(selected);
    }

    private TrelloBoardSetup boardSetup(LocalSetup.Options options) {
        return options.codexModelDefaults()
                .map(defaults -> options.hasExplicitCodexModelRequest()
                        ? boardSetup.withCodexModelOverrides(
                                defaults, options.codexModel(), options.codexReasoningEffort())
                        : boardSetup.withCodexModelDefaults(defaults))
                .orElse(boardSetup);
    }

    private MaxAgentsSelection configureMaxAgents(LocalSetup.Options options, Path workflowPath, Terminal terminal)
            throws IOException {
        if (options.maxAgentsExplicit()) {
            return new MaxAgentsSelection(options.maxAgents(), false);
        }
        MaxAgentsSelection currentMaxAgents = workflowConfig
                .maxAgents(workflowPath)
                .map(maxAgents -> new MaxAgentsSelection(maxAgents, true))
                .orElseGet(() -> new MaxAgentsSelection(TrelloBoardSetup.DEFAULT_MAX_CONCURRENT_AGENTS, false));
        int defaultMaxAgents = currentMaxAgents.value();
        if (options.nonInteractive()) {
            return currentMaxAgents;
        }

        terminal.info("");
        terminal.info("Per-board concurrency");
        terminal.info("Current value for this board: " + defaultMaxAgents + " card" + (defaultMaxAgents == 1 ? "" : "s")
                + " processed concurrently.");
        terminal.info(
                "Higher values run multiple Codex agents for this board at once, including their builds, tests, package installs, and network calls.");
        terminal.info(
                "Only raise this when the machine and repository can handle parallel runs from separate workspaces.");
        terminal.info(
                "If Trello cards depend on each other, add prerequisite checklist items before moving them into Ready for Codex.");
        terminal.info("If you are unsure, press Enter to keep the current value.");
        return new MaxAgentsSelection(
                promptedMaxAgents(terminal, currentMaxAgents), currentMaxAgents.preservedFromWorkflow());
    }

    private static int promptedMaxAgents(Terminal terminal, MaxAgentsSelection currentMaxAgents) throws IOException {
        String answer = terminal.readLine(
                "Maximum cards processed concurrently for this board [" + currentMaxAgents.value() + "]: ");
        if (answer == null || answer.isBlank()) {
            return currentMaxAgents.value();
        }
        return PromptSupport.parseBoundedChoice(answer, TrelloBoardSetup.MAX_SETUP_CONCURRENT_AGENTS);
    }

    private record MaxAgentsSelection(int value, boolean preservedFromWorkflow) {}

    private static String workspaceId(
            LocalSetup.Options options, List<TrelloBoardSetup.WorkspaceInfo> workspaces, Terminal terminal)
            throws IOException {
        Optional<String> configuredWorkspaceId = options.workspaceId();
        if (configuredWorkspaceId.isEmpty()) {
            return chooseWorkspaceId(workspaces, terminal, options.nonInteractive());
        }
        return configuredWorkspaceId.get();
    }

    private static String chooseWorkspaceId(
            List<TrelloBoardSetup.WorkspaceInfo> workspaces, Terminal terminal, boolean nonInteractive)
            throws IOException {
        if (workspaces.size() == 1) {
            terminal.info("  OK  Trello Workspace: "
                    + DisplayNames.quotedName(workspaces.getFirst().displayName()));
            return workspaces.getFirst().id();
        }
        if (workspaces.isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_workspace_required",
                    "No Trello Workspace was found for this token. Create a Workspace in Trello, then rerun setup-local.");
        }
        terminal.info("");
        terminal.info("Choose Trello Workspace:");
        for (int i = 0; i < workspaces.size(); i++) {
            terminal.info("  " + (i + 1) + ". "
                    + DisplayNames.quotedName(workspaces.get(i).displayName()));
        }
        if (nonInteractive) {
            throw new TrelloBoardSetupException(
                    "setup_workspace_id_required",
                    "This token can access multiple Trello Workspaces. Re-run with --workspace-id.");
        }
        int selected = PromptSupport.requiredChoice(
                terminal.readLine("Workspace: "),
                workspaces.size(),
                "setup_workspace_id_required",
                "Workspace selection is required.");
        return workspaces.get(selected - 1).id();
    }

    private static ExistingBoardLists existingBoardLists(
            LocalSetup.Options options,
            Terminal terminal,
            List<String> openListNames,
            GitHubIntegration githubIntegration)
            throws IOException {
        List<String> activeStates = options.activeStates().isEmpty()
                ? detectedList(openListNames, TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE)
                        .map(List::of)
                        .orElseGet(List::of)
                : options.activeStates();
        List<String> terminalStates = options.terminalStates().isEmpty()
                ? detectedList(openListNames, "Done").map(List::of).orElseGet(List::of)
                : options.terminalStates();
        String inProgressState = options.detectInProgressState()
                ? detectedList(openListNames, TrelloBoardSetup.RECOMMENDED_IN_PROGRESS_STATE)
                        .orElse(null)
                : options.inProgressState();
        boolean detectInProgressState = false;
        String blockedState = blank(options.blockedState())
                ? detectedList(openListNames, TrelloBoardSetup.RECOMMENDED_BLOCKED_STATE)
                        .orElse(null)
                : options.blockedState();
        boolean createMissingGithubLists = githubIntegration.enabled();
        if (!options.nonInteractive()) {
            terminal.info("");
            terminal.info("Existing board lists");
            terminal.info("Open lists: " + DisplayNames.quotedList(openListNames));
            if (activeStates.isEmpty()) {
                activeStates = promptCommaSeparatedListNames(terminal, "Queued-work list names, comma-separated: ");
            }
            if (terminalStates.isEmpty()) {
                terminalStates = promptCommaSeparatedListNames(terminal, "Done list names, comma-separated: ");
            }
            if (options.detectInProgressState()) {
                inProgressState = promptOptionalList(terminal, "In-progress list name", inProgressState, null);
            }
            if (blank(options.blockedState())) {
                blockedState = promptOptionalList(terminal, "Blocked list name", blockedState, "-");
            }
            if (githubIntegration.enabled()
                    && openListNames.stream()
                            .noneMatch(name -> name.equalsIgnoreCase(TrelloBoardSetup.RECOMMENDED_MERGING_STATE))) {
                terminal.info(
                        "GitHub mode will create this missing list: " + TrelloBoardSetup.RECOMMENDED_MERGING_STATE);
                createMissingGithubLists = PromptSupport.yesDefaultTrue(terminal, "Create missing GitHub list? [Y/n] ");
                if (!createMissingGithubLists) {
                    throw new TrelloBoardSetupException(
                            "setup_github_import_list_declined",
                            "GitHub workflow import needs a Merging list for merge approval.");
                }
            }
        }
        return new ExistingBoardLists(
                activeStates,
                terminalStates,
                inProgressState,
                detectInProgressState,
                blockedState,
                createMissingGithubLists);
    }

    private static Optional<String> detectedList(List<String> openListNames, String expectedName) {
        return openListNames.stream()
                .filter(name -> name.equalsIgnoreCase(expectedName))
                .findAny();
    }

    private static List<String> promptCommaSeparatedListNames(Terminal terminal, String prompt) throws IOException {
        return javaTrimmedNonEmptyFields(terminal.readLine(prompt));
    }

    private static String promptOptionalList(Terminal terminal, String label, String detected, String disabledValue)
            throws IOException {
        String suffix = blank(detected) ? "[enter - for none]: " : "[" + detected + ", enter - for none]: ";
        String answer = terminal.readLine(label + " " + suffix);
        if ("-".equals(answer)) {
            return disabledValue;
        }
        return blank(answer) ? detected : answer;
    }

    private static BoardSetupChoice boardSetupChoice(LocalSetup.Options options, Terminal terminal) throws IOException {
        if (options.existingBoardId().isPresent()) {
            return BoardSetupChoice.EXISTING;
        }
        if (options.boardName().isPresent()) {
            return BoardSetupChoice.NEW;
        }
        if (options.nonInteractive()) {
            return BoardSetupChoice.NEW;
        }
        terminal.info("");
        terminal.info("Trello board");
        terminal.info("");
        terminal.info("Choose board setup:");
        terminal.info("  1. Create a new Trello board with recommended lists");
        terminal.info("  2. Use an existing Trello board");
        terminal.info("");
        return PromptSupport.choice(terminal.readLine("Board setup [1]: "), 1, 2) == 2
                ? BoardSetupChoice.EXISTING
                : BoardSetupChoice.NEW;
    }

    static Path resolveWorkflowPath(LocalSetup.Options options, String boardName) {
        if (options.workflowPathExplicit()) {
            return options.workflowPath();
        }
        Path requested = options.configDir().resolve(WorkflowFileNames.generatedFileName(boardName, "trello-board", 1));
        if (options.force() || !Files.exists(requested.toAbsolutePath().normalize())) {
            return requested;
        }
        Path parent = requested.getParent();
        Path candidate =
                parentOrCurrent(parent).resolve(WorkflowFileNames.generatedFileName(boardName, "trello-board", 2));
        for (int suffix = 3; Files.exists(candidate.toAbsolutePath().normalize()); suffix++) {
            candidate = parentOrCurrent(parent)
                    .resolve(WorkflowFileNames.generatedFileName(boardName, "trello-board", suffix));
        }
        return candidate;
    }

    private static Path parentOrCurrent(Path parent) {
        return parent == null ? Path.of(".") : parent;
    }

    int localSetupServerPort(
            LocalSetup.Options options,
            ConnectedBoardManifest manifest,
            Path workflowPath,
            WorkflowConfigEditor editor) {
        Set<Integer> reservedPorts = reservedWorkflowServerPorts(
                manifest, workflowPath, options.force(), editor, ignored -> Optional.empty());
        return options.serverPort()
                .map(requestedPort -> validatedRequestedServerPort(requestedPort, reservedPorts))
                .orElseGet(() -> firstAvailableServerPort(reservedPorts));
    }

    private int firstAvailableServerPort(Set<Integer> reservedPorts) {
        for (int port = TrelloBoardSetup.DEFAULT_SERVER_PORT; port <= LocalPort.MAX; port++) {
            if (!reservedPorts.contains(port) && !boardSetup.portInUse(port)) {
                return port;
            }
        }
        throw new TrelloBoardSetupException("setup_server_port_unavailable", "No free local server port was found.");
    }

    private int validatedRequestedServerPort(int port, Set<Integer> reservedPorts) {
        if (reservedPorts.contains(port)) {
            throw new TrelloBoardSetupException(
                    "setup_server_port_conflict",
                    "--server-port %d is already reserved by another connected workflow.".formatted(port));
        }
        if (boardSetup.portInUse(port)) {
            throw new TrelloBoardSetupException(
                    "setup_server_port_conflict",
                    "--server-port %d is already in use on %s.".formatted(port, LocalHealthChecker.LOOPBACK_HOST));
        }
        return port;
    }

    private int localSetupServerPort(LocalSetup.Options options, ConnectedBoardManifest manifest, Path workflowPath) {
        Set<Integer> reservedPorts = reservedWorkflowServerPorts(
                manifest,
                workflowPath,
                options.force(),
                workflowConfig,
                name -> LocalEnvironment.get(name, options.envPath()));
        return options.serverPort()
                .map(requestedPort ->
                        validatedRequestedServerPort(options, manifest, workflowPath, requestedPort, reservedPorts))
                .orElseGet(() -> firstAvailableServerPort(reservedPorts));
    }

    private Integer localSetupImportServerPort(
            LocalSetup.Options options, ConnectedBoardManifest manifest, Path workflowPath) throws IOException {
        Set<Integer> reservedPorts = reservedWorkflowServerPorts(
                manifest,
                workflowPath,
                options.force(),
                workflowConfig,
                name -> LocalEnvironment.get(name, options.envPath()));
        Optional<Integer> serverPort = options.serverPort();
        if (serverPort.isEmpty()) {
            return selectedImportServerPort(options, manifest, workflowPath, reservedPorts);
        }
        return validatedRequestedServerPort(options, manifest, workflowPath, serverPort.get(), reservedPorts);
    }

    private Integer selectedImportServerPort(
            LocalSetup.Options options, ConnectedBoardManifest manifest, Path workflowPath, Set<Integer> reservedPorts)
            throws IOException {
        return replaceableWorkflowServerPort(options, manifest, workflowPath, reservedPorts)
                .orElseGet(() -> firstAvailableServerPort(reservedPorts));
    }

    private Optional<Integer> replaceableWorkflowServerPort(
            LocalSetup.Options options, ConnectedBoardManifest manifest, Path workflowPath, Set<Integer> reservedPorts)
            throws IOException {
        if (!options.force()
                || !Files.isRegularFile(workflowPath.toAbsolutePath().normalize())) {
            return Optional.empty();
        }
        Optional<Integer> existingPort = workflowConfig
                .serverPort(workflowPath, name -> LocalEnvironment.get(name, options.envPath()))
                .filter(port -> port != 0)
                .filter(port -> !reservedPorts.contains(port));
        return existingPort.map(port -> replaceableExistingPort(options, manifest, workflowPath, port));
    }

    private int replaceableExistingPort(
            LocalSetup.Options options, ConnectedBoardManifest manifest, Path workflowPath, int port) {
        if (boardSetup.portInUse(port) && !canStopManagedWorkflow(options, manifest, workflowPath, port)) {
            throw new TrelloBoardSetupException(
                    "setup_server_port_conflict",
                    "--server-port %d is already in use on %s.".formatted(port, LocalHealthChecker.LOOPBACK_HOST));
        }
        return port;
    }

    private int validatedRequestedServerPort(
            LocalSetup.Options options,
            ConnectedBoardManifest manifest,
            Path workflowPath,
            int port,
            Set<Integer> reservedPorts) {
        if (reservedPorts.contains(port)) {
            throw new TrelloBoardSetupException(
                    "setup_server_port_conflict",
                    "--server-port %d is already reserved by another connected workflow.".formatted(port));
        }
        if (boardSetup.portInUse(port) && !canStopManagedWorkflow(options, manifest, workflowPath, port)) {
            throw new TrelloBoardSetupException(
                    "setup_server_port_conflict",
                    "--server-port %d is already in use on %s.".formatted(port, LocalHealthChecker.LOOPBACK_HOST));
        }
        return port;
    }

    private boolean canStopManagedWorkflow(
            LocalSetup.Options options, ConnectedBoardManifest manifest, Path workflowPath, int port) {
        List<ConnectedBoard> matches = manifest.boards().stream()
                .filter(board -> board.serverPort() == port)
                .filter(board -> PathsEqual.samePath(board.workflowPath(), workflowPath))
                .toList();
        if (matches.isEmpty()) {
            return false;
        }
        try {
            return workerManager.canStopManagedWorker(localWorkerPaths(options), matches.getFirst());
        } catch (IOException e) {
            return false;
        }
    }

    private LocalWorkerPaths localWorkerPaths(LocalSetup.Options options) {
        return LocalWorkerPaths.from(
                Optional.empty(),
                Optional.of(options.configDir()),
                Optional.of(options.workspaceRoot()),
                Optional.empty(),
                environment);
    }

    private static Set<Integer> reservedWorkflowServerPorts(
            ConnectedBoardManifest manifest,
            Path workflowPath,
            boolean replacingTarget,
            WorkflowConfigEditor editor,
            Function<String, Optional<String>> environmentResolver) {
        Set<Integer> reservedPorts = manifest.boards().stream()
                .filter(board -> !PathsEqual.samePath(board.workflowPath(), workflowPath))
                .map(ConnectedBoard::serverPort)
                // Keep this mutable because sibling workflow ports are merged before returning the
                // reserved-port set to setup validation.
                .collect(Collectors.toCollection(HashSet::new));
        reservedPorts.addAll(siblingWorkflowServerPorts(workflowPath, replacingTarget, editor, environmentResolver));
        return reservedPorts;
    }

    private static Set<Integer> siblingWorkflowServerPorts(
            Path workflowPath,
            boolean replacingTarget,
            WorkflowConfigEditor editor,
            Function<String, Optional<String>> environmentResolver) {
        Path absolute = workflowPath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return Set.of();
        }
        try (var stream = Files.list(parent)) {
            return stream.filter(path -> path.getFileName().toString().matches("WORKFLOW(\\..*)?\\.md"))
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .filter(path -> !replacingTarget || !path.equals(absolute))
                    .flatMap(path -> editor.serverPort(path, environmentResolver).stream())
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException ignored) {
            return Set.of();
        }
    }

    static String slug(String value) {
        return WorkflowFileNames.slug(value, "trello-board");
    }

    static String fallbackSlug(String value) {
        return slug(blank(value) ? DEFAULT_BOARD_NAME : value);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record ExistingBoardLists(
            List<String> activeStates,
            List<String> terminalStates,
            String inProgressState,
            boolean detectInProgressState,
            String blockedState,
            boolean createMissingGithubLists) {}

    enum BoardSetupChoice {
        NEW,
        EXISTING
    }

    @FunctionalInterface
    interface CredentialPersistence {
        void persist() throws IOException;
    }

    @FunctionalInterface
    interface GitHubIntegrationResolution {
        GitHubIntegration resolve() throws IOException;
    }
}
