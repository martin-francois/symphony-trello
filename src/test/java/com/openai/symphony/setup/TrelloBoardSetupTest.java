package com.openai.symphony.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.symphony.config.ConfigResolver;
import com.openai.symphony.config.EffectiveConfig;
import com.openai.symphony.workflow.WorkflowLoader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrelloBoardSetupTest {
    private HttpServer server;
    private TrelloBoardSetup setup;
    private final List<String> createdLists = new ArrayList<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        setup = new TrelloBoardSetup(new ObjectMapper());

        server.createContext("/1/boards/", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            Map<String, String> query = query(exchange);
            assertThat(query)
                    .containsEntry("name", "Symphony Work Queue")
                    .containsEntry("defaultLists", "false")
                    .containsEntry("defaultLabels", "false")
                    .containsEntry("idOrganization", "workspace-1");
            respond(
                    exchange,
                    """
                    {"id":"board-1","name":"Symphony Work Queue","shortLink":"abc123","url":"https://trello.com/b/abc123/symphony-work-queue"}
                    """);
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
        server.createContext(
                "/1/boards/board-1/lists",
                exchange -> respond(
                        exchange,
                        """
                        [
                          {"id":"list-inbox","name":"Inbox","closed":false,"pos":1},
                          {"id":"list-ready","name":"Ready for Codex","closed":false,"pos":2},
                          {"id":"list-review","name":"Review","closed":false,"pos":3},
                          {"id":"list-done","name":"Done","closed":false,"pos":4},
                          {"id":"list-archive","name":"Archived old work","closed":true,"pos":5}
                        ]
                        """));
        server.createContext(
                "/1/members/me/organizations",
                exchange -> respond(
                        exchange,
                        """
                        [
                          {"id":"workspace-1","name":"symphony-automation","displayName":"Symphony Automation","url":"https://trello.com/w/symphony-automation"}
                        ]
                        """));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void createsRecommendedBoardListsAndWorkflow() {
        Path workflow = tempDir.resolve("generated-workflow.md");

        var result = setup.createRecommendedBoard(new TrelloBoardSetup.NewBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "Symphony Work Queue",
                "workspace-1",
                workflow,
                Path.of("./workspaces"),
                1,
                false));

        assertThat(result.boardKey()).isEqualTo("abc123");
        assertThat(createdLists).containsExactly("Inbox", "Ready for Codex", "Review", "Done");
        assertThat(authorization.get()).contains("oauth_consumer_key=\"key\"").contains("oauth_token=\"token\"");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("board_id: \"abc123\"")
                .contains("- \"Ready for Codex\"")
                .contains("- \"Done\"")
                .contains("max_concurrent_agents: 1");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().boardId()).isEqualTo("abc123");
        assertThat(config.tracker().activeStates()).containsExactly("Ready for Codex");
        assertThat(config.tracker().terminalStates())
                .contains("done", "archived", "archivedlist", "archivedboard", "deleted");
    }

    @Test
    void importsExistingBoardAndWritesWorkflowUsingDiscoveredRecommendedLists() {
        Path workflow = tempDir.resolve("imported-workflow.md");

        var result = setup.importExistingBoard(new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of(),
                List.of(),
                workflow,
                Path.of("./agent-workspaces"),
                2,
                false));

        assertThat(result.openLists()).containsExactly("Inbox", "Ready for Codex", "Review", "Done");
        assertThat(result.activeStates()).containsExactly("Ready for Codex");
        assertThat(result.terminalStates()).containsExactly("Done");
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("board_id: \"existing\"")
                .contains("root: \"./agent-workspaces\"")
                .contains("max_concurrent_agents: 2");
        EffectiveConfig config = resolve(workflow);
        assertThat(config.tracker().boardId()).isEqualTo("existing");
        assertThat(config.workspace().root()).isEqualTo(workflow.getParent().resolve("agent-workspaces"));
    }

    @Test
    void listsWorkspacesAvailableToTheToken() {
        var workspaces = setup.listWorkspaces(new TrelloBoardSetup.WorkspaceListRequest(
                endpoint(), new TrelloBoardSetup.TrelloCredentials("key", "token")));

        assertThat(workspaces).singleElement().satisfies(workspace -> {
            assertThat(workspace.id()).isEqualTo("workspace-1");
            assertThat(workspace.displayName()).isEqualTo("Symphony Automation");
        });
    }

    @Test
    void refusesToOverwriteExistingWorkflowUnlessForced() throws IOException {
        Path workflow = tempDir.resolve("WORKFLOW.md");
        java.nio.file.Files.writeString(workflow, "keep me", StandardCharsets.UTF_8);

        var request = new TrelloBoardSetup.ImportBoardRequest(
                endpoint(),
                new TrelloBoardSetup.TrelloCredentials("key", "token"),
                "input",
                List.of("Ready for Codex"),
                List.of("Done"),
                workflow,
                Path.of("./workspaces"),
                1,
                false);

        assertThatThrownBy(() -> setup.importExistingBoard(request))
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("--force");
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo("keep me");
    }

    private java.net.URI endpoint() {
        return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/1");
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
