package com.openai.symphony.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.symphony.TestCards;
import com.openai.symphony.config.ConfigResolver;
import com.openai.symphony.config.EffectiveConfig;
import com.openai.symphony.domain.Card;
import com.openai.symphony.tracker.TrelloClient;
import com.openai.symphony.workflow.WorkflowDefinition;
import com.openai.symphony.workspace.HookRunner;
import com.openai.symphony.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAgentRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void createsWorkspaceRunsHooksAndPassesPromptToCodexClient() throws Exception {
        // given
        RecordingCodexClient codex = new RecordingCodexClient();
        var runner = new LocalAgentRunner(new WorkspaceManager(new HookRunner()), new HookRunner(), codex);
        EffectiveConfig config = config(Map.of(
                "before_run", "echo before > before.txt",
                "after_run", "echo after > after.txt"));
        Card card = TestCards.card("card-1", "TRELLO-local", "Ready for Codex");

        // when
        AgentRunResult result = runner.run(
                new AgentRunner.AgentRunRequest(card, null, "handoff prompt", config, "worker-success", event -> {}));

        // then
        Path workspace = config.workspace().root().resolve("TRELLO-local");
        assertThat(result).isEqualTo(AgentRunResult.ok());
        assertThat(codex.prompt).hasValue("handoff prompt");
        assertThat(codex.workspace).hasValue(workspace.toAbsolutePath().normalize());
        assertThat(Files.readString(workspace.resolve("before.txt"))).contains("before");
        assertThat(Files.readString(workspace.resolve("after.txt"))).contains("after");
    }

    @Test
    void returnsFailureWhenRequiredHookFailsAndStillRemovesWorkerFromActiveSet() throws Exception {
        // given
        RecordingCodexClient codex = new RecordingCodexClient();
        var runner = new LocalAgentRunner(new WorkspaceManager(new HookRunner()), new HookRunner(), codex);
        EffectiveConfig config = config(Map.of("before_run", "echo broken && exit 9"));

        // when
        AgentRunResult result = runner.run(new AgentRunner.AgentRunRequest(
                TestCards.card("card-1", "TRELLO-hook", "Ready for Codex"),
                null,
                "prompt",
                config,
                "worker-failed-hook",
                event -> {}));
        runner.cancel("worker-failed-hook");

        // then
        assertThat(result.success()).isFalse();
        assertThat(result.reason()).contains("Hook before_run failed");
        assertThat(codex.prompt).hasValue(null);
    }

    @Test
    void cancelInterruptsActiveWorkerThread() throws Exception {
        // given
        BlockingCodexClient codex = new BlockingCodexClient();
        var runner = new LocalAgentRunner(new WorkspaceManager(new HookRunner()), new HookRunner(), codex);
        EffectiveConfig config = config(Map.of());
        var result = new AtomicReference<AgentRunResult>();
        Thread worker = Thread.ofVirtual()
                .start(() -> result.set(runner.run(new AgentRunner.AgentRunRequest(
                        TestCards.card("card-1", "TRELLO-cancel", "Ready for Codex"),
                        null,
                        "prompt",
                        config,
                        "worker-cancel",
                        event -> {}))));
        assertThat(codex.entered.await(5, TimeUnit.SECONDS)).isTrue();

        // when
        runner.cancel("worker-cancel");
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // then
        assertThat(codex.interrupted).isTrue();
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().reason()).isEqualTo("interrupted");
    }

    private EffectiveConfig config(Map<String, String> hooks) {
        return new ConfigResolver()
                .resolve(new WorkflowDefinition(
                        tempDir.resolve("WORKFLOW.md"),
                        Map.of(
                                "tracker",
                                Map.of("kind", "trello", "api_key", "key", "api_token", "token", "board_id", "board"),
                                "workspace",
                                Map.of("root", tempDir.resolve("workspaces").toString()),
                                "hooks",
                                hooks),
                        ""));
    }

    private static class RecordingCodexClient extends CodexAppServerClient {
        private final AtomicReference<Path> workspace = new AtomicReference<>();
        private final AtomicReference<String> prompt = new AtomicReference<>();

        private RecordingCodexClient() {
            super(
                    new ObjectMapper(),
                    new TrelloHandoffToolHandler(new ObjectMapper(), new TrelloClient(new ObjectMapper())));
        }

        @Override
        public AgentRunResult runTurn(
                EffectiveConfig config,
                Card card,
                Path workspace,
                String prompt,
                String workerIdentity,
                AgentEventListener listener) {
            this.workspace.set(workspace);
            this.prompt.set(prompt);
            return AgentRunResult.ok();
        }
    }

    private static final class BlockingCodexClient extends RecordingCodexClient {
        private final CountDownLatch entered = new CountDownLatch(1);
        private volatile boolean interrupted;

        @Override
        public AgentRunResult runTurn(
                EffectiveConfig config,
                Card card,
                Path workspace,
                String prompt,
                String workerIdentity,
                AgentEventListener listener) {
            entered.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                return AgentRunResult.ok();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                interrupted = true;
                return AgentRunResult.fail("interrupted");
            }
        }
    }
}
