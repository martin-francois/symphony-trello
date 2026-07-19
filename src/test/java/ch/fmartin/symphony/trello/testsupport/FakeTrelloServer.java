package ch.fmartin.symphony.trello.testsupport;

import static ch.fmartin.symphony.trello.TestHttpExchange.query;

import ch.fmartin.symphony.trello.TestHttpExchange;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class FakeTrelloServer implements AutoCloseable {
    private static final String CREATED_BOARD_NAME_PLACEHOLDER = "__CREATED_BOARD_NAME__";

    private final List<String> createdLists = new ArrayList<>();
    private final List<String> boardLookups = new ArrayList<>();
    private final List<String> memberLookups = new ArrayList<>();
    private final List<String> workspaceLookups = new ArrayList<>();
    private final AtomicReference<String> memberResponse =
            new AtomicReference<>(memberJson("member-1", "alex", "Alex Example"));
    private final AtomicReference<String> workspaceResponse = new AtomicReference<>(
            workspacesJson(workspaceJson("workspace-1", "engineering", "Engineering", "engineering")));
    private final AtomicReference<String> boardResponse = new AtomicReference<>(
            boardJson("board-1", CREATED_BOARD_NAME_PLACEHOLDER, "abc123", "https://trello.com/b/abc123/board"));
    private final AtomicReference<String> boardListsResponse = new AtomicReference<>(listsJson(
            trelloList("list-1", "Inbox", 1),
            trelloList("list-2", "Ready for Codex", 2),
            trelloList("list-3", "In Progress", 3),
            trelloList("list-4", "Blocked", 4),
            trelloList("list-5", "Human Review", 5),
            trelloList("list-6", "Done", 6)));
    private final Map<String, HttpHandler> customRoutes = new LinkedHashMap<>();
    private HttpServer server;

    public FakeTrelloServer start() throws IOException {
        return start(true);
    }

    public FakeTrelloServer startEmpty() throws IOException {
        return start(false);
    }

    private FakeTrelloServer start(boolean includeDefaultRoutes) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        if (includeDefaultRoutes) {
            registerDefaultRoutes();
        }
        customRoutes.forEach(server::createContext);
        server.start();
        return this;
    }

    private void registerDefaultRoutes() {
        server.createContext("/1/members/me", exchange -> {
            memberLookups.add(exchange.getRequestURI().getPath());
            respond(exchange, memberResponse.get());
        });
        server.createContext("/1/members/me/organizations", exchange -> {
            workspaceLookups.add(exchange.getRequestURI().getPath());
            respond(exchange, workspaceResponse.get());
        });
        server.createContext("/1/boards/", exchange -> {
            boardLookups.add(exchange.getRequestURI().getPath());
            String boardName = query(exchange).getOrDefault("name", "Imported Queue");
            respond(exchange, boardResponse.get().replace(CREATED_BOARD_NAME_PLACEHOLDER, jsonEscaped(boardName)));
        });
        server.createContext("/1/boards/board-1/lists", exchange -> respond(exchange, boardListsResponse.get()));
        server.createContext("/1/lists", exchange -> {
            createdLists.add(query(exchange).get("name"));
            respond(exchange, createdListJson("list-" + createdLists.size()));
        });
    }

    public FakeTrelloServer on(String path, HttpHandler handler) {
        if (server == null) {
            customRoutes.put(path, handler);
        } else {
            server.createContext(path, handler);
        }
        return this;
    }

    public FakeTrelloServer remove(String path) {
        customRoutes.remove(path);
        if (server != null) {
            server.removeContext(path);
        }
        return this;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Override
    public void close() {
        stop();
    }

    public String endpoint() {
        return endpointUri().toString();
    }

    public URI endpointUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/1");
    }

    public FakeTrelloServer givenMember(String id, String username, String fullName) {
        memberResponse.set(memberJson(id, username, fullName));
        return this;
    }

    public FakeTrelloServer givenSingleWorkspace(String id, String displayName) {
        return givenWorkspaces(workspacesJson(workspaceJson(
                id, displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"), displayName, id)));
    }

    public FakeTrelloServer givenWorkspaces(String json) {
        workspaceResponse.set(json);
        return this;
    }

    public FakeTrelloServer givenBoard(String id, String shortLink, String name, String url) {
        boardResponse.set(boardJson(id, name, shortLink, url));
        return this;
    }

    public FakeTrelloServer givenBoardLists(String... listNames) {
        List<TrelloListJson> jsonLists = new ArrayList<>();
        for (int i = 0; i < listNames.length; i++) {
            jsonLists.add(trelloList("list-" + (i + 1), listNames[i], i + 1));
        }
        boardListsResponse.set(listsJson(jsonLists.toArray(TrelloListJson[]::new)));
        return this;
    }

    public FakeTrelloServer givenRawBoardListsJson(String json) {
        boardListsResponse.set(json);
        return this;
    }

    public List<String> createdLists() {
        return createdLists;
    }

    public List<String> boardLookups() {
        return boardLookups;
    }

    public List<String> memberLookups() {
        return memberLookups;
    }

    public List<String> workspaceLookups() {
        return workspaceLookups;
    }

    public static void respond(HttpExchange exchange, String body) throws IOException {
        TestHttpExchange.respond(exchange, body);
    }

    public static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        TestHttpExchange.respond(exchange, statusCode, body);
    }

    public static String memberJson(String id, String username, String fullName) {
        return """
                {"id":%s,"username":%s,"fullName":%s}
                """
                .formatted(jsonValue(id), jsonValue(username), jsonValue(fullName));
    }

    public static String workspaceJson(String id, String name, String displayName, String urlSlug) {
        return """
                {"id":%s,"name":%s,"displayName":%s,"url":"https://trello.com/w/%s"}
                """
                .formatted(jsonValue(id), jsonValue(name), jsonValue(displayName), jsonEscaped(urlSlug));
    }

    public static String workspacesJson(String... workspaces) {
        return "[\n" + String.join(",\n", workspaces) + "\n]";
    }

    public static String boardJson(String id, String name, String shortLink, String url) {
        return boardJson(id, name, shortLink, url, null);
    }

    public static String boardJson(String id, String name, boolean closed) {
        return """
                {"id":%s,"name":%s,"closed":%s}
                """
                .formatted(jsonValue(id), jsonValue(name), closed);
    }

    public static String boardJson(String id, String name, String shortLink, String url, Boolean closed) {
        String closedField = closed == null ? "" : ",\"closed\":" + closed;
        return """
                {"id":%s,"name":%s,"shortLink":%s,"url":%s%s}
                """
                .formatted(jsonValue(id), jsonValue(name), jsonValue(shortLink), jsonValue(url), closedField);
    }

    public static String createdListJson(String id) {
        return """
                {"id":%s}
                """.formatted(jsonValue(id));
    }

    public static TrelloListJson trelloList(String id, String name, int pos) {
        return new TrelloListJson(id, name, false, pos);
    }

    public static TrelloListJson trelloList(String id, String name, boolean closed, int pos) {
        return new TrelloListJson(id, name, closed, pos);
    }

    public static String listsJson(TrelloListJson... lists) {
        List<String> jsonLists = new ArrayList<>();
        for (TrelloListJson list : lists) {
            jsonLists.add("""
                    {"id":%s,"name":%s,"closed":%s,"pos":%d}
                    """
                    .formatted(jsonValue(list.id()), jsonValue(list.name()), list.closed(), list.pos()));
        }
        return "[\n" + String.join(",\n", jsonLists) + "\n]";
    }

    public record TrelloListJson(String id, String name, boolean closed, int pos) {}

    private static String jsonValue(String value) {
        return "\"" + jsonEscaped(value) + "\"";
    }

    /// Trello returns valid JSON for any reflected value, so canned responses escape echoes.
    public static String jsonEscaped(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
