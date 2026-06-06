package ch.fmartin.symphony.trello.setup;

import static com.google.common.base.Preconditions.checkArgument;

import ch.fmartin.symphony.trello.codex.CodexSkillCatalog;
import ch.fmartin.symphony.trello.config.ConfigDefaults;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.Response.Status.Family;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class TrelloBoardSetup {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://api.trello.com/1");
    public static final Path DEFAULT_WORKFLOW_PATH = Path.of("WORKFLOW.md");
    public static final Path DEFAULT_WORKSPACE_ROOT = Path.of("./workspaces");
    public static final int DEFAULT_MAX_CONCURRENT_AGENTS = ConfigDefaults.DEFAULT_SETUP_MAX_CONCURRENT_AGENTS;
    public static final int DEFAULT_SERVER_PORT = ConfigDefaults.DEFAULT_SERVER_PORT;
    public static final String DEFAULT_CODEX_MODEL = "gpt-5.5";
    public static final String DEFAULT_CODEX_REASONING_EFFORT = "medium";
    private static final String CODEX_MODEL_DEFAULTS_LABEL = "codexModelDefaults";
    public static final String RECOMMENDED_ACTIVE_STATE = "Ready for Codex";
    public static final String RECOMMENDED_IN_PROGRESS_STATE = "In Progress";
    public static final String RECOMMENDED_BLOCKED_STATE = "Blocked";
    public static final String RECOMMENDED_REVIEW_STATE = "Human Review";
    public static final String RECOMMENDED_MERGING_STATE = "Merging";
    public static final String LEGACY_REVIEW_STATE = "Review";
    public static final List<String> RECOMMENDED_LISTS = List.of(
            "Inbox",
            RECOMMENDED_ACTIVE_STATE,
            RECOMMENDED_IN_PROGRESS_STATE,
            RECOMMENDED_BLOCKED_STATE,
            RECOMMENDED_REVIEW_STATE,
            RECOMMENDED_MERGING_STATE,
            "Done");
    public static final List<String> RECOMMENDED_NON_GITHUB_LISTS = List.of(
            "Inbox",
            RECOMMENDED_ACTIVE_STATE,
            RECOMMENDED_IN_PROGRESS_STATE,
            RECOMMENDED_BLOCKED_STATE,
            RECOMMENDED_REVIEW_STATE,
            "Done");
    public static final List<String> RECOMMENDED_ACTIVE_STATES =
            List.of(RECOMMENDED_ACTIVE_STATE, RECOMMENDED_IN_PROGRESS_STATE, RECOMMENDED_MERGING_STATE);
    public static final List<String> RECOMMENDED_NON_GITHUB_ACTIVE_STATES =
            List.of(RECOMMENDED_ACTIVE_STATE, RECOMMENDED_IN_PROGRESS_STATE);
    public static final List<String> RECOMMENDED_TERMINAL_STATES = List.of("Done");

    private static final List<String> SYSTEM_TERMINAL_STATES =
            List.of("Archived", "ArchivedList", "ArchivedBoard", "Deleted");
    private static final String FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION =
            """
            Filesystem access blocker details must include the inaccessible path, why it is inaccessible,
            that deployed Symphony blocks undeclared host paths by default for security reasons so Trello
            cards cannot make Codex read or edit unrelated host files, that accessible files are available
            in the current per-card workspace shown by `pwd`, and that an operator can allow one or more
            files or folders with the manual deployment
            settings `BindPaths`, `ReadWritePaths`, and `SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS`,
            as documented in `docs/deployment.md#allow-host-path-access`.""";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper json;
    private final ObjectMapper yaml;
    private final HttpClient httpClient;
    private final Supplier<CodexModelSelectionDefaults> codexModelSelectionDefaults;
    private final Optional<String> codexModelOverride;
    private final Optional<String> codexReasoningEffortOverride;

    public TrelloBoardSetup(ObjectMapper json) {
        this(json, CodexModelDefaults.fallback());
    }

    public TrelloBoardSetup(ObjectMapper json, CodexModelDefaults codexModelDefaults) {
        this(json, CodexModelSelectionDefaults.of(codexModelDefaults));
    }

    TrelloBoardSetup(ObjectMapper json, CodexModelSelectionDefaults codexModelSelectionDefaults) {
        this(json, codexModelSelectionDefaultsSupplier(codexModelSelectionDefaults));
    }

    TrelloBoardSetup(ObjectMapper json, Supplier<CodexModelSelectionDefaults> codexModelSelectionDefaults) {
        this(json, codexModelSelectionDefaults, Optional.empty(), Optional.empty());
    }

    private TrelloBoardSetup(
            ObjectMapper json,
            Supplier<CodexModelSelectionDefaults> codexModelSelectionDefaults,
            Optional<String> codexModelOverride,
            Optional<String> codexReasoningEffortOverride) {
        this.json = json;
        this.yaml = new ObjectMapper(new YAMLFactory());
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.codexModelSelectionDefaults =
                Objects.requireNonNull(codexModelSelectionDefaults, CODEX_MODEL_DEFAULTS_LABEL);
        this.codexModelOverride = Objects.requireNonNull(codexModelOverride, "codexModelOverride");
        this.codexReasoningEffortOverride =
                Objects.requireNonNull(codexReasoningEffortOverride, "codexReasoningEffortOverride");
    }

    TrelloBoardSetup withCodexModelDefaults(CodexModelDefaults codexModelDefaults) {
        return new TrelloBoardSetup(json, CodexModelSelectionDefaults.of(codexModelDefaults));
    }

    TrelloBoardSetup withCodexModelOverrides(
            CodexModelDefaults codexModelDefaults,
            Optional<String> codexModelOverride,
            Optional<String> codexReasoningEffortOverride) {
        return withCodexModelOverrides(
                CodexModelSelectionDefaults.of(codexModelDefaults), codexModelOverride, codexReasoningEffortOverride);
    }

    TrelloBoardSetup withCodexModelOverrides(
            CodexModelSelectionDefaults codexModelSelectionDefaults,
            Optional<String> codexModelOverride,
            Optional<String> codexReasoningEffortOverride) {
        return new TrelloBoardSetup(
                json,
                codexModelSelectionDefaultsSupplier(codexModelSelectionDefaults),
                codexModelOverride,
                codexReasoningEffortOverride);
    }

    public NewBoardResult createRecommendedBoard(NewBoardRequest request) {
        request.validate();
        Path workflowPath = resolveNewBoardWorkflowPath(request);
        ensureWorkflowWritable(workflowPath, request.force());
        int serverPort = resolveServerPort(workflowPath, request.serverPort(), request.force());
        String workspaceId = resolveWorkspaceId(request);
        Map<String, Object> board = postMap(
                request.endpoint(),
                "boards/",
                createBoardQuery(request.boardName(), workspaceId),
                request.credentials());
        String boardId = requiredString(board, "id");
        String boardKey = boardKey(board);
        String boardUrl = string(board.get("url"));

        boolean githubEnabled = request.githubIntegration().enabled();
        List<String> createdLists = new ArrayList<>();
        List<String> recommendedLists = githubEnabled ? RECOMMENDED_LISTS : RECOMMENDED_NON_GITHUB_LISTS;
        for (String listName : recommendedLists) {
            postMap(
                    request.endpoint(),
                    "lists",
                    orderedMap("name", listName, "idBoard", boardId, "pos", "bottom"),
                    request.credentials());
            createdLists.add(listName);
        }

        writeWorkflow(
                workflowPath,
                request.force(),
                workflowTemplate(
                        boardKey,
                        githubEnabled ? RECOMMENDED_ACTIVE_STATES : RECOMMENDED_NON_GITHUB_ACTIVE_STATES,
                        RECOMMENDED_TERMINAL_STATES,
                        RECOMMENDED_IN_PROGRESS_STATE,
                        RECOMMENDED_REVIEW_STATE,
                        RECOMMENDED_BLOCKED_STATE,
                        githubEnabled ? RECOMMENDED_MERGING_STATE : null,
                        request.workspaceRoot(),
                        serverPort,
                        request.maxConcurrentAgents(),
                        githubEnabled,
                        codexModelDefaultsForWorkflow(workflowPath)));

        return new NewBoardResult(
                boardId, boardKey, request.boardName(), boardUrl, createdLists, workflowPath, serverPort);
    }

    private String resolveWorkspaceId(NewBoardRequest request) {
        if (!blank(request.workspaceId())) {
            return request.workspaceId();
        }

        List<WorkspaceInfo> workspaces =
                listWorkspaces(new WorkspaceListRequest(request.endpoint(), request.credentials()));
        if (workspaces.size() == 1) {
            return workspaces.getFirst().id();
        }
        if (workspaces.isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_workspace_required",
                    "No Trello Workspace was found for this token. Create a Workspace in Trello, then re-run new-board.");
        }
        throw new TrelloBoardSetupException(
                "setup_workspace_id_required",
                "This token can access multiple Trello Workspaces. Re-run with --workspace-id. Available Workspaces: "
                        + workspaceChoices(workspaces));
    }

    public List<WorkspaceInfo> listWorkspaces(WorkspaceListRequest request) {
        request.validate();
        return getList(
                        request.endpoint(),
                        "members/me/organizations",
                        Map.of("fields", "id,name,displayName,url"),
                        request.credentials())
                .stream()
                .map(payload -> new WorkspaceInfo(
                        requiredString(payload, "id"),
                        requiredString(payload, "name"),
                        fallback(string(payload.get("displayName")), requiredString(payload, "name")),
                        string(payload.get("url"))))
                .toList();
    }

    public MemberInfo getMemberInfo(MemberInfoRequest request) {
        request.validate();
        Map<String, Object> member = getMap(
                request.endpoint(), "members/me", Map.of("fields", "id,username,fullName"), request.credentials());
        String username = requiredString(member, "username");
        return new MemberInfo(
                requiredString(member, "id"), username, fallback(string(member.get("fullName")), username));
    }

    public BoardInfo getBoardInfo(BoardInfoRequest request) {
        request.validate();
        Map<String, Object> board = getMap(
                request.endpoint(),
                "boards/" + encodeSegment(request.boardId()),
                Map.of("fields", "id,name,shortLink,url,closed"),
                request.credentials());
        if (bool(board.get("closed"))) {
            throw new TrelloBoardSetupException("trello_board_closed", "Trello board is archived");
        }
        return new BoardInfo(
                requiredString(board, "id"), boardKey(board), requiredString(board, "name"), string(board.get("url")));
    }

    public List<String> getOpenBoardListNames(BoardInfoRequest request) {
        request.validate();
        return getList(
                        request.endpoint(),
                        "boards/" + encodeSegment(request.boardId()) + "/lists",
                        Map.of("filter", "open", "fields", "id,name,closed,pos"),
                        request.credentials())
                .stream()
                .map(TrelloBoardSetup::toBoardList)
                .filter(list -> !list.closed())
                .map(BoardList::name)
                .toList();
    }

    public ImportBoardResult importExistingBoard(ImportBoardRequest request) {
        request.validate();
        Map<String, Object> board = getMap(
                request.endpoint(),
                "boards/" + encodeSegment(request.boardId()),
                Map.of("fields", "id,name,shortLink,url,closed"),
                request.credentials());
        if (bool(board.get("closed"))) {
            throw new TrelloBoardSetupException("trello_board_closed", "Trello board is closed");
        }

        String resolvedBoardId = requiredString(board, "id");
        List<BoardList> lists = getList(
                        request.endpoint(),
                        "boards/" + encodeSegment(resolvedBoardId) + "/lists",
                        Map.of("filter", "all", "fields", "id,name,closed,pos"),
                        request.credentials())
                .stream()
                .map(TrelloBoardSetup::toBoardList)
                .toList();

        List<String> openListNames = new ArrayList<>(lists.stream()
                .filter(list -> !list.closed())
                .map(BoardList::name)
                .toList());
        List<String> activeStates =
                request.activeStates().isEmpty() ? defaultActiveStates(openListNames) : request.activeStates();
        if (activeStates.isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_active_state_required",
                    "No active list was provided and the board has no 'Ready for Codex' list. Pass --active with the list Codex should poll. Open lists: "
                            + String.join(", ", openListNames));
        }

        List<String> terminalStates =
                request.terminalStates().isEmpty() ? defaultTerminalStates(openListNames) : request.terminalStates();
        boolean canCreateMergingList = request.githubIntegration().enabled()
                && request.createMissingGithubLists()
                && !terminalStates.isEmpty()
                && openListNames.stream().noneMatch(name -> name.equalsIgnoreCase(RECOMMENDED_MERGING_STATE));
        List<String> validationListNames =
                canCreateMergingList ? withOptionalListName(openListNames, RECOMMENDED_MERGING_STATE) : openListNames;
        String blockedState = "-".equals(request.blockedState())
                ? null
                : blank(request.blockedState()) ? defaultBlockedState(openListNames) : request.blockedState();
        String inProgressState =
                request.detectInProgressState() ? defaultInProgressState(openListNames) : request.inProgressState();
        String mergingState =
                request.githubIntegration().enabled() ? defaultMergingState(validationListNames, terminalStates) : null;
        boolean shouldCreateMergingList = canCreateMergingList && !blank(mergingState);
        String reviewState = defaultReviewState(openListNames);
        validateConfiguredList("in-progress", "in_progress", inProgressState, openListNames);
        validateConfiguredList("merging", "merging", mergingState, validationListNames);
        validateConfiguredLists("active", "active", activeStates, validationListNames);
        validateConfiguredLists("terminal", "terminal", terminalStates, openListNames);
        validateConfiguredList("blocked", "blocked", blockedState, openListNames);
        activeStates = withOptionalActiveState(activeStates, inProgressState);
        activeStates = withOptionalActiveState(activeStates, mergingState);
        ensureWorkflowWritable(request.workflowPath(), request.force());
        int serverPort = resolveServerPort(request.workflowPath(), request.serverPort(), request.force());
        if (shouldCreateMergingList) {
            postMap(
                    request.endpoint(),
                    "lists",
                    orderedMap("name", RECOMMENDED_MERGING_STATE, "idBoard", resolvedBoardId, "pos", "bottom"),
                    request.credentials());
            openListNames.add(RECOMMENDED_MERGING_STATE);
        }

        String boardKey = boardKey(board);
        writeWorkflow(
                request.workflowPath(),
                request.force(),
                workflowTemplate(
                        boardKey,
                        activeStates,
                        terminalStates,
                        inProgressState,
                        reviewState,
                        blockedState,
                        mergingState,
                        request.workspaceRoot(),
                        serverPort,
                        request.maxConcurrentAgents(),
                        request.githubIntegration().enabled(),
                        codexModelDefaultsForWorkflow(request.workflowPath())));

        return new ImportBoardResult(
                resolvedBoardId,
                boardKey,
                requiredString(board, "name"),
                string(board.get("url")),
                openListNames,
                activeStates,
                terminalStates,
                inProgressState,
                blockedState,
                request.workflowPath(),
                serverPort);
    }

    private Map<String, Object> getMap(
            URI endpoint, String path, Map<String, String> query, TrelloCredentials credentials) {
        return request("GET", endpoint, path, query, credentials, MAP_TYPE);
    }

    private CodexModelSelectionDefaults codexModelSelectionDefaults() {
        return Objects.requireNonNull(codexModelSelectionDefaults.get(), CODEX_MODEL_DEFAULTS_LABEL);
    }

    private CodexModelDefaults codexModelDefaults() {
        return codexModelSelectionDefaults().defaults();
    }

    CodexModelDefaults resolvedCodexModelDefaults() {
        return codexModelDefaults();
    }

    CodexModelSelectionDefaults resolvedCodexModelSelectionDefaults() {
        return codexModelSelectionDefaults();
    }

    CodexModelDefaults codexModelDefaultsForWorkflow(Path workflowPath) {
        return workflowCodexModelDefaults(workflowPath, codexModelSelectionDefaults())
                .defaults();
    }

    private WorkflowCodexModelDefaults workflowCodexModelDefaults(
            Path workflowPath, CodexModelSelectionDefaults selectionDefaults) {
        CodexModelDefaults defaults = selectionDefaults.defaults();
        boolean hasOverrides = codexModelOverride.isPresent() || codexReasoningEffortOverride.isPresent();
        try {
            WorkflowCodexModelDefaults effective = readWorkflowFrontMatter(workflowPath)
                    .map(frontMatter -> preserveExistingCodexModelDefaults(frontMatter, defaults))
                    .orElseGet(() -> new WorkflowCodexModelDefaults(defaults, false, false));
            return hasOverrides
                    ? new WorkflowCodexModelDefaults(
                            applyCodexModelOverrides(
                                    effective.defaults(),
                                    selectionDefaults,
                                    effective.preserveConfiguredReasoningEffort()),
                            effective.preserveConfiguredReasoningEffort(),
                            effective.preserveReasoningEffortOmission())
                    : effective;
        } catch (TrelloBoardSetupException e) {
            CodexModelDefaults effective =
                    hasOverrides ? applyCodexModelOverrides(defaults, selectionDefaults, false) : defaults;
            return new WorkflowCodexModelDefaults(effective, false, false);
        }
    }

    CodexModelSelectionDefaults codexModelSelectionDefaultsForWorkflow(Path workflowPath) {
        CodexModelSelectionDefaults selectionDefaults = codexModelSelectionDefaults();
        WorkflowCodexModelDefaults workflowDefaults = workflowCodexModelDefaults(workflowPath, selectionDefaults);
        return selectionDefaults.withDefaults(
                workflowDefaults.defaults(),
                workflowDefaults.preserveConfiguredReasoningEffort(),
                workflowDefaults.preserveReasoningEffortOmission());
    }

    private CodexModelDefaults applyCodexModelOverrides(
            CodexModelDefaults defaults,
            CodexModelSelectionDefaults selectionDefaults,
            boolean preserveConfiguredReasoningEffort) {
        String model = codexModelOverride.orElseGet(defaults::model);
        return CodexModelDefaults.partial(
                model,
                codexReasoningEffortOverride
                        .or(() -> codexModelOverride.flatMap(
                                modelOverride -> selectionDefaults.reasoningEffortForExplicitModelOverride(
                                        modelOverride, defaults, preserveConfiguredReasoningEffort)))
                        .orElseGet(defaults::reasoningEffort));
    }

    private static WorkflowCodexModelDefaults preserveExistingCodexModelDefaults(
            Map<String, Object> frontMatter, CodexModelDefaults defaults) {
        Object codex = frontMatter.get("codex");
        if (!(codex instanceof Map<?, ?> codexMap)) {
            return new WorkflowCodexModelDefaults(defaults, false, false);
        }
        String model = string(codexMap.get("model"));
        String reasoningEffort = string(codexMap.get("reasoning_effort"));
        if (blank(model) && blank(reasoningEffort)) {
            return new WorkflowCodexModelDefaults(CodexModelDefaults.omittedFirstClassFields(), false, true);
        }
        return new WorkflowCodexModelDefaults(
                CodexModelDefaults.partial(model, reasoningEffort), !blank(reasoningEffort), blank(reasoningEffort));
    }

    private record WorkflowCodexModelDefaults(
            CodexModelDefaults defaults,
            boolean preserveConfiguredReasoningEffort,
            boolean preserveReasoningEffortOmission) {}

    private static Supplier<CodexModelSelectionDefaults> codexModelSelectionDefaultsSupplier(
            CodexModelSelectionDefaults codexModelSelectionDefaults) {
        Objects.requireNonNull(codexModelSelectionDefaults, CODEX_MODEL_DEFAULTS_LABEL);
        return () -> codexModelSelectionDefaults;
    }

    private List<Map<String, Object>> getList(
            URI endpoint, String path, Map<String, String> query, TrelloCredentials credentials) {
        return request("GET", endpoint, path, query, credentials, LIST_MAP_TYPE);
    }

    private Map<String, Object> postMap(
            URI endpoint, String path, Map<String, String> query, TrelloCredentials credentials) {
        return request("POST", endpoint, path, query, credentials, MAP_TYPE);
    }

    private <T> T request(
            String method,
            URI endpoint,
            String path,
            Map<String, String> query,
            TrelloCredentials credentials,
            TypeReference<T> type) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri(endpoint, path, query))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json")
                    .header("Authorization", TrelloClient.authorization(credentials.apiKey(), credentials.apiToken()));
            HttpRequest request =
                    switch (method) {
                        case "GET" -> builder.GET().build();
                        case "POST" ->
                            builder.POST(HttpRequest.BodyPublishers.noBody()).build();
                        default -> throw new IllegalArgumentException("Unsupported method: " + method);
                    };
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (isSuccessfulStatus(response.statusCode())) {
                return json.readValue(response.body(), type);
            }
            throw statusException(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new TrelloBoardSetupException("trello_api_request", "Trello request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrelloBoardSetupException("trello_api_request", "Trello request interrupted", e);
        }
    }

    private static URI uri(URI endpoint, String path, Map<String, String> query) {
        String normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        if (normalizedPath.contains("..") || normalizedPath.contains("?") || normalizedPath.contains("#")) {
            throw new TrelloBoardSetupException("setup_invalid_path", "Invalid Trello API path");
        }
        String queryString = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
        String base = endpoint.toString().replaceAll("/+$", "");
        return URI.create(base + "/" + normalizedPath + (queryString.isBlank() ? "" : "?" + queryString));
    }

    private static void writeWorkflow(Path workflowPath, boolean force, String workflow) {
        Path absolute = ensureWorkflowWritable(workflowPath, force);
        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (force) {
                Files.writeString(
                        absolute,
                        workflow,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            } else {
                Files.writeString(absolute, workflow, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException e) {
            throw new TrelloBoardSetupException(
                    "setup_workflow_write_failed", "Could not write workflow file: " + absolute, e);
        }
    }

    private static Path resolveNewBoardWorkflowPath(NewBoardRequest request) {
        Path requested = request.workflowPath();
        if (request.force()
                || !request.useBoardNameWorkflowFallback()
                || !Files.exists(requested.toAbsolutePath().normalize())) {
            return requested;
        }

        Path parent = requested.getParent();
        String slug = slugify(request.boardName());
        Path candidate = resolveSibling(parent, "WORKFLOW." + slug + ".md");
        for (int suffix = 2; Files.exists(candidate.toAbsolutePath().normalize()); suffix++) {
            candidate = resolveSibling(parent, "WORKFLOW." + slug + "-" + suffix + ".md");
        }
        return candidate;
    }

    private static Path resolveSibling(Path parent, String fileName) {
        return parent == null ? Path.of(fileName) : parent.resolve(fileName);
    }

    private int resolveServerPort(Path workflowPath, Integer requestedPort, boolean force) {
        if (requestedPort != null) {
            ensureServerPortAvailable(workflowPath, requestedPort);
            return requestedPort;
        }

        Path absolute = workflowPath.toAbsolutePath().normalize();
        if (force) {
            return replaceableWorkflowServerPortReservation(absolute)
                    .filter(existingPort ->
                            workflowServerPortConflict(absolute, existingPort).isEmpty())
                    .orElseGet(() -> nextAvailableWorkflowServerPort(absolute));
        }
        return nextAvailableWorkflowServerPort(absolute);
    }

    private void ensureServerPortAvailable(Path workflowPath, int requestedPort) {
        if (requestedPort == 0) {
            return;
        }

        Path target = workflowPath.toAbsolutePath().normalize();
        workflowServerPortConflict(target, requestedPort).ifPresent(conflictingWorkflow -> {
            throw new TrelloBoardSetupException(
                    "setup_server_port_conflict",
                    "--server-port %d is already used by %s".formatted(requestedPort, conflictingWorkflow));
        });
    }

    private Optional<Path> workflowServerPortConflict(Path target, int requestedPort) {
        for (Path candidate : siblingWorkflowFiles(target)) {
            if (workflowServerPortReservation(candidate)
                    .filter(port -> port == requestedPort)
                    .isPresent()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private int nextAvailableWorkflowServerPort(Path workflowPath) {
        Set<Integer> reservedPorts = new HashSet<>();
        for (Path candidate : siblingWorkflowFiles(workflowPath)) {
            workflowServerPortReservation(candidate).ifPresent(reservedPorts::add);
        }

        for (int port = DEFAULT_SERVER_PORT; port <= 65535; port++) {
            if (!reservedPorts.contains(port)) {
                return port;
            }
        }
        throw new TrelloBoardSetupException(
                "setup_server_port_unavailable",
                "No workflow HTTP port is available between %d and 65535".formatted(DEFAULT_SERVER_PORT));
    }

    private static List<Path> siblingWorkflowFiles(Path workflowPath) {
        Path absolute = workflowPath.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return List.of();
        }
        try (var paths = Files.list(parent)) {
            return paths.map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> !path.equals(absolute))
                    .toList();
        } catch (IOException e) {
            throw new TrelloBoardSetupException(
                    "setup_workflow_scan_failed", "Could not scan workflow directory: " + parent, e);
        }
    }

    private Optional<Integer> workflowServerPortReservation(Path workflowPath) {
        return readWorkflowFrontMatter(workflowPath)
                .filter(TrelloBoardSetup::hasRealTrelloBoardId)
                .flatMap(workflowConfig -> workflowServerPortReservation(workflowConfig, workflowPath));
    }

    private Optional<Integer> workflowServerPortReservation(Map<String, Object> workflowConfig, Path workflowPath) {
        Object server = workflowConfig.get("server");
        if (server instanceof Map<?, ?> serverMap && serverMap.containsKey("port")) {
            int port = parseServerPort(serverMap.get("port"), workflowPath);
            return port == 0 ? Optional.empty() : Optional.of(port);
        }
        return Optional.of(DEFAULT_SERVER_PORT);
    }

    private Optional<Integer> replaceableWorkflowServerPortReservation(Path workflowPath) {
        try {
            return workflowServerPortReservation(workflowPath);
        } catch (TrelloBoardSetupException e) {
            return Optional.empty();
        }
    }

    private Optional<Map<String, Object>> readWorkflowFrontMatter(Path workflowPath) {
        if (!Files.isRegularFile(workflowPath)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(workflowPath, StandardCharsets.UTF_8);
            return readYamlFrontMatter(text);
        } catch (IOException e) {
            throw new TrelloBoardSetupException(
                    "setup_workflow_scan_failed", "Could not read workflow file: " + workflowPath, e);
        }
    }

    private Optional<Map<String, Object>> readYamlFrontMatter(String text) throws IOException {
        if (!text.startsWith("---")) {
            return Optional.empty();
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return Optional.empty();
        }
        int end = text.indexOf("\n---", firstLineEnd + 1);
        if (end < 0) {
            return Optional.empty();
        }
        return Optional.of(yaml.readValue(text.substring(firstLineEnd + 1, end), MAP_TYPE));
    }

    private static boolean hasRealTrelloBoardId(Map<String, Object> frontMatter) {
        Object tracker = frontMatter.get("tracker");
        if (!(tracker instanceof Map<?, ?> trackerMap)) {
            return false;
        }
        Object kind = trackerMap.get("kind");
        Object boardId = trackerMap.get("board_id");
        return "trello".equals(string(kind))
                && !blank(string(boardId))
                && !"your-board-id-or-shortlink".equals(string(boardId));
    }

    private static int parseServerPort(Object value, Path workflowPath) {
        int port;
        try {
            port = switch (value) {
                case Number number -> number.intValue();
                case String text -> Integer.parseInt(text.trim());
                default ->
                    throw new TrelloBoardSetupException(
                            "setup_invalid_server_port", "Workflow file has an invalid server.port: " + workflowPath);
            };
        } catch (NumberFormatException e) {
            throw new TrelloBoardSetupException(
                    "setup_invalid_server_port", "Workflow file has an invalid server.port: " + workflowPath, e);
        }
        validateServerPort(port, "server.port in " + workflowPath);
        return port;
    }

    private static void validateOptionalServerPort(Integer port, String label) {
        if (port != null) {
            validateServerPort(port, label);
        }
    }

    private static void validateServerPort(int port, String label) {
        LocalPort.validateWorkflowServerPort(port, label);
    }

    private static Path ensureWorkflowWritable(Path workflowPath, boolean force) {
        Path absolute = workflowPath.toAbsolutePath().normalize();
        if (Files.exists(absolute) && !force) {
            throw new TrelloBoardSetupException(
                    "setup_workflow_exists",
                    "Workflow file already exists: " + absolute + "\nRe-run with --force to overwrite it.");
        }
        return absolute;
    }

    private static String workflowTemplate(
            String boardId,
            List<String> activeStates,
            List<String> terminalStates,
            String inProgressState,
            String reviewState,
            String blockedState,
            String mergingState,
            Path workspaceRoot,
            int serverPort,
            int maxAgents,
            boolean githubEnabled,
            CodexModelDefaults codexModelDefaults) {
        String doneState = landingDoneState(terminalStates);
        List<String> handoffStates =
                allowedMoveStates(inProgressState, reviewState, blockedState, mergingState, doneState);
        return """
                ---
                tracker:
                  kind: trello
                  api_key: $TRELLO_API_KEY
                  api_token: $TRELLO_API_TOKEN
                  board_id: %s
                  active_states:
                %s
                %s
                %s
                  blocker_enforced_states:
                %s
                  terminal_states:
                %s
                workspace:
                  root: %s
                server:
                  port: %d
                polling:
                  interval_ms: %d
                %s
                agent:
                  max_concurrent_agents: %d
                codex:
                  command: %s
                %s
                  approval_policy: never
                  turn_timeout_ms: %d
                  read_timeout_ms: %d
                  stall_timeout_ms: %d
                ---
                # Trello Card

                You are working on {{ card.identifier }}: {{ card.title }}.

                ## Description

                {{ card.description }}

                ## Trello Comments

                {%% for comment in card.comments %%}
                - {{ comment.created_at }}{%% if comment.author %%} {{ comment.author }}{%% endif %%}: {{ comment.text }}
                {%% endfor %%}

                %s

                %s

                Read the Trello description carefully, inspect the repository, make the smallest maintainable change,
                run relevant verification, and leave the workspace in a reviewable state.

                ## Repository Checkout Policy

                Do implementation work inside the current per-card workspace or a writable checkout under it.
                Do not edit a shared host checkout directly unless the card explicitly asks you to work there and
                that checkout is writable.

                If the Trello card names only a repository URL, create or reuse a writable checkout in a subdirectory
                of the current workspace. Prefer cloning from a readable matching local checkout under an allowed host
                path, then set the checkout's `origin` remote to the repository URL when needed. If no matching local
                checkout is readable, clone the repository URL into a new subdirectory named after the repository.

                If the Trello card names a specific local path or checkout, inspect it as source context. When it is
                not writable, clone from that readable local path into a subdirectory of the current workspace and work
                in the clone instead of blocking. Block only when the path is not readable, the repository cannot be
                cloned into a writable workspace subdirectory, or required repository/auth context is unavailable. If
                Git rejects a readable
                local checkout because of safe-directory ownership checks, add only that source checkout to the
                current user's Git safe directories with `git config --global --add safe.directory <source-checkout>`,
                then retry a read-only clone with `git clone --no-hardlinks <source-checkout> <workspace-checkout>`.
                After cloning from a local checkout, do not inherit the source checkout's current branch as the task
                base. Start the task branch from the repository's default branch when it is discoverable, usually
                `origin/main`, unless the Trello card explicitly asks for a different base.

                %s

                %s

                %s

                %s

                %s

                %s

                %s

                %s

                %s

                %s

                %s

                Card URL: {{ card.url }}
                """
                .formatted(
                        yamlScalar(boardId),
                        yamlList(activeStates),
                        optionalTrackerStateYaml("in_progress_state", inProgressState),
                        optionalTrackerStateYaml("blocked_state", blockedState),
                        yamlList(activeStates),
                        yamlList(withSystemTerminalStates(terminalStates)),
                        yamlScalar(workspaceRoot.toString()),
                        serverPort,
                        ConfigDefaults.DEFAULT_POLLING_INTERVAL_MS,
                        trelloToolsYaml(handoffStates),
                        maxAgents,
                        ConfigDefaults.DEFAULT_CODEX_COMMAND,
                        codexModelYaml(codexModelDefaults),
                        ConfigDefaults.DEFAULT_CODEX_TURN_TIMEOUT_MS,
                        ConfigDefaults.DEFAULT_CODEX_READ_TIMEOUT_MS,
                        ConfigDefaults.DEFAULT_CODEX_STALL_TIMEOUT_MS,
                        workpadPrompt(!handoffStates.isEmpty()),
                        repositorySkillsPrompt(githubEnabled),
                        operatingPosturePrompt(!handoffStates.isEmpty()),
                        routingPrompt(
                                activeStates, terminalStates, inProgressState, reviewState, blockedState, mergingState),
                        executionPrompt(
                                reviewState,
                                blockedDestination(reviewState, blockedState),
                                !handoffStates.isEmpty(),
                                githubEnabled),
                        validationPrompt(!handoffStates.isEmpty(), reviewState),
                        prPublicationPrompt(
                                reviewState,
                                blockedDestination(reviewState, blockedState),
                                !handoffStates.isEmpty(),
                                githubEnabled),
                        prFeedbackPrompt(
                                reviewState,
                                blockedDestination(reviewState, blockedState),
                                !handoffStates.isEmpty(),
                                githubEnabled),
                        reworkPrompt(activeStates, reviewState, mergingState, !handoffStates.isEmpty(), githubEnabled),
                        landingPrompt(
                                mergingState,
                                reviewState,
                                doneState,
                                blockedDestination(reviewState, blockedState),
                                githubEnabled),
                        pickupPrompt(activeStates, inProgressState, mergingState),
                        completionBarPrompt(
                                reviewState,
                                blockedDestination(reviewState, blockedState),
                                !handoffStates.isEmpty(),
                                githubEnabled),
                        handoffPrompt(reviewState, blockedState, !handoffStates.isEmpty(), githubEnabled));
    }

    private static String repositorySkillsPrompt(boolean githubEnabled) {
        if (!githubEnabled) {
            return """
                    ## Repository Skills

                    Use the workspace-local skills under `.codex/skills/symphony-trello-*` when they fit.
                    Symphony installs these skill files in the per-card workspace after workspace sync hooks
                    and before Codex starts, so they are available even when the target repository does not
                    provide its own skills:

                    - `%s` for workpad updates.
                    - `%s` for Trello pickup, review, blocked, and done handoff.
                    - `%s` and `%s` for branch and commit hygiene.
                    - `%s` when diagnosing a stuck or retrying run.
                    """
                    .formatted(
                            skillPath("trello-workpad"),
                            skillPath("trello-handoff"),
                            skillPath("repo-sync"),
                            skillPath("commit"),
                            skillPath("debug"))
                    .stripTrailing();
        }
        return """
                ## Repository Skills

                Use the workspace-local skills under `.codex/skills/symphony-trello-*` when they fit.
                Symphony installs these skill files in the per-card workspace after workspace sync hooks
                and before Codex starts, so they are available even when the target repository does not
                provide its own skills:

                - `%s` for workpad updates.
                - `%s` for Trello pickup, review, blocked, merge, and done handoff.
                - `%s` when a pull request or branch is involved.
                - `%s`, `%s`, and `%s` for branch, commit, and PR hygiene.
                - `%s` only when this workflow says the current Trello list is Merging.
                - `%s` when diagnosing a stuck or retrying run.
                """
                .formatted(
                        skillPath("trello-workpad"),
                        skillPath("trello-handoff"),
                        skillPath("review-sweep"),
                        skillPath("repo-sync"),
                        skillPath("commit"),
                        skillPath("push-pr"),
                        skillPath("land"),
                        skillPath("debug"))
                .stripTrailing();
    }

    private static String codexModelYaml(CodexModelDefaults codexModelDefaults) {
        if (!codexModelDefaults.firstClassFieldsSupported()) {
            return "";
        }
        StringBuilder yaml = new StringBuilder();
        if (!blank(codexModelDefaults.model())) {
            yaml.append("  model: ")
                    .append(yamlScalar(codexModelDefaults.model()))
                    .append('\n');
        }
        if (!blank(codexModelDefaults.reasoningEffort())) {
            yaml.append("  reasoning_effort: ")
                    .append(yamlScalar(codexModelDefaults.reasoningEffort()))
                    .append('\n');
        }
        return yaml.toString();
    }

    private static String workpadPrompt(boolean workpadToolEnabled) {
        if (!workpadToolEnabled) {
            return "";
        }
        return """
                ## Codex Workpad

                Maintain one Trello workpad comment for this card by calling trello_upsert_workpad. Reuse the
                existing comment that starts with "## Codex Workpad"; do not create separate progress comments.
                Keep it current with the plan, acceptance criteria, progress, validation evidence, blockers, and
                handoff notes. Do not include private host paths; use sanitized workspace or repository names when
                context is needed.
                """;
    }

    private static String skillPath(String skillName) {
        return CodexSkillCatalog.installedSkillPath(skillName);
    }

    private static String operatingPosturePrompt(boolean workpadToolEnabled) {
        String workpadText = workpadToolEnabled
                ? "Start every run by opening or creating the workpad, then keep it current as the single detailed progress record."
                : "Keep progress notes in the final response because Trello workpad tools are disabled.";
        return """
                ## Operating Posture

                This is an unattended orchestration run. Do not ask a human to perform routine follow-up actions.
                Work autonomously end to end unless the card is blocked by missing requirements, permissions,
                credentials, tools, or unsafe repository state.

                Start by determining the current Trello list and route from that list. %s
                Spend extra effort up front on planning and validation design before implementation. Reproduce bugs
                or capture a concrete current-state signal before changing behavior. When meaningful out-of-scope
                improvements are discovered, record them as separate follow-up work instead of expanding this card.

                Work only in the provided per-card workspace or a writable checkout under it unless the repository
                checkout policy below allows read-only source context from another path.
                """
                .formatted(workpadText)
                .stripTrailing();
    }

    private static String executionPrompt(
            String reviewState, String blockedDestination, boolean workpadToolEnabled, boolean githubEnabled) {
        String reviewHandoff = blank(reviewState) ? "human review" : quote(reviewState);
        String blockedText = blank(blockedDestination)
                ? "record a blocker in the final response"
                : "move the card to " + quote(blockedDestination);
        String planningRecord = workpadToolEnabled
                ? "Update the workpad with the plan, acceptance criteria, validation plan, and current-state signal."
                : "Record the plan, acceptance criteria, validation plan, and current-state signal for the final response.";
        String progressRecord = workpadToolEnabled
                ? "Keep the workpad checklist current when scope, risks, validation, or blockers change."
                : "Keep final-response notes current when scope, risks, validation, or blockers change.";
        String duplicateProgress = workpadToolEnabled
                ? """

                Do not leave completed work unchecked in the workpad. Do not create duplicate progress comments when
                the workpad contains the details.
                """
                : """

                Do not report completed work as ready until the final response contains the implementation, validation,
                repository, and blocker details that apply to the card.
                """;
        String publicationStep = githubEnabled
                ? "Publish or update a pull request when repository changes should be reviewed."
                : "For repository changes, create a local commit or patch according to the card and workflow.";
        return """
                ## Execution Flow

                1. Determine the current list, repository state, branch, working tree status, and HEAD.
                2. Read the full Trello card description and all rendered Trello comments before editing.
                3. %s
                4. Sync with the repository default branch before implementation when a Git repository is involved.
                5. Implement the smallest maintainable change that satisfies the card.
                6. %s
                7. Run the validation required by the card and the repository.
                8. Commit logical changes with a clear message when repository files changed.
                9. %s
                10. Only move to %s after the completion bar below is met. If the work cannot safely reach the
                    completion bar, %s.
                %s
                """
                .formatted(
                        planningRecord, progressRecord, publicationStep, reviewHandoff, blockedText, duplicateProgress)
                .stripTrailing();
    }

    private static String optionalTrackerStateYaml(String key, String value) {
        if (blank(value)) {
            return "";
        }
        return "  " + key + ": " + yamlScalar(value);
    }

    private static String trelloToolsYaml(List<String> handoffStates) {
        if (handoffStates.isEmpty()) {
            return """
                    trello_tools:
                      enabled: false
                      allow_writes: false
                    """
                    .stripTrailing();
        }
        return """
                trello_tools:
                  enabled: true
                  allow_writes: true
                  allowed_move_list_names:
                %s
                  allow_comments: true
                  allow_checklists: false
                  allow_url_attachments: false
                """
                .formatted(yamlList(handoffStates))
                .stripTrailing();
    }

    private static String validationPrompt(boolean workpadToolEnabled, String reviewState) {
        String evidenceDestination = workpadToolEnabled
                ? "the Codex workpad and final handoff comment"
                : "the final Codex response or handoff comment";
        String reviewHandoff = blank(reviewState) ? "a ready-for-review handoff" : quote(reviewState);
        return """
                ## Acceptance Criteria And Validation

                Before changing code, extract the card-specific acceptance criteria from the title, description,
                and Trello comments. Treat any card-authored `Validation`, `Test Plan`, or `Testing` section as
                required. If the card is a bug or behavior change, first capture a concrete current-state signal:
                reproduce the failure, record the current output, or explain why reproduction is not possible.

                Track the acceptance criteria, required validation, current-state signal, and final validation
                evidence in %s. Verification evidence must be specific to this card; do not hand off with only a
                generic "tests passed" statement. Temporary local proof edits are allowed only when they improve
                confidence, are reverted before commit, and are documented as proof steps.

                Broad validation failures that are clearly unrelated to the card do not automatically block handoff
                when card-specific validation passed. Record the failing command, why the failure is unrelated, and
                the narrower passing validation that still gives confidence in the change.

                If required validation cannot be performed because auth, files, tools, or environment access are
                missing, treat the work as blocked. Do not move the card to %s until the blocker is fixed or a human
                explicitly changes the requirement.
                """
                .formatted(evidenceDestination, reviewHandoff)
                .stripTrailing();
    }

    private static String prPublicationPrompt(
            String reviewState, String blockedDestination, boolean workpadToolEnabled, boolean githubEnabled) {
        if (!githubEnabled) {
            String reviewHandoff = blank(reviewState) ? "human review" : quote(reviewState);
            String localEvidence = workpadToolEnabled ? "the workpad and handoff comment" : "the final response";
            return """
                    ## Local And Non-GitHub Repository Work

                    This workflow does not have GitHub PR integration configured. Do not create pull requests,
                    do not require GitHub auth, and do not refer to a landing approval list. For repository-changing work,
                    make the smallest maintainable local commit when a Git repository is available, run relevant
                    validation, and record the branch, commit, workspace path, and validation evidence in %s before
                    moving the card to %s.

                    If the card explicitly asks for a patch instead of a commit, leave the patch or changed files in
                    the workspace and record the exact location. If the work needs a remote Git provider or permission
                    that is not available in this non-GitHub workflow, treat that as a blocker instead of inventing a
                    GitHub PR flow.
                    """
                    .formatted(localEvidence, reviewHandoff)
                    .stripTrailing();
        }
        String reviewHandoff = blank(reviewState) ? "human review" : quote(reviewState);
        String blockedText = blank(blockedDestination)
                ? "record a blocker in the final response"
                : "move the card to " + quote(blockedDestination);
        String prEvidence = workpadToolEnabled ? "the workpad and the visible handoff comment" : "the final response";
        String localEvidence = workpadToolEnabled ? "the workpad and handoff comment" : "the final response";
        return """
                ## Pull Request Publication

                For repository-changing work, %s means a human can review a pull request. Before moving the card
                there, use `%s` and `%s` to commit, push,
                and create or update the PR for the current branch. Create a ready-for-review, non-draft PR by
                default. Create a draft PR only when the Trello card explicitly asks for a draft PR. Add the PR
                URL to %s. In Trello-visible text, put PR links on their own line as `PR: <https://github.com/owner/repo/pull/123>`;
                do not write a bare PR URL followed by punctuation.

                Before creating commits for PR-bound work, reuse the task checkout's local Git author only when both
                `user.name` and `user.email` are already configured and `symphony-trello.github-author-verified` is
                `true`. Otherwise, resolve the authenticated GitHub login with `gh api user` and configure the
                task checkout's Git author name from that login. Use the public GitHub email when available, otherwise
                fetch the account's actual GitHub noreply email with `gh api user/emails`. The noreply lookup needs
                GitHub CLI auth with the `user:email` scope. Do not guess a noreply address format. If the identity
                cannot be resolved when lookup is needed, treat PR-bound work as blocked before committing instead
                of using a generic fallback author.

                Before moving to "Human Review", verify every commit in the PR's merge-base-to-HEAD range is
                authored as the authenticated GitHub login and email. This includes commits that were already present
                when continuing an existing PR. A PR with any commit authored as `Codex <codex@openai.com>` or
                another generic identity is not ready for Human Review. For the current non-default task PR
                branch, this workflow allows an author-only history rewrite followed by `git push
                --force-with-lease` to fix wrong-author commits. Do not rewrite the default branch, an unnamed
                branch, or a branch that contains unrelated human-owned work; move the card to "Blocked" with
                the exact mismatch instead.

                This PR requirement applies when the card asks for code, documentation, configuration, tests, or
                other version-controlled repository changes. It does not apply when the card explicitly asks for a
                local-only investigation, says not to push, or requires no repository change. In those cases, explain
                the local-only result and the workspace/branch/commit evidence in %s.

                If GitHub auth, push permission, branch protection, or repository policy prevents a required PR, try
                the fallback strategies in `%s`. If a PR is still required and cannot be
                created or updated, %s with the exact blocker instead of moving to %s.
                """
                .formatted(
                        reviewHandoff,
                        skillPath("commit"),
                        skillPath("push-pr"),
                        prEvidence,
                        localEvidence,
                        skillPath("push-pr"),
                        blockedText,
                        reviewHandoff)
                .stripTrailing();
    }

    private static String prFeedbackPrompt(
            String reviewState, String blockedDestination, boolean workpadToolEnabled, boolean githubEnabled) {
        if (!githubEnabled) {
            return """
                    ## Non-GitHub Review Feedback

                    If a reviewed card is moved back to an active list, reread the Trello card description and Trello
                    comments, then continue from the existing workspace or local branch when possible. Do not require
                    PR comments, PR checks, or GitHub review state in this workflow.
                    """
                    .stripTrailing();
        }
        String reviewHandoff = blank(reviewState) ? "ready-for-review handoff" : quote(reviewState);
        String blockedText = blank(blockedDestination)
                ? "treat the card as blocked"
                : "move the card to " + quote(blockedDestination);
        String checkCaveatDestination = workpadToolEnabled ? "the workpad and handoff comment" : "the final response";
        return """
                ## Pull Request Feedback Sweep

                If the Trello description, Trello comments, current branch, repository context, or open PR list
                identifies an associated pull request, use `%s` before moving the
                card to %s or landing from Merging. Cards without PR context do not need GitHub review checks.

                The sweep must check top-level PR comments, inline review comments, GitHub review threads and whether
                each thread is resolved, review states and summaries, CI/check status, and Codex review issue comments
                when present. Every actionable human, bot, or Codex review comment is blocking until it is addressed
                with code, tests, docs, or PR metadata, or answered with a justified response in the right thread. Do
                not decline correctness feedback without concrete validation. Resolve addressed GitHub review threads
                when the authenticated GitHub user is allowed to resolve them; if a thread cannot be resolved because
                of permissions, API limitations, or ambiguity, record that clearly and do not claim it was resolved.

                Classify PR checks before deciding handoff:
                - If a failing check is related to the card's changes, the current branch, or can be reproduced by
                  equivalent local validation, keep the card active, fix the failure, push again, and repeat the
                  sweep.
                - If a related CI check fails, rerun that check or the closest local equivalent. If it fails locally,
                  fix it before handoff. If it passes locally and the failure looks flaky after a reasonable refresh
                  or rerun, hand off with the local evidence and flaky-check caveat.
                - If checks are pending or stale, wait, refresh, or rerun them while the card remains active unless
                  the workflow reaches a true external blocker.
                - If CI cannot run because of external quota or infrastructure limits, run equivalent local CI checks.
                  Hand off only when those checks pass or only have failures clearly unrelated to the card.
                - If CI fails for a reason clearly unrelated to the current card, do not spend time reproducing that
                  unrelated failure locally. Record why it is unrelated and hand off when the card-specific validation
                  and related checks are clean.
                - Record the check state, local commands used when local validation was required, and any unavailable,
                  flaky, or unrelated check caveat in %s.

                After feedback-driven changes, rerun the relevant validation and repeat the sweep until no
                actionable feedback remains. For landing from Merging, exact and unambiguous feedback that was
                added before the card entered %s and addressed with clean checks may land without returning to
                %s; material fixups, ambiguity, or unverifiable changes require renewed %s. If GitHub auth, PR
                discovery, or review data are
                unavailable for a PR-backed card and no documented fallback can provide the required signal, %s
                instead of handing it off.
                """
                .formatted(
                        skillPath("review-sweep"),
                        reviewHandoff,
                        checkCaveatDestination,
                        quote("Merging"),
                        reviewHandoff,
                        reviewHandoff,
                        blockedText)
                .stripTrailing();
    }

    private static String reworkPrompt(
            List<String> activeStates,
            String reviewState,
            String mergingState,
            boolean workpadToolEnabled,
            boolean githubEnabled) {
        String reviewHandoff = blank(reviewState) ? "human review" : quote(reviewState);
        List<String> implementationStates = implementationActiveStates(activeStates, mergingState);
        String activeText =
                implementationStates.isEmpty() ? "an active implementation list" : quotedList(implementationStates);
        if (!githubEnabled) {
            String contextSources =
                    workpadToolEnabled ? "new Trello comments and the existing workpad" : "new Trello comments";
            String reworkPlan = workpadToolEnabled
                    ? "update the workpad with a short rework plan"
                    : "record a short rework plan for the final response";
            String reworkEvidence = workpadToolEnabled
                    ? "update the existing workpad with the rework evidence, and add one concise handoff comment. Do not create\n"
                            + "duplicate progress summary comments when the workpad already contains the details."
                    : "include the rework evidence in the final response.";
            return """
                    ## Rework From Human Review

                    If a human moves a reviewed card from %s back to %s, treat the next run as rework. Before changing
                    code, reread the full card description and %s.

                    Identify what changed since the last handoff and %s that says what will be done differently.
                    Preserve completed work that still satisfies the current card; do not restart from scratch or create
                    a new branch unless the Trello card or a human explicitly asks for a reset.

                    Before returning the card to %s, rerun card-specific validation and %s
                    """
                    .formatted(reviewHandoff, activeText, contextSources, reworkPlan, reviewHandoff, reworkEvidence)
                    .stripTrailing();
        }
        String contextSources = workpadToolEnabled
                ? "new Trello comments, existing workpad, linked PR comments,"
                : "new Trello comments, linked PR comments,";
        String reworkPlan = workpadToolEnabled
                ? "update the workpad with a short rework plan"
                : "record a short rework plan for the final response";
        String reworkEvidence = workpadToolEnabled
                ? "update the existing workpad with the rework evidence, and add one concise handoff comment. Do not create\n"
                        + "duplicate progress summary comments when the workpad already contains the details."
                : "include the rework evidence in the final response.";
        String resetRestrictions = workpadToolEnabled
                ? "close the existing PR, delete the workpad, or create a new branch"
                : "close the existing PR or create a new branch";
        return """
                ## Rework From Human Review

                If a human moves a reviewed card from %s back to %s, treat the next run as rework. Before changing
                code, reread the full card description, %s inline PR review comments, and current PR/check state.

                Identify what changed since the last handoff and %s that says what will be done differently.
                Preserve completed work that still satisfies the current card;
                do not restart from scratch, %s unless the Trello card or a human explicitly asks for a reset.

                Before returning the card to %s, rerun the card-specific validation and PR feedback sweep, %s
                """
                .formatted(
                        reviewHandoff,
                        activeText,
                        contextSources,
                        reworkPlan,
                        resetRestrictions,
                        reviewHandoff,
                        reworkEvidence)
                .stripTrailing();
    }

    private static String landingPrompt(
            String mergingState,
            String reviewState,
            String doneState,
            String blockedDestination,
            boolean githubEnabled) {
        if (!githubEnabled) {
            return """
                    ## Landing

                    This workflow has no GitHub landing flow configured. Do not merge remote branches or land pull
                    requests from this workflow. Move completed local/non-GitHub work to the configured review or done
                    destination described in the handoff rules.
                    """
                    .stripTrailing();
        }
        String reviewHandoff = blank(reviewState) ? "human review" : quote(reviewState);
        if (blank(mergingState)) {
            return """
                    ## Landing

                    This workflow has no landing approval list configured. Do not merge or land from %s.
                    A human must land outside Symphony or add a Merging-style list to the workflow active lists
                    and Trello move allowlist.
                    """
                    .formatted(reviewHandoff)
                    .stripTrailing();
        }
        String doneDestination = blank(doneState) ? "the configured done list" : quote(doneState);
        String blockedText = blank(blockedDestination)
                ? "block with a visible Codex response because no blocked destination is configured"
                : "move the card to " + quote(blockedDestination) + " with a concise blocker";
        String fixupDecision = blank(reviewState)
                ? "If landing required material fixups, broad interpretation, or unverifiable changes, update the "
                        + "workpad and " + blockedText + "."
                : "If final work in the landing approval list required material fixups, broad interpretation, or "
                        + "unverifiable changes, move back to\n  " + quote(reviewState)
                        + " with the reason and ask for renewed approval.";
        return """
                ## Landing From %s

                %s is human approval for landing. Only run landing when the current Trello list is %s. Do not
                merge from %s, and do not call `gh pr merge` directly from the workflow prompt. Open
                `%s` and follow it.

                Before landing, identify the PR, run the PR feedback sweep, run current card-specific validation,
                check mergeability, branch state, required reviews, and CI/check status, and follow the repository's
                merge policy. Do not enable auto-merge unless the repository policy explicitly requires it.

                Deterministic landing decisions:
                - If the card moved from %s to %s with no new feedback and the PR is clean, land it.
                - If exact, unambiguous feedback added before the card entered %s was addressed with current
                  validation and clean checks, land it.
                - %s
                - If PR discovery, checks, auth, branch state, merge policy, required reviews, or actionable review
                  feedback is unresolved, update the workpad and %s.

                After successful landing, update the workpad with merge evidence, add a concise completion comment
                when useful, and move the card to %s.
                """
                .formatted(
                        quote(mergingState),
                        quote(mergingState),
                        quote(mergingState),
                        reviewHandoff,
                        skillPath("land"),
                        reviewHandoff,
                        quote(mergingState),
                        quote(mergingState),
                        fixupDecision,
                        blockedText,
                        doneDestination)
                .stripTrailing();
    }

    private static String routingPrompt(
            List<String> activeStates,
            List<String> terminalStates,
            String inProgressState,
            String reviewState,
            String blockedState,
            String mergingState) {
        List<String> queueStates = activeStates.stream()
                .filter(state -> blank(inProgressState) || !state.equalsIgnoreCase(inProgressState))
                .filter(state -> blank(mergingState) || !state.equalsIgnoreCase(mergingState))
                .toList();
        String activeText = activeStates.isEmpty() ? "no active lists" : quotedList(activeStates);
        String queueText = queueStates.isEmpty() ? "active queue lists" : quotedList(queueStates);
        String pickupText = blank(inProgressState)
                ? "work from the current active list because no in-progress list is configured"
                : "move the card to " + quote(inProgressState) + " before active implementation";
        String inProgressText = blank(inProgressState) ? "No in-progress list" : quote(inProgressState);
        String blockedText = blank(blockedState) ? "no configured blocked list" : quote(blockedState);
        String reviewText = blank(reviewState) ? "no configured human review list" : quote(reviewState);
        String mergingText = blank(mergingState) ? "No landing approval list" : quote(mergingState);
        String landingText = blank(mergingState)
                ? "landing automation is disabled until one is configured"
                : "human approval for landing. Run landing only from this list";
        String terminalText = terminalStates.isEmpty() ? "configured terminal lists" : quotedList(terminalStates);
        return """
                ## Trello List Routing

                Symphony only dispatches cards from configured active lists: %s.

                - %s: queued work; %s.
                - %s: work currently running in Codex; continue the existing execution flow.
                - %s: blocked work. Symphony does not dispatch it while this list is not configured as active.
                - %s: human review. Do not code from this list unless a human moves the card back to an active list.
                - %s: %s.
                - %s: terminal work. Symphony cleans up matching workspaces for terminal cards.
                - Any other list: out of scope for this Symphony process unless it is added to active_states or terminal_states.
                """
                .formatted(
                        activeText,
                        queueText,
                        pickupText,
                        inProgressText,
                        blockedText,
                        reviewText,
                        mergingText,
                        landingText,
                        terminalText)
                .stripTrailing();
    }

    private static String handoffPrompt(
            String reviewState, String blockedState, boolean workpadToolEnabled, boolean githubEnabled) {
        String blockedDestination = blockedDestination(reviewState, blockedState);
        if (!githubEnabled) {
            return nonGithubHandoffPrompt(reviewState, blockedDestination, workpadToolEnabled);
        }
        if (blank(reviewState) && blank(blockedDestination)) {
            if (!workpadToolEnabled) {
                return """
                        When the work is ready for human review, leave the workspace in a reviewable state and summarize the status in the Codex response. Trello handoff tools are disabled in this starter workflow until you configure trello_tools.allowed_move_list_names.
                        If the work is blocked, summarize the blocker in the Codex response; an operator must move the card out of the active list manually. Leaving blocked work active can make Symphony run it again.
                        If a human returns a reviewed card to an active list, reread the card, the Trello comments rendered above, and linked PR feedback when available before changing code again.
                        A Merging list, when configured, is a human approval signal for a landing flow; do not merge from human review.
                        %s"""
                        .formatted(FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION)
                        .stripTrailing();
            }
            return """
                    When the work is ready for human review, update the workpad with the final summary and validation evidence, leave the workspace in a reviewable state, and summarize the status in the Codex response. No review or blocked destination list is configured, so do not move the card for handoff.
                    If the work is blocked, update the workpad with the blocker and summarize the blocker in the Codex response; an operator must move the card out of the active list manually. Leaving blocked work active can make Symphony run it again.
                    If a human returns a reviewed card to an active list, reread the card, the Trello comments rendered above, and linked PR feedback when available before changing code again.
                    A Merging list, when configured, is a human approval signal for a landing flow; do not merge from human review.
                    %s"""
                    .formatted(FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION)
                    .stripTrailing();
        }
        if (blank(reviewState)) {
            return """
                    When the work is ready for human review, update the workpad with the final summary and validation evidence, leave the workspace in a reviewable state, and summarize the status in the Codex response.
                    If the work is blocked or unsafe to hand off, update the workpad with the blocker, call trello_add_comment with the blocker, then call
                    trello_move_current_card with list_name "%s".
                    If a human returns a reviewed card to an active list, reread the card, the Trello comments rendered above, and linked PR feedback when available before changing code again.
                    A Merging list, when configured, is a human approval signal for a landing flow; do not merge from human review. %s"""
                    .formatted(blockedDestination, FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION);
        }
        if (blank(blockedState)) {
            return """
                    When the work is ready for human review, update the workpad with the final summary and validation evidence, call trello_add_comment with a concise summary and
                    verification notes, then call trello_move_current_card with list_name "%s". If the work is
                    blocked or unsafe to hand off, update the workpad with the blocker, add a Trello comment explaining the blocker, then call
                    trello_move_current_card with list_name "%s" so the card leaves the active list. Do not leave
                    blocked work in an active list; Symphony may run it again.
                    If a human returns a reviewed card to an active list, treat it as rework: reread the card, the Trello comments rendered above, and linked PR feedback when available before changing code again.
                    A Merging list, when configured, is a human approval signal for a landing flow; do not merge from human review. %s"""
                    .formatted(reviewState, blockedDestination, FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION);
        }
        return """
                When the work is ready for human review, update the workpad with the final summary and validation evidence, call trello_add_comment with a concise summary and
                verification notes, then call trello_move_current_card with list_name "%s". If the work is
                blocked or unsafe to hand off, update the workpad with the blocker, add a Trello comment explaining the blocker, then call
                trello_move_current_card with list_name "%s".
                If a human returns a reviewed card to an active list, treat it as rework: reread the card, the Trello comments rendered above, and linked PR feedback when available before changing code again.
                A Merging list, when configured, is a human approval signal for a landing flow; do not merge from human review. %s"""
                .formatted(reviewState, blockedDestination, FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION);
    }

    private static String nonGithubHandoffPrompt(
            String reviewState, String blockedDestination, boolean workpadToolEnabled) {
        String workpadReview =
                workpadToolEnabled ? "update the workpad with the final summary and validation evidence, " : "";
        String workpadBlocker = workpadToolEnabled ? "update the workpad with the blocker and " : "";
        if (blank(reviewState) && blank(blockedDestination)) {
            return """
                    When the work is ready for human review, %sleave the workspace in a reviewable state and summarize the status in the Codex response.
                    If the work is blocked, %ssummarize the blocker in the Codex response; an operator must move the card out of the active list manually. Leaving blocked work active can make Symphony run it again.
                    If a human returns a reviewed card to an active list, reread the card and Trello comments before changing code again.
                    %s"""
                    .formatted(workpadReview, workpadBlocker, FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION)
                    .stripTrailing();
        }
        if (blank(reviewState)) {
            return """
                    When the work is ready for human review, %sleave the workspace in a reviewable state and summarize the status in the Codex response.
                    If the work is blocked or unsafe to hand off, %scall trello_add_comment with the blocker, then call trello_move_current_card with list_name "%s".
                    If a human returns a reviewed card to an active list, reread the card and Trello comments before changing code again.
                    %s"""
                    .formatted(
                            workpadReview, workpadBlocker, blockedDestination, FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION)
                    .stripTrailing();
        }
        if (blank(blockedDestination) || reviewState.equalsIgnoreCase(blockedDestination)) {
            return """
                    When the work is ready for human review, %scall trello_add_comment with a concise summary and verification notes, then call trello_move_current_card with list_name "%s".
                    If the work is blocked or unsafe to hand off, %sadd a Trello comment explaining the blocker, then call trello_move_current_card with list_name "%s" so the card leaves the active list. Do not leave blocked work in an active list; Symphony may run it again.
                    If a human returns a reviewed card to an active list, treat it as rework: reread the card and Trello comments before changing code again.
                    %s"""
                    .formatted(
                            workpadReview,
                            reviewState,
                            workpadBlocker,
                            reviewState,
                            FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION)
                    .stripTrailing();
        }
        return """
                When the work is ready for human review, %scall trello_add_comment with a concise summary and verification notes, then call trello_move_current_card with list_name "%s".
                If the work is blocked or unsafe to hand off, %sadd a Trello comment explaining the blocker, then call trello_move_current_card with list_name "%s".
                If a human returns a reviewed card to an active list, treat it as rework: reread the card and Trello comments before changing code again.
                %s"""
                .formatted(
                        workpadReview,
                        reviewState,
                        workpadBlocker,
                        blockedDestination,
                        FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION)
                .stripTrailing();
    }

    private static String pickupPrompt(List<String> activeStates, String inProgressState, String mergingState) {
        if (blank(inProgressState)) {
            String landingException = blank(mergingState)
                    ? ""
                    : " If the card is in " + quote(mergingState) + ", follow the landing section instead.";
            return ("""
                    This workflow has no in-progress list configured. Leave the card in its current active list
                    while working, then move it to the configured handoff list when the work is ready or blocked."""
                            + landingException)
                    .stripTrailing();
        }
        List<String> queueStates = activeStates.stream()
                .filter(state -> !state.equalsIgnoreCase(inProgressState))
                .filter(state -> blank(mergingState) || !state.equalsIgnoreCase(mergingState))
                .toList();
        String queueText = queueStates.isEmpty() ? "an active queue list" : quotedList(queueStates);
        return """
                Symphony moves cards from %s to "%s" before Codex starts when tracker.in_progress_state is configured.
                If the card is already in "%s", continue the existing execution flow."""
                .formatted(queueText, inProgressState, inProgressState);
    }

    private static String completionBarPrompt(
            String reviewState, String blockedDestination, boolean workpadToolEnabled, boolean githubEnabled) {
        String planRecord = workpadToolEnabled ? "workpad plan" : "final-response plan";
        if (blank(reviewState)) {
            String publicationText = githubEnabled ? "PR publication" : "local repository handoff";
            return """
                    ## Completion Bar

                    This workflow has no human review list configured. Before reporting ready-for-review status,
                    finish the %s, validation, commit, and %s requirements that
                    apply to this card. If the work is blocked, report the blocker clearly and keep the card out of
                    any active list manually.
                    """
                    .formatted(planRecord, publicationText)
                    .stripTrailing();
        }
        String blockedText =
                blank(blockedDestination) ? "block in the final response" : "move to " + quote(blockedDestination);
        String pullRequestLine = "";
        if (githubEnabled) {
            pullRequestLine = workpadToolEnabled
                    ? """
                - A pull request exists and is linked in the workpad and handoff comment for repository-changing work
                  unless the card explicitly requested local-only/no-push work.
                - PR feedback sweep is complete for any existing or newly created PR.
                """
                    : """
                - A pull request exists and is linked in the final response for repository-changing work
                  unless the card explicitly requested local-only/no-push work.
                - PR feedback sweep is complete for any existing or newly created PR.
                """;
        }
        return """
                ## Completion Bar Before %s

                Do not move the card to %s until all applicable items are true:

                - The %s, acceptance criteria, and validation sections match the work actually completed.
                - Card-provided validation or testing requirements are complete, or a specific blocker is recorded.
                - Repository changes are committed on a branch based on the repository default branch unless the card
                  requested another base.
                %s
                - Relevant local validation is current after the latest commit.
                - The working tree does not contain unrelated uncommitted changes.

                If any required item cannot be satisfied, %s with the exact blocker.
                """
                .formatted(quote(reviewState), quote(reviewState), planRecord, pullRequestLine, blockedText)
                .stripTrailing();
    }

    private static List<String> allowedMoveStates(
            String inProgressState, String reviewState, String blockedState, String mergingState, String doneState) {
        List<String> states = new ArrayList<>();
        if (!blank(inProgressState)) {
            states.add(inProgressState);
        }
        if (!blank(reviewState)) {
            states.add(reviewState);
        }
        String blockedDestination = blockedDestination(reviewState, blockedState);
        if (!blank(blockedDestination)
                && states.stream().noneMatch(existing -> existing.equalsIgnoreCase(blockedDestination))) {
            states.add(blockedDestination);
        }
        if (!blank(mergingState)
                && !blank(doneState)
                && states.stream().noneMatch(existing -> existing.equalsIgnoreCase(doneState))) {
            states.add(doneState);
        }
        return List.copyOf(states);
    }

    private static String blockedDestination(String reviewState, String blockedState) {
        return blank(blockedState) ? reviewState : blockedState;
    }

    private static List<String> withSystemTerminalStates(List<String> terminalStates) {
        List<String> combined = new ArrayList<>(terminalStates);
        for (String state : SYSTEM_TERMINAL_STATES) {
            if (combined.stream().noneMatch(existing -> existing.equalsIgnoreCase(state))) {
                combined.add(state);
            }
        }
        return List.copyOf(combined);
    }

    private static String yamlList(List<String> values) {
        return values.stream()
                .map(value -> "    - " + yamlScalar(value))
                .collect(Collectors.joining(System.lineSeparator()));
    }

    private static String yamlScalar(String value) {
        return "\"%s\"".formatted(value.replace("\\", "\\\\").replace("\"", "\\\""));
    }

    private static String quotedList(List<String> values) {
        return values.stream().map(value -> "\"" + value + "\"").collect(Collectors.joining(", "));
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    private static List<String> defaultActiveStates(List<String> openListNames) {
        return openListNames.stream()
                .filter(name -> name.equalsIgnoreCase(RECOMMENDED_ACTIVE_STATE))
                .findFirst()
                .map(List::of)
                .orElseGet(List::of);
    }

    private static List<String> defaultTerminalStates(List<String> openListNames) {
        return openListNames.stream()
                .filter(name -> name.equalsIgnoreCase("Done"))
                .findFirst()
                .map(List::of)
                .orElseGet(List::of);
    }

    private static String defaultReviewState(List<String> openListNames) {
        return openListNames.stream()
                .filter(name -> name.equalsIgnoreCase(RECOMMENDED_REVIEW_STATE))
                .findFirst()
                .orElseGet(() -> openListNames.stream()
                        .filter(name -> name.equalsIgnoreCase(LEGACY_REVIEW_STATE))
                        .findFirst()
                        .orElse(null));
    }

    private static String defaultInProgressState(List<String> openListNames) {
        return openListNames.stream()
                .filter(name -> name.equalsIgnoreCase(RECOMMENDED_IN_PROGRESS_STATE))
                .findFirst()
                .orElse(null);
    }

    private static String defaultMergingState(List<String> openListNames, List<String> terminalStates) {
        if (blank(landingDoneState(terminalStates))) {
            return null;
        }
        return openListNames.stream()
                .filter(name -> name.equalsIgnoreCase(RECOMMENDED_MERGING_STATE))
                .findFirst()
                .orElse(null);
    }

    private static String defaultBlockedState(List<String> openListNames) {
        return openListNames.stream()
                .filter(name -> name.equalsIgnoreCase(RECOMMENDED_BLOCKED_STATE))
                .findFirst()
                .orElse(null);
    }

    private static String landingDoneState(List<String> terminalStates) {
        return terminalStates.stream()
                .filter(state -> state.equalsIgnoreCase("Done"))
                .findFirst()
                .orElseGet(() -> terminalStates.stream().findFirst().orElse(null));
    }

    private static List<String> implementationActiveStates(List<String> activeStates, String mergingState) {
        if (blank(mergingState)) {
            return activeStates;
        }
        return activeStates.stream()
                .filter(state -> !state.equalsIgnoreCase(mergingState))
                .toList();
    }

    private static List<String> withOptionalActiveState(List<String> activeStates, String extraState) {
        if (blank(extraState) || activeStates.stream().anyMatch(existing -> existing.equalsIgnoreCase(extraState))) {
            return activeStates;
        }
        List<String> combined = new ArrayList<>(activeStates);
        combined.add(extraState);
        return List.copyOf(combined);
    }

    private static List<String> withOptionalListName(List<String> names, String extraName) {
        if (blank(extraName) || names.stream().anyMatch(existing -> existing.equalsIgnoreCase(extraName))) {
            return names;
        }
        List<String> combined = new ArrayList<>(names);
        combined.add(extraName);
        return List.copyOf(combined);
    }

    private static void validateConfiguredLists(
            String label, String errorCodeSegment, List<String> configured, List<String> openListNames) {
        Set<String> normalizedOpenNames =
                openListNames.stream().map(TrelloBoardSetup::normalize).collect(Collectors.toUnmodifiableSet());
        List<String> missing = configured.stream()
                .filter(name -> !normalizedOpenNames.contains(normalize(name)))
                .toList();
        if (!missing.isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_unknown_" + errorCodeSegment + "_state",
                    "Unknown " + label + " list(s): " + String.join(", ", missing) + ". Open lists: "
                            + String.join(", ", openListNames));
        }
    }

    private static void validateConfiguredList(
            String label, String errorCodeSegment, String configured, List<String> openListNames) {
        if (blank(configured)) {
            return;
        }
        validateConfiguredLists(label, errorCodeSegment, List.of(configured), openListNames);
    }

    private static BoardList toBoardList(Map<String, Object> payload) {
        return new BoardList(
                requiredString(payload, "id"), requiredString(payload, "name"), bool(payload.get("closed")));
    }

    private static TrelloBoardSetupException statusException(int statusCode, String responseBody) {
        String detail = blank(responseBody)
                ? ""
                : ": " + responseBody.strip().lines().findFirst().orElse("");
        Status status = Status.fromStatusCode(statusCode);
        if (status == null) {
            return new TrelloBoardSetupException(
                    "trello_api_status", "Trello returned HTTP " + statusCode + detail, statusCode);
        }
        return switch (status) {
            case BAD_REQUEST ->
                new TrelloBoardSetupException(
                        "trello_invalid_request", "Trello rejected the setup request" + detail, statusCode);
            case UNAUTHORIZED ->
                new TrelloBoardSetupException(
                        "trello_auth_failed", "Trello authentication failed" + detail, statusCode);
            case FORBIDDEN ->
                new TrelloBoardSetupException(
                        "trello_permission_denied", "Trello permission denied" + detail, statusCode);
            case NOT_FOUND ->
                new TrelloBoardSetupException(
                        "trello_resource_not_found", "Trello resource not found" + detail, statusCode);
            default ->
                new TrelloBoardSetupException(
                        "trello_api_status", "Trello returned HTTP " + statusCode + detail, statusCode);
        };
    }

    private static boolean isSuccessfulStatus(int statusCode) {
        return Family.SUCCESSFUL == Family.familyOf(statusCode);
    }

    private static String boardKey(Map<String, Object> board) {
        String shortLink = string(board.get("shortLink"));
        return blank(shortLink) ? requiredString(board, "id") : shortLink;
    }

    private static Map<String, String> createBoardQuery(String boardName, String workspaceId) {
        Map<String, String> query = orderedMap("name", boardName, "defaultLists", "false", "defaultLabels", "false");
        if (!blank(workspaceId)) {
            query.put("idOrganization", workspaceId);
        }
        return query;
    }

    private static String workspaceChoices(List<WorkspaceInfo> workspaces) {
        return workspaces.stream()
                .map(workspace -> workspace.id() + " (" + workspace.displayName() + ")")
                .collect(Collectors.joining(", "));
    }

    private static Map<String, String> orderedMap(String... entries) {
        checkArgument(entries.length % 2 == 0, "Entries must contain key-value pairs");
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put(entries[i], entries[i + 1]);
        }
        return map;
    }

    private static String requiredString(Map<String, Object> payload, String key) {
        String value = string(payload.get(key));
        if (blank(value)) {
            throw new TrelloBoardSetupException("trello_unknown_payload", "Trello payload is missing " + key);
        }
        return value;
    }

    private static boolean bool(Object value) {
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String fallback(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private static String encodeSegment(String value) {
        return encode(value).replace("+", "%20");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    static String slugify(String value) {
        String slug =
                value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return slug.isBlank() ? "board" : slug;
    }

    public record TrelloCredentials(String apiKey, String apiToken) {
        public TrelloCredentials {
            validateValues(apiKey, apiToken);
        }

        private void validate() {
            validateValues(apiKey, apiToken);
        }

        private static void validateValues(String apiKey, String apiToken) {
            if (blank(apiKey)) {
                throw new TrelloBoardSetupException("setup_missing_api_key", "Missing Trello API key");
            }
            if (blank(apiToken)) {
                throw new TrelloBoardSetupException("setup_missing_api_token", "Missing Trello API token");
            }
            CliInputValidation.rejectControlCharacters("Trello API key", apiKey);
            CliInputValidation.rejectControlCharacters("Trello API token", apiToken);
        }
    }

    public record NewBoardRequest(
            URI endpoint,
            TrelloCredentials credentials,
            String boardName,
            String workspaceId,
            Path workflowPath,
            Path workspaceRoot,
            Integer serverPort,
            int maxConcurrentAgents,
            boolean force,
            boolean useBoardNameWorkflowFallback,
            GitHubIntegration githubIntegration) {
        public NewBoardRequest(
                URI endpoint,
                TrelloCredentials credentials,
                String boardName,
                String workspaceId,
                Path workflowPath,
                Path workspaceRoot,
                int maxConcurrentAgents,
                boolean force,
                boolean useBoardNameWorkflowFallback) {
            this(
                    endpoint,
                    credentials,
                    boardName,
                    workspaceId,
                    workflowPath,
                    workspaceRoot,
                    null,
                    maxConcurrentAgents,
                    force,
                    useBoardNameWorkflowFallback,
                    GitHubIntegration.ENABLED);
        }

        public NewBoardRequest(
                URI endpoint,
                TrelloCredentials credentials,
                String boardName,
                String workspaceId,
                Path workflowPath,
                Path workspaceRoot,
                Integer serverPort,
                int maxConcurrentAgents,
                boolean force,
                boolean useBoardNameWorkflowFallback) {
            this(
                    endpoint,
                    credentials,
                    boardName,
                    workspaceId,
                    workflowPath,
                    workspaceRoot,
                    serverPort,
                    maxConcurrentAgents,
                    force,
                    useBoardNameWorkflowFallback,
                    GitHubIntegration.ENABLED);
        }

        private void validate() {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(credentials, "credentials").validate();
            Objects.requireNonNull(workflowPath, "workflowPath");
            Objects.requireNonNull(workspaceRoot, "workspaceRoot");
            Objects.requireNonNull(githubIntegration, "githubIntegration");
            validateOptionalServerPort(serverPort, "--server-port");
            if (blank(boardName)) {
                throw new TrelloBoardSetupException("setup_missing_board_name", "Missing board name");
            }
            if (maxConcurrentAgents < 1) {
                throw new TrelloBoardSetupException(
                        "setup_invalid_max_agents", "--max-agents must be greater than zero");
            }
        }
    }

    public record WorkspaceListRequest(URI endpoint, TrelloCredentials credentials) {
        private void validate() {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(credentials, "credentials").validate();
        }
    }

    public record MemberInfoRequest(URI endpoint, TrelloCredentials credentials) {
        private void validate() {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(credentials, "credentials").validate();
        }
    }

    public record BoardInfoRequest(URI endpoint, TrelloCredentials credentials, String boardId) {
        private void validate() {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(credentials, "credentials").validate();
            if (blank(boardId)) {
                throw new TrelloBoardSetupException("setup_missing_board_id", "Missing board id");
            }
        }
    }

    public record ImportBoardRequest(
            URI endpoint,
            TrelloCredentials credentials,
            String boardId,
            List<String> activeStates,
            List<String> terminalStates,
            String inProgressState,
            boolean detectInProgressState,
            String blockedState,
            Path workflowPath,
            Path workspaceRoot,
            Integer serverPort,
            int maxConcurrentAgents,
            boolean force,
            GitHubIntegration githubIntegration,
            boolean createMissingGithubLists) {
        public ImportBoardRequest {
            activeStates = List.copyOf(activeStates);
            terminalStates = List.copyOf(terminalStates);
        }

        public ImportBoardRequest(
                URI endpoint,
                TrelloCredentials credentials,
                String boardId,
                List<String> activeStates,
                List<String> terminalStates,
                String inProgressState,
                boolean detectInProgressState,
                String blockedState,
                Path workflowPath,
                Path workspaceRoot,
                int maxConcurrentAgents,
                boolean force) {
            this(
                    endpoint,
                    credentials,
                    boardId,
                    activeStates,
                    terminalStates,
                    inProgressState,
                    detectInProgressState,
                    blockedState,
                    workflowPath,
                    workspaceRoot,
                    null,
                    maxConcurrentAgents,
                    force,
                    GitHubIntegration.ENABLED,
                    false);
        }

        public ImportBoardRequest(
                URI endpoint,
                TrelloCredentials credentials,
                String boardId,
                List<String> activeStates,
                List<String> terminalStates,
                String inProgressState,
                boolean detectInProgressState,
                String blockedState,
                Path workflowPath,
                Path workspaceRoot,
                Integer serverPort,
                int maxConcurrentAgents,
                boolean force,
                GitHubIntegration githubIntegration) {
            this(
                    endpoint,
                    credentials,
                    boardId,
                    activeStates,
                    terminalStates,
                    inProgressState,
                    detectInProgressState,
                    blockedState,
                    workflowPath,
                    workspaceRoot,
                    serverPort,
                    maxConcurrentAgents,
                    force,
                    githubIntegration,
                    false);
        }

        public ImportBoardRequest(
                URI endpoint,
                TrelloCredentials credentials,
                String boardId,
                List<String> activeStates,
                List<String> terminalStates,
                String inProgressState,
                boolean detectInProgressState,
                String blockedState,
                Path workflowPath,
                Path workspaceRoot,
                Integer serverPort,
                int maxConcurrentAgents,
                boolean force) {
            this(
                    endpoint,
                    credentials,
                    boardId,
                    activeStates,
                    terminalStates,
                    inProgressState,
                    detectInProgressState,
                    blockedState,
                    workflowPath,
                    workspaceRoot,
                    serverPort,
                    maxConcurrentAgents,
                    force,
                    GitHubIntegration.ENABLED,
                    false);
        }

        public ImportBoardRequest(
                URI endpoint,
                TrelloCredentials credentials,
                String boardId,
                List<String> activeStates,
                List<String> terminalStates,
                String blockedState,
                Path workflowPath,
                Path workspaceRoot,
                int maxConcurrentAgents,
                boolean force) {
            this(
                    endpoint,
                    credentials,
                    boardId,
                    activeStates,
                    terminalStates,
                    null,
                    true,
                    blockedState,
                    workflowPath,
                    workspaceRoot,
                    null,
                    maxConcurrentAgents,
                    force,
                    GitHubIntegration.ENABLED,
                    false);
        }

        public ImportBoardRequest(
                URI endpoint,
                TrelloCredentials credentials,
                String boardId,
                List<String> activeStates,
                List<String> terminalStates,
                String inProgressState,
                String blockedState,
                Path workflowPath,
                Path workspaceRoot,
                int maxConcurrentAgents,
                boolean force) {
            this(
                    endpoint,
                    credentials,
                    boardId,
                    activeStates,
                    terminalStates,
                    inProgressState,
                    false,
                    blockedState,
                    workflowPath,
                    workspaceRoot,
                    null,
                    maxConcurrentAgents,
                    force,
                    GitHubIntegration.ENABLED,
                    false);
        }

        private void validate() {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(credentials, "credentials").validate();
            Objects.requireNonNull(activeStates, "activeStates");
            Objects.requireNonNull(terminalStates, "terminalStates");
            Objects.requireNonNull(workflowPath, "workflowPath");
            Objects.requireNonNull(workspaceRoot, "workspaceRoot");
            Objects.requireNonNull(githubIntegration, "githubIntegration");
            validateOptionalServerPort(serverPort, "--server-port");
            if (blank(boardId)) {
                throw new TrelloBoardSetupException("setup_missing_board_id", "Missing board id");
            }
            if (maxConcurrentAgents < 1) {
                throw new TrelloBoardSetupException(
                        "setup_invalid_max_agents", "--max-agents must be greater than zero");
            }
        }
    }

    public record NewBoardResult(
            String boardId,
            String boardKey,
            String boardName,
            String boardUrl,
            List<String> lists,
            Path workflowPath,
            int serverPort) {
        public NewBoardResult {
            lists = List.copyOf(lists);
        }
    }

    public record MemberInfo(String id, String username, String displayName) {}

    public record BoardInfo(String boardId, String boardKey, String boardName, String boardUrl) {}

    public record WorkspaceInfo(String id, String name, String displayName, String url) {}

    public record CodexModelDefaults(String model, String reasoningEffort, boolean firstClassFieldsSupported) {
        public CodexModelDefaults {
            model = blank(model) ? null : model;
            reasoningEffort = blank(reasoningEffort) ? null : reasoningEffort;
            checkArgument(
                    firstClassFieldsSupported || !(model == null || reasoningEffort == null),
                    "unsupported first-class Codex defaults must include fallback model and reasoning effort");
        }

        public CodexModelDefaults(String model, String reasoningEffort) {
            this(requireNonBlank(model, "model"), requireNonBlank(reasoningEffort, "reasoningEffort"), true);
        }

        public static CodexModelDefaults fallback() {
            return new CodexModelDefaults(DEFAULT_CODEX_MODEL, DEFAULT_CODEX_REASONING_EFFORT);
        }

        public static CodexModelDefaults unsupportedFirstClassFields() {
            return new CodexModelDefaults(DEFAULT_CODEX_MODEL, DEFAULT_CODEX_REASONING_EFFORT, false);
        }

        public static CodexModelDefaults omittedFirstClassFields() {
            return new CodexModelDefaults(null, null, true);
        }

        static CodexModelDefaults partial(String model, String reasoningEffort) {
            return new CodexModelDefaults(
                    blank(model) ? null : model, blank(reasoningEffort) ? null : reasoningEffort, true);
        }

        private static String requireNonBlank(String value, String name) {
            checkArgument(!blank(value), "%s must not be blank", name);
            return value;
        }
    }

    public record ImportBoardResult(
            String boardId,
            String boardKey,
            String boardName,
            String boardUrl,
            List<String> openLists,
            List<String> activeStates,
            List<String> terminalStates,
            String inProgressState,
            String blockedState,
            Path workflowPath,
            int serverPort) {
        public ImportBoardResult {
            openLists = List.copyOf(openLists);
            activeStates = List.copyOf(activeStates);
            terminalStates = List.copyOf(terminalStates);
        }
    }

    private record BoardList(String id, String name, boolean closed) {}

    public enum GitHubIntegration {
        ENABLED,
        DISABLED;

        public boolean enabled() {
            return this == ENABLED;
        }
    }
}
