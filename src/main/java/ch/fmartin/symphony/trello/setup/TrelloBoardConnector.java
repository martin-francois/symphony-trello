package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.GitHubIntegration;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class TrelloBoardConnector {
    private static final String DEFAULT_BOARD_NAME = "Symphony Work Queue";

    private final TrelloBoardSetup boardSetup;
    private final WorkflowConfigEditor workflowConfig;
    private final CodexModelSelectionFlow codexModelSelectionFlow;

    TrelloBoardConnector(TrelloBoardSetup boardSetup, WorkflowConfigEditor workflowConfig) {
        this.boardSetup = boardSetup;
        this.workflowConfig = workflowConfig;
        this.codexModelSelectionFlow = new CodexModelSelectionFlow();
    }

    LocalSetup.SetupResult createOrConnectBoard(
            LocalSetup.Options options,
            TrelloCredentials credentials,
            GitHubIntegration githubIntegration,
            ConnectedBoardManifest manifest,
            Terminal terminal)
            throws IOException {
        BoardSetupChoice choice = boardSetupChoice(options, terminal);
        if (choice == BoardSetupChoice.EXISTING) {
            return importExistingBoard(options, credentials, githubIntegration, manifest, terminal);
        }
        return createRecommendedBoard(options, credentials, githubIntegration, manifest, terminal);
    }

    private LocalSetup.SetupResult importExistingBoard(
            LocalSetup.Options options,
            TrelloCredentials credentials,
            GitHubIntegration githubIntegration,
            ConnectedBoardManifest manifest,
            Terminal terminal)
            throws IOException {
        String boardId = options.existingBoardId().orElse(null);
        if (blank(boardId)) {
            boardId = terminal.readLine("Trello board URL, shortlink, or id: ");
        }
        String selector = boardId;
        boardId = manifest.boards().stream()
                .filter(board -> board.boardName().equalsIgnoreCase(selector))
                .map(ConnectedBoard::boardId)
                .findFirst()
                .orElse(selector);
        String parsedBoardId = TrelloBoardIds.parse(boardId);
        TrelloBoardSetup.BoardInfo boardInfo = boardSetup.getBoardInfo(
                new TrelloBoardSetup.BoardInfoRequest(options.endpoint(), credentials, parsedBoardId));
        List<String> openLists = boardSetup.getOpenBoardListNames(
                new TrelloBoardSetup.BoardInfoRequest(options.endpoint(), credentials, boardInfo.boardId()));
        ExistingBoardLists configuredLists = existingBoardLists(options, terminal, openLists, githubIntegration);
        Path workflowPath = resolveWorkflowPath(options, slug(boardInfo.boardName()));
        options = configureCodexModel(options, workflowPath, terminal);
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
                        localSetupServerPort(options, manifest, workflowPath),
                        options.maxAgents(),
                        options.force(),
                        githubIntegration,
                        configuredLists.createMissingGithubLists()));
        return LocalSetup.SetupResult.from(result);
    }

    private LocalSetup.SetupResult createRecommendedBoard(
            LocalSetup.Options options,
            TrelloCredentials credentials,
            GitHubIntegration githubIntegration,
            ConnectedBoardManifest manifest,
            Terminal terminal)
            throws IOException {
        String boardName = options.boardName().orElse(null);
        if (blank(boardName) && !options.nonInteractive()) {
            String answer = terminal.readLine("Board name [\"" + DEFAULT_BOARD_NAME + "\"]: ");
            boardName = blank(answer) ? DEFAULT_BOARD_NAME : answer;
        }
        if (blank(boardName)) {
            boardName = DEFAULT_BOARD_NAME;
        }
        List<TrelloBoardSetup.WorkspaceInfo> workspaces =
                boardSetup.listWorkspaces(new TrelloBoardSetup.WorkspaceListRequest(options.endpoint(), credentials));
        String workspaceId = workspaceId(options, workspaces, terminal);
        Path workflowPath = resolveWorkflowPath(options, slug(boardName));
        options = configureCodexModel(options, workflowPath, terminal);
        return LocalSetup.SetupResult.from(boardSetup(options)
                .createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                        options.endpoint(),
                        credentials,
                        boardName,
                        workspaceId,
                        workflowPath,
                        options.workspaceRoot(),
                        localSetupServerPort(options, manifest, workflowPath),
                        options.maxAgents(),
                        options.force(),
                        !options.workflowPathExplicit(),
                        githubIntegration)));
    }

    private LocalSetup.Options configureCodexModel(LocalSetup.Options options, Path workflowPath, Terminal terminal)
            throws IOException {
        CodexModelSelectionFlow.Selection selected = codexModelSelectionFlow.resolve(
                options, boardSetup.codexModelSelectionDefaultsForWorkflow(workflowPath), terminal);
        return options.withCodexModelSelection(selected);
    }

    private TrelloBoardSetup boardSetup(LocalSetup.Options options) {
        if (options.codexModelDefaults().isEmpty()) {
            return boardSetup;
        }
        TrelloBoardSetup.CodexModelDefaults defaults =
                options.codexModelDefaults().orElseThrow();
        return options.hasExplicitCodexModelRequest()
                ? boardSetup.withCodexModelOverrides(defaults, options.codexModel(), options.codexReasoningEffort())
                : boardSetup.withCodexModelDefaults(defaults);
    }

    private static String workspaceId(
            LocalSetup.Options options, List<TrelloBoardSetup.WorkspaceInfo> workspaces, Terminal terminal)
            throws IOException {
        Optional<String> configuredWorkspaceId = options.workspaceId();
        if (configuredWorkspaceId.isEmpty()) {
            return chooseWorkspaceId(workspaces, terminal, options.nonInteractive());
        }
        return configuredWorkspaceId.orElseThrow();
    }

    private static String chooseWorkspaceId(
            List<TrelloBoardSetup.WorkspaceInfo> workspaces, Terminal terminal, boolean nonInteractive)
            throws IOException {
        if (workspaces.size() == 1) {
            terminal.info("  OK  Trello Workspace: \"" + workspaces.getFirst().displayName() + "\"");
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
            terminal.info("  " + (i + 1) + ". \"" + workspaces.get(i).displayName() + "\"");
        }
        if (nonInteractive) {
            throw new TrelloBoardSetupException(
                    "setup_workspace_id_required",
                    "This token can access multiple Trello Workspaces. Re-run with --workspace-id.");
        }
        int selected = PromptSupport.choice(terminal.readLine("Workspace [1]: "), 1, workspaces.size());
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
                        .orElse(List.of())
                : options.activeStates();
        List<String> terminalStates = options.terminalStates().isEmpty()
                ? detectedList(openListNames, "Done").map(List::of).orElse(List.of())
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
            terminal.info("Open lists: " + String.join(", ", openListNames));
            if (activeStates.isEmpty()) {
                activeStates = promptCsv(terminal, "Queued-work list names, comma-separated: ");
            }
            if (terminalStates.isEmpty()) {
                terminalStates = promptCsv(terminal, "Done list names, comma-separated: ");
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
                            "GitHub workflow import needs a Merging list for landing approval.");
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
                .findFirst();
    }

    private static List<String> promptCsv(Terminal terminal, String prompt) throws IOException {
        return csv(terminal.readLine(prompt));
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

    static Path resolveWorkflowPath(LocalSetup.Options options, String slug) {
        if (options.workflowPathExplicit()) {
            return options.workflowPath();
        }
        Path requested = options.configDir().resolve("WORKFLOW." + slug + ".md");
        if (options.force() || !Files.exists(requested.toAbsolutePath().normalize())) {
            return requested;
        }
        Path parent = requested.getParent();
        String fileName = PathNames.fileName(requested);
        String prefix = fileName.substring(0, fileName.length() - ".md".length());
        Path candidate = parentOrCurrent(parent).resolve(prefix + "-2.md");
        for (int suffix = 3; Files.exists(candidate.toAbsolutePath().normalize()); suffix++) {
            candidate = parentOrCurrent(parent).resolve(prefix + "-" + suffix + ".md");
        }
        return candidate;
    }

    private static Path parentOrCurrent(Path parent) {
        return parent == null ? Path.of(".") : parent;
    }

    static int localSetupServerPort(
            LocalSetup.Options options,
            ConnectedBoardManifest manifest,
            Path workflowPath,
            WorkflowConfigEditor editor) {
        Set<Integer> reservedPorts = reservedWorkflowServerPorts(manifest, workflowPath, options.force(), editor);
        return options.serverPort()
                .map(requestedPort -> validatedRequestedServerPort(requestedPort, reservedPorts))
                .orElseGet(() -> firstAvailableServerPort(reservedPorts));
    }

    private static int firstAvailableServerPort(Set<Integer> reservedPorts) {
        for (int port = TrelloBoardSetup.DEFAULT_SERVER_PORT; port <= 65535; port++) {
            if (!reservedPorts.contains(port) && !LocalHealthChecker.portAcceptsConnections(port)) {
                return port;
            }
        }
        throw new TrelloBoardSetupException("setup_server_port_unavailable", "No free local server port was found.");
    }

    private static int validatedRequestedServerPort(int port, Set<Integer> reservedPorts) {
        if (reservedPorts.contains(port)) {
            throw new TrelloBoardSetupException(
                    "setup_server_port_conflict",
                    "--server-port %d is already reserved by another connected workflow.".formatted(port));
        }
        if (LocalHealthChecker.portAcceptsConnections(port)) {
            throw new TrelloBoardSetupException(
                    "setup_server_port_conflict", "--server-port %d is already in use on 127.0.0.1.".formatted(port));
        }
        return port;
    }

    private int localSetupServerPort(LocalSetup.Options options, ConnectedBoardManifest manifest, Path workflowPath) {
        return localSetupServerPort(options, manifest, workflowPath, workflowConfig);
    }

    private static Set<Integer> reservedWorkflowServerPorts(
            ConnectedBoardManifest manifest, Path workflowPath, boolean replacingTarget, WorkflowConfigEditor editor) {
        Set<Integer> reservedPorts = manifest.boards().stream()
                .filter(board -> !PathsEqual.samePath(board.workflowPath(), workflowPath))
                .map(ConnectedBoard::serverPort)
                .collect(Collectors.toSet());
        reservedPorts.addAll(siblingWorkflowServerPorts(workflowPath, replacingTarget, editor));
        return reservedPorts;
    }

    private static Set<Integer> siblingWorkflowServerPorts(
            Path workflowPath, boolean replacingTarget, WorkflowConfigEditor editor) {
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
                    .flatMap(path -> editor.serverPort(path).stream())
                    .collect(Collectors.toSet());
        } catch (IOException ignored) {
            return Set.of();
        }
    }

    static String slug(String value) {
        String slug =
                value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return slug.isBlank() ? "trello-board" : slug;
    }

    static String fallbackSlug(String value) {
        return slug(blank(value) ? DEFAULT_BOARD_NAME : value);
    }

    private static List<String> csv(String value) {
        if (blank(value)) {
            return List.of();
        }
        return Pattern.compile(",")
                .splitAsStream(value)
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
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

    private enum BoardSetupChoice {
        NEW,
        EXISTING
    }
}
