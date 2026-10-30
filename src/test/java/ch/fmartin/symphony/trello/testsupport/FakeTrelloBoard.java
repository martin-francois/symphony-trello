package ch.fmartin.symphony.trello.testsupport;

import static ch.fmartin.symphony.trello.TestHttpExchange.query;

import ch.fmartin.symphony.trello.TestHttpExchange;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/// Stateful in-memory Trello board for one fake API server. It keeps lists, cards, labels,
/// attachments, checklists, and comments, so a test can check the board state that a sequence
/// of Trello writes leaves behind instead of only the individual requests.
///
/// Register it with `server.on("/1/", board)`. Requests outside the supported routes answer 404.
public final class FakeTrelloBoard implements HttpHandler {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String boardId;
    private final List<BoardList> lists = new CopyOnWriteArrayList<>();
    private final List<FakeCard> cards = new CopyOnWriteArrayList<>();
    private final List<Label> labels = new CopyOnWriteArrayList<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> failures = new LinkedHashMap<>();
    private int idCounter = 1;

    public FakeTrelloBoard(String boardId) {
        this.boardId = boardId;
    }

    public synchronized FakeTrelloBoard list(String id, String name) {
        lists.add(new BoardList(id, name, false));
        return this;
    }

    public synchronized FakeTrelloBoard label(String id, String name) {
        labels.add(new Label(id, name, "green"));
        return this;
    }

    public synchronized FakeCard card(String id, String shortLink, String name, String listId) {
        var card = new FakeCard(id, shortLink, name, listId, boardId);
        cards.add(card);
        return card;
    }

    /// Seeds a card that belongs to another board but answers on this server's card routes, as a
    /// card on another board of the same Trello account does. Board routes never list it.
    public synchronized FakeCard foreignCard(String id, String shortLink, String name, String otherBoardId) {
        var card = new FakeCard(id, shortLink, name, "list-on-" + otherBoardId, otherBoardId);
        cards.add(card);
        return card;
    }

    /// Makes the next `count` requests whose `METHOD /path` starts with `requestPrefix` fail with
    /// HTTP 500 before any state changes.
    public synchronized FakeTrelloBoard failNext(String requestPrefix, int count) {
        failures.put(requestPrefix, count);
        return this;
    }

    public Optional<FakeCard> cardByName(String name) {
        for (FakeCard card : cards) {
            if (card.name.equals(name)) {
                return Optional.of(card);
            }
        }
        return Optional.empty();
    }

    /// Cards created through the API, in creation order. Cards a test seeded are not included.
    public List<FakeCard> createdCards() {
        List<FakeCard> created = new ArrayList<>();
        for (FakeCard card : cards) {
            if (card.createdThroughApi) {
                created.add(card);
            }
        }
        return created;
    }

    public List<Label> labels() {
        return List.copyOf(labels);
    }

    /// Every request as `METHOD /path`, in arrival order.
    public List<String> requests() {
        return List.copyOf(requests);
    }

    public List<String> writeRequests() {
        List<String> writes = new ArrayList<>();
        for (String request : requests) {
            if (!request.startsWith("GET ")) {
                writes.add(request);
            }
        }
        return writes;
    }

