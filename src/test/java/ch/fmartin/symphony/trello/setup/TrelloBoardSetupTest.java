package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.TestHttpExchange.query;
import static ch.fmartin.symphony.trello.TestHttpExchange.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
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
    private HttpServer server;
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
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
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
            boardInfoLookups.incrementAndGet();
            respond(
                    exchange,
                    """
                    {"id":"board-1","name":"Existing Board","shortLink":"SYNTH001","url":"https://trello.com/b/SYNTH001/existing-board","closed":false}
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
    void newBoardDerivesBoardKeyFromUrlWhenCreateResponseOmitsShortLink() {
        // given
        server.removeContext("/1/boards/");
        server.createContext("/1/boards/", exchange -> {
            Map<String, String> query = query(exchange);
            respond(
                    exchange,
                    """
                    {"id":"000000000000000000000001","name":"%s","url":"https://trello.com/b/SYNTH777/synthetic-board"}
                    """
                            .formatted(query.get("name")));
        });
        server.removeContext("/1/lists");
        server.createContext("/1/lists", exchange -> {
            Map<String, String> query = query(exchange);
            createdLists.add(query.get("name"));
            respond(exchange, "{\"id\":\"list-" + createdLists.size() + "\",\"name\":\"" + query.get("name") + "\"}");
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
        server.removeContext("/1/boards/");
        server.createContext("/1/boards/", exchange -> {
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
    void importBoardReportsActionableErrorForUnresolvableBoardSelector() {
        // given
        server.createContext("/1/boards/notreal", exchange -> {
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
        server.removeContext("/1/boards/");
        server.createContext("/1/boards/", exchange -> {
            exchange.sendResponseHeaders(401, 0);
            exchange.getResponseBody().write("unauthorized org access".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.createContext(
                "/1/members/me",
                exchange -> respond(
                        exchange,
                        """
                {"id":"member-1","username":"alex","fullName":"Alex Example"}
                """));
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
        server.removeContext("/1/boards/");
        server.createContext("/1/boards/", exchange -> {
            exchange.sendResponseHeaders(401, 0);
            exchange.getResponseBody().write("invalid token".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.createContext("/1/members/me", exchange -> {
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
                .contains("## Landing From \"Merging\"")
                .contains("Only run landing when the current Trello list is \"Merging\"")
                .contains("If the card moved from \"Human Review\" to \"Merging\" with no new feedback")
                .contains("If exact, unambiguous feedback added before the card entered \"Merging\"")
                .contains("If final work in the landing approval list required material fixups")
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
                .contains("card to \"Human Review\" or landing from Merging")
                .contains("top-level PR comments")
                .contains("inline review comments")
                .contains("GitHub review threads")
                .contains("Resolve addressed GitHub review threads")
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
                .contains("docs/deployment.md#allow-host-path-access")
                .contains("server:")
                .contains("port: " + expectedPort)
                .contains("command: " + ConfigDefaults.DEFAULT_CODEX_COMMAND)
                .contains("model: \"gpt-5.5\"")
                .contains("reasoning_effort: \"medium\"")
                .contains("polling:")
                .contains("interval_ms: " + ConfigDefaults.DEFAULT_POLLING_INTERVAL_MS)
                .contains("max_concurrent_agents: " + ConfigDefaults.DEFAULT_SETUP_MAX_CONCURRENT_AGENTS);
        assertThat(result.serverPort()).isEqualTo(expectedPort);
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().boardId()).isEqualTo("abc123");
        assertThat(config.codex().command()).isEqualTo(ConfigDefaults.DEFAULT_CODEX_COMMAND);
        assertThat(config.codex().model()).isEqualTo("gpt-5.5");
        assertThat(config.codex().reasoningEffort()).isEqualTo("medium");
        assertThat(config.codex().turnTimeout()).isEqualTo(ConfigDefaults.DEFAULT_CODEX_TURN_TIMEOUT);
        assertThat(config.codex().readTimeout()).isEqualTo(ConfigDefaults.DEFAULT_CODEX_READ_TIMEOUT);
        assertThat(config.codex().stallTimeout()).isEqualTo(ConfigDefaults.DEFAULT_CODEX_STALL_TIMEOUT);
        assertThat(config.tracker().activeStates()).containsExactly("Ready for Codex", "In Progress", "Merging");
        assertThat(config.tracker().terminalStates())
                .contains("done", "archived", "archivedlist", "archivedboard", "deleted");
        assertThat(config.trelloTools().enabled()).isTrue();
        assertThat(config.trelloTools().allowWrites()).isTrue();
        assertThat(config.trelloTools().allowedMoveListNames())
                .containsExactly("in progress", "human review", "blocked", "done");
        assertThat(config.trelloTools().allowChecklists()).isFalse();
        assertThat(config.trelloTools().allowUrlAttachments()).isFalse();
        assertThat(config.polling().interval()).isEqualTo(ConfigDefaults.DEFAULT_POLLING_INTERVAL);
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
                .contains("## Local And Non-GitHub Repository Work")
                .contains("do not require GitHub auth")
                .contains("## Non-GitHub Review Feedback")
                .contains("reread the full card description and new Trello comments")
                .contains("This workflow has no GitHub landing flow configured")
                .doesNotContain("Merging")
                .doesNotContain("## Pull Request Publication")
                .doesNotContain("linked PR comments")
                .doesNotContain("PR feedback sweep")
                .doesNotContain("## Landing From \"Merging\"");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().activeStates()).containsExactly("Ready for Codex", "In Progress");
        assertThat(config.trelloTools().allowedMoveListNames())
                .containsExactly("in progress", "human review", "blocked", "done");
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
                .contains("interval_ms: " + ConfigDefaults.DEFAULT_POLLING_INTERVAL_MS)
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
                .contains("GitHub review threads")
                .contains("Resolve addressed GitHub review threads")
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
        assertThat(config.polling().interval()).isEqualTo(ConfigDefaults.DEFAULT_POLLING_INTERVAL);
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
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("review", "needs help", "released");
        assertThat(config.agent().maxConcurrentAgents()).isEqualTo(2);
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("Do not move the card to \"Review\"")
                .contains("card to \"Review\" or landing from Merging")
                .contains("Before returning the card to \"Review\"")
                .contains("This workflow has no landing approval list configured");
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
                .contains("## Landing From \"Merging\"")
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
                .contains("## Landing From \"Merging\"")
                .contains("If exact, unambiguous feedback added before the card entered \"Merging\"")
                .contains("If landing required material fixups")
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
                .contains("This workflow has no landing approval list configured")
                .doesNotContain("## Landing From \"Merging\"")
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
                .contains("This workflow has no landing approval list configured")
                .doesNotContain("## Landing From \"Merging\"");
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
                .contains("accessible files are available")
                .contains("per-card workspace")
                .contains("shown by `pwd`");
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
            thrown = catchThrowable(() -> setup.createRecommendedBoard(request));
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
            thrown = catchThrowable(() -> setup.importExistingBoard(request));
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
        assertThat(result.serverPort()).isEqualTo(firstAvailableManagedPort(18080));
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
            result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
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

    private static int firstAvailableManagedPort(int... reservedPorts) {
        for (int port = ConfigDefaults.DEFAULT_SERVER_PORT; port <= LocalPort.MAX; port++) {
            if (!contains(reservedPorts, port) && !LocalHealthChecker.portAcceptsConnections(port)) {
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
