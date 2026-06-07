package ch.fmartin.symphony.trello.tracker;

import static ch.fmartin.symphony.trello.TestHttpExchange.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class TrelloClientTest {
    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final List<String> readRequests = new ArrayList<>();
    private final List<String> writeRequests = new ArrayList<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
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
                          {"id":"list-progress","name":"In Progress","closed":false,"pos":2},
                          {"id":"list-done","name":"Done","closed":false,"pos":3},
                          {"id":"list-archived","name":"Later","closed":true,"pos":4}
                        ]
                        """));
        server.createContext("/1/boards/board-1/cards/open", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    [
                      {"id":"000000000000000000000101","name":"A","desc":"","idList":"list-todo","idBoard":"board-1","closed":false,"idShort":7,"shortLink":"abc","shortUrl":"u","url":"u","labels":[{"id":"l1","name":"P1"}],"dateLastActivity":"2026-02-24T20:10:12.000Z","pos":2},
                      {"id":"000000000000000000000102","name":"B","desc":"","idList":"list-done","idBoard":"board-1","closed":false,"shortLink":"def","labels":[],"pos":1}
                    ]
                    """);
        });
        server.createContext("/1/cards/000000000000000000000101", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"000000000000000000000101","name":"A","desc":"","idList":"list-todo","idBoard":"board-1","closed":false,"idShort":7,"shortLink":"abc","shortUrl":"u","url":"u","labels":[{"id":"l1","name":"P1"}],"actions":[{"id":"comment-1","date":"2026-02-24T20:11:12.000Z","data":{"text":"Please rework the edge case."},"memberCreator":{"fullName":"Reviewer"}}],"dateLastActivity":"2026-02-24T20:10:12.000Z","pos":2}
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
                          {"id":"000000000000000000000103","name":"Done","idList":"terminal-done","idBoard":"terminal-board","closed":false,"shortLink":"done","labels":[],"pos":1},
                          {"id":"000000000000000000000104","name":"Archived card","idList":"terminal-todo","idBoard":"terminal-board","closed":true,"shortLink":"archived","labels":[],"pos":2},
                          {"id":"000000000000000000000105","name":"Archived list duplicate","idList":"terminal-archived","idBoard":"terminal-board","closed":false,"shortLink":"dup","labels":[],"pos":3}
                        ]
                        """));
        server.createContext(
                "/1/lists/terminal-archived/cards",
                exchange -> respond(
                        exchange,
                        """
                        [
                          {"id":"000000000000000000000105","name":"Archived list duplicate","idList":"terminal-archived","idBoard":"terminal-board","closed":false,"shortLink":"dup","labels":[],"pos":3},
                          {"id":"000000000000000000000106","name":"Archived list only","idList":"terminal-archived","idBoard":"terminal-board","closed":false,"shortLink":"list-only","labels":[],"pos":4}
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
        server.createContext("/1/cards/card-found", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    "{\"id\":\"card-found\",\"name\":\"Found\",\"idList\":\"lookup-review\",\"idBoard\":\"lookup-board\",\"closed\":false,\"shortLink\":\"found\",\"labels\":[],\"actions\":[{\"id\":\"comment-2\",\"date\":\"2026-02-25T20:11:12.000Z\",\"data\":{\"text\":\"Follow-up review note.\"},\"memberCreator\":{\"username\":\"reviewer\"}}]}");
        });
        server.createContext("/1/cards/card-workpad-old", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String rawQuery = exchange.getRequestURI().getRawQuery();
            boolean deepLookup = rawQuery != null && rawQuery.contains("actions_limit=1000");
            respond(
                    exchange,
                    cardWithActions("card-workpad-old", deepLookup ? oldWorkpadActions() : regularActions(20)));
        });
        server.createContext("/1/cards/card-workpad-deep-failed", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery != null && rawQuery.contains("actions_limit=1000")) {
                respond(exchange, 429, "{}");
                return;
            }
            respond(exchange, cardWithActions("card-workpad-deep-failed", regularActions(20)));
        });
        server.createContext("/1/cards/card-missing", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 404, "{}");
        });
        server.createContext("/1/cards/card-failed", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 500, "{}");
        });
        server.createContext("/1/cards/card-malformed", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    "{\"id\":\"card-malformed\",\"idList\":\"lookup-review\",\"idBoard\":\"lookup-board\",\"closed\":false,\"labels\":[]}");
        });
        server.createContext("/1/cards/000000000000000000000107/idList", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"000000000000000000000107\"}");
        });
        server.createContext("/1/cards/000000000000000000000107", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"000000000000000000000107","name":"Picked up","desc":"","idList":"list-progress","idBoard":"board-1","closed":false,"idShort":8,"shortLink":"pickup","shortUrl":"u","url":"u","labels":[],"actions":[],"dateLastActivity":"2026-02-24T20:10:12.000Z","pos":1}
                    """);
        });
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
        server.createContext("/1/actions/comment-1/text", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"comment-1\"}");
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
            assertThat(card.comments()).isEmpty();
            assertThat(card.createdAt()).isNotNull();
        });
        assertThat(authorization.get()).contains("oauth_consumer_key=\"key\"").contains("oauth_token=\"token\"");
        assertThat(readRequests).hasSize(1);
        assertThat(readRequests.getFirst())
                .startsWith("GET /1/boards/board-1/cards/open?")
                .contains("fields=")
                .doesNotContain("actions=");
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
        assertThat(results.get("card-found"))
                .isInstanceOfSatisfying(CardLookupResult.Found.class, found -> assertThat(found.card())
                        .satisfies(card -> {
                            assertThat(card.state()).isEqualTo("Review");
                            assertThat(card.comments()).singleElement().satisfies(comment -> {
                                assertThat(comment.text()).isEqualTo("Follow-up review note.");
                                assertThat(comment.author()).isEqualTo("reviewer");
                            });
                        }));
        assertThat(results.get("card-missing")).isInstanceOf(CardLookupResult.Missing.class);
        assertThat(results.get("card-failed"))
                .isInstanceOfSatisfying(CardLookupResult.Failed.class, failed -> assertThat(failed.code())
                        .isEqualTo("trello_api_status"));
        assertThat(results.get("card-malformed"))
                .isInstanceOfSatisfying(CardLookupResult.Failed.class, failed -> assertThat(failed.code())
                        .isEqualTo("trello_unknown_payload"));
        assertThat(readRequests).anySatisfy(request -> assertThat(request)
                .startsWith("GET /1/cards/card-found?")
                .contains("actions_limit=20")
                .contains("action_fields=data%2Cdate%2CmemberCreator"));
    }

    @Test
    void fetchCardStateForWorkpadRequestsDeepCommentWindowAndActionIds() {
        // given
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("lookup-input", Map.of());

        // when
        var result = client.fetchCardStateForWorkpad(config, "card-found");

        // then
        assertThat(result).isInstanceOfSatisfying(CardLookupResult.Found.class, found -> assertThat(found.card())
                .satisfies(card -> assertThat(card.comments())
                        .singleElement()
                        .satisfies(comment -> assertThat(comment.id()).isEqualTo("comment-2"))));
        assertThat(readRequests).anySatisfy(request -> assertThat(request)
                .startsWith("GET /1/cards/card-found?")
                .contains("actions_limit=1000")
                .contains("action_fields=id%2Cdata%2Cdate%2CmemberCreator"));
    }

    @Test
    void fetchCardStatesByIdsDoesNotFetchOlderWorkpadDuringStateRefresh() {
        // given
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("lookup-input", Map.of());

        // when
        var results = client.fetchCardStatesByIds(config, List.of("card-workpad-old"));

        // then
        assertThat(results.get("card-workpad-old"))
                .isInstanceOfSatisfying(CardLookupResult.Found.class, found -> assertThat(
                                found.card().comments())
                        .hasSize(20)
                        .extracting(Card.Comment::text)
                        .doesNotContain(TrelloClient.WORKPAD_MARKER + "\n\nOlder plan")
                        .contains("Regular comment 19"));
        assertThat(readRequests)
                .filteredOn(request -> request.startsWith("GET /1/cards/card-workpad-old?"))
                .singleElement()
                .satisfies(request -> assertThat(request).contains("actions_limit=20"));
    }

    @Test
    void fetchCardStatesForPromptByIdsIncludesOlderWorkpadWithoutExpandingRecentComments() {
        // given
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("lookup-input", Map.of());

        // when
        var results = client.fetchCardStatesForPromptByIds(config, List.of("card-workpad-old"));

        // then
        assertThat(results.get("card-workpad-old"))
                .isInstanceOfSatisfying(CardLookupResult.Found.class, found -> assertThat(
                                found.card().comments())
                        .hasSize(21)
                        .extracting(Card.Comment::text)
                        .startsWith(TrelloClient.WORKPAD_MARKER + "\n\nOlder plan")
                        .contains("Regular comment 19"));
        assertThat(readRequests)
                .filteredOn(request -> request.startsWith("GET /1/cards/card-workpad-old?"))
                .satisfiesExactly(
                        request -> assertThat(request).contains("actions_limit=20"),
                        request -> assertThat(request).contains("actions_limit=1000"));
    }

    @Test
    void fetchCardStatesForPromptByIdsKeepsReadableCardWhenOlderWorkpadLookupFails() {
        // given
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("lookup-input", Map.of("max_api_retries", 0));

        // when
        var results = client.fetchCardStatesForPromptByIds(config, List.of("card-workpad-deep-failed"));

        // then
        assertThat(results.get("card-workpad-deep-failed"))
                .isInstanceOfSatisfying(CardLookupResult.Found.class, found -> assertThat(
                                found.card().comments())
                        .hasSize(20)
                        .extracting(Card.Comment::text)
                        .contains("Regular comment 19")
                        .doesNotContain(TrelloClient.WORKPAD_MARKER + "\n\nOlder plan"));
        assertThat(readRequests)
                .filteredOn(request -> request.startsWith("GET /1/cards/card-workpad-deep-failed?"))
                .satisfiesExactly(
                        request -> assertThat(request).contains("actions_limit=20"),
                        request -> assertThat(request).contains("actions_limit=1000"));
    }

    @Test
    void writeOperationsSendExpectedMethodsAndDoNotRetryFailedWrites() {
        // given
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("input", Map.of());

        // when
        client.addComment(config, "write-card", "Ready for review");
        client.updateComment(config, "comment-1", "Updated workpad");
        client.moveCardToList(config, "write-card", "review-list");
        Throwable thrown = catchThrowable(() -> client.addComment(config, "rate-limited-card", "Retry me"));

        // then
        assertThat(writeRequests)
                .containsExactly(
                        "POST /1/cards/write-card/actions/comments?text=Ready+for+review",
                        "PUT /1/actions/comment-1/text?value=Updated+workpad",
                        "PUT /1/cards/write-card/idList?value=review-list",
                        "POST /1/cards/rate-limited-card/actions/comments?text=Retry+me");
        assertThat(thrown).isInstanceOfSatisfying(TrelloException.class, exception -> assertThat(exception.code())
                .isEqualTo("trello_api_rate_limited"));
    }

    @Test
    void rateLimitWarningNamesPollingIntervalSettingWorkflowPathAndBoardScaleGuidance() {
        // given
        var config = config("input", Map.of());

        // when
        String warning = TrelloClient.rateLimitWarning(config);

        // then
        assertThat(warning)
                .contains("Trello rate limit reached")
                .contains("polling.interval_ms")
                .contains(Long.toString(ConfigDefaults.DEFAULT_POLLING_INTERVAL_MS))
                .contains(tempDir.resolve("WORKFLOW.md").toString())
                .contains("more than 5-10 boards")
                .contains("same Trello token");
    }

    @Test
    void prepareForDispatchMovesQueueCardToConfiguredInProgressListAndReturnsRefreshedCard() {
        // given
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config(
                "input", Map.of("active_states", List.of("Todo", "In Progress"), "in_progress_state", "In Progress"));
        Card queueCard = card("000000000000000000000107", "TRELLO-pickup", "Todo", "list-todo", null, BigDecimal.ONE);

        // when
        Card prepared = client.prepareForDispatch(config, queueCard);

        // then
        assertThat(prepared.state()).isEqualTo("In Progress");
        assertThat(prepared.listId()).isEqualTo("list-progress");
        assertThat(writeRequests).containsExactly("PUT /1/cards/000000000000000000000107/idList?value=list-progress");
        assertThat(readRequests)
                .anySatisfy(request -> assertThat(request).startsWith("GET /1/cards/000000000000000000000107?"));
    }

    @Test
    void releaseFromDispatchMovesInProgressCardBackToPreviousActiveList() {
        // given
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config(
                "input", Map.of("active_states", List.of("Todo", "In Progress"), "in_progress_state", "In Progress"));
        Card inProgressCard =
                card("000000000000000000000107", "TRELLO-pickup", "In Progress", "list-progress", null, BigDecimal.ONE);

        // when
        client.releaseFromDispatch(config, inProgressCard);

        // then
        assertThat(writeRequests).containsExactly("PUT /1/cards/000000000000000000000107/idList?value=list-todo");
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
        Card highPriorityReady = card("card-2", "TRELLO-b", "Ready", "ready", 1, BigDecimal.ZERO);
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
                List.of(),
                Instant.parse("2026-01-04T00:00:00Z"),
                Instant.parse("2026-01-04T00:00:00Z"),
                null,
                null,
                BigDecimal.ONE);

        // when
        List<Card> sorted = List.of(lowPriorityReview, highPriorityReady, highPriorityReview).stream()
                .sorted(TrelloClient.dispatchComparator(config))
                .toList();

        // then
        assertThat(TrelloClient.isActive(highPriorityReady, config)).isTrue();
        assertThat(TrelloClient.isTerminal(done, config)).isTrue();
        assertThat(TrelloClient.isActive(done, config)).isFalse();
        assertThat(TrelloClient.isActive(outOfScope, config)).isFalse();
        assertThat(sorted).extracting(Card::identifier).containsExactly("TRELLO-a", "TRELLO-c", "TRELLO-b");
    }

    @Test
    void dispatchOrderingPrioritizesLaterConfiguredActiveStatesBeforePriority() {
        // given
        var config = config("board-1", Map.of("active_states", List.of("Ready for Codex", "In Progress", "Merging")));
        Card highPriorityReady = card("card-1", "TRELLO-a", "Ready for Codex", null, 1, BigDecimal.ZERO);
        Card lowPriorityMerging = card("card-2", "TRELLO-b", "Merging", null, 3, BigDecimal.TEN);
        Card inProgress = card("card-3", "TRELLO-c", "In Progress", null, 2, BigDecimal.ONE);
        Card sameLaneHigherPriority = card("card-4", "TRELLO-d", "Merging", null, 1, BigDecimal.TEN);

        // when
        List<Card> sorted = List.of(highPriorityReady, lowPriorityMerging, inProgress, sameLaneHigherPriority).stream()
                .sorted(TrelloClient.dispatchComparator(config))
                .toList();

        // then
        assertThat(sorted).extracting(Card::identifier).containsExactly("TRELLO-d", "TRELLO-b", "TRELLO-c", "TRELLO-a");
    }

    @MethodSource("specialTerminalCards")
    @ParameterizedTest(name = "{0} remains terminal even with terminal list IDs configured")
    void terminalListIdsDoNotOverrideSpecialTerminalStates(String displayName, Card card) {
        // given
        var config = config("board-1", Map.of("terminal_list_ids", List.of("done")));

        // when
        boolean terminal = TrelloClient.isTerminal(card, config);

        // then
        assertThat(terminal).as(displayName).isTrue();
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
                List.of(),
                Instant.parse("2026-01-0%sT00:00:00Z".formatted(id.charAt(id.length() - 1))),
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                null,
                position);
    }

    private static Stream<Arguments> specialTerminalCards() {
        return Stream.of(
                Arguments.of(
                        "archived card",
                        card(
                                "card-6",
                                "TRELLO-archived",
                                "Archived",
                                "card_closed",
                                "todo",
                                "Todo",
                                false,
                                false,
                                true)),
                Arguments.of(
                        "card in archived list",
                        card(
                                "card-7",
                                "TRELLO-archived-list",
                                "ArchivedList",
                                "list_closed",
                                "todo",
                                "Todo",
                                true,
                                false,
                                false)),
                Arguments.of(
                        "card in archived board",
                        card(
                                "card-8",
                                "TRELLO-archived-board",
                                "ArchivedBoard",
                                "board_closed",
                                "todo",
                                "Todo",
                                false,
                                true,
                                false)),
                Arguments.of(
                        "deleted card snapshot",
                        card("card-9", "TRELLO-deleted", "Deleted", "deleted", "todo", "Todo", false, false, false)));
    }

    private static Card card(
            String id,
            String identifier,
            String state,
            String stateSource,
            String listId,
            String listName,
            Boolean listClosed,
            Boolean boardClosed,
            boolean closed) {
        return new Card(
                id,
                identifier,
                identifier,
                "",
                null,
                state,
                stateSource,
                listId,
                listName,
                listClosed,
                "board-1",
                boardClosed,
                closed,
                null,
                identifier,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                null,
                BigDecimal.ONE);
    }

    private static String cardWithActions(String cardId, String actionsJson) {
        return """
                {"id":"%s","name":"Found","idList":"lookup-review","idBoard":"lookup-board","closed":false,"shortLink":"found","labels":[],"actions":%s}
                """
                .formatted(cardId, actionsJson);
    }

    private static String oldWorkpadActions() {
        return "[" + workpadAction() + "," + regularActions(20).substring(1);
    }

    private static String workpadAction() {
        return """
                {"id":"comment-workpad","date":"2026-02-25T20:00:00.000Z","data":{"text":"## Codex Workpad\\n\\nOlder plan"},"memberCreator":{"username":"codex"}}
                """;
    }

    private static String regularActions(int count) {
        return IntStream.range(0, count)
                .mapToObj(index ->
                        """
                        {"id":"regular-%d","date":"2026-02-25T20:11:12.000Z","data":{"text":"Regular comment %d"},"memberCreator":{"username":"reviewer"}}
                        """
                                .formatted(index, index))
                .collect(Collectors.joining(",", "[", "]"));
    }
}
