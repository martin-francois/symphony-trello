package ch.fmartin.symphony.trello.tracker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.jspecify.annotations.Nullable;

/// One Trello board held in memory and served to a [TrelloClient] through its package-private transport
/// seam. It answers the requests the Trello handoff tools send and records every write request, including
/// writes it rejects, with the card that owned the written resource when the request arrived. Fuzz targets
/// use it so that each execution runs the real client without a socket or a file.
public final class InMemoryTrelloBoard {
    /// The endpoint a configuration must use so its requests reach this board. Nothing resolves the host.
    public static final String ENDPOINT = "https://trello.invalid/1";
    /// Trello card links start with this prefix and end with the card's short link.
    public static final String CARD_URL_PREFIX = "https://trello.com/c/";

    private static final String API_PATH_PREFIX = URI.create(ENDPOINT).getPath() + "/";
    private static final Splitter PATH_SEGMENTS = Splitter.on('/');
    private static final Splitter QUERY_PARAMETERS = Splitter.on('&').omitEmptyStrings();
    private static final HttpHeaders NO_HEADERS = HttpHeaders.of(Map.of(), (name, value) -> true);
    private static final int OK = 200;
    private static final int NOT_FOUND = 404;
    private static final String EMPTY_OBJECT = "{}";

    private final ObjectMapper json = new ObjectMapper();
    private final String boardId;
    private final List<TrelloClient.BoardList> lists = new ArrayList<>();
    private final Map<String, CardState> cards = new HashMap<>();
    // Owning card id by resource id, so a write to a comment, checklist, or check item resolves its card.
    private final Map<String, String> commentOwners = new HashMap<>();
    private final Map<String, String> checklistOwners = new HashMap<>();
    private final Map<String, String> checkItemOwners = new HashMap<>();
    private final Map<String, ChecklistState> checklists = new HashMap<>();
    private final List<Write> writes = new ArrayList<>();
    private final Map<Integer, Integer> injectedStatuses = new HashMap<>();
    private int requests;
    private int failedRequests;
    private int nextId;

    public InMemoryTrelloBoard(String boardId) {
        this.boardId = boardId;
    }

    public InMemoryTrelloBoard list(String id, String name, boolean closed) {
        lists.add(new TrelloClient.BoardList(id, name, closed));
        return this;
    }

    public InMemoryTrelloBoard card(String id, String shortLink, String title, String listId) {
        cards.put(id, new CardState(id, shortLink, title, listId));
        return this;
    }

    /// Adds a comment below the card's existing comments, because Trello lists comments newest first.
    public InMemoryTrelloBoard olderComment(String cardId, String actionId, String text) {
        cards.get(cardId).comments().add(new CommentState(actionId, text));
        ownComment(cardId, actionId);
        return this;
    }

    public InMemoryTrelloBoard checklist(String cardId, String checklistId, String name) {
        addChecklist(cardId, checklistId, name);
        return this;
    }

    public InMemoryTrelloBoard checkItem(String checklistId, String itemId, String name, boolean complete) {
        addCheckItem(checklistId, itemId, name, complete);
        return this;
    }

    /// Answers the request with the given ordinal, counted from 1, with an error status instead of serving it.
    public InMemoryTrelloBoard failRequest(int ordinal, int status) {
        injectedStatuses.put(ordinal, status);
        return this;
    }

    public TrelloClient client() {
        return new TrelloClient(json, this::send);
    }

    public List<Write> writes() {
        return List.copyOf(writes);
    }

    /// Whether the board answered any request with an injected error status.
    public boolean failedAnyRequest() {
        return failedRequests > 0;
    }

    public List<TrelloClient.BoardList> lists() {
        return List.copyOf(lists);
    }

    private HttpResponse<String> send(HttpRequest request) {
        requests++;
        Request parsed = Request.of(request);
        Integer injectedStatus = injectedStatuses.get(requests);
        if (injectedStatus != null) {
            failedRequests++;
            if (!parsed.isRead()) {
                writes.add(describeWrite(parsed, false));
            }
            return new Response(request, injectedStatus, EMPTY_OBJECT);
        }
        if (parsed.isRead()) {
            return read(request, parsed);
        }
        Write write = describeWrite(parsed, true);
        writes.add(write);
        return apply(request, parsed, write);
    }

