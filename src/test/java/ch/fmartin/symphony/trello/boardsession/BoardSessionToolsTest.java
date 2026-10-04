package ch.fmartin.symphony.trello.boardsession;

import static ch.fmartin.symphony.trello.boardsession.BoardSessionTools.ADD_CARD_COMMENT;
import static ch.fmartin.symphony.trello.boardsession.BoardSessionTools.ARCHIVE_CARD;
import static ch.fmartin.symphony.trello.boardsession.BoardSessionTools.BOARD_OVERVIEW;
import static ch.fmartin.symphony.trello.boardsession.BoardSessionTools.CREATE_CARD;
import static ch.fmartin.symphony.trello.boardsession.BoardSessionTools.GET_CARD;
import static ch.fmartin.symphony.trello.boardsession.BoardSessionTools.LIST_CARDS;
import static ch.fmartin.symphony.trello.boardsession.BoardSessionTools.MOVE_CARD;
import static ch.fmartin.symphony.trello.boardsession.BoardSessionTools.SET_CARD_CHECKLIST_ITEM;
import static ch.fmartin.symphony.trello.boardsession.BoardSessionTools.UPDATE_CARD;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.testsupport.FakeTrelloBoard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class BoardSessionToolsTest {
    private static final String READY_CARD_ID = "card-ready";
    private static final String READY_CARD_SHORT_LINK = "Ready123";
    private static final String REVIEW_CARD_ID = "card-review";
    private static final List<String> ALL_TOOLS = List.of(
            BOARD_OVERVIEW,
            LIST_CARDS,
            GET_CARD,
            CREATE_CARD,
            UPDATE_CARD,
            MOVE_CARD,
            ADD_CARD_COMMENT,
            SET_CARD_CHECKLIST_ITEM,
            ARCHIVE_CARD);
    private static final List<String> READ_TOOLS = List.of(BOARD_OVERVIEW, LIST_CARDS, GET_CARD);

    private final ObjectMapper json = new ObjectMapper();
    private FakeTrelloBoard trello;

    @BeforeEach
    void startBoard() throws Exception {
        trello = new FakeTrelloBoard("Symphony Work Queue")
                .withList("list-inbox", "Inbox")
                .withList("list-ready", "Ready for Codex")
                .withList("list-progress", "In Progress")
                .withList("list-blocked", "Blocked")
                .withList("list-review", "Human Review")
                .withList("list-done", "Done")
                .withList("list-old", "Old Ideas", true)
                .withCard(READY_CARD_ID, READY_CARD_SHORT_LINK, "Add snapshot tests", "list-ready")
                .withCard(REVIEW_CARD_ID, "Review12", "Fix login bug", "list-review")
                .withCardDescription(REVIEW_CARD_ID, "Users cannot log in after the update.")
                .withComment(REVIEW_CARD_ID, "Riley Reviewer", "Please add a regression test.")
                .withCard("card-foreign", "Foreign1", "Other board card", "list-x", "board-other")
                .start();
    }

    @AfterEach
    void stopBoard() {
        trello.close();
    }

    static Stream<Arguments> advertisedToolsByPolicy() {
        return Stream.of(
                Arguments.of("all writes allowed", policy(), ALL_TOOLS),
                Arguments.of("writes disabled", policy().with("allow_writes", false), READ_TOOLS),
                Arguments.of("tools disabled", policy().with("enabled", false), List.of()),
                Arguments.of(
                        "comments, checklists, and moves not allowed",
                        policy().with("allow_comments", false)
                                .with("allow_checklists", false)
                                .with("allowed_move_list_names", List.of()),
                        List.of(BOARD_OVERVIEW, LIST_CARDS, GET_CARD, CREATE_CARD, UPDATE_CARD, ARCHIVE_CARD)));
    }

    @MethodSource("advertisedToolsByPolicy")
    @ParameterizedTest(name = "{0}")
    void advertisesOnlyTheToolsTheWorkflowAllows(String scenario, Policy policy, List<String> expectedTools) {
        // given
        BoardSessionTools tools = tools(policy, defaultRoles());

        // when
        List<BoardSessionTools.ToolDefinition> definitions = tools.definitions();

        // then
        assertThat(definitions)
                .extracting(BoardSessionTools.ToolDefinition::name)
                .containsExactlyElementsOf(expectedTools);
    }

    static Stream<Arguments> refusedCallsByPolicy() {
        return Stream.of(
                Arguments.of(policy().with("enabled", false), LIST_CARDS, "{}", "trello_tools_disabled"),
                Arguments.of(
                        policy().with("allow_writes", false),
                        CREATE_CARD,
                        "{\"title\":\"New\",\"list_name\":\"Inbox\"}",
                        "trello_writes_disabled"),
                Arguments.of(
                        policy().with("allow_writes", false),
                        ARCHIVE_CARD,
                        "{\"card\":\"Ready123\",\"confirm_title\":\"Add snapshot tests\"}",
                        "trello_writes_disabled"),
                Arguments.of(
                        policy().with("allow_comments", false),
                        ADD_CARD_COMMENT,
                        "{\"card\":\"Ready123\",\"text\":\"Hello\"}",
                        "trello_comments_disabled"),
                Arguments.of(
                        policy().with("allow_checklists", false),
                        SET_CARD_CHECKLIST_ITEM,
                        "{\"card\":\"Ready123\",\"checklist_name\":\"Steps\",\"item_name\":\"One\",\"complete\":true}",
                        "trello_checklists_disabled"),
                Arguments.of(
                        policy().with("allowed_move_list_names", List.of()),
                        MOVE_CARD,
                        "{\"card\":\"Ready123\",\"list_name\":\"Done\"}",
                        "trello_move_allowlist_required"));
    }

    @MethodSource("refusedCallsByPolicy")
    @ParameterizedTest(name = "{1} -> {3}")
    void refusesToolsTheWorkflowDisablesEvenWhenCalledDirectly(
            Policy policy, String tool, String arguments, String expectedError) throws Exception {
        // given
        BoardSessionTools tools = tools(policy, defaultRoles());

        // when
        BoardSessionTools.ToolResult result = tools.call(tool, json.readTree(arguments));

        // then
        assertFailure(result, expectedError);
        assertThat(trello.requests())
                .as("a refused tool must not contact Trello")
                .isEmpty();
    }

    @Test
    void boardOverviewReportsListRolesDefaultListAndAllowedOperations() {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        JsonNode overview = success(tools.call(BOARD_OVERVIEW, json.createObjectNode()));

        // then
        assertThat(overview.path("board").path("name").asText()).isEqualTo("Symphony Work Queue");
        assertThat(overview.path("default_new_card_list").asText()).isEqualTo("Ready for Codex");
        assertThat(overview.path("lists"))
                .extracting(list -> list.path("name").asText() + "=" + list.path("roles"))
                .containsExactly(
                        "Inbox=[]",
                        "Ready for Codex=[\"active\",\"queue\"]",
                        "In Progress=[\"active\",\"in_progress\"]",
                        "Blocked=[\"blocked\"]",
                        "Human Review=[\"review\"]",
                        "Done=[\"terminal\"]");
        assertThat(overview.path("permissions").path("move_destinations"))
                .extracting(JsonNode::asText)
                .containsExactly("In Progress", "Blocked", "Human Review", "Done");
        assertThat(overview.path("permissions").path("delete").asBoolean())
                .as("board sessions never offer card deletion")
                .isFalse();
    }

    @Test
    void listCardsFiltersByListNameAndSearchText() throws Exception {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        JsonNode byList = success(tools.call(LIST_CARDS, json.readTree("{\"list_name\":\"human review\"}")));
        JsonNode byText = success(tools.call(LIST_CARDS, json.readTree("{\"query\":\"CANNOT LOG\"}")));
        JsonNode limited = success(tools.call(LIST_CARDS, json.readTree("{\"limit\":1}")));

        // then
        assertThat(byList.path("cards"))
                .extracting(card -> card.path("title").asText())
                .containsExactly("Fix login bug");
        assertThat(byText.path("cards"))
                .extracting(card -> card.path("id").asText())
                .containsExactly(REVIEW_CARD_ID);
        assertThat(limited.path("total_matches").asInt()).isEqualTo(2);
        assertThat(limited.path("truncated").asBoolean())
                .as("a limited result must say that more cards matched")
                .isTrue();
        assertThat(limited.path("cards")).hasSize(1);
    }

    @Test
    void getCardReturnsDescriptionListAndRecentComments() throws Exception {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        JsonNode card = success(
                tools.call(GET_CARD, json.readTree("{\"card\":\"https://trello.com/c/Review12/fix-login-bug\"}")));

        // then
        assertThat(card.path("title").asText()).isEqualTo("Fix login bug");
        assertThat(card.path("list_name").asText()).isEqualTo("Human Review");
        assertThat(card.path("description").asText()).isEqualTo("Users cannot log in after the update.");
        assertThat(card.path("recent_comments")).singleElement().satisfies(comment -> {
            assertThat(comment.path("author").asText()).isEqualTo("Riley Reviewer");
            assertThat(comment.path("text").asText()).isEqualTo("Please add a regression test.");
        });
    }

    static Stream<Arguments> foreignCardCalls() {
        return Stream.of(
                Arguments.of(GET_CARD, "{\"card\":\"Foreign1\"}"),
                Arguments.of(UPDATE_CARD, "{\"card\":\"Foreign1\",\"title\":\"Renamed\"}"),
                Arguments.of(MOVE_CARD, "{\"card\":\"Foreign1\",\"list_name\":\"Done\"}"),
                Arguments.of(ADD_CARD_COMMENT, "{\"card\":\"Foreign1\",\"text\":\"Hello\"}"),
                Arguments.of(
                        SET_CARD_CHECKLIST_ITEM,
                        "{\"card\":\"Foreign1\",\"checklist_name\":\"Steps\",\"item_name\":\"One\",\"complete\":true}"),
                Arguments.of(ARCHIVE_CARD, "{\"card\":\"Foreign1\",\"confirm_title\":\"Other board card\"}"));
    }

    @MethodSource("foreignCardCalls")
    @ParameterizedTest(name = "{0}")
    void rejectsCardsThatBelongToAnotherBoardWithoutWriting(String tool, String arguments) throws Exception {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        BoardSessionTools.ToolResult result = tools.call(tool, json.readTree(arguments));

        // then
        assertFailure(result, "card_not_on_selected_board");
        assertThat(trello.writes())
                .as("a card on another board must not be changed")
                .isEmpty();
    }

    @Test
    void createCardUsesTheNamedList() throws Exception {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        JsonNode created = success(tools.call(
                CREATE_CARD,
                json.readTree("{\"title\":\"Add snapshot testing for the CLI output\",\"description\":\"Use"
                        + " approval files.\",\"list_name\":\"inbox\"}")));

        // then
        assertThat(created.path("list_name").asText()).isEqualTo("Inbox");
        assertThat(trello.card(created.path("card_id").asText())).hasValueSatisfying(card -> {
            assertThat(card.path("name").asText()).isEqualTo("Add snapshot testing for the CLI output");
            assertThat(card.path("desc").asText()).isEqualTo("Use approval files.");
            assertThat(card.path("idList").asText()).isEqualTo("list-inbox");
        });
    }

    @Test
    void createCardWithoutListUsesTheWorkflowsSingleQueueList() throws Exception {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        JsonNode created = success(tools.call(CREATE_CARD, json.readTree("{\"title\":\"Write the docs\"}")));

        // then
        assertThat(created.path("list_name").asText()).isEqualTo("Ready for Codex");
    }

    @Test
    void createCardWithoutListFailsWhenTheWorkflowHasNoSingleQueueList() throws Exception {
        // given
        BoardListRoles twoQueues = BoardListRoles.of(
                List.of("Inbox", "Ready for Codex"),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of("Done"));
        BoardSessionTools tools = tools(policy(), twoQueues);

        // when
        BoardSessionTools.ToolResult result = tools.call(CREATE_CARD, json.readTree("{\"title\":\"Write the docs\"}"));

        // then
        assertFailure(result, "list_name_required");
        assertThat(result.payload().path("message").asText()).contains("Ask the user", "Inbox", "Ready for Codex");
        assertThat(trello.writes()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Old Ideas", "Nowhere"})
    void createCardRejectsListsThatAreNotOpenOnTheBoard(String listName) {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        BoardSessionTools.ToolResult result = tools.call(
                CREATE_CARD, json.createObjectNode().put("title", "Idea").put("list_name", listName));

        // then
        assertFailure(result, "list_not_found");
        assertThat(trello.writes()).isEmpty();
    }

    @Test
    void createCardRejectsDuplicateListNames() throws Exception {
        // given
        trello.close();
        trello = new FakeTrelloBoard("Duplicates")
                .withList("list-a", "Inbox")
                .withList("list-b", "Inbox")
                .start();
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        BoardSessionTools.ToolResult result =
                tools.call(CREATE_CARD, json.readTree("{\"title\":\"Idea\",\"list_name\":\"Inbox\"}"));

        // then
        assertFailure(result, "list_name_ambiguous");
        assertThat(trello.writes()).isEmpty();
    }

    @Test
    void updateCardChangesTitleAndDescription() throws Exception {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        success(tools.call(
                UPDATE_CARD,
                json.readTree("{\"card\":\"Ready123\",\"title\":\"Add CLI snapshot tests\",\"description\":\"Done"
                        + " when the help output is covered.\"}")));

        // then
        assertThat(trello.card(READY_CARD_ID)).hasValueSatisfying(card -> {
            assertThat(card.path("name").asText()).isEqualTo("Add CLI snapshot tests");
            assertThat(card.path("desc").asText()).isEqualTo("Done when the help output is covered.");
        });
    }

    @Test
    void updateCardRequiresAFieldToChange() throws Exception {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        BoardSessionTools.ToolResult result = tools.call(UPDATE_CARD, json.readTree("{\"card\":\"Ready123\"}"));

        // then
        assertFailure(result, "missing_update_fields");
        assertThat(trello.writes()).isEmpty();
    }

    @Test
    void moveCardUsesTheWorkflowMoveAllowlist() throws Exception {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        JsonNode moved =
                success(tools.call(MOVE_CARD, json.readTree("{\"card\":\"Ready123\",\"list_name\":\"Done\"}")));
        BoardSessionTools.ToolResult refused =
                tools.call(MOVE_CARD, json.readTree("{\"card\":\"Ready123\",\"list_name\":\"Inbox\"}"));

        // then
        assertThat(moved.path("list_name").asText()).isEqualTo("Done");
        assertFailure(refused, "trello_move_not_allowed");
        assertThat(trello.card(READY_CARD_ID))
                .hasValueSatisfying(
                        card -> assertThat(card.path("idList").asText()).isEqualTo("list-done"));
    }

    @Test
    void addCardCommentWritesTheComment() throws Exception {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        success(tools.call(ADD_CARD_COMMENT, json.readTree("{\"card\":\"Ready123\",\"text\":\"Started a plan.\"}")));

        // then
        assertThat(trello.card(READY_CARD_ID)).hasValueSatisfying(card -> assertThat(card.path("actions"))
                .extracting(action -> action.path("data").path("text").asText())
                .containsExactly("Started a plan."));
    }

    @Test
    void setCardChecklistItemCreatesTheChecklistAndItem() throws Exception {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        JsonNode created = success(tools.call(
                SET_CARD_CHECKLIST_ITEM,
                json.readTree("{\"card\":\"Ready123\",\"checklist_name\":\"Steps\",\"item_name\":\"Write tests\","
                        + "\"complete\":false}")));
        JsonNode completed = success(tools.call(
                SET_CARD_CHECKLIST_ITEM,
                json.readTree("{\"card\":\"Ready123\",\"checklist_name\":\"Steps\",\"item_name\":\"Write tests\","
                        + "\"complete\":true}")));

        // then
        assertThat(created.path("status").asText()).isEqualTo("checklist_item_created");
        assertThat(completed.path("status").asText()).isEqualTo("checklist_item_updated");
        assertThat(trello.card(READY_CARD_ID)).hasValueSatisfying(card -> assertThat(
                        card.path("checklists").path(0).path("checkItems"))
                .singleElement()
                .satisfies(item -> assertThat(item.path("state").asText()).isEqualTo("complete")));
    }

    @Test
    void archiveCardRequiresTheExactCurrentTitle() throws Exception {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        BoardSessionTools.ToolResult mismatch = tools.call(
                ARCHIVE_CARD, json.readTree("{\"card\":\"Ready123\",\"confirm_title\":\"add snapshot tests\"}"));
        List<FakeTrelloBoard.RecordedRequest> writesAfterMismatch = trello.writes();
        JsonNode archived = success(tools.call(
                ARCHIVE_CARD, json.readTree("{\"card\":\"Ready123\",\"confirm_title\":\"Add snapshot tests\"}")));
        BoardSessionTools.ToolResult again = tools.call(
                ARCHIVE_CARD, json.readTree("{\"card\":\"Ready123\",\"confirm_title\":\"Add snapshot tests\"}"));

        // then
        assertFailure(mismatch, "archive_confirmation_mismatch");
        assertThat(writesAfterMismatch)
                .as("a mismatched confirmation must not archive")
                .isEmpty();
        assertThat(archived.path("status").asText()).isEqualTo("card_archived");
        assertThat(trello.card(READY_CARD_ID))
                .hasValueSatisfying(card -> assertThat(card.path("closed").asBoolean())
                        .as("the card must be archived, not deleted")
                        .isTrue());
        assertFailure(again, "card_already_archived");
    }

    @Test
    void toolResultsNeverContainTheTrelloCredentials() throws Exception {
        // given
        BoardSessionTools tools = tools(policy(), defaultRoles());

        // when
        List<BoardSessionTools.ToolResult> results = List.of(
                tools.call(BOARD_OVERVIEW, json.createObjectNode()),
                tools.call(LIST_CARDS, json.createObjectNode()),
                tools.call(GET_CARD, json.readTree("{\"card\":\"Review12\"}")),
                tools.call(GET_CARD, json.readTree("{\"card\":\"Missing1\"}")),
                tools.call(CREATE_CARD, json.readTree("{\"title\":\"New\"}")),
                tools.call(MOVE_CARD, json.readTree("{\"card\":\"Ready123\",\"list_name\":\"Inbox\"}")));

        // then
        assertThat(results).extracting(result -> result.payload().toString()).allSatisfy(payload -> assertThat(payload)
                .doesNotContain(BoardSessionFixtures.API_KEY_SENTINEL, BoardSessionFixtures.API_TOKEN_SENTINEL));
        assertThat(trello.requests())
                .as("Symphony itself authenticates every Trello request")
                .allSatisfy(request -> assertThat(request.authorization())
                        .contains(BoardSessionFixtures.API_KEY_SENTINEL, BoardSessionFixtures.API_TOKEN_SENTINEL));
    }

    private BoardSessionTools tools(Policy policy, BoardListRoles roles) {
        return BoardSessionFixtures.tools(trello, policy.values(), roles);
    }

    static BoardListRoles defaultRoles() {
        return BoardSessionFixtures.defaultRoles();
    }

    static Policy policy() {
        return new Policy(BoardSessionFixtures.allowAllPolicy());
    }

    private static JsonNode success(BoardSessionTools.ToolResult result) {
        assertThat(result.error()).as("tool result %s", result.payload()).isFalse();
        return result.payload();
    }

    private static void assertFailure(BoardSessionTools.ToolResult result, String expectedError) {
        assertThat(result.error()).as("tool result %s", result.payload()).isTrue();
        assertThat(result.payload().path("error").asText()).isEqualTo(expectedError);
    }

    record Policy(Map<String, Object> values) {
        Policy with(String key, Object value) {
            // Insertion order keeps parameterized display names stable.
            Map<String, Object> copy = new LinkedHashMap<>(values);
            copy.put(key, value);
            return new Policy(copy);
        }

        @Override
        public String toString() {
            return values.toString();
        }
    }
}
