package com.openai.symphony.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.symphony.config.ConfigResolver;
import com.openai.symphony.config.EffectiveConfig;
import com.openai.symphony.domain.Card;
import com.openai.symphony.workflow.WorkflowDefinition;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private final List<String> writeRequests = new ArrayList<>();

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
        server.createContext(
                "/1/boards/terminal-input",
                exchange -> respond(exchange, "{\"id\":\"terminal-board\",\"name\":\"Board\",\"closed\":false}"));
        server.createContext(
                "/1/boards/terminal-board/lists",
                exchange -> respond(
                        exchange,
                        """
                        [
                          {"id":"terminal-todo","name":"Todo","closed":false,"pos":1},
                          {"id":"terminal-done","name":"Done","closed":false,"pos":2},
                          {"id":"terminal-archived","name":"Later","closed":true,"pos":3}
                        ]
                        """));
        server.createContext(
                "/1/boards/terminal-board/cards/all",
                exchange -> respond(
                        exchange,
                        """
                        [
                          {"id":"65df9f72b4a22a1234567892","name":"Done","idList":"terminal-done","idBoard":"terminal-board","closed":false,"shortLink":"done","labels":[],"pos":1},
                          {"id":"65df9f73b4a22a1234567893","name":"Archived card","idList":"terminal-todo","idBoard":"terminal-board","closed":true,"shortLink":"archived","labels":[],"pos":2},
                          {"id":"65df9f74b4a22a1234567894","name":"Archived list duplicate","idList":"terminal-archived","idBoard":"terminal-board","closed":false,"shortLink":"dup","labels":[],"pos":3}
                        ]
                        """));
        server.createContext(
                "/1/lists/terminal-archived/cards",
                exchange -> respond(
                        exchange,
                        """
                        [
                          {"id":"65df9f74b4a22a1234567894","name":"Archived list duplicate","idList":"terminal-archived","idBoard":"terminal-board","closed":false,"shortLink":"dup","labels":[],"pos":3},
                          {"id":"65df9f75b4a22a1234567895","name":"Archived list only","idList":"terminal-archived","idBoard":"terminal-board","closed":false,"shortLink":"list-only","labels":[],"pos":4}
                        ]
                        """));
        server.createContext(
                "/1/boards/lookup-input",
                exchange -> respond(exchange, "{\"id\":\"lookup-board\",\"name\":\"Board\",\"closed\":false}"));
        server.createContext(
                "/1/boards/lookup-board/lists",
                exchange -> respond(
                        exchange,
                        """
                        [
                          {"id":"lookup-todo","name":"Todo","closed":false,"pos":1},
                          {"id":"lookup-review","name":"Review","closed":false,"pos":2}
                        ]
                        """));
        server.createContext(
                "/1/cards/card-found",
                exchange -> respond(
                        exchange,
                        "{\"id\":\"card-found\",\"name\":\"Found\",\"idList\":\"lookup-review\",\"idBoard\":\"lookup-board\",\"closed\":false,\"shortLink\":\"found\",\"labels\":[]}"));
        server.createContext("/1/cards/card-missing", exchange -> respond(exchange, 404, "{}"));
        server.createContext("/1/cards/card-failed", exchange -> respond(exchange, 500, "{}"));
        server.createContext(
                "/1/cards/card-malformed",
                exchange -> respond(
                        exchange,
                        "{\"id\":\"card-malformed\",\"idList\":\"lookup-review\",\"idBoard\":\"lookup-board\",\"closed\":false,\"labels\":[]}"));
        server.createContext(
                "/1/boards/closed-input",
                exchange -> respond(exchange, "{\"id\":\"closed-board\",\"name\":\"Board\",\"closed\":true}"));
        server.createContext("/1/cards/write-card/actions/comments", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"comment-1\"}");
        });
        server.createContext("/1/cards/write-card/idList", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"write-card\"}");
        });
        server.createContext("/1/cards/rate-limited-card/actions/comments", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 429, "{}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchesCandidatesWithOAuthHeaderAndNormalizesPriorityAndIdentifiers() {
        // given
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("input", Map.of("active_states", List.of("Todo")));

        // when
        var cards = client.fetchCandidateCards(config);

        // then
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.identifier()).isEqualTo("TRELLO-abc");
            assertThat(card.priority()).isEqualTo(1);
            assertThat(card.state()).isEqualTo("Todo");
            assertThat(card.createdAt()).isNotNull();
        });
        assertThat(authorization.get()).contains("oauth_consumer_key=\"key\"").contains("oauth_token=\"token\"");
    }

    @Test
    void fetchesTerminalCardsFromTerminalListsArchivedCardsAndArchivedListsWithoutDuplicates() {
        // given
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("terminal-input", Map.of());

        // when
        var cards = client.fetchTerminalCards(config);

        // then
        assertThat(cards)
                .extracting(Card::identifier)
                .containsExactly("TRELLO-done", "TRELLO-archived", "TRELLO-dup", "TRELLO-list-only");
        assertThat(cards).extracting(Card::state).containsExactly("Done", "Archived", "ArchivedList", "ArchivedList");
    }

    @Test
    void fetchCardStatesByIdsKeepsPartialFailuresLocalToEachCard() {
        // given
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("lookup-input", Map.of());

        // when
        var results = client.fetchCardStatesByIds(
                config, List.of("card-found", "card-missing", "card-failed", "card-malformed"));

        // then
        assertThat(results.keySet()).containsExactly("card-found", "card-missing", "card-failed", "card-malformed");
        assertThat(results.get("card-found")).isInstanceOfSatisfying(CardLookupResult.Found.class, found -> assertThat(
                        found.card().state())
                .isEqualTo("Review"));
        assertThat(results.get("card-missing")).isInstanceOf(CardLookupResult.Missing.class);
        assertThat(results.get("card-failed"))
                .isInstanceOfSatisfying(CardLookupResult.Failed.class, failed -> assertThat(failed.code())
                        .isEqualTo("trello_api_status"));
        assertThat(results.get("card-malformed"))
                .isInstanceOfSatisfying(CardLookupResult.Failed.class, failed -> assertThat(failed.code())
                        .isEqualTo("trello_unknown_payload"));
    }

    @Test
    void writeOperationsSendExpectedMethodsAndDoNotRetryFailedWrites() {
        // given
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("input", Map.of());

        // when
        client.addComment(config, "write-card", "Ready for review");
        client.moveCardToList(config, "write-card", "review-list");
        Throwable thrown = catchThrowable(() -> client.addComment(config, "rate-limited-card", "Retry me"));

        // then
        assertThat(writeRequests)
                .containsExactly(
                        "POST /1/cards/write-card/actions/comments?text=Ready+for+review",
                        "PUT /1/cards/write-card/idList?value=review-list",
                        "POST /1/cards/rate-limited-card/actions/comments?text=Retry+me");
        assertThat(thrown).isInstanceOfSatisfying(TrelloException.class, exception -> assertThat(exception.code())
                .isEqualTo("trello_api_rate_limited"));
    }

    @Test
    void resolveBoardIdRejectsClosedBoard() {
        // given
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("closed-input", Map.of());

        // when
        Throwable thrown = catchThrowable(() -> client.resolveBoardId(config));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloException.class, exception -> assertThat(exception.code())
                .isEqualTo("trello_board_closed"));
    }

    @Test
    void activeTerminalAndDispatchOrderingHonorListConfiguration() {
        // given
        var config = config(
                "board-1", Map.of("active_list_ids", List.of("ready", "review"), "terminal_list_ids", List.of("done")));
        Card lowPriorityReview = card("card-3", "TRELLO-c", "Review", "review", 2, BigDecimal.ONE);
        Card highPriorityReview = card("card-1", "TRELLO-a", "Review", "review", 1, BigDecimal.TEN);
        Card readyWithoutPriority = card("card-2", "TRELLO-b", "Ready", "ready", null, BigDecimal.TEN);
        Card done = card("card-4", "TRELLO-d", "Done", "done", null, BigDecimal.ONE);
        Card outOfScope = new Card(
                "card-5",
                "TRELLO-e",
                "Out of scope",
                "",
                null,
                "Ready",
                "list",
                "ready",
                "Ready",
                false,
                "other-board",
                false,
                false,
                null,
                "e",
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Instant.parse("2026-01-04T00:00:00Z"),
                Instant.parse("2026-01-04T00:00:00Z"),
                null,
                null,
                BigDecimal.ONE);

        // when
        List<Card> sorted = List.of(lowPriorityReview, readyWithoutPriority, highPriorityReview).stream()
                .sorted(TrelloClient.dispatchComparator(config))
                .toList();

        // then
        assertThat(TrelloClient.isActive(readyWithoutPriority, config)).isTrue();
        assertThat(TrelloClient.isTerminal(done, config)).isTrue();
        assertThat(TrelloClient.isActive(done, config)).isFalse();
        assertThat(TrelloClient.isActive(outOfScope, config)).isFalse();
        assertThat(sorted).extracting(Card::identifier).containsExactly("TRELLO-a", "TRELLO-c", "TRELLO-b");
    }

    private EffectiveConfig config(String boardId, Map<String, Object> trackerOverrides) {
        Map<String, Object> tracker = new LinkedHashMap<>();
        tracker.put("kind", "trello");
        tracker.put("endpoint", "http://127.0.0.1:" + server.getAddress().getPort() + "/1");
        tracker.put("api_key", "key");
        tracker.put("api_token", "token");
        tracker.put("board_id", boardId);
        tracker.put("active_states", List.of("Todo", "Review", "Ready"));
        tracker.put("terminal_states", List.of("Done", "Archived", "ArchivedList", "ArchivedBoard", "Deleted"));
        tracker.put("priority_labels", Map.of("P1", 1, "P2", 2));
        tracker.putAll(trackerOverrides);
        return new ConfigResolver()
                .resolve(new WorkflowDefinition(tempDir.resolve("WORKFLOW.md"), Map.of("tracker", tracker), ""))
                .withResolvedBoardId(boardId.equals("input") ? "board-1" : boardId);
    }

    private static Card card(
            String id, String identifier, String state, String listId, Integer priority, BigDecimal position) {
        return new Card(
                id,
                identifier,
                identifier,
                "",
                priority,
                state,
                "list",
                listId,
                state,
                false,
                "board-1",
                false,
                false,
                null,
                identifier,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Instant.parse("2026-01-0%sT00:00:00Z".formatted(id.charAt(id.length() - 1))),
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                null,
                position);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
