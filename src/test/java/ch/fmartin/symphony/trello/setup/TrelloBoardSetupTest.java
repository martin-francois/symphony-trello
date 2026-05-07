package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrelloBoardSetupTest {
    private HttpServer server;
    private TrelloBoardSetup setup;
    private final List<String> createdLists = new ArrayList<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> workspaceResponse = new AtomicReference<>();
    private final AtomicReference<String> boardListsResponse = new AtomicReference<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        setup = new TrelloBoardSetup(new ObjectMapper());
        workspaceResponse.set(
                """
                [
                  {"id":"workspace-1","name":"symphony-automation","displayName":"Symphony Automation","url":"https://trello.com/w/symphony-automation"}
                ]
                """);
        boardListsResponse.set(
                """
                [
                  {"id":"list-inbox","name":"Inbox","closed":false,"pos":1},
                  {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":2},
                  {"id":"list-in-progress","name":"In Progress","closed":false,"pos":3},
                  {"id":"list-blocked","name":"Blocked","closed":false,"pos":4},
                  {"id":"list-review","name":"Human Review","closed":false,"pos":5},
                  {"id":"list-merging","name":"Merging","closed":false,"pos":6},
                  {"id":"list-done","name":"Done","closed":false,"pos":7},
                  {"id":"list-archive","name":"Archived old work","closed":true,"pos":8}
                ]
                """);

        server.createContext("/1/boards/", exchange -> {
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
                    """
                    {"id":"board-1","name":"%s","shortLink":"abc123","url":"https://trello.com/b/abc123/symphony-work-queue"}
                    """
                            .formatted(query.get("name")));
        });
        server.createContext("/1/lists", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            Map<String, String> query = query(exchange);
            assertThat(query).containsEntry("idBoard", "board-1").containsEntry("pos", "bottom");
            createdLists.add(query.get("name"));
            respond(exchange, "{\"id\":\"list-" + createdLists.size() + "\",\"name\":\"" + query.get("name") + "\"}");
        });
        server.createContext("/1/boards/input", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            respond(
                    exchange,
                    """
                    {"id":"board-1","name":"Existing Board","shortLink":"existing","url":"https://trello.com/b/existing/existing-board","closed":false}
                    """);
        });
        server.createContext("/1/boards/board-1/lists", exchange -> respond(exchange, boardListsResponse.get()));
        server.createContext("/1/members/me/organizations", exchange -> respond(exchange, workspaceResponse.get()));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
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
                .contains("## Landing From \"Merging\"")
                .contains("Only run landing when the current Trello list is \"Merging\"")
                .contains("Do not enable auto-merge")
                .contains("move the card to \"Blocked\" with a concise blocker")
                .contains("move the card to \"Done\"")
                .contains("treat it as rework")
                .contains("## Description")
                .contains("{{ card.description }}")
                .contains("## Trello Comments")
                .contains("{% for comment in card.comments %}")
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
                .contains("card to \"Human Review\" or landing from Merging")
                .contains("top-level PR comments")
                .contains("inline review comments")
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
                .contains("\"Merging\": human approval for landing")
                .contains("## Repository Skills")
                .contains(".codex/skills/symphony-trello-trello-workpad/SKILL.md")
                .contains(".codex/skills/symphony-trello-trello-handoff/SKILL.md")
                .contains(".codex/skills/symphony-trello-review-sweep/SKILL.md")
                .contains(".codex/skills/symphony-trello-land/SKILL.md")
                .contains("## Repository Checkout Policy")
                .contains("Do implementation work inside the current per-card workspace")
                .contains("names only a repository URL")
                .contains("cloning from a readable matching local checkout")
                .contains("clone the repository URL into a new subdirectory named after the repository")
                .contains("clone from that readable local path into a subdirectory of the current workspace")
                .contains("git config --global --add safe.directory <source-checkout>")
                .contains("git clone --no-hardlinks <source-checkout> <workspace-checkout>")
                .contains("Start the task branch from the repository's default branch")
                .contains("## Completion Bar Before \"Human Review\"")
                .contains("A pull request exists and is linked in the workpad and handoff comment")
                .contains("Filesystem access blocker details")
                .contains("inaccessible path")
                .contains("by default for security reasons")
                .contains("undeclared host paths")
                .contains("symphony_trello_allowed_host_paths")
                .contains("server:")
                .contains("port: 18080")
                .contains("max_concurrent_agents: 1");
        assertThat(result.serverPort()).isEqualTo(18080);
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().boardId()).isEqualTo("abc123");
        assertThat(config.tracker().activeStates()).containsExactly("Ready for Codex", "In Progress", "Merging");
        assertThat(config.tracker().terminalStates())
                .contains("done", "archived", "archivedlist", "archivedboard", "deleted");
        assertThat(config.trelloTools().enabled()).isTrue();
        assertThat(config.trelloTools().allowWrites()).isTrue();
        assertThat(config.trelloTools().allowedMoveListNames())
                .containsExactly("in progress", "human review", "blocked", "done");
        assertThat(config.trelloTools().allowChecklists()).isFalse();
        assertThat(config.trelloTools().allowUrlAttachments()).isFalse();
    }

    @Test
    void requiresWorkspaceIdWhenTokenCanAccessMultipleWorkspaces() {
        // given
        workspaceResponse.set(
                """
                [
                  {"id":"workspace-1","name":"first","displayName":"First Workspace"},
                  {"id":"workspace-2","name":"second","displayName":"Second Workspace"}
                ]
                """);
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
                .contains("board_id: \"existing\"")
                .contains("root: \"./agent-workspaces\"")
                .contains("port: 18080")
                .contains("allowed_move_list_names:")
                .contains("- \"In Progress\"")
                .contains("- \"Human Review\"")
                .contains("- \"Blocked\"")
                .contains("- \"Done\"")
                .contains("in_progress_state: \"In Progress\"")
                .contains("Symphony moves cards from \"Ready for Codex\" to \"In Progress\" before Codex starts")
                .contains("## Trello Comments")
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
                .contains("## Rework From Human Review")
                .contains("treat the next run as rework")
                .contains("linked PR comments")
                .contains("current PR/check state")
                .contains("## Trello List Routing")
                .contains("\"Ready for Codex\": queued work")
                .contains("\"In Progress\": work currently running in Codex")
                .contains("workspace-local skills")
                .contains("after workspace sync hooks")
                .contains(".codex/skills/symphony-trello-commit/SKILL.md")
                .contains(".codex/skills/symphony-trello-push-pr/SKILL.md")
                .contains("tracker.in_progress_state is configured")
                .contains("list_name \"Human Review\"")
                .contains("## Landing From \"Merging\"")
                .contains("Only run landing when the current Trello list is \"Merging\"")
                .contains("## Completion Bar Before \"Human Review\"")
                .contains("move the card to \"Done\"")
                .contains("max_concurrent_agents: 2");
        assertThat(result.serverPort()).isEqualTo(18080);
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().boardId()).isEqualTo("existing");
        assertThat(config.workspace().root()).isEqualTo(workflow.getParent().resolve("agent-workspaces"));
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
                .contains("If the card is in \"Merging\", follow the landing section instead")
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
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("review", "needs help");
        assertThat(config.agent().maxConcurrentAgents()).isEqualTo(2);
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("Do not move the card to \"Review\"")
                .contains("card to \"Review\" or landing from Merging")
                .contains("Before returning the card to \"Review\"")
                .contains("This workflow has no landing approval list configured");
    }

    @Test
    void importUsesConfiguredTerminalListForLandingWhenMergingExists() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-review","name":"Human Review","closed":false,"pos":2},
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
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("human review", "blocked", "released");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("## Landing From \"Merging\"")
                .contains("move the card to \"Released\"")
                .doesNotContain("move the card to \"Done\"");
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
                .contains("This workflow has no landing approval list configured")
                .doesNotContain("## Landing From \"Merging\"")
                .doesNotContain("move the card to \"Done\"");
    }

    @Test
    void importPrefersHumanReviewWhenLegacyReviewAlsoExists() {
        // given
        boardListsResponse.set(
                """
                [
                  {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":1},
                  {"id":"list-legacy-review","name":"Review","closed":false,"pos":2},
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
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("human review", "blocked");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("list_name \"Human Review\"")
                .doesNotContain("list_name \"Review\"");
    }

    @Test
    void importDisablesTrelloWritesWhenNoReviewListExists() {
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
        assertThat(config.trelloTools().enabled()).isFalse();
        assertThat(config.trelloTools().allowWrites()).isFalse();
        assertThat(config.trelloTools().allowedMoveListNames()).isEmpty();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .doesNotContain("## Codex Workpad")
                .doesNotContain("trello_upsert_workpad")
                .doesNotContain("Add the PR URL to the workpad")
                .doesNotContain("Update the workpad")
                .contains("## Acceptance Criteria And Validation")
                .contains("the final Codex response or handoff comment")
                .contains("Record the plan, acceptance criteria, validation plan")
                .contains("Add the PR\nURL to the final response")
                .contains("record a short rework plan for the final response")
                .contains("finish the final-response plan");
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
                .contains("accessible files are available")
                .contains("per-card workspace")
                .contains("shown by `pwd`");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("review");
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
    void newBoardUsesNextServerPortWhenExistingWorkflowUsesDefaultPort() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: $TRELLO_API_KEY
                  api_token: $TRELLO_API_TOKEN
                  board_id: "existing"
                ---
                # Existing
                """,
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
        assertThat(result.serverPort()).isEqualTo(18081);
        assertThat(generatedWorkflow).content(StandardCharsets.UTF_8).contains("port: 18081");
    }

    @Test
    void newBoardUsesRequestedServerPortWhenItDoesNotConflict() {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");

        // when
        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "My Project",
                null,
                workflow,
                Path.of("./workspaces"),
                18081,
                1,
                false,
                true));

        // then
        assertThat(result.serverPort()).isEqualTo(18081);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("port: 18081");
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
    void forceNewBoardPreservesExistingServerPortWhenItDoesNotConflict() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "existing"
                server:
                  port: 18081
                ---
                # Existing
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
        assertThat(result.serverPort()).isEqualTo(18081);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("port: 18081");
    }

    @Test
    void forceNewBoardDoesNotPreserveExistingServerPortWhenSiblingAlreadyUsesIt() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "old"
                server:
                  port: 18081
                ---
                # Existing
                """,
                StandardCharsets.UTF_8);
        Path siblingWorkflow = tempDir.resolve("project-a.WORKFLOW.md");
        Files.writeString(
                siblingWorkflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "sibling"
                server:
                  port: 18081
                ---
                # Sibling
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
        assertThat(result.serverPort()).isEqualTo(18080);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("port: 18080");
    }

    @Test
    void forceNewBoardCanReplaceMalformedExistingWorkflow() throws IOException {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
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
        assertThat(result.serverPort()).isEqualTo(18080);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("port: 18080");
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
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/1");
    }

    private static EffectiveConfig resolve(Path workflow) {
        return new ConfigResolver().resolve(new WorkflowLoader().load(workflow));
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new LinkedHashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String part : rawQuery.split("&")) {
            String[] pair = part.split("=", 2);
            values.put(decode(pair[0]), pair.length == 1 ? "" : decode(pair[1]));
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
