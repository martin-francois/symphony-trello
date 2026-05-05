package com.openai.symphony.setup;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.symphony.tracker.TrelloClient;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class TrelloBoardSetup {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://api.trello.com/1");
    public static final Path DEFAULT_WORKFLOW_PATH = Path.of("WORKFLOW.md");
    public static final Path DEFAULT_WORKSPACE_ROOT = Path.of("./workspaces");
    public static final int DEFAULT_MAX_CONCURRENT_AGENTS = 1;
    public static final List<String> RECOMMENDED_LISTS = List.of("Inbox", "Ready for Codex", "Review", "Done");
    public static final List<String> RECOMMENDED_ACTIVE_STATES = List.of("Ready for Codex");
    public static final List<String> RECOMMENDED_TERMINAL_STATES = List.of("Done");

    private static final List<String> SYSTEM_TERMINAL_STATES =
            List.of("Archived", "ArchivedList", "ArchivedBoard", "Deleted");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper json;
    private final HttpClient httpClient;

    public TrelloBoardSetup(ObjectMapper json) {
        this.json = json;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public NewBoardResult createRecommendedBoard(NewBoardRequest request) {
        request.validate();
        Path workflowPath = resolveNewBoardWorkflowPath(request);
        ensureWorkflowWritable(workflowPath, request.force());
        String workspaceId = resolveWorkspaceId(request);
        Map<String, Object> board = postMap(
                request.endpoint(),
                "boards/",
                createBoardQuery(request.boardName(), workspaceId),
                request.credentials());
        String boardId = requiredString(board, "id");
        String boardKey = boardKey(board);
        String boardUrl = string(board.get("url"));

        List<String> createdLists = new ArrayList<>();
        for (String listName : RECOMMENDED_LISTS) {
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
                        RECOMMENDED_ACTIVE_STATES,
                        RECOMMENDED_TERMINAL_STATES,
                        List.of("Review"),
                        request.workspaceRoot(),
                        request.maxConcurrentAgents()));

        return new NewBoardResult(boardId, boardKey, request.boardName(), boardUrl, createdLists, workflowPath);
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

        List<String> openListNames = lists.stream()
                .filter(list -> !list.closed())
                .map(BoardList::name)
                .toList();
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
        validateConfiguredLists("active", activeStates, openListNames);
        validateConfiguredLists("terminal", terminalStates, openListNames);

        String boardKey = boardKey(board);
        writeWorkflow(
                request.workflowPath(),
                request.force(),
                workflowTemplate(
                        boardKey,
                        activeStates,
                        terminalStates,
                        defaultHandoffStates(openListNames),
                        request.workspaceRoot(),
                        request.maxConcurrentAgents()));

        return new ImportBoardResult(
                resolvedBoardId,
                boardKey,
                requiredString(board, "name"),
                string(board.get("url")),
                openListNames,
                activeStates,
                terminalStates,
                request.workflowPath());
    }

    private Map<String, Object> getMap(
            URI endpoint, String path, Map<String, String> query, TrelloCredentials credentials) {
        return request("GET", endpoint, path, query, credentials, MAP_TYPE);
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
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
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

    private static Path ensureWorkflowWritable(Path workflowPath, boolean force) {
        Path absolute = workflowPath.toAbsolutePath().normalize();
        if (Files.exists(absolute) && !force) {
            throw new TrelloBoardSetupException(
                    "setup_workflow_exists",
                    "Workflow file already exists: " + absolute + ". Re-run with --force to overwrite it.");
        }
        return absolute;
    }

    private static String workflowTemplate(
            String boardId,
            List<String> activeStates,
            List<String> terminalStates,
            List<String> handoffStates,
            Path workspaceRoot,
            int maxAgents) {
        return """
                ---
                tracker:
                  kind: trello
                  api_key: $TRELLO_API_KEY
                  api_token: $TRELLO_API_TOKEN
                  board_id: %s
                  active_states:
                %s
                  blocker_enforced_states:
                %s
                  terminal_states:
                %s
                workspace:
                  root: %s
                %s
                agent:
                  max_concurrent_agents: %d
                codex:
                  command: codex app-server
                  approval_policy: never
                  turn_timeout_ms: 3600000
                  read_timeout_ms: 5000
                  stall_timeout_ms: 300000
                ---
                # Trello Card

                You are working on {{ card.identifier }}: {{ card.title }}.

                Read the Trello description carefully, inspect the repository, make the smallest maintainable change,
                run relevant verification, and leave the workspace in a reviewable state.

                %s

                Card URL: {{ card.url }}
                """
                .formatted(
                        yamlScalar(boardId),
                        yamlList(activeStates),
                        yamlList(activeStates),
                        yamlList(withSystemTerminalStates(terminalStates)),
                        yamlScalar(workspaceRoot.toString()),
                        trelloToolsYaml(handoffStates),
                        maxAgents,
                        handoffPrompt(handoffStates));
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

    private static String handoffPrompt(List<String> handoffStates) {
        if (handoffStates.isEmpty()) {
            return "When the work is ready for human review, leave the workspace in a reviewable state and summarize the status in the Codex response. Trello handoff tools are disabled in this starter workflow until you configure trello_tools.allowed_move_list_names.";
        }
        return """
                When the work is ready for human review, call trello_add_comment with a concise summary and
                verification notes, then call trello_move_current_card with list_name "%s". If the work is
                blocked or unsafe to hand off, add a Trello comment explaining the blocker and do not move the card."""
                .formatted(handoffStates.getFirst());
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

    private static List<String> defaultActiveStates(List<String> openListNames) {
        return openListNames.stream()
                .filter(name -> name.equalsIgnoreCase("Ready for Codex"))
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

    private static List<String> defaultHandoffStates(List<String> openListNames) {
        return openListNames.stream()
                .filter(name -> name.equalsIgnoreCase("Review"))
                .findFirst()
                .map(List::of)
                .orElseGet(List::of);
    }

    private static void validateConfiguredLists(String label, List<String> configured, List<String> openListNames) {
        Set<String> normalizedOpenNames =
                openListNames.stream().map(TrelloBoardSetup::normalize).collect(Collectors.toSet());
        List<String> missing = configured.stream()
                .filter(name -> !normalizedOpenNames.contains(normalize(name)))
                .toList();
        if (!missing.isEmpty()) {
            throw new TrelloBoardSetupException(
                    "setup_unknown_" + label + "_state",
                    "Unknown " + label + " list(s): " + String.join(", ", missing) + ". Open lists: "
                            + String.join(", ", openListNames));
        }
    }

    private static BoardList toBoardList(Map<String, Object> payload) {
        return new BoardList(
                requiredString(payload, "id"), requiredString(payload, "name"), bool(payload.get("closed")));
    }

    private static TrelloBoardSetupException statusException(int statusCode, String responseBody) {
        String detail = blank(responseBody)
                ? ""
                : ": " + responseBody.strip().lines().findFirst().orElse("");
        return switch (statusCode) {
            case 400 ->
                new TrelloBoardSetupException(
                        "trello_invalid_request", "Trello rejected the setup request" + detail, statusCode);
            case 401 ->
                new TrelloBoardSetupException(
                        "trello_auth_failed", "Trello authentication failed" + detail, statusCode);
            case 403 ->
                new TrelloBoardSetupException(
                        "trello_permission_denied", "Trello permission denied" + detail, statusCode);
            case 404 ->
                new TrelloBoardSetupException(
                        "trello_resource_not_found", "Trello resource not found" + detail, statusCode);
            default ->
                new TrelloBoardSetupException(
                        "trello_api_status", "Trello returned HTTP " + statusCode + detail, statusCode);
        };
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
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Entries must contain key-value pairs");
        }
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
        private void validate() {
            if (blank(apiKey)) {
                throw new TrelloBoardSetupException("setup_missing_api_key", "Missing Trello API key");
            }
            if (blank(apiToken)) {
                throw new TrelloBoardSetupException("setup_missing_api_token", "Missing Trello API token");
            }
        }
    }

    public record NewBoardRequest(
            URI endpoint,
            TrelloCredentials credentials,
            String boardName,
            String workspaceId,
            Path workflowPath,
            Path workspaceRoot,
            int maxConcurrentAgents,
            boolean force,
            boolean useBoardNameWorkflowFallback) {
        private void validate() {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(credentials, "credentials").validate();
            Objects.requireNonNull(workflowPath, "workflowPath");
            Objects.requireNonNull(workspaceRoot, "workspaceRoot");
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

    public record ImportBoardRequest(
            URI endpoint,
            TrelloCredentials credentials,
            String boardId,
            List<String> activeStates,
            List<String> terminalStates,
            Path workflowPath,
            Path workspaceRoot,
            int maxConcurrentAgents,
            boolean force) {
        private void validate() {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(credentials, "credentials").validate();
            Objects.requireNonNull(activeStates, "activeStates");
            Objects.requireNonNull(terminalStates, "terminalStates");
            Objects.requireNonNull(workflowPath, "workflowPath");
            Objects.requireNonNull(workspaceRoot, "workspaceRoot");
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
            Path workflowPath) {}

    public record WorkspaceInfo(String id, String name, String displayName, String url) {}

    public record ImportBoardResult(
            String boardId,
            String boardKey,
            String boardName,
            String boardUrl,
            List<String> openLists,
            List<String> activeStates,
            List<String> terminalStates,
            Path workflowPath) {}

    private record BoardList(String id, String name, boolean closed) {}
}
