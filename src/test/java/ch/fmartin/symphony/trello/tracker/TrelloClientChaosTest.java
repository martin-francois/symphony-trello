package ch.fmartin.symphony.trello.tracker;

import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.boardJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.listsJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.respond;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.trelloList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.fmartin.symphony.trello.config.ConfigDefaults;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class TrelloClientChaosTest {
    private static final String CARD_ID = "000000000000000000000101";
    private static final String CHECKLIST_BODY =
            """
            [
              {"id":"checklist-1","name":"Prerequisites","checkItems":[{"id":"item-1","name":"https://trello.com/c/SYNTH103","state":"incomplete"}]}
            ]
            """;
    private static final String OPEN_CARDS_BODY =
            """
            [
              {"id":"%s","name":"A","desc":"","idList":"list-todo","idBoard":"board-1","closed":false,"shortLink":"abc","shortUrl":"https://trello.com/c/abc","url":"https://trello.com/c/abc/card","labels":[],"badges":{"checkItems":1},"pos":1}
            ]
            """
                    .formatted(CARD_ID);

    private final TrelloClient client = new TrelloClient(new ObjectMapper());
    private final List<String> writes = new ArrayList<>();

    private FakeTrelloServer trello;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        trello = new FakeTrelloServer();
        trello.startEmpty();
    }

    @AfterEach
    void stopServer() {
        trello.stop();
    }

    @MethodSource("readFaults")
    @ParameterizedTest(name = "{0}")
    void readFaultsFailWithoutTrelloWrites(String scenario, FaultPoint faultPoint) throws IOException {
        // given
        configureCandidateRoutes(faultPoint);

        // when
        TrelloException failure = catchThrowableOfType(
                () -> client.fetchCandidateCards(config(Map.of("blocker_enforced_states", List.of("Todo")))),
                TrelloException.class);

        // then
        assertThat(failure).as(scenario).isNotNull();
        assertThat(failure.code()).as(scenario).isNotBlank();
        assertThat(writes).as(scenario).isEmpty();
    }

    private static Stream<Arguments> readFaults() {
        return Stream.of(
                Arguments.of("board lookup returns HTTP 500", FaultPoint.BOARD_STATUS),
                Arguments.of("board list lookup returns malformed JSON", FaultPoint.LISTS_MALFORMED_JSON),
                Arguments.of("candidate card lookup returns an object instead of a list", FaultPoint.CARDS_WRONG_SHAPE),
                Arguments.of("checklist lookup returns HTTP 429 without retries", FaultPoint.CHECKLIST_RATE_LIMITED));
    }

    @MethodSource("attachmentPayloadFaults")
    @ParameterizedTest(name = "{0}")
    void malformedAttachmentResponsesReportUnknownPayloadWithoutRetry(String scenario, String responseBody) {
        // given
        trello.on("/1/cards/write-card/attachments", exchange -> {
            writes.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, responseBody);
        });

        // when
        TrelloException failure = catchThrowableOfType(
                () -> client.addUrlAttachment(config(Map.of()), "write-card", "https://example.invalid/pr/1", "PR"),
                TrelloException.class);

        // then
        assertThat(failure).as(scenario).isNotNull();
        assertThat(failure.code()).as(scenario).isEqualTo("trello_unknown_payload");
        assertThat(writes)
                .as(scenario)
                .containsExactly(
                        "POST /1/cards/write-card/attachments?url=https%3A%2F%2Fexample.invalid%2Fpr%2F1&name=PR");
    }

    private static Stream<Arguments> attachmentPayloadFaults() {
        return Stream.of(
                Arguments.of("empty response body", ""),
                Arguments.of("array response without an attachment object", "[]"),
                Arguments.of("object response without id", "{\"name\":\"Pull request\"}"),
                Arguments.of("non-object response", "\"attachment-1\""));
    }

    @Test
    void readRateLimitRetriesButWriteRateLimitDoesNotRetry() throws IOException {
        // given
        CountingResponse board = new CountingResponse(429, boardJson("board-1", "Board", false));
        trello.on("/1/boards/input", exchange -> board.respond(exchange));
        trello.on(
                "/1/boards/board-1/lists",
                exchange -> respond(exchange, listsJson(trelloList("list-todo", "Todo", 1))));
        trello.on("/1/boards/board-1/cards/open", exchange -> respond(exchange, "[]"));
        trello.on("/1/cards/write-card/actions/comments", exchange -> {
            writes.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            respond(exchange, 429, "{}");
        });
        EffectiveConfig config = config(Map.of("max_api_retries", 1, "api_retry_base_delay_ms", 1));

        // when
        List<?> cards = client.fetchCandidateCards(config);
        TrelloException writeFailure =
                catchThrowableOfType(() -> client.addComment(config, "write-card", "hello"), TrelloException.class);

        // then
        assertThat(cards).isEmpty();
        assertThat(board.requests()).isEqualTo(2);
        assertThat(writeFailure.code()).isEqualTo("trello_api_rate_limited");
        assertThat(writes).containsExactly("POST /1/cards/write-card/actions/comments?text=hello");
    }

    private void configureCandidateRoutes(FaultPoint faultPoint) throws IOException {
        trello.on("/1/boards/input", exchange -> {
            if (faultPoint == FaultPoint.BOARD_STATUS) {
                respond(exchange, 500, "{}");
                return;
            }
            respond(exchange, boardJson("board-1", "Board", false));
        });
        trello.on("/1/boards/board-1/lists", exchange -> {
            if (faultPoint == FaultPoint.LISTS_MALFORMED_JSON) {
                respond(exchange, "{");
                return;
            }
            respond(exchange, listsJson(trelloList("list-todo", "Todo", 1)));
        });
        trello.on("/1/boards/board-1/cards/open", exchange -> {
            if (faultPoint == FaultPoint.CARDS_WRONG_SHAPE) {
                respond(exchange, "{}");
                return;
            }
            respond(exchange, OPEN_CARDS_BODY);
        });
        trello.on("/1/cards/" + CARD_ID + "/checklists", exchange -> {
            if (faultPoint == FaultPoint.CHECKLIST_RATE_LIMITED) {
                respond(exchange, 429, "{}");
                return;
            }
            respond(exchange, CHECKLIST_BODY);
        });
    }

    private EffectiveConfig config(Map<String, Object> trackerOverrides) {
        Map<String, Object> tracker = new LinkedHashMap<>();
        tracker.put("kind", "trello");
        tracker.put("endpoint", trello.endpoint());
        tracker.put("api_key", "key");
        tracker.put("api_token", "token");
        tracker.put("board_id", "input");
        tracker.put("active_states", List.of("Todo"));
        tracker.put("terminal_states", List.of("Done", "Archived", "ArchivedList", "ArchivedBoard", "Deleted"));
        tracker.put("max_api_retries", 0);
        tracker.put("api_retry_base_delay_ms", ConfigDefaults.DEFAULT_TRACKER_API_RETRY_BASE_DELAY_MS);
        tracker.putAll(trackerOverrides);
        return new ConfigResolver()
                .resolve(new WorkflowDefinition(tempDir.resolve("WORKFLOW.md"), Map.of("tracker", tracker), ""))
                .withResolvedBoardId("board-1");
    }

    private enum FaultPoint {
        BOARD_STATUS,
        LISTS_MALFORMED_JSON,
        CARDS_WRONG_SHAPE,
        CHECKLIST_RATE_LIMITED
    }

    private static final class CountingResponse {
        private final int firstStatus;
        private final String successBody;
        private int requests;

        private CountingResponse(int firstStatus, String successBody) {
            this.firstStatus = firstStatus;
            this.successBody = successBody;
        }

        private void respond(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
            requests++;
            if (requests == 1) {
                FakeTrelloServer.respond(exchange, firstStatus, "{}");
                return;
            }
            FakeTrelloServer.respond(exchange, successBody);
        }

        private int requests() {
            return requests;
        }
    }
}
