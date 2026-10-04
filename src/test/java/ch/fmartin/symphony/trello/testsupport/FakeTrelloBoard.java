package ch.fmartin.symphony.trello.testsupport;

import static ch.fmartin.symphony.trello.TestHttpExchange.query;
import static ch.fmartin.symphony.trello.TestHttpExchange.respond;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/// Stateful synthetic Trello board for tests that create, read, and change cards through the real
/// [ch.fmartin.symphony.trello.tracker.TrelloClient]. It records every request so tests can
/// assert which writes happened and that requests carried the expected authorization.
public final class FakeTrelloBoard implements AutoCloseable {
    public static final String BOARD_ID = "board-1";
    public static final String BOARD_SHORT_LINK = "SYNTH001";

    private final ObjectMapper json = new ObjectMapper();
    private final FakeTrelloServer server = new FakeTrelloServer();
    private final String boardId;
    private final String boardShortLink;
    private final String boardName;
    // Board order matters: Trello returns lists and cards in board position order.
    private final Map<String, ObjectNode> lists = new LinkedHashMap<>();
    private final Map<String, ObjectNode> cards = new LinkedHashMap<>();
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicBoolean rejectCredentials = new AtomicBoolean();

    public FakeTrelloBoard(String boardName) {
        this(BOARD_ID, BOARD_SHORT_LINK, boardName);
    }

    public FakeTrelloBoard(String boardId, String boardShortLink, String boardName) {
        this.boardId = boardId;
        this.boardShortLink = boardShortLink;
        this.boardName = boardName;
    }

    public FakeTrelloBoard start() throws IOException {
        server.on("/1/", this::handle).startEmpty();
        return this;
    }

    public String endpoint() {
        return server.endpoint();
    }

    public FakeTrelloBoard withList(String id, String name) {
        return withList(id, name, false);
    }

    public FakeTrelloBoard withList(String id, String name, boolean closed) {
        synchronized (this) {
            ObjectNode list = json.createObjectNode();
            list.put("id", id).put("name", name).put("closed", closed).put("pos", lists.size() + 1);
            lists.put(id, list);
        }
        return this;
    }

    public FakeTrelloBoard withCard(String id, String shortLink, String name, String listId) {
        return withCard(id, shortLink, name, listId, boardId);
    }

    public FakeTrelloBoard withCard(String id, String shortLink, String name, String listId, String boardId) {
        synchronized (this) {
            ObjectNode card = json.createObjectNode();
            card.put("id", id)
                    .put("shortLink", shortLink)
                    .put("name", name)
                    .put("desc", "")
                    .put("idList", listId)
                    .put("idBoard", boardId)
                    .put("closed", false)
                    .put("url", "https://trello.com/c/" + shortLink + "/synthetic-card")
                    .put("dateLastActivity", "2026-01-02T03:04:05.000Z");
            card.putArray("labels");
            card.putArray("actions");
            card.putArray("checklists");
            cards.put(id, card);
        }
        return this;
    }

    public FakeTrelloBoard withCardDescription(String cardId, String description) {
        synchronized (this) {
            cards.get(cardId).put("desc", description);
        }
        return this;
    }

    public FakeTrelloBoard withComment(String cardId, String author, String text) {
        synchronized (this) {
            addComment(cards.get(cardId), author, text);
        }
        return this;
    }

    public synchronized Optional<ObjectNode> card(String id) {
        return Optional.ofNullable(cards.get(id)).map(ObjectNode::deepCopy);
    }

    public synchronized List<ObjectNode> cards() {
        List<ObjectNode> copies = new ArrayList<>();
        for (ObjectNode card : cards.values()) {
            copies.add(card.deepCopy());
        }
        return copies;
    }

    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    public List<RecordedRequest> writes() {
        List<RecordedRequest> writes = new ArrayList<>();
        for (RecordedRequest request : requests) {
            if (!"GET".equals(request.method())) {
                writes.add(request);
            }
        }
        return writes;
    }

    /// Makes Trello answer every later request with 401, as it does for a revoked token.
    public FakeTrelloBoard rejectingCredentials() {
        rejectCredentials.set(true);
        return this;
    }

    @Override
    public void close() {
        server.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> query = query(exchange);
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        requests.add(new RecordedRequest(
                method, path, query, exchange.getRequestHeaders().getFirst("Authorization")));
        if (rejectCredentials.get()) {
            respond(exchange, 401, "invalid token");
            return;
        }
        List<String> segments = Splitter.on('/').omitEmptyStrings().splitToList(path.substring("/1/".length()));
        Optional<String> body;
        synchronized (this) {
            body = route(method, segments, query);
        }
        if (body.isPresent()) {
            respond(exchange, body.orElseThrow());
        } else {
            respond(exchange, 404, "{\"message\":\"not found\"}");
        }
    }

    private Optional<String> route(String method, List<String> segments, Map<String, String> query) {
        String resource = segments.getFirst();
        return switch (resource) {
            case "members" -> Optional.of("{\"id\":\"member-1\",\"username\":\"alex\",\"fullName\":\"Alex Example\"}");
            case "boards" -> board(segments);
            case "cards" -> cardRoute(method, segments, query);
            case "checklists" -> checklistRoute(method, segments, query);
            default -> Optional.empty();
        };
    }

