package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.TestHttpExchange.query;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.boardJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.createdListJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.listsJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.memberJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.respond;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.trelloList;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.workspaceJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.workspacesJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.config.ConfigDefaults;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TrelloBoardSetupTest {
    private FakeTrelloServer trello;
    private TrelloBoardSetup setup;
    private final List<String> createdLists = new ArrayList<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> workspaceResponse = new AtomicReference<>();
    private final AtomicReference<String> boardListsResponse = new AtomicReference<>();
    private final AtomicInteger boardInfoLookups = new AtomicInteger();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        trello = new FakeTrelloServer();
        // Port-selection must not depend on the host's real port occupancy (live workers can
        // hold 18080+), so the fixture fakes every loopback port as free.
        setup = new TrelloBoardSetup(new ObjectMapper()).withPortProbe(port -> false);
        workspaceResponse.set(workspacesJson(
                workspaceJson("workspace-1", "symphony-automation", "Symphony Automation", "symphony-automation")));
        boardListsResponse.set(listsJson(
                trelloList("list-inbox", "Inbox", 1),
                trelloList("list-ready", "Ready for Codex", 2),
                trelloList("list-in-progress", "In Progress", 3),
                trelloList("list-blocked", "Blocked", 4),
                trelloList("list-review", "Human Review", 5),
                trelloList("list-merging", "Merging", 6),
                trelloList("list-done", "Done", 7),
                trelloList("list-archive", "Archived old work", true, 8)));

        trello.on("/1/boards/", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            Map<String, String> query = query(exchange);
            assertThat(query)
                    .containsKey("name")
                    .containsEntry("defaultLists", "false")
                    .containsEntry("defaultLabels", "false")
                    .containsEntry("idOrganization", "workspace-1");
            respond(
                    exchange,
                    boardJson(
                            "board-1", query.get("name"), "abc123", "https://trello.com/b/abc123/symphony-work-queue"));
        });
        trello.on("/1/lists", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            Map<String, String> query = query(exchange);
            assertThat(query).containsEntry("idBoard", "board-1").containsEntry("pos", "bottom");
            createdLists.add(query.get("name"));
            respond(exchange, createdListJson("list-" + createdLists.size()));
        });
        trello.on("/1/boards/input", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            boardInfoLookups.incrementAndGet();
            respond(
                    exchange,
                    boardJson(
                            "board-1",
                            "Existing Board",
                            "SYNTH001",
                            "https://trello.com/b/SYNTH001/existing-board",
                            false));
        });
        trello.on("/1/boards/board-1/lists", exchange -> respond(exchange, boardListsResponse.get()));
        trello.on("/1/members/me/organizations", exchange -> respond(exchange, workspaceResponse.get()));
        trello.startEmpty();
    }

    @AfterEach
    void stopServer() {
        trello.stop();
    }

    @Test
    void newBoardDerivesBoardKeyFromUrlWhenCreateResponseOmitsShortLink() {
        // given
        trello.remove("/1/boards/");
        trello.on("/1/boards/", exchange -> {
            Map<String, String> query = query(exchange);
            respond(
                    exchange,
                    """
                    {"id":"000000000000000000000001","name":"%s","url":"https://trello.com/b/SYNTH777/synthetic-board"}
                    """
                            .formatted(query.get("name")));
        });
        trello.remove("/1/lists");
        trello.on("/1/lists", exchange -> {
            Map<String, String> query = query(exchange);
            createdLists.add(query.get("name"));
            respond(exchange, createdListJson("list-" + createdLists.size()));
        });
        Path workflow = tempDir.resolve("no-short-link-workflow.md");

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "Synthetic Board",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                false));

        // then
        assertThat(result.boardKey()).isEqualTo("SYNTH777");
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("board_id: \"SYNTH777\"");
    }

    @Test
    void newBoardClassifiesTrelloBoardLimitAsExpectedGuidance() {
        // given
        trello.remove("/1/boards/");
        trello.on("/1/boards/", exchange -> {
            byte[] body = "{\"message\":\"Cannot create board as organization is at its board limit\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        Path workflow = tempDir.resolve("board-limit-workflow.md");

        // when
        Throwable thrown = catchThrowable(() -> setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "Board Limit Board",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                false)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_trello_board_limit");
            assertThat(failure.getMessage()).contains("board limit").doesNotContain("{\"message\"");
        });
    }

    @Test
    void newBoardTrelloStatusExceptionUsesFirstResponseLine() {
        // given
        trello.remove("/1/boards/");
        trello.on("/1/boards/", exchange -> {
            byte[] body = "first public line\nsecond diagnostic line".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        Path workflow = tempDir.resolve("multiline-status-workflow.md");

        // when
        Throwable thrown = catchThrowable(() -> setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "Multiline Failure Board",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                false)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.getMessage()).doesNotContain("first public line", "second diagnostic line");
            assertThat(failure.getCause())
                    .hasMessageContaining("first public line")
                    .hasMessageNotContaining("second diagnostic line");
        });
        assertThat(workflow).doesNotExist();
    }

    @Test
    void importBoardReportsActionableErrorForUnresolvableBoardSelector() {
        // given
        trello.on("/1/boards/notreal", exchange -> {
            exchange.sendResponseHeaders(400, 0);
            exchange.getResponseBody().write("invalid id".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        Path workflow = tempDir.resolve("unresolvable-board-workflow.md");

        // when
        Throwable thrown = catchThrowable(() -> setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "notreal",
                List.of("Ready for Codex"),
                List.of("Done"),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                1,
                false)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_board_not_found");
            assertThat(failure.getMessage())
                    .contains("--board", "notreal", "short link")
                    .doesNotContain("invalid id");
        });
    }

    @Test
    void newBoardClassifiesUnauthorizedWorkspaceIdAsWorkspaceInputError() {
        // given
        trello.remove("/1/boards/");
        trello.on("/1/boards/", exchange -> {
            exchange.sendResponseHeaders(401, 0);
            exchange.getResponseBody().write("unauthorized org access".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        trello.on("/1/members/me", exchange -> respond(exchange, memberJson("member-1", "alex", "Alex Example")));
        Path workflow = tempDir.resolve("invalid-workspace-workflow.md");

        // when
        Throwable thrown = catchThrowable(() -> setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "Invalid Workspace Board",
                "not-a-workspace-id",
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                false)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_invalid_workspace_id");
            assertThat(failure.getMessage())
                    .contains("not-a-workspace-id", "list-workspaces")
                    .doesNotContain("API key");
        });
    }

    @Test
    void newBoardKeepsAuthFailureWhenCredentialsCannotReadMemberProfile() {
        // given
        trello.remove("/1/boards/");
        trello.on("/1/boards/", exchange -> {
            exchange.sendResponseHeaders(401, 0);
            exchange.getResponseBody().write("invalid token".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        trello.on("/1/members/me", exchange -> {
            exchange.sendResponseHeaders(401, 0);
            exchange.close();
        });
        Path workflow = tempDir.resolve("invalid-token-workflow.md");

        // when
        Throwable thrown = catchThrowable(() -> setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "bad-token"),
                "Bad Token Board",
                "workspace-1",
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                false)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> assertThat(failure.code())
                .isEqualTo("trello_auth_failed"));
    }

    @Test
    void newBoardRejectsUnsafeHighMaxAgentsBeforeCreatingTrelloBoard() {
        // given
        Path workflow = tempDir.resolve("high-max-agents-workflow.md");

        // when
        Throwable thrown = catchThrowable(() -> setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "High Max Agents Board",
                null,
                workflow,
                Path.of("./workspaces"),
                999999,
                false,
                false)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_invalid_max_agents");
            assertThat(failure.getMessage()).contains("between 1 and " + TrelloBoardSetup.MAX_SETUP_CONCURRENT_AGENTS);
        });
        assertThat(authorization.get()).as("no Trello board must be created").isNull();
        assertThat(createdLists).isEmpty();
    }

    @Test
    void importBoardRejectsUnsafeHighMaxAgentsBeforeTrelloRequests() {
        // given
        Path workflow = tempDir.resolve("high-max-agents-import.md");

        // when
        Throwable thrown = catchThrowable(() -> setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of("Ready for Codex"),
                List.of("Done"),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                999999,
                false)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_invalid_max_agents");
            assertThat(failure.getMessage()).contains("between 1 and " + TrelloBoardSetup.MAX_SETUP_CONCURRENT_AGENTS);
        });
        assertThat(boardInfoLookups.get())
                .as("no Trello lookups before validation")
                .isZero();
    }

    @Test
    void newBoardRejectsDirectoryWorkflowPathBeforeCreatingTrelloBoard() {
        // given
        Path workflowDirectory = tempDir;

        // when
        Throwable thrown = catchThrowable(() -> setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "Directory Workflow Board",
                null,
                workflowDirectory,
                Path.of("./workspaces"),
                1,
                true,
                false)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_invalid_path");
            assertThat(failure.getMessage()).contains("directory");
        });
        assertThat(authorization.get()).as("no Trello board must be created").isNull();
        assertThat(createdLists).isEmpty();
    }

    @Test
    void newBoardRejectsWorkflowPathUnderRegularFileBeforeCreatingTrelloBoard() throws Exception {
        // given
        Path plainFile = tempDir.resolve("not-a-directory");
        Files.writeString(plainFile, "plain", StandardCharsets.UTF_8);
        Path workflow = plainFile.resolve("WORKFLOW.generated.md");

        // when
        Throwable thrown = catchThrowable(() -> setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "File Parent Workflow Board",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                true,
                false)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_invalid_path");
            assertThat(failure.getMessage()).contains("not a directory");
        });
        assertThat(authorization.get()).as("no Trello board must be created").isNull();
        assertThat(createdLists).isEmpty();
    }

    @Test
    void createsRecommendedBoardListsAndWorkflow() {
        // given
        Path workflow = tempDir.resolve("generated-workflow.md");

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "Symphony Work Queue",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                false));
        int expectedPort = result.serverPort();

        // then
        assertThat(result.boardKey()).isEqualTo("abc123");
        assertThat(createdLists)
                .containsExactly(
                        "Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Merging", "Done");
        assertThat(authorization.get()).contains("oauth_consumer_key=\"key\"").contains("oauth_token=\"token\"");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("board_id: \"abc123\"")
                .contains("- \"Ready for Codex\"")
                .contains("- \"In Progress\"")
                .contains("in_progress_state: \"In Progress\"")
                .contains("- \"Done\"")
                .contains("trello_tools:")
                .contains("allowed_move_list_names:")
                .contains("- \"In Progress\"")
                .contains("- \"Human Review\"")
                .contains("- \"Blocked\"")
                .contains("- \"Done\"")
                .contains("Symphony moves cards from \"Ready for Codex\" to \"In Progress\" before Codex starts")
                .contains("list_name \"Human Review\"")
                .contains("## Merge From \"Merging\"")
                .contains("Only run the merge helper when the current Trello list is \"Merging\"")
                .contains("If the card moved from \"Human Review\" to \"Merging\" with no new feedback")
                .contains("If exact, unambiguous feedback added before the card entered \"Merging\"")
                .contains("If final work in the merge approval list required material fixups")
                .contains("Do not enable auto-merge")
                .contains("move the card to \"Blocked\" with a concise blocker")
                .contains("move the card to \"Done\"")
                .contains("treat it as rework")
                .contains("## Description")
                .contains("{{ card.description }}")
                .contains("## Trello Comments")
                .contains("{% for comment in card.comments %}")
                .contains("## Trello Checklists")
                .contains("{% for checklist in card.checklists %}")
                .contains("complete={{ item.complete }} text={{ item.text }}")
                .contains("## Trello Relationship Context")
                .contains("Scheduler-enforced prerequisites come only from normal Trello checklists")
                .contains("exactly one bare Trello card reference each")
                .contains("Markdown checklist links are context")
                .contains("Parsed prerequisite problems:")
                .contains("{% for problem in card.prerequisite_problems %}")
                .contains("Trello card references found on this card:")
                .contains("{% for reference in card.trello_references %}")
                .contains("source={{ reference.source }}")
                .contains("Before editing, review this context for credible missed prerequisites")
                .contains("Markdown checklist link appears to say another Trello card must finish")
                .contains("Trello-visible blocker or workpad path")
                .contains("`Proceed anyway`")
                .contains("must not bypass scheduler-enforced")
                .contains("## Codex Workpad")
                .contains("trello_upsert_workpad")
                .contains("do not create separate progress comments")
                .contains("## Operating Posture")
                .contains("This is an unattended orchestration run")
                .contains("Start by determining the current Trello list")
                .contains("## Execution Flow")
                .contains("commit, push")
                .contains("Only move to \"Human Review\"")
                .contains("## Acceptance Criteria And Validation")
                .contains("extract the card-specific acceptance criteria")
                .contains("Validation`, `Test Plan`, or `Testing` section as")
                .contains("capture a concrete current-state signal")
                .contains("Verification evidence must be specific to this card")
                .contains("Broad validation failures that are clearly unrelated")
                .contains("Temporary local proof edits are allowed only")
                .contains("Do not move the card to \"Human Review\"")
                .contains("## Pull Request Publication")
                .contains("For repository-changing work, \"Human Review\" means a human can review a pull request")
                .contains("and create or update the PR")
                .contains("Create a ready-for-review, non-draft PR by\ndefault")
                .contains("Create a draft PR only when the Trello card explicitly asks for a draft PR")
                .contains("inspect target repository pull request templates from the")
                .contains("repository's default/base template source")
                .contains("not templates that only exist on the unmerged task branch")
                .contains("single-template locations under `.github/`, the repository root, and `docs/`")
                .contains("case-insensitively with supported `.md` or `.txt` extensions")
                .contains("`PULL_REQUEST_TEMPLATE/` directories under `.github/`")
                .contains("the repository root, and `docs/`")
                .contains("Preserve the selected template's headings, checklists, and prompts")
                .contains("If no template exists, use the normal generated PR body")
                .contains("multiple directory template candidates exist")
                .contains("treat PR publication as blocked instead of guessing")
                .contains("PR: <https://github.com/owner/repo/pull/123>")
                .contains("do not write a bare PR URL followed by punctuation")
                .contains("reuse the task checkout's local Git author")
                .contains("user.name")
                .contains("user.email")
                .contains("symphony-trello.github-author-verified")
                .contains("authenticated GitHub login")
                .contains("GitHub noreply email")
                .contains("user:email")
                .contains("Do not guess a")
                .contains("noreply address format")
                .contains("generic fallback author")
                .contains("verify every commit in the PR's merge-base-to-HEAD range")
                .contains("Codex <codex@openai.com>")
                .contains("author-only history rewrite")
                .contains("git push\n--force-with-lease")
                .contains("contains unrelated human-owned work")
                .contains("does not apply when the card explicitly asks for a\nlocal-only investigation")
                .contains(
                        "If GitHub auth, push permission, branch protection, or repository policy prevents a required PR")
                .contains("## Pull Request Feedback Sweep")
                .contains("before moving the")
                .contains("card to \"Human Review\" or merging from Merging")
                .contains("top-level PR comments")
                .contains("inline review comments")
                .contains("GitHub review threads")
                .contains("leave thread resolution to the reviewer")
                .contains("name the short\ncommit hash containing the change")
                .contains("fenced code blocks with the final relevant code")
                .contains("file\nlink, or commit link does not replace the final snippet")
                .contains("When no file content changed, say that explicitly")
                .contains("Codex review issue comments")
                .contains("Classify PR checks before deciding handoff")
                .contains("If a failing check is related to the card's changes")
                .contains("If a related CI check fails")
                .contains("If checks are pending or stale, wait, refresh, or rerun them")
                .contains("external quota or infrastructure limits")
                .contains("do not spend time reproducing that")
                .contains("flaky, or unrelated check caveat")
                .contains("move the card to \"Blocked\"")
                .contains("## Rework From Human Review")
                .contains("from \"Human Review\" back to \"Ready for Codex\", \"In Progress\"")
                .contains("reread the full card description")
                .contains("existing workpad")
                .contains("what changed since the last handoff")
                .contains("do not restart from scratch")
                .contains("close the existing PR")
                .contains("add one concise handoff comment")
                .contains("## Trello List Routing")
                .contains("Symphony only dispatches cards from configured active lists")
                .contains("\"Ready for Codex\": queued work")
                .contains("\"In Progress\": work currently running in Codex")
                .contains("\"Blocked\": blocked work")
                .contains("\"Human Review\": human review")
                .contains("\"Merging\": human approval for merging")
                .contains("## Repository Skills")
                .contains(".codex/skills/symphony-trello-trello-workpad/SKILL.md")
                .contains(".codex/skills/symphony-trello-trello-handoff/SKILL.md")
                .contains(".codex/skills/symphony-trello-review-sweep/SKILL.md")
                .contains(".codex/skills/symphony-trello-land/SKILL.md")
                .contains("repository:")
                .contains("default_url: null")
                .contains("default_path: null")
                .contains("## Repository Source Precedence")
                .contains("1. An explicit Trello card repository URL or local checkout path.")
                .contains("2. Workflow `repository.default_url`.")
                .contains("3. Workflow `repository.default_path`.")
                .contains("4. No selected repository.")
                .contains("Repository URL: <url>")
                .contains("Repository path: <path>")
                .contains("Ordinary unlabelled web links are not selected as repositories")
                .contains("Each source declaration is read from one logical line")
                .contains("URL labels and `repository.default_url` accept credential-free HTTP(S)")
                .contains("Path and checkout labels accept local checkout paths")
                .contains("encoded or literal control characters are invalid")
                .contains("A valid selected source wins and suppresses lower-priority fallbacks")
                .contains("An invalid explicit Trello card source\nblocks instead of falling back")
                .contains("Repository preparation is workflow-owned in this phase")
                .contains("For a selected repository URL, create or reuse a\nwritable checkout")
                .contains("For a selected local checkout path, treat that\npath as source context")
                .contains("clone from it into the current per-card workspace")
                .contains("do not inherit the source checkout's current branch")
                .contains("repository's default branch")
                .contains("the Trello card clearly requests another base")
                .contains("Do not edit the shared checkout directly")
                .contains("direct work, the checkout is writable")
                .contains("Git metadata writes when the card asks for direct commits")
                .contains("does not by itself guarantee that direct checkout commits can\nupdate Git metadata")
                .contains("move the Trello card to \"Blocked\" with path-safe guidance instead of guessing")
                .contains("## Completion Bar Before \"Human Review\"")
                .contains("A pull request exists and is linked in the workpad and handoff comment")
                .contains("Filesystem access blocker details")
                .contains("requested file or folder is inaccessible")
                .contains("by default for security reasons")
                .contains("undeclared host paths")
                .contains("Do not copy absolute host paths")
                .contains("--add-path")
                .contains("codex.additional_writable_roots")
                .contains("Allowed Host Paths And Sandbox")
                .contains("server:")
                .contains("port: " + expectedPort)
                .contains("command: " + ConfigDefaults.DEFAULT_CODEX_COMMAND)
                .contains("model: \"gpt-5.5\"")
                .contains("reasoning_effort: \"medium\"")
                .contains("turn_sandbox_policy:", "type: workspaceWrite", "networkAccess: true")
                .contains("polling:")
                .contains("interval_ms: " + ConfigDefaults.GENERATED_WORKFLOW_POLLING_INTERVAL_MS)
                .contains("max_concurrent_agents: " + ConfigDefaults.DEFAULT_SETUP_MAX_CONCURRENT_AGENTS);
        assertThat(result.serverPort()).isEqualTo(expectedPort);
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().boardId()).isEqualTo("abc123");
        assertThat(config.codex().command()).isEqualTo(ConfigDefaults.DEFAULT_CODEX_COMMAND);
        assertThat(config.codex().model()).isEqualTo("gpt-5.5");
        assertThat(config.codex().reasoningEffort()).isEqualTo("medium");
        assertThat(config.codex().turnSandboxPolicy())
                .isEqualTo(Map.of("type", "workspaceWrite", "networkAccess", true));
        assertThat(config.codex().turnTimeout()).isEqualTo(ConfigDefaults.DEFAULT_CODEX_TURN_TIMEOUT);
        assertThat(config.codex().readTimeout()).isEqualTo(ConfigDefaults.DEFAULT_CODEX_READ_TIMEOUT);
        assertThat(config.codex().stallTimeout()).isEqualTo(ConfigDefaults.DEFAULT_CODEX_STALL_TIMEOUT);
        assertThat(config.repository().defaultUrl()).isNull();
        assertThat(config.repository().defaultPath()).isNull();
        assertThat(config.repository().selectedDefaultSource()).isEqualTo(EffectiveConfig.DefaultSource.NONE);
        assertThat(config.tracker().activeStates()).containsExactly("Ready for Codex", "In Progress", "Merging");
        assertThat(config.tracker().terminalStates())
                .contains("done", "archived", "archivedlist", "archivedboard", "deleted");
        assertThat(config.trelloTools().enabled()).isTrue();
        assertThat(config.trelloTools().allowWrites()).isTrue();
        assertThat(config.trelloTools().allowedMoveListNames())
                .containsExactly("in progress", "human review", "blocked", "done");
        assertThat(config.trelloTools().allowChecklists()).isFalse();
        assertThat(config.trelloTools().allowUrlAttachments()).isFalse();
        assertThat(config.polling().interval()).isEqualTo(ConfigDefaults.GENERATED_WORKFLOW_POLLING_INTERVAL);
    }

    @Test
    void shippedDebugSkillDoesNotAskAgentsToCopyExactFilesystemBlockerPaths() throws IOException {
        // given
        Path skill = Path.of(".codex/skills/debug/SKILL.md");

        // when
        String source = Files.readString(skill, StandardCharsets.UTF_8);

        // then
        assertThat(source)
                .contains("requested path", "per-card workspace", "without\n   copying private host paths")
                .doesNotContain("exact missing path");
    }

    @Test
    void importExistingBoardRejectsCaseVariantRecommendedListNames() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready-lower","name":"ready for codex","closed":false,"pos":1},
                  {"id":"list-ready-title","name":"Ready for Codex","closed":false,"pos":2},
                  {"id":"list-progress-lower","name":"in progress","closed":false,"pos":3},
                  {"id":"list-progress-title","name":"In Progress","closed":false,"pos":4},
                  {"id":"list-blocked-lower","name":"blocked","closed":false,"pos":5},
                  {"id":"list-blocked-title","name":"Blocked","closed":false,"pos":6},
                  {"id":"list-review-lower","name":"human review","closed":false,"pos":7},
                  {"id":"list-review-title","name":"Human Review","closed":false,"pos":8},
                  {"id":"list-done-lower","name":"done","closed":false,"pos":9},
                  {"id":"list-done-title","name":"Done","closed":false,"pos":10}
                ]
                """);
        Path workflow = tempDir.resolve("imported-duplicated-recommended-lists.md");

        // when
        Throwable thrown = catchThrowable(() -> setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                null,
                true,
                null,
                workflow,
                Path.of("./agent-workspaces"),
                null,
                1,
                false,
                TrelloBoardSetup.GitHubIntegration.DISABLED)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_ambiguous_in_progress_state");
            assertThat(failure)
                    .hasMessageContaining(
                            "Multiple open Trello lists match in-progress list selector(s): \"in progress\"");
        });
        assertThat(workflow).doesNotExist();
    }

    @Test
    void createsNonGithubRecommendedBoardListsAndWorkflow() {
        // given
        Path workflow = tempDir.resolve("non-github-workflow.md");

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "Local Queue",
                null,
                workflow,
                Path.of("./workspaces"),
                null,
                1,
                false,
                false,
                TrelloBoardSetup.GitHubIntegration.DISABLED));

        // then
        assertThat(result.boardKey()).isEqualTo("abc123");
        assertThat(createdLists)
                .containsExactly("Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Done");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("- \"Ready for Codex\"")
                .contains("- \"In Progress\"")
                .contains("## Trello Checklists")
                .contains("## Trello Relationship Context")
                .contains("Scheduler-enforced prerequisites come only from normal Trello checklists")
                .contains("`Proceed anyway`")
                .contains("## Repository Source Precedence")
                .contains("Workflow `repository.default_url`")
                .contains("Workflow `repository.default_path`")
                .contains("clone from it into the current per-card workspace")
                .contains("do not inherit the source checkout's current branch")
                .contains("repository's default branch")
                .contains("the Trello card clearly requests another base")
                .contains("explicitly requests direct work")
                .contains("direct work, the checkout is writable")
                .contains("Git metadata writes when the card asks for direct commits")
                .contains("does not by itself guarantee that direct checkout commits can\nupdate Git metadata")
                .contains("move the Trello card to \"Blocked\" with path-safe guidance instead of guessing")
                .contains("## Local And Non-GitHub Repository Work")
                .contains("do not require GitHub auth")
                .contains("## Non-GitHub Review Feedback")
                .contains("reread the full card description and new Trello comments")
                .contains("This workflow has no GitHub merge flow configured")
                .contains("turn_sandbox_policy:", "type: workspaceWrite", "networkAccess: true")
                .doesNotContain("Merging")
                .doesNotContain("## Pull Request Publication")
                .doesNotContain("linked PR comments")
                .doesNotContain("PR feedback sweep")
                .doesNotContain("## Merge From \"Merging\"");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().activeStates()).containsExactly("Ready for Codex", "In Progress");
        assertThat(config.codex().turnSandboxPolicy())
                .isEqualTo(Map.of("type", "workspaceWrite", "networkAccess", true));
        assertThat(config.trelloTools().allowedMoveListNames())
                .containsExactly("in progress", "human review", "blocked", "done");
    }

    @Test
    void requiresWorkspaceIdWhenTokenCanAccessMultipleWorkspaces() {
        // given
        workspaceResponse.set(workspacesJson(
                workspaceJson("workspace-1", "first", "First Workspace", "first"),
                workspaceJson("workspace-2", "second", "Second Workspace", "second")));
        Path workflow = tempDir.resolve("generated-workflow.md");

        var request = new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "Symphony Work Queue",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                false);

        // when
        ThrowingCallable action = () -> setup.createRecommendedBoard(request);

        // then
        assertThatThrownBy(action)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("--workspace-id")
                .hasMessageContaining("workspace-1")
                .hasMessageContaining("workspace-2");
        assertThat(workflow).doesNotExist();
        assertThat(createdLists).isEmpty();
    }

    @Test
    void importsExistingBoardAndWritesWorkflowUsingDiscoveredRecommendedLists() {
        // given
        Path workflow = tempDir.resolve("imported-workflow.md");

        // when
        var result = setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                2,
                false));
        int expectedPort = result.serverPort();

        // then
        assertThat(result.openLists())
                .containsExactly(
                        "Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Merging", "Done");
        assertThat(result.activeStates()).containsExactly("Ready for Codex", "In Progress", "Merging");
        assertThat(result.terminalStates()).containsExactly("Done");
        assertThat(result.inProgressState()).isEqualTo("In Progress");
        assertThat(result.blockedState()).isEqualTo("Blocked");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("board_id: \"SYNTH001\"")
                .contains("root: \"./agent-workspaces\"")
                .contains("port: " + expectedPort)
                .contains("model: \"gpt-5.5\"")
                .contains("reasoning_effort: \"medium\"")
                .contains("polling:")
                .contains("interval_ms: " + ConfigDefaults.GENERATED_WORKFLOW_POLLING_INTERVAL_MS)
                .contains("allowed_move_list_names:")
                .contains("- \"In Progress\"")
                .contains("- \"Human Review\"")
                .contains("- \"Blocked\"")
                .contains("- \"Done\"")
                .contains("in_progress_state: \"In Progress\"")
                .contains("Symphony moves cards from \"Ready for Codex\" to \"In Progress\" before Codex starts")
                .contains("## Trello Comments")
                .contains("## Trello Checklists")
                .contains("## Trello Relationship Context")
                .contains("Scheduler-enforced prerequisites come only from normal Trello checklists")
                .contains("`Proceed anyway`")
                .contains("## Codex Workpad")
                .contains("trello_upsert_workpad")
                .contains("## Operating Posture")
                .contains("## Execution Flow")
                .contains("## Acceptance Criteria And Validation")
                .contains("extract the card-specific acceptance criteria")
                .contains("Validation`, `Test Plan`, or `Testing` section as")
                .contains("current-state signal")
                .contains("final validation")
                .contains("## Pull Request Publication")
                .contains("create or update the PR for the current branch")
                .contains("inspect target repository pull request templates from the")
                .contains("repository's default/base template source")
                .contains("single-template locations under `.github/`, the repository root, and `docs/`")
                .contains("`PULL_REQUEST_TEMPLATE/` directories under `.github/`")
                .contains("the repository root, and `docs/`")
                .contains("Preserve the selected template's headings, checklists, and prompts")
                .contains("If no template exists, use the normal generated PR body")
                .contains("treat PR publication as blocked instead of guessing")
                .contains("reuse the task checkout's local Git author")
                .contains("symphony-trello.github-author-verified")
                .contains("authenticated GitHub login")
                .contains("GitHub noreply email")
                .contains("user:email")
                .contains("Do not guess a")
                .contains("noreply address format")
                .contains("verify every commit in the PR's merge-base-to-HEAD range")
                .contains("Codex <codex@openai.com>")
                .contains("author-only history rewrite")
                .contains("git push\n--force-with-lease")
                .contains("local-only/no-push work")
                .contains("## Pull Request Feedback Sweep")
                .contains("Every actionable human, bot, or Codex")
                .contains("GitHub review threads")
                .contains("leave thread resolution to the reviewer")
                .contains("name the short\ncommit hash containing the change")
                .contains("fenced code blocks with the final relevant code")
                .contains("file\nlink, or commit link does not replace the final snippet")
                .contains("When no file content changed, say that explicitly")
                .contains("## Rework From Human Review")
                .contains("treat the next run as rework")
                .contains("linked PR comments")
                .contains("current PR/check state")
                .contains("## Trello List Routing")
                .contains("\"Ready for Codex\": queued work")
                .contains("\"In Progress\": work currently running in Codex")
                .contains("repository:")
                .contains("default_url: null")
                .contains("default_path: null")
                .contains("## Repository Source Precedence")
                .contains("1. An explicit Trello card repository URL or local checkout path.")
                .contains("2. Workflow `repository.default_url`.")
                .contains("3. Workflow `repository.default_path`.")
                .contains("4. No selected repository.")
                .contains("Repository URL: <url>")
                .contains("Ordinary unlabelled web links are not selected as repositories")
                .contains("clone from it into the current per-card workspace")
                .contains("do not inherit the source checkout's current branch")
                .contains("repository's default branch")
                .contains("the Trello card clearly requests another base")
                .contains("explicitly requests direct work")
                .contains("direct work, the checkout is writable")
                .contains("Git metadata writes when the card asks for direct commits")
                .contains("does not by itself guarantee that direct checkout commits can\nupdate Git metadata")
                .contains("move the Trello card to \"Blocked\" with path-safe guidance instead of guessing")
                .contains("workspace-local skills")
                .contains("after workspace sync hooks")
                .contains(".codex/skills/symphony-trello-commit/SKILL.md")
                .contains(".codex/skills/symphony-trello-push-pr/SKILL.md")
                .contains("tracker.in_progress_state is configured")
                .contains("list_name \"Human Review\"")
                .contains("## Merge From \"Merging\"")
                .contains("Only run the merge helper when the current Trello list is \"Merging\"")
                .contains("If exact, unambiguous feedback added before the card entered \"Merging\"")
                .contains("## Completion Bar Before \"Human Review\"")
                .contains("move the card to \"Done\"")
                .contains("max_concurrent_agents: 2");
        assertThat(result.serverPort()).isEqualTo(expectedPort);
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().boardId()).isEqualTo("SYNTH001");
        assertThat(config.codex().model()).isEqualTo("gpt-5.5");
        assertThat(config.codex().reasoningEffort()).isEqualTo("medium");
        assertThat(config.workspace().root()).isEqualTo(workflow.getParent().resolve("agent-workspaces"));
        assertThat(config.polling().interval()).isEqualTo(ConfigDefaults.GENERATED_WORKFLOW_POLLING_INTERVAL);
        assertThat(config.repository().selectedDefaultSource()).isEqualTo(EffectiveConfig.DefaultSource.NONE);
    }

    @Test
    void importUsesReviewFallbackForRepositoryBlockerWhenNoBlockedListIsConfigured() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-review","name":"Human Review","closed":false,"pos":2},
                  {"id":"list-done","name":"Done","closed":false,"pos":3}
                ]
                """);
        Path workflow = tempDir.resolve("imported-review-fallback.md");

        // when
        var result = importBoardWithoutRepositoryBlockerDestination(workflow);

        // then
        assertThat(result.blockedState()).isNull();
        EffectiveConfig config = resolve(workflow);
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("human review", "done");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("## Trello Checklists")
                .contains("## Trello Relationship Context")
                .contains("Scheduler-enforced prerequisites come only from normal Trello checklists")
                .contains("`Proceed anyway`")
                .contains("## Repository Source Precedence")
                .contains("Repository URL: <url>")
                .contains("Ordinary unlabelled web links are not selected as repositories")
                .contains("For a selected local checkout path, treat that")
                .contains("clone from it into the current per-card workspace")
                .contains("do not inherit the source checkout's current branch")
                .contains("repository's default branch")
                .contains("the Trello card clearly requests another base")
                .contains("explicitly requests direct work")
                .contains("direct work, the checkout is writable")
                .contains("Git metadata writes when the card asks for direct commits")
                .contains("does not by itself guarantee that direct checkout commits can\nupdate Git metadata")
                .contains("move the Trello card to \"Human Review\" with path-safe guidance instead of guessing");
    }

    @Test
    void importRecordsRepositoryBlockerGuidanceWhenNoMoveDestinationExists() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-done","name":"Done","closed":false,"pos":2}
                ]
                """);
        Path workflow = tempDir.resolve("imported-no-blocker-destination.md");

        // when
        var result = importBoardWithoutRepositoryBlockerDestination(workflow);

        // then
        assertThat(result.blockedState()).isNull();
        EffectiveConfig config = resolve(workflow);
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("done");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("## Trello Checklists")
                .contains("## Trello Relationship Context")
                .contains("Scheduler-enforced prerequisites come only from normal Trello checklists")
                .contains("`Proceed anyway`")
                .contains("## Repository Source Precedence")
                .contains("For a selected local checkout path, treat that")
                .contains("clone from it into the current per-card workspace")
                .contains("do not inherit the source checkout's current branch")
                .contains("repository's default branch")
                .contains("the Trello card clearly requests another base")
                .contains("explicitly requests direct work")
                .contains("direct work, the checkout is writable")
                .contains("Git metadata writes when the card asks for direct commits")
                .contains("does not by itself guarantee that direct checkout commits can\nupdate Git metadata")
                .contains("record a path-safe blocker in the workpad")
                .contains("operator must move the Trello card to the appropriate blocked list")
                .doesNotContain("move the Trello card to \"Done\" with path-safe guidance");
    }

    @Test
    void importBoardTreatsDroppedMissingGithubListPostAsUnknownWriteOutcome() {
        // given
        Path workflow = tempDir.resolve("dropped-missing-list-post.md");

        // when
        Throwable thrown = importBoardWithMissingMergingListFailure(workflow, HttpExchange::close);

        // then
        assertUnknownWriteOutcome(thrown, workflow);
    }

    @Test
    void importBoardTreatsIncompleteMissingGithubListPostAsUnknownWriteOutcome() {
        // given
        Path workflow = tempDir.resolve("incomplete-missing-list-post.md");

        // when
        Throwable thrown = importBoardWithMissingMergingListFailure(workflow, exchange -> respond(exchange, "{}"));

        // then
        assertUnknownWriteOutcome(thrown, workflow);
    }

    @Test
    void importBoardTreatsNullMissingGithubListPostAsUnknownWriteOutcome() {
        // given
        Path workflow = tempDir.resolve("null-missing-list-post.md");

        // when
        Throwable thrown = importBoardWithMissingMergingListFailure(workflow, exchange -> respond(exchange, "null"));

        // then
        assertUnknownWriteOutcome(thrown, workflow);
    }

    private Throwable importBoardWithMissingMergingListFailure(Path workflow, HttpHandler missingListHandler) {
        boardListsResponse.set(listsJson(
                trelloList("list-inbox", "Inbox", 1),
                trelloList("list-ready", "Ready for Codex", 2),
                trelloList("list-review", "Human Review", 3),
                trelloList("list-blocked", "Blocked", 4),
                trelloList("list-done", "Done", 5)));
        trello.remove("/1/lists").on("/1/lists", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            Map<String, String> query = query(exchange);
            assertThat(query)
                    .containsEntry("name", TrelloBoardSetup.RECOMMENDED_MERGING_STATE)
                    .containsEntry("idBoard", "board-1");
            createdLists.add(query.get("name"));
            missingListHandler.handle(exchange);
        });
        return catchThrowable(() -> setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                null,
                true,
                null,
                workflow,
                Path.of("./agent-workspaces"),
                null,
                ConfigDefaults.DEFAULT_SETUP_MAX_CONCURRENT_AGENTS,
                false,
                TrelloBoardSetup.GitHubIntegration.ENABLED,
                true)));
    }

    private TrelloBoardSetup.ImportBoardResult importBoardWithoutRepositoryBlockerDestination(Path workflow) {
        return setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                null,
                false,
                "-",
                workflow,
                Path.of("./agent-workspaces"),
                null,
                2,
                false,
                TrelloBoardSetup.GitHubIntegration.DISABLED,
                false));
    }

    private void assertUnknownWriteOutcome(Throwable thrown, Path workflow) {
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("Trello write outcome is unknown")
                .hasMessageNotContaining(workflow.toString())
                .hasMessageNotContaining(tempDir.toString());
        assertThat(((TrelloBoardSetupException) thrown).code()).isEqualTo("trello_write_outcome_unknown");
        assertThat(createdLists).containsExactly(TrelloBoardSetup.RECOMMENDED_MERGING_STATE);
        assertThat(workflow).doesNotExist();
    }

    @Test
    void omitsModelFieldsWhenFirstClassCodexFieldsAreUnsupported() {
        // given
        Path workflow = tempDir.resolve("unsupported-codex-model-fields.md");
        TrelloBoardSetup setupWithoutFirstClassFields = new TrelloBoardSetup(
                new ObjectMapper(), TrelloBoardSetup.CodexModelDefaults.unsupportedFirstClassFields());

        // when
        setupWithoutFirstClassFields.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "Compatibility Queue",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                false));

        // then
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("command: " + ConfigDefaults.DEFAULT_CODEX_COMMAND)
                .doesNotContain("model:")
                .doesNotContain("reasoning_effort:");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.codex().model()).isNull();
        assertThat(config.codex().reasoningEffort()).isNull();
    }

    @Test
    void preservesExistingWorkflowModelAndReasoningWhenForceRegeneratingWorkflow() throws Exception {
        // given
        Path workflow = tempDir.resolve("existing-codex-model.md");
        writeExistingWorkflow(
                workflow,
                """
                  model: "gpt-hand-edited"
                  reasoning_effort: "high"
                """);

        // when
        forceRegenerateExistingWorkflow(workflow);

        // then
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-hand-edited\"")
                .contains("reasoning_effort: \"high\"")
                .doesNotContain("model: \"gpt-new\"");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.codex().model()).isEqualTo("gpt-hand-edited");
        assertThat(config.codex().reasoningEffort()).isEqualTo("high");
    }

    @Test
    void preservesExistingWorkflowReasoningOmissionWhenForceRegeneratingWorkflow() throws Exception {
        // given
        Path workflow = tempDir.resolve("existing-codex-model-only.md");
        writeExistingWorkflow(workflow, """
                  model: "gpt-hand-edited"
                """);

        // when
        forceRegenerateExistingWorkflow(workflow);

        // then
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-hand-edited\"")
                .doesNotContain("reasoning_effort:");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.codex().model()).isEqualTo("gpt-hand-edited");
        assertThat(config.codex().reasoningEffort()).isNull();
    }

    @Test
    void preservesExistingWorkflowModelOmissionWhenForceRegeneratingWorkflow() throws Exception {
        // given
        Path workflow = tempDir.resolve("existing-codex-reasoning-only.md");
        writeExistingWorkflow(workflow, """
                  reasoning_effort: "high"
                """);

        // when
        forceRegenerateExistingWorkflow(workflow);

        // then
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("reasoning_effort: \"high\"")
                .doesNotContain("model:");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.codex().model()).isNull();
        assertThat(config.codex().reasoningEffort()).isEqualTo("high");
    }

    @Test
    void preservesExistingWorkflowModelAndReasoningOmissionWhenForceRegeneratingWorkflow() throws Exception {
        // given
        Path workflow = tempDir.resolve("existing-codex-command-only.md");
        writeExistingWorkflow(workflow, "");

        // when
        forceRegenerateExistingWorkflow(workflow);

        // then
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("command: " + ConfigDefaults.DEFAULT_CODEX_COMMAND)
                .doesNotContain("model:")
                .doesNotContain("reasoning_effort:");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.codex().model()).isNull();
        assertThat(config.codex().reasoningEffort()).isNull();
    }

    @Test
    void modelOnlyOverridePreservesExistingReasoningWhenCatalogHasSelectedModelDefault() throws Exception {
        // given
        Path workflow = tempDir.resolve("existing-codex-reasoning-model-override.md");
        writeExistingWorkflow(
                workflow,
                """
                  model: "gpt-existing"
                  reasoning_effort: "low"
                """);
        CodexModelSelectionDefaults catalog = new CodexModelSelectionDefaults(
                new TrelloBoardSetup.CodexModelDefaults("gpt-5.5", "medium"),
                Map.of("gpt-5.5", "medium", "gpt-new", "high"));
        TrelloBoardSetup setupWithModelOverride = new TrelloBoardSetup(new ObjectMapper(), catalog)
                .withCodexModelOverrides(catalog, Optional.of("gpt-new"), Optional.empty());

        // when
        setupWithModelOverride.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                2,
                true));

        // then
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("model: \"gpt-new\"", "reasoning_effort: \"low\"")
                .doesNotContain("model: \"gpt-existing\"", "reasoning_effort: \"high\"");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.codex().model()).isEqualTo("gpt-new");
        assertThat(config.codex().reasoningEffort()).isEqualTo("low");
    }

    @Test
    void reusesResolvedModelCatalogWhenDerivingWorkflowSelectionDefaults() throws Exception {
        // given
        Path workflow = tempDir.resolve("existing-codex-model-only-for-catalog.md");
        writeExistingWorkflow(workflow, """
                  model: "gpt-existing"
                """);
        AtomicInteger resolutions = new AtomicInteger();
        TrelloBoardSetup catalogBackedSetup = new TrelloBoardSetup(new ObjectMapper(), () -> {
            resolutions.incrementAndGet();
            return new CodexModelSelectionDefaults(
                    new TrelloBoardSetup.CodexModelDefaults("gpt-5.5", "medium"),
                    Map.of("gpt-5.5", "medium", "gpt-6", "high"));
        });

        // when
        CodexModelSelectionDefaults defaults = catalogBackedSetup.codexModelSelectionDefaultsForWorkflow(workflow);

        // then
        assertThat(resolutions.get()).isOne();
        assertThat(defaults.defaults()).isEqualTo(TrelloBoardSetup.CodexModelDefaults.partial("gpt-existing", null));
        assertThat(defaults.reasoningEffortForModel("gpt-6")).contains("high");
    }

    @Test
    void resolvesDefaultCodexCommandWhenWorkflowOmitsCommand() throws IOException {
        // given
        Path workflow = tempDir.resolve("workflow-without-codex-command.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: abc123
                codex:
                  approval_policy: never
                ---
                # Workflow
                """,
                StandardCharsets.UTF_8);

        // when
        EffectiveConfig config = resolve(workflow);

        // then
        assertThat(config.codex().command()).isEqualTo(ConfigDefaults.DEFAULT_CODEX_COMMAND);
    }

    @Test
    void codexModelDefaultsRejectUnsupportedStateWithoutFallbackValues() {
        // given
        ThrowingCallable createInvalidDefaults = () -> new TrelloBoardSetup.CodexModelDefaults(null, null, false);

        // when
        Throwable thrown = catchThrowable(createInvalidDefaults);

        // then
        assertThat(thrown)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported first-class Codex defaults");
    }

    @Test
    void importCanDisableDetectedInProgressList() {
        // given
        Path workflow = tempDir.resolve("imported-without-in-progress.md");

        // when
        var result = setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                null,
                false,
                null,
                workflow,
                Path.of("./agent-workspaces"),
                2,
                false));

        // then
        assertThat(result.inProgressState()).isNull();
        assertThat(result.activeStates()).containsExactly("Ready for Codex", "Merging");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("This workflow has no in-progress list configured")
                .contains("If the card is in \"Merging\", follow the merge section instead")
                .doesNotContain("list_name \"In Progress\"");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().activeStates()).containsExactly("Ready for Codex", "Merging");
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("human review", "blocked", "done");
    }

    @Test
    void importsExistingBoardWithExplicitNonDefaultLists() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-intake","name":"Intake","closed":false,"pos":1},
                  {"id":"list-queue","name":"Queue for Codex","closed":false,"pos":2},
                  {"id":"list-escalated","name":"Escalated for Codex","closed":false,"pos":3},
                  {"id":"list-review","name":"Review","closed":false,"pos":4},
                  {"id":"list-needs-help","name":"Needs Help","closed":false,"pos":5},
                  {"id":"list-released","name":"Released","closed":false,"pos":6},
                  {"id":"list-parked","name":"Parked","closed":false,"pos":7},
                  {"id":"list-archive","name":"Archived experiments","closed":true,"pos":8}
                ]
                """);
        Path workflow = tempDir.resolve("imported-custom-workflow.md");

        // when
        var result = setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of("Queue for Codex", "Escalated for Codex"),
                List.of("Released", "Parked"),
                "Needs Help",
                workflow,
                Path.of("./agent-workspaces"),
                2,
                false));

        // then
        assertThat(result.openLists())
                .containsExactly(
                        "Intake",
                        "Queue for Codex",
                        "Escalated for Codex",
                        "Review",
                        "Needs Help",
                        "Released",
                        "Parked");
        assertThat(result.activeStates()).containsExactly("Queue for Codex", "Escalated for Codex");
        assertThat(result.terminalStates()).containsExactly("Released", "Parked");
        assertThat(result.blockedState()).isEqualTo("Needs Help");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().activeStates()).containsExactly("Queue for Codex", "Escalated for Codex");
        assertThat(config.tracker().terminalStates())
                .contains("released", "parked", "archived", "archivedlist", "archivedboard", "deleted");
        assertThat(config.trelloTools().enabled()).isTrue();
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("review", "needs help", "released");
        assertThat(config.agent().maxConcurrentAgents()).isEqualTo(2);
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("## Repository Source Precedence")
                .contains("For a selected local checkout path, treat that")
                .contains("clone from it into the current per-card workspace")
                .contains("do not inherit the source checkout's current branch")
                .contains("repository's default branch")
                .contains("the Trello card clearly requests another base")
                .contains("explicitly requests direct work")
                .contains("direct work, the checkout is writable")
                .contains("Git metadata writes when the card asks for direct commits")
                .contains("does not by itself guarantee that direct checkout commits can\nupdate Git metadata")
                .contains("move the Trello card to \"Needs Help\" with path-safe guidance instead of guessing")
                .contains("Do not move the card to \"Review\"")
                .contains("card to \"Review\" or merging from Merging")
                .contains("Before returning the card to \"Review\"")
                .contains("This workflow has no merge approval list configured");
    }

    @Test
    void importUsesConfiguredReviewAndTerminalListsForLandingWhenMergingExists() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-review","name":"Review","closed":false,"pos":2},
                  {"id":"list-merging","name":"Merging","closed":false,"pos":3},
                  {"id":"list-blocked","name":"Blocked","closed":false,"pos":4},
                  {"id":"list-released","name":"Released","closed":false,"pos":5}
                ]
                """);
        Path workflow = tempDir.resolve("imported-custom-landing.md");

        // when
        var result = setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of("Released"),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                2,
                false));

        // then
        assertThat(result.activeStates()).containsExactly("Ready for Codex", "Merging");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("review", "blocked", "released");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("## Merge From \"Merging\"")
                .contains("merge from \"Review\"")
                .contains("renewed \"Review\"")
                .contains("move back to\n  \"Review\" with the reason")
                .contains("move the card to \"Released\"")
                .doesNotContain("renewed Human Review")
                .doesNotContain("move back to\n  \"Human Review\"")
                .doesNotContain("move the card to \"Done\"");
    }

    @Test
    void importUsesBlockedDestinationForLandingFixupsWhenNoReviewListExists() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-merging","name":"Merging","closed":false,"pos":2},
                  {"id":"list-blocked","name":"Blocked","closed":false,"pos":3},
                  {"id":"list-done","name":"Done","closed":false,"pos":4}
                ]
                """);
        Path workflow = tempDir.resolve("imported-landing-without-review.md");

        // when
        var result = setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                2,
                false));

        // then
        assertThat(result.activeStates()).containsExactly("Ready for Codex", "Merging");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("blocked", "done");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("## Merge From \"Merging\"")
                .contains("If exact, unambiguous feedback added before the card entered \"Merging\"")
                .contains("If merging required material fixups")
                .contains("move the card to \"Blocked\" with a concise blocker")
                .doesNotContain("renewed human review")
                .doesNotContain("move back to\n  human review")
                .doesNotContain("move back to\n  \"Human Review\"");
    }

    @Test
    void importDoesNotActivateLandingWhenMergingExistsWithoutTerminalList() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-in-progress","name":"In Progress","closed":false,"pos":2},
                  {"id":"list-review","name":"Human Review","closed":false,"pos":3},
                  {"id":"list-merging","name":"Merging","closed":false,"pos":4},
                  {"id":"list-blocked","name":"Blocked","closed":false,"pos":5}
                ]
                """);
        Path workflow = tempDir.resolve("imported-no-landing-destination.md");

        // when
        var result = setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                2,
                false));

        // then
        assertThat(result.activeStates()).containsExactly("Ready for Codex", "In Progress");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.trelloTools().allowedMoveListNames())
                .containsExactly("in progress", "human review", "blocked");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("This workflow has no merge approval list configured")
                .doesNotContain("## Merge From \"Merging\"")
                .doesNotContain("move the card to \"Done\"");
    }

    @Test
    void importDoesNotCreateMergingWhenThereIsNoLandingDestination() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-in-progress","name":"In Progress","closed":false,"pos":2},
                  {"id":"list-review","name":"Human Review","closed":false,"pos":3},
                  {"id":"list-blocked","name":"Blocked","closed":false,"pos":4}
                ]
                """);
        Path workflow = tempDir.resolve("imported-no-created-landing.md");

        // when
        var result = setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                2,
                false));

        // then
        assertThat(result.openLists()).doesNotContain("Merging");
        assertThat(result.activeStates()).containsExactly("Ready for Codex", "In Progress");
        assertThat(createdLists).isEmpty();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("This workflow has no merge approval list configured")
                .doesNotContain("## Merge From \"Merging\"");
    }

    @Test
    void importPrefersHumanReviewWhenPlainReviewListAlsoExists() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-plain-review","name":"Review","closed":false,"pos":2},
                  {"id":"list-human-review","name":"Human Review","closed":false,"pos":3},
                  {"id":"list-blocked","name":"Blocked","closed":false,"pos":4},
                  {"id":"list-done","name":"Done","closed":false,"pos":5}
                ]
                """);
        Path workflow = tempDir.resolve("imported-human-review.md");

        // when
        setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                2,
                false));

        // then
        EffectiveConfig config = resolve(workflow);
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("human review", "blocked", "done");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("list_name \"Human Review\"")
                .doesNotContain("list_name \"Review\"");
    }

    @Test
    void importEnablesDoneHandoffMovesWhenNoReviewListExists() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-done","name":"Done","closed":false,"pos":2}
                ]
                """);
        Path workflow = tempDir.resolve("imported-without-review.md");

        // when
        setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                2,
                false));

        // then
        EffectiveConfig config = resolve(workflow);
        assertThat(config.trelloTools().enabled()).isTrue();
        assertThat(config.trelloTools().allowWrites()).isTrue();
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("done");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("allowed_move_list_names:")
                .contains("- \"Done\"")
                .contains("## Acceptance Criteria And Validation");
    }

    @Test
    void importMovesBlockedCardsToReviewWhenNoBlockedListExists() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-review","name":"Review","closed":false,"pos":2},
                  {"id":"list-done","name":"Done","closed":false,"pos":3}
                ]
                """);
        Path workflow = tempDir.resolve("imported-without-blocked.md");

        // when
        var result = setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                2,
                false));

        // then
        assertThat(result.blockedState()).isNull();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("allowed_move_list_names:")
                .contains("- \"Review\"")
                .contains("completion bar, move the card to \"Review\"")
                .contains("blocked or unsafe to hand off")
                .contains("list_name \"Review\" so the card leaves the active list")
                .contains("Do not leave")
                .contains("blocked work in an active list")
                .contains("Filesystem access blocker details")
                .contains("requested file or folder is inaccessible")
                .contains("the per-card workspace")
                .contains("Do not copy absolute host paths")
                .doesNotContain("shown by `pwd`");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("review", "done");
    }

    @Test
    void listsWorkspacesAvailableToTheToken() {
        // given
        var request = new TrelloBoardSetup.WorkspaceListRequest(
                endpoint(), new TrelloBoardSetup.TrelloCredentials("key", "token"));

        // when
        var workspaces = setup.listWorkspaces(request);

        // then
        assertThat(workspaces).singleElement().satisfies(workspace -> {
            assertThat(workspace.id()).isEqualTo("workspace-1");
            assertThat(workspace.displayName()).isEqualTo("Symphony Automation");
        });
    }

    @Test
    void importRefusesToOverwriteExistingWorkflowUnlessForced() throws IOException {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-review","name":"Human Review","closed":false,"pos":2},
                  {"id":"list-blocked","name":"Blocked","closed":false,"pos":3},
                  {"id":"list-done","name":"Done","closed":false,"pos":4}
                ]
                """);
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(workflow, "keep me", StandardCharsets.UTF_8);

        var request = new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of("Ready for Codex"),
                List.of("Done"),
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false);

        // when
        ThrowingCallable action = () -> setup.importExistingBoard(request);

        // then
        assertThatThrownBy(action).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("--force");
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo("keep me");
        assertThat(createdLists).isEmpty();
    }

    @Test
    void importBoardRejectsReservedRequestedServerPortBeforeTrelloRequest() {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        var request = new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of("Ready for Codex"),
                List.of("Done"),
                null,
                false,
                null,
                workflow,
                Path.of("./workspaces"),
                2,
                1,
                false,
                TrelloBoardSetup.GitHubIntegration.DISABLED,
                false);

        // when
        Throwable thrown = catchThrowable(() -> setup.importExistingBoard(request));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .extracting(exception -> ((TrelloBoardSetupException) exception).code(), Throwable::getMessage)
                .containsExactly(
                        "setup_invalid_server_port",
                        "--server-port must be between 1024 and 65535 for local HTTP status.");
        assertThat(boardInfoLookups).hasValue(0);
        assertThat(createdLists).isEmpty();
        assertThat(workflow).doesNotExist();
    }

    @Test
    void newBoardRejectsRequestedServerPortAlreadyListeningBeforeTrelloRequest() throws IOException {
        // given
        // This test binds its own listener, so it uses the real port probe on a port it owns.
        TrelloBoardSetup probingSetup = new TrelloBoardSetup(new ObjectMapper());
        Path workflow = tempDir.resolve("WORKFLOW.md");
        HttpServer listeningServer = startLoopbackServer();
        int listeningPort = listeningServer.getAddress().getPort();
        var request = new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                listeningPort,
                1,
                false,
                true);

        // when
        Throwable thrown;
        try {
            thrown = catchThrowable(() -> probingSetup.createRecommendedBoard(request));
        } finally {
            listeningServer.stop(0);
        }

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .extracting(exception -> ((TrelloBoardSetupException) exception).code(), Throwable::getMessage)
                .containsExactly(
                        "setup_server_port_conflict",
                        "--server-port %d is already in use on 127.0.0.1.".formatted(listeningPort));
        assertThat(authorization.get()).isNull();
        assertThat(createdLists).isEmpty();
        assertThat(workflow).doesNotExist();
    }

    @Test
    void importBoardRejectsRequestedServerPortAlreadyListeningBeforeTrelloRequest() throws IOException {
        // given
        // This test binds its own listener, so it uses the real port probe on a port it owns.
        TrelloBoardSetup probingSetup = new TrelloBoardSetup(new ObjectMapper());
        Path workflow = tempDir.resolve("WORKFLOW.md");
        HttpServer listeningServer = startLoopbackServer();
        int listeningPort = listeningServer.getAddress().getPort();
        var request = new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of("Ready for Codex"),
                List.of("Done"),
                null,
                false,
                null,
                workflow,
                Path.of("./workspaces"),
                listeningPort,
                1,
                false,
                TrelloBoardSetup.GitHubIntegration.DISABLED,
                false);

        // when
        Throwable thrown;
        try {
            thrown = catchThrowable(() -> probingSetup.importExistingBoard(request));
        } finally {
            listeningServer.stop(0);
        }

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .extracting(exception -> ((TrelloBoardSetupException) exception).code(), Throwable::getMessage)
                .containsExactly(
                        "setup_server_port_conflict",
                        "--server-port %d is already in use on 127.0.0.1.".formatted(listeningPort));
        assertThat(boardInfoLookups).hasValue(0);
        assertThat(createdLists).isEmpty();
        assertThat(workflow).doesNotExist();
    }

    @Test
    void newBoardReservesNormalizedWholeFloatSiblingWorkflowPort() throws IOException {
        // given
        // A stale sibling workflow with port 18080.0 reserves exactly 18080; it must never
        // reserve a truncated or shifted value.
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Path siblingWorkflow = tempDir.resolve("WORKFLOW.whole-float.md");
        Files.writeString(
                siblingWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: existing-board
                server:
                  port: 18080.0
                ---
                # Existing workflow
                """,
                StandardCharsets.UTF_8);

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                true));

        // then
        assertThat(result.serverPort()).isEqualTo(18081);
    }

    @Test
    void newBoardRejectsFractionalSiblingWorkflowPortInsteadOfTruncatingIt() throws IOException {
        // given
        // The sibling scan already fails strictly for non-numeric ports, so a fractional port is
        // rejected the same way instead of silently reserving the truncated value.
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Path siblingWorkflow = tempDir.resolve("WORKFLOW.fractional.md");
        Files.writeString(
                siblingWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: existing-board
                server:
                  port: 18080.5
                ---
                # Existing workflow
                """,
                StandardCharsets.UTF_8);

        // when
        Throwable thrown = catchThrowable(() -> setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                true)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_invalid_server_port");
            assertThat(failure.getMessage()).contains("WORKFLOW.fractional.md");
        });
        assertThat(workflow).doesNotExist();
    }

    @Test
    void newBoardSkipsUnresolvedEnvironmentBackedSiblingWorkflowPort() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Path siblingWorkflow = tempDir.resolve("WORKFLOW.env-port.md");
        Files.writeString(
                siblingWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: existing-board
                server:
                  port: $SYNTHETIC_MISSING_WORKFLOW_PORT_FOR_TEST
                ---
                # Existing workflow
                """,
                StandardCharsets.UTF_8);

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                true));

        int expectedPort = firstAvailableManagedPort();

        // then
        assertThat(result.serverPort()).isEqualTo(expectedPort);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("port: " + expectedPort);
    }

    @Test
    void newBoardSkipsPortsTheAvailabilityProbeReportsBusy() throws IOException {
        // given
        // Deterministically simulates live workers occupying the first ports of the managed
        // range, which previously made these tests depend on real host port occupancy.
        TrelloBoardSetup probedSetup =
                new TrelloBoardSetup(new ObjectMapper()).withPortProbe(port -> port >= 18080 && port <= 18082);
        Path workflow = tempDir.resolve("WORKFLOW.md");

        // when
        var result = probedSetup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                true));

        // then
        assertThat(result.serverPort()).isEqualTo(18083);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("port: 18083");
    }

    @Test
    void importExistingBoardRoundTripsControlCharacterListNames() throws IOException {
        // given
        // Trello list names can contain control characters; the request bypasses the CLI option
        // validation exactly like names fetched from Trello do, so the generated workflow must
        // escape them physically while parsing back to the original names.
        String dirtyActive = "Ready\nfor \"Codex\"";
        String dirtyTerminal = "Done\tList";
        boardListsResponse.set(
                """
                [
                  {"id":"list-1","name":"Ready\\nfor \\"Codex\\"","closed":false,"pos":1},
                  {"id":"list-2","name":"Done\\tList","closed":false,"pos":2}
                ]
                """);
        Path workflow = tempDir.resolve("dirty-roundtrip.WORKFLOW.md");

        // when
        var result = setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(dirtyActive),
                List.of(dirtyTerminal),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                1,
                false));

        // then
        assertThat(result.activeStates()).containsExactly(dirtyActive);
        assertThat(result.terminalStates()).containsExactly(dirtyTerminal);
        String content = Files.readString(workflow, StandardCharsets.UTF_8);
        assertThat(content).contains("Done\\tList");
        assertThat(content)
                .as("generated text must escape control characters instead of emitting them raw")
                .contains("Ready\\nfor \\\"Codex\\\"")
                .doesNotContain(dirtyActive)
                .doesNotContain("\t");
        EffectiveConfig parsed =
                new ConfigResolver(ignored -> Optional.empty()).resolve(new WorkflowLoader().load(workflow));
        assertThat(parsed.tracker().activeStates())
                .as("the YAML escapes must round-trip to the actual Trello list name")
                .containsExactly(dirtyActive);
    }

    @Test
    void importExistingBoardEscapesControlCharacterListNamesInGeneratedPromptProse() throws IOException {
        // given
        // Trello list names can contain quotes and control characters; the generated workflow
        // prompt prose must render them display-escaped on one physical line, while the YAML
        // values still round-trip the actual Trello list names.
        String dirtyInProgress = "Codex \"Live\"\tNow";
        String dirtyBlocked = "Hold\n\"Up\"";
        boardListsResponse.set(
                """
                [
                  {"id":"list-1","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-2","name":"Codex \\"Live\\"\\tNow","closed":false,"pos":2},
                  {"id":"list-3","name":"Hold\\n\\"Up\\"","closed":false,"pos":3},
                  {"id":"list-4","name":"Released","closed":false,"pos":4}
                ]
                """);
        Path workflow = tempDir.resolve("dirty-prompt-prose.WORKFLOW.md");

        // when
        setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of("Ready for Codex"),
                List.of("Released"),
                dirtyInProgress,
                false,
                dirtyBlocked,
                workflow,
                Path.of("./agent-workspaces"),
                1,
                false));

        // then
        String content = Files.readString(workflow, StandardCharsets.UTF_8);
        assertThat(content)
                .as("handoff instruction must keep the escaped blocked list name on one line")
                .contains("trello_move_current_card with list_name \"Hold\\n\\\"Up\\\"\"")
                .doesNotContain(dirtyBlocked);
        assertThat(content)
                .as("pickup prose must keep the escaped in-progress list name on one line")
                .contains("to \"Codex \\\"Live\\\"\\tNow\" before Codex starts")
                .contains("If the card is already in \"Codex \\\"Live\\\"\\tNow\", continue")
                .doesNotContain(dirtyInProgress)
                .doesNotContain("\t");
        EffectiveConfig parsed =
                new ConfigResolver(ignored -> Optional.empty()).resolve(new WorkflowLoader().load(workflow));
        assertThat(parsed.tracker().inProgressState())
                .as("YAML must still round-trip the actual Trello list name")
                .isEqualTo(dirtyInProgress);
        assertThat(parsed.tracker().blockedState()).isEqualTo(dirtyBlocked);
    }

    @Test
    void importExistingBoardEscapesQuotedAmbiguousListSelectors() {
        // given
        // Configured selectors reach this validation without CLI option validation when they are
        // supplied programmatically, exactly like the control-character round-trip above, so the
        // ambiguous-selector error must display-escape quotes and control characters instead of
        // letting them forge or split the message.
        String dirtySelector = "Plan \"B\"\nQueue";
        boardListsResponse.set(
                """
                [
                  {"id":"list-1","name":"Plan \\"B\\"\\nQueue","closed":false,"pos":1},
                  {"id":"list-2","name":"PLAN \\"B\\"\\nQUEUE","closed":false,"pos":2},
                  {"id":"list-3","name":"Released","closed":false,"pos":3}
                ]
                """);
        Path workflow = tempDir.resolve("ambiguous-quoted.WORKFLOW.md");

        // when
        Throwable thrown = catchThrowable(() -> setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(dirtySelector),
                List.of("Released"),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                1,
                false)));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_ambiguous_active_state");
            assertThat(failure)
                    .hasMessageContaining(
                            "Multiple open Trello lists match active list selector(s): \"Plan \\\"B\\\"\\nQueue\"")
                    .hasMessageNotContaining(dirtySelector);
        });
        assertThat(workflow).doesNotExist();
    }

    @Test
    void newBoardUsesSluggedWorkflowPathWhenDefaultWorkflowAlreadyExists() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(workflow, "keep me", StandardCharsets.UTF_8);

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project!",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                true));

        // then
        Path generatedWorkflow = tempDir.resolve("WORKFLOW.my-project.md");
        assertThat(result.workflowPath()).isEqualTo(generatedWorkflow);
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo("keep me");
        assertThat(generatedWorkflow).content(StandardCharsets.UTF_8).contains("board_id: \"abc123\"");
    }

    @Test
    void newBoardBoundsGeneratedWorkflowPathForLongBoardNames() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(workflow, "keep me", StandardCharsets.UTF_8);
        String boardName = "Project " + "Alpha ".repeat(80);
        String expectedSlugPrefix = TrelloBoardSetup.slugify(boardName).substring(0, 100);

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                boardName,
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                true));

        // then
        String fileName = result.workflowPath().getFileName().toString();
        assertThat(fileName)
                .startsWith("WORKFLOW." + expectedSlugPrefix + "-")
                .endsWith(".md")
                .matches("WORKFLOW\\.[a-z0-9-]+-[0-9a-f]{12}\\.md")
                .hasSizeLessThan(128);
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo("keep me");
        assertThat(result.workflowPath()).content(StandardCharsets.UTF_8).contains("board_id: \"abc123\"");
    }

    @Test
    void newBoardBoundsGeneratedWorkflowPathWhenLongBoardNameFallbackNeedsNumericSuffix() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(workflow, "keep me", StandardCharsets.UTF_8);
        String boardName = "Project " + "Alpha ".repeat(80);
        for (int suffix = 1; suffix < 10; suffix++) {
            Files.writeString(
                    tempDir.resolve(WorkflowFileNames.generatedFileName(boardName, "board", suffix)), "taken");
        }

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                boardName,
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                true));

        // then
        String fileName = result.workflowPath().getFileName().toString();
        assertThat(fileName)
                .endsWith("-10.md")
                .matches("WORKFLOW\\.[a-z0-9-]+-[0-9a-f]{12}-10\\.md")
                .hasSizeLessThan(128);
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo("keep me");
        assertThat(result.workflowPath()).content(StandardCharsets.UTF_8).contains("board_id: \"abc123\"");
    }

    @Test
    void newBoardUsesNextServerPortWhenExistingWorkflowUsesDefaultPort() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        int existingPort = ConfigDefaults.DEFAULT_SERVER_PORT;
        int expectedPort = firstAvailableManagedPort(existingPort);
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: $TRELLO_API_KEY
                  api_token: $TRELLO_API_TOKEN
                  board_id: "existing"
                server:
                  port: %d
                ---
                # Existing
                """
                        .formatted(existingPort),
                StandardCharsets.UTF_8);

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project!",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                true));

        // then
        Path generatedWorkflow = tempDir.resolve("WORKFLOW.my-project.md");
        assertThat(result.serverPort()).isEqualTo(expectedPort);
        assertThat(generatedWorkflow).content(StandardCharsets.UTF_8).contains("port: " + expectedPort);
    }

    @Test
    void newBoardUsesRequestedServerPortWhenItDoesNotConflict() {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        int requestedPort = firstAvailableManagedPort();

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                requestedPort,
                1,
                false,
                true));

        // then
        assertThat(result.serverPort()).isEqualTo(requestedPort);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("port: " + requestedPort);
    }

    @Test
    void newBoardRejectsReservedRequestedServerPortBeforeTrelloRequest() {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        var request = new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                1,
                false,
                true);

        // when
        Throwable thrown = catchThrowable(() -> setup.createRecommendedBoard(request));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .extracting(exception -> ((TrelloBoardSetupException) exception).code(), Throwable::getMessage)
                .containsExactly(
                        "setup_invalid_server_port",
                        "--server-port must be between 1024 and 65535 for local HTTP status.");
        assertThat(authorization.get()).isNull();
        assertThat(createdLists).isEmpty();
        assertThat(workflow).doesNotExist();
    }

    @Test
    void newBoardRejectsRequestedServerPortReservedByExistingWorkflow() throws IOException {
        // given
        Path existingWorkflow = tempDir.resolve("project-a.WORKFLOW.md");
        Files.writeString(
                existingWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: $TRELLO_API_KEY
                  api_token: $TRELLO_API_TOKEN
                  board_id: "existing"
                server:
                  port: 18081
                ---
                # Existing
                """,
                StandardCharsets.UTF_8);

        Path workflow = tempDir.resolve("WORKFLOW.md");
        var request = new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                18081,
                1,
                false,
                true);

        // when
        ThrowingCallable action = () -> setup.createRecommendedBoard(request);

        // then
        assertThatThrownBy(action).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("already used");
    }

    @Test
    void newBoardIgnoresEphemeralServerPortReservedByExistingWorkflow() throws IOException {
        // given
        Path existingWorkflow = tempDir.resolve("ephemeral.WORKFLOW.md");
        Files.writeString(
                existingWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: $TRELLO_API_KEY
                  api_token: $TRELLO_API_TOKEN
                  board_id: "existing"
                server:
                  port: 0
                ---
                # Existing
                """,
                StandardCharsets.UTF_8);
        Path workflow = tempDir.resolve("WORKFLOW.md");

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                true));

        // then
        int expectedPort = firstAvailableManagedPort();
        assertThat(result.serverPort()).isEqualTo(expectedPort);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("port: " + expectedPort);
    }

    @Test
    void forceNewBoardPreservesExistingServerPortWhenItDoesNotConflict() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        int expectedPort = firstAvailableManagedPort();
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "existing"
                server:
                  port: %d
                ---
                # Existing
                """
                        .formatted(expectedPort),
                StandardCharsets.UTF_8);

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                true,
                true));

        // then
        assertThat(result.serverPort()).isEqualTo(expectedPort);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("port: " + expectedPort);
    }

    @Test
    void forceNewBoardDoesNotPreserveExistingServerPortWhenItIsAlreadyListening() throws IOException {
        // given
        // This test binds its own listener, so it uses the real port probe on a port it owns.
        TrelloBoardSetup probingSetup = new TrelloBoardSetup(new ObjectMapper());
        Path workflow = tempDir.resolve("WORKFLOW.md");
        HttpServer listeningServer = startLoopbackServer();
        int listeningPort = listeningServer.getAddress().getPort();
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "existing"
                server:
                  port: %d
                ---
                # Existing
                """
                        .formatted(listeningPort),
                StandardCharsets.UTF_8);

        // when
        TrelloBoardSetup.NewBoardResult result;
        try {
            result = probingSetup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                    endpoint(),
                    new TrelloBoardSetup.TrelloCredentials("key", "token"),
                    "My Project",
                    null,
                    workflow,
                    Path.of("./workspaces"),
                    1,
                    true,
                    true));
        } finally {
            listeningServer.stop(0);
        }

        // then
        assertThat(result.serverPort()).isNotEqualTo(listeningPort);
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("port: " + result.serverPort())
                .doesNotContain("port: " + listeningPort);
    }

    @Test
    void forceNewBoardDoesNotPreserveExistingServerPortWhenSiblingAlreadyUsesIt() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        int siblingPort = firstAvailableManagedPort();
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "old"
                server:
                  port: %d
                ---
                # Existing
                """
                        .formatted(siblingPort),
                StandardCharsets.UTF_8);
        Path siblingWorkflow = tempDir.resolve("project-a.WORKFLOW.md");
        int expectedPort = firstAvailableManagedPort(siblingPort);
        Files.writeString(
                siblingWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "sibling"
                server:
                  port: %d
                ---
                # Sibling
                """
                        .formatted(siblingPort),
                StandardCharsets.UTF_8);

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                true,
                true));

        // then
        assertThat(result.serverPort()).isEqualTo(expectedPort);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("port: " + expectedPort);
    }

    @Test
    void forceNewBoardCanReplaceMalformedExistingWorkflow() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        int expectedPort = ConfigDefaults.DEFAULT_SERVER_PORT;
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                server: [
                ---
                # Broken
                """,
                StandardCharsets.UTF_8);

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                true,
                true));

        // then
        assertThat(result.serverPort()).isEqualTo(expectedPort);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("port: " + expectedPort);
    }

    @Test
    void newBoardAddsNumericSuffixWhenSluggedWorkflowPathAlreadyExists() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Path firstGeneratedWorkflow = tempDir.resolve("WORKFLOW.my-project.md");
        Files.writeString(workflow, "keep me", StandardCharsets.UTF_8);
        Files.writeString(firstGeneratedWorkflow, "keep me too", StandardCharsets.UTF_8);

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                true));

        // then
        Path generatedWorkflow = tempDir.resolve("WORKFLOW.my-project-2.md");
        assertThat(result.workflowPath()).isEqualTo(generatedWorkflow);
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo("keep me");
        assertThat(firstGeneratedWorkflow).content(StandardCharsets.UTF_8).isEqualTo("keep me too");
        assertThat(generatedWorkflow).content(StandardCharsets.UTF_8).contains("board_id: \"abc123\"");
    }

    @Test
    void generatedPromptRendersTrelloReferencesAsSeparateBullets() {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.references.md");
        setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "Reference Prompt Board",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                false));
        Card card = TestCards.card("card-1", "TRELLO-123", "Ready for Codex")
                .withRelationships(
                        List.of(),
                        List.of(
                                new Card.TrelloReference(
                                        "description",
                                        "See https://trello.com/c/DESC123",
                                        "DESC123",
                                        "TRELLO-desc",
                                        "Description reference",
                                        "Done",
                                        "https://trello.com/c/DESC123",
                                        "found",
                                        true),
                                new Card.TrelloReference(
                                        "comment",
                                        "See https://trello.com/c/COMM123",
                                        "COMM123",
                                        "TRELLO-comment",
                                        "Comment reference",
                                        "Ready for Codex",
                                        "https://trello.com/c/COMM123",
                                        "found",
                                        false)),
                        List.of(),
                        List.of());

        // when
        String prompt =
                new PromptRenderer().render(new WorkflowLoader().load(workflow).promptTemplate(), card, null);

        // then
        assertThat(prompt.lines().filter(line -> line.startsWith("- source=")).toList())
                .containsExactly(
                        "- source=description status=found terminal=true identifier=TRELLO-desc state=Done title=Description reference url=https://trello.com/c/DESC123 text=See https://trello.com/c/DESC123",
                        "- source=comment status=found terminal=false identifier=TRELLO-comment state=Ready for Codex title=Comment reference url=https://trello.com/c/COMM123 text=See https://trello.com/c/COMM123");
    }

    @Test
    void newBoardRefusesToOverwriteExplicitWorkflowPathUnlessForced() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(workflow, "keep me", StandardCharsets.UTF_8);

        var request = new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                1,
                false,
                false);

        // when
        ThrowingCallable action = () -> setup.createRecommendedBoard(request);

        // then
        assertThatThrownBy(action).isInstanceOf(TrelloBoardSetupException.class).hasMessageContaining("--force");
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo("keep me");
    }

    @Test
    void slugifyUsesReadableFallbackForNamesWithoutAsciiLettersOrDigits() {
        // given
        String boardName = "!!!";

        // when
        String slug = TrelloBoardSetup.slugify(boardName);

        // then
        assertThat(slug).isEqualTo("board");
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + trello.endpointUri().getPort() + "/1");
    }

    private static EffectiveConfig resolve(Path workflow) {
        return new ConfigResolver().resolve(new WorkflowLoader().load(workflow));
    }

    private static void writeExistingWorkflow(Path workflow, String codexFields) throws IOException {
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: existing
                server:
                  port: 18080
                codex:
                  command: %s
                %s---
                # Existing workflow
                """
                        .formatted(ConfigDefaults.DEFAULT_CODEX_COMMAND, codexFields),
                StandardCharsets.UTF_8);
    }

    private static HttpServer startLoopbackServer() throws IOException {
        HttpServer listeningServer =
                HttpServer.create(new InetSocketAddress(LocalHealthChecker.loopbackIpv4ForTests(), 0), 0);
        listeningServer.start();
        return listeningServer;
    }

    /** The class fixture fakes every port as free, so the next port is pure arithmetic. */
    private static int firstAvailableManagedPort(int... reservedPorts) {
        for (int port = ConfigDefaults.DEFAULT_SERVER_PORT; port <= LocalPort.MAX; port++) {
            if (!contains(reservedPorts, port)) {
                return port;
            }
        }
        throw new AssertionError("No free managed test port found.");
    }

    private static boolean contains(int[] ports, int candidate) {
        for (int port : ports) {
            if (port == candidate) {
                return true;
            }
        }
        return false;
    }

    private void forceRegenerateExistingWorkflow(Path workflow) {
        TrelloBoardSetup setupWithNewDefaults =
                new TrelloBoardSetup(new ObjectMapper(), new TrelloBoardSetup.CodexModelDefaults("gpt-new", "medium"));
        setupWithNewDefaults.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                null,
                workflow,
                Path.of("./agent-workspaces"),
                2,
                true));
    }
}
