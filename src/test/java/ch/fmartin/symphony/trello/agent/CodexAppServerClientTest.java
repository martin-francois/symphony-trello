package ch.fmartin.symphony.trello.agent;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CodexAppServerClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void launchesCodexWithGitDiscoveryLimitedToWorkspacesRoot() throws Exception {
        // given
        Path ceilingOutput = tempDir.resolve("ceiling.txt");
        Path appServer = writeExecutableScript(
                "ceiling-app-server.sh",
                """
                #!/usr/bin/env bash
                # Absolute output path: the codex launch uses a login shell, and hosted CI login
                # profiles can change the working directory before the script body runs.
                printf '%s' "$GIT_CEILING_DIRECTORIES" > '__CEILING_OUTPUT__'
                while IFS= read -r line; do
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo '{"id":1,"result":{"userAgent":"ceiling-test"}}' ;;
                    *\\"method\\":\\"thread/start\\"*) echo '{"id":2,"result":{"thread":{"id":"thread-ceiling"}}}' ;;
                    *\\"method\\":\\"turn/start\\"*)
                      echo '{"id":3,"result":{"turn":{"id":"turn-ceiling"}}}'
                      echo '{"method":"turn/completed","params":{"threadId":"thread-ceiling","turn":{"id":"turn-ceiling","error":null}}}'
                      ;;
                  esac
                done
                """
                        .replace("__CEILING_OUTPUT__", ceilingOutput.toString()));
        EffectiveConfig config = config(appServer);
        Path workspace = config.workspace().root().resolve("TRELLO-ceiling");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-ceiling", "Ready for Codex"),
                workspace,
                "Do a no-op turn.",
                "worker-ceiling",
                event -> {});

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        assertThat(ceilingOutput)
                .content(StandardCharsets.UTF_8)
                .isEqualTo(
                        config.workspace().root().toAbsolutePath().normalize().toString());
    }

    @Test
    void handlesTurnCompletedArrivingBeforeAwaiterIsRegistered() throws Exception {
        // given
        Path appServer = writeExecutableScript(
                "fast-app-server.sh",
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
        EffectiveConfig config = config(appServer);
        Path workspace = config.workspace().root().resolve("TRELLO-fast");
        Files.createDirectories(workspace);
        Instant eventTime = Instant.parse("2026-05-11T12:34:56Z");
        CodexAppServerClient client = new CodexAppServerClient(
                json,
                new TrelloHandoffToolHandler(json, new TrelloClient(json)),
                Clock.fixed(eventTime, ZoneOffset.UTC));
        List<AgentEvent> events = new ArrayList<>();

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-fast", "Ready for Codex"),
                workspace,
                "Do a fast no-op turn.",
                "worker-fast",
                events::add);

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        assertThat(events).allSatisfy(event -> assertThat(event.timestamp()).isEqualTo(eventTime));
    }

    @Test
    void keepsQueuedTurnCompletionWhenAppServerExitsBeforeAwaiterIsRegistered() throws Exception {
        // given
        Path appServer = writeExecutableScript(
                "completed-then-exit-app-server.sh",
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
        Path appServer = writeExecutableScript(
                "error-response-app-server.sh",
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
        Path appServer = writeExecutableScript(
                "capturing-app-server.sh",
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
    void preservesWorkspaceWriteNetworkAccessWhenMergingWritableRoots() throws Exception {
        // given
        Path capture = tempDir.resolve("turn-start-network.jsonl");
        Path appServer = writeExecutableScript(
                "capturing-network-app-server.sh",
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
        Path extraRoot = tempDir.resolve("allowed-project");
        EffectiveConfig config = config(Map.of(
                "command",
                appServer + " " + capture,
                "turn_sandbox_policy",
                Map.of("type", "workspaceWrite", "networkAccess", true),
                "additional_writable_roots",
                List.of(extraRoot.toString()),
                "read_timeout_ms",
                1000,
                "turn_timeout_ms",
                1000));
        Path workspace = config.workspace().root().resolve("TRELLO-capture-network");
        Files.createDirectories(workspace);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));

        // when
        AgentRunResult result = client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-capture-network", "Ready for Codex"),
                workspace,
                "Do a capture no-op turn.",
                "worker-capture",
                event -> {});

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        JsonNode sandboxPolicy =
                json.readTree(Files.readString(capture)).path("params").path("sandboxPolicy");
        assertThat(sandboxPolicy.path("type").asText()).isEqualTo("workspaceWrite");
        assertThat(sandboxPolicy.path("networkAccess").asBoolean()).isTrue();
        assertThat(sandboxPolicy.path("writableRoots"))
                .extracting(JsonNode::asText)
                .contains(extraRoot.toAbsolutePath().normalize().toString());
    }

    @Test
    void sendsConfiguredModelAndReasoningEffortToCodexAppServer() throws Exception {
        // given
        Path capture = tempDir.resolve("model-requests.jsonl");
        Path appServer = writeExecutableScript(
                "model-capturing-app-server.sh",
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
        Path appServer = writeExecutableScript(
                "default-model-capturing-app-server.sh",
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
        Path appServer = writeExecutableScript(
                "multi-turn-app-server.sh",
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
        Path appServer = writeExecutableScript(
                "exiting-app-server.sh",
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
        Path appServer = writeExecutableScript(
                "exiting-before-request-response-app-server.sh",
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
        Path appServer = writeExecutableScript(
                "failed-turn-app-server.sh",
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
    void preservesGenericTurnFailureFormattingForNestedBadRequest() throws Exception {
        // given
        Path appServer = writeExecutableScript(
                "nested-bad-request-app-server.sh",
                """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo '{"id":1,"result":{"userAgent":"bad-request-test"}}' ;;
                    *\\"method\\":\\"thread/start\\"*) echo '{"id":2,"result":{"thread":{"id":"thread-bad-request"}}}' ;;
                    *\\"method\\":\\"turn/start\\"*)
                      echo '{"id":3,"result":{"turn":{"id":"turn-bad-request"}}}'
                      echo '{"method":"turn/failed","params":{"threadId":"thread-bad-request","turn":{"id":"turn-bad-request","error":{"message":"unknown model","codexErrorInfo":"badRequest"}}}}'
                      ;;
                  esac
                done
                """);

        // when
        AgentRunResult result = runFailureTurn(
                appServer, Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC), "nested-bad-request");

        // then
        assertThat(result)
                .isEqualTo(
                        AgentRunResult.fail(
                                "turn_failed: {\"threadId\":\"thread-bad-request\",\"turn\":{\"id\":\"turn-bad-request\",\"error\":{\"message\":\"unknown model\",\"codexErrorInfo\":\"badRequest\"}}}"));
    }

    @Test
    void failsTurnWhenAppServerEmitsTerminalErrorNotification() throws Exception {
        // given
        Path appServer = writeExecutableScript(
                "terminal-error-app-server.sh",
                """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  [[ "$line" =~ \\"id\\":([0-9]+) ]] && id="${BASH_REMATCH[1]}"
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"userAgent\\":\\"terminal-error-test\\"}}" ;;
                    *\\"method\\":\\"thread/start\\"*) echo "{\\"id\\":$id,\\"result\\":{\\"thread\\":{\\"id\\":\\"thread-error\\"}}}" ;;
                    *\\"method\\":\\"turn/start\\"*)
                      echo "{\\"id\\":$id,\\"result\\":{\\"turn\\":{\\"id\\":\\"turn-error\\"}}}"
                      echo "{\\"method\\":\\"error\\",\\"params\\":{\\"threadId\\":\\"thread-error\\",\\"turnId\\":\\"turn-error\\",\\"willRetry\\":false,\\"error\\":{\\"message\\":\\"model request failed\\",\\"codexErrorInfo\\":\\"badRequest\\"}}}"
                      ;;
                  esac
                done
                """);
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
    void classifiesCompletedUsageLimitAndUsesLatestExhaustedWindowResetWithoutLeakingDetails() throws Exception {
        // given
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        Instant primaryReset = now.plusSeconds(60);
        Instant secondaryReset = now.plusSeconds(120);
        Path appServer = writeUsageFixtureScript(
                "completed-usage-limit-app-server.sh",
                """
                echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":__PRIMARY_RESET__},"secondary":{"usedPercent":101,"resetsAt":__SECONDARY_RESET__}}}}'
                echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","items":[],"status":"failed","error":{"message":"Usage is unavailable.","additionalDetails":"private account payload","codexErrorInfo":"usageLimitExceeded"}}}}'
                """
                        .replace("__PRIMARY_RESET__", Long.toString(primaryReset.getEpochSecond()))
                        .replace("__SECONDARY_RESET__", Long.toString(secondaryReset.getEpochSecond())));

        // when
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        AgentRunResult result =
                runFailureTurn(appServer, Clock.fixed(now, ZoneOffset.UTC), "completed-usage", events::add);

        // then
        assertThat(result.failureCategory()).isEqualTo(AgentRunResult.FailureCategory.CODEX_USAGE_LIMIT);
        assertThat(result.reason()).isEqualTo("turn_failed: Usage is unavailable.");
        assertThat(result.reason()).doesNotContain("private account payload", "additionalDetails", "rateLimits");
        assertThat(result.retryNotBefore()).contains(secondaryReset);
        assertThat(events.stream()
                        .filter(event -> event.event().equals("turn/completed"))
                        .map(AgentEvent::message))
                .containsExactly("Usage is unavailable.")
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain("private account payload", "additionalDetails", "codexErrorInfo"));
    }

    @Test
    void rejectedRateLimitEventCannotSupplyAUsageRetryDeadline() throws Exception {
        // given
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        Instant rejectedReset = now.plusSeconds(120);
        Path appServer = writeUsageFixtureScript(
                "rejected-rate-limit-app-server.sh",
                """
                echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":__RESET__}}}}'
                echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","status":"failed","error":{"message":"Usage is unavailable.","codexErrorInfo":"usageLimitExceeded"}}}}'
                """
                        .replace("__RESET__", Long.toString(rejectedReset.getEpochSecond())));
        List<AgentEvent> acceptedEvents = new CopyOnWriteArrayList<>();
        AgentEventListener listener = new AgentEventListener() {
            @Override
            public void onEvent(AgentEvent event) {
                acceptedEvents.add(event);
            }

            @Override
            public boolean onEventAndReportAccepted(AgentEvent event) {
                if (event.event().equals("account/rateLimits/updated")) {
                    return false;
                }
                onEvent(event);
                return true;
            }
        };

        // when
        AgentRunResult result =
                runFailureTurn(appServer, Clock.fixed(now, ZoneOffset.UTC), "rejected-rate-limit", listener);

        // then
        assertThat(result.failureCategory()).isEqualTo(AgentRunResult.FailureCategory.CODEX_USAGE_LIMIT);
        assertThat(result.retryNotBefore()).isEmpty();
        assertThat(acceptedEvents).noneMatch(event -> event.event().equals("account/rateLimits/updated"));
    }

    @Test
    void preservesSchemaValidFarFutureUsageResetThatExceedsMillisecondDurationRange() throws Exception {
        // given
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        Instant farFutureReset = Instant.ofEpochSecond(Instant.MAX.getEpochSecond() - 1);
        Path appServer = writeUsageFixtureScript(
                "far-future-usage-limit-app-server.sh",
                """
                echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":__RESET__},"secondary":null}}}'
                echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","items":[],"status":"failed","error":{"message":"Usage is unavailable.","codexErrorInfo":"usageLimitExceeded"}}}}'
                """
                        .replace("__RESET__", Long.toString(farFutureReset.getEpochSecond())));

        // when
        AgentRunResult result = runFailureTurn(appServer, Clock.fixed(now, ZoneOffset.UTC), "far-future-usage");

        // then
        assertThat(result.failureCategory()).isEqualTo(AgentRunResult.FailureCategory.CODEX_USAGE_LIMIT);
        assertThat(result.retryNotBefore()).contains(farFutureReset);
    }

    @Test
    void mergesSparseRollingRateLimitWindowsWithoutClearingUnavailableValues() throws Exception {
        // given
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        Instant laterPrimaryReset = now.plusSeconds(120);
        Instant earlierSecondaryReset = now.plusSeconds(60);
        List<JsonNode> publishedRateLimits = new ArrayList<>();
        Path appServer = writeUsageFixtureScript(
                "sparse-rate-limit-updates-app-server.sh",
                """
                echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":__PRIMARY_RESET__}}}}'
                echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":null,"secondary":{"usedPercent":100,"resetsAt":__SECONDARY_RESET__}}}}'
                echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":null}}}}'
                echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","items":[],"status":"failed","error":{"message":"Usage is unavailable.","codexErrorInfo":"usageLimitExceeded"}}}}'
                """
                        .replace("__PRIMARY_RESET__", Long.toString(laterPrimaryReset.getEpochSecond()))
                        .replace("__SECONDARY_RESET__", Long.toString(earlierSecondaryReset.getEpochSecond())));

        // when
        AgentRunResult result =
                runFailureTurn(appServer, Clock.fixed(now, ZoneOffset.UTC), "sparse-rate-limits", event -> {
                    if ("account/rateLimits/updated".equals(event.event())) {
                        publishedRateLimits.add(event.payload());
                    }
                });

        // then
        assertThat(result.failureCategory()).isEqualTo(AgentRunResult.FailureCategory.CODEX_USAGE_LIMIT);
        assertThat(result.retryNotBefore()).contains(laterPrimaryReset);
        assertThat(publishedRateLimits).hasSize(3);
        assertThat(publishedRateLimits.get(1).path("primary").path("resetsAt").asLong())
                .isEqualTo(laterPrimaryReset.getEpochSecond());
        assertThat(publishedRateLimits.get(2).path("secondary").path("resetsAt").asLong())
                .isEqualTo(earlierSecondaryReset.getEpochSecond());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"rateLimits\":null", "", "\"rateLimits\":\"malformed\"", "\"rateLimits\":[]"})
    void malformedRateLimitUpdatesRetainCanonicalSnapshot(String malformedRateLimits) throws Exception {
        // given
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        Instant reset = now.plusSeconds(120);
        String notifications =
                """
                echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":__RESET__},"planType":"team"}}}'
                echo '{"method":"account/rateLimits/updated","params":{__MALFORMED_RATE_LIMITS__}}'
                """
                        .replace("__RESET__", Long.toString(reset.getEpochSecond()))
                        .replace("__MALFORMED_RATE_LIMITS__", malformedRateLimits);
        Path appServer = writeRateLimitNotificationScript(
                "malformed-after-valid-" + Integer.toUnsignedString(malformedRateLimits.hashCode()) + ".sh",
                notifications);
        List<JsonNode> publishedRateLimits = new ArrayList<>();

        // when
        AgentRunResult result = runFailureTurn(
                appServer,
                Clock.fixed(now, ZoneOffset.UTC),
                "malformed-after-valid-" + Integer.toUnsignedString(malformedRateLimits.hashCode()),
                event -> {
                    if ("account/rateLimits/updated".equals(event.event())) {
                        publishedRateLimits.add(event.payload());
                    }
                });

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        assertThat(publishedRateLimits).hasSize(2);
        JsonNode canonical = publishedRateLimits.get(0);
        assertThat(canonical.path("primary").path("resetsAt").asLong()).isEqualTo(reset.getEpochSecond());
        assertThat(canonical.path("planType").asText()).isEqualTo("team");
        assertThat(publishedRateLimits.get(1)).isEqualTo(canonical);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"rateLimits\":null", "", "\"rateLimits\":\"malformed\"", "\"rateLimits\":[]"})
    void malformedFirstRateLimitUpdatePublishesAbsentCanonicalState(String malformedRateLimits) throws Exception {
        // given
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        String notifications =
                """
                echo '{"method":"account/rateLimits/updated","params":{__MALFORMED_RATE_LIMITS__}}'
                """
                        .replace("__MALFORMED_RATE_LIMITS__", malformedRateLimits);
        Path appServer = writeRateLimitNotificationScript(
                "malformed-first-" + Integer.toUnsignedString(malformedRateLimits.hashCode()) + ".sh", notifications);
        List<JsonNode> publishedRateLimits = new ArrayList<>();

        // when
        AgentRunResult result = runFailureTurn(
                appServer,
                Clock.fixed(now, ZoneOffset.UTC),
                "malformed-first-" + Integer.toUnsignedString(malformedRateLimits.hashCode()),
                event -> {
                    if ("account/rateLimits/updated".equals(event.event())) {
                        publishedRateLimits.add(event.payload());
                    }
                });

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        assertThat(publishedRateLimits).singleElement().satisfies(payload -> assertThat(payload.isNull())
                .isTrue());
    }

    @Test
    void sharesSparseRateLimitsAndMetadataAcrossSequentialAppServerSessions() throws Exception {
        // given
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        Instant laterPrimaryReset = now.plusSeconds(120);
        Instant earlierSecondaryReset = now.plusSeconds(60);
        Path firstSessionMarker = tempDir.resolve("sequential-rate-limit-first-session");
        List<JsonNode> publishedRateLimits = new ArrayList<>();
        Path appServer = writeUsageFixtureScript(
                "sequential-shared-rate-limit-app-server.sh",
                """
                if mkdir '__FIRST_SESSION_MARKER__' 2>/dev/null; then
                  session=first
                else
                  session=second
                fi
                """
                        .replace("__FIRST_SESSION_MARKER__", firstSessionMarker.toString()),
                """
                if [ "$session" = first ]; then
                  echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":__PRIMARY_RESET__},"credits":{"balance":"7","hasCredits":true,"unlimited":false},"planType":"team","limitId":"limit-a","limitName":"Initial limit","individualLimit":{"limit":"100","remainingPercent":25,"resetsAt":__PRIMARY_RESET__,"used":"75"},"rateLimitReachedType":"rate_limit_reached","futureMetadata":{"opaque":"preserve-me"}}}}'
                  echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","items":[],"status":"completed","error":null}}}'
                else
                  echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":null,"secondary":{"usedPercent":100,"resetsAt":__SECONDARY_RESET__},"credits":null,"limitId":null,"limitName":"Updated limit","individualLimit":null,"rateLimitReachedType":null,"futureMetadata":null,"futureAdded":"available"}}}'
                  echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","items":[],"status":"failed","error":{"message":"Usage is unavailable.","codexErrorInfo":"usageLimitExceeded"}}}}'
                fi
                """
                        .replace("__PRIMARY_RESET__", Long.toString(laterPrimaryReset.getEpochSecond()))
                        .replace("__SECONDARY_RESET__", Long.toString(earlierSecondaryReset.getEpochSecond())));
        EffectiveConfig config = config(appServer);
        CodexAppServerClient client = new CodexAppServerClient(
                json, new TrelloHandoffToolHandler(json, new TrelloClient(json)), Clock.fixed(now, ZoneOffset.UTC));
        AgentEventListener listener = event -> {
            if ("account/rateLimits/updated".equals(event.event())) {
                publishedRateLimits.add(event.payload());
            }
        };

        // when
        AgentRunResult first = runTurn(client, config, "sequential-rate-limit-first", listener);
        AgentRunResult second = runTurn(client, config, "sequential-rate-limit-second", listener);

        // then
        assertThat(first).isEqualTo(AgentRunResult.ok());
        assertThat(second.failureCategory()).isEqualTo(AgentRunResult.FailureCategory.CODEX_USAGE_LIMIT);
        assertThat(second.retryNotBefore()).contains(laterPrimaryReset);
        assertThat(publishedRateLimits).hasSize(2);
        JsonNode merged = publishedRateLimits.get(1);
        assertThat(merged.path("primary").path("resetsAt").asLong()).isEqualTo(laterPrimaryReset.getEpochSecond());
        assertThat(merged.path("secondary").path("resetsAt").asLong())
                .isEqualTo(earlierSecondaryReset.getEpochSecond());
        assertThat(merged.path("credits").path("balance").asText()).isEqualTo("7");
        assertThat(merged.path("planType").asText()).isEqualTo("team");
        assertThat(merged.path("limitId").asText()).isEqualTo("limit-a");
        assertThat(merged.path("limitName").asText()).isEqualTo("Updated limit");
        assertThat(merged.path("individualLimit").path("used").asText()).isEqualTo("75");
        assertThat(merged.path("rateLimitReachedType").asText()).isEqualTo("rate_limit_reached");
        assertThat(merged.path("futureMetadata").path("opaque").asText()).isEqualTo("preserve-me");
        assertThat(merged.path("futureAdded").asText()).isEqualTo("available");
    }

    @Test
    void isolatesRateLimitSnapshotsForDifferentConfiguredCodexCommands() throws Exception {
        // given
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        Instant firstAccountReset = now.plusSeconds(120);
        Instant secondAccountReset = now.plusSeconds(60);
        Path firstAppServer = writeUsageFixtureScript(
                "first-account-rate-limit-app-server.sh",
                """
                echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":__FIRST_RESET__},"planType":"first-account"}}}'
                echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","items":[],"status":"completed","error":null}}}'
                """
                        .replace("__FIRST_RESET__", Long.toString(firstAccountReset.getEpochSecond())));
        Path secondAppServer = writeUsageFixtureScript(
                "second-account-rate-limit-app-server.sh",
                """
                echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"secondary":{"usedPercent":100,"resetsAt":__SECOND_RESET__}}}}'
                echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","items":[],"status":"failed","error":{"message":"Usage is unavailable.","codexErrorInfo":"usageLimitExceeded"}}}}'
                """
                        .replace("__SECOND_RESET__", Long.toString(secondAccountReset.getEpochSecond())));
        EffectiveConfig firstConfig = config(firstAppServer);
        EffectiveConfig secondConfig = config(secondAppServer);
        List<JsonNode> publishedRateLimits = new ArrayList<>();
        CodexAppServerClient client = new CodexAppServerClient(
                json, new TrelloHandoffToolHandler(json, new TrelloClient(json)), Clock.fixed(now, ZoneOffset.UTC));
        AgentEventListener listener = event -> {
            if ("account/rateLimits/updated".equals(event.event())) {
                publishedRateLimits.add(event.payload());
            }
        };

        // when
        AgentRunResult first = runTurn(client, firstConfig, "first-rate-limit-account", listener);
        AgentRunResult second = runTurn(client, secondConfig, "second-rate-limit-account", listener);

        // then
        assertThat(first).isEqualTo(AgentRunResult.ok());
        assertThat(second.failureCategory()).isEqualTo(AgentRunResult.FailureCategory.CODEX_USAGE_LIMIT);
        assertThat(second.retryNotBefore()).contains(secondAccountReset);
        assertThat(publishedRateLimits).hasSize(2);
        assertThat(publishedRateLimits.get(1).has("primary")).isFalse();
        assertThat(publishedRateLimits.get(1).has("planType")).isFalse();
    }

    @Test
    void atomicallySharesConcurrentSparseRateLimitsWithUsageDeadline() throws Exception {
        // given
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        Instant laterPrimaryReset = now.plusSeconds(120);
        Instant earlierSecondaryReset = now.plusSeconds(60);
        Path primaryRole = tempDir.resolve("concurrent-primary-role");
        Path primaryReady = tempDir.resolve("concurrent-primary-ready");
        Path secondaryReady = tempDir.resolve("concurrent-secondary-ready");
        Path primaryObserved = tempDir.resolve("concurrent-primary-observed");
        Path secondaryObserved = tempDir.resolve("concurrent-secondary-observed");
        List<JsonNode> publishedRateLimits = new CopyOnWriteArrayList<>();
        Path appServer = writeUsageFixtureScript(
                "concurrent-shared-rate-limit-app-server.sh",
                """
                if mkdir '__PRIMARY_ROLE__' 2>/dev/null; then
                  session=primary
                else
                  session=secondary
                fi
                """
                        .replace("__PRIMARY_ROLE__", primaryRole.toString()),
                """
                if [ "$session" = primary ]; then
                  touch '__PRIMARY_READY__'
                else
                  touch '__SECONDARY_READY__'
                fi
                while [ ! -f '__PRIMARY_READY__' ] || [ ! -f '__SECONDARY_READY__' ]; do sleep 0.01; done
                if [ "$session" = primary ]; then
                  echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":__PRIMARY_RESET__}}}}'
                else
                  echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"secondary":{"usedPercent":100,"resetsAt":__SECONDARY_RESET__}}}}'
                fi
                while [ ! -f '__PRIMARY_OBSERVED__' ] || [ ! -f '__SECONDARY_OBSERVED__' ]; do sleep 0.01; done
                if [ "$session" = primary ]; then
                  echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","items":[],"status":"completed","error":null}}}'
                else
                  echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","items":[],"status":"failed","error":{"message":"Usage is unavailable.","codexErrorInfo":"usageLimitExceeded"}}}}'
                fi
                """
                        .replace("__PRIMARY_READY__", primaryReady.toString())
                        .replace("__SECONDARY_READY__", secondaryReady.toString())
                        .replace("__PRIMARY_OBSERVED__", primaryObserved.toString())
                        .replace("__SECONDARY_OBSERVED__", secondaryObserved.toString())
                        .replace("__PRIMARY_RESET__", Long.toString(laterPrimaryReset.getEpochSecond()))
                        .replace("__SECONDARY_RESET__", Long.toString(earlierSecondaryReset.getEpochSecond())));
        EffectiveConfig config =
                config(Map.of("command", appServer.toString(), "read_timeout_ms", 5000, "turn_timeout_ms", 5000));
        CodexAppServerClient client = new CodexAppServerClient(
                json, new TrelloHandoffToolHandler(json, new TrelloClient(json)), Clock.fixed(now, ZoneOffset.UTC));
        AgentEventListener listener = event -> {
            if (!"account/rateLimits/updated".equals(event.event())) {
                return;
            }
            publishedRateLimits.add(event.payload());
            if (event.payload().path("primary").isObject()) {
                markObserved(primaryObserved);
            }
            if (event.payload().path("secondary").isObject()) {
                markObserved(secondaryObserved);
            }
        };
        Path firstWorkspace = workspace("concurrent-rate-limit-first");
        Path secondWorkspace = workspace("concurrent-rate-limit-second");

        // when
        List<AgentRunResult> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> client.runTurn(
                    config,
                    TestCards.card("card-1", "TRELLO-concurrent-rate-limit-first", "Ready for Codex"),
                    firstWorkspace,
                    "Run the concurrent fixture.",
                    "worker-concurrent-rate-limit-first",
                    listener));
            var second = executor.submit(() -> client.runTurn(
                    config,
                    TestCards.card("card-2", "TRELLO-concurrent-rate-limit-second", "Ready for Codex"),
                    secondWorkspace,
                    "Run the concurrent fixture.",
                    "worker-concurrent-rate-limit-second",
                    listener));
            results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }

        // then
        assertThat(results).contains(AgentRunResult.ok());
        AgentRunResult usageLimit = results.stream()
                .filter(result -> result.failureCategory() == AgentRunResult.FailureCategory.CODEX_USAGE_LIMIT)
                .findFirst()
                .orElseThrow();
        assertThat(usageLimit.retryNotBefore()).contains(laterPrimaryReset);
        assertThat(publishedRateLimits).hasSize(2);
        JsonNode latestPublished = publishedRateLimits.get(publishedRateLimits.size() - 1);
        assertThat(latestPublished.path("primary").path("resetsAt").asLong())
                .isEqualTo(laterPrimaryReset.getEpochSecond());
        assertThat(latestPublished.path("secondary").path("resetsAt").asLong())
                .isEqualTo(earlierSecondaryReset.getEpochSecond());
    }

    @ParameterizedTest(name = "accepted={0}")
    @ValueSource(booleans = {true, false})
    void usageDeadlineWaitsForAnInFlightRateLimitUpdateDecision(boolean accepted) throws Exception {
        // given
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        Instant reset = now.plusSeconds(120);
        Path rateLimitRole = tempDir.resolve("inflight-rate-limit-role");
        Path acceptanceEntered = tempDir.resolve("inflight-rate-limit-acceptance-entered");
        Path appServer = writeUsageFixtureScript(
                "inflight-accepted-rate-limit-app-server.sh",
                """
                if mkdir '__RATE_LIMIT_ROLE__' 2>/dev/null; then
                  session=rate-limit
                else
                  session=usage
                fi
                """
                        .replace("__RATE_LIMIT_ROLE__", rateLimitRole.toString()),
                """
                if [ "$session" = rate-limit ]; then
                  echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":__RESET__}}}}'
                  echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","status":"completed","error":null}}}'
                else
                  while [ ! -f '__ACCEPTANCE_ENTERED__' ]; do sleep 0.01; done
                  echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","status":"failed","error":{"message":"Usage is unavailable.","codexErrorInfo":"usageLimitExceeded"}}}}'
                fi
                """
                        .replace("__ACCEPTANCE_ENTERED__", acceptanceEntered.toString())
                        .replace("__RESET__", Long.toString(reset.getEpochSecond())));
        EffectiveConfig config =
                config(Map.of("command", appServer.toString(), "read_timeout_ms", 5000, "turn_timeout_ms", 5000));
        CodexAppServerClient client = new CodexAppServerClient(
                json, new TrelloHandoffToolHandler(json, new TrelloClient(json)), Clock.fixed(now, ZoneOffset.UTC));
        CountDownLatch releaseAcceptance = new CountDownLatch(1);
        CountDownLatch usageFailurePublished = new CountDownLatch(1);
        AtomicReference<String> usageWorker = new AtomicReference<>();
        AgentEventListener listener = new AgentEventListener() {
            @Override
            public void onEvent(AgentEvent event) {
                if (isTypedUsageFailure(event)) {
                    usageWorker.set(event.workerIdentity());
                    usageFailurePublished.countDown();
                }
            }

            @Override
            public boolean onEventAndReportAccepted(AgentEvent event) {
                if (event.event().equals("account/rateLimits/updated")) {
                    markObserved(acceptanceEntered);
                    try {
                        assertThat(releaseAcceptance.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                onEvent(event);
                return accepted;
            }
        };

        // when
        List<AgentRunResult> results;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AgentRunResult> first =
                    executor.submit(() -> runTurn(client, config, "inflight-rate-limit-first", listener));
            Future<AgentRunResult> second =
                    executor.submit(() -> runTurn(client, config, "inflight-rate-limit-second", listener));
            try {
                assertThat(usageFailurePublished.await(5, TimeUnit.SECONDS)).isTrue();
                Future<AgentRunResult> usageResult = usageWorker.get().endsWith("first") ? first : second;
                boolean completedBeforeDecision;
                try {
                    usageResult.get(500, TimeUnit.MILLISECONDS);
                    completedBeforeDecision = true;
                } catch (TimeoutException expectedWhileUpdateIsInFlight) {
                    completedBeforeDecision = false;
                }
                assertThat(completedBeforeDecision).isFalse();
            } finally {
                releaseAcceptance.countDown();
            }
            results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }

        // then
        AgentRunResult usageLimit = results.stream()
                .filter(result -> result.failureCategory() == AgentRunResult.FailureCategory.CODEX_USAGE_LIMIT)
                .findFirst()
                .orElseThrow();
        if (accepted) {
            assertThat(usageLimit.retryNotBefore()).contains(reset);
        } else {
            assertThat(usageLimit.retryNotBefore()).isEmpty();
        }
    }

    @Test
    void classifiesTerminalUsageLimitWithoutInventingResetFromMessageOrMalformedWindows() throws Exception {
        // given
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        Path appServer = writeUsageFixtureScript(
                "terminal-usage-limit-app-server.sh",
                """
                echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":__OVERSIZED_RESET__},"secondary":{"usedPercent":2147483648,"resetsAt":4102444800}}}}'
                echo '{"method":"error","params":{"threadId":"thread-usage","turnId":"turn-usage","willRetry":false,"error":{"message":"Try again tomorrow at a localized time.","additionalDetails":"private provider payload","codexErrorInfo":"usageLimitExceeded"}}}'
                """
                        .replace("__OVERSIZED_RESET__", "9".repeat(24)));

        // when
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        AgentRunResult result =
                runFailureTurn(appServer, Clock.fixed(now, ZoneOffset.UTC), "terminal-usage", events::add);

        // then
        assertThat(result.failureCategory()).isEqualTo(AgentRunResult.FailureCategory.CODEX_USAGE_LIMIT);
        assertThat(result.reason()).isEqualTo("turn_failed: Try again tomorrow at a localized time.");
        assertThat(result.reason()).doesNotContain("private provider payload", "additionalDetails");
        assertThat(result.retryNotBefore()).isEmpty();
        assertThat(events.stream()
                        .filter(event -> event.event().equals("error"))
                        .map(AgentEvent::message))
                .containsExactly("Try again tomorrow at a localized time.")
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain("private provider payload", "additionalDetails", "codexErrorInfo"));
    }

    @Test
    void keepsMessageOnlyUsageWordingGeneric() throws Exception {
        // given
        Path appServer = writeUsageFixtureScript(
                "message-only-usage-app-server.sh",
                """
                echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","items":[],"status":"failed","error":{"message":"You have hit your usage limit."}}}}'
                """);

        // when
        AgentRunResult result = runFailureTurn(
                appServer, Clock.fixed(Instant.parse("2026-07-10T12:00:00Z"), ZoneOffset.UTC), "message-only-usage");

        // then
        assertThat(result).isEqualTo(AgentRunResult.fail("turn_failed: You have hit your usage limit."));
    }

    @Test
    void failsTurnWhenAppServerEmitsTurnCancellationNotification() throws Exception {
        // given
        Path appServer = writeExecutableScript(
                "cancelled-turn-app-server.sh",
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
        Path appServer = writeExecutableScript(
                "interrupted-turn-app-server.sh",
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

    private AgentRunResult runFailureTurn(Path appServer, Clock clock, String identifier) throws Exception {
        return runFailureTurn(appServer, clock, identifier, event -> {});
    }

    private AgentRunResult runFailureTurn(Path appServer, Clock clock, String identifier, AgentEventListener listener)
            throws Exception {
        EffectiveConfig config = config(appServer);
        CodexAppServerClient client =
                new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)), clock);
        return runTurn(client, config, identifier, listener);
    }

    private AgentRunResult runTurn(
            CodexAppServerClient client, EffectiveConfig config, String identifier, AgentEventListener listener)
            throws Exception {
        return client.runTurn(
                config,
                TestCards.card("card-1", "TRELLO-" + identifier, "Ready for Codex"),
                workspace(identifier),
                "Run the failure fixture.",
                "worker-" + identifier,
                listener);
    }

    private Path workspace(String identifier) throws IOException {
        Path workspace = tempDir.resolve("workspaces").resolve("TRELLO-" + identifier);
        Files.createDirectories(workspace);
        return workspace;
    }

    private void markObserved(Path marker) {
        try {
            Files.writeString(marker, "observed");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isTypedUsageFailure(AgentEvent event) {
        return event.event().equals("turn/completed")
                && event.payload() != null
                && event.payload().at("/turn/error/codexErrorInfo").asText().equals("usageLimitExceeded");
    }

    private Path writeRateLimitNotificationScript(String fileName, String notifications) throws Exception {
        return writeUsageFixtureScript(
                fileName,
                """
                __RATE_LIMIT_NOTIFICATIONS__
                echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","items":[],"status":"completed","error":null}}}'
                """
                        .replace("__RATE_LIMIT_NOTIFICATIONS__", notifications));
    }

    private Path writeUsageFixtureScript(String fileName, String turnResponses) throws Exception {
        return writeUsageFixtureScript(fileName, "", turnResponses);
    }

    private Path writeUsageFixtureScript(String fileName, String setup, String turnResponses) throws Exception {
        return writeExecutableScript(
                fileName,
                """
                #!/usr/bin/env bash
                __SETUP__
                while IFS= read -r line; do
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo '{"id":1,"result":{"userAgent":"usage-test"}}' ;;
                    *\\"method\\":\\"thread/start\\"*) echo '{"id":2,"result":{"thread":{"id":"thread-usage"}}}' ;;
                    *\\"method\\":\\"turn/start\\"*)
                      echo '{"id":3,"result":{"turn":{"id":"turn-usage"}}}'
                      __TURN_RESPONSES__
                      ;;
                  esac
                done
                """
                        .replace("__SETUP__", setup)
                        .replace("__TURN_RESPONSES__", turnResponses));
    }

    private Path writeExecutableScript(String fileName, String content) throws Exception {
        Path script = tempDir.resolve(fileName);
        Files.writeString(script, content);
        script.toFile().setExecutable(true);
        return script;
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
