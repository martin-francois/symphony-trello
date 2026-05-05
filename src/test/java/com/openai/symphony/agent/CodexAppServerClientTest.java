package com.openai.symphony.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.symphony.TestCards;
import com.openai.symphony.config.ConfigResolver;
import com.openai.symphony.config.EffectiveConfig;
import com.openai.symphony.tracker.TrelloClient;
import com.openai.symphony.workflow.WorkflowDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
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

    private EffectiveConfig config(Path appServer) {
        return new ConfigResolver()
                .resolve(new WorkflowDefinition(
                        tempDir.resolve("WORKFLOW.md"),
                        Map.of(
                                "tracker",
                                Map.of("kind", "trello", "api_key", "key", "api_token", "token", "board_id", "board"),
                                "workspace",
                                Map.of("root", tempDir.resolve("workspaces").toString()),
                                "codex",
                                Map.of(
                                        "command",
                                        appServer.toString(),
                                        "read_timeout_ms",
                                        1000,
                                        "turn_timeout_ms",
                                        1000)),
                        ""));
    }
}
