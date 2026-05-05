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
    private final List<String> createdColumns = new ArrayList<>();
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
            createdColumns.add(query.get("name"));
            respond(exchange, "{\"id\":\"list-" + createdColumns.size() + "\",\"name\":\"" + query.get("name") + "\"}");
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
        assertThat(createdColumns)
                .containsExactly(
                        "Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Merging", "Done");
        assertThat(authorization.get()).contains("oauth_consumer_key=\"key\"").contains("oauth_token=\"token\"");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("board_id: \"abc123\"")
                .contains("- \"Ready for Codex\"")
                .contains("- \"In Progress\"")
                .contains("- \"Done\"")
                .contains("trello_tools:")
                .contains("allowed_move_list_names:")
                .contains("- \"In Progress\"")
                .contains("- \"Human Review\"")
                .contains("- \"Blocked\"")
                .contains("immediately call trello_move_current_card with list_name \"In Progress\"")
                .contains("list_name \"Human Review\"")
                .contains("A Merging column, when configured, is a human approval signal")
                .contains("treat it as rework")
                .contains("## Description")
                .contains("{{ card.description }}")
                .contains("## Trello Comments")
                .contains("{% for comment in card.comments %}")
                .contains("If the Trello card names a specific local path or project")
                .contains("Filesystem access blocker details")
                .contains("inaccessible path")
                .contains("allowed project roots")
                .contains("max_concurrent_agents: 1");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().boardId()).isEqualTo("abc123");
        assertThat(config.tracker().activeStates()).containsExactly("Ready for Codex", "In Progress");
        assertThat(config.tracker().terminalStates())
                .contains("done", "archived", "archivedlist", "archivedboard", "deleted");
        assertThat(config.trelloTools().enabled()).isTrue();
        assertThat(config.trelloTools().allowWrites()).isTrue();
        assertThat(config.trelloTools().allowedMoveListNames())
                .containsExactly("in progress", "human review", "blocked");
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
        assertThat(createdColumns).isEmpty();
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
        assertThat(result.openColumns())
                .containsExactly(
                        "Inbox", "Ready for Codex", "In Progress", "Blocked", "Human Review", "Merging", "Done");
        assertThat(result.activeStates()).containsExactly("Ready for Codex", "In Progress");
        assertThat(result.terminalStates()).containsExactly("Done");
        assertThat(result.inProgressState()).isEqualTo("In Progress");
        assertThat(result.blockedState()).isEqualTo("Blocked");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("board_id: \"existing\"")
                .contains("root: \"./agent-workspaces\"")
                .contains("allowed_move_list_names:")
                .contains("- \"In Progress\"")
                .contains("- \"Human Review\"")
                .contains("- \"Blocked\"")
                .contains("If the card is in \"Ready for Codex\"")
                .contains("## Trello Comments")
                .contains("list_name \"In Progress\"")
                .contains("list_name \"Human Review\"")
                .contains("A Merging column, when configured, is a human approval signal")
                .contains("max_concurrent_agents: 2");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().boardId()).isEqualTo("existing");
        assertThat(config.workspace().root()).isEqualTo(workflow.getParent().resolve("agent-workspaces"));
    }

    @Test
    void importCanDisableDetectedInProgressColumn() {
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
        assertThat(result.activeStates()).containsExactly("Ready for Codex");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("This workflow has no in-progress column configured")
                .doesNotContain("list_name \"In Progress\"");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().activeStates()).containsExactly("Ready for Codex");
        assertThat(config.trelloTools().allowedMoveListNames()).containsExactly("human review", "blocked");
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
        assertThat(result.openColumns())
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
                .contains("blocked or unsafe to hand off")
                .contains("list_name \"Review\" so the card leaves the active column")
                .contains("Do not leave")
                .contains("blocked work in an active column")
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
