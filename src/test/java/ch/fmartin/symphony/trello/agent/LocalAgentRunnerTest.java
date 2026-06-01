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
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrackerClient;
import ch.fmartin.symphony.trello.workflow.WorkflowDefinition;
import ch.fmartin.symphony.trello.workspace.CodexSkillInstaller;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

final class LocalAgentRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void createsWorkspaceRunsHooksAndPassesPromptToCodexClient() throws Exception {
        // given
        CodexAppServerClient codex = mock();
        when(codex.runSession(any(), any(), any(), any(), any(), any(), any())).thenReturn(AgentRunResult.ok());
        var runner = runner(codex, CardLookupResult.Found::new);
        EffectiveConfig config = config(Map.of(
                "before_run", "rm -rf .codex && echo before > before.txt", "after_run", "echo after > after.txt"));
        Card card = TestCards.card("card-1", "TRELLO-local", "Ready for Codex");
        String renderedPrompt = "Use " + CodexSkillInstaller.installedSkillPath("commit");

        // when
        AgentRunResult result = runner.run(
                new AgentRunner.AgentRunRequest(card, null, renderedPrompt, config, "worker-success", event -> {}));

        // then
        ArgumentCaptor<Path> workspace = ArgumentCaptor.forClass(Path.class);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        Path expectedWorkspace = config.workspace().root().resolve("TRELLO-local");
        assertThat(result).isEqualTo(AgentRunResult.ok());
        verify(codex)
                .runSession(
                        eq(config),
                        eq(card),
                        workspace.capture(),
                        prompt.capture(),
                        eq("worker-success"),
                        any(),
                        any());
        assertThat(prompt.getValue()).isEqualTo(renderedPrompt);
        assertThat(workspace.getValue())
                .isEqualTo(expectedWorkspace.toAbsolutePath().normalize());
        assertThat(Files.readString(expectedWorkspace.resolve("before.txt"))).contains("before");
        assertThat(Files.readString(expectedWorkspace.resolve("after.txt"))).contains("after");
        assertThat(expectedWorkspace.resolve(CodexSkillInstaller.installedSkillPath("commit")))
                .content()
                .contains("configure it from the authenticated GitHub login");
    }

    @Test
    void leavesLegacyWorkspaceEmptyWhenPromptDoesNotReferenceBundledSkills() throws Exception {
        // given
        CodexAppServerClient codex = mock();
        when(codex.runSession(any(), any(), any(), any(), any(), any(), any())).thenReturn(AgentRunResult.ok());
        var runner = runner(codex, CardLookupResult.Found::new);
        EffectiveConfig config = config(Map.of());
        Card card = TestCards.card("card-1", "TRELLO-legacy", "Ready for Codex");

        // when
        AgentRunResult result = runner.run(
                new AgentRunner.AgentRunRequest(card, null, "legacy prompt", config, "worker-legacy", event -> {}));

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        assertThat(config.workspace().root().resolve("TRELLO-legacy").resolve(".codex"))
                .doesNotExist();
    }

    @Test
    void returnsFailureWhenRequiredHookFailsAndStillRemovesWorkerFromActiveSet() throws Exception {
        // given
        CodexAppServerClient codex = mock();
        var runner = runner(codex, CardLookupResult.Found::new);
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
        CodexAppServerClient codex = mock();
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
                .runSession(any(), any(), any(), any(), any(), any(), any());
        var runner = runner(codex, CardLookupResult.Found::new);
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

    @Test
    void continuesSameSessionWhileCardRemainsActiveAndMaxTurnsAllowsIt() throws Exception {
        // given
        CodexAppServerClient codex = mock();
        AtomicReference<CodexAppServerClient.TurnController> controller = new AtomicReference<>();
        when(codex.runSession(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            controller.set(invocation.getArgument(6));
            return AgentRunResult.ok();
        });
        var runner = runner(codex, CardLookupResult.Found::new);
        EffectiveConfig config = config(Map.of(), Map.of("max_turns", 2));

        // when
        AgentRunResult result = runner.run(new AgentRunner.AgentRunRequest(
                TestCards.card("card-1", "TRELLO-active", "Ready for Codex"),
                null,
                "prompt",
                config,
                "worker-multiturn",
                event -> {}));

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        assertThat(controller.get().afterSuccessfulTurn(1).nextPrompt()).contains("continuation turn 2 of at most 2");
        assertThat(controller.get().afterSuccessfulTurn(2)).isEqualTo(CodexAppServerClient.TurnDecision.stop());
    }

    @Test
    void stopsSameSessionWhenCardLeavesActiveStates() throws Exception {
        // given
        CodexAppServerClient codex = mock();
        AtomicReference<CodexAppServerClient.TurnController> controller = new AtomicReference<>();
        when(codex.runSession(any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            controller.set(invocation.getArgument(6));
            return AgentRunResult.ok();
        });
        var runner = runner(
                codex,
                ignored -> new CardLookupResult.Found(TestCards.card("card-1", "TRELLO-review", "Human Review")));
        EffectiveConfig config = config(Map.of(), Map.of("max_turns", 2));

        // when
        AgentRunResult result = runner.run(new AgentRunner.AgentRunRequest(
                TestCards.card("card-1", "TRELLO-review", "Ready for Codex"),
                null,
                "prompt",
                config,
                "worker-stop",
                event -> {}));

        // then
        assertThat(result).isEqualTo(AgentRunResult.ok());
        assertThat(controller.get().afterSuccessfulTurn(1)).isEqualTo(CodexAppServerClient.TurnDecision.stop());
    }

    private LocalAgentRunner runner(CodexAppServerClient codex, CardLookup lookup) {
        return new LocalAgentRunner(
                new WorkspaceManager(new HookRunner()), new HookRunner(), codex, tracker(lookup), new PromptRenderer());
    }

    private TrackerClient tracker(CardLookup lookup) {
        return new TrackerClient() {
            @Override
            public String resolveBoardId(EffectiveConfig config) {
                return config.tracker().boardId();
            }

            @Override
            public List<Card> fetchCandidateCards(EffectiveConfig config) {
                return List.of();
            }

            @Override
            public List<Card> fetchTerminalCards(EffectiveConfig config) {
                return List.of();
            }

            @Override
            public Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds) {
                return Map.of(
                        cardIds.getFirst(),
                        lookup.lookup(TestCards.card(cardIds.getFirst(), "TRELLO-card", "Ready for Codex")));
            }
        };
    }

    private EffectiveConfig config(Map<String, String> hooks) {
        return config(hooks, Map.of());
    }

    private EffectiveConfig config(Map<String, String> hooks, Map<String, Object> agent) {
        return new ConfigResolver()
                .resolve(new WorkflowDefinition(
                        tempDir.resolve("WORKFLOW.md"),
                        Map.of(
                                "tracker",
                                Map.of(
                                        "kind",
                                        "trello",
                                        "api_key",
                                        "key",
                                        "api_token",
                                        "token",
                                        "board_id",
                                        "board-1",
                                        "active_states",
                                        List.of("Ready for Codex", "In Progress"),
                                        "terminal_states",
                                        List.of("Done")),
                                "workspace",
                                Map.of("root", tempDir.resolve("workspaces").toString()),
                                "agent",
                                agent,
                                "hooks",
                                hooks),
                        ""));
    }

    @FunctionalInterface
    private interface CardLookup {
        CardLookupResult lookup(Card card);
    }
}
