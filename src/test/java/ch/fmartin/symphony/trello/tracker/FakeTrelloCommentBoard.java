package ch.fmartin.symphony.trello.tracker;

import static ch.fmartin.symphony.trello.TestHttpExchange.query;
import static ch.fmartin.symphony.trello.TestHttpExchange.respond;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.boardJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.listsJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.trelloList;

import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import jakarta.ws.rs.core.Response.Status;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// Stateful fake of the Trello endpoints the candidate poll uses. Unlike canned responses, comment
/// writes change what later reads return and move the card's comment badge and `dateLastActivity`,
/// so a test can follow one card across several poll ticks.
final class FakeTrelloCommentBoard implements AutoCloseable {
    static final String BOARD_INPUT = "input";
    static final String BOARD_ID = "board-1";
    static final String TODO_LIST = "list-todo";
    static final String PROGRESS_LIST = "list-progress";
    static final String DONE_LIST = "list-done";
    static final String TRELLO_CARD_URL_PREFIX = "https://trello.com/c/";

    // Trello returns 50 actions when a card request names no actions_limit.
    private static final String TRELLO_DEFAULT_ACTIONS_LIMIT = "50";
    private static final Instant FIRST_ACTIVITY = Instant.parse("2026-01-01T00:00:00Z");
    // Gives each generated comment a realistic payload size for the lookup measurement.
    private static final int REVIEW_NOTE_PADDING = 200;

    private static final Pattern CARD_CHECKLISTS = Pattern.compile("/1/cards/([^/]+)/checklists");
    private static final Pattern CARD_COMMENTS = Pattern.compile("/1/cards/([^/]+)/actions/comments");
    private static final Pattern CARD = Pattern.compile("/1/cards/([^/]+)");
    private static final Pattern COMMENT_TEXT = Pattern.compile("/1/actions/([^/]+)/text");

    private final ObjectMapper json = new ObjectMapper();
    private final FakeTrelloServer server = new FakeTrelloServer();
    // Keep insertion order so the board lists cards in the order a test adds them.
    private final Map<String, FakeCard> cards = new LinkedHashMap<>();
    private final List<String> requests = new ArrayList<>();
    private int nextId;
    private long activityClock;
    private boolean rejectCommentWrites;

    FakeTrelloCommentBoard start() throws IOException {
        server.on("/1/", this::handle).startEmpty();
        return this;
    }

    String endpoint() {
        return server.endpoint();
    }

    synchronized FakeCard addCard(String id, String listId) {
        var card = new FakeCard(id, "short" + id, listId);
        card.lastActivity = nextActivity();
        cards.put(id, card);
        return card;
    }

    synchronized List<String> requests() {
        return List.copyOf(requests);
    }

    /// Answers comment writes with HTTP 429, as Trello does when the token is rate limited.
    synchronized void rejectCommentWrites(boolean reject) {
        rejectCommentWrites = reject;
    }

    synchronized void clearRequests() {
        requests.clear();
    }

    @Override
    public void close() {
        server.close();
    }

    static String cardUrl(FakeCard card) {
        return TRELLO_CARD_URL_PREFIX + card.shortLink;
    }

