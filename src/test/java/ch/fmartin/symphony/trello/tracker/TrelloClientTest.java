package ch.fmartin.symphony.trello.tracker;

import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.boardJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.listsJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.respond;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.trelloList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
    private static final String TRELLO_CARD_URL_PREFIX = "https://trello.com/c/";

    private FakeTrelloServer trello;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final List<String> readRequests = new ArrayList<>();
    private final List<String> writeRequests = new ArrayList<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        trello = new FakeTrelloServer();
        trello.on("/1/boards/input", exchange -> respond(exchange, boardJson("board-1", "Board", false)));
        trello.on(
                "/1/boards/board-1/lists",
                exchange -> respond(
                        exchange,
                        listsJson(
                                trelloList("list-todo", "Todo", 1),
                                trelloList("list-progress", "In Progress", 2),
                                trelloList("list-done", "Done", 3),
                                trelloList("list-archived", "Later", true, 4))));
        trello.on("/1/boards/board-1/cards/open", exchange -> {
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
        trello.on("/1/cards/000000000000000000000101", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"000000000000000000000101","name":"A","desc":"","idList":"list-todo","idBoard":"board-1","closed":false,"idShort":7,"shortLink":"abc","shortUrl":"u","url":"u","labels":[{"id":"l1","name":"P1"}],"actions":[{"id":"comment-1","date":"2026-02-24T20:11:12.000Z","data":{"text":"Please rework the edge case."},"memberCreator":{"fullName":"Reviewer"}}],"dateLastActivity":"2026-02-24T20:10:12.000Z","pos":2}
                    """);
        });
        trello.on(
                "/1/boards/terminal-input", exchange -> respond(exchange, boardJson("terminal-board", "Board", false)));
        trello.on(
                "/1/boards/terminal-board/lists",
                exchange -> respond(
                        exchange,
                        listsJson(
                                trelloList("terminal-todo", "Todo", 1),
                                trelloList("terminal-done", "Done", 2),
                                trelloList("terminal-archived", "Later", true, 3))));
        trello.on(
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
        trello.on(
                "/1/lists/terminal-archived/cards",
                exchange -> respond(
                        exchange,
                        """
                        [
                          {"id":"000000000000000000000105","name":"Archived list duplicate","idList":"terminal-archived","idBoard":"terminal-board","closed":false,"shortLink":"dup","labels":[],"pos":3},
                          {"id":"000000000000000000000106","name":"Archived list only","idList":"terminal-archived","idBoard":"terminal-board","closed":false,"shortLink":"list-only","labels":[],"pos":4}
                        ]
                        """));
        trello.on(
                "/1/boards/lookup-input",
                exchange -> respond(exchange, "{\"id\":\"lookup-board\",\"name\":\"Board\",\"closed\":false}"));
        trello.on(
                "/1/boards/lookup-board/lists",
                exchange -> respond(
                        exchange,
                        """
                        [
                          {"id":"lookup-todo","name":"Todo","closed":false,"pos":1},
                          {"id":"lookup-review","name":"Review","closed":false,"pos":2}
                        ]
                        """));
        trello.on("/1/cards/card-found", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    "{\"id\":\"card-found\",\"name\":\"Found\",\"idList\":\"lookup-review\",\"idBoard\":\"lookup-board\",\"closed\":false,\"shortLink\":\"found\",\"labels\":[],\"actions\":[{\"id\":\"comment-2\",\"date\":\"2026-02-25T20:11:12.000Z\",\"data\":{\"text\":\"Follow-up review note.\"},\"memberCreator\":{\"username\":\"reviewer\"}}]}");
        });
        trello.on("/1/cards/card-workpad-old", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String rawQuery = exchange.getRequestURI().getRawQuery();
            boolean deepLookup = rawQuery != null && rawQuery.contains("actions_limit=1000");
            respond(
                    exchange,
                    cardWithActions("card-workpad-old", deepLookup ? oldWorkpadActions() : regularActions(20)));
        });
        trello.on("/1/cards/card-workpad-deep-failed", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String rawQuery = exchange.getRequestURI().getRawQuery();
            if (rawQuery != null && rawQuery.contains("actions_limit=1000")) {
                respond(exchange, 429, "{}");
                return;
            }
            respond(exchange, cardWithActions("card-workpad-deep-failed", regularActions(20)));
        });
        trello.on("/1/cards/idshort-probe-", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            String path = exchange.getRequestURI().getPath();
            String suffix = path.substring(path.lastIndexOf("idshort-probe-") + "idshort-probe-".length());
            respond(exchange, cardWithIdShort("idshort-probe-" + suffix, idShortToken(suffix)));
        });
        trello.on("/1/cards/card-missing", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 404, "{}");
        });
        trello.on("/1/cards/card-failed", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 500, "{}");
        });
        trello.on("/1/cards/card-malformed", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    "{\"id\":\"card-malformed\",\"idList\":\"lookup-review\",\"idBoard\":\"lookup-board\",\"closed\":false,\"labels\":[]}");
        });
        trello.on("/1/cards/000000000000000000000107/idList", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"000000000000000000000107\"}");
        });
        trello.on("/1/cards/000000000000000000000107", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"000000000000000000000107","name":"Picked up","desc":"","idList":"list-progress","idBoard":"board-1","closed":false,"idShort":8,"shortLink":"pickup","shortUrl":"u","url":"u","labels":[],"actions":[],"dateLastActivity":"2026-02-24T20:10:12.000Z","pos":1}
                    """);
        });
        trello.on(
                "/1/boards/closed-input",
                exchange -> respond(exchange, "{\"id\":\"closed-board\",\"name\":\"Board\",\"closed\":true}"));
        trello.on("/1/cards/write-card/actions/comments", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"comment-1\"}");
        });
        trello.on("/1/cards/write-card/idList", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"write-card\"}");
        });
        trello.on("/1/actions/comment-1/text", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"comment-1\"}");
        });
        trello.on("/1/cards/rate-limited-card/actions/comments", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 429, "{}");
        });
        trello.startEmpty();
    }

    @AfterEach
    void stopServer() {
        trello.close();
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
        // Expected WARN in the build log (issue #354): skipping the malformed Trello card is the
        // production behavior under test; muting shared logger categories would be JVM-global
        // state and is unsafe with parallel test execution.
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

    @MethodSource("idShortScenarios")
    @ParameterizedTest(name = "idShort {0} -> {2}")
    void idShortIsParsedAsWholeNumberOrRejectedAsMalformedPayload(
            String scenario, Integer expectedIdShort, String description) {
        // given
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("lookup-input", Map.of());
        String cardId = "idshort-probe-" + scenario;

        // when
        var result = client.fetchCardStatesByIds(config, List.of(cardId)).get(cardId);

        // then
        if (expectedIdShort != null) {
            assertThat(result).as(description).isInstanceOfSatisfying(CardLookupResult.Found.class, found -> assertThat(
                            found.card().idShort())
                    .isEqualTo(expectedIdShort));
        } else {
            assertThat(result)
                    .as(description)
                    .isInstanceOfSatisfying(CardLookupResult.Failed.class, failed -> assertThat(failed.code())
                            .isEqualTo("trello_unknown_payload"));
        }
    }

    private static Stream<Arguments> idShortScenarios() {
        return Stream.of(
                Arguments.of("whole", 7, "a whole integer idShort parses unchanged"),
                Arguments.of("whole-float", 7, "a whole-valued float normalizes to its integer value"),
                Arguments.of("fractional", null, "a fractional idShort is rejected instead of truncated"),
                Arguments.of("out-of-range", null, "an idShort beyond int range is rejected"),
                Arguments.of("non-numeric", null, "a non-numeric idShort is rejected"));
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
        // Expected WARN in the build log (issue #354): the simulated Trello rate limit makes the
        // client log its operator guidance; muting shared logger categories would be JVM-global
        // state and is unsafe with parallel test execution.
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
    void fetchCardStatesForPromptByIdsIncludesChecklistAttachmentAndReferenceContext() {
        // given
        trello.on("/1/cards/card-context", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"card-context","name":"Context","desc":"Read the related material.","idList":"lookup-review","idBoard":"lookup-board","closed":false,"shortLink":"context","labels":[],"actions":[{"id":"comment-context","date":"2026-02-25T20:11:12.000Z","data":{"text":"No prerequisite in this comment."},"memberCreator":{"username":"reviewer"}}],"checklists":[{"id":"checklist-context","name":"Related","checkItems":[{"id":"item-context","name":"related to [the card](%s)","state":"incomplete"}]}],"attachments":[{"id":"attachment-context","name":"Design card","url":"%s"}]}
                    """
                            .formatted(cardUrl("CTXMARK1"), cardUrl("CTXATT1")));
        });
        trello.on("/1/cards/CTXMARK1", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"mark-card","name":"Markdown reference","desc":"","idList":"lookup-review","idBoard":"lookup-board","closed":false,"shortLink":"CTXMARK1","shortUrl":"%s","url":"%s","labels":[],"actions":[],"badges":{"checkItems":0},"pos":2}
                    """
                            .formatted(cardUrl("CTXMARK1"), cardUrl("CTXMARK1")));
        });
        trello.on("/1/cards/CTXATT1", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"attachment-card","name":"Attachment reference","desc":"","idList":"lookup-review","idBoard":"lookup-board","closed":false,"shortLink":"CTXATT1","shortUrl":"%s","url":"%s","labels":[],"actions":[],"badges":{"checkItems":0},"pos":3}
                    """
                            .formatted(cardUrl("CTXATT1"), cardUrl("CTXATT1")));
        });
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("lookup-input", Map.of("blocker_enforced_states", List.of()));

        // when
        var results = client.fetchCardStatesForPromptByIds(config, List.of("card-context"));

        // then
        assertThat(results.get("card-context"))
                .isInstanceOfSatisfying(CardLookupResult.Found.class, found -> assertThat(found.card())
                        .satisfies(card -> {
                            assertThat(card.checklists()).singleElement().satisfies(checklist -> assertThat(
                                            checklist.name())
                                    .isEqualTo("Related"));
                            assertThat(card.attachments()).singleElement().satisfies(attachment -> assertThat(
                                            attachment.url())
                                    .isEqualTo(cardUrl("CTXATT1")));
                            assertThat(card.trelloReferences())
                                    .extracting(Card.TrelloReference::source)
                                    .containsExactly("checklist:Related", "attachment");
                            assertThat(card.trelloReferences())
                                    .extracting(Card.TrelloReference::lookupId)
                                    .containsExactly("CTXMARK1", "CTXATT1");
                        }));
        assertThat(readRequests)
                .filteredOn(request -> request.startsWith("GET /1/cards/card-context?"))
                .singleElement()
                .satisfies(request -> assertThat(request)
                        .contains("attachments=true")
                        .contains("attachment_fields=name%2Curl")
                        .contains("checklists=all")
                        .contains("checkItem_fields=id%2Cname%2Cstate"));
        assertThat(writeRequests).isEmpty();
    }

    @Test
    void fetchCardStatesForPromptByIdsLooksUpRepeatedPromptReferencesOnceForTheBatch() {
        // given
        trello.on("/1/cards/card-context-a", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"card-context-a","name":"Context A","desc":"See %s","idList":"lookup-review","idBoard":"lookup-board","closed":false,"shortLink":"contexta","labels":[],"actions":[],"badges":{"checkItems":0},"pos":1}
                    """
                            .formatted(cardUrl("CTXSHARED")));
        });
        trello.on("/1/cards/card-context-b", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"card-context-b","name":"Context B","desc":"Also see %s","idList":"lookup-review","idBoard":"lookup-board","closed":false,"shortLink":"contextb","labels":[],"actions":[],"badges":{"checkItems":0},"pos":2}
                    """
                            .formatted(cardUrl("CTXSHARED")));
        });
        trello.on("/1/cards/CTXSHARED", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"shared-card","name":"Shared context","desc":"","idList":"lookup-review","idBoard":"lookup-board","closed":false,"shortLink":"CTXSHARED","shortUrl":"%s","url":"%s","labels":[],"actions":[],"badges":{"checkItems":0},"pos":3}
                    """
                            .formatted(cardUrl("CTXSHARED"), cardUrl("CTXSHARED")));
        });
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("lookup-input", Map.of("blocker_enforced_states", List.of()));

        // when
        var results = client.fetchCardStatesForPromptByIds(config, List.of("card-context-a", "card-context-b"));

        // then
        assertThat(results.values())
                .filteredOn(CardLookupResult.Found.class::isInstance)
                .map(CardLookupResult.Found.class::cast)
                .extracting(found -> found.card().trelloReferences())
                .allSatisfy(references -> assertThat(references)
                        .singleElement()
                        .satisfies(reference -> assertThat(reference.lookupId()).isEqualTo("CTXSHARED")));
        assertThat(readRequests)
                .filteredOn(request -> request.startsWith("GET /1/cards/CTXSHARED?"))
                .hasSize(1);
        assertThat(writeRequests).isEmpty();
    }

    @Test
    void writeOperationsSendExpectedMethodsAndDoNotRetryFailedWrites() {
        // Expected WARN in the build log (issue #354): the simulated Trello rate limit makes the
        // client log its operator guidance; muting shared logger categories would be JVM-global
        // state and is unsafe with parallel test execution.
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
    void fetchCandidateCardsPopulatesBlockersFromPrerequisiteChecklistAndWritesOneWaitingComment() {
        // given
        configureDependencyBoard("Todo");
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("dependency-input", Map.of("blocker_enforced_states", List.of("Todo")))
                .withResolvedBoardId("dependency-board");

        // when
        List<Card> cards = client.fetchCandidateCards(config);

        // then
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.checklists()).singleElement().satisfies(checklist -> {
                assertThat(checklist.name()).isEqualTo("Must finish first");
                assertThat(checklist.items()).singleElement().satisfies(item -> {
                    assertThat(item.text()).isEqualTo(cardUrl("PREREQ01"));
                    assertThat(item.complete()).isFalse();
                });
            });
            assertThat(card.blockedBy()).singleElement().satisfies(blocker -> {
                assertThat(blocker.identifier()).isEqualTo("TRELLO-PREREQ01");
                assertThat(blocker.state()).isEqualTo("Todo");
            });
            assertThat(card.prerequisiteProblems()).isEmpty();
        });
        assertThat(readRequests)
                .filteredOn(request -> request.startsWith("GET /1/cards/PREREQ01?"))
                .hasSize(1);
        assertThat(writeRequests).singleElement().satisfies(request -> assertThat(request)
                .startsWith("POST /1/cards/waiting-card/actions/comments?")
                .contains("Symphony+Prerequisite+Status")
                .contains("Status%3A+waiting+for+prerequisites"));
    }

    @Test
    void fetchCandidateCardsLeavesCurrentPrerequisiteWaitingCommentUnchanged() throws Exception {
        // given
        configureDependencyBoard("Todo");
        configureWaitingCardWithManagedComment(waitingTextForPrerequisite("TRELLO-PREREQ01", "Todo"));

        // when
        List<Card> cards = fetchDependencyCandidates();

        // then
        assertWaitingBlockedWithoutProblems(cards);
        assertThat(writeRequests).isEmpty();
    }

    @Test
    void fetchCandidateCardsDoesNotFetchChecklistsForInactiveOpenCards() {
        // given
        trello.remove("/1/boards/board-1/cards/open");
        trello.on("/1/boards/board-1/cards/open", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    [
                      {"id":"inactive-card","name":"Inactive","desc":"","idList":"list-progress","idBoard":"board-1","closed":false,"shortLink":"inactive","shortUrl":"u","url":"u","labels":[],"badges":{"checkItems":1},"pos":1},
                      {"id":"active-card","name":"Active","desc":"","idList":"list-todo","idBoard":"board-1","closed":false,"shortLink":"active","shortUrl":"u","url":"u","labels":[],"badges":{"checkItems":0},"pos":2}
                    ]
                    """);
        });
        trello.on("/1/cards/inactive-card/checklists", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 500, "{}");
        });
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("input", Map.of("active_states", List.of("Todo")));

        // when
        List<Card> cards = client.fetchCandidateCards(config);

        // then
        assertThat(cards).singleElement().satisfies(card -> assertThat(card.id())
                .isEqualTo("active-card"));
        assertThat(readRequests).noneMatch(request -> request.startsWith("GET /1/cards/inactive-card/checklists"));
    }

    @Test
    void fetchCandidateCardsClearsStaleWaitingMarkerWhenPrerequisiteItemsAreRemoved() {
        // given
        trello.remove("/1/boards/board-1/cards/open");
        trello.on("/1/boards/board-1/cards/open", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    [
                      {"id":"no-prereq-card","name":"Ready","desc":"","idList":"list-todo","idBoard":"board-1","closed":false,"shortLink":"ready","shortUrl":"u","url":"u","labels":[],"badges":{"checkItems":0,"comments":1},"pos":1}
                    ]
                    """);
        });
        trello.on("/1/cards/no-prereq-card", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"no-prereq-card","name":"Ready","desc":"","idList":"list-todo","idBoard":"board-1","closed":false,"shortLink":"ready","labels":[],"actions":[{"id":"waiting-comment","date":"2026-02-25T20:11:12.000Z","data":{"text":"%s\\n\\nOld waiting text"},"memberCreator":{"username":"codex"}}],"badges":{"checkItems":0,"comments":1},"pos":1}
                    """
                            .formatted(TrelloClient.WAITING_COMMENT_MARKER));
        });
        trello.on("/1/actions/waiting-comment/text", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"waiting-comment\"}");
        });
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config =
                config("input", Map.of("active_states", List.of("Todo"), "blocker_enforced_states", List.of("Todo")));

        // when
        List<Card> cards = client.fetchCandidateCards(config);

        // then
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.blockedBy()).isEmpty();
            assertThat(card.prerequisiteProblems()).isEmpty();
        });
        assertThat(readRequests).anySatisfy(request -> assertThat(request)
                .startsWith("GET /1/cards/no-prereq-card?")
                .contains("actions_limit=1000"));
        assertThat(writeRequests).singleElement().satisfies(request -> assertThat(request)
                .startsWith("PUT /1/actions/waiting-comment/text?"));
        assertThat(queryValue(writeRequests.getFirst(), "value"))
                .contains("Status: prerequisites resolved")
                .doesNotStartWith(TrelloClient.WAITING_COMMENT_MARKER)
                .doesNotContain(TrelloClient.WAITING_COMMENT_MARKER);
        assertThat(writeRequests)
                .noneMatch(request -> request.startsWith("POST /1/cards/no-prereq-card/actions/comments"));
    }

    @Test
    void fetchCandidateCardsUpdatesFirstManagedPrerequisiteStatusCommentWhenSeveralExist() {
        // given
        trello.remove("/1/boards/board-1/cards/open");
        trello.on("/1/boards/board-1/cards/open", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    [
                      {"id":"no-prereq-card","name":"Ready","desc":"","idList":"list-todo","idBoard":"board-1","closed":false,"shortLink":"ready","shortUrl":"u","url":"u","labels":[],"badges":{"checkItems":0,"comments":2},"pos":1}
                    ]
                    """);
        });
        trello.on("/1/cards/no-prereq-card", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"no-prereq-card","name":"Ready","desc":"","idList":"list-todo","idBoard":"board-1","closed":false,"shortLink":"ready","labels":[],"actions":[
                      {"id":"waiting-comment-newest","date":"2026-02-25T20:12:12.000Z","data":{"text":"%s\\n\\nNewest waiting text"},"memberCreator":{"username":"codex"}},
                      {"id":"waiting-comment-older","date":"2026-02-25T20:11:12.000Z","data":{"text":"%s\\n\\nOlder waiting text"},"memberCreator":{"username":"codex"}}
                    ],"badges":{"checkItems":0,"comments":2},"pos":1}
                    """
                            .formatted(
                                    TrelloClient.WAITING_COMMENT_MARKER,
                                    TrelloClient.PREREQUISITE_STATUS_COMMENT_MARKER));
        });
        trello.on("/1/actions/waiting-comment-newest/text", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"waiting-comment-newest\"}");
        });
        trello.on("/1/actions/waiting-comment-older/text", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"waiting-comment-older\"}");
        });
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config =
                config("input", Map.of("active_states", List.of("Todo"), "blocker_enforced_states", List.of("Todo")));

        // when
        List<Card> cards = client.fetchCandidateCards(config);

        // then
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.blockedBy()).isEmpty();
            assertThat(card.prerequisiteProblems()).isEmpty();
        });
        assertThat(writeRequests).singleElement().satisfies(request -> assertThat(request)
                .startsWith("PUT /1/actions/waiting-comment-newest/text?"));
        assertThat(writeRequests).noneMatch(request -> request.startsWith("PUT /1/actions/waiting-comment-older/text"));
    }

    @Test
    void resolvedPrerequisiteStatusCommentIsUpdatedInPlaceWhenCardBecomesBlockedAgain() throws Exception {
        // given
        configureDependencyBoard("Todo");
        String resolvedStatus = String.join(
                "\n",
                TrelloClient.PREREQUISITE_STATUS_COMMENT_MARKER,
                "",
                "Status: prerequisites resolved.",
                "",
                "Symphony may start this Trello card when other dispatch rules allow.");
        configureWaitingCardWithManagedComment(resolvedStatus);
        trello.on("/1/actions/waiting-comment/text", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"waiting-comment\"}");
        });

        // when
        List<Card> cards = fetchDependencyCandidates();

        // then
        assertWaitingBlockedWithoutProblems(cards);
        assertThat(writeRequests).singleElement().satisfies(request -> assertThat(request)
                .startsWith("PUT /1/actions/waiting-comment/text?"));
        assertThat(queryValue(writeRequests.getFirst(), "value"))
                .startsWith(TrelloClient.PREREQUISITE_STATUS_COMMENT_MARKER)
                .contains("Status: waiting for prerequisites")
                .contains("TRELLO-PREREQ01");
        assertThat(writeRequests).noneMatch(request -> request.startsWith("POST /1/cards/waiting-card"));
    }

    @Test
    void duplicatePrerequisiteReferencesAreDeduplicatedForLookupAndBlockerState() {
        // given
        configureDependencyBoard("Todo");
        trello.remove("/1/cards/waiting-card/checklists");
        trello.on("/1/cards/waiting-card/checklists", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    [
                      {"id":"checklist-1","name":"Must finish first","checkItems":[
                        {"id":"item-1","name":"%s","state":"incomplete"},
                        {"id":"item-2","name":"PREREQ01","state":"incomplete"}
                      ]}
                    ]
                    """
                            .formatted(cardUrl("PREREQ01")));
        });
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("dependency-input", Map.of("blocker_enforced_states", List.of("Todo")))
                .withResolvedBoardId("dependency-board");

        // when
        List<Card> cards = client.fetchCandidateCards(config);

        // then
        assertThat(cards).singleElement().satisfies(card -> assertThat(card.blockedBy())
                .singleElement()
                .satisfies(blocker -> assertThat(blocker.identifier()).isEqualTo("TRELLO-PREREQ01")));
        assertThat(readRequests)
                .filteredOn(request -> request.startsWith("GET /1/cards/PREREQ01?"))
                .hasSize(1);
    }

    @Test
    void missingPrerequisiteUnchecksCompletedItemAndShowsWaitingGuidance() {
        // given
        configureDependencyBoard("Todo", "complete");
        trello.remove("/1/cards/PREREQ01");
        trello.on("/1/cards/PREREQ01", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 404, "{}");
        });
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("dependency-input", Map.of("blocker_enforced_states", List.of("Todo")))
                .withResolvedBoardId("dependency-board");

        // when
        List<Card> cards = client.fetchCandidateCards(config);

        // then
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.blockedBy()).singleElement().satisfies(blocker -> assertThat(blocker.identifier())
                    .isEqualTo("Missing prerequisite"));
            assertThat(card.prerequisiteProblems()).singleElement().satisfies(problem -> assertThat(problem.code())
                    .isEqualTo("trello_prerequisite_missing"));
        });
        assertThat(writeRequests)
                .anySatisfy(request ->
                        assertThat(request).isEqualTo("PUT /1/cards/waiting-card/checkItem/item-1?state=incomplete"))
                .anySatisfy(request -> assertThat(request)
                        .startsWith("POST /1/cards/waiting-card/actions/comments?")
                        .contains("Missing+prerequisite"));
    }

    @Test
    void selfPrerequisiteBlocksWithVisibleGuidance() {
        // given
        configureDependencyBoard("Todo");
        trello.remove("/1/cards/waiting-card/checklists");
        trello.on("/1/cards/waiting-card/checklists", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    [
                      {"id":"checklist-1","name":"Must finish first","checkItems":[{"id":"item-1","name":"%s","state":"incomplete"}]}
                    ]
                    """
                            .formatted(cardUrl("WAITING1")));
        });
        trello.on("/1/cards/WAITING1", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"waiting-card","name":"Waiting","desc":"","idList":"dep-todo","idBoard":"dependency-board","closed":false,"shortLink":"WAITING1","shortUrl":"%s","url":"%s","labels":[],"actions":[],"badges":{"checkItems":1},"pos":1}
                    """
                            .formatted(cardUrl("WAITING1"), cardUrl("WAITING1")));
        });
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("dependency-input", Map.of("blocker_enforced_states", List.of("Todo")))
                .withResolvedBoardId("dependency-board");

        // when
        List<Card> cards = client.fetchCandidateCards(config);

        // then
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.blockedBy()).singleElement().satisfies(blocker -> assertThat(blocker.identifier())
                    .isEqualTo("Self prerequisite"));
            assertThat(card.prerequisiteProblems()).singleElement().satisfies(problem -> assertThat(problem.code())
                    .isEqualTo("trello_prerequisite_self_reference"));
        });
        assertThat(writeRequests).singleElement().satisfies(request -> assertThat(request)
                .startsWith("POST /1/cards/waiting-card/actions/comments?")
                .contains("cannot+use+itself+as+a+prerequisite"));
    }

    @Test
    void terminalPrerequisiteSyncsChecklistItemCompleteWithoutBlockingCandidate() {
        // given
        configureDependencyBoard("Done");
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("dependency-input", Map.of("blocker_enforced_states", List.of("Todo")))
                .withResolvedBoardId("dependency-board");

        // when
        List<Card> cards = client.fetchCandidateCards(config);

        // then
        assertDonePrerequisiteWithoutProblems(cards);
        assertThat(writeRequests).singleElement().satisfies(request -> assertThat(request)
                .isEqualTo("PUT /1/cards/waiting-card/checkItem/item-1?state=complete"));
    }

    @Test
    void ambiguousPrerequisiteChecklistBlocksCandidateWithVisibleGuidance() {
        // given
        configureAmbiguousPrerequisiteBoard();
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("ambiguous-input", Map.of("blocker_enforced_states", List.of("Todo")))
                .withResolvedBoardId("ambiguous-board");

        // when
        List<Card> cards = client.fetchCandidateCards(config);

        // then
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.blockedBy()).singleElement().satisfies(blocker -> assertThat(blocker.identifier())
                    .isEqualTo("Ambiguous prerequisite checklist"));
            assertThat(card.prerequisiteProblems()).singleElement().satisfies(problem -> assertThat(problem.code())
                    .isEqualTo(TrelloChecklistClassifier.AMBIGUOUS_PREREQUISITE_CODE));
        });
        assertThat(readRequests).noneMatch(request -> request.startsWith("GET /1/cards/AMBIG001?"));
        assertThat(writeRequests).singleElement().satisfies(request -> assertThat(request)
                .startsWith("POST /1/cards/ambiguous-card/actions/comments?")
                .contains("Checklist+contains+a+Trello+card+reference")
                .contains("Markdown+links"));
    }

    @Test
    void mixedExactAndProseChecklistBlocksWithoutResolvingOrSyncingExactItem() {
        // given
        configureMixedPrerequisiteBoard(List.of(
                """
                {"id":"item-exact","name":"%s","state":"incomplete"}
                """
                        .formatted(cardUrl("MIXED01")),
                """
                {"id":"item-note","name":"Update docs","state":"incomplete"}
                """));
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("mixed-input", Map.of("blocker_enforced_states", List.of("Todo")))
                .withResolvedBoardId("mixed-board");

        // when
        List<Card> cards = client.fetchCandidateCards(config);

        // then
        assertMixedChecklistBlocksWithoutSchedulerLookupOrSync(cards, "MIXED01");
    }

    @Test
    void mixedExactAndMarkdownChecklistBlocksWithoutResolvingOrSyncingExactItem() {
        // given
        configureMixedPrerequisiteBoard(List.of(
                """
                {"id":"item-exact","name":"%s","state":"incomplete"}
                """
                        .formatted(cardUrl("MIXED02")),
                """
                {"id":"item-markdown","name":"related to [the card](%s)","state":"incomplete"}
                """
                        .formatted(cardUrl("CTXMARK2"))));
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("mixed-input", Map.of("blocker_enforced_states", List.of("Todo")))
                .withResolvedBoardId("mixed-board");

        // when
        List<Card> cards = client.fetchCandidateCards(config);

        // then
        assertMixedChecklistBlocksWithoutSchedulerLookupOrSync(cards, "MIXED02");
        assertThat(readRequests).noneMatch(request -> request.startsWith("GET /1/cards/CTXMARK2?"));
    }

    @Test
    void prerequisiteChecklistSyncFailureKeepsCardVisibleAsWaiting() {
        // given
        configureDependencyBoard("Done");
        trello.remove("/1/cards/waiting-card/checkItem/item-1");
        trello.on("/1/cards/waiting-card/checkItem/item-1", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 500, "{}");
        });
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("dependency-input", Map.of("blocker_enforced_states", List.of("Todo")))
                .withResolvedBoardId("dependency-board");

        // when
        List<Card> cards = client.fetchCandidateCards(config);

        // then
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.blockedBy()).singleElement().satisfies(blocker -> assertThat(blocker.state())
                    .isEqualTo("Done"));
            assertThat(card.prerequisiteProblems()).singleElement().satisfies(problem -> assertThat(problem.code())
                    .isEqualTo("trello_prerequisite_checklist_sync_failed"));
        });
        assertThat(writeRequests)
                .anySatisfy(request ->
                        assertThat(request).isEqualTo("PUT /1/cards/waiting-card/checkItem/item-1?state=complete"))
                .anySatisfy(request -> assertThat(request)
                        .startsWith("POST /1/cards/waiting-card/actions/comments?")
                        .contains("Could+not+update+a+prerequisite+checklist+item"));
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
        tracker.put("endpoint", trello.endpoint());
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

    private void configureDependencyBoard(String prerequisiteState) {
        configureDependencyBoard(prerequisiteState, "incomplete");
    }

    private void configureDependencyBoard(String prerequisiteState, String checkItemState) {
        String prerequisiteList = "Done".equals(prerequisiteState) ? "dep-done" : "dep-todo";
        trello.on(
                "/1/boards/dependency-input",
                exchange -> respond(exchange, boardJson("dependency-board", "Dependency board", false)));
        trello.on(
                "/1/boards/dependency-board/lists",
                exchange -> respond(
                        exchange, listsJson(trelloList("dep-todo", "Todo", 1), trelloList("dep-done", "Done", 2))));
        trello.on("/1/boards/dependency-board/cards/open", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    [
                      {"id":"waiting-card","name":"Waiting","desc":"","idList":"dep-todo","idBoard":"dependency-board","closed":false,"shortLink":"WAITING1","shortUrl":"%s","url":"%s","labels":[],"badges":{"checkItems":1},"pos":1}
                    ]
                    """
                            .formatted(cardUrl("WAITING1"), cardUrl("WAITING1")));
        });
        trello.on("/1/cards/waiting-card/checklists", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    [
                      {"id":"checklist-1","name":"Must finish first","checkItems":[{"id":"item-1","name":"%s","state":"%s"}]}
                    ]
                    """
                            .formatted(cardUrl("PREREQ01"), checkItemState));
        });
        trello.on("/1/cards/PREREQ01", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"prereq-card","name":"Prerequisite","desc":"","idList":"%s","idBoard":"dependency-board","closed":false,"shortLink":"PREREQ01","shortUrl":"%s","url":"%s","labels":[],"actions":[],"badges":{"checkItems":0},"pos":2}
                    """
                            .formatted(prerequisiteList, cardUrl("PREREQ01"), cardUrl("PREREQ01")));
        });
        trello.on("/1/cards/waiting-card", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"waiting-card","name":"Waiting","desc":"","idList":"dep-todo","idBoard":"dependency-board","closed":false,"shortLink":"WAITING1","shortUrl":"%s","url":"%s","labels":[],"actions":[],"badges":{"checkItems":1},"pos":1}
                    """
                            .formatted(cardUrl("WAITING1"), cardUrl("WAITING1")));
        });
        trello.on("/1/cards/waiting-card/actions/comments", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"waiting-comment\"}");
        });
        trello.on("/1/cards/waiting-card/checkItem/item-1", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"item-1\"}");
        });
    }

    private void configureAmbiguousPrerequisiteBoard() {
        trello.on(
                "/1/boards/ambiguous-input",
                exchange -> respond(exchange, boardJson("ambiguous-board", "Ambiguous board", false)));
        trello.on(
                "/1/boards/ambiguous-board/lists",
                exchange -> respond(
                        exchange,
                        listsJson(trelloList("ambiguous-todo", "Todo", 1), trelloList("ambiguous-done", "Done", 2))));
        trello.on("/1/boards/ambiguous-board/cards/open", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    [
                      {"id":"ambiguous-card","name":"Ambiguous","desc":"","idList":"ambiguous-todo","idBoard":"ambiguous-board","closed":false,"shortLink":"AMBIGWAIT","shortUrl":"%s","url":"%s","labels":[],"badges":{"checkItems":1},"pos":1}
                    ]
                    """
                            .formatted(cardUrl("AMBIGWAIT"), cardUrl("AMBIGWAIT")));
        });
        trello.on("/1/cards/ambiguous-card/checklists", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    [
                      {"id":"ambiguous-checklist","name":"Must finish first","checkItems":[{"id":"ambiguous-item","name":"Wait for %s","state":"incomplete"}]}
                    ]
                    """
                            .formatted(cardUrl("AMBIG001")));
        });
        trello.on("/1/cards/ambiguous-card", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"ambiguous-card","name":"Ambiguous","desc":"","idList":"ambiguous-todo","idBoard":"ambiguous-board","closed":false,"shortLink":"AMBIGWAIT","shortUrl":"%s","url":"%s","labels":[],"actions":[],"badges":{"checkItems":1},"pos":1}
                    """
                            .formatted(cardUrl("AMBIGWAIT"), cardUrl("AMBIGWAIT")));
        });
        trello.on("/1/cards/ambiguous-card/actions/comments", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"waiting-comment\"}");
        });
    }

    private void configureMixedPrerequisiteBoard(List<String> checkItems) {
        trello.on(
                "/1/boards/mixed-input", exchange -> respond(exchange, boardJson("mixed-board", "Mixed board", false)));
        trello.on(
                "/1/boards/mixed-board/lists",
                exchange -> respond(
                        exchange, listsJson(trelloList("mixed-todo", "Todo", 1), trelloList("mixed-done", "Done", 2))));
        trello.on("/1/boards/mixed-board/cards/open", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    [
                      {"id":"mixed-card","name":"Mixed","desc":"","idList":"mixed-todo","idBoard":"mixed-board","closed":false,"shortLink":"MIXWAIT","shortUrl":"%s","url":"%s","labels":[],"badges":{"checkItems":2},"pos":1}
                    ]
                    """
                            .formatted(cardUrl("MIXWAIT"), cardUrl("MIXWAIT")));
        });
        trello.on("/1/cards/mixed-card/checklists", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    [
                      {"id":"mixed-checklist","name":"Must finish first","checkItems":[%s]}
                    ]
                    """
                            .formatted(String.join(",", checkItems)));
        });
        trello.on("/1/cards/MIXED01", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 500, "{}");
        });
        trello.on("/1/cards/MIXED02", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 500, "{}");
        });
        trello.on("/1/cards/CTXMARK2", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 500, "{}");
        });
        trello.on("/1/cards/mixed-card", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"mixed-card","name":"Mixed","desc":"","idList":"mixed-todo","idBoard":"mixed-board","closed":false,"shortLink":"MIXWAIT","shortUrl":"%s","url":"%s","labels":[],"actions":[],"badges":{"checkItems":2},"pos":1}
                    """
                            .formatted(cardUrl("MIXWAIT"), cardUrl("MIXWAIT")));
        });
        trello.on("/1/cards/mixed-card/actions/comments", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, "{\"id\":\"waiting-comment\"}");
        });
        trello.on("/1/cards/mixed-card/checkItem/item-exact", exchange -> {
            writeRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 500, "{}");
        });
    }

    private static void assertDonePrerequisiteWithoutProblems(List<Card> cards) {
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.blockedBy()).singleElement().satisfies(blocker -> assertThat(blocker.state())
                    .isEqualTo("Done"));
            assertThat(card.prerequisiteProblems()).isEmpty();
        });
    }

    private List<Card> fetchDependencyCandidates() {
        TrelloClient client = new TrelloClient(new ObjectMapper());
        var config = config("dependency-input", Map.of("blocker_enforced_states", List.of("Todo")))
                .withResolvedBoardId("dependency-board");
        return client.fetchCandidateCards(config);
    }

    private void configureWaitingCardWithManagedComment(String text) throws Exception {
        String textJson = new ObjectMapper().writeValueAsString(text);
        trello.remove("/1/cards/waiting-card");
        trello.on("/1/cards/waiting-card", exchange -> {
            readRequests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(
                    exchange,
                    """
                    {"id":"waiting-card","name":"Waiting","desc":"","idList":"dep-todo","idBoard":"dependency-board","closed":false,"shortLink":"WAITING1","shortUrl":"%s","url":"%s","labels":[],"actions":[{"id":"waiting-comment","date":"2026-02-25T20:11:12.000Z","data":{"text":%s},"memberCreator":{"username":"codex"}}],"badges":{"checkItems":1},"pos":1}
                    """
                            .formatted(cardUrl("WAITING1"), cardUrl("WAITING1"), textJson));
        });
    }

    private static void assertWaitingBlockedWithoutProblems(List<Card> cards) {
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.blockedBy()).singleElement().satisfies(blocker -> assertThat(blocker.identifier())
                    .isEqualTo("TRELLO-PREREQ01"));
            assertThat(card.prerequisiteProblems()).isEmpty();
        });
    }

    private void assertMixedChecklistBlocksWithoutSchedulerLookupOrSync(List<Card> cards, String exactLookupId) {
        assertThat(cards).singleElement().satisfies(card -> {
            assertThat(card.blockedBy()).singleElement().satisfies(blocker -> assertThat(blocker.identifier())
                    .isEqualTo("Ambiguous prerequisite checklist"));
            assertThat(card.prerequisiteProblems()).singleElement().satisfies(problem -> assertThat(problem.code())
                    .isEqualTo(TrelloChecklistClassifier.MIXED_PREREQUISITE_CODE));
        });
        assertThat(readRequests).noneMatch(request -> request.startsWith("GET /1/cards/" + exactLookupId + "?"));
        assertThat(writeRequests).singleElement().satisfies(request -> assertThat(request)
                .startsWith("POST /1/cards/mixed-card/actions/comments?")
                .contains("Checklist+mixes+prerequisite+references"));
        assertThat(writeRequests).noneMatch(request -> request.startsWith("PUT /1/cards/mixed-card/checkItem/"));
    }

    private static String waitingTextForPrerequisite(String identifier, String state) {
        return String.join(
                "\n",
                TrelloClient.PREREQUISITE_STATUS_COMMENT_MARKER,
                "",
                "Status: waiting for prerequisites.",
                "",
                "Symphony has not started this Trello card because prerequisite checklists are not resolved.",
                "",
                "Waiting for:",
                "- " + identifier + " (" + state + ")",
                "",
                "Fix by making prerequisite checklist items exactly one bare Trello card reference each, moving notes to a separate checklist, or writing non-prerequisite Trello references as Markdown links.");
    }

    private static String cardUrl(String shortLink) {
        return TRELLO_CARD_URL_PREFIX + shortLink;
    }

    private static String queryValue(String request, String name) {
        int queryStart = request.indexOf('?');
        assertThat(queryStart).as("request has query").isGreaterThanOrEqualTo(0);
        for (String part : Splitter.on('&').split(request.substring(queryStart + 1))) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            if (key.equals(name)) {
                String value = separator < 0 ? "" : part.substring(separator + 1);
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Missing query parameter: " + name);
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

    private static String cardWithIdShort(String cardId, String idShortToken) {
        return """
                {"id":"%s","name":"Found","idList":"lookup-review","idBoard":"lookup-board","closed":false,"shortLink":"found","idShort":%s,"labels":[],"actions":[]}
                """
                .formatted(cardId, idShortToken);
    }

    private static String idShortToken(String scenario) {
        // Raw JSON token for each idShort scenario. Strings are quoted so non-numeric input reaches the
        // client as a JSON string, exactly as a malformed Trello payload would deliver it.
        return switch (scenario) {
            case "whole" -> "7";
            case "whole-float" -> "7.0";
            case "fractional" -> "7.5";
            case "out-of-range" -> "9999999999";
            case "non-numeric" -> "\"abc\"";
            default -> throw new IllegalArgumentException("Unknown idShort scenario: " + scenario);
        };
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
