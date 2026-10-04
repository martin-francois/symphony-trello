package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.WorkspaceInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The Trello calls the guided tutorial makes. Every write targets the temporary tutorial board or
/// the card on it, so the tutorial cannot change a board it did not create.
final class TutorialTrello {
    /// Trello returns at most this many comments for the tutorial card. The tutorial card only gets
    /// a handful, so this is far above what one run produces.
    private static final String COMMENT_LIMIT = "50";

    private final TrelloSetupApi api;
    private final URI endpoint;
    private final TrelloCredentials credentials;
    private final TrelloBoardSetup boardSetup;

    TutorialTrello(URI endpoint, TrelloCredentials credentials) {
        var json = new ObjectMapper();
        this.api = new TrelloSetupApi(json);
        this.endpoint = TrelloApiEndpoint.normalize(Objects.requireNonNull(endpoint, "endpoint"));
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.boardSetup = new TrelloBoardSetup(json);
    }

    List<WorkspaceInfo> workspaces() {
        return boardSetup.listWorkspaces(new TrelloBoardSetup.WorkspaceListRequest(endpoint, credentials));
    }

    Board createBoard(String name, String workspaceId) {
        Map<String, Object> board = api.postMap(
                endpoint, "boards/", TrelloBoardSetup.createBoardQuery(name, workspaceId), credentials, "id");
        return new Board(string(board, "id"), string(board, "url"));
    }

    String createList(Board board, String name) {
        return string(
                api.postMap(endpoint, "lists", TrelloBoardSetup.listCreationQuery(name, board.id()), credentials, "id"),
                "id");
    }

    String createCard(String listId, String name, String description) {
        Map<String, String> query = Map.of("idList", listId, "name", name, "desc", description);
        return string(api.postMap(endpoint, "cards", query, credentials, "id"), "id");
    }

    CardSnapshot card(String cardId) {
        Map<String, String> query = Map.of(
                "fields",
                "id,idList,closed",
                "actions",
                "commentCard",
                "actions_limit",
                COMMENT_LIMIT,
                "action_fields",
                "id,data,date");
        Map<String, Object> card = api.getMap(endpoint, cardPath(cardId), query, credentials);
        return new CardSnapshot(string(card, "idList"), Boolean.parseBoolean(string(card, "closed")), comments(card));
    }

    String addComment(String cardId, String text) {
        return string(
                api.postMap(endpoint, cardPath(cardId) + "/actions/comments", Map.of("text", text), credentials, "id"),
                "id");
    }

    void updateComment(String commentId, String text) {
        api.putMap(
                endpoint,
                "actions/" + TrelloSetupApi.encodeSegment(commentId) + "/text",
                Map.of("value", text),
                credentials);
    }

    void moveCard(String cardId, String listId) {
        api.putMap(endpoint, cardPath(cardId) + "/idList", Map.of("value", listId), credentials);
    }

    /// Archives the board. Trello's API calls archived boards `closed`.
    void archiveBoard(Board board) {
        api.putMap(
                endpoint, "boards/" + TrelloSetupApi.encodeSegment(board.id()), Map.of("closed", "true"), credentials);
    }

    private static String cardPath(String cardId) {
        return "cards/" + TrelloSetupApi.encodeSegment(cardId);
    }

    private static List<Comment> comments(Map<String, Object> card) {
        if (!(card.get("actions") instanceof List<?> actions)) {
            return List.of();
        }
        return actions.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(TutorialTrello::comment)
                .toList();
    }

    private static Comment comment(Map<?, ?> action) {
        Object id = action.get("id");
        String text = action.get("data") instanceof Map<?, ?> data && data.get("text") != null
                ? data.get("text").toString()
                : "";
        return new Comment(id == null ? "" : id.toString(), text);
    }

    private static String string(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : value.toString();
    }

    record Board(String id, String url) {}

    record Comment(String id, String text) {}

    record CardSnapshot(String listId, boolean archived, List<Comment> comments) {
        CardSnapshot {
            comments = List.copyOf(comments);
        }
    }
}
