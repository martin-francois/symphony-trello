import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
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
 *
 * <p>The live bug-bash harness (scripts/live-bugbash) drives per-card behavior through optional
 * environment variables. {@code SYMPHONY_FAKE_CODEX_PROMPT_DIR} receives one file per raw
 * {@code turn/start} request so the harness can assert on the rendered prompt.
 * {@code SYMPHONY_FAKE_CODEX_SCRIPT_DIR} holds {@code <scenario-id>.script} files; a turn whose
 * request mentions a script's scenario id runs that script instead of the default handoff. The
 * steps are the cases of {@link #runScript}. {@code SYMPHONY_FAKE_CODEX_TOOL_DIR} receives one file
 * per client response to a scripted request. One file per record keeps the concurrent fake
 * processes of one worker from interleaving their output.
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

    private static int recordSequence;

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
        writeRecord("SYMPHONY_FAKE_CODEX_PROMPT_DIR", line);
        send("{\"id\":%d,\"result\":{\"turn\":{\"id\":\"%s\"}}}".formatted(responseId, turnId));

        Optional<Path> script = scriptFor(line);
        if (script.isPresent()) {
            runScript(script.orElseThrow(), threadId, turnId);
            return;
        }

        int sleepMs = Integer.parseInt(System.getenv().getOrDefault("SYMPHONY_FAKE_CODEX_SLEEP_MS", "0"));
        if (sleepMs > 0) {
            Thread.sleep(sleepMs);
        }

        String usageLimitMatch = System.getenv("SYMPHONY_FAKE_CODEX_USAGE_LIMIT_MATCH");
        if (usageLimitMatch != null && !usageLimitMatch.isBlank() && line.contains(usageLimitMatch)) {
            completeWithUsageLimit(threadId, turnId);
            return;
        }

        if (Boolean.parseBoolean(System.getenv("SYMPHONY_FAKE_CODEX_NO_HANDOFF"))) {
            completeTurn(threadId, turnId, null);
            return;
        }

        handoff(threadId, turnId);
    }

    private void handoff(String threadId, String turnId) throws IOException {
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

    private static Optional<Path> scriptFor(String turnStartLine) throws IOException {
        String directory = System.getenv("SYMPHONY_FAKE_CODEX_SCRIPT_DIR");
        if (directory == null || directory.isBlank() || !Files.isDirectory(Path.of(directory))) {
            return Optional.empty();
        }
        try (var scripts = Files.list(Path.of(directory))) {
            return scripts.filter(path -> path.getFileName().toString().endsWith(".script"))
                    .filter(path -> turnStartLine.contains(scenarioId(path)))
                    .max((left, right) -> Integer.compare(
                            scenarioId(left).length(), scenarioId(right).length()));
        }
    }

    private static String scenarioId(Path script) {
        String name = script.getFileName().toString();
        return name.substring(0, name.length() - ".script".length());
    }

    private void runScript(Path script, String threadId, String turnId) throws Exception {
        String scenario = scenarioId(script);
        List<String> steps = Files.readAllLines(script, StandardCharsets.UTF_8);
        int requestId = 20_000;
        for (String step : steps) {
            String trimmed = step.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split(" ", 3);
            switch (parts[0]) {
                case "tool" -> logToolResponse(
                        scenario, parts[1], requestTool(requestId++, parts[1], parts.length > 2 ? parts[2] : "{}"));
                case "user-input" -> logToolResponse(
                        scenario,
                        "item/tool/requestUserInput",
                        request(
                                requestId++,
                                "item/tool/requestUserInput",
                                "{\"threadId\":\"%s\",\"turnId\":\"%s\",\"itemId\":\"item-fake\",\"questions\":[{\"id\":\"q1\",\"header\":\"Fake\",\"question\":\"Continue?\",\"options\":null}]}"
                                        .formatted(jsonString(threadId), jsonString(turnId))));
                case "approval" -> logToolResponse(
                        scenario,
                        parts[1],
                        request(
                                requestId++,
                                parts[1],
                                "{\"threadId\":\"%s\",\"turnId\":\"%s\",\"itemId\":\"item-fake\",\"command\":\"true\",\"cwd\":\".\"}"
                                        .formatted(jsonString(threadId), jsonString(turnId))));
                case "telemetry" -> sendTelemetry(threadId, turnId);
                case "malformed-json" -> {
                    send("{this is not json");
                    return;
                }
                case "turn-cancelled" -> {
                    send("{\"method\":\"turn/cancelled\",\"params\":{\"threadId\":\"%s\",\"turnId\":\"%s\"}}"
                            .formatted(jsonString(threadId), jsonString(turnId)));
                    return;
                }
                case "stall" -> {
                    return;
                }
                case "handoff" -> {
                    handoff(threadId, turnId);
                    return;
                }
                case "complete" -> {
                    completeTurn(threadId, turnId, null);
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown fake Codex script step: " + parts[0]);
            }
        }
        completeTurn(threadId, turnId, null);
    }

    private static void sendTelemetry(String threadId, String turnId) {
        send(
                "{\"method\":\"thread/tokenUsage/updated\",\"params\":{\"threadId\":\"%s\",\"turnId\":\"%s\",\"tokenUsage\":{\"total\":{\"inputTokens\":120,\"outputTokens\":30,\"totalTokens\":150}}}}"
                        .formatted(jsonString(threadId), jsonString(turnId)));
        send(
                "{\"method\":\"account/rateLimits/updated\",\"params\":{\"rateLimits\":{\"primary\":{\"usedPercent\":42,\"windowDurationMins\":300,\"resetsAt\":4102444800},\"secondary\":null}}}");
    }

    private static void logToolResponse(String scenario, String request, String response) throws IOException {
        writeRecord("SYMPHONY_FAKE_CODEX_TOOL_DIR", scenario + "\t" + request + "\t" + response);
    }

    /**
     * Writes one record into its own file. The name starts with the wall-clock time and a
     * per-process sequence number, so a sorted listing keeps each process's records in order.
     */
    private static void writeRecord(String variable, String record) throws IOException {
        String directory = System.getenv(variable);
        if (directory == null || directory.isBlank()) {
            return;
        }
        String name = "%013d-%06d-%s.txt".formatted(System.currentTimeMillis(), ++recordSequence, UUID.randomUUID());
        Files.writeString(Path.of(directory, name), record, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static void completeWithUsageLimit(String threadId, String turnId) {
        long resetsAt = Long.parseLong(
                System.getenv().getOrDefault("SYMPHONY_FAKE_CODEX_USAGE_LIMIT_RESETS_AT", "4102444800"));
        send(
                "{\"method\":\"account/rateLimits/updated\",\"params\":{\"rateLimits\":{\"primary\":{\"usedPercent\":100,\"resetsAt\":%d},\"secondary\":null}}}"
                        .formatted(resetsAt));
        send(
                "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"%s\",\"turn\":{\"id\":\"%s\",\"items\":[],\"status\":\"failed\",\"error\":{\"message\":\"Synthetic Codex usage limit.\",\"additionalDetails\":\"private account detail\",\"codexErrorInfo\":\"usageLimitExceeded\"}}}}"
                        .formatted(jsonString(threadId), jsonString(turnId)));
    }

    private String requestTool(int requestId, String tool, String arguments) throws IOException {
        return request(requestId, "item/tool/call", "{\"tool\":\"%s\",\"arguments\":%s}".formatted(tool, arguments));
    }

    private String request(int requestId, String method, String params) throws IOException {
        send("{\"id\":%d,\"method\":\"%s\",\"params\":%s}".formatted(requestId, method, params));
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
