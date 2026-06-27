package ch.fmartin.symphony.trello.orchestrator;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
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
import ch.fmartin.symphony.trello.domain.BlockerRef;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrackerClient;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class SymphonyOrchestratorTest {
    private static final Instant COMMENT_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

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
        waitUntil(() -> tracker.candidateFetches.get() >= 1);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.counts().running()).isZero();
        assertThat(snapshot.counts().retrying()).isZero();
        assertThat(tracker.promptStateFetches.get()).isZero();
    }

    @Test
    void dispatchRequiresAllConfiguredLabelsUsingNormalizedMatching() throws Exception {
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
                  active_states: [Todo]
                  required_labels:
                    - " Ready For Codex "
                    - "Customer Blocked"
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                codex:
                  command: fake
                ---
                {{ card.title }}
                """);
        FakeTracker tracker = new FakeTracker(List.of(
                TestCards.cardWithLabels(
                        "card-1", "TRELLO-match", "Todo", List.of("ready for codex", " customer blocked ")),
                TestCards.cardWithLabels("card-2", "TRELLO-missing", "Todo", List.of("ready for codex"))));
        AtomicInteger runs = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            runs.incrementAndGet();
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> runs.get() == 1 && orchestrator.snapshot().counts().retrying() == 1);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(runs).hasValue(1);
        assertThat(snapshot.retrying()).singleElement().satisfies(row -> assertThat(row.cardIdentifier())
                .isEqualTo("TRELLO-match"));
        assertThat(tracker.promptStateFetches.get()).isEqualTo(1);
    }

    @Test
    void blankRequiredLabelMakesNoCardDispatchEligible() throws Exception {
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
                  active_states: [Todo]
                  required_labels:
                    - ""
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                codex:
                  command: fake
                ---
                {{ card.title }}
                """);
        FakeTracker tracker =
                new FakeTracker(List.of(TestCards.cardWithLabels("card-1", "TRELLO-abc", "Todo", List.of("any"))));
        AgentRunner runner = mock();
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> tracker.candidateFetches.get() > 0);
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
        waitUntil(() -> runs.get() >= 2 && orchestrator.snapshot().counts().retrying() == 1);
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
        writeWorkflow(workflow, "60001");
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
    void statusReadsAnswerWhileTickIsBlockedInsideTrelloFetch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        BlockingTracker tracker = new BlockingTracker();
        AgentRunner runner = mock();
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        assertThat(tracker.firstFetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<String> boardId = CompletableFuture.supplyAsync(orchestrator::selectedBoardId);
        CompletableFuture<String> configuredBoardId =
                CompletableFuture.supplyAsync(orchestrator::selectedConfiguredBoardId);
        CompletableFuture<Path> workflowPath = CompletableFuture.supplyAsync(orchestrator::selectedWorkflowPath);
        CompletableFuture<RuntimeSnapshot> snapshot = CompletableFuture.supplyAsync(orchestrator::snapshot);

        // then
        try {
            assertThat(boardId)
                    .as("local-status board id read must not wait for the in-flight Trello poll")
                    .succeedsWithin(Duration.ofSeconds(2))
                    .isEqualTo("board-1");
            assertThat(configuredBoardId)
                    .as("local-status configured board id read must not wait for the in-flight Trello poll")
                    .succeedsWithin(Duration.ofSeconds(2));
            assertThat(workflowPath)
                    .as("local-status workflow path read must not wait for the in-flight Trello poll")
                    .succeedsWithin(Duration.ofSeconds(2))
                    .isEqualTo(workflow.toAbsolutePath().normalize());
            assertThat(snapshot)
                    .as("state snapshot must not wait for the in-flight Trello poll")
                    .succeedsWithin(Duration.ofSeconds(2));
        } finally {
            tracker.releaseFirstFetch.countDown();
            orchestrator.stop();
        }
    }

    @Test
    void refreshAtTickCompletionBoundaryIsNotOverwrittenByIntervalSchedule() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of());
        AgentRunner runner = mock();
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);
        AtomicBoolean boundaryRefreshInjected = new AtomicBoolean();
        orchestrator.tickCompletionHookForTests = () -> {
            if (!boundaryRefreshInjected.compareAndSet(false, true)) {
                return;
            }
            Thread refresher = new Thread(orchestrator::requestRefresh);
            refresher.start();
            try {
                refresher.join(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // when
        orchestrator.start();
        waitUntil(() -> tracker.candidateFetches.get() >= 2);
        orchestrator.stop();

        // then
        assertThat(tracker.candidateFetches.get())
                .as("a refresh at the tick completion boundary must schedule the next tick "
                        + "immediately instead of waiting for the 60s polling interval")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void refreshAfterTerminalReconcileCleanupFailureSchedulesNextTick() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        BlockingRun blockingRun = blockRunnerUntilCancelled(runner);
        ThrowingWorkspaceManager workspaces = new ThrowingWorkspaceManager();
        AtomicInteger tickCompletions = new AtomicInteger();
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, workspaces);
        orchestrator.tickCompletionHookForTests = tickCompletions::incrementAndGet;

        orchestrator.start();
        assertThat(blockingRun.started.await(5, TimeUnit.SECONDS)).isTrue();
        int completedTicksBeforeFailure = tickCompletions.get();
        tracker.setCardState(TestCards.card("card-1", "TRELLO-abc", "done"));

        // when
        orchestrator.requestRefresh();
        waitUntil(() -> workspaces.removalAttempts.get() == 1 && tickCompletions.get() > completedTicksBeforeFailure);
        int candidateFetchesAfterFailedTick = tracker.candidateFetches.get();
        orchestrator.requestRefresh();
        waitUntil(() -> tracker.candidateFetches.get() > candidateFetchesAfterFailedTick);
        orchestrator.stop();

        // then
        assertThat(blockingRun.cancelled).hasValue(1);
        assertThat(workspaces.removalAttempts).hasValue(1);
    }

    @Test
    void refreshRequestedDuringAndAfterStopIsANoOpAndDoesNotThrow() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        CountDownLatch agentStarted = new CountDownLatch(1);
        AtomicReference<SymphonyOrchestrator> orchestratorReference = new AtomicReference<>();
        AtomicReference<Throwable> refreshDuringStopFailure = new AtomicReference<>();
        AgentRunner runner = mock();
        doAnswer(invocation -> {
                    agentStarted.countDown();
                    try {
                        blockUntilInterruptedOrTimedOut(Duration.ofSeconds(5));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        doAnswer(invocation -> {
                    refreshDuringStopFailure.set(
                            catchThrowable(() -> orchestratorReference.get().requestRefresh()));
                    return null;
                })
                .when(runner)
                .cancel(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);
        orchestratorReference.set(orchestrator);
        orchestrator.start();
        assertThat(agentStarted.await(5, TimeUnit.SECONDS)).isTrue();
        int fetchesBeforeStop = tracker.candidateFetches.get();

        // when
        orchestrator.stop();
        Throwable refreshAfterStopFailure = catchThrowable(orchestrator::requestRefresh);

        // then
        assertThat(refreshDuringStopFailure.get())
                .as("requestRefresh during stop must be a no-op instead of throwing")
                .isNull();
        assertThat(refreshAfterStopFailure)
                .as("requestRefresh after stop must not schedule against the shut-down scheduler")
                .isNull();
        assertThat(tracker.candidateFetches.get()).isEqualTo(fetchesBeforeStop);
    }

    @Test
    void workflowPathCannotChangeOnceStartHasBegun() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        StartBlockingTracker tracker = new StartBlockingTracker();
        AgentRunner runner = mock();
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);
        Thread starter = new Thread(orchestrator::start);
        starter.start();
        assertThat(tracker.terminalFetchStarted.await(5, TimeUnit.SECONDS)).isTrue();

        // when
        CountDownLatch changeAttempted = new CountDownLatch(1);
        CompletableFuture<Throwable> rejection = CompletableFuture.supplyAsync(() -> {
            changeAttempted.countDown();
            return catchThrowable(() -> orchestrator.setWorkflowPath(tempDir.resolve("WORKFLOW.other.md")));
        });
        assertThat(changeAttempted.await(5, TimeUnit.SECONDS)).isTrue();
        waitForBoundedQuietPeriod(rejection);
        tracker.releaseTerminalFetch.countDown();
        starter.join(TimeUnit.SECONDS.toMillis(5));

        // then
        assertThat(rejection)
                .as("a workflow path change racing startup must wait for the operation boundary and be rejected")
                .succeedsWithin(Duration.ofSeconds(5))
                .isInstanceOf(IllegalStateException.class);
        assertThat(orchestrator.selectedWorkflowPath())
                .isEqualTo(workflow.toAbsolutePath().normalize());
        orchestrator.stop();
    }

    /**
     * Gives a racing change that does not block (the old bug) time to complete while startup is
     * still latched; a correctly blocking change leaves the future incomplete and this returns
     * after the bound.
     */
    private static void waitForBoundedQuietPeriod(CompletableFuture<Throwable> future) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline && !future.isDone()) {
            pollDelayForBoundedConditionWait();
        }
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
    void runningSnapshotRowIncludesCardUrl() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        doAnswer(invocation -> {
                    started.countDown();
                    releaseWorker.await(5, TimeUnit.SECONDS);
                    return AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        releaseWorker.countDown();
        orchestrator.stop();

        // then
        assertThat(snapshot.running()).singleElement().satisfies(row -> assertThat(row.cardUrl())
                .isEqualTo("https://trello.com/c/SYNTH101"));
    }

    @CsvSource(
            nullValues = "null",
            value = {
                // prefers the short URL when present
                "https://trello.com/c/SYNTH001,https://trello.com/c/SYNTH001/full-title,https://trello.com/c/SYNTH001",
                // falls back to the full URL when no short URL is known
                "null,https://trello.com/c/SYNTH002/full-title,https://trello.com/c/SYNTH002/full-title",
                // omits the URL when neither is known
                "null,null,null"
            })
    @ParameterizedTest(name = "shortUrl={0} url={1} -> cardUrl={2}")
    void retrySnapshotRowCarriesCardUrlPreferringShortUrl(String shortUrl, String url, String expectedCardUrl)
            throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000", "");
        Card card = TestCards.cardWithUrls("card-1", "TRELLO-abc", "Todo", shortUrl, url);
        FakeTracker tracker = new FakeTracker(List.of(card));
        AgentRunner runner = mock();
        when(runner.run(any())).thenReturn(AgentRunResult.fail("boom"));
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.retrying()).singleElement().satisfies(row -> assertThat(row.cardUrl())
                .isEqualTo(expectedCardUrl));
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
    void retryTimerReleasesAndRequeuesCardWhenItLosesRequiredLabel() throws Exception {
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
                  required_labels: [Ready for Codex]
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                codex:
                  command: fake
                ---
                {{ card.title }}
                """);
        Card card = TestCards.cardWithLabels("card-1", "TRELLO-abc", "Todo", List.of("ready for codex"));
        FakeTracker tracker = new FakeTracker(List.of(card));
        tracker.preparedCard =
                TestCards.cardWithLabels("card-1", "TRELLO-abc", "In Progress", List.of("ready for codex"));
        AtomicInteger runs = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            runs.incrementAndGet();
            tracker.preparedCard = null;
            tracker.setCardState(TestCards.cardWithLabels("card-1", "TRELLO-abc", "In Progress", List.of()));
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> tracker.releasedCards.size() == 1
                && orchestrator.snapshot().counts().retrying() == 1);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(runs.get()).isEqualTo(1);
        assertCardReleasedForRetry(snapshot, tracker);
    }

    @Test
    void runningCardIsCancelledWhenItLeavesTheActiveState() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        Card active = TestCards.card("card-1", "TRELLO-abc", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(active));
        AgentRunner runner = mock();
        BlockingRun blockingRun = blockRunnerUntilCancelled(runner);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        assertThat(blockingRun.started().await(5, TimeUnit.SECONDS)).isTrue();
        tracker.setCandidates(List.of());
        tracker.setCardState(TestCards.card("card-1", "TRELLO-abc", "Review"));
        orchestrator.requestRefresh();
        waitUntil(() -> blockingRun.cancelled().get() == 1);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.counts().running()).isZero();
        assertThat(snapshot.counts().retrying()).isZero();
    }

    @Test
    void runningCardIsCancelledReleasedAndRetriedWhenItLosesRequiredLabel() throws Exception {
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
                  required_labels: [Ready for Codex]
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                agent:
                  max_retry_backoff_ms: 60000
                codex:
                  command: fake
                ---
                {{ card.title }}
                """);
        Card listedCard = TestCards.cardWithLabels("card-1", "TRELLO-abc", "Todo", List.of("ready for codex"));
        FakeTracker tracker = new FakeTracker(List.of(listedCard));
        tracker.preparedCard =
                TestCards.cardWithLabels("card-1", "TRELLO-abc", "In Progress", List.of("ready for codex"));
        AgentRunner runner = mock();
        BlockingRun blockingRun = blockRunnerUntilCancelled(runner);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        assertThat(blockingRun.started().await(5, TimeUnit.SECONDS)).isTrue();
        tracker.setCandidates(List.of());
        tracker.setCardState(TestCards.cardWithLabels("card-1", "TRELLO-abc", "In Progress", List.of()));
        orchestrator.requestRefresh();
        waitUntil(() -> blockingRun.cancelled().get() == 1
                && orchestrator.snapshot().counts().retrying() == 1);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.counts().running()).isZero();
        assertThat(snapshot.retrying()).singleElement().satisfies(row -> assertThat(row.cardIdentifier())
                .isEqualTo("TRELLO-abc"));
        assertThat(tracker.releasedCards).containsExactly("TRELLO-abc");
        assertThat(tracker.cardState("card-1").state()).isEqualTo("Todo");
    }

    @Test
    void dispatchesEligiblePrerequisiteBeforeUnrelatedLowerPriorityWork() throws Exception {
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
                  active_states: [Todo]
                  blocker_enforced_states: [Todo]
                  terminal_states: [Done]
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                codex:
                  command: fake
                ---
                {{ card.identifier }}
                """);
        Card blocked = cardWithPriorityAndBlockers(
                "card-blocked",
                "TRELLO-blocked",
                1,
                List.of(new BlockerRef("card-prerequisite", "TRELLO-prerequisite", "Todo", null)));
        Card unrelated = cardWithPriorityAndBlockers("card-unrelated", "TRELLO-unrelated", 2, List.of());
        Card prerequisite = cardWithPriorityAndBlockers("card-prerequisite", "TRELLO-prerequisite", null, List.of());
        FakeTracker tracker = new FakeTracker(List.of(blocked, unrelated, prerequisite));

        // when
        String dispatched = dispatchFirstCard(workflow, tracker);

        // then
        assertThat(dispatched).isEqualTo("TRELLO-prerequisite");
    }

    @Test
    void prerequisiteProblemsDenyDispatchEvenWhenPrerequisiteBlockerIsTerminal() throws Exception {
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
                  active_states: [Todo]
                  blocker_enforced_states: [Todo]
                  terminal_states: [Done]
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                codex:
                  command: fake
                ---
                {{ card.identifier }}
                """);
        Card blocked = cardWithPriorityBlockersAndProblems(
                "card-blocked",
                "TRELLO-blocked",
                1,
                List.of(new BlockerRef(
                        "card-prerequisite", "TRELLO-prerequisite", "Done", "https://trello.com/c/SYNTH103")),
                List.of(new Card.PrerequisiteProblem(
                        "trello_prerequisite_checklist_sync_failed",
                        "Could not update a prerequisite checklist item.",
                        "Must finish first")));
        Card unrelated = cardWithPriorityAndBlockers("card-unrelated", "TRELLO-unrelated", 2, List.of());
        FakeTracker tracker = new FakeTracker(List.of(blocked, unrelated));

        // when
        String dispatched = dispatchFirstCard(workflow, tracker);

        // then
        assertThat(dispatched).isEqualTo("TRELLO-unrelated");
    }

    @Test
    void runningCardContinuesWhenItOnlyGainsANonTerminalBlocker() throws Exception {
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
                  active_states: [Todo]
                  blocker_enforced_states: [Todo]
                  terminal_states: [Done]
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                codex:
                  command: fake
                ---
                {{ card.title }}
                """);
        Card active = TestCards.card("card-1", "TRELLO-abc", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(active));
        AgentRunner runner = mock();
        BlockingRun blockingRun = blockRunnerUntilCancelled(runner);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        assertThat(blockingRun.started().await(5, TimeUnit.SECONDS)).isTrue();
        int stateFetchesAfterDispatch = tracker.stateFetches.get();
        tracker.setCardState(TestCards.cardWithBlockers(
                "card-1",
                "TRELLO-abc",
                "Todo",
                List.of(new BlockerRef("card-2", "TRELLO-blocker", "Todo", "https://trello.com/c/SYNTH102"))));
        orchestrator.requestRefresh();
        waitUntil(() -> tracker.stateFetches.get() > stateFetchesAfterDispatch);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        assertThat(blockingRun.cancelled()).hasValue(0);
        orchestrator.stop();

        // then
        assertThat(snapshot.counts().running()).isEqualTo(1);
        assertThat(snapshot.counts().retrying()).isZero();
    }

    @Test
    void invalidWorkflowReloadLeavesPreviousConfigActive() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-first", "Todo")));
        AtomicInteger runs = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            runs.incrementAndGet();
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> runs.get() == 1 && orchestrator.snapshot().counts().retrying() == 1);
        int fetchesBeforeUnsafeReload = tracker.candidateFetches.get();
        Files.writeString(
                workflow,
                """
                ---
                tracker: []
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                codex:
                  command: fake
                ---
                ## Operating Posture

                This is an unattended orchestration run.

                ## Local And Non-GitHub Repository Work

                Record the validation evidence.

                ## Trello List Routing

                Card URL: {{ card.url }}
                """);
        Files.setLastModifiedTime(
                workflow,
                FileTime.fromMillis(Files.getLastModifiedTime(workflow).toMillis() + 2_000));
        tracker.setCandidates(List.of(TestCards.card("card-2", "TRELLO-second", "Other")));
        orchestrator.requestRefresh();
        waitUntil(() -> tracker.candidateFetches.get() > fetchesBeforeUnsafeReload);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(runs).hasValue(1);
        assertThat(snapshot.retrying()).singleElement().satisfies(row -> assertThat(row.cardIdentifier())
                .isEqualTo("TRELLO-first"));
    }

    private static BlockingRun blockRunnerUntilCancelled(AgentRunner runner) {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger cancelled = new AtomicInteger();
        doAnswer(invocation -> {
                    started.countDown();
                    try {
                        blockUntilInterruptedOrTimedOut(Duration.ofSeconds(30));
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
        return new BlockingRun(started, cancelled);
    }

    private record BlockingRun(CountDownLatch started, AtomicInteger cancelled) {}

    private String dispatchFirstCard(Path workflow, FakeTracker tracker) throws Exception {
        AtomicReference<String> dispatched = new AtomicReference<>();
        AgentRunner runner = mock();
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    dispatched.set(request.card().identifier());
                    return AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);
        orchestrator.start();
        waitUntil(() -> dispatched.get() != null);
        orchestrator.stop();
        return dispatched.get();
    }

    private static Card cardWithPriorityAndBlockers(
            String id, String identifier, Integer priority, List<BlockerRef> blockers) {
        return cardWithPriorityBlockersAndProblems(id, identifier, priority, blockers, List.of());
    }

    private static Card cardWithPriorityBlockersAndProblems(
            String id,
            String identifier,
            Integer priority,
            List<BlockerRef> blockers,
            List<Card.PrerequisiteProblem> prerequisiteProblems) {
        return new Card(
                id,
                identifier,
                "Implement feature",
                "Description",
                priority,
                "Todo",
                "list",
                "list-todo",
                "Todo",
                false,
                "board-1",
                false,
                false,
                1,
                identifier,
                "https://trello.com/c/" + identifier,
                null,
                "https://trello.com/c/" + identifier,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                prerequisiteProblems,
                blockers,
                List.of(),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-02T00:00:00Z"),
                null,
                false,
                BigDecimal.ONE);
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
        return orchestrator(workflow, tracker, runner, new WorkspaceManager(new HookRunner()));
    }

    private static SymphonyOrchestrator orchestrator(
            Path workflow, TrackerClient tracker, AgentRunner runner, WorkspaceManager workspaces) {
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(), new ConfigResolver(), tracker, runner, new PromptRenderer(), workspaces);
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
            pollDelayForBoundedConditionWait();
        }
        throw new AssertionError("Condition was not met before timeout");
    }

    private static void pollDelayForBoundedConditionWait() throws InterruptedException {
        Thread.sleep(POLL_INTERVAL.toMillis());
    }

    private static void blockUntilInterruptedOrTimedOut(Duration timeout) throws InterruptedException {
        new CountDownLatch(1).await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @FunctionalInterface
    private interface Condition {
        boolean matches();
    }

    private static final class FakeTracker implements TrackerClient {
        private volatile List<Card> candidates;
        private volatile Map<String, CardLookupResult> cardStates;
        private final AtomicInteger candidateFetches = new AtomicInteger();
        private final AtomicInteger stateFetches = new AtomicInteger();
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
            stateFetches.incrementAndGet();
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

    private static final class ThrowingWorkspaceManager extends WorkspaceManager {
        private final AtomicInteger removalAttempts = new AtomicInteger();

        private ThrowingWorkspaceManager() {
            super(new HookRunner());
        }

        @Override
        public void removeForIdentifierIfPresent(String cardIdentifier, EffectiveConfig config) {
            removalAttempts.incrementAndGet();
            throw new IllegalStateException("workspace cleanup failed");
        }
    }

    private static final class StartBlockingTracker implements TrackerClient {
        private final CountDownLatch terminalFetchStarted = new CountDownLatch(1);
        private final CountDownLatch releaseTerminalFetch = new CountDownLatch(1);

        @Override
        public String resolveBoardId(EffectiveConfig config) {
            return "board-1";
        }

        @Override
        public List<Card> fetchCandidateCards(EffectiveConfig config) {
            return List.of();
        }

        @Override
        public List<Card> fetchTerminalCards(EffectiveConfig config) {
            terminalFetchStarted.countDown();
            try {
                assertThat(releaseTerminalFetch.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return List.of();
        }

        @Override
        public Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds) {
            return Map.of();
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