    private Optional<String> board(List<String> segments) {
        String selector = segments.get(1);
        if (!boardId.equals(selector) && !boardShortLink.equals(selector)) {
            return Optional.empty();
        }
        if (segments.size() == 2) {
            ObjectNode board = json.createObjectNode();
            board.put("id", boardId).put("name", boardName).put("closed", false);
            return Optional.of(board.toString());
        }
        if (segments.size() == 3 && "lists".equals(segments.get(2))) {
            ArrayNode result = json.createArrayNode();
            lists.values().forEach(result::add);
            return Optional.of(result.toString());
        }
        if (segments.size() == 4 && "cards".equals(segments.get(2))) {
            ArrayNode result = json.createArrayNode();
            cards.values().stream()
                    .filter(card -> boardId.equals(card.path("idBoard").asText()))
                    .filter(card -> !card.path("closed").asBoolean())
                    .map(this::cardSummary)
                    .forEach(result::add);
            return Optional.of(result.toString());
        }
        return Optional.empty();
    }

    private Optional<String> cardRoute(String method, List<String> segments, Map<String, String> query) {
        if (segments.size() == 1 && "POST".equals(method)) {
            int number = nextId.getAndIncrement();
            String id = "card-new-" + number;
            withCard(id, "NEWCARD" + number, query.get("name"), query.get("idList"));
            Optional.ofNullable(query.get("desc"))
                    .ifPresent(desc -> cards.get(id).put("desc", desc));
            return Optional.of(cards.get(id).toString());
        }
        Optional<ObjectNode> found = findCard(segments.get(1));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode card = found.orElseThrow();
        if (segments.size() == 2 && "GET".equals(method)) {
            return Optional.of(cardDetails(card, query).toString());
        }
        if (segments.size() == 2 && "PUT".equals(method)) {
            Optional.ofNullable(query.get("name")).ifPresent(name -> card.put("name", name));
            Optional.ofNullable(query.get("desc")).ifPresent(desc -> card.put("desc", desc));
            Optional.ofNullable(query.get("closed"))
                    .ifPresent(closed -> card.put("closed", Boolean.parseBoolean(closed)));
            return Optional.of(card.toString());
        }
        String action = segments.get(2);
        if ("idList".equals(action) && "PUT".equals(method)) {
            card.put("idList", query.get("value"));
            return Optional.of(card.toString());
        }
        if ("actions".equals(action) && "POST".equals(method)) {
            return Optional.of(
                    addComment(card, "Alex Example", query.get("text")).toString());
        }
        if ("checklists".equals(action) && "GET".equals(method)) {
            return Optional.of(card.path("checklists").toString());
        }
        if ("checklists".equals(action) && "POST".equals(method)) {
            ObjectNode checklist = ((ArrayNode) card.get("checklists")).addObject();
            checklist.put("id", "checklist-" + nextId.getAndIncrement()).put("name", query.get("name"));
            checklist.putArray("checkItems");
            return Optional.of(checklist.toString());
        }
        if ("checkItem".equals(action) && "PUT".equals(method)) {
            card.path("checklists")
                    .forEach(checklist -> checklist.path("checkItems").forEach(item -> {
                        if (item.path("id").asText().equals(segments.get(3))) {
                            ((ObjectNode) item).put("state", query.get("state"));
                        }
                    }));
            return Optional.of(card.toString());
        }
        return Optional.empty();
    }

    private Optional<String> checklistRoute(String method, List<String> segments, Map<String, String> query) {
        if (segments.size() != 3 || !"checkItems".equals(segments.get(2)) || !"POST".equals(method)) {
            return Optional.empty();
        }
        for (ObjectNode card : cards.values()) {
            for (var checklist : card.path("checklists")) {
                if (checklist.path("id").asText().equals(segments.get(1))) {
                    ObjectNode item = ((ArrayNode) checklist.get("checkItems")).addObject();
                    item.put("id", "item-" + nextId.getAndIncrement())
                            .put("name", query.get("name"))
                            .put("state", Boolean.parseBoolean(query.get("checked")) ? "complete" : "incomplete");
                    return Optional.of(item.toString());
                }
            }
        }
        return Optional.empty();
    }

    private Optional<ObjectNode> findCard(String reference) {
        return Optional.ofNullable(cards.get(reference)).or(() -> cards.values().stream()
                .filter(card -> reference.equals(card.path("shortLink").asText()))
                .findAny());
    }

    private ObjectNode cardSummary(ObjectNode card) {
        ObjectNode summary = card.deepCopy();
        summary.remove(List.of("actions", "checklists"));
        return summary;
    }

    private ObjectNode cardDetails(ObjectNode card, Map<String, String> query) {
        ObjectNode details = card.deepCopy();
        if (!"all".equals(query.get("checklists"))) {
            details.remove("checklists");
        }
        return details;
    }

    private ObjectNode addComment(ObjectNode card, String author, String text) {
        var actions = (ArrayNode) card.get("actions");
        ObjectNode action = json.createObjectNode();
        action.put("id", "action-" + nextId.getAndIncrement()).put("date", "2026-01-02T03:04:05.000Z");
        action.putObject("data").put("text", text);
        action.putObject("memberCreator").put("fullName", author);
        // Trello returns comment actions newest first.
        List<ObjectNode> existing = new ArrayList<>();
        actions.forEach(node -> existing.add((ObjectNode) node));
        actions.removeAll();
        actions.add(action);
        existing.forEach(actions::add);
        return action;
    }

    public record RecordedRequest(String method, String path, Map<String, String> query, String authorization) {
        public RecordedRequest {
            query = Map.copyOf(query);
        }
    }
}
