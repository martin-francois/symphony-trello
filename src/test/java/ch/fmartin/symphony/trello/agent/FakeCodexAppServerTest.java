package ch.fmartin.symphony.trello.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class FakeCodexAppServerTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void everyToolRequestCarriesItsCurrentThreadAndTurn(boolean scripted) throws Exception {
        // given
        Path output = tempDir.resolve("stdout.jsonl");
        Path errors = tempDir.resolve("stderr.txt");
        var builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                Path.of("scripts/FakeCodexAppServer.java").toAbsolutePath().toString());
        builder.redirectOutput(output.toFile()).redirectError(errors.toFile());
        builder.environment().keySet().removeIf(FakeCodexAppServerTest::fakeSetting);
        if (scripted) {
            Path scripts = Files.createDirectory(tempDir.resolve("scripts"));
            Files.writeString(scripts.resolve("protocol.script"), "tool trello_get_current_card {}\nhandoff\n");
            builder.environment().put("SYMPHONY_FAKE_CODEX_SCRIPT_DIR", scripts.toString());
        }
        String first = turnInput(1, "thread-first", scripted);
        String second = turnInput(2, "thread-second", scripted);
        Process process = builder.start();

        // when
        try {
            try (var input = process.outputWriter(StandardCharsets.UTF_8)) {
                input.write(first + second);
            }
            assertThat(process.waitFor(Duration.ofSeconds(30)))
                    .as("the fake completes both turns and exits after stdin closes")
                    .isTrue();
        } finally {
            process.destroyForcibly();
        }

        // then
        assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
        var json = new ObjectMapper();
        List<ToolCall> calls = new ArrayList<>();
        List<String> turns = new ArrayList<>();
        for (String line : Files.readAllLines(output)) {
            JsonNode message = json.readTree(line);
            if (message.has("result")) {
                turns.add(message.path("result").path("turn").path("id").asText());
            } else if (message.path("method").asText().equals("item/tool/call")) {
                JsonNode params = message.path("params");
                calls.add(new ToolCall(
                        params.path("threadId").asText(),
                        params.path("turnId").asText(),
                        params.path("tool").asText()));
            }
        }
        assertThat(turns)
                .hasSize(2)
                .doesNotHaveDuplicates()
                .allMatch(turn -> turn.startsWith("turn-fake-"), "generated turn identities");
        List<ToolCall> expected = new ArrayList<>();
        expected.addAll(expectedCalls("thread-first", turns.getFirst(), scripted));
        expected.addAll(expectedCalls("thread-second", turns.get(1), scripted));
        assertThat(calls).containsExactlyElementsOf(expected);
    }

    private static boolean fakeSetting(String name) {
        return name.startsWith("SYMPHONY_FAKE_CODEX_");
    }

    private static String turnInput(int id, String thread, boolean scripted) {
        var input = new StringBuilder(
                """
                {"id":%d,"method":"turn/start","params":{"threadId":"%s","input":[{"text":"protocol"}]}}
                """
                        .formatted(id, thread));
        if (scripted) {
            input.append(success(20_000));
        }
        for (int request = 10_000; request <= 10_002; request++) {
            input.append(success(request));
        }
        return input.toString();
    }

    private static String success(int id) {
        return "{\"id\":%d,\"result\":{\"success\":true}}\n".formatted(id);
    }

    private static List<ToolCall> expectedCalls(String thread, String turn, boolean scripted) {
        List<ToolCall> calls = new ArrayList<>();
        if (scripted) {
            calls.add(new ToolCall(thread, turn, "trello_get_current_card"));
        }
        calls.add(new ToolCall(thread, turn, "trello_upsert_workpad"));
        calls.add(new ToolCall(thread, turn, "trello_add_comment"));
        calls.add(new ToolCall(thread, turn, "trello_move_current_card"));
        return calls;
    }

    private record ToolCall(String threadId, String turnId, String tool) {}
}