    private HttpResponse<String> read(HttpRequest request, Request parsed) {
        List<String> path = parsed.path();
        if (matches(path, "boards", boardId)) {
            return ok(
                    request,
                    JsonNodeFactory.instance
                            .objectNode()
                            .put("id", boardId)
                            .put("name", "Board")
                            .put("closed", false));
        }
        if (matches(path, "boards", boardId, "lists")) {
            ArrayNode body = JsonNodeFactory.instance.arrayNode();
            for (int index = 0; index < lists.size(); index++) {
                TrelloClient.BoardList list = lists.get(index);
                body.addObject()
                        .put("id", list.id())
                        .put("name", list.name())
                        .put("closed", list.closed())
                        .put("pos", index);
            }
            return ok(request, body);
        }
        CardState card = path.size() >= 2 && path.getFirst().equals("cards") ? cards.get(path.get(1)) : null;
        if (card == null) {
            return new Response(request, NOT_FOUND, EMPTY_OBJECT);
        }
        if (path.size() == 2) {
            return ok(request, cardJson(card, parsed.query()));
        }
        if (matches(path, "cards", card.id(), "checklists")) {
            return ok(request, checklistsJson(card));
        }
        return new Response(request, NOT_FOUND, EMPTY_OBJECT);
    }

    private Write describeWrite(Request request, boolean applied) {
        List<String> path = request.path();
        Map<String, String> query = request.query();
        String method = request.method();
        if (method.equals("POST") && matches(path, "cards", null, "actions", "comments")) {
            return cardWrite(WriteKind.COMMENT_CREATE, path.get(1), query.get("text"), applied);
        }
        if (method.equals("PUT") && matches(path, "actions", null, "text")) {
            return commentWrite(WriteKind.COMMENT_UPDATE, path.get(1), query.get("value"), applied);
        }
        if (method.equals("DELETE") && matches(path, "actions", null)) {
            return commentWrite(WriteKind.COMMENT_DELETE, path.get(1), null, applied);
        }
        if (method.equals("PUT") && matches(path, "cards", null, "idList")) {
            return cardWrite(WriteKind.CARD_MOVE, path.get(1), query.get("value"), applied);
        }
        if (method.equals("POST") && matches(path, "cards", null, "checklists")) {
            return cardWrite(WriteKind.CHECKLIST_CREATE, path.get(1), query.get("name"), applied);
        }
        if (method.equals("POST") && matches(path, "checklists", null, "checkItems")) {
            return new Write(
                    WriteKind.CHECK_ITEM_CREATE,
                    checklistOwners.get(path.get(1)),
                    path.get(1),
                    query.get("name"),
                    null,
                    applied);
        }
        if (method.equals("PUT") && matches(path, "cards", null, "checkItem", null)) {
            String owner = checkItemOwners.get(path.get(3));
            // The path names a card, but the write lands on the card that owns the item.
            String target = path.get(1).equals(owner) ? owner : null;
            return new Write(WriteKind.CHECK_ITEM_UPDATE, target, path.get(3), query.get("state"), null, applied);
        }
        if (method.equals("POST") && matches(path, "cards", null, "attachments")) {
            return cardWrite(WriteKind.URL_ATTACHMENT_CREATE, path.get(1), query.get("url"), applied);
        }
        return new Write(WriteKind.UNKNOWN, null, method + " " + String.join("/", path), null, null, applied);
    }

    private static Write cardWrite(WriteKind kind, String cardId, @Nullable String value, boolean applied) {
        return new Write(kind, cardId, cardId, value, null, applied);
    }

    private Write commentWrite(WriteKind kind, String actionId, @Nullable String value, boolean applied) {
        String owner = commentOwners.get(actionId);
        CommentState comment = owner == null ? null : comment(cards.get(owner), actionId);
        return new Write(kind, owner, actionId, value, comment == null ? null : comment.text(), applied);
    }

