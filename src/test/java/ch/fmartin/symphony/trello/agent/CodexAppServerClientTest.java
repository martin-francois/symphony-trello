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
    void keepsQueuedTurnCompletionWhenAppServerExitsBeforeAwaiterIsRegistered() throws Exception {
        // given
        Path appServer = tempDir.resolve("completed-then-exit-app-server.sh");
        Files.writeString(
                appServer,
                """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  [[ "$line" =~ \\"id\\":([0-9]+) ]] && id="${BASH_REMATCH[1]}"
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"userAgent\\":\\"completed-exit-test\\"}}" ;;
                    *\\"method\\":\\"thread/start\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"thread\\":{\\"id\\":\\"thread-completed-exit\\"}}}" ;;
                    *\\"method\\":\\"turn/start\\"*)
                      echo "{\\"id\\":$id,\\"result\\":{\\"turn\\":{\\"id\\":\\"turn-completed-exit\\"}}}"
                      echo "{\\"method\\":\\"turn/completed\\",\\"params\\":{\\"threadId\\":\\"thread-completed-exit\\",\\"turn\\":{\\"id\\":\\"turn-completed-exit\\",\\"error\\":null}}}"
                      exit 0
                      ;;
                  esac
                done
                """);
        appServer.toFile().setExecutable(true);
        EffectiveConfig config =
                config(Map.of("command", appServer.toString(), "read_timeout_ms", 1000, "turn_timeout_ms", 10000));
        Path workspace = config.workspace().root().resolve("TRELLO-completed-exit");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-completed-exit", "Ready for Codex"),
                workspace,
                "Do a completed exit no-op turn.",
                "worker-completed-exit",
                event -> {});

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
    }

    @Test
    void preservesAppServerErrorResponseMessage() throws Exception {
        // given
        Path appServer = tempDir.resolve("error-response-app-server.sh");
        Files.writeString(
                appServer,
                """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  [[ "$line" =~ \\"id\\":([0-9]+) ]] && id="${BASH_REMATCH[1]}"
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*)
                      echo "{\\"id\\":$id,\\"error\\":{\\"code\\":-32000,\\"message\\":\\"bad initialize\\"}}"
                      exit 0
                      ;;
                  esac
                done
                """);
        appServer.toFile().setExecutable(true);
        EffectiveConfig config = config(appServer);
        Path workspace = config.workspace().root().resolve("TRELLO-error-response");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-error-response", "Ready for Codex"),
                workspace,
                "Do an error-response turn.",
                "worker-error-response",
                event -> {});

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).startsWith("codex_protocol_error:").contains("bad initialize");
        assertThat(result.reason()).doesNotContain("app-server reader failed");
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

    @Test
    void sendsConfiguredModelAndReasoningEffortToCodexAppServer() throws Exception {
        // given
        Path capture = tempDir.resolve("model-requests.jsonl");
        Path appServer = tempDir.resolve("model-capturing-app-server.sh");
        Files.writeString(
                appServer,
                """
                #!/usr/bin/env bash
                capture="$1"
                : > "$capture"
                while IFS= read -r line; do
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo '{"id":1,"result":{"userAgent":"model-test"}}' ;;
                    *\\"method\\":\\"thread/start\\"*)
                      printf '%s\\n' "$line" >> "$capture"
                      echo '{"id":2,"result":{"thread":{"id":"thread-model"}}}'
                      ;;
                    *\\"method\\":\\"turn/start\\"*)
                      printf '%s\\n' "$line" >> "$capture"
                      echo '{"id":3,"result":{"turn":{"id":"turn-model"}}}'
                      echo '{"method":"turn/completed","params":{"threadId":"thread-model","turn":{"id":"turn-model","error":null}}}'
                      ;;
                  esac
                done
                """);
        appServer.toFile().setExecutable(true);
        EffectiveConfig config = config(Map.of(
                "command",
                appServer + " " + capture,
                "model",
                "gpt-5.5",
                "reasoning_effort",
                "xhigh",
                "read_timeout_ms",
                1000,
                "turn_timeout_ms",
                1000));
        Path workspace = config.workspace().root().resolve("TRELLO-model");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-model", "Ready for Codex"),
                workspace,
                "Do a model capture no-op turn.",
                "worker-model",
                event -> {});

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        List<JsonNode> requests = capturedRequests(capture);
        assertThat(requests)
                .extracting(request -> request.path("method").asText())
                .containsExactly("thread/start", "turn/start");
        assertThat(requests.get(0).path("params").path("model").asText()).isEqualTo("gpt-5.5");
        assertThat(requests.get(1).path("params").path("model").asText()).isEqualTo("gpt-5.5");
        assertThat(requests.get(1).path("params").path("effort").asText()).isEqualTo("xhigh");
    }

    @Test
    void omitsModelAndReasoningEffortWhenWorkflowDoesNotConfigureThem() throws Exception {
        // given
        Path capture = tempDir.resolve("default-model-requests.jsonl");
        Path appServer = tempDir.resolve("default-model-capturing-app-server.sh");
        Files.writeString(
                appServer,
                """
                #!/usr/bin/env bash
                capture="$1"
                : > "$capture"
                while IFS= read -r line; do
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo '{"id":1,"result":{"userAgent":"default-model-test"}}' ;;
                    *\\"method\\":\\"thread/start\\"*)
                      printf '%s\\n' "$line" >> "$capture"
                      echo '{"id":2,"result":{"thread":{"id":"thread-default-model"}}}'
                      ;;
                    *\\"method\\":\\"turn/start\\"*)
                      printf '%s\\n' "$line" >> "$capture"
                      echo '{"id":3,"result":{"turn":{"id":"turn-default-model"}}}'
                      echo '{"method":"turn/completed","params":{"threadId":"thread-default-model","turn":{"id":"turn-default-model","error":null}}}'
                      ;;
                  esac
                done
                """);
        appServer.toFile().setExecutable(true);
        EffectiveConfig config =
                config(Map.of("command", appServer + " " + capture, "read_timeout_ms", 1000, "turn_timeout_ms", 1000));
        Path workspace = config.workspace().root().resolve("TRELLO-default-model");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-default-model", "Ready for Codex"),
                workspace,
                "Do a default model capture no-op turn.",
                "worker-default-model",
                event -> {});

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        List<JsonNode> requests = capturedRequests(capture);
        assertThat(requests)
                .extracting(request -> request.path("method").asText())
                .containsExactly("thread/start", "turn/start");
        assertThat(requests.get(0).path("params").has("model")).isFalse();
        assertThat(requests.get(1).path("params").has("model")).isFalse();
        assertThat(requests.get(1).path("params").has("effort")).isFalse();
    }

    @Test
    void continuesMultipleTurnsOnOneThreadWhenControllerRequestsIt() throws Exception {
        // given
        Path capture = tempDir.resolve("turns.jsonl");
        Path appServer = tempDir.resolve("multi-turn-app-server.sh");
        Files.writeString(
                appServer,
                """
                #!/usr/bin/env bash
                capture="$1"
                turn_count=0
                while IFS= read -r line; do
                  [[ "$line" =~ \\"id\\":([0-9]+) ]] && id="${BASH_REMATCH[1]}"
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"userAgent\\":\\"multi-turn-test\\"}}" ;;
                    *\\"method\\":\\"thread/start\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"thread\\":{\\"id\\":\\"thread-shared\\"}}}" ;;
                    *\\"method\\":\\"turn/start\\"*)
                      turn_count=$((turn_count + 1))
                      printf '%s\\n' "$line" >> "$capture"
                      echo "{\\"id\\":$id,\\"result\\":{\\"turn\\":{\\"id\\":\\"turn-$turn_count\\"}}}"
                      echo "{\\"method\\":\\"turn/completed\\",\\"params\\":{\\"threadId\\":\\"thread-shared\\",\\"turn\\":{\\"id\\":\\"turn-$turn_count\\",\\"error\\":null}}}"
                      ;;
                  esac
                done
                """);
        appServer.toFile().setExecutable(true);
        EffectiveConfig config =
                config(Map.of("command", appServer + " " + capture, "read_timeout_ms", 1000, "turn_timeout_ms", 1000));
        Path workspace = config.workspace().root().resolve("TRELLO-multi-turn");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runSession(
                config,
                TestCards.card("card-1", "TRELLO-multi-turn", "Ready for Codex"),
                workspace,
                "first turn",
                "worker-multi-turn",
                event -> {},
                completedTurns -> completedTurns == 1
                        ? CodexAppServerClient.TurnDecision.continueWith("second turn")
                        : CodexAppServerClient.TurnDecision.stop());

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        List<JsonNode> turns = Files.readAllLines(capture).stream()
                .map(line -> {
                    try {
                        return json.readTree(line);
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .toList();
        assertThat(turns).hasSize(2);
        assertThat(turns)
                .extracting(turn -> turn.path("params").path("threadId").asText())
                .containsExactly("thread-shared", "thread-shared");
        assertThat(turns)
                .extracting(turn -> turn.at("/params/input/0/text").asText())
                .containsExactly("first turn", "second turn");
    }

    @Test
    void failsPromptlyWhenAppServerExitsBeforeTurnCompletion() throws Exception {
        // given
        Path appServer = tempDir.resolve("exiting-app-server.sh");
        Files.writeString(
                appServer,
                """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  [[ "$line" =~ \\"id\\":([0-9]+) ]] && id="${BASH_REMATCH[1]}"
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"userAgent\\":\\"exit-test\\"}}" ;;
                    *\\"method\\":\\"thread/start\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"thread\\":{\\"id\\":\\"thread-exit\\"}}}" ;;
                    *\\"method\\":\\"turn/start\\"*)
                      echo "{\\"id\\":$id,\\"result\\":{\\"turn\\":{\\"id\\":\\"turn-exit\\"}}}"
                      exit 0
                      ;;
                  esac
                done
                """);
        appServer.toFile().setExecutable(true);
        EffectiveConfig config =
                config(Map.of("command", appServer.toString(), "read_timeout_ms", 1000, "turn_timeout_ms", 10000));
        Path workspace = config.workspace().root().resolve("TRELLO-exit");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-exit", "Ready for Codex"),
                workspace,
                "Do an exit no-op turn.",
                "worker-exit",
                event -> {});

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason())
                .startsWith("process_exit:")
                .doesNotStartWith("response_timeout:")
                .doesNotStartWith("turn_timeout:");
    }

    @Test
    void failsPromptlyWhenAppServerExitsBeforeRequestResponse() throws Exception {
        // given
        Path appServer = tempDir.resolve("exiting-before-request-response-app-server.sh");
        Files.writeString(
                appServer,
                """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  [[ "$line" =~ \\"id\\":([0-9]+) ]] && id="${BASH_REMATCH[1]}"
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"userAgent\\":\\"exit-before-response-test\\"}}" ;;
                    *\\"method\\":\\"thread/start\\"*) exit 0 ;;
                  esac
                done
                """);
        appServer.toFile().setExecutable(true);
        EffectiveConfig config =
                config(Map.of("command", appServer.toString(), "read_timeout_ms", 1000, "turn_timeout_ms", 10000));
        Path workspace = config.workspace().root().resolve("TRELLO-exit-request");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-exit-request", "Ready for Codex"),
                workspace,
                "Do an exit before response turn.",
                "worker-exit-request",
                event -> {});

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason())
                .startsWith("process_exit:")
                .doesNotStartWith("response_timeout:")
                .doesNotStartWith("turn_timeout:");
    }

    @Test
    void failsTurnWhenAppServerEmitsTurnFailureNotification() throws Exception {
        // given
        Path appServer = tempDir.resolve("failed-turn-app-server.sh");
        Files.writeString(
                appServer,
                """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  [[ "$line" =~ \\"id\\":([0-9]+) ]] && id="${BASH_REMATCH[1]}"
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"userAgent\\":\\"failed-turn-test\\"}}" ;;
                    *\\"method\\":\\"thread/start\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"thread\\":{\\"id\\":\\"thread-failed\\"}}}" ;;
                    *\\"method\\":\\"turn/start\\"*)
                      echo "{\\"id\\":$id,\\"result\\":{\\"turn\\":{\\"id\\":\\"turn-failed\\"}}}"
                      echo "{\\"method\\":\\"turn/failed\\",\\"params\\":{\\"threadId\\":\\"thread-failed\\",\\"turn\\":{\\"id\\":\\"turn-failed\\"},\\"message\\":\\"tool failed\\"}}"
                      ;;
                  esac
                done
                """);
        appServer.toFile().setExecutable(true);
        EffectiveConfig config = config(appServer);
        Path workspace = config.workspace().root().resolve("TRELLO-failed");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-failed", "Ready for Codex"),
                workspace,
                "Do a failed turn.",
                "worker-failed",
                event -> {});

        // then
        assertThat(result).isEqualTo(AgentRunResult.fail("turn_failed: tool failed"));
    }

    @Test
    void failsTurnWhenAppServerEmitsTerminalErrorNotification() throws Exception {
        // given
        Path appServer = tempDir.resolve("terminal-error-app-server.sh");
        Files.writeString(
                appServer,
                """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  [[ "$line" =~ \\"id\\":([0-9]+) ]] && id="${BASH_REMATCH[1]}"
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"userAgent\\":\\"terminal-error-test\\"}}" ;;
                    *\\"method\\":\\"thread/start\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"thread\\":{\\"id\\":\\"thread-error\\"}}}" ;;
                    *\\"method\\":\\"turn/start\\"*)
                      echo "{\\"id\\":$id,\\"result\\":{\\"turn\\":{\\"id\\":\\"turn-error\\"}}}"
                      echo "{\\"method\\":\\"error\\",\\"params\\":{\\"threadId\\":\\"thread-error\\",\\"turnId\\":\\"turn-error\\",\\"willRetry\\":false,\\"error\\":{\\"message\\":\\"model request failed\\"}}}"
                      ;;
                  esac
                done
                """);
        appServer.toFile().setExecutable(true);
        EffectiveConfig config = config(appServer);
        Path workspace = config.workspace().root().resolve("TRELLO-error");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-error", "Ready for Codex"),
                workspace,
                "Do a terminal error turn.",
                "worker-error",
                event -> {});

        // then
        assertThat(result).isEqualTo(AgentRunResult.fail("turn_failed: model request failed"));
    }

    @Test
    void failsTurnWhenAppServerEmitsTurnCancellationNotification() throws Exception {
        // given
        Path appServer = tempDir.resolve("cancelled-turn-app-server.sh");
        Files.writeString(
                appServer,
                """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  [[ "$line" =~ \\"id\\":([0-9]+) ]] && id="${BASH_REMATCH[1]}"
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"userAgent\\":\\"cancelled-turn-test\\"}}" ;;
                    *\\"method\\":\\"thread/start\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"thread\\":{\\"id\\":\\"thread-cancelled\\"}}}" ;;
                    *\\"method\\":\\"turn/start\\"*)
                      echo "{\\"id\\":$id,\\"result\\":{\\"turn\\":{\\"id\\":\\"turn-cancelled\\"}}}"
                      echo "{\\"method\\":\\"turn/cancelled\\",\\"params\\":{\\"threadId\\":\\"thread-cancelled\\",\\"turnId\\":\\"turn-cancelled\\",\\"message\\":\\"operator cancelled\\"}}"
                      ;;
                  esac
                done
                """);
        appServer.toFile().setExecutable(true);
        EffectiveConfig config = config(appServer);
        Path workspace = config.workspace().root().resolve("TRELLO-cancelled");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-cancelled", "Ready for Codex"),
                workspace,
                "Do a cancelled turn.",
                "worker-cancelled",
                event -> {});

        // then
        assertThat(result).isEqualTo(AgentRunResult.fail("turn_cancelled: operator cancelled"));
    }

    @Test
    void failsTurnWhenAppServerCompletesTurnAsInterrupted() throws Exception {
        // given
        Path appServer = tempDir.resolve("interrupted-turn-app-server.sh");
        Files.writeString(
                appServer,
                """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  [[ "$line" =~ \\"id\\":([0-9]+) ]] && id="${BASH_REMATCH[1]}"
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"userAgent\\":\\"interrupted-turn-test\\"}}" ;;
                    *\\"method\\":\\"thread/start\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"thread\\":{\\"id\\":\\"thread-interrupted\\"}}}" ;;
                    *\\"method\\":\\"turn/start\\"*)
                      echo "{\\"id\\":$id,\\"result\\":{\\"turn\\":{\\"id\\":\\"turn-interrupted\\"}}}"
                      echo "{\\"method\\":\\"turn/completed\\",\\"params\\":{\\"threadId\\":\\"thread-interrupted\\",\\"turn\\":{\\"id\\":\\"turn-interrupted\\",\\"items\\":[],\\"status\\":\\"interrupted\\",\\"error\\":null}}}"
                      ;;
                  esac
                done
                """);
        appServer.toFile().setExecutable(true);
        EffectiveConfig config = config(appServer);
        Path workspace = config.workspace().root().resolve("TRELLO-interrupted");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-interrupted", "Ready for Codex"),
                workspace,
                "Do an interrupted turn.",
                "worker-interrupted",
                event -> {});

        // then
        assertThat(result).isEqualTo(AgentRunResult.fail("turn_interrupted: turn status interrupted"));
    }

    private EffectiveConfig config(Path appServer) {
        return config(Map.of("command", appServer.toString(), "read_timeout_ms", 1000, "turn_timeout_ms", 1000));
    }

    private List<JsonNode> capturedRequests(Path capture) throws Exception {
        return Files.readAllLines(capture).stream().map(this::readJsonLine).toList();
    }

    private JsonNode readJsonLine(String line) {
        try {
            return json.readTree(line);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
