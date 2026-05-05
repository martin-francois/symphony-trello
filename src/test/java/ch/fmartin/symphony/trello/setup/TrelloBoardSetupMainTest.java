package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrelloBoardSetupMainTest {
    private HttpServer server;
    private final List<String> createdLists = new ArrayList<>();
    private final AtomicReference<String> createdBoardName = new AtomicReference<>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/1/members/me/organizations",
                exchange -> respond(
                        exchange,
                        """
                [
                  {"id":"workspace-1","name":"engineering","displayName":"Engineering","url":"https://trello.com/w/engineering"}
                ]
                """));
        server.createContext("/1/boards/", exchange -> {
            Map<String, String> query = query(exchange);
            createdBoardName.set(query.get("name"));
            respond(
                    exchange,
                    """
                    {"id":"board-1","name":"%s","shortLink":"abc123","url":"https://trello.com/b/abc123/board"}
                    """
                            .formatted(query.get("name")));
        });
        server.createContext("/1/lists", exchange -> {
            Map<String, String> query = query(exchange);
            createdLists.add(query.get("name"));
            respond(exchange, "{\"id\":\"list-" + createdLists.size() + "\",\"name\":\"" + query.get("name") + "\"}");
        });
        server.createContext(
                "/1/boards/input",
                exchange -> respond(
                        exchange,
                        """
                {"id":"board-1","name":"Existing Board","shortLink":"existing","url":"https://trello.com/b/existing/board","closed":false}
                """));
        server.createContext(
                "/1/boards/board-1/lists",
                exchange -> respond(
                        exchange,
                        """
                [
                  {"id":"list-ready","name":"Queue for Codex","closed":false,"pos":1},
                  {"id":"list-review","name":"Review","closed":false,"pos":2},
                  {"id":"list-blocked","name":"Blocked","closed":false,"pos":3},
                  {"id":"list-done","name":"Released","closed":false,"pos":4}
                ]
                """));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void printsHelpWithoutRequiringCredentials() {
        // given
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode = run(stdout, stderr, "--help");

        // then
        assertThat(exitCode).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("new-board")
                .contains("import-board");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void listsWorkspacesFromCommandLineCredentials() {
        // given
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode =
                run(stdout, stderr, "list-workspaces", "--endpoint", endpoint(), "--key", "key", "--token", "token");

        // then
        assertThat(exitCode).isZero();
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("Trello workspaces:")
                .contains("workspace-1")
                .contains("Engineering");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void createsRecommendedBoardAndPrintsNextSteps() {
        // given
        Path workflow = tempDir.resolve("generated.WORKFLOW.md");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode = run(
                stdout,
                stderr,
                "new-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--name",
                "Symphony Work Queue",
                "--workflow",
                workflow.toString());

        // then
        assertThat(exitCode).isZero();
        assertThat(createdBoardName).hasValue("Symphony Work Queue");
        assertThat(createdLists).containsExactly("Inbox", "Ready for Codex", "Blocked", "Review", "Done");
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("board_id: \"abc123\"");
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("Created Trello board: Symphony Work Queue")
                .contains("Wrote workflow:")
                .contains("./mvnw quarkus:dev");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void importsExistingBoardWithExplicitListsAndPrintsSelection() {
        // given
        Path workflow = tempDir.resolve("imported.WORKFLOW.md");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode = run(
                stdout,
                stderr,
                "import-board",
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                "--board",
                "input",
                "--active",
                "Queue for Codex",
                "--terminal",
                "Released",
                "--workflow",
                workflow.toString());

        // then
        assertThat(exitCode).isZero();
        assertThat(workflow)
                .content(StandardCharsets.UTF_8)
                .contains("- \"Queue for Codex\"")
                .contains("- \"Released\"");
        assertThat(stdout.toString(StandardCharsets.UTF_8))
                .contains("Imported Trello board: Existing Board")
                .contains("Active lists: Queue for Codex")
                .contains("Terminal lists: Released")
                .contains("Blocked list: Blocked");
        assertThat(stderr.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void reportsInvalidArgumentsWithStableSetupError() {
        // given
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();

        // when
        int exitCode = run(stdout, stderr, "new-board", "--unknown");

        // then
        assertThat(exitCode).isEqualTo(2);
        assertThat(stdout.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .contains("setup_failed code=setup_invalid_arguments")
                .contains("Unknown option: --unknown");
    }

    private int run(ByteArrayOutputStream stdout, ByteArrayOutputStream stderr, String... args) {
        return TrelloBoardSetupMain.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/1";
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

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
