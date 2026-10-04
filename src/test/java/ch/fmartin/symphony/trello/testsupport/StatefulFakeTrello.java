package ch.fmartin.symphony.trello.testsupport;

import static ch.fmartin.symphony.trello.TestHttpExchange.query;

import ch.fmartin.symphony.trello.TestHttpExchange;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/// Stateful stand-in for the Trello REST calls that board setup and the guided tutorial make.
///
/// Unlike [FakeTrelloServer], which answers canned payloads, this fake keeps boards, lists, cards,
/// and comments, so a test can act as the person moving cards in the Trello UI while the code under
/// test polls the board. Routes it does not model answer HTTP 404.
public final class StatefulFakeTrello implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MEMBER_ID = "000000000000000000000099";

    // Trello lists boards, lists, and comments in creation order, so the fake keeps insertion order.
    private final Map<String, Board> boards = new LinkedHashMap<>();
    private final Map<String, TrelloList> lists = new LinkedHashMap<>();
    private final Map<String, Card> cards = new LinkedHashMap<>();
    private final Map<String, Comment> comments = new LinkedHashMap<>();
    private final List<Workspace> workspaces = new ArrayList<>(List.of(new Workspace("workspace-1", "Engineering")));
    private final List<String> requests = new ArrayList<>();
    // Injected failures fire in the order the test registered them.
    private final Map<String, Integer> failures = new LinkedHashMap<>();
    private Consumer<String> beforeCardRead = cardId -> {};
    private int nextId = 1;
    private HttpServer server;

    public StatefulFakeTrello start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/1/", this::handle);
        server.start();
        return this;
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    public URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/1");
    }

    public synchronized StatefulFakeTrello givenWorkspaces(String... displayNames) {
        workspaces.clear();
        for (int i = 0; i < displayNames.length; i++) {
            workspaces.add(new Workspace("workspace-" + (i + 1), displayNames[i]));
        }
        return this;
    }

    /// Creates a board the way a person would, for example a board that setup connected earlier.
    public synchronized String givenBoard(String name) {
        return createBoard(name, workspaces.getFirst().id()).id;
    }

    /// Runs before the fake answers a card read, so a test can change the card between two polls.
    public synchronized void beforeCardRead(Consumer<String> action) {
        beforeCardRead = action;
    }

    /// Answers the next request whose method and path start match with the given HTTP status.
    public synchronized void failNext(String method, String pathPrefix, int statusCode) {
        failures.put(method + " " + pathPrefix, statusCode);
    }

    public synchronized List<String> boardNames() {
        List<String> names = new ArrayList<>();
        for (Board board : boards.values()) {
            names.add(board.name);
        }
        return names;
    }

    public synchronized String boardIdNamed(String name) {
        return boards.values().stream()
                .filter(board -> board.name.equals(name))
                .map(board -> board.id)
                .reduce((first, second) -> {
                    throw new IllegalStateException("More than one board is named " + name);
                })
                .orElseThrow(() -> new IllegalStateException("No board is named " + name));
    }

    public synchronized boolean archived(String boardId) {
        return board(boardId).closed;
    }

    public synchronized String workspaceOf(String boardId) {
        return board(boardId).workspaceId;
    }

    public synchronized List<String> listNames(String boardId) {
        List<String> names = new ArrayList<>();
        for (TrelloList list : lists.values()) {
            if (list.boardId.equals(boardId)) {
                names.add(list.name);
            }
        }
        return names;
    }

    public synchronized String onlyCardId(String boardId) {
        List<String> ids = cards.values().stream()
                .filter(card -> card.boardId.equals(boardId))
                .map(card -> card.id)
                .toList();
        if (ids.size() != 1) {
            throw new IllegalStateException("Expected one card on " + boardId + " but found " + ids);
        }
        return ids.getFirst();
    }

    public synchronized String cardName(String cardId) {
        return card(cardId).name;
    }

    public synchronized String listOf(String cardId) {
        return lists.get(card(cardId).listId).name;
    }

    /// Comment texts on the card, oldest first.
    public synchronized List<String> commentTexts(String cardId) {
        List<String> texts = new ArrayList<>();
        for (Comment comment : comments.values()) {
            if (comment.cardId.equals(cardId)) {
                texts.add(comment.text);
            }
        }
        return texts;
    }

    public synchronized void moveCard(String cardId, String listName) {
        Card card = card(cardId);
        card.listId = lists.values().stream()
                .filter(list -> list.boardId.equals(card.boardId) && list.name.equals(listName))
                .map(list -> list.id)
                .findAny()
                .orElseThrow(() -> new IllegalStateException("No list named " + listName));
    }

    public synchronized void addComment(String cardId, String text) {
        card(cardId);
        String id = nextId();
        comments.put(id, new Comment(id, cardId, text));
    }

    public synchronized void deleteCard(String cardId) {
        cards.remove(cardId);
    }

    public synchronized boolean receivedRequestStartingWith(String prefix) {
        for (String request : requests) {
            if (request.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /// Requests in arrival order, as `METHOD /path`.
    public synchronized List<String> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Reply reply;
        Consumer<String> cardReadAction;
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath().substring("/1/".length());
        synchronized (this) {
            requests.add(method + " /" + path);
            cardReadAction = beforeCardRead;
        }
        List<String> segments = List.of(path.split("/"));
        if ("GET".equals(method) && segments.size() == 2 && "cards".equals(segments.getFirst())) {
            cardReadAction.accept(segments.get(1));
        }
        synchronized (this) {
            reply = injectedFailure(method, path).orElseGet(() -> route(method, segments, query(exchange)));
        }
        TestHttpExchange.respond(exchange, reply.status(), reply.body());
    }

    private Optional<Reply> injectedFailure(String method, String path) {
        for (Map.Entry<String, Integer> failure : List.copyOf(failures.entrySet())) {
            if ((method + " " + path).startsWith(failure.getKey())) {
                failures.remove(failure.getKey());
                return Optional.of(new Reply(failure.getValue(), "injected failure"));
            }
        }
        return Optional.empty();
    }

    private Reply route(String method, List<String> path, Map<String, String> query) {
        String resource = path.getFirst();
        return switch (method + " " + resource + "/" + (path.size() - 1)) {
            case "GET members/1" -> ok(Map.of("id", MEMBER_ID, "username", "alex", "fullName", "Alex Example"));
            case "GET members/2" -> ok(workspaces.stream().map(Workspace::json).toList());
            case "POST boards/0", "POST boards/1" ->
                ok(createBoard(query.get("name"), query.get("idOrganization")).json());
            case "GET boards/1", "PUT boards/1" -> boardRoute(method, path.get(1), query);
            case "GET boards/2" -> boardLists(path.get(1));
            case "POST lists/0" ->
                ok(createList(query.get("idBoard"), query.get("name")).json());
            case "POST cards/0" -> ok(createCard(query).json(List.of()));
            case "GET cards/1" -> cardRead(path.get(1));
            case "PUT cards/2" -> "idList".equals(path.get(2)) ? moveCardRoute(path.get(1), query) : notFound();
            case "POST cards/3" -> addCommentRoute(path.get(1), query);
            case "PUT actions/2" -> updateComment(path.get(1), query);
            default -> notFound();
        };
    }

    private Board createBoard(String name, String workspaceId) {
        String id = nextId();
        String shortLink = "SYNTH%03d".formatted(boards.size() + 1);
        var board = new Board(id, name, shortLink, workspaceId);
        boards.put(id, board);
        return board;
    }

    private Reply boardRoute(String method, String boardId, Map<String, String> query) {
        Board board = boards.get(boardId);
        if (board == null) {
            return notFound();
        }
        if ("PUT".equals(method) && query.containsKey("closed")) {
            board.closed = Boolean.parseBoolean(query.get("closed"));
        }
        return ok(board.json());
    }

    private Reply boardLists(String boardId) {
        return ok(lists.values().stream()
                .filter(list -> list.boardId.equals(boardId))
                .map(TrelloList::json)
                .toList());
    }

    private TrelloList createList(String boardId, String name) {
        var list = new TrelloList(nextId(), boardId, name);
        lists.put(list.id, list);
        return list;
    }

    private Card createCard(Map<String, String> query) {
        TrelloList list = lists.get(query.get("idList"));
        var card = new Card(nextId(), list.boardId, query.get("name"));
        card.listId = list.id;
        cards.put(card.id, card);
        return card;
    }

    private Reply cardRead(String cardId) {
        Card card = cards.get(cardId);
        if (card == null) {
            return notFound();
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        for (Comment comment : comments.values()) {
            if (comment.cardId.equals(cardId)) {
                // Trello returns comment actions newest first.
                actions.addFirst(comment.json());
            }
        }
        return ok(card.json(actions));
    }

    private Reply moveCardRoute(String cardId, Map<String, String> query) {
        Card card = cards.get(cardId);
        if (card == null || !lists.containsKey(query.get("value"))) {
            return notFound();
        }
        card.listId = query.get("value");
        return ok(card.json(List.of()));
    }

    private Reply addCommentRoute(String cardId, Map<String, String> query) {
        if (!cards.containsKey(cardId)) {
            return notFound();
        }
        String id = nextId();
        var comment = new Comment(id, cardId, query.get("text"));
        comments.put(id, comment);
        return ok(comment.json());
    }

    private Reply updateComment(String commentId, Map<String, String> query) {
        Comment comment = comments.get(commentId);
        if (comment == null) {
            return notFound();
        }
        comment.text = query.get("value");
        return ok(comment.json());
    }

    private Board board(String boardId) {
        Board board = boards.get(boardId);
        if (board == null) {
            throw new IllegalStateException("No board " + boardId);
        }
        return board;
    }

    private Card card(String cardId) {
        Card card = cards.get(cardId);
        if (card == null) {
            throw new IllegalStateException("No card " + cardId);
        }
        return card;
    }

    /// Same shape as Trello's 24-character ids, clearly synthetic.
    private String nextId() {
        int id = nextId;
        nextId++;
        return "%024d".formatted(id);
    }

    private static Reply ok(Object body) {
        try {
            return new Reply(200, JSON.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Reply notFound() {
        return new Reply(404, "The requested resource was not found.");
    }

    private record Reply(int status, String body) {}

    private record Workspace(String id, String displayName) {
        Map<String, Object> json() {
            return Map.of("id", id, "name", id, "displayName", displayName, "url", "https://trello.com/w/" + id);
        }
    }

    private static final class Board {
        private final String id;
        private final String name;
        private final String shortLink;
        private final String workspaceId;
        private boolean closed;

        private Board(String id, String name, String shortLink, String workspaceId) {
            this.id = id;
            this.name = name;
            this.shortLink = shortLink;
            this.workspaceId = workspaceId;
        }

        private Map<String, Object> json() {
            return Map.of(
                    "id",
                    id,
                    "name",
                    name,
                    "shortLink",
                    shortLink,
                    "url",
                    "https://trello.com/b/" + shortLink + "/synthetic-board",
                    "closed",
                    closed);
        }
    }

    private record TrelloList(String id, String boardId, String name) {
        Map<String, Object> json() {
            return Map.of("id", id, "name", name, "closed", false, "pos", 1);
        }
    }

    private static final class Card {
        private final String id;
        private final String boardId;
        private final String name;
        private String listId;

        private Card(String id, String boardId, String name) {
            this.id = id;
            this.boardId = boardId;
            this.name = name;
        }

        private Map<String, Object> json(List<Map<String, Object>> actions) {
            return Map.of("id", id, "name", name, "idList", listId, "closed", false, "actions", actions);
        }
    }

    private static final class Comment {
        private final String id;
        private final String cardId;
        private String text;

        private Comment(String id, String cardId, String text) {
            this.id = id;
            this.cardId = cardId;
            this.text = text;
        }

        private Map<String, Object> json() {
            return Map.of("id", id, "type", "commentCard", "data", Map.of("text", text));
        }
    }
}
