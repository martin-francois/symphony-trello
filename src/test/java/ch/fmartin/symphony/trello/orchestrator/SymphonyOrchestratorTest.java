package ch.fmartin.symphony.trello.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.agent.AgentEvent;
import ch.fmartin.symphony.trello.agent.AgentRunResult;
import ch.fmartin.symphony.trello.agent.AgentRunner;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrackerClient;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymphonyOrchestratorTest {
    @TempDir
    Path tempDir;

    @Test
    void dispatchesEligibleCardAndQueuesContinuationRetryAfterNormalExit() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock(AgentRunner.class);
        when(runner.run(any())).thenReturn(AgentRunResult.ok());
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                runner,
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()));
        orchestrator.workflowPath = workflow;

        // when
        orchestrator.start();
        Thread.sleep(500);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.counts().retrying()).isEqualTo(1);
        assertThat(snapshot.retrying()).singleElement().satisfies(row -> {
            assertThat(row.cardIdentifier()).isEqualTo("TRELLO-abc");
            assertThat(row.attempt()).isEqualTo(1);
        });
    }

    @Test
    void refreshesSelectedCardBeforeRenderingPrompt() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                "",
                """
                {% for comment in card.comments %}{{ comment.text }}{% endfor %}
                """);
        Card listedCard = TestCards.card("card-1", "TRELLO-abc", "Todo");
        Card refreshedCard = TestCards.cardWithComments(
                "card-1",
                "TRELLO-abc",
                "Todo",
                List.of(new Card.Comment("comment-1", "Review the edge case.", "Reviewer", Instant.now())));
        FakeTracker tracker = new FakeTracker(List.of(listedCard));
        tracker.setCardState(refreshedCard);
        AtomicReference<String> prompt = new AtomicReference<>();
        AgentRunner runner = mock(AgentRunner.class);
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    prompt.set(request.prompt());
                    return AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                runner,
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()));
        orchestrator.workflowPath = workflow;

        // when
        orchestrator.start();
        waitUntil(() -> prompt.get() != null);
        orchestrator.stop();

        // then
        assertThat(prompt.get()).contains("Review the edge case.");
    }

    @Test
    void workflowWatcherQueuesRefreshWhenWorkflowFileChanges() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of());
        AgentRunner runner = mock(AgentRunner.class);
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                runner,
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()));
        orchestrator.workflowPath = workflow;

        // when
        orchestrator.start();
        waitUntil(() -> tracker.candidateFetches.get() >= 1);
        Thread.sleep(20);
        writeWorkflow(workflow, "60000");
        waitUntil(() -> tracker.candidateFetches.get() >= 2);
        orchestrator.stop();

        // then
        assertThat(tracker.candidateFetches.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void refreshRequestedDuringActiveTickRunsImmediatelyAfterTickCompletes() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        BlockingTracker tracker = new BlockingTracker();
        AgentRunner runner = mock(AgentRunner.class);
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                runner,
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()));
        orchestrator.workflowPath = workflow;

        // when
        orchestrator.start();
        assertThat(tracker.firstFetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.requestRefresh();
        tracker.releaseFirstFetch.countDown();
        waitUntil(() -> tracker.candidateFetches.get() >= 2);
        orchestrator.stop();

        // then
        assertThat(tracker.candidateFetches.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void rateLimitEventsAreExposedInSnapshot() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock(AgentRunner.class);
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    request.listener()
                            .onEvent(new AgentEvent(
                                    "account/rateLimits/updated",
                                    Instant.now(),
                                    request.workerIdentity(),
                                    123L,
                                    "thread-1",
                                    "turn-1",
                                    "rate limits",
                                    Map.of(),
                                    new ObjectMapper().createObjectNode().put("primary", "ok")));
                    return AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                runner,
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()));
        orchestrator.workflowPath = workflow;

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().rateLimits() != null);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.rateLimits().toString()).contains("primary");
    }

    @Test
    void failedAgentRunSchedulesRetryAndExposesTokenUsageAndCardDetails() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000", "");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock(AgentRunner.class);
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    request.listener()
                            .onEvent(new AgentEvent(
                                    "turn/started",
                                    Instant.now(),
                                    request.workerIdentity(),
                                    123L,
                                    "thread-1",
                                    "turn-1",
                                    "started",
                                    Map.of("input_tokens", 10L, "output_tokens", 3L, "total_tokens", 13L),
                                    new ObjectMapper().createObjectNode()));
                    return AgentRunResult.fail("boom");
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                runner,
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()));
        orchestrator.workflowPath = workflow;

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        var details = orchestrator.cardDetails("TRELLO-abc");
        orchestrator.stop();

        // then
        assertThat(snapshot.codexTotals().inputTokens()).isEqualTo(10);
        assertThat(snapshot.codexTotals().outputTokens()).isEqualTo(3);
        assertThat(snapshot.codexTotals().totalTokens()).isEqualTo(13);
        assertThat(snapshot.retrying()).singleElement().satisfies(row -> assertThat(row.error())
                .isEqualTo("boom"));
        assertThat(details).hasValueSatisfying(detail -> {
            assertThat(detail.status()).isEqualTo("retrying");
            assertThat(detail.lastError()).isEqualTo("boom");
            assertThat(detail.recentEvents())
                    .extracting(CardDebugDetails.EventInfo::event)
                    .contains("turn/started");
        });
    }

    @Test
    void retryTimerDispatchesAgainWhenCardRemainsEligible() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                """
                agent:
                  max_retry_backoff_ms: 10
                """);
        Card card = TestCards.card("card-1", "TRELLO-abc", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(card));
        AtomicInteger runs = new AtomicInteger();
        AgentRunner runner = mock(AgentRunner.class);
        doAnswer(invocation ->
                        runs.incrementAndGet() == 1 ? AgentRunResult.fail("temporary failure") : AgentRunResult.ok())
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                runner,
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()));
        orchestrator.workflowPath = workflow;

        // when
        orchestrator.start();
        waitUntil(() -> runs.get() >= 2);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(runs.get()).isGreaterThanOrEqualTo(2);
        assertThat(snapshot.retrying())
                .anySatisfy(row -> assertThat(row.cardIdentifier()).isEqualTo("TRELLO-abc"));
    }

    @Test
    void runningCardIsCancelledWhenItLeavesTheActiveState() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        Card active = TestCards.card("card-1", "TRELLO-abc", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(active));
        AgentRunner runner = mock(AgentRunner.class);
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger cancelled = new AtomicInteger();
        doAnswer(invocation -> {
                    started.countDown();
                    try {
                        Thread.sleep(TimeUnit.SECONDS.toMillis(30));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return AgentRunResult.fail("interrupted");
                })
                .when(runner)
                .run(any());
        doAnswer(invocation -> {
                    cancelled.incrementAndGet();
                    return null;
                })
                .when(runner)
                .cancel(any());
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                runner,
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()));
        orchestrator.workflowPath = workflow;

        // when
        orchestrator.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        tracker.setCandidates(List.of());
        tracker.setCardState(TestCards.card("card-1", "TRELLO-abc", "Review"));
        orchestrator.requestRefresh();
        waitUntil(() -> cancelled.get() == 1);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.counts().running()).isZero();
        assertThat(snapshot.counts().retrying()).isZero();
    }

    private static void writeWorkflow(Path workflow, String pollIntervalMs) throws Exception {
        writeWorkflow(workflow, pollIntervalMs, "");
    }

    private static void writeWorkflow(Path workflow, String pollIntervalMs, String extraConfig) throws Exception {
        writeWorkflow(workflow, pollIntervalMs, extraConfig, "{{ card.title }}");
    }

    private static void writeWorkflow(Path workflow, String pollIntervalMs, String extraConfig, String prompt)
            throws Exception {
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: key
                  api_token: token
                  board_id: board-1
                  active_states: [Todo]
                workspace:
                  root: work
                polling:
                  interval_ms: %s
                %s
                codex:
                  command: fake
                ---
                %s
                """
                        .formatted(pollIntervalMs, extraConfig, prompt));
    }

    private static void waitUntil(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.matches()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Condition was not met before timeout");
    }

    @FunctionalInterface
    private interface Condition {
        boolean matches();
    }

    private static final class FakeTracker implements TrackerClient {
        private volatile List<Card> candidates;
        private volatile Map<String, CardLookupResult> cardStates;
        private final AtomicInteger candidateFetches = new AtomicInteger();

        private FakeTracker(List<Card> candidates) {
            setCandidates(candidates);
        }

        private void setCandidates(List<Card> candidates) {
            this.candidates = new ArrayList<>(candidates);
            this.cardStates = candidates.stream().collect(Collectors.toMap(Card::id, CardLookupResult.Found::new));
        }

        private void setCardState(Card card) {
            this.cardStates = Map.of(card.id(), new CardLookupResult.Found(card));
        }

        @Override
        public String resolveBoardId(EffectiveConfig config) {
            return "board-1";
        }

        @Override
        public List<Card> fetchCandidateCards(EffectiveConfig config) {
            candidateFetches.incrementAndGet();
            return candidates;
        }

        @Override
        public List<Card> fetchTerminalCards(EffectiveConfig config) {
            return List.of();
        }

        @Override
        public Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds) {
            return cardStates.entrySet().stream()
                    .filter(entry -> cardIds.contains(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }

    private static final class BlockingTracker implements TrackerClient {
        private final AtomicInteger candidateFetches = new AtomicInteger();
        private final CountDownLatch firstFetchStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstFetch = new CountDownLatch(1);

        @Override
        public String resolveBoardId(EffectiveConfig config) {
            return "board-1";
        }

        @Override
        public List<Card> fetchCandidateCards(EffectiveConfig config) {
            if (candidateFetches.incrementAndGet() == 1) {
                firstFetchStarted.countDown();
                try {
                    assertThat(releaseFirstFetch.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return List.of();
        }

        @Override
        public List<Card> fetchTerminalCards(EffectiveConfig config) {
            return List.of();
        }

        @Override
        public Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds) {
            return Map.of();
        }
    }
}
