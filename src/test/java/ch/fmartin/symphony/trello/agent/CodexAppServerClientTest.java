package ch.fmartin.symphony.trello.agent;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexAppServerClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void handlesTurnCompletedArrivingBeforeAwaiterIsRegistered() throws Exception {
        // given
        Path appServer = tempDir.resolve("fast-app-server.sh");
        Files.writeString(
                appServer,
                """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo '{"id":1,"result":{"userAgent":"fast-test"}}' ;;
                    *\\"method\\":\\"thread/start\\"*) echo '{"id":2,"result":{"thread":{"id":"thread-fast"}}}' ;;
                    *\\"method\\":\\"turn/start\\"*)
                      echo '{"id":3,"result":{"turn":{"id":"turn-fast"}}}'
                      echo '{"method":"turn/completed","params":{"threadId":"thread-fast","turn":{"id":"turn-fast","error":null}}}'
                      ;;
                  esac
                done
                """);
        appServer.toFile().setExecutable(true);
        EffectiveConfig config = config(appServer);
        Path workspace = config.workspace().root().resolve("TRELLO-fast");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-fast", "Ready for Codex"),
                workspace,
                "Do a fast no-op turn.",
                "worker-fast",
                event -> {});

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
    }

    @Test
    void sendsAdditionalWritableRootsAsWorkspaceWriteSandboxPolicy() throws Exception {
        // given
        Path capture = tempDir.resolve("turn-start.jsonl");
        Path appServer = tempDir.resolve("capturing-app-server.sh");
        Files.writeString(
                appServer,
                """
                #!/usr/bin/env bash
                capture="$1"
                while IFS= read -r line; do
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo '{"id":1,"result":{"userAgent":"capture-test"}}' ;;
                    *\\"method\\":\\"thread/start\\"*) echo '{"id":2,"result":{"thread":{"id":"thread-capture"}}}' ;;
                    *\\"method\\":\\"turn/start\\"*)
                      printf '%s\\n' "$line" > "$capture"
                      echo '{"id":3,"result":{"turn":{"id":"turn-capture"}}}'
                      echo '{"method":"turn/completed","params":{"threadId":"thread-capture","turn":{"id":"turn-capture","error":null}}}'
                      ;;
                  esac
                done
                """);
        appServer.toFile().setExecutable(true);
        Path extraRoot = tempDir.resolve("allowed-project");
        EffectiveConfig config = config(Map.of(
                "command",
                appServer + " " + capture,
                "additional_writable_roots",
                List.of(extraRoot.toString()),
                "read_timeout_ms",
                1000,
                "turn_timeout_ms",
                1000));
        Path workspace = config.workspace().root().resolve("TRELLO-capture");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-capture", "Ready for Codex"),
                workspace,
                "Do a capture no-op turn.",
                "worker-capture",
                event -> {});

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        assertThat(json.readTree(Files.readString(capture))
                        .path("params")
                        .path("sandboxPolicy")
                        .path("writableRoots"))
                .extracting(JsonNode::asText)
                .contains(extraRoot.toAbsolutePath().normalize().toString());
    }

    private EffectiveConfig config(Path appServer) {
        return config(Map.of("command", appServer.toString(), "read_timeout_ms", 1000, "turn_timeout_ms", 1000));
    }

    private EffectiveConfig config(Map<String, Object> codexConfig) {
        return new ConfigResolver()
                .resolve(new WorkflowDefinition(
                        tempDir.resolve("WORKFLOW.md"),
                        Map.of(
                                "tracker",
                                Map.of("kind", "trello", "api_key", "key", "api_token", "token", "board_id", "board"),
                                "workspace",
                                Map.of("root", tempDir.resolve("workspaces").toString()),
                                "codex",
                                codexConfig),
                        ""));
    }
}
