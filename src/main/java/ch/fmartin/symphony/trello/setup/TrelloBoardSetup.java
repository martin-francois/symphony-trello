package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.tracker.TrelloClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    public static final String RECOMMENDED_ACTIVE_STATE = "Ready for Codex";
    public static final String RECOMMENDED_IN_PROGRESS_STATE = "In Progress";
    public static final String RECOMMENDED_BLOCKED_STATE = "Blocked";
    public static final String RECOMMENDED_REVIEW_STATE = "Human Review";
    public static final String RECOMMENDED_MERGING_STATE = "Merging";
    public static final String LEGACY_REVIEW_STATE = "Review";
    public static final List<String> RECOMMENDED_COLUMNS = List.of(
            "Inbox",
            RECOMMENDED_ACTIVE_STATE,
            RECOMMENDED_IN_PROGRESS_STATE,
            RECOMMENDED_BLOCKED_STATE,
            RECOMMENDED_REVIEW_STATE,
            RECOMMENDED_MERGING_STATE,
            "Done");
    public static final List<String> RECOMMENDED_ACTIVE_STATES =
            List.of(RECOMMENDED_ACTIVE_STATE, RECOMMENDED_IN_PROGRESS_STATE);
    public static final List<String> RECOMMENDED_TERMINAL_STATES = List.of("Done");

    private static final List<String> SYSTEM_TERMINAL_STATES =
            List.of("Archived", "ArchivedList", "ArchivedBoard", "Deleted");
    private static final String FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION =
            """
            Filesystem access blocker details must include the inaccessible path, why it is inaccessible,
            that deployed Symphony exposes only managed workspaces and explicitly allowed project roots by
            default, that accessible files are available in the current per-card workspace shown by `pwd`,
            and that an operator can relax access with the systemd or Ansible allowed-project-roots
            deployment setting.""";
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

        List<String> createdColumns = new ArrayList<>();
        for (String columnName : RECOMMENDED_COLUMNS) {
            postMap(
                    request.endpoint(),
                    "lists",
                    orderedMap("name", columnName, "idBoard", boardId, "pos", "bottom"),
                    request.credentials());
            createdColumns.add(columnName);
        }

        writeWorkflow(
                workflowPath,
                request.force(),
                workflowTemplate(
                        boardKey,
                        RECOMMENDED_ACTIVE_STATES,
                        RECOMMENDED_TERMINAL_STATES,
                        RECOMMENDED_IN_PROGRESS_STATE,
                        RECOMMENDED_REVIEW_STATE,
                        RECOMMENDED_BLOCKED_STATE,
                        request.workspaceRoot(),
                        request.maxConcurrentAgents()));

        return new NewBoardResult(boardId, boardKey, request.boardName(), boardUrl, createdColumns, workflowPath);
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
                    "No active column was provided and the board has no 'Ready for Codex' column. Pass --active with the column Codex should poll. Open columns: "
                            + String.join(", ", openListNames));
        }

        List<String> terminalStates =
                request.terminalStates().isEmpty() ? defaultTerminalStates(openListNames) : request.terminalStates();
        String blockedState =
                blank(request.blockedState()) ? defaultBlockedState(openListNames) : request.blockedState();
        String inProgressState =
                request.detectInProgressState() ? defaultInProgressState(openListNames) : request.inProgressState();
        String reviewState = defaultReviewState(openListNames);
        activeStates = withOptionalActiveState(activeStates, inProgressState);
        validateConfiguredLists("active", activeStates, openListNames);
        validateConfiguredLists("terminal", terminalStates, openListNames);
        validateConfiguredList("in-progress", inProgressState, openListNames);
        validateConfiguredList("blocked", blockedState, openListNames);

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
                inProgressState,
                blockedState,
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
            String inProgressState,
            String reviewState,
            String blockedState,
            Path workspaceRoot,
            int maxAgents) {
        List<String> handoffStates = allowedMoveStates(inProgressState, reviewState, blockedState);
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

                ## Description

                {{ card.description }}

                ## Trello Comments

                {%% for comment in card.comments %%}
                - {{ comment.created_at }}{%% if comment.author %%} {{ comment.author }}{%% endif %%}: {{ comment.text }}
                {%% endfor %%}

                %s

                ## Repository Skills

                Use repository-local skills when they fit:

                - `.codex/skills/trello-workpad/SKILL.md` for workpad updates.
                - `.codex/skills/trello-handoff/SKILL.md` for Trello pickup, review, blocked, merge,
                  and done handoff.
                - `.codex/skills/review-sweep/SKILL.md` when a pull request or branch is involved.
                - `.codex/skills/repo-sync/SKILL.md`, `.codex/skills/commit/SKILL.md`, and
                  `.codex/skills/push-pr/SKILL.md` for branch, commit, and PR hygiene.
                - `.codex/skills/land/SKILL.md` only when this workflow says the current Trello
                  column is Merging.
                - `.codex/skills/debug/SKILL.md` when diagnosing a stuck or retrying run.

                Read the Trello description carefully, inspect the repository, make the smallest maintainable change,
                run relevant verification, and leave the workspace in a reviewable state.
                Use the current workspace by default. If the Trello card names a specific local path or project
                checkout, inspect that path instead. If that path is inaccessible, treat it as a blocker and follow
                the filesystem access blocker instructions below.

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
                        yamlList(activeStates),
                        yamlList(withSystemTerminalStates(terminalStates)),
                        yamlScalar(workspaceRoot.toString()),
                        trelloToolsYaml(handoffStates),
                        maxAgents,
                        workpadPrompt(!handoffStates.isEmpty()),
                        routingPrompt(activeStates, terminalStates, inProgressState, reviewState, blockedState),
                        validationPrompt(!handoffStates.isEmpty(), reviewState),
                        prFeedbackPrompt(reviewState),
                        reworkPrompt(activeStates, reviewState),
                        pickupPrompt(activeStates, inProgressState),
                        handoffPrompt(reviewState, blockedState, !handoffStates.isEmpty()));
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

                If required validation cannot be performed because auth, files, tools, or environment access are
                missing, treat the work as blocked. Do not move the card to %s until the blocker is fixed or a human
                explicitly changes the requirement.
                """
                .formatted(evidenceDestination, reviewHandoff)
                .stripTrailing();
    }

    private static String prFeedbackPrompt(String reviewState) {
        String reviewHandoff = blank(reviewState) ? "ready-for-review handoff" : quote(reviewState);
        return """
                ## Pull Request Feedback Sweep

                If the Trello description, Trello comments, current branch, repository context, or open PR list
                identifies an associated pull request, use `.codex/skills/review-sweep/SKILL.md` before moving the
                card to %s or landing from Merging. Cards without PR context do not need GitHub review checks.

                The sweep must check top-level PR comments, inline review comments, review states and summaries,
                CI/check status, and Codex review issue comments when present. Every actionable human, bot, or Codex
                review comment is blocking until it is addressed with code, tests, docs, or PR metadata, or answered
                with a justified response in the right thread. Do not decline correctness feedback without concrete
                validation. Failing, pending, or stale required checks mean the work is not ready for handoff.

                After feedback-driven changes, rerun the relevant validation and repeat the sweep until no
                actionable feedback remains. If GitHub auth, PR discovery, required checks, or review data are
                unavailable for a PR-backed card, treat the card as blocked instead of handing it off.
                """
                .formatted(reviewHandoff)
                .stripTrailing();
    }

    private static String reworkPrompt(List<String> activeStates, String reviewState) {
        String reviewHandoff = blank(reviewState) ? "human review" : quote(reviewState);
        String activeText = activeStates.isEmpty() ? "an active column" : quotedList(activeStates);
        return """
                ## Rework From Human Review

                If a human moves a reviewed card from %s back to %s, treat the next run as rework. Before changing
                code, reread the full card description, new Trello comments, existing workpad, linked PR comments,
                inline PR review comments, and current PR/check state.

                Identify what changed since the last handoff and update the workpad with a short rework plan that
                says what will be done differently. Preserve completed work that still satisfies the current card;
                do not restart from scratch, close the existing PR, delete the workpad, or create a new branch unless
                the Trello card or a human explicitly asks for a reset.

                Before returning the card to %s, rerun the card-specific validation and PR feedback sweep, update the
                existing workpad with the rework evidence, and add one concise handoff comment. Do not create
                duplicate progress summary comments when the workpad already contains the details.
                """
                .formatted(reviewHandoff, activeText, reviewHandoff)
                .stripTrailing();
    }

    private static String routingPrompt(
            List<String> activeStates,
            List<String> terminalStates,
            String inProgressState,
            String reviewState,
            String blockedState) {
        List<String> queueStates = activeStates.stream()
                .filter(state -> blank(inProgressState) || !state.equalsIgnoreCase(inProgressState))
                .toList();
        String activeText = activeStates.isEmpty() ? "no active columns" : quotedList(activeStates);
        String queueText = queueStates.isEmpty() ? "active queue columns" : quotedList(queueStates);
        String pickupText = blank(inProgressState)
                ? "work from the current active column because no in-progress column is configured"
                : "move the card to " + quote(inProgressState) + " before active implementation";
        String inProgressText = blank(inProgressState) ? "No in-progress column" : quote(inProgressState);
        String blockedText = blank(blockedState) ? "no configured blocked column" : quote(blockedState);
        String reviewText = blank(reviewState) ? "no configured human review column" : quote(reviewState);
        String terminalText = terminalStates.isEmpty() ? "configured terminal columns" : quotedList(terminalStates);
        return """
                ## Trello Column Routing

                Symphony only dispatches cards from configured active columns: %s.

                - %s: queued work; %s.
                - %s: active work already picked up by Codex; continue the existing execution flow.
                - %s: blocked work. Symphony does not dispatch it while this column is not configured as active.
                - %s: human review. Do not code from this column unless a human moves the card back to an active column.
                - `Merging`: human approval for landing. Do not merge from Human Review, and do not run landing unless this workflow explicitly configures Merging as active.
                - %s: terminal work. Symphony cleans up matching workspaces for terminal cards.
                - Any other column: out of scope for this Symphony process unless it is added to active_states or terminal_states.
                """
                .formatted(activeText, queueText, pickupText, inProgressText, blockedText, reviewText, terminalText)
                .stripTrailing();
    }

    private static String handoffPrompt(String reviewState, String blockedState, boolean workpadToolEnabled) {
        String blockedDestination = blockedDestination(reviewState, blockedState);
        if (blank(reviewState) && blank(blockedDestination)) {
            if (!workpadToolEnabled) {
                return """
                        When the work is ready for human review, leave the workspace in a reviewable state and summarize the status in the Codex response. Trello handoff tools are disabled in this starter workflow until you configure trello_tools.allowed_move_list_names.
                        If the work is blocked, summarize the blocker in the Codex response; an operator must move the card out of the active column manually. Leaving blocked work active can make Symphony run it again.
                        If a human returns a reviewed card to an active column, reread the card, the Trello comments rendered above, and linked PR feedback when available before changing code again.
                        A Merging column, when configured, is a human approval signal for a landing flow; do not merge from human review.
                        %s"""
                        .formatted(FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION)
                        .stripTrailing();
            }
            return """
                    When the work is ready for human review, update the workpad with the final summary and validation evidence, leave the workspace in a reviewable state, and summarize the status in the Codex response. No review or blocked destination column is configured, so do not move the card for handoff.
                    If the work is blocked, update the workpad with the blocker and summarize the blocker in the Codex response; an operator must move the card out of the active column manually. Leaving blocked work active can make Symphony run it again.
                    If a human returns a reviewed card to an active column, reread the card, the Trello comments rendered above, and linked PR feedback when available before changing code again.
                    A Merging column, when configured, is a human approval signal for a landing flow; do not merge from human review.
                    %s"""
                    .formatted(FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION)
                    .stripTrailing();
        }
        if (blank(reviewState)) {
            return """
                    When the work is ready for human review, update the workpad with the final summary and validation evidence, leave the workspace in a reviewable state, and summarize the status in the Codex response.
                    If the work is blocked or unsafe to hand off, update the workpad with the blocker, call trello_add_comment with the blocker, then call
                    trello_move_current_card with list_name "%s".
                    If a human returns a reviewed card to an active column, reread the card, the Trello comments rendered above, and linked PR feedback when available before changing code again.
                    A Merging column, when configured, is a human approval signal for a landing flow; do not merge from human review. %s"""
                    .formatted(blockedDestination, FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION);
        }
        if (blank(blockedState)) {
            return """
                    When the work is ready for human review, update the workpad with the final summary and validation evidence, call trello_add_comment with a concise summary and
                    verification notes, then call trello_move_current_card with list_name "%s". If the work is
                    blocked or unsafe to hand off, update the workpad with the blocker, add a Trello comment explaining the blocker, then call
                    trello_move_current_card with list_name "%s" so the card leaves the active column. Do not leave
                    blocked work in an active column; Symphony may run it again.
                    If a human returns a reviewed card to an active column, treat it as rework: reread the card, the Trello comments rendered above, and linked PR feedback when available before changing code again.
                    A Merging column, when configured, is a human approval signal for a landing flow; do not merge from human review. %s"""
                    .formatted(reviewState, blockedDestination, FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION);
        }
        return """
                When the work is ready for human review, update the workpad with the final summary and validation evidence, call trello_add_comment with a concise summary and
                verification notes, then call trello_move_current_card with list_name "%s". If the work is
                blocked or unsafe to hand off, update the workpad with the blocker, add a Trello comment explaining the blocker, then call
                trello_move_current_card with list_name "%s".
                If a human returns a reviewed card to an active column, treat it as rework: reread the card, the Trello comments rendered above, and linked PR feedback when available before changing code again.
                A Merging column, when configured, is a human approval signal for a landing flow; do not merge from human review. %s"""
                .formatted(reviewState, blockedDestination, FILESYSTEM_BLOCKER_COMMENT_INSTRUCTION);
    }

    private static String pickupPrompt(List<String> activeStates, String inProgressState) {
        if (blank(inProgressState)) {
            return """
                    This workflow has no in-progress column configured. Leave the card in its current active column
                    while working, then move it to the configured handoff column when the work is ready or blocked."""
                    .stripTrailing();
        }
        List<String> queueStates = activeStates.stream()
                .filter(state -> !state.equalsIgnoreCase(inProgressState))
                .toList();
        String queueText = queueStates.isEmpty() ? "an active queue column" : quotedList(queueStates);
        return """
                If the card is in %s, immediately call trello_move_current_card with list_name "%s" before
                implementation work. If the card is already in "%s", continue the existing execution flow."""
                .formatted(queueText, inProgressState, inProgressState);
    }

    private static List<String> allowedMoveStates(String inProgressState, String reviewState, String blockedState) {
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

    private static String defaultBlockedState(List<String> openListNames) {
        return openListNames.stream()
                .filter(name -> name.equalsIgnoreCase(RECOMMENDED_BLOCKED_STATE))
                .findFirst()
                .orElse(null);
    }

    private static List<String> withOptionalActiveState(List<String> activeStates, String extraState) {
        if (blank(extraState) || activeStates.stream().anyMatch(existing -> existing.equalsIgnoreCase(extraState))) {
            return activeStates;
        }
        List<String> combined = new ArrayList<>(activeStates);
        combined.add(extraState);
        return List.copyOf(combined);
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
                    "Unknown " + label + " column(s): " + String.join(", ", missing) + ". Open columns: "
                            + String.join(", ", openListNames));
        }
    }

    private static void validateConfiguredList(String label, String configured, List<String> openListNames) {
        if (blank(configured)) {
            return;
        }
        validateConfiguredLists(label, List.of(configured), openListNames);
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
            String inProgressState,
            boolean detectInProgressState,
            String blockedState,
            Path workflowPath,
            Path workspaceRoot,
            int maxConcurrentAgents,
            boolean force) {
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
                    maxConcurrentAgents,
                    force);
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
                    maxConcurrentAgents,
                    force);
        }

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
            List<String> columns,
            Path workflowPath) {}

    public record WorkspaceInfo(String id, String name, String displayName, String url) {}

    public record ImportBoardResult(
            String boardId,
            String boardKey,
            String boardName,
            String boardUrl,
            List<String> openColumns,
            List<String> activeStates,
            List<String> terminalStates,
            String inProgressState,
            String blockedState,
            Path workflowPath) {}

    private record BoardList(String id, String name, boolean closed) {}
}