    private HttpResponse<String> apply(HttpRequest request, Request parsed, Write write) {
        CardState card = write.cardId() == null ? null : cards.get(write.cardId());
        if (card == null) {
            return new Response(request, NOT_FOUND, EMPTY_OBJECT);
        }
        Map<String, String> query = parsed.query();
        return switch (write.kind()) {
            case COMMENT_CREATE -> {
                String actionId = newId("action");
                card.comments().addFirst(new CommentState(actionId, query.get("text")));
                ownComment(card.id(), actionId);
                yield created(request, actionId);
            }
            case COMMENT_UPDATE -> {
                String actionId = write.resourceId();
                card.comments()
                        .replaceAll(comment -> actionId.equals(comment.id())
                                ? new CommentState(actionId, query.get("value"))
                                : comment);
                yield created(request, actionId);
            }
            case COMMENT_DELETE -> {
                card.comments().remove(comment(card, write.resourceId()));
                commentOwners.remove(write.resourceId());
                yield new Response(request, OK, EMPTY_OBJECT);
            }
            case CARD_MOVE -> {
                card.listId = query.get("value");
                yield created(request, card.id());
            }
            case CHECKLIST_CREATE -> {
                String checklistId = newId("checklist");
                addChecklist(card.id(), checklistId, query.get("name"));
                yield created(request, checklistId);
            }
            case CHECK_ITEM_CREATE -> {
                String itemId = newId("check-item");
                addCheckItem(write.resourceId(), itemId, query.get("name"), "true".equals(query.get("checked")));
                yield created(request, itemId);
            }
            case CHECK_ITEM_UPDATE -> {
                boolean complete = "complete".equals(query.get("state"));
                for (ChecklistState checklist : card.checklists()) {
                    checklist
                            .items()
                            .replaceAll(item -> item.id().equals(write.resourceId())
                                    ? new CheckItemState(item.id(), item.name(), complete)
                                    : item);
                }
                yield created(request, write.resourceId());
            }
            case URL_ATTACHMENT_CREATE -> {
                ArrayNode body = JsonNodeFactory.instance.arrayNode();
                body.addObject().put("id", newId("attachment"));
                yield ok(request, body);
            }
            case UNKNOWN -> new Response(request, NOT_FOUND, EMPTY_OBJECT);
        };
    }

    private void ownComment(String cardId, String actionId) {
        // Trello has no action with a blank id, so a write to one targets nothing on the board.
        if (!actionId.isBlank()) {
            commentOwners.put(actionId, cardId);
        }
    }

    private void addChecklist(String cardId, String checklistId, String name) {
        var checklist = new ChecklistState(checklistId, name, new ArrayList<>());
        cards.get(cardId).checklists().add(checklist);
        checklists.put(checklistId, checklist);
        checklistOwners.put(checklistId, cardId);
    }

    private void addCheckItem(String checklistId, String itemId, String name, boolean complete) {
        checklists.get(checklistId).items().add(new CheckItemState(itemId, name, complete));
        checkItemOwners.put(itemId, checklistOwners.get(checklistId));
    }

    private static @Nullable CommentState comment(CardState card, String actionId) {
        for (CommentState comment : card.comments()) {
            if (actionId.equals(comment.id())) {
                return comment;
            }
        }
        return null;
    }

    private ObjectNode cardJson(CardState card, Map<String, String> query) {
        ObjectNode body = JsonNodeFactory.instance
                .objectNode()
                .put("id", card.id())
                .put("name", card.title())
                .put("desc", "")
                .put("idList", card.listId)
                .put("idBoard", boardId)
                .put("closed", false)
                .put("idShort", 1)
                .put("shortLink", card.shortLink())
                .put("url", CARD_URL_PREFIX + card.shortLink());
        body.putArray("labels");
        if ("commentCard".equals(query.get("actions"))) {
            int limit = Integer.parseInt(query.getOrDefault("actions_limit", "50"));
            boolean withIds = query.getOrDefault("action_fields", "").contains("id");
            ArrayNode actions = body.putArray("actions");
            for (CommentState comment :
                    card.comments().subList(0, Math.min(limit, card.comments().size()))) {
                ObjectNode action = actions.addObject();
                if (withIds) {
                    action.put("id", comment.id());
                }
                action.putObject("data").put("text", comment.text());
            }
        }
        if ("all".equals(query.get("checklists"))) {
            body.set("checklists", checklistsJson(card));
        }
        return body;
    }

