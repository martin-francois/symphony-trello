package ch.fmartin.symphony.trello.boardsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import ch.fmartin.symphony.trello.testsupport.FakeTrelloBoard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class BoardSessionMcpServerTest {
    private static final String SERVER_INSTRUCTIONS = "Selected board: Symphony Work Queue.";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private FakeTrelloBoard trello;
    private BoardSessionMcpServer server;

    @BeforeEach
    void start() throws Exception {
        trello = new FakeTrelloBoard(BoardSessionFixtures.BOARD_NAME)
                .withList("list-ready", "Ready for Codex")
                .withList("list-done", "Done")
                .withCard("card-ready", "Ready123", "Add snapshot tests", "list-ready")
                .start();
        server = BoardSessionMcpServer.start(
                json,
                BoardSessionFixtures.tools(
                        trello, BoardSessionFixtures.allowAllPolicy(), BoardSessionFixtures.defaultRoles()),
                SERVER_INSTRUCTIONS,
                "test");
    }

    @AfterEach
    void stop() {
        server.close();
        trello.close();
    }

    @CsvSource({"2025-06-18,2025-06-18", "2024-11-05,2024-11-05", "1999-01-01,2025-11-25"})
    @ParameterizedTest
    void initializeNegotiatesTheProtocolAndReturnsBoardInstructions(String requested, String expected)
            throws Exception {
        // given

        // when
        JsonNode response = post(
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"%s","capabilities":{},\
                "clientInfo":{"name":"codex","version":"1"}}}"""
                        .formatted(requested));

        // then
        assertThat(response.path("result").path("protocolVersion").asText()).isEqualTo(expected);
        assertThat(response.path("result").path("instructions").asText()).isEqualTo(SERVER_INSTRUCTIONS);
        assertThat(response.path("result").path("capabilities").has("tools"))
                .as("the server must advertise tools")
                .isTrue();
    }

    @Test
    void toolsListMarksReadOnlyAndDestructiveTools() throws Exception {
        // given

        // when
        JsonNode response = post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");

        // then
        assertThat(response.path("result").path("tools"))
                .extracting(
                        tool -> tool.path("name").asText(),
                        tool -> tool.path("annotations").path("readOnlyHint").asBoolean(),
                        tool -> tool.path("annotations").path("destructiveHint").asBoolean())
                .contains(
                        tuple(BoardSessionTools.LIST_CARDS, true, false),
                        tuple(BoardSessionTools.CREATE_CARD, false, false),
                        tuple(BoardSessionTools.ARCHIVE_CARD, false, true));
    }

    @Test
    void toolsCallReturnsTheToolResultAsText() throws Exception {
        // given

        // when
        JsonNode success = post(
                """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"trello_list_cards","arguments":{}}}""");
        JsonNode failure = post(
                """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"trello_get_card",\
                "arguments":{"card":"../x"}}}""");

        // then
        assertThat(success.path("result").path("isError").asBoolean())
                .as("a successful tool call must not be marked as an error")
                .isFalse();
        assertThat(json.readTree(success.path("result")
                                .path("content")
                                .path(0)
                                .path("text")
                                .asText())
                        .path("cards"))
                .extracting(card -> card.path("title").asText())
                .containsExactly("Add snapshot tests");
        assertThat(failure.path("result").path("isError").asBoolean())
                .as("tool failures are tool results, not protocol errors")
                .isTrue();
    }

    @Test
    void batchesAnswerEveryRequestAndIgnoreNotifications() throws Exception {
        // given

        // when
        JsonNode response = post(
                """
                [{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","id":5,"method":"ping"},\
                {"jsonrpc":"2.0","id":6,"method":"resources/list"}]""");

        // then
        assertThat(response).extracting(node -> node.path("id").asInt()).containsExactly(5, 6);
        assertThat(response.path(1).path("error").path("code").asInt()).isEqualTo(-32_601);
    }

    @Test
    void notificationsAreAcceptedWithoutBody() throws Exception {
        // given

        // when
        HttpResponse<String> response =
                send(authorized().POST(body("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")));

        // then
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEmpty();
    }

    @Test
    void rejectsRequestsWithoutTheSessionBearerToken() throws Exception {
        // given

        // when
        HttpResponse<String> missing = send(HttpRequest.newBuilder(server.endpoint())
                .POST(body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")));
        HttpResponse<String> wrong = send(HttpRequest.newBuilder(server.endpoint())
                .header("Authorization", "Bearer " + server.bearerToken() + "x")
                .POST(body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":"
                        + "\"trello_list_cards\",\"arguments\":{}}}")));

        // then
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(trello.requests())
                .as("an unauthenticated caller must not reach Trello")
                .isEmpty();
    }

    @Test
    void rejectsBrowserRequestsThatCarryAnOrigin() throws Exception {
        // given

        // when
        HttpResponse<String> response = send(authorized()
                .header("Origin", "https://example.invalid")
                .POST(body("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")));

        // then
        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void offersNoEventStream() throws Exception {
        // given

        // when
        HttpResponse<String> response = send(authorized().GET());

        // then
        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Allow")).hasValue("POST");
    }

    @Test
    void reportsInvalidJsonAsAParseError() throws Exception {
        // given

        // when
        HttpResponse<String> response = send(authorized().POST(body("{not json")));

        // then
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).path("error").path("code").asInt())
                .isEqualTo(-32_700);
    }

    @Test
    void rejectsOversizedRequests() throws Exception {
        // given

        // when
        HttpResponse<String> response =
                send(authorized().POST(body("x".repeat(BoardSessionMcpServer.MAX_REQUEST_BYTES + 1))));

        // then
        assertThat(response.statusCode()).isEqualTo(413);
    }

    @Test
    void closeReleasesTheLoopbackPort() {
        // given
        URI endpoint = server.endpoint();

        // when
        server.close();

        // then
        assertThatThrownBy(() -> http.send(
                        HttpRequest.newBuilder(endpoint).POST(body("{}")).build(),
                        HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(ConnectException.class);
    }

    @Test
    void bindsToLoopbackWithAFreshTokenPerServer() throws Exception {
        // given
        BoardSessionTools tools = BoardSessionFixtures.tools(
                trello, BoardSessionFixtures.allowAllPolicy(), BoardSessionFixtures.defaultRoles());

        // when
        String otherToken;
        try (BoardSessionMcpServer other = BoardSessionMcpServer.start(json, tools, SERVER_INSTRUCTIONS, "test")) {
            otherToken = other.bearerToken();
        }

        // then
        assertThat(server.endpoint().getHost()).isEqualTo("127.0.0.1");
        assertThat(otherToken).isNotEqualTo(server.bearerToken()).hasSizeGreaterThanOrEqualTo(43);
    }

    private JsonNode post(String body) throws IOException, InterruptedException {
        HttpResponse<String> response = send(authorized().POST(body(body)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        return json.readTree(response.body());
    }

    private HttpRequest.Builder authorized() {
        return HttpRequest.newBuilder(server.endpoint())
                .header("Authorization", "Bearer " + server.bearerToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.BodyPublisher body(String value) {
        return HttpRequest.BodyPublishers.ofString(value);
    }
}
