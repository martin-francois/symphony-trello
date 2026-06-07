package ch.fmartin.symphony.trello.orchestrator;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SymphonyOrchestratorTest {
    private static final Instant COMMENT_TIME = Instant.parse("2026-01-01T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void dispatchesEligibleCardAndQueuesContinuationRetryAfterNormalExit() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        when(runner.run(any())).thenReturn(AgentRunResult.ok());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.counts().retrying()).isEqualTo(1);
        assertThat(snapshot.retrying()).singleElement().satisfies(row -> {
            assertThat(row.cardIdentifier()).isEqualTo("TRELLO-abc");
            assertThat(row.attempt()).isEqualTo(1);
        });
        assertThat(snapshot.routing().activeLists()).containsExactly("Todo");
        assertThat(snapshot.routing().terminalLists()).contains("done");
    }

    @Test
    void doesNotQueueContinuationRetryAfterSuccessfulHandoffMovesCardOutOfActiveList() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AtomicInteger runs = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            runs.incrementAndGet();
            tracker.setCardState(TestCards.card("card-1", "TRELLO-abc", "Human Review"));
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        RuntimeSnapshot snapshot = runOnceAndSnapshotIdle(orchestrator, runs);

        // then
        assertThat(snapshot.counts().running()).isZero();
        assertThat(snapshot.counts().retrying()).isZero();
        assertThat(tracker.cardState("card-1").state()).isEqualTo("Human Review");
    }

    @Test
    void doesNotDispatchCardsFromNonActiveLists() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Human Review")));
        AgentRunner runner = mock();
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        Thread.sleep(250);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.counts().running()).isZero();
        assertThat(snapshot.counts().retrying()).isZero();
        assertThat(tracker.promptStateFetches.get()).isZero();
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
                List.of(new Card.Comment("comment-1", "Review the edge case.", "Reviewer", COMMENT_TIME)));
        FakeTracker tracker = new FakeTracker(List.of(listedCard));
        tracker.setCardState(refreshedCard);
        AtomicReference<String> prompt = new AtomicReference<>();
        AgentRunner runner = mock();
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    prompt.set(request.prompt());
                    return AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> prompt.get() != null);
        orchestrator.stop();

        // then
        assertThat(prompt.get()).contains("Review the edge case.");
        assertThat(tracker.promptStateFetches.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void preparesCardForDispatchBeforeRenderingPromptAndStartingAgent() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: key
                  api_token: token
                  board_id: board-1
                  active_states: [Todo, In Progress]
                  in_progress_state: In Progress
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                codex:
                  command: fake
                ---
                {{ card.state }}
                """);
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        tracker.preparedCard = TestCards.card("card-1", "TRELLO-abc", "In Progress");
        AtomicReference<AgentRunner.AgentRunRequest> request = new AtomicReference<>();
        AgentRunner runner = mock();
        doAnswer(invocation -> {
                    request.set(invocation.getArgument(0));
                    return AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> request.get() != null);
        orchestrator.stop();

        // then
        assertThat(tracker.prepareForDispatchCalls.get()).isEqualTo(1);
        assertThat(request.get().card().state()).isEqualTo("In Progress");
        assertThat(request.get().prompt()).isEqualTo("In Progress");
    }

    @Test
    void retriesCardFromInProgressWhenAgentRunFailsAfterPickupMove() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: key
                  api_token: token
                  board_id: board-1
                  active_states: [Todo, In Progress]
                  in_progress_state: In Progress
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                agent:
                  max_retry_backoff_ms: 10
                codex:
                  command: fake
                ---
                {{ card.state }}
                """);
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        tracker.preparedCard = TestCards.card("card-1", "TRELLO-abc", "In Progress");
        AtomicInteger runs = new AtomicInteger();
        List<String> requestedStates = new ArrayList<>();
        AgentRunner runner = mock();
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    requestedStates.add(request.card().state());
                    return runs.incrementAndGet() == 1 ? AgentRunResult.fail("codex failed") : AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> runs.get() >= 2);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(runs.get()).isGreaterThanOrEqualTo(2);
        assertThat(requestedStates).containsSubsequence("In Progress", "In Progress");
        assertThat(tracker.prepareForDispatchCalls.get()).isGreaterThanOrEqualTo(2);
        assertThat(snapshot.retrying())
                .anySatisfy(row -> assertThat(row.cardIdentifier()).isEqualTo("TRELLO-abc"));
    }

    @Test
    void releasesIdleInProgressCardsWhenConcurrencyIsFull() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: key
                  api_token: token
                  board_id: board-1
                  active_states: [Todo, In Progress]
                  in_progress_state: In Progress
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                agent:
                  max_concurrent_agents: 1
                codex:
                  command: fake
                ---
                {{ card.state }}
                """);
        Card first = TestCards.card("card-1", "TRELLO-first", "In Progress");
        Card second = TestCards.card("card-2", "TRELLO-second", "In Progress");
        FakeTracker tracker = new FakeTracker(List.of(first, second));
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AgentRunner runner = mock();
        doAnswer(invocation -> {
                    workerStarted.countDown();
                    releaseWorker.await(5, TimeUnit.SECONDS);
                    return AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> tracker.releasedCards.size() == 1);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        releaseWorker.countDown();
        orchestrator.stop();

        // then
        assertThat(snapshot.running()).singleElement().satisfies(row -> assertThat(row.cardIdentifier())
                .isEqualTo("TRELLO-first"));
        assertThat(tracker.releasedCards).containsExactly("TRELLO-second");
    }

    @Test
    void releasesFailedInProgressCardBeforeRetryBackoff() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeRetryableInProgressWorkflow(workflow);
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        tracker.preparedCard = TestCards.card("card-1", "TRELLO-abc", "In Progress");
        AgentRunner runner = mock();
        when(runner.run(any())).thenReturn(AgentRunResult.fail("codex failed"));
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertCardReleasedForRetry(snapshot, tracker);
    }

    @Test
    void releasesCurrentCardWhenPrepareForDispatchFailsAfterPickupMove() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeRetryableInProgressWorkflow(workflow);
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        tracker.preparedCard = TestCards.card("card-1", "TRELLO-abc", "In Progress");
        tracker.prepareForDispatchFailure = new IllegalStateException("post-move refresh failed");
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, mock(AgentRunner.class));

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertCardReleasedForRetry(snapshot, tracker);
    }

    @Test
    void doesNotRetryFailedCardThatAlreadyMovedOutOfInProgress() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: key
                  api_token: token
                  board_id: board-1
                  active_states: [Todo, In Progress]
                  in_progress_state: In Progress
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                agent:
                  max_retry_backoff_ms: 60000
                codex:
                  command: fake
                ---
                {{ card.state }}
        """);
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "In Progress")));
        AtomicInteger runs = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            runs.incrementAndGet();
            tracker.setCardState(TestCards.card("card-1", "TRELLO-abc", "Blocked"));
            return AgentRunResult.fail("handoff failed after move");
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        RuntimeSnapshot snapshot = runOnceAndSnapshotIdle(orchestrator, runs);

        // then
        assertThat(snapshot.counts().running()).isZero();
        assertThat(snapshot.counts().retrying()).isZero();
        assertThat(tracker.releasedCards).isEmpty();
        assertThat(tracker.cardState("card-1").state()).isEqualTo("Blocked");
    }

    @Test
    void workflowWatcherQueuesRefreshWhenWorkflowFileChanges() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of());
        AgentRunner runner = mock();
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

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
    void periodicPollingPicksUpCardAddedAfterIdleStartup() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "50");
        FakeTracker tracker = new FakeTracker(List.of());
        AgentRunner runner = mock();
        AtomicReference<String> pickedUpCard = new AtomicReference<>();
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    pickedUpCard.set(request.card().identifier());
                    return AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> tracker.candidateFetches.get() >= 1);
        tracker.setCandidates(List.of(TestCards.card("card-1", "TRELLO-late", "Todo")));
        waitUntil(() -> pickedUpCard.get() != null);
        orchestrator.stop();

        // then
        assertThat(pickedUpCard.get()).isEqualTo("TRELLO-late");
        assertThat(tracker.candidateFetches.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void refreshRequestedDuringActiveTickRunsImmediatelyAfterTickCompletes() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        BlockingTracker tracker = new BlockingTracker();
        AgentRunner runner = mock();
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

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
        AgentRunner runner = mock();
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    request.listener()
                            .onEvent(new AgentEvent(
                                    "account/rateLimits/updated",
                                    COMMENT_TIME,
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
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().rateLimits() != null);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.rateLimits().toString()).contains("primary");
    }

    @Test
    void tokenTotalsUseAbsoluteSnapshotsAcrossMultipleTurnEvents() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    request.listener()
                            .onEvent(new AgentEvent(
                                    "thread/tokenUsage/updated",
                                    COMMENT_TIME,
                                    request.workerIdentity(),
                                    123L,
                                    "thread-1",
                                    "turn-1",
                                    "first turn usage",
                                    Map.of("input_tokens", 10L, "output_tokens", 3L, "total_tokens", 13L),
                                    new ObjectMapper().createObjectNode()));
                    request.listener()
                            .onEvent(new AgentEvent(
                                    "thread/tokenUsage/updated",
                                    COMMENT_TIME,
                                    request.workerIdentity(),
                                    123L,
                                    "thread-1",
                                    "turn-2",
                                    "second turn usage",
                                    Map.of("input_tokens", 14L, "output_tokens", 5L, "total_tokens", 19L),
                                    new ObjectMapper().createObjectNode()));
                    return AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.codexTotals().inputTokens()).isEqualTo(14);
        assertThat(snapshot.codexTotals().outputTokens()).isEqualTo(5);
        assertThat(snapshot.codexTotals().totalTokens()).isEqualTo(19);
    }

    @Test
    void runningSnapshotCountsContinuationTurns() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        CountDownLatch eventsEmitted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    request.listener()
                            .onEvent(new AgentEvent(
                                    "session_started",
                                    COMMENT_TIME,
                                    request.workerIdentity(),
                                    123L,
                                    "thread-1",
                                    "turn-1",
                                    null,
                                    Map.of(),
                                    new ObjectMapper().createObjectNode()));
                    request.listener()
                            .onEvent(new AgentEvent(
                                    "session_continued",
                                    COMMENT_TIME,
                                    request.workerIdentity(),
                                    123L,
                                    "thread-1",
                                    "turn-2",
                                    null,
                                    Map.of(),
                                    new ObjectMapper().createObjectNode()));
                    eventsEmitted.countDown();
                    releaseWorker.await(5, TimeUnit.SECONDS);
                    return AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        assertThat(eventsEmitted.await(5, TimeUnit.SECONDS)).isTrue();
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        releaseWorker.countDown();
        orchestrator.stop();

        // then
        assertThat(snapshot.running()).singleElement().satisfies(row -> assertThat(row.turnCount())
                .isEqualTo(2));
    }

    @Test
    void failedAgentRunSchedulesRetryAndExposesTokenUsageAndCardDetails() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000", "");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    request.listener()
                            .onEvent(new AgentEvent(
                                    "turn/started",
                                    COMMENT_TIME,
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
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

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
        AgentRunner runner = mock();
        doAnswer(invocation ->
                        runs.incrementAndGet() == 1 ? AgentRunResult.fail("temporary failure") : AgentRunResult.ok())
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> runs.get() >= 2);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(runs.get()).isGreaterThanOrEqualTo(2);
        assertThat(tracker.promptStateFetches.get()).isGreaterThanOrEqualTo(2);
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
        AgentRunner runner = mock();
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
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

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

    private static void writeRetryableInProgressWorkflow(Path workflow) throws Exception {
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: key
                  api_token: token
                  board_id: board-1
                  active_states: [Todo, In Progress]
                  in_progress_state: In Progress
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                agent:
                  max_retry_backoff_ms: 60000
                codex:
                  command: fake
                ---
                {{ card.state }}
                """);
    }

    private static void assertCardReleasedForRetry(RuntimeSnapshot snapshot, FakeTracker tracker) {
        assertThat(snapshot.retrying()).singleElement().satisfies(row -> assertThat(row.cardIdentifier())
                .isEqualTo("TRELLO-abc"));
        assertThat(tracker.releasedCards).containsExactly("TRELLO-abc");
        assertThat(tracker.cardState("card-1").state()).isEqualTo("Todo");
    }

    private static SymphonyOrchestrator orchestrator(Path workflow, TrackerClient tracker, AgentRunner runner) {
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                runner,
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()));
        orchestrator.workflowPath = workflow;
        return orchestrator;
    }

    private static RuntimeSnapshot runOnceAndSnapshotIdle(SymphonyOrchestrator orchestrator, AtomicInteger runs)
            throws Exception {
        orchestrator.start();
        waitUntil(() -> runs.get() == 1
                && orchestrator.snapshot().counts().running() == 0
                && orchestrator.snapshot().counts().retrying() == 0);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();
        return snapshot;
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
        private final AtomicInteger promptStateFetches = new AtomicInteger();
        private final AtomicInteger prepareForDispatchCalls = new AtomicInteger();
        private final List<String> releasedCards = new ArrayList<>();
        private volatile Card preparedCard;
        private volatile RuntimeException prepareForDispatchFailure;

        private FakeTracker(List<Card> candidates) {
            setCandidates(candidates);
        }

        private void setCandidates(List<Card> candidates) {
            this.candidates = new ArrayList<>(candidates);
            this.cardStates = candidates.stream().collect(toImmutableMap(Card::id, CardLookupResult.Found::new));
        }

        private void setCardState(Card card) {
            Map<String, CardLookupResult> updated = new LinkedHashMap<>(this.cardStates);
            updated.put(card.id(), new CardLookupResult.Found(card));
            this.cardStates = updated;
        }

        private Card cardState(String cardId) {
            return ((CardLookupResult.Found) cardStates.get(cardId)).card();
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
                    .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        @Override
        public Map<String, CardLookupResult> fetchCardStatesForPromptByIds(
                EffectiveConfig config, List<String> cardIds) {
            promptStateFetches.incrementAndGet();
            return fetchCardStatesByIds(config, cardIds);
        }

        @Override
        public Card prepareForDispatch(EffectiveConfig config, Card card) {
            prepareForDispatchCalls.incrementAndGet();
            if (preparedCard == null) {
                return card;
            }
            setCardState(preparedCard);
            if (prepareForDispatchFailure != null) {
                throw prepareForDispatchFailure;
            }
            return preparedCard;
        }

        @Override
        public void releaseFromDispatch(EffectiveConfig config, Card card) {
            releasedCards.add(card.identifier());
            setCardState(TestCards.card(card.id(), card.identifier(), "Todo"));
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