    @Override
    public synchronized void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath().substring("/1/".length());
        String request = method + " /" + path;
        requests.add(request);
        for (Map.Entry<String, Integer> failure : failures.entrySet()) {
            if (request.startsWith(failure.getKey()) && failure.getValue() > 0) {
                failure.setValue(failure.getValue() - 1);
                TestHttpExchange.respond(exchange, 500, "{\"message\":\"injected failure\"}");
                return;
            }
        }
        Map<String, String> query = query(exchange);
        List<String> segments = Arrays.asList(path.split("/"));
        Object response = route(method, segments, query);
        if (response == null) {
            TestHttpExchange.respond(exchange, 404, "{\"message\":\"not found\"}");
            return;
        }
        TestHttpExchange.respond(exchange, json(response));
    }

    private Object route(String method, List<String> segments, Map<String, String> query) {
        String head = segments.getFirst();
        return switch (head) {
            case "boards" -> boardRoute(method, segments);
            case "labels" -> "POST".equals(method) && segments.size() == 1 ? createLabel(query) : null;
            case "cards" -> cardRoute(method, segments, query);
            case "checklists" -> checklistRoute(method, segments, query);
            case "actions" -> actionRoute(method, segments, query);
            default -> null;
        };
    }

    private Object actionRoute(String method, List<String> segments, Map<String, String> query) {
        if (!"PUT".equals(method) || segments.size() != 3 || !"text".equals(segments.get(2))) {
            return null;
        }
        for (FakeCard card : cards) {
            for (int index = 0; index < card.comments.size(); index++) {
                Comment comment = card.comments.get(index);
                if (comment.id().equals(segments.get(1))) {
                    card.comments.set(index, new Comment(comment.id(), query.get("value")));
                    return Map.of("id", comment.id());
                }
            }
        }
        return null;
    }

    private Object boardRoute(String method, List<String> segments) {
        if ("GET".equals(method) && segments.size() == 2 && boardId.equals(segments.get(1))) {
            return Map.of("id", boardId, "name", "Synthetic board", "closed", false);
        }
        if (!"GET".equals(method) || segments.size() < 3 || !boardId.equals(segments.get(1))) {
            return null;
        }
        String tail = String.join("/", segments.subList(2, segments.size()));
        List<Object> payload = new ArrayList<>();
        switch (tail) {
            case "lists" -> {
                for (BoardList list : lists) {
                    payload.add(list.json());
                }
            }
            case "labels" -> {
                for (Label label : labels) {
                    payload.add(label.json());
                }
            }
            case "cards/open" -> {
                for (FakeCard card : cards) {
                    if (card.boardId.equals(boardId) && !card.closed) {
                        payload.add(card.json());
                    }
                }
            }
            default -> {
                return null;
            }
        }
        return payload;
    }

    private Object createLabel(Map<String, String> query) {
        if (!boardId.equals(query.get("idBoard"))) {
            return null;
        }
        var label = new Label("label-" + nextId(), query.get("name"), query.get("color"));
        labels.add(label);
        return label.json();
    }

    private Object cardRoute(String method, List<String> segments, Map<String, String> query) {
        if (segments.size() == 1) {
            return "POST".equals(method) ? createCard(query) : null;
        }
        FakeCard card = null;
        for (FakeCard candidate : cards) {
            if (candidate.id.equals(segments.get(1)) || candidate.shortLink.equals(segments.get(1))) {
                card = candidate;
            }
        }
        if (card == null) {
            return null;
        }
        String tail = String.join("/", segments.subList(2, segments.size()));
        return switch (method + " " + tail) {
            case "GET " -> card.json(query);
            case "PUT " -> {
                if (!"true".equals(query.get("closed"))) {
                    yield null;
                }
                card.closed = true;
                yield card.json();
            }
            case "GET attachments" -> {
                List<Object> payload = new ArrayList<>();
                for (Attachment attachment : card.attachments) {
                    payload.add(attachment.json());
                }
                yield payload;
            }
            case "POST attachments" -> {
                var attachment = new Attachment("attachment-" + nextId(), query.get("name"), query.get("url"));
                card.attachments.add(attachment);
                yield List.of(attachment.json());
            }
            case "GET checklists" -> {
                List<Object> payload = new ArrayList<>();
                for (Checklist checklist : card.checklists) {
                    payload.add(checklist.json());
                }
                yield payload;
            }
            case "POST checklists" -> {
                var checklist = new Checklist("checklist-" + nextId(), query.get("name"));
                card.checklists.add(checklist);
                yield checklist.json();
            }
            case "POST idLabels" -> {
                if (!hasLabel(query.get("value"))) {
                    yield null;
                }
                card.labelIds.add(query.get("value"));
                yield card.labelIds;
            }
            case "PUT idList" -> {
                if (openList(query.get("value")) == null) {
                    yield null;
                }
                card.listId = query.get("value");
                yield card.json();
            }
            case "POST actions/comments" -> {
                String actionId = "action-" + nextId();
                card.comments.addFirst(new Comment(actionId, query.get("text")));
                yield Map.of("id", actionId);
            }
            default -> null;
        };
    }

    private Object createCard(Map<String, String> query) {
        String listId = query.get("idList");
        if (openList(listId) == null) {
            return null;
        }
        int number = nextId();
        var card = new FakeCard("card-" + number, "Fu%06d".formatted(number), query.get("name"), listId, boardId);
        card.description = query.getOrDefault("desc", "");
        card.createdThroughApi = true;
        String labelIds = query.get("idLabels");
        if (labelIds != null && !labelIds.isBlank()) {
            card.labelIds.addAll(Arrays.asList(labelIds.split(",")));
        }
        cards.add(card);
        return card.json();
    }

    private Object checklistRoute(String method, List<String> segments, Map<String, String> query) {
        if (!"POST".equals(method) || segments.size() != 3 || !"checkItems".equals(segments.get(2))) {
            return null;
        }
        for (FakeCard card : cards) {
            for (Checklist checklist : card.checklists) {
                if (checklist.id.equals(segments.get(1))) {
                    var item = new ChecklistItem(
                            "item-" + nextId(), query.get("name"), Boolean.parseBoolean(query.get("checked")));
                    checklist.items.add(item);
                    return Map.of("id", item.id());
                }
            }
        }
        return null;
    }

    private boolean hasLabel(String labelId) {
        for (Label label : labels) {
            if (label.id().equals(labelId)) {
                return true;
            }
        }
        return false;
    }

    private BoardList openList(String listId) {
        for (BoardList list : lists) {
            if (list.id().equals(listId) && !list.closed()) {
                return list;
            }
        }
        return null;
    }

    private int nextId() {
        int id = idCounter;
        idCounter = id + 1;
        return id;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class FakeCard {
        private final String id;
        private final String shortLink;
        private final String name;
        private final List<String> labelIds = new CopyOnWriteArrayList<>();
        private final List<Attachment> attachments = new CopyOnWriteArrayList<>();
        private final List<Checklist> checklists = new CopyOnWriteArrayList<>();
        private final List<Comment> comments = new CopyOnWriteArrayList<>();
        private final String boardId;
        private volatile String listId;
        private volatile String description = "";
        private volatile boolean createdThroughApi;
        private volatile boolean closed;

        private FakeCard(String id, String shortLink, String name, String listId, String boardId) {
            this.boardId = boardId;
            this.id = id;
            this.shortLink = shortLink;
            this.name = name;
            this.listId = listId;
        }

        public FakeCard archive() {
            closed = true;
            return this;
        }

        public boolean archived() {
            return closed;
        }

        public FakeCard description(String text) {
            description = text;
            return this;
        }

        /// Moves the card the way a person would in the Trello UI.
        public FakeCard moveTo(String targetListId) {
            listId = targetListId;
            return this;
        }

        public FakeCard attachment(String name, String url) {
            attachments.add(new Attachment("attachment-" + id + "-" + attachments.size(), name, url));
            return this;
        }

        public FakeCard checklist(String name, String... items) {
            var checklist = new Checklist("checklist-" + id + "-" + checklists.size(), name);
            for (String item : items) {
                checklist.items.add(new ChecklistItem("item-" + id + "-" + checklist.items.size(), item, false));
            }
            checklists.add(checklist);
            return this;
        }

        public String id() {
            return id;
        }

        public String shortLink() {
            return shortLink;
        }

        public String url() {
            return "https://trello.com/c/" + shortLink;
        }

        public String name() {
            return name;
        }

        public String description() {
            return description;
        }

        public String listId() {
            return listId;
        }

        public List<String> labelIds() {
            return List.copyOf(labelIds);
        }

        public List<Attachment> attachments() {
            return List.copyOf(attachments);
        }

        public List<Checklist> checklists() {
            return List.copyOf(checklists);
        }

        public FakeCard comment(String text) {
            comments.addFirst(new Comment("comment-" + id + "-" + comments.size(), text));
            return this;
        }

        public List<String> comments() {
            List<String> texts = new ArrayList<>();
            for (Comment comment : comments) {
                texts.add(comment.text());
            }
            return List.copyOf(texts);
        }

        /// The card as `GET /cards/{id}` returns it: checklists and attachments only when the query
        /// asks for them, as Trello does.
        private Map<String, Object> json(Map<String, String> query) {
            Map<String, Object> json = json();
            if ("all".equals(query.get("checklists"))) {
                List<Object> checklistPayload = new ArrayList<>();
                for (Checklist checklist : checklists) {
                    checklistPayload.add(checklist.json());
                }
                json.put("checklists", checklistPayload);
            }
            if ("true".equals(query.get("attachments"))) {
                List<Object> attachmentPayload = new ArrayList<>();
                for (Attachment attachment : attachments) {
                    attachmentPayload.add(attachment.json());
                }
                json.put("attachments", attachmentPayload);
            }
            return json;
        }

        private Map<String, Object> json() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("shortLink", shortLink);
            json.put("name", name);
            json.put("desc", description);
            json.put("idBoard", boardId);
            List<Object> actions = new ArrayList<>();
            for (Comment comment : comments) {
                actions.add(Map.of(
                        "id",
                        comment.id(),
                        "type",
                        "commentCard",
                        "date",
                        "2026-01-01T00:00:00Z",
                        "data",
                        Map.of("text", comment.text())));
            }
            json.put("actions", actions);
            json.put("idList", listId);
            json.put("idLabels", List.copyOf(labelIds));
            json.put("closed", closed);
            json.put("url", url() + "/1-slug");
            return json;
        }
    }

    private record Comment(String id, String text) {}

    public record BoardList(String id, String name, boolean closed) {
        private Map<String, Object> json() {
            return Map.of("id", id, "name", name, "closed", closed, "pos", 1);
        }
    }

    public record Label(String id, String name, String color) {
        private Map<String, Object> json() {
            return Map.of("id", id, "name", name, "color", color);
        }
    }

    public record Attachment(String id, String name, String url) {
        private Map<String, Object> json() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("id", id);
            json.put("name", name == null ? "" : name);
            json.put("url", url);
            return json;
        }
    }

    public static final class Checklist {
        private final String id;
        private final String name;
        private final List<ChecklistItem> items = new ArrayList<>();

        private Checklist(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String name() {
            return name;
        }

        public List<ChecklistItem> items() {
            return List.copyOf(items);
        }

        private Map<String, Object> json() {
            List<Object> checkItems = new ArrayList<>();
            for (ChecklistItem item : items) {
                checkItems.add(item.json());
            }
            return Map.of("id", id, "name", name, "checkItems", checkItems);
        }
    }

    public record ChecklistItem(String id, String name, boolean complete) {
        private Map<String, Object> json() {
            return Map.of("id", id, "name", name, "state", complete ? "complete" : "incomplete");
        }
    }
}