    private synchronized void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        requests.add(method + " " + exchange.getRequestURI());
        Map<String, String> query = query(exchange);
        if (rejectCommentWrites
                && (CARD_COMMENTS.matcher(path).matches()
                        || COMMENT_TEXT.matcher(path).matches())) {
            respond(exchange, Status.TOO_MANY_REQUESTS.getStatusCode(), "{}");
            return;
        }
        String body = route(method, path, query);
        if (body == null) {
            respond(exchange, Status.NOT_FOUND.getStatusCode(), "{}");
        } else {
            respond(exchange, body);
        }
    }

    private @Nullable String route(String method, String path, Map<String, String> query)
            throws JsonProcessingException {
        if ("GET".equals(method) && path.equals("/1/boards/" + BOARD_INPUT)) {
            return boardJson(BOARD_ID, "Board", false);
        }
        if ("GET".equals(method) && path.equals("/1/boards/" + BOARD_ID + "/lists")) {
            return listsJson(
                    trelloList(TODO_LIST, "Todo", 1),
                    trelloList(PROGRESS_LIST, "In Progress", 2),
                    trelloList(DONE_LIST, "Done", 3));
        }
        if ("GET".equals(method) && path.equals("/1/boards/" + BOARD_ID + "/cards/open")) {
            List<Map<String, Object>> payload = new ArrayList<>();
            for (FakeCard card : cards.values()) {
                payload.add(card.fields());
            }
            return json.writeValueAsString(payload);
        }
        Matcher matcher = CARD_CHECKLISTS.matcher(path);
        if ("GET".equals(method) && matcher.matches()) {
            FakeCard card = card(matcher.group(1));
            return card == null ? null : json.writeValueAsString(card.checklists());
        }
        matcher = CARD_COMMENTS.matcher(path);
        if ("POST".equals(method) && matcher.matches()) {
            FakeCard card = card(matcher.group(1));
            return card == null ? null : json.writeValueAsString(Map.of("id", addComment(card, query.get("text"))));
        }
        matcher = COMMENT_TEXT.matcher(path);
        if ("PUT".equals(method) && matcher.matches()) {
            return editComment(matcher.group(1), query.get("value"))
                    ? json.writeValueAsString(Map.of("id", matcher.group(1)))
                    : null;
        }
        matcher = CARD.matcher(path);
        if ("GET".equals(method) && matcher.matches()) {
            FakeCard card = card(matcher.group(1));
            if (card == null) {
                return null;
            }
            Map<String, Object> payload = card.fields();
            payload.put(
                    "actions",
                    card.commentActions(
                            Integer.parseInt(query.getOrDefault("actions_limit", TRELLO_DEFAULT_ACTIONS_LIMIT))));
            return json.writeValueAsString(payload);
        }
        return null;
    }

    private @Nullable FakeCard card(String idOrShortLink) {
        FakeCard byId = cards.get(idOrShortLink);
        if (byId != null) {
            return byId;
        }
        for (FakeCard card : cards.values()) {
            if (card.shortLink.equals(idOrShortLink)) {
                return card;
            }
        }
        return null;
    }

    private String addComment(FakeCard card, String text) {
        nextId++;
        String id = "comment-" + nextId;
        // Trello returns comment actions newest first.
        card.comments.addFirst(new FakeComment(id, text));
        card.lastActivity = nextActivity();
        return id;
    }

    private boolean editComment(String commentId, String text) {
        for (FakeCard card : cards.values()) {
            for (FakeComment comment : card.comments) {
                if (comment.id.equals(commentId)) {
                    comment.text = text;
                    card.lastActivity = nextActivity();
                    return true;
                }
            }
        }
        return false;
    }

    private Instant nextActivity() {
        activityClock++;
        return FIRST_ACTIVITY.plusSeconds(activityClock);
    }

    final class FakeCard {
        private final String id;
        private final String shortLink;
        private final String listId;
        private final List<FakeComment> comments = new ArrayList<>();
        private final List<String> checkItems = new ArrayList<>();
        private Instant lastActivity;

        private FakeCard(String id, String shortLink, String listId) {
            this.id = id;
            this.shortLink = shortLink;
            this.listId = listId;
        }

        String id() {
            return id;
        }

        FakeCard withComments(int count) {
            synchronized (FakeTrelloCommentBoard.this) {
                for (int index = 0; index < count; index++) {
                    addComment(this, "Review note " + index + " for " + id + ". " + "x".repeat(REVIEW_NOTE_PADDING));
                }
            }
            return this;
        }

        FakeCard withComment(String text) {
            synchronized (FakeTrelloCommentBoard.this) {
                addComment(this, text);
            }
            return this;
        }

        // Checklist edits leave the comment badge and last-activity time alone, so tests cannot pass only
        // because Trello may also move dateLastActivity for them.
        FakeCard withChecklistItem(String name) {
            synchronized (FakeTrelloCommentBoard.this) {
                checkItems.add(name);
            }
            return this;
        }

        FakeCard withoutChecklistItems() {
            synchronized (FakeTrelloCommentBoard.this) {
                checkItems.clear();
            }
            return this;
        }

        List<String> commentTexts() {
            synchronized (FakeTrelloCommentBoard.this) {
                List<String> texts = new ArrayList<>();
                for (FakeComment comment : comments) {
                    texts.add(comment.text);
                }
                return texts;
            }
        }

        private Map<String, Object> fields() {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("id", id);
            fields.put("name", "Card " + id);
            fields.put("desc", "");
            fields.put("idList", listId);
            fields.put("idBoard", BOARD_ID);
            fields.put("closed", false);
            fields.put("shortLink", shortLink);
            fields.put("shortUrl", TRELLO_CARD_URL_PREFIX + shortLink);
            fields.put("url", TRELLO_CARD_URL_PREFIX + shortLink);
            fields.put("labels", List.of());
            fields.put("dateLastActivity", lastActivity.toString());
            fields.put("pos", 1);
            fields.put("badges", Map.of("checkItems", checkItems.size(), "comments", comments.size()));
            return fields;
        }

        private List<Map<String, Object>> checklists() {
            if (checkItems.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> items = new ArrayList<>();
            for (int index = 0; index < checkItems.size(); index++) {
                items.add(Map.of("id", id + "-item-" + index, "name", checkItems.get(index), "state", "incomplete"));
            }
            return List.of(Map.of("id", id + "-checklist", "name", "Before starting", "checkItems", items));
        }

        private List<Map<String, Object>> commentActions(int limit) {
            List<Map<String, Object>> actions = new ArrayList<>();
            for (FakeComment comment : comments.subList(0, Math.min(limit, comments.size()))) {
                actions.add(Map.of(
                        "id",
                        comment.id,
                        "date",
                        FIRST_ACTIVITY.toString(),
                        "data",
                        Map.of("text", comment.text),
                        "memberCreator",
                        Map.of("username", "someone")));
            }
            return actions;
        }
    }

    private static final class FakeComment {
        private final String id;
        private String text;

        private FakeComment(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }
}
