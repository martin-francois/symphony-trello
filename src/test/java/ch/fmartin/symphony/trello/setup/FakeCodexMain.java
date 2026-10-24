package ch.fmartin.symphony.trello.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;

/// Stand-in for the `codex` executable in process-level tests. It never contacts OpenAI or Trello
/// directly. Environment variables chosen by the test select the behavior:
///
/// - `FAKE_CODEX_REPORT`: file that receives a JSON report of the arguments, working directory,
///   environment, and any MCP responses.
/// - `FAKE_CODEX_MODE`: `exit:N` exits with N, `mcp` calls the board tools over the configured MCP
///   server and exits 0, `wait` writes the report and then blocks until it is killed.
public final class FakeCodexMain {
    static final String REPORT_ENVIRONMENT = "FAKE_CODEX_REPORT";
    static final String MODE_ENVIRONMENT = "FAKE_CODEX_MODE";
    private static final String CONFIG_FLAG = "-c";

    private FakeCodexMain() {}

    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        Path report = Path.of(System.getenv(REPORT_ENVIRONMENT));
        String mode = System.getenv().getOrDefault(MODE_ENVIRONMENT, "exit:0");
        ObjectNode result = json.createObjectNode();
        result.set("args", json.valueToTree(List.of(args)));
        result.put("cwd", Path.of("").toAbsolutePath().toString());
        result.put("pid", ProcessHandle.current().pid());
        // Sorted for a stable report when a test prints it after a failure.
        result.set("environment", json.valueToTree(new TreeMap<>(System.getenv())));
        if ("mcp".equals(mode)) {
            result.set("mcp", callBoardTools(json, args));
        }
        // Write next to the report and move it into place so a waiting test never reads half a file.
        Path partial = Files.writeString(
                report.resolveSibling(report.getFileName() + ".partial"), json.writeValueAsString(result));
        Files.move(partial, report, StandardCopyOption.ATOMIC_MOVE);
        if ("wait".equals(mode)) {
            // Blocks until the session under test stops this process.
            new CountDownLatch(1).await();
        }
        System.exit(mode.startsWith("exit:") ? Integer.parseInt(mode.substring("exit:".length())) : 0);
    }

    private static ArrayNode callBoardTools(ObjectMapper json, String[] args) throws IOException, InterruptedException {
        Map<String, String> config = configOverrides(args);
        URI endpoint = URI.create(config.get("mcp_servers.symphony_trello.url"));
        String token = System.getenv(config.get("mcp_servers.symphony_trello.bearer_token_env_var"));
        ArrayNode responses = json.createArrayNode();
        List<String> requests = List.of(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"fake-codex\",\"version\":\"1\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"trello_board_overview\","
                        + "\"arguments\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"trello_list_cards\","
                        + "\"arguments\":{}}}");
        try (HttpClient http = HttpClient.newHttpClient()) {
            for (String request : requests) {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(endpoint)
                                .header("Authorization", "Bearer " + token)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(request))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                JsonNode body = response.body().isEmpty() ? json.createObjectNode() : json.readTree(response.body());
                responses.add(body);
            }
        }
        return responses;
    }

    private static Map<String, String> configOverrides(String[] args) {
        // Sorted only for readable failure output; lookups do not depend on order.
        Map<String, String> overrides = new TreeMap<>();
        for (int index = 0; index < args.length - 1; index++) {
            if (CONFIG_FLAG.equals(args[index])) {
                String value = args[index + 1];
                int separator = value.indexOf('=');
                Optional.of(separator)
                        .filter(position -> position > 0)
                        .ifPresent(
                                position -> overrides.put(value.substring(0, position), value.substring(position + 1)));
            }
        }
        return overrides;
    }
}