    private static ArrayNode checklistsJson(CardState card) {
        ArrayNode body = JsonNodeFactory.instance.arrayNode();
        for (ChecklistState checklist : card.checklists()) {
            ObjectNode checklistJson =
                    body.addObject().put("id", checklist.id()).put("name", checklist.name());
            ArrayNode items = checklistJson.putArray("checkItems");
            for (CheckItemState item : checklist.items()) {
                items.addObject()
                        .put("id", item.id())
                        .put("name", item.name())
                        .put("state", item.complete() ? "complete" : "incomplete");
            }
        }
        return body;
    }

    /// Whether the path has the expected segments. A `null` segment matches any value.
    private static boolean matches(List<String> path, @Nullable String... expected) {
        if (path.size() != expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (expected[index] != null && !expected[index].equals(path.get(index))) {
                return false;
            }
        }
        return true;
    }

    private String newId(String prefix) {
        nextId++;
        return prefix + "-new-" + nextId;
    }

    private HttpResponse<String> created(HttpRequest request, String id) {
        return ok(request, JsonNodeFactory.instance.objectNode().put("id", id));
    }

    private HttpResponse<String> ok(HttpRequest request, Object body) {
        try {
            return new Response(request, OK, json.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("in-memory Trello response could not be serialized", e);
        }
    }

    /// The kind of Trello write a request asks for. `UNKNOWN` is any write request the handoff tools are
    /// not expected to send.
    public enum WriteKind {
        COMMENT_CREATE,
        COMMENT_UPDATE,
        COMMENT_DELETE,
        CARD_MOVE,
        CHECKLIST_CREATE,
        CHECK_ITEM_CREATE,
        CHECK_ITEM_UPDATE,
        URL_ATTACHMENT_CREATE,
        UNKNOWN
    }

    /// One write request. `cardId` is the card the write lands on: the card in the request path for card
    /// writes, or the card that owned the comment, checklist, or check item when the request arrived. It is
    /// `null` when no card owns that resource. `resourceId` is the card, comment action, checklist, or check
    /// item id from the request path. `value` is the written text, list id, name, state, or URL.
    /// `previousText` is the text of an updated or deleted comment before the request. `applied` is false
    /// when the board answered the request with an injected error status.
    public record Write(
            WriteKind kind,
            @Nullable String cardId,
            String resourceId,
            @Nullable String value,
            @Nullable String previousText,
            boolean applied) {}

    private record Request(String method, List<String> path, Map<String, String> query) {
        static Request of(HttpRequest request) {
            URI uri = request.uri();
            String rawPath = uri.getRawPath();
            String apiPath =
                    rawPath.startsWith(API_PATH_PREFIX) ? rawPath.substring(API_PATH_PREFIX.length()) : rawPath;
            List<String> path = new ArrayList<>();
            for (String segment : PATH_SEGMENTS.split(apiPath)) {
                path.add(decode(segment));
            }
            Map<String, String> query = new HashMap<>();
            if (uri.getRawQuery() != null) {
                for (String parameter : QUERY_PARAMETERS.split(uri.getRawQuery())) {
                    int separator = parameter.indexOf('=');
                    query.put(decode(parameter.substring(0, separator)), decode(parameter.substring(separator + 1)));
                }
            }
            return new Request(request.method(), List.copyOf(path), Map.copyOf(query));
        }

        boolean isRead() {
            return method.equals("GET");
        }
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static final class CardState {
        private final String id;
        private final String shortLink;
        private final String title;
        private final List<CommentState> comments = new ArrayList<>();
        private final List<ChecklistState> checklists = new ArrayList<>();
        private String listId;

        CardState(String id, String shortLink, String title, String listId) {
            this.id = id;
            this.shortLink = shortLink;
            this.title = title;
            this.listId = listId;
        }

        String id() {
            return id;
        }

        String shortLink() {
            return shortLink;
        }

        String title() {
            return title;
        }

        List<CommentState> comments() {
            return comments;
        }

        List<ChecklistState> checklists() {
            return checklists;
        }
    }

    private record CommentState(String id, String text) {}

    private record ChecklistState(String id, String name, List<CheckItemState> items) {}

    private record CheckItemState(String id, String name, boolean complete) {}

    private record Response(HttpRequest request, int statusCode, String body) implements HttpResponse<String> {
        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return NO_HEADERS;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
