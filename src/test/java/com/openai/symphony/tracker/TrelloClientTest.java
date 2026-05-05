package com.openai.symphony.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.symphony.config.ConfigResolver;
import com.openai.symphony.workflow.WorkflowDefinition;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrelloClientTest {
    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/1/boards/input",
                exchange -> respond(exchange, "{\"id\":\"board-1\",\"name\":\"Board\",\"closed\":false}"));
        server.createContext(
                "/1/boards/board-1/lists",
                exchange -> respond(
                        exchange,
                        """
                [
                  {"id":"list-todo","name":"Todo","closed":false,"pos":1},
                  {"id":"list-done","name":"Done","closed":false,"pos":2},
                  {"id":"list-archived","name":"Later","closed":true,"pos":3}
                ]
                """));
        server.createContext("/1/boards/board-1/cards/open", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(
                    exchange,
                    """
                    [
                      {"id":"65df9f70b4a22a1234567890","name":"A","desc":"","idList":"list-todo","idBoard":"board-1","closed":false,"idShort":7,"shortLink":"abc","shortUrl":"u","url":"u","labels":[{"id":"l1","name":"P1"}],"dateLastActivity":"2026-02-24T20:10:12.000Z","pos":2},
                      {"id":"65df9f71b4a22a1234567891","name":"B","desc":"","idList":"list-done","idBoard":"board-1","closed":false,"shortLink":"def","labels":[],"pos":1}
                    ]
                    """);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchesCandidatesWithOAuthHeaderAndNormalizesPriorityAndIdentifiers() {
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = new ConfigResolver()
                .resolve(new WorkflowDefinition(
                        tempDir.resolve("WORKFLOW.md"),
                        Map.of(
                                "tracker",
                                Map.of(
                                        "kind",
                                        "trello",
                                        "endpoint",
                                        "http://127.0.0.1:"
                                                + server.getAddress().getPort() + "/1",
                                        "api_key",
                                        "key",
                                        "api_token",
                                        "token",
                                        "board_id",
                                        "input",
                                        "active_states",
                                        List.of("Todo"),
                                        "terminal_states",
                                        List.of("Done", "Archived", "ArchivedList", "ArchivedBoard", "Deleted"))),
                        ""))
                .withCanonicalBoardId("board-1");

        var cards = client.fetchCandidateCards(config);

        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.identifier()).isEqualTo("TRELLO-abc");
            assertThat(card.priority()).isEqualTo(1);
            assertThat(card.state()).isEqualTo("Todo");
            assertThat(card.createdAt()).isNotNull();
        });
        assertThat(authorization.get()).contains("oauth_consumer_key=\"key\"").contains("oauth_token=\"token\"");
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
