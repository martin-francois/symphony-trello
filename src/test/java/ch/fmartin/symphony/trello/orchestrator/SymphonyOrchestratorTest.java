package ch.fmartin.symphony.trello.orchestrator;

import static ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestratorTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.Sha3;
import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.agent.AgentEvent;
import ch.fmartin.symphony.trello.agent.AgentRunResult;
import ch.fmartin.symphony.trello.agent.AgentRunner;
import ch.fmartin.symphony.trello.domain.BlockerRef;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.workflow.WorkflowException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class SymphonyOrchestratorTest {
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
    void releasesFailedInProgressCardUsingDispatchSourceBeforeRetryBackoff() throws Exception {
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
                  active_states: [Normal Queue, Priority Queue, In Progress]
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
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Normal Queue")));
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
        assertThat(snapshot.retrying()).singleElement().satisfies(row -> assertThat(row.cardIdentifier())
                .isEqualTo("TRELLO-abc"));
        assertThat(tracker.releases).singleElement().isEqualTo(new ReleaseRecord("In Progress", "Normal Queue"));
        assertThat(tracker.cardState("card-1").state()).isEqualTo("Normal Queue");
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
        assertThat(blockingRun.started().await(5, TimeUnit.SECONDS)).isTrue();
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
        assertThat(blockingRun.cancelled()).hasValue(1);
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
    void refreshAtExecutorShutdownBoundaryIsANoOp() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        SymphonyOrchestrator orchestrator = orchestrator(workflow, new FakeTracker(List.of()), mock());
        AtomicReference<Throwable> refreshFailure = new AtomicReference<>();
        orchestrator.executorsStoppedHookForTests =
                () -> refreshFailure.set(catchThrowable(orchestrator::requestRefresh));
        orchestrator.start();

        // when
        orchestrator.stop();

        // then
        assertThat(refreshFailure)
                .as("refresh at the executor shutdown boundary must observe the stopped lifecycle state")
                .hasValue(null);
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

    @Test
    void duplicateWorkflowRuntimeFailsBeforeTrelloResolution() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker ownerTracker = new FakeTracker(List.of());
        SymphonyOrchestrator owner = orchestrator(workflow, ownerTracker, mock());
        owner.start();

        FakeTracker duplicateTracker = new FakeTracker(List.of());
        SymphonyOrchestrator duplicate = orchestrator(workflow, duplicateTracker, mock());

        try {

            // when
            Throwable failure = catchThrowable(duplicate::start);

            // then
            assertThat(failure).isInstanceOfSatisfying(WorkflowException.class, exception -> {
                assertThat(exception.code()).isEqualTo("workflow_already_running");
                assertThat(exception).hasMessageContaining("already using this workflow file");
            });
            assertThat(duplicateTracker.boardResolutions).hasValue(0);
        } finally {
            owner.stop();
        }

        FakeTracker restartedTracker = new FakeTracker(List.of());
        SymphonyOrchestrator restarted = orchestrator(workflow, restartedTracker, mock());
        restarted.start();
        restarted.stop();
        assertThat(restartedTracker.boardResolutions).hasValue(1);
    }

    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    @Test
    void workflowRuntimeLockUsesWorkflowLocalNamespaceWhenRuntimeDirectoriesDiffer() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        Path stateHome = tempDir.resolve("state");
        Path firstTmp = tempDir.resolve("first-tmp");
        Path secondTmp = tempDir.resolve("second-tmp");
        Files.createDirectories(firstTmp);
        Files.createDirectories(secondTmp);
        String previousStateHome = System.getProperty("symphony.trello.installed.state.home");
        String previousTmp = System.getProperty("java.io.tmpdir");
        SymphonyOrchestrator owner = orchestrator(workflow, new FakeTracker(List.of()), mock());
        try {
            System.setProperty("symphony.trello.installed.state.home", stateHome.toString());
            System.setProperty("java.io.tmpdir", firstTmp.toString());
            owner.start();

            FakeTracker duplicateTracker = new FakeTracker(List.of());
            SymphonyOrchestrator duplicate = orchestrator(workflow, duplicateTracker, mock());

            // when
            System.clearProperty("symphony.trello.installed.state.home");
            System.setProperty("java.io.tmpdir", secondTmp.toString());
            Throwable failure = catchThrowable(duplicate::start);

            // then
            assertThat(failure).isInstanceOfSatisfying(WorkflowException.class, exception -> {
                assertThat(exception.code()).isEqualTo("workflow_already_running");
                assertThat(exception).hasMessageContaining("already using this workflow file");
            });
            assertThat(duplicateTracker.boardResolutions).hasValue(0);
            assertThat(workflow.getParent().resolve(".symphony-trello-locks").resolve(workflowLockFileName(workflow)))
                    .isRegularFile();
            assertThat(stateHome.resolve("workflow-locks")).doesNotExist();
            assertThat(firstTmp.resolve("symphony-trello-workflow-locks")).doesNotExist();
            assertThat(secondTmp.resolve("symphony-trello-workflow-locks")).doesNotExist();
        } finally {
            owner.stop();
            restoreProperty("symphony.trello.installed.state.home", previousStateHome);
            restoreProperty("java.io.tmpdir", previousTmp);
        }
    }

    @Test
    void workflowRuntimeLockFailsWhenWorkflowLockDirectoryCannotBeCreated() throws Exception {
        // given
        Path workflowDirectory = tempDir.resolve("readonly-workflow");
        Files.createDirectories(workflowDirectory);
        Path workflow = workflowDirectory.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        Set<PosixFilePermission> originalPermissions;
        try {
            originalPermissions = Files.getPosixFilePermissions(workflowDirectory);
        } catch (UnsupportedOperationException e) {
            assumeTrue(false, "POSIX permissions are not available on this filesystem.");
            return;
        }
        FakeTracker tracker = new FakeTracker(List.of());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, mock());
        try {
            Files.setPosixFilePermissions(workflowDirectory, PosixFilePermissions.fromString("r-x------"));
            assumeFalse(Files.isWritable(workflowDirectory), "The current user can still write to the test directory.");

            // when
            Throwable failure = catchThrowable(orchestrator::start);

            // then
            assertThat(failure).isInstanceOfSatisfying(WorkflowException.class, exception -> {
                assertThat(exception.code()).isEqualTo("workflow_lock_unavailable");
                assertThat(exception).hasMessageContaining("Workflow runtime lock cannot be acquired");
            });
            assertThat(tracker.boardResolutions).hasValue(0);
        } finally {
            Files.setPosixFilePermissions(workflowDirectory, originalPermissions);
            orchestrator.stop();
        }
    }

    @Test
    void workflowRuntimeLockIsReleasedWhenStartupFailsAfterAcquiringIt() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker failingTracker = new FakeTracker(List.of());
        failingTracker.resolveBoardIdFailure = new IllegalStateException("board lookup failed");
        SymphonyOrchestrator failing = orchestrator(workflow, failingTracker, mock());

        // when
        Throwable failure = catchThrowable(failing::start);

        // then
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("board lookup failed");

        FakeTracker restartedTracker = new FakeTracker(List.of());
        SymphonyOrchestrator restarted = orchestrator(workflow, restartedTracker, mock());
        restarted.start();
        restarted.stop();
        assertThat(restartedTracker.boardResolutions).hasValue(1);
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

    private static String workflowLockFileName(Path workflow) throws Exception {
        String canonicalWorkflowPath = workflow.toRealPath().toString();
        return Sha3.sha3_256(canonicalWorkflowPath) + ".lock";
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    @Test
    void gracefulStopAccountsActiveRuntimeExactlyOnce() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        BlockingRun blockingRun = blockRunnerUntilCancelled(runner);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        assertThat(blockingRun.started().await(5, TimeUnit.SECONDS)).isTrue();
        clock.advance(Duration.ofSeconds(12));
        orchestrator.stop();
        RuntimeSnapshot stopped = orchestrator.snapshot();
        clock.advance(Duration.ofSeconds(8));
        orchestrator.stop();
        RuntimeSnapshot stoppedAgain = orchestrator.snapshot();

        // then
        assertThat(blockingRun.cancelled()).hasValue(1);
        assertThat(stopped.counts().running()).isZero();
        assertThat(stopped.codexTotals().secondsRunning()).isEqualTo(12.0);
        assertThat(stoppedAgain.codexTotals().secondsRunning()).isEqualTo(12.0);
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
    void cardDetailsRetainOnlyTheNewestFiftyAgentEventsInOrder() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000", "");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    for (int index = 0; index < 55; index++) {
                        request.listener()
                                .onEvent(new AgentEvent(
                                        "event-" + index,
                                        COMMENT_TIME,
                                        request.workerIdentity(),
                                        123L,
                                        "thread-1",
                                        "turn-1",
                                        "message-" + index,
                                        Map.of(),
                                        new ObjectMapper().createObjectNode()));
                    }
                    return AgentRunResult.fail("boom");
                })
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        var details = orchestrator.cardDetails("TRELLO-abc");
        orchestrator.stop();

        // then
        List<String> expectedEvents =
                IntStream.range(5, 55).mapToObj(index -> "event-" + index).toList();
        assertThat(details).hasValueSatisfying(detail -> assertThat(detail.recentEvents())
                .hasSize(50)
                .extracting(CardDebugDetails.EventInfo::event)
                .containsExactlyElementsOf(expectedEvents));
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
                  max_retry_backoff_ms: 500
                """);
        Card card = TestCards.card("card-1", "TRELLO-abc", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(card));
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<RuntimeSnapshot> retryingSnapshot = new AtomicReference<>();
        AgentRunner runner = mock();
        doAnswer(invocation ->
                        runs.incrementAndGet() == 1 ? AgentRunResult.fail("temporary failure") : AgentRunResult.ok())
                .when(runner)
                .run(any());
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        try {
            orchestrator.start();
            waitUntil(() -> {
                RuntimeSnapshot current = orchestrator.snapshot();
                if (containsRetryingCard(current, "TRELLO-abc")) {
                    retryingSnapshot.set(current);
                    return true;
                }
                return false;
            });
            waitUntil(() -> runs.get() >= 2);
        } finally {
            orchestrator.stop();
        }
        RuntimeSnapshot snapshot = retryingSnapshot.get();

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

    @Test
    void sameRawCardIdAcrossTargetsRunsIndependentlyWithoutCrossRemoval() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String endpoint = "https://api.example.test/1";
        writeWorkflowWithTarget(workflow, endpoint, "board-a", "token-a", "same-command", "work-a", 60_000, 2);
        Card cardA = cardOnBoard("shared-card", "TRELLO-shared-a", "Todo", "board-a");
        Card cardB = cardOnBoard("shared-card", "TRELLO-shared-b", "Todo", "board-b");
        ScopedTracker tracker = new ScopedTracker();
        tracker.setCandidates(endpoint, "board-a", List.of(cardA));
        tracker.setCandidates(endpoint, "board-b", List.of(cardB));
        CountDownLatch workerAStarted = new CountDownLatch(1);
        CountDownLatch workerBStarted = new CountDownLatch(1);
        CountDownLatch releaseWorkerA = new CountDownLatch(1);
        CountDownLatch releaseWorkerB = new CountDownLatch(1);
        List<AgentRunner.AgentRunRequest> requests = new CopyOnWriteArrayList<>();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            requests.add(request);
            String boardId = request.config().tracker().resolvedBoardId();
            request.listener()
                    .onEvent(agentEvent(
                            request,
                            boardId.equals("board-a") ? "a/running" : "b/running",
                            boardId.equals("board-a") ? "from A" : "from B"));
            if (boardId.equals("board-a")) {
                workerAStarted.countDown();
                assertThat(releaseWorkerA.await(5, TimeUnit.SECONDS)).isTrue();
                return AgentRunResult.fail("old target failed after reload");
            }
            return finishBoardBRun(tracker, endpoint, workerBStarted, releaseWorkerB, request);
        });
        SymphonyOrchestrator orchestrator = orchestrator(
                workflow, tracker, runner, Clock.fixed(COMMENT_TIME, ZoneOffset.UTC), successfulWorkpadHandler());

        // when
        orchestrator.start();
        assertThat(workerAStarted.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.stopWorkflowWatcherForTests();
        rewriteWorkflowWithTarget(workflow, endpoint, "board-b", "token-b", "same-command", "work-b", 60_000, 2);
        orchestrator.tickNowForTests();
        assertThat(workerBStarted.await(5, TimeUnit.SECONDS)).isTrue();
        AgentRunner.AgentRunRequest requestA = requests.stream()
                .filter(request -> request.config().tracker().resolvedBoardId().equals("board-a"))
                .findFirst()
                .orElseThrow();
        requestA.listener().onEvent(agentEvent(requestA, "a/late-after-reload", "late from A"));
        RuntimeSnapshot whileBothRun = orchestrator.snapshot();
        CardDebugDetails detailsB = orchestrator.cardDetails("TRELLO-shared-b").orElseThrow();
        releaseWorkerA.countDown();
        waitUntil(() ->
                tracker.releases.size() == 1 && orchestrator.snapshot().counts().running() == 1);
        RuntimeSnapshot afterOldTargetExit = orchestrator.snapshot();
        releaseWorkerB.countDown();
        waitUntil(() -> orchestrator.snapshot().counts().running() == 0);
        RuntimeSnapshot afterBothExit = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(whileBothRun.running())
                .extracting(RuntimeSnapshot.RunningRow::cardId)
                .containsExactly("shared-card", "shared-card");
        assertThat(detailsB.workspace().path()).isEqualTo(tempDir.resolve("work-b/TRELLO-shared-b"));
        assertThat(detailsB.recentEvents())
                .extracting(CardDebugDetails.EventInfo::event)
                .containsExactly("b/running");
        assertThat(afterOldTargetExit.running())
                .singleElement()
                .extracting(RuntimeSnapshot.RunningRow::cardIdentifier)
                .isEqualTo("TRELLO-shared-b");
        assertThat(afterOldTargetExit.retrying()).isEmpty();
        assertThat(afterBothExit.retrying()).isEmpty();
        assertThat(requests).hasSize(2);
        verify(runner, never()).cancel(anyString());
    }

    @Test
    void staleOldTargetRetryCallbackCannotConsumeSameIdCurrentTargetRunningState() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String endpoint = "https://api.example.test/1";
        writeWorkflowWithTarget(workflow, endpoint, "board-a", "token-a", "same-command", "work-a");
        Card cardA = cardOnBoard("shared-card", "TRELLO-stale-retry-a", "Todo", "board-a");
        Card cardB = cardOnBoard("shared-card", "TRELLO-stale-retry-b", "Todo", "board-b");
        ScopedTracker tracker = new ScopedTracker();
        tracker.setCandidates(endpoint, "board-a", List.of(cardA));
        tracker.setCandidates(endpoint, "board-b", List.of(cardB));
        CountDownLatch workerBStarted = new CountDownLatch(1);
        CountDownLatch releaseWorkerB = new CountDownLatch(1);
        List<AgentRunner.AgentRunRequest> requests = new CopyOnWriteArrayList<>();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            requests.add(request);
            if (request.config().tracker().resolvedBoardId().equals("board-a")) {
                return AgentRunResult.fail("queue old-target retry");
            }
            return finishBoardBRun(tracker, endpoint, workerBStarted, releaseWorkerB, request);
        });
        SymphonyOrchestrator orchestrator = orchestrator(
                workflow, tracker, runner, Clock.fixed(COMMENT_TIME, ZoneOffset.UTC), successfulWorkpadHandler());
        CountDownLatch staleCallbackEntered = new CountDownLatch(1);
        CountDownLatch releaseStaleCallback = new CountDownLatch(1);
        orchestrator.retryTimerWaitingHookForTests = () -> {
            staleCallbackEntered.countDown();
            try {
                assertThat(releaseStaleCallback.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        };

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        CompletableFuture<Void> staleCallback =
                CompletableFuture.runAsync(() -> orchestrator.retryNowForTests("shared-card"));
        assertThat(staleCallbackEntered.await(5, TimeUnit.SECONDS)).isTrue();
        rewriteWorkflowWithTarget(workflow, endpoint, "board-b", "token-b", "same-command", "work-b");
        orchestrator.tickNowForTests();
        assertThat(workerBStarted.await(5, TimeUnit.SECONDS)).isTrue();
        RuntimeSnapshot beforeStaleCallback = orchestrator.snapshot();
        releaseStaleCallback.countDown();
        staleCallback.get(5, TimeUnit.SECONDS);
        RuntimeSnapshot afterStaleCallback = orchestrator.snapshot();
        releaseWorkerB.countDown();
        waitUntil(() -> orchestrator.snapshot().counts().running() == 0);
        orchestrator.stop();

        // then
        assertThat(beforeStaleCallback.running())
                .singleElement()
                .extracting(RuntimeSnapshot.RunningRow::cardIdentifier)
                .isEqualTo("TRELLO-stale-retry-b");
        assertThat(beforeStaleCallback.retrying()).isEmpty();
        assertThat(afterStaleCallback.running()).isEqualTo(beforeStaleCallback.running());
        assertThat(afterStaleCallback.retrying()).isEmpty();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).attempt()).isNull();
    }

    @Test
    void sameRawCardIdAcrossTargetsKeepsRecentEventsIsolated() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String endpoint = "https://api.example.test/1";
        writeWorkflowWithTarget(workflow, endpoint, "board-a", "token-a", "same-command", "work-a");
        Card cardA = cardOnBoard("shared-card", "TRELLO-history-a", "Todo", "board-a");
        Card cardB = cardOnBoard("shared-card", "TRELLO-history-b", "Todo", "board-b");
        ScopedTracker tracker = new ScopedTracker();
        tracker.setCandidates(endpoint, "board-a", List.of(cardA));
        tracker.setCandidates(endpoint, "board-b", List.of(cardB));
        CountDownLatch workerBStarted = new CountDownLatch(1);
        CountDownLatch workerACompleted = new CountDownLatch(1);
        CountDownLatch releaseWorkerB = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            String boardId = request.config().tracker().resolvedBoardId();
            request.listener()
                    .onEvent(agentEvent(
                            request,
                            boardId.equals("board-a") ? "a/history" : "b/history",
                            boardId.equals("board-a") ? "from A" : "from B"));
            if (boardId.equals("board-a")) {
                tracker.setCardState(
                        endpoint,
                        "board-a",
                        cardOnBoard(request.card().id(), request.card().identifier(), "Human Review", "board-a"));
                workerACompleted.countDown();
                return AgentRunResult.ok();
            }
            return finishBoardBRun(tracker, endpoint, workerBStarted, releaseWorkerB, request);
        });
        SymphonyOrchestrator orchestrator = orchestrator(
                workflow, tracker, runner, Clock.fixed(COMMENT_TIME, ZoneOffset.UTC), successfulWorkpadHandler());

        // when
        orchestrator.start();
        assertThat(workerACompleted.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> orchestrator.snapshot().counts().running() == 0);
        rewriteWorkflowWithTarget(workflow, endpoint, "board-b", "token-b", "same-command", "work-b");
        orchestrator.tickNowForTests();
        assertThat(workerBStarted.await(5, TimeUnit.SECONDS)).isTrue();
        CardDebugDetails details = orchestrator.cardDetails("TRELLO-history-b").orElseThrow();
        releaseWorkerB.countDown();
        waitUntil(() -> orchestrator.snapshot().counts().running() == 0);
        orchestrator.stop();

        // then
        assertThat(details.recentEvents())
                .extracting(CardDebugDetails.EventInfo::event)
                .containsExactly("b/history");
    }

    @Test
    void missingLaunchTargetCardAfterReloadCleansOnlyLaunchWorkspaceRoot() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String endpoint = "https://api.example.test/1";
        writeWorkflowWithTarget(workflow, endpoint, "board-a", "token-a", "command", "work-a");
        Card cardA = cardOnBoard("shared-card", "TRELLO-workspace", "Todo", "board-a");
        ScopedTracker tracker = new ScopedTracker();
        tracker.setCandidates(endpoint, "board-a", List.of(cardA));
        tracker.setCandidates(endpoint, "board-b", List.of());
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            workerStarted.countDown();
            try {
                releaseWorker.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentRunResult.fail("cancelled after card disappeared");
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        Path launchWorkspace = tempDir.resolve("work-a/TRELLO-workspace");
        Path reloadedWorkspace = tempDir.resolve("work-b/TRELLO-workspace");
        Files.createDirectories(launchWorkspace);
        Files.createDirectories(reloadedWorkspace);
        Files.writeString(launchWorkspace.resolve("launch.txt"), "launch");
        Files.writeString(reloadedWorkspace.resolve("sentinel.txt"), "must survive");
        tracker.removeCardState(endpoint, "board-a", "shared-card");
        rewriteWorkflowWithTarget(workflow, endpoint, "board-b", "token-b", "command", "work-b");
        orchestrator.tickNowForTests();
        releaseWorker.countDown();
        orchestrator.stop();

        // then
        assertThat(launchWorkspace).doesNotExist();
        assertThat(reloadedWorkspace.resolve("sentinel.txt")).exists();
    }

    @Test
    void reloadedDisabledStallTimeoutDoesNotSuppressLaunchTimeout() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithStallTimeout(workflow, 1_000);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-stall", "Todo")));
        AgentRunner runner = mock();
        BlockingRun blocking = blockRunnerUntilCancelled(runner);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        assertThat(blocking.started().await(5, TimeUnit.SECONDS)).isTrue();
        rewriteWorkflowWithStallTimeout(workflow, 0);
        clock.advance(Duration.ofSeconds(2));
        orchestrator.tickNowForTests();
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.running()).isEmpty();
        assertThat(blocking.cancelled()).hasValueGreaterThanOrEqualTo(1);
    }

    @Test
    void reloadedPositiveStallTimeoutDoesNotApplyToLaunchWithDisabledTimeout() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithStallTimeout(workflow, 0);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-no-stall", "Todo")));
        AgentRunner runner = mock();
        BlockingRun blocking = blockRunnerUntilCancelled(runner);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        assertThat(blocking.started().await(5, TimeUnit.SECONDS)).isTrue();
        rewriteWorkflowWithStallTimeout(workflow, 1_000);
        clock.advance(Duration.ofSeconds(2));
        orchestrator.tickNowForTests();
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.running()).hasSize(1);
    }
}
