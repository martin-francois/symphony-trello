package ch.fmartin.symphony.trello.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class LocalAgentRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void createsWorkspaceRunsHooksAndPassesPromptToCodexClient() throws Exception {
        // given
        CodexAppServerClient codex = mock(CodexAppServerClient.class);
        when(codex.runTurn(any(), any(), any(), any(), any(), any())).thenReturn(AgentRunResult.ok());
        var runner = new LocalAgentRunner(new WorkspaceManager(new HookRunner()), new HookRunner(), codex);
        EffectiveConfig config = config(Map.of(
                "before_run", "echo before > before.txt",
                "after_run", "echo after > after.txt"));
        Card card = TestCards.card("card-1", "TRELLO-local", "Ready for Codex");

        // when
        AgentRunResult result = runner.run(
                new AgentRunner.AgentRunRequest(card, null, "handoff prompt", config, "worker-success", event -> {}));

        // then
        ArgumentCaptor<Path> workspace = ArgumentCaptor.forClass(Path.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        Path expectedWorkspace = config.workspace().root().resolve("TRELLO-local");
        assertThat(result).isEqualTo(AgentRunResult.ok());
        verify(codex).runTurn(eq(config), eq(card), workspace.capture(), prompt.capture(), eq("worker-success"), any());
        assertThat(prompt.getValue()).isEqualTo("handoff prompt");
        assertThat(workspace.getValue())
                .isEqualTo(expectedWorkspace.toAbsolutePath().normalize());
        assertThat(Files.readString(expectedWorkspace.resolve("before.txt"))).contains("before");
        assertThat(Files.readString(expectedWorkspace.resolve("after.txt"))).contains("after");
    }

    @Test
    void returnsFailureWhenRequiredHookFailsAndStillRemovesWorkerFromActiveSet() throws Exception {
        // given
        CodexAppServerClient codex = mock(CodexAppServerClient.class);
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
        verifyNoInteractions(codex);
    }

    @Test
    void cancelInterruptsActiveWorkerThread() throws Exception {
        // given
        CodexAppServerClient codex = mock(CodexAppServerClient.class);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        doAnswer(invocation -> {
                    entered.countDown();
                    try {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                        return AgentRunResult.ok();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        interrupted.set(true);
                        return AgentRunResult.fail("interrupted");
                    }
                })
                .when(codex)
                .runTurn(any(), any(), any(), any(), any(), any());
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
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // when
        runner.cancel("worker-cancel");
        worker.join(TimeUnit.SECONDS.toMillis(5));

        // then
        assertThat(interrupted).hasValue(true);
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
}
