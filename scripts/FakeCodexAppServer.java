import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Codex app-server test double for live Trello E2E checks.
 *
 * <p>The real Codex app-server protocol is newline-delimited JSON over stdin/stdout. Keeping this as
 * a single-file Java program makes the live runbook repository-native while avoiding an extra test
 * dependency or a packaged helper just to emulate that stdio boundary.
 */
public class FakeCodexAppServer {
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern THREAD_ID = Pattern.compile("\"threadId\"\\s*:\\s*\"([^\"]+)\"");
    private static final String DEFAULT_COMMENT =
            "Symphony live E2E fake Codex handoff: summary and verification complete.";

    public static void main(String[] args) throws Exception {
        new FakeCodexAppServer().run();
    }

    private final BufferedReader input =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private void run() throws Exception {
        String line;
        while ((line = input.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Optional<Integer> id = captureInt(ID, line);
            String method = capture(METHOD, line).orElse("");
            switch (method) {
                case "initialize" -> send("{\"id\":%d,\"result\":{\"userAgent\":\"fake-codex-app-server/1.0\"}}"
                        .formatted(id.orElseThrow()));
                case "initialized" -> {
                    // Notification from Symphony. No response is expected.
                }
                case "thread/start" -> send(
                        "{\"id\":%d,\"result\":{\"thread\":{\"id\":\"thread-fake-%s\"}}}"
                                .formatted(id.orElseThrow(), UUID.randomUUID()));
                case "turn/start" -> handleTurn(id.orElseThrow(), line);
                default -> id.ifPresent(requestId -> send(
                        "{\"id\":%d,\"error\":{\"code\":-32601,\"message\":\"Unsupported method: %s\"}}"
                                .formatted(requestId, jsonString(method))));
            }
        }
    }

    private void handleTurn(int responseId, String line) throws Exception {
        String threadId = capture(THREAD_ID, line).orElse("thread-fake");
        String turnId = "turn-fake-" + UUID.randomUUID();
        send("{\"id\":%d,\"result\":{\"turn\":{\"id\":\"%s\"}}}".formatted(responseId, turnId));

        int sleepMs = Integer.parseInt(System.getenv().getOrDefault("SYMPHONY_FAKE_CODEX_SLEEP_MS", "0"));
        if (sleepMs > 0) {
            Thread.sleep(sleepMs);
        }

        String comment = System.getenv().getOrDefault("SYMPHONY_FAKE_CODEX_COMMENT", DEFAULT_COMMENT);
        String workpadResponse = requestTool(
                10_000,
                "trello_upsert_workpad",
                "{\"text\":\"## Codex Workpad\\n\\n- Plan: live E2E fake Codex executed.\\n- Validation: tool handoff completed.\"}");
        String workpadError = toolError(workpadResponse);
        if (workpadError != null) {
            completeTurn(threadId, turnId, workpadError);
            return;
        }

        String commentResponse = requestTool(
                10_001,
                "trello_add_comment",
                "{\"text\":\"%s\"}".formatted(jsonString(comment)));
        String commentError = toolError(commentResponse);
        if (commentError != null) {
            completeTurn(threadId, turnId, commentError);
            return;
        }

        String moveResponse = requestTool(
                10_002,
                "trello_move_current_card",
                "{\"list_name\":\"%s\"}".formatted(jsonString(reviewState())));
        String moveError = toolError(moveResponse);
        if (moveError == null) {
            recordSuccessfulCompletion(turnId);
        }
        completeTurn(threadId, turnId, moveError);
    }

    private String requestTool(int requestId, String tool, String arguments) throws IOException {
        send("{\"id\":%d,\"method\":\"item/tool/call\",\"params\":{\"tool\":\"%s\",\"arguments\":%s}}"
                .formatted(requestId, tool, arguments));
        String line;
        while ((line = input.readLine()) != null) {
            if (captureInt(ID, line).filter(id -> id == requestId).isPresent()) {
                return line;
            }
        }
        throw new IOException("Symphony closed stdin while waiting for tool response");
    }

    private void completeTurn(String threadId, String turnId, String error) {
        String errorJson = error == null ? "null" : "{\"message\":\"%s\"}".formatted(jsonString(error));
        send(
                """
                {"method":"turn/completed","params":{"threadId":"%s","turn":{"id":"%s","error":%s,"usage":{"inputTokens":1,"outputTokens":1}}}}\
                """
                        .formatted(jsonString(threadId), jsonString(turnId), errorJson));
    }

    private static String toolError(String response) {
        if (response.contains("\"error\"")) {
            return response;
        }
        return response.contains("\"success\":false") ? response : null;
    }

    private static void recordSuccessfulCompletion(String turnId) throws IOException {
        String completionsFile = System.getenv("SYMPHONY_FAKE_CODEX_COMPLETIONS_FILE");
        if (completionsFile == null || completionsFile.isBlank()) {
            return;
        }
        Files.writeString(
                Path.of(completionsFile),
                turnId + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static String reviewState() {
        return System.getenv().getOrDefault("SYMPHONY_FAKE_CODEX_REVIEW_STATE", "Human Review");
    }

    private static Optional<Integer> captureInt(Pattern pattern, String value) {
        return capture(pattern, value).map(Integer::parseInt);
    }

    private static Optional<String> capture(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append("\\u%04x".formatted((int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static void send(String message) {
        System.out.println(message);
        System.out.flush();
    }
}
