package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.CliExitCodes;
import ch.fmartin.symphony.trello.TestHttpExchange;
import ch.fmartin.symphony.trello.testsupport.CliRunResult;
import ch.fmartin.symphony.trello.testsupport.CredentialSentinels;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloBoard;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import ch.fmartin.symphony.trello.testsupport.TestEnv;
import ch.fmartin.symphony.trello.tracker.SymphonyCommentFooter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BoardCardCommandsTest {
    private static final String BOARD_ID = "board-1";
    private static final String DEFAULT_TRELLO_TOOLS =
            """
            trello_tools:
              enabled: true
              allow_writes: true
              allowed_move_list_names:
                - In Progress
                - Blocked
                - Human Review
                - Done
              allow_comments: true
            """;

    @TempDir
    Path tempDir;

    private final FakeTrelloBoard board = new FakeTrelloBoard(BOARD_ID)
            .list("list-inbox", "Inbox")
            .list("list-ready", "Ready for Codex")
            .list("list-progress", "In Progress")
            .list("list-blocked", "Blocked")
            .list("list-review", "Human Review")
            .list("list-done", "Done");
    private final FakeTrelloBoard foreignBoard = new FakeTrelloBoard(BOARD_ID).list("list-inbox", "Inbox");
    private FakeTrelloServer server;
    private FakeTrelloServer foreignServer;

    @AfterEach
    void stopServers() {
        if (server != null) {
            server.close();
        }
        if (foreignServer != null) {
            foreignServer.close();
        }
    }

    @Test
    void listShowsEachOpenListWithItsRolesPermissionsAndCards() throws Exception {
        // given
        board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox");
        board.card("card0002", "SYNTH102", "Write the release notes", "list-review");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "list");

        // then
        result.assertSuccess();
        assertThat(result.stdoutLines())
                .containsSubsequence(
                        "Board \"Queue\" https://trello.com/b/SYNTH001",
                        "",
                        "List \"Inbox\"  roles: none  move here: no  create here: yes",
                        "  SYNTH101  \"Fix the login redirect\"",
                        "",
                        "List \"Ready for Codex\"  roles: active  move here: no  create here: no",
                        "  (no open cards)",
                        "",
                        "List \"In Progress\"  roles: in-progress  move here: yes  create here: yes",
                        "  (no open cards)",
                        "",
                        "List \"Blocked\"  roles: blocked  move here: yes  create here: yes",
                        "  (no open cards)",
                        "",
                        "List \"Human Review\"  roles: human-review  move here: yes  create here: yes",
                        "  SYNTH102  \"Write the release notes\"",
                        "",
                        "List \"Done\"  roles: terminal  move here: yes  create here: yes",
                        "  (no open cards)");
        assertThat(board.writeRequests()).isEmpty();
    }

    @Test
    void listJsonNamesTheBoardTheListRolesAndTheCards() throws Exception {
        // given
        board.card("card0001", "SYNTH101", "Fix the login redirect", "list-review");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "list", "--list", "Human Review", "--json");

        // then
        result.assertSuccess();
        assertThat(result.stdout().strip())
                .isEqualTo(
                        """
                        {"status":"ok","board":{"name":"Queue","id":"board-1","url":"https://trello.com/b/SYNTH001",\
                        "workflow":%s},"lists":[{"list":{"id":"list-review","name":"Human Review",\
                        "roles":["human-review"],"move_allowed":true,"create_allowed":true},\
                        "cards":[{"id":"card0001","short_link":"SYNTH101","url":"https://trello.com/c/SYNTH101",\
                        "title":"Fix the login redirect"}]}]}"""
                                .formatted(jsonString(workflowPath(configDir).toString())));
    }

    @Test
    void listByRoleShowsEveryListWithThatRole() throws Exception {
        // given
        board.list("list-wont", "Won't Do");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS, List.of("Done", "Won't Do"));

        // when
        CliRunResult result = runCard(configDir, "list", "--list", "terminal");

        // then
        result.assertSuccess();
        assertThat(result.stdoutLines())
                .filteredOn(line -> line.startsWith("List "))
                .containsExactly(
                        "List \"Done\"  roles: terminal  move here: yes  create here: yes",
                        "List \"Won't Do\"  roles: terminal  move here: no  create here: no");
    }

    @Test
    void showJsonIncludesDescriptionChecklistsAttachmentsAndComments() throws Exception {
        // given
        board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox")
                .description("The redirect drops the query string.")
                .checklist("Acceptance", "Keeps the query string")
                .attachment("Bug report", "https://example.com/bug")
                .comment("Seen on staging.");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "show", "https://trello.com/c/SYNTH101/1-slug", "--json");

        // then
        result.assertSuccess();
        var card = new ObjectMapper().readTree(result.stdout()).path("card");
        assertThat(card.path("id").asText()).isEqualTo("card0001");
        assertThat(card.path("url").asText()).isEqualTo("https://trello.com/c/SYNTH101");
        assertThat(card.path("description").asText()).isEqualTo("The redirect drops the query string.");
        assertThat(card.path("list").path("name").asText()).isEqualTo("Inbox");
        assertThat(card.path("archived").asBoolean())
                .as("an open card reports archived false")
                .isFalse();
        assertThat(card.path("checklists").path(0).path("name").asText()).isEqualTo("Acceptance");
        assertThat(card.path("checklists")
                        .path(0)
                        .path("items")
                        .path(0)
                        .path("text")
                        .asText())
                .isEqualTo("Keeps the query string");
        assertThat(card.path("attachments").path(0).path("url").asText()).isEqualTo("https://example.com/bug");
        assertThat(card.path("comments").path(0).path("text").asText()).isEqualTo("Seen on staging.");
    }

    @Test
    void showPrintsTheCardAsEscapedText() throws Exception {
        // given
        board.card("card0001", "SYNTH101", "Fix \"the\" redirect", "list-inbox")
                .description("First line\nSecond \u001b[31mline");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "show", "SYNTH101");

        // then
        result.assertSuccess();
        assertThat(result.stdoutLines())
                .containsSubsequence(
                        "Card \"Fix \\\"the\\\" redirect\"",
                        "  URL: https://trello.com/c/SYNTH101",
                        "  List: \"Inbox\"",
                        "  Archived: no",
                        "Description:",
                        "  First line",
                        "  Second \\u001B[31mline");
    }

    @Test
    void createPutsTheCardInTheListOfTheRequestedRole() throws Exception {
        // given
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(
                configDir,
                "create",
                "--title",
                "  Add a dark mode  ",
                "--description",
                "#2076: needs it",
                "--list",
                "Human_Review",
                "--json");

        // then
        result.assertSuccess();
        FakeTrelloBoard.FakeCard created = board.createdCards().getFirst();
        assertThat(created.name()).isEqualTo("Add a dark mode");
        assertThat(created.listId()).isEqualTo("list-review");
        assertThat(created.description()).isEqualTo("\\#2076: needs it");
        var json = new ObjectMapper().readTree(result.stdout());
        assertThat(json.path("status").asText()).isEqualTo("card_created");
        assertThat(json.path("card").path("id").asText()).isEqualTo(created.id());
        assertThat(json.path("card").path("url").asText()).isEqualTo("https://trello.com/c/%s", created.shortLink());
        assertThat(json.path("list").path("name").asText()).isEqualTo("Human Review");
    }

    @Test
    void createRefusesAnActiveListThatTheMoveAllowlistLeavesOut() throws Exception {
        // given
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "create", "--title", "Start now", "--list", "Ready for Codex");

        // then
        assertFailure(result, "trello_create_list_not_allowed");
        assertThat(board.writeRequests()).isEmpty();
    }

    @Test
    void createRefusesATitleWithALineBreak() throws Exception {
        // given
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "create", "--title", "One\nTwo", "--list", "Inbox");

        // then
        assertFailure(result, "invalid_card_text");
        result.stderrContains("title must be one line");
        assertThat(board.writeRequests()).isEmpty();
    }

    @Test
    void moveResolvesARoleToItsListAndReportsTheMove() throws Exception {
        // given
        FakeTrelloBoard.FakeCard card = board.card("card0001", "SYNTH101", "Fix the login redirect", "list-progress");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "move", "SYNTH101", "--to", "human-review", "--json");

        // then
        result.assertSuccess();
        assertThat(card.listId()).isEqualTo("list-review");
        assertThat(result.stdout().strip())
                .isEqualTo(
                        """
                        {"status":"card_moved","board":{"name":"Queue","id":"board-1","url":"https://trello.com/b/SYNTH001",\
                        "workflow":%s},"card":{"id":"card0001","short_link":"SYNTH101",\
                        "url":"https://trello.com/c/SYNTH101","title":"Fix the login redirect"},\
                        "list":{"id":"list-review","name":"Human Review","roles":["human-review"],\
                        "move_allowed":true,"create_allowed":true}}"""
                                .formatted(jsonString(workflowPath(configDir).toString())));
    }

    @Test
    void moveTakesAListNameBeforeARoleName() throws Exception {
        // given
        board.list("list-active", "Active");
        FakeTrelloBoard.FakeCard card = board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS.replace("- Done", "- Done\n    - Active"));

        // when
        CliRunResult result = runCard(configDir, "move", "SYNTH101", "--to", "active");

        // then
        result.assertSuccess().stdoutContains("Moved card \"Fix the login redirect\" to \"Active\".");
        assertThat(card.listId()).isEqualTo("list-active");
    }

    @Test
    void moveRefusesAListThatTheWorkflowDoesNotAllow() throws Exception {
        // given
        FakeTrelloBoard.FakeCard card = board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "move", "SYNTH101", "--to", "active");

        // then
        assertFailure(result, "trello_move_not_allowed");
        assertThat(card.listId()).isEqualTo("list-inbox");
        assertThat(board.writeRequests()).isEmpty();
    }

    @Test
    void moveReportsARoleThatNoOpenListHas() throws Exception {
        // given
        board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "move", "SYNTH101", "--to", "merging");

        // then
        assertFailure(result, "setup_card_list_role_unset");
        result.stderrContains("The workflow gives no open list the merging role.");
    }

    @Test
    void moveReportsARoleThatCoversSeveralLists() throws Exception {
        // given
        board.list("list-wont", "Won't Do");
        board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS, List.of("Done", "Won't Do"));

        // when
        CliRunResult result = runCard(configDir, "move", "SYNTH101", "--to", "terminal");

        // then
        assertFailure(result, "setup_card_list_role_ambiguous");
        result.stderrContains("The terminal role covers several open lists: \"Done\", \"Won't Do\".");
        assertThat(board.writeRequests()).isEmpty();
    }

    @Test
    void moveReportsAnUnknownListWithTheOpenListsAndRoles() throws Exception {
        // given
        board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "move", "SYNTH101", "--to", "Backlog");

        // then
        assertFailure(result, "setup_card_list_not_found");
        result.stderrContains(
                "No open list or list role matches \"Backlog\".",
                "Roles: active, in-progress, human-review, blocked, merging, terminal.");
    }

    @Test
    void commentAppendsTheSymphonyFooter() throws Exception {
        // given
        FakeTrelloBoard.FakeCard card = board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "comment", "card0001", "--text", "Waiting for the API key.");

        // then
        result.assertSuccess().stdoutContains("Added a comment to card \"Fix the login redirect\".");
        assertThat(card.comments()).containsExactly(SymphonyCommentFooter.append("Waiting for the API key."));
    }

    @Test
    void commentJsonNamesTheCardAndNoList() throws Exception {
        // given
        board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "comment", "SYNTH101", "--text", "Hello", "--json");

        // then
        result.assertSuccess();
        var json = new ObjectMapper().readTree(result.stdout());
        assertThat(json.path("status").asText()).isEqualTo("comment_added");
        assertThat(json.path("card").path("short_link").asText()).isEqualTo("SYNTH101");
        assertThat(json.path("list").isNull()).as("a comment names no list").isTrue();
    }

    @Test
    void commentFollowsTheWorkflowCommentSetting() throws Exception {
        // given
        board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS.replace("allow_comments: true", "allow_comments: false"));

        // when
        CliRunResult result = runCard(configDir, "comment", "SYNTH101", "--text", "Hello");

        // then
        assertFailure(result, "trello_comments_disabled");
        assertThat(board.writeRequests()).isEmpty();
    }

    @Test
    void writesFollowTheWorkflowToolSwitch() throws Exception {
        // given
        board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS.replace("enabled: true", "enabled: false"));

        // when
        CliRunResult archive = runCard(configDir, "archive", "SYNTH101", "--confirm-title", "Fix the login redirect");
        CliRunResult list = runCard(configDir, "list");

        // then
        assertFailure(archive, "trello_tools_disabled");
        list.assertSuccess().stdoutContains("List \"Inbox\"  roles: none  move here: no  create here: no");
        assertThat(board.writeRequests()).isEmpty();
    }

    @Test
    void archiveArchivesACardOfTheSelectedBoard() throws Exception {
        // given
        FakeTrelloBoard.FakeCard card = board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result =
                runCard(configDir, "archive", "SYNTH101", "--confirm-title", "Fix the login redirect", "--json");

        // then
        result.assertSuccess();
        assertThat(card.archived()).as("the fake board archived the card").isTrue();
        assertThat(new ObjectMapper().readTree(result.stdout()).path("status").asText())
                .isEqualTo("card_archived");
    }

    @Test
    void archiveNeedsTheCurrentCardTitle() throws Exception {
        // given
        FakeTrelloBoard.FakeCard card = board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "archive", "SYNTH101", "--confirm-title", "Fix the login");

        // then
        assertFailure(result, "setup_card_archive_title_mismatch");
        result.stderrContains("\"Fix the login redirect\"");
        assertThat(card.archived())
                .as("a mismatched title leaves the card open")
                .isFalse();
        assertThat(board.writeRequests()).isEmpty();
    }

    @Test
    void archiveRefusesACardOnAnotherBoard() throws Exception {
        // given
        FakeTrelloBoard.FakeCard foreign = board.foreignCard("card0009", "SYNTH999", "Other team card", "board-2");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "archive", "SYNTH999", "--confirm-title", "Other team card");

        // then
        assertFailure(result, "setup_card_not_on_board");
        assertThat(foreign.archived())
                .as("the card on the other board stays open")
                .isFalse();
        assertThat(board.writeRequests()).isEmpty();
    }

    @Test
    void archiveRefusesACardThatIsAlreadyArchived() throws Exception {
        // given
        board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox")
                .archive();
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult result = runCard(configDir, "archive", "SYNTH101", "--confirm-title", "Fix the login redirect");

        // then
        assertFailure(result, "setup_card_archived");
        assertThat(board.writeRequests()).isEmpty();
    }

    @Test
    void cardArgumentsMustNameACard() throws Exception {
        // given
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        CliRunResult invalid = runCard(configDir, "show", "https://example.com/c/SYNTH101");
        CliRunResult missing = runCard(configDir, "show", "nope0000");

        // then
        assertFailure(invalid, "setup_card_invalid_reference");
        assertFailure(missing, "setup_card_not_found");
    }

    @Test
    void severalConnectedBoardsNeedASelector() throws Exception {
        // given
        Path configDir = connectBoards(DEFAULT_TRELLO_TOOLS, "Queue", "Other");

        // when
        CliRunResult unselected = runCard(configDir, "list");
        CliRunResult selected = runCard(configDir, "list", "--board", "Queue");
        CliRunResult byWorkflow =
                runCard(configDir, "list", "--workflow", workflowPath(configDir).toString());
        CliRunResult conflicting = runCard(
                configDir,
                "list",
                "--board",
                "Queue",
                "--workflow",
                workflowPath(configDir).toString());
        CliRunResult unknown = runCard(configDir, "list", "--board", "Missing");
        CliRunResult byId = runCard(configDir, "list", "--board", BOARD_ID);
        CliRunResult byShortLink = runCard(configDir, "list", "--board", "SYNTH001");
        CliRunResult byUrl = runCard(configDir, "list", "--board", "https://trello.com/b/SYNTH001/queue");

        // then
        assertFailure(unselected, "setup_worker_board_required");
        unselected.stderrContains(
                "Multiple Trello boards are connected. Re-run with --board NAME or --workflow PATH for card list.");
        selected.assertSuccess().stdoutContains("Board \"Queue\"");
        byWorkflow.assertSuccess().stdoutContains("Board \"Queue\"");
        assertFailure(conflicting, "setup_worker_selection_conflict");
        assertFailure(unknown, "setup_worker_board_not_found");
        assertThat(List.of(byId, byShortLink, byUrl))
                .allSatisfy(result -> result.assertSuccess().stdoutContains("Board \"Queue\""));
    }

    @Test
    void anUnconnectedWorkflowWithAnotherEndpointIsRefusedBeforeAnyRequest() throws Exception {
        // given
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);
        Path unconnected = configDir.resolve("WORKFLOW.unconnected.md");
        Files.writeString(unconnected, workflow(startForeignServer(), BOARD_ID, DEFAULT_TRELLO_TOOLS, List.of("Done")));

        // when
        CliRunResult result = runCard(configDir, "list", "--workflow", unconnected.toString());

        // then
        assertFailure(result, "setup_card_workflow_not_connected");
        assertThat(foreignBoard.requests()).isEmpty();
        assertThat(board.requests()).isEmpty();
    }

    @Test
    void aConnectedWorkflowWithAnotherEndpointIsRefusedBeforeAnyRequest() throws Exception {
        // given
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);
        Files.writeString(
                workflowPath(configDir),
                workflow(startForeignServer(), BOARD_ID, DEFAULT_TRELLO_TOOLS, List.of("Done")));

        // when
        CliRunResult result = runCard(configDir, "list");

        // then
        assertFailure(result, "setup_card_endpoint_not_trello");
        assertThat(foreignBoard.requests()).isEmpty();
        assertThat(board.requests()).isEmpty();
    }

    @Test
    void outputNeverContainsTheTrelloCredentials() throws Exception {
        // given
        board.card("card0001", "SYNTH101", "Fix the login redirect", "list-inbox");
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);

        // when
        List<CliRunResult> results = List.of(
                runCard(configDir, "list"),
                runCard(configDir, "list", "--json"),
                runCard(configDir, "show", "SYNTH101"),
                runCard(configDir, "show", "SYNTH101", "--json"),
                runCard(configDir, "create", "--title", "New card", "--list", "Inbox", "--json"),
                runCard(configDir, "move", "SYNTH101", "--to", "active"),
                runCard(configDir, "comment", "SYNTH101", "--text", "Done."),
                runCard(configDir, "archive", "SYNTH101", "--confirm-title", "Wrong title"),
                runCard(configDir, "archive", "SYNTH101", "--confirm-title", "Fix the login redirect"));

        // then
        assertThat(results).allSatisfy(result -> assertThat(result.stdout() + result.stderr())
                .doesNotContain(CredentialSentinels.TRELLO_API_KEY, CredentialSentinels.TRELLO_API_TOKEN));
    }

    @Test
    void rejectedCredentialsNameTheCredentialFileButNotTheValues() throws Exception {
        // given
        server = new FakeTrelloServer()
                .on("/1/", exchange -> TestHttpExchange.respond(exchange, 401, "{\"message\":\"invalid key\"}"))
                .startEmpty();
        Path configDir = writeConnectedBoards(DEFAULT_TRELLO_TOOLS, List.of("Done"), "Queue");

        // when
        CliRunResult result = runCard(configDir, "list");

        // then
        assertFailure(result, "trello_auth_failed");
        result.stderrDoesNotContain(CredentialSentinels.TRELLO_API_KEY, CredentialSentinels.TRELLO_API_TOKEN);
    }

    @Test
    void missingCredentialsNameTheCardCommand() throws Exception {
        // given
        Path configDir = connectBoard(DEFAULT_TRELLO_TOOLS);
        Files.delete(configDir.resolve(".env"));

        // when
        CliRunResult result = runCard(configDir, "list");

        // then
        assertFailure(result, "setup_worker_missing_trello_credentials");
        result.stderrContains("Missing Trello credentials for card list.");
        assertThat(board.requests()).isEmpty();
    }

    private Path connectBoard(String trelloTools) throws Exception {
        return connectBoard(trelloTools, List.of("Done"));
    }

    private Path connectBoard(String trelloTools, List<String> terminalStates) throws Exception {
        server = new FakeTrelloServer().on("/1/", board).startEmpty();
        return writeConnectedBoards(trelloTools, terminalStates, "Queue");
    }

    private Path connectBoards(String trelloTools, String... boardNames) throws Exception {
        server = new FakeTrelloServer().on("/1/", board).startEmpty();
        return writeConnectedBoards(trelloTools, List.of("Done"), boardNames);
    }

    private Path writeConnectedBoards(String trelloTools, List<String> terminalStates, String... boardNames)
            throws Exception {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Path env = configDir.resolve(".env");
        Files.writeString(
                env,
                TestEnv.trelloCredentials(CredentialSentinels.TRELLO_API_KEY, CredentialSentinels.TRELLO_API_TOKEN));
        List<ConnectedBoard> boards = new ArrayList<>();
        for (int index = 0; index < boardNames.length; index++) {
            String name = boardNames[index];
            Path workflow = configDir.resolve("WORKFLOW." + name.toLowerCase(Locale.ROOT) + ".md");
            Files.writeString(
                    workflow, workflow(index == 0 ? BOARD_ID : "board-" + (index + 1), trelloTools, terminalStates));
            boards.add(ConnectedBoardBuilder.connectedBoard(
                            workflow.toAbsolutePath().normalize())
                    .withBoardId(index == 0 ? BOARD_ID : "board-" + (index + 1))
                    .withBoardKey(index == 0 ? "SYNTH001" : "SYNTH00" + (index + 1))
                    .withBoardName(name)
                    .withBoardUrl("https://trello.com/b/" + (index == 0 ? "SYNTH001" : "SYNTH00" + (index + 1)))
                    .withEnvPath(env)
                    .withServerPort(8080 + index)
                    .build());
        }
        new ConnectedBoardRepository(configDir.resolve(ConnectedBoardManifest.FILE_NAME))
                .save(new ConnectedBoardManifest(boards));
        return configDir;
    }

    private String workflow(String boardId, String trelloTools, List<String> terminalStates) {
        return workflow(server.endpoint(), boardId, trelloTools, terminalStates);
    }

    private static String workflow(String endpoint, String boardId, String trelloTools, List<String> terminalStates) {
        var terminal = new StringBuilder();
        for (String state : terminalStates) {
            terminal.append("    - \"").append(state).append("\"\n");
        }
        return """
                ---
                tracker:
                  kind: trello
                  endpoint: %s
                  board_id: %s
                  active_states:
                    - Ready for Codex
                    - In Progress
                  in_progress_state: In Progress
                  blocked_state: Blocked
                  terminal_states:
                %s%s---
                # Queue
                """
                .formatted(endpoint, boardId, terminal, trelloTools);
    }

    /// Starts a second Trello API that the card commands must never call, and returns its endpoint.
    private String startForeignServer() throws Exception {
        foreignServer = new FakeTrelloServer().on("/1/", foreignBoard).startEmpty();
        return foreignServer.endpoint();
    }

    private static Path workflowPath(Path configDir) {
        return configDir.resolve("WORKFLOW.queue.md").toAbsolutePath().normalize();
    }

    private static String jsonString(String value) throws Exception {
        return new ObjectMapper().writeValueAsString(value);
    }

    private static void assertFailure(CliRunResult result, String code) {
        result.assertFailure(CliExitCodes.SETUP_FAILURE).stderrContains("setup_failed code=" + code + " message=");
    }

    private CliRunResult runCard(Path configDir, String... args) {
        List<String> command = new ArrayList<>(List.of("card"));
        command.add(args[0]);
        command.add("--config-dir");
        command.add(configDir.toString());
        command.addAll(List.of(args).subList(1, args.length));
        Map<String, String> environment = Map.of();
        var setup = new TrelloBoardSetup(
                new ObjectMapper(),
                () -> CodexModelSelectionDefaults.of(TrelloBoardSetup.CodexModelDefaults.fallback()));
        var workerManager = new LocalWorkerManager(environment);
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exitCode = TrelloBoardSetupMain.run(
                command.toArray(String[]::new),
                new TrelloBoardSetupService(setup, workerManager, environment),
                new LocalSetup(setup, new ProcessCommandRunner()),
                workerManager,
                new BoardCardCommands(workerManager, environment, server.endpointUri()),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new CliRunResult(
                exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }
}
