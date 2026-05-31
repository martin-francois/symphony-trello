package ch.fmartin.symphony.trello.setup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class FakeTrelloServer implements AutoCloseable {
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
    private HttpServer server;

    FakeTrelloServer start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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
        server.start();
        return this;
    }

    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Override
    public void close() {
        stop();
    }

    String endpoint() {
        return endpointUri().toString();
    }

    URI endpointUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/1");
    }

    FakeTrelloServer givenMember(String id, String username, String fullName) {
        memberResponse.set("""
                {"id":"%s","username":"%s","fullName":"%s"}
                """
                .formatted(id, username, fullName));
        return this;
    }

    FakeTrelloServer givenSingleWorkspace(String id, String displayName) {
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

    FakeTrelloServer givenWorkspaces(String json) {
        workspaceResponse.set(json);
        return this;
    }

    FakeTrelloServer givenBoard(String id, String shortLink, String name, String url) {
        boardResponse.set("""
                {"id":"%s","name":"%s","shortLink":"%s","url":"%s"}
                """
                .formatted(id, name, shortLink, url));
        return this;
    }

    FakeTrelloServer givenBoardLists(String... listNames) {
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

    FakeTrelloServer givenRawBoardListsJson(String json) {
        boardListsResponse.set(json);
        return this;
    }

    List<String> createdLists() {
        return createdLists;
    }

    List<String> boardLookups() {
        return boardLookups;
    }

    List<String> memberLookups() {
        return memberLookups;
    }

    List<String> workspaceLookups() {
        return workspaceLookups;
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new LinkedHashMap<>();
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String part : rawQuery.split("&")) {
            String[] pair = part.split("=", 2);
            values.put(decode(pair[0]), pair.length == 1 ? "" : decode(pair[1]));
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
