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
    private final List<String> createdLists = new ArrayList<>();
    private final List<String> boardLookups = new ArrayList<>();
    private final List<String> memberLookups = new ArrayList<>();
    private final List<String> workspaceLookups = new ArrayList<>();
    private final AtomicReference<String> memberResponse = new AtomicReference<>(
            """
            {"id":"member-1","username":"alex","fullName":"Alex Example"}
            """);
    private final AtomicReference<String> workspaceResponse = new AtomicReference<>(
            """
            [
              {"id":"workspace-1","name":"engineering","displayName":"Engineering","url":"https://trello.com/w/engineering"}
            ]
            """);
    private final AtomicReference<String> boardResponse = new AtomicReference<>(
            """
            {"id":"board-1","name":"%s","shortLink":"abc123","url":"https://trello.com/b/abc123/board"}
            """);
    private final AtomicReference<String> boardListsResponse = new AtomicReference<>(
            """
            [
              {"id":"list-1","name":"Inbox","closed":false,"pos":1},
              {"id":"list-2","name":"Ready for Codex","closed":false,"pos":2},
              {"id":"list-3","name":"In Progress","closed":false,"pos":3},
              {"id":"list-4","name":"Blocked","closed":false,"pos":4},
              {"id":"list-5","name":"Human Review","closed":false,"pos":5},
              {"id":"list-6","name":"Done","closed":false,"pos":6}
            ]
            """);
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
            respond(exchange, boardResponse.get().formatted(query(exchange).getOrDefault("name", "Imported Queue")));
        });
        server.createContext("/1/boards/board-1/lists", exchange -> respond(exchange, boardListsResponse.get()));
        server.createContext("/1/lists", exchange -> {
            createdLists.add(query(exchange).get("name"));
            respond(exchange, "{\"id\":\"list-" + createdLists.size() + "\"}");
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
        memberResponse.set("""
                {"id":"%s","username":"%s","fullName":"%s"}
                """
                .formatted(id, username, fullName));
        return this;
    }

    public FakeTrelloServer givenSingleWorkspace(String id, String displayName) {
        return givenWorkspaces(
                """
                [
                        {"id":"%s","name":"%s","displayName":"%s","url":"https://trello.com/w/%s"}
                ]
                """
                        .formatted(
                                id,
                                displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"),
                                displayName,
                                id));
    }

    public FakeTrelloServer givenWorkspaces(String json) {
        workspaceResponse.set(json);
        return this;
    }

    public FakeTrelloServer givenBoard(String id, String shortLink, String name, String url) {
        boardResponse.set("""
                {"id":"%s","name":"%s","shortLink":"%s","url":"%s"}
                """
                .formatted(id, name, shortLink, url));
        return this;
    }

    public FakeTrelloServer givenBoardLists(String... listNames) {
        List<String> jsonLists = new ArrayList<>();
        for (int i = 0; i < listNames.length; i++) {
            jsonLists.add(
                    """
                    {"id":"list-%d","name":"%s","closed":false,"pos":%d}
                    """
                            .formatted(i + 1, listNames[i], i + 1));
        }
        boardListsResponse.set("[\n" + String.join(",\n", jsonLists) + "\n]");
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
}
