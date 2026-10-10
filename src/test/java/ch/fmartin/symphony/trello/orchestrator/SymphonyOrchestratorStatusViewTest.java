package ch.fmartin.symphony.trello.orchestrator;

import static ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestratorTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.agent.AgentRunResult;
import ch.fmartin.symphony.trello.agent.AgentRunner;
import ch.fmartin.symphony.trello.agent.TrelloHandoffToolHandler;
import ch.fmartin.symphony.trello.domain.Card;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Owns the published status view: status readers take no lock, and an operation publishes a
/// reader-visible change before the Trello, worker or filesystem I/O that follows it. Each test
/// reads the status from inside that I/O, where the operation still holds the operation lock.
/// ADR 0105 maps the publish points to these tests.
final class SymphonyOrchestratorStatusViewTest {
    private static final Duration STALL_TIMEOUT = Duration.ofSeconds(1);
    /// Lets one tick dispatch a second card while the first one still runs.
    private static final String TWO_AGENTS = "agent:\n  max_concurrent_agents: 2";
    private static final Optional<RuntimeSnapshot.DispatchPause> NO_PAUSE = Optional.empty();

    @TempDir
    Path tempDir;

    @Test
    void cardDetailsAndLifecycleReadsAnswerWhileATickIsBlockedInsideTrelloFetch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        var tracker = new BlockingTracker();
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, mock());
        orchestrator.start();
        assertThat(tracker.firstFetchStarted.await(5, TimeUnit.SECONDS))
                .as("the blocked tick should enter its first tracker fetch within 5 seconds")
                .isTrue();

        // when
        CompletableFuture<Optional<CardDebugDetails>> details =
                CompletableFuture.supplyAsync(() -> orchestrator.cardDetails("TRELLO-abc"));
        CompletableFuture<Boolean> started = CompletableFuture.supplyAsync(orchestrator::isStarted);

        // then
        try {
            assertThat(details)
                    .as("card details must not wait for the in-flight Trello poll")
                    .succeedsWithin(Duration.ofSeconds(2))
                    .isEqualTo(Optional.empty());
            assertThat(started)
                    .as("the started state must not wait for the in-flight Trello poll")
                    .succeedsWithin(Duration.ofSeconds(2))
                    .isEqualTo(true);
        } finally {
            tracker.releaseFirstFetch.countDown();
            orchestrator.stop();
        }
    }

    @Test
    void localStatusShowsTheSelectedBoardWhileStartupCleanupFetchesTerminalCards() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        var tracker = new StartBlockingTracker();
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, mock());
        var starter = Thread.ofPlatform().start(orchestrator::start);

        // when
        assertThat(tracker.terminalFetchStarted.await(5, TimeUnit.SECONDS))
                .as("startup should enter terminal-card fetching within 5 seconds")
                .isTrue();
        String boardDuringStartup = orchestrator.selectedBoardId();
        boolean startedDuringStartup = orchestrator.isStarted();
        tracker.releaseTerminalFetch.countDown();
        assertThat(starter.join(Duration.ofSeconds(5)))
                .as("startup should finish within 5 seconds once the terminal fetch is released")
                .isTrue();
        boolean startedAfterStartup = orchestrator.isStarted();
        orchestrator.stop();

        // then
        assertThat(boardDuringStartup)
                .as("the board resolved by startup must be visible before the startup cleanup I/O")
                .isEqualTo("board-1");
        assertThat(startedDuringStartup)
                .as("the orchestrator must not report started before startup finished")
                .isFalse();
        assertThat(startedAfterStartup)
                .as("the orchestrator must report started once start() returned")
                .isTrue();
    }

    @Test
    void workerSeesItsCardRunningWhileTheDispatchingTickContinues() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000", TWO_AGENTS);
        var tracker = new FakeTracker(List.of(
                TestCards.card("card-1", "TRELLO-first", "Todo"), TestCards.card("card-2", "TRELLO-second", "Todo")));
        var firstWorkerRead = new CountDownLatch(1);
        var runningSeenByFirstWorker = new AtomicReference<RuntimeSnapshot>();
        var orchestratorRef = new AtomicReference<SymphonyOrchestrator>();
        AgentRunner runner = mock();
        doAnswer(invocation -> {
                    AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                    if (request.card().id().equals("card-1")) {
                        runningSeenByFirstWorker.set(orchestratorRef.get().snapshot());
                        firstWorkerRead.countDown();
                    }
                    return AgentRunResult.ok();
                })
                .when(runner)
                .run(any());
        var promptRefreshes = new AtomicInteger();
        var secondDispatchReached = new CountDownLatch(1);
        // The second prompt refresh belongs to the second card, after the first card's dispatch.
        tracker.stateFetchHook = () -> {
            if (promptRefreshes.incrementAndGet() == 2) {
                secondDispatchReached.countDown();
                awaitRelease(firstWorkerRead, "the tick paused before its second dispatch");
            }
        };
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);
        orchestratorRef.set(orchestrator);

        // when
        orchestrator.start();
        assertThat(secondDispatchReached.await(5, TimeUnit.SECONDS))
                .as("the tick should reach the second card's prompt refresh within 5 seconds")
                .isTrue();
        assertThat(firstWorkerRead.await(5, TimeUnit.SECONDS))
                .as("the first worker should read the status within 5 seconds")
                .isTrue();
        orchestrator.stop();

        // then
        assertThat(runningSeenByFirstWorker.get().running())
                .as("a dispatched card must show as running before its tick continues with the next card")
                .extracting(RuntimeSnapshot.RunningRow::cardIdentifier)
                .containsExactly("TRELLO-first");
    }

    @Test
    void workerExitRefreshSeesTheCardNoLongerRunning() throws Exception {
        // given
        FailingWorkerScenario scenario = failingWorkerScenario();
        var snapshotDuringExitRefresh = new AtomicReference<RuntimeSnapshot>();
        // Workers run on virtual threads, so the first virtual-thread fetch is the worker-exit refresh.
        scenario.tracker().stateFetchHook = () -> {
            if (Thread.currentThread().isVirtual()) {
                snapshotDuringExitRefresh.compareAndSet(
                        null, scenario.orchestrator().snapshot());
            }
        };

        // when
        scenario.startAndAwaitRetry();
        scenario.orchestrator().stop();

        // then
        assertThat(snapshotDuringExitRefresh.get())
                .as("the worker-exit card refresh should have read the status")
                .isNotNull()
                .extracting(RuntimeSnapshot::counts)
                .as("an exited worker must leave the running rows before its worker-exit Trello I/O")
                .isEqualTo(new RuntimeSnapshot.Counts(0, 0));
    }

    @Test
    void terminatedCardLeavesTheRunningRowsBeforeItsWorkerIsCancelled() throws Exception {
        // given
        BlockedWorkerScenario scenario = startOneBlockedWorker();
        FakeTracker tracker = scenario.tracker();
        SymphonyOrchestrator orchestrator = scenario.orchestrator();
        CancelObservation cancel = scenario.cancel();
        tracker.setCardState(TestCards.card("card-1", "TRELLO-abc", "done"));

        // when
        orchestrator.tickNowForTests();
        RuntimeSnapshot seenByCancel = cancel.snapshot().get();
        orchestrator.stop();

        // then
        assertThat(seenByCancel)
                .as("reconciliation should have cancelled the worker of the terminal card")
                .isNotNull()
                .extracting(RuntimeSnapshot::running, list(RuntimeSnapshot.RunningRow.class))
                .as("a terminated card must leave the running rows before its worker is cancelled")
                .isEmpty();
    }

    @Test
    void stopShowsTheStoppedStateWhileItCancelsWorkers() throws Exception {
        // given
        BlockedWorkerScenario scenario = startOneBlockedWorker();
        SymphonyOrchestrator orchestrator = scenario.orchestrator();
        CancelObservation cancel = scenario.cancel();

        // when
        orchestrator.stop();

        // then
        assertThat(cancel.snapshot().get())
                .as("stop should have cancelled the running worker")
                .isNotNull()
                .extracting(RuntimeSnapshot::counts)
                .as("stop must clear the running rows before it cancels the workers")
                .isEqualTo(new RuntimeSnapshot.Counts(0, 0));
        assertThat(cancel.startedWhenCancelled())
                .as("stop must report the orchestrator as stopped before it cancels the workers")
                .hasValue(false);
    }

    @Test
    void reloadedRoutingIsVisibleWhileTheReloadTickReconcilesRunningCards() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithActiveStates(workflow, "[Todo]", "");
        var tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        BlockingRun blockingRun = blockRunnerUntilCancelled(runner);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);
        orchestrator.start();
        assertThat(blockingRun.started().await(5, TimeUnit.SECONDS))
                .as("the worker should start within 5 seconds")
                .isTrue();
        // Only the manual tick below may reload, so the reload and its reconciliation share one tick.
        orchestrator.stopWorkflowWatcherForTests();
        long previousModified = Files.getLastModifiedTime(workflow).toMillis();
        writeWorkflowWithActiveStates(workflow, "[Todo, Doing]", "");
        Files.setLastModifiedTime(workflow, FileTime.fromMillis(previousModified + 2_000));
        var routingDuringReconcile = new AtomicReference<RuntimeSnapshot.Routing>();
        tracker.stateFetchHook = () -> routingDuringReconcile.compareAndSet(
                null, orchestrator.snapshot().routing());

        // when
        orchestrator.tickNowForTests();
        orchestrator.stop();

        // then
        assertThat(routingDuringReconcile.get())
                .as("the reload tick should refresh the running card")
                .isNotNull()
                .extracting(RuntimeSnapshot.Routing::activeLists, list(String.class))
                .as("a reloaded workflow must be visible before the reconciliation Trello I/O")
                .containsExactly("Todo", "Doing");
    }

    @Test
    void retryTimerCardRefreshSeesTheRetryLeaveTheQueue() throws Exception {
        // given
        FailingWorkerScenario scenario = failingWorkerScenario();
        SymphonyOrchestrator orchestrator = scenario.orchestrator();
        scenario.startAndAwaitRetry();
        var snapshotDuringRetryRefresh = new AtomicReference<RuntimeSnapshot>();
        scenario.tracker().stateFetchHook =
                () -> snapshotDuringRetryRefresh.compareAndSet(null, orchestrator.snapshot());

        // when
        orchestrator.retryNowForTests("card-1");
        orchestrator.stop();

        // then
        assertThat(snapshotDuringRetryRefresh.get())
                .as("the retry timer should refresh the card")
                .isNotNull()
                .extracting(RuntimeSnapshot::counts)
                .as("a fired retry must leave the retry rows before its card refresh")
                .isEqualTo(new RuntimeSnapshot.Counts(0, 0));
    }

    @Test
    void refreshedCardStateIsVisibleWhileTheTickDispatchesTheNextCard() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithActiveStates(workflow, "[Todo, Doing]", TWO_AGENTS);
        Card first = TestCards.card("card-1", "TRELLO-first", "Todo");
        var tracker = new FakeTracker(List.of(first));
        AgentRunner runner = mock();
        BlockingRun blockingRun = blockRunnerUntilCancelled(runner);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);
        orchestrator.start();
        assertThat(blockingRun.started().await(5, TimeUnit.SECONDS))
                .as("the first worker should start within 5 seconds")
                .isTrue();
        tracker.setCandidates(List.of(
                TestCards.card("card-1", "TRELLO-first", "Doing"), TestCards.card("card-2", "TRELLO-second", "Todo")));
        AtomicReference<RuntimeSnapshot> atNextDispatch = captureStatusAtNextPromptRefresh(tracker, orchestrator);

        // when
        orchestrator.tickNowForTests();
        orchestrator.stop();

        // then
        assertThat(atNextDispatch.get())
                .as("the tick should refresh the second card before dispatching it")
                .isNotNull()
                .extracting(RuntimeSnapshot::running, list(RuntimeSnapshot.RunningRow.class))
                .as("a reconciled card state must be visible before the tick dispatches the next card")
                .singleElement()
                .extracting(RuntimeSnapshot.RunningRow::state)
                .isEqualTo("Doing");
    }

    @Test
    void scheduledRetryIsVisibleWhileTheTickDispatchesTheNextCard() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithStallTimeout(workflow, STALL_TIMEOUT.toMillis());
        var clock = new MutableClock(Instant.parse("2026-07-10T12:00:00Z"));
        var tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-stalled", "Todo")));
        AgentRunner runner = mock();
        BlockingRun blockingRun = blockRunnerUntilCancelled(runner);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());
        orchestrator.start();
        assertThat(blockingRun.started().await(5, TimeUnit.SECONDS))
                .as("the first worker should start within 5 seconds")
                .isTrue();
        tracker.setCandidates(List.of(
                TestCards.card("card-1", "TRELLO-stalled", "Todo"), TestCards.card("card-2", "TRELLO-next", "Todo")));
        clock.advance(STALL_TIMEOUT.multipliedBy(2));
        AtomicReference<RuntimeSnapshot> atNextDispatch = captureStatusAtNextPromptRefresh(tracker, orchestrator);

        // when
        orchestrator.tickNowForTests();
        orchestrator.stop();

        // then
        assertThat(atNextDispatch.get())
                .as("the tick should refresh the second card before dispatching it")
                .isNotNull()
                .extracting(RuntimeSnapshot::retrying, list(RuntimeSnapshot.RetryRow.class))
                .as("the retry of a stalled card must be visible before the tick dispatches the next card")
                .extracting(RuntimeSnapshot.RetryRow::cardIdentifier)
                .containsExactly("TRELLO-stalled");
    }

    @Test
    void rearmedUsagePauseIsVisibleBeforeItsWorkpadUpdate() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000", "agent:\n  max_retry_backoff_ms: 0");
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        var clock = new MutableClock(now);
        var tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-first", "Todo")));
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            tracker.setCardState(TestCards.card("card-1", "TRELLO-first", "Human Review"));
            return AgentRunResult.codexUsageLimit("turn_failed: Usage is unavailable.", Optional.empty());
        });
        var orchestratorRef = new AtomicReference<SymphonyOrchestrator>();
        WorkpadObservation workpads = observeStatusOnWorkpadWrites(orchestratorRef);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads.handler());
        orchestratorRef.set(orchestrator);
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null);
        tracker.setCandidateList(List.of());
        clock.advance(Duration.ofSeconds(1));

        // when
        orchestrator.dispatchPauseDeadlineNowForTests(now.plusSeconds(1));
        orchestrator.stop();

        // then
        assertThat(workpads.pausesSeenByUpdates())
                .as("each paused workpad section must be written after the status shows the same deadline")
                .extracting(RuntimeSnapshot.DispatchPause::until)
                .containsExactly(now.plusSeconds(1), now.plusSeconds(2));
    }

    @Test
    void clearedUsagePauseIsVisibleBeforeItsWorkpadCleanup() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000", "agent:\n  max_retry_backoff_ms: 60000");
        var clock = new MutableClock(Instant.parse("2026-07-10T12:00:00Z"));
        var tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        var runs = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            if (runs.incrementAndGet() == 1) {
                return AgentRunResult.codexUsageLimit("turn_failed: Usage is unavailable.", Optional.empty());
            }
            tracker.setCardState(TestCards.card("card-1", "TRELLO-abc", "Human Review"));
            return AgentRunResult.ok();
        });
        var orchestratorRef = new AtomicReference<SymphonyOrchestrator>();
        WorkpadObservation workpads = observeStatusOnWorkpadWrites(orchestratorRef);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads.handler());
        orchestratorRef.set(orchestrator);
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        clock.advance(Duration.ofSeconds(60));

        // when
        orchestrator.retryNowForTests("card-1");
        waitUntil(() -> runs.get() == 2 && orchestrator.snapshot().dispatchPause() == null);
        orchestrator.stop();

        // then
        assertThat(workpads.pausesSeenByCleanups())
                .as("the usage workpad cleanup must run after the status shows the pause as cleared")
                .containsExactly(NO_PAUSE);
    }

    /// One card whose worker fails with a generic error, so the orchestrator schedules a retry.
    private FailingWorkerScenario failingWorkerScenario() throws Exception {
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        var tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        when(runner.run(any())).thenReturn(AgentRunResult.fail("generic failure"));
        return new FailingWorkerScenario(tracker, orchestrator(workflow, tracker, runner));
    }

    private record FailingWorkerScenario(FakeTracker tracker, SymphonyOrchestrator orchestrator) {
        void startAndAwaitRetry() throws Exception {
            orchestrator.start();
            waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        }
    }

    /// Starts an orchestrator whose only card runs a worker that blocks until it is cancelled.
    private BlockedWorkerScenario startOneBlockedWorker() throws Exception {
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        var tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        var orchestratorRef = new AtomicReference<SymphonyOrchestrator>();
        AgentRunner runner = mock();
        CancelObservation cancel = blockUntilCancelledAndObserveStatus(runner, orchestratorRef);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);
        orchestratorRef.set(orchestrator);
        orchestrator.start();
        assertThat(cancel.started().await(5, TimeUnit.SECONDS))
                .as("the worker should start within 5 seconds")
                .isTrue();
        return new BlockedWorkerScenario(tracker, orchestrator, cancel);
    }

    private record BlockedWorkerScenario(
            FakeTracker tracker, SymphonyOrchestrator orchestrator, CancelObservation cancel) {}

    /// Blocks every run until the orchestrator cancels it, and records the status the orchestrator
    /// shows at the moment it cancels the worker.
    private static CancelObservation blockUntilCancelledAndObserveStatus(
            AgentRunner runner, AtomicReference<SymphonyOrchestrator> orchestrator) {
        var started = new CountDownLatch(1);
        var snapshot = new AtomicReference<RuntimeSnapshot>();
        var startedWhenCancelled = new AtomicReference<Boolean>();
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
                    snapshot.compareAndSet(null, orchestrator.get().snapshot());
                    startedWhenCancelled.compareAndSet(null, orchestrator.get().isStarted());
                    return null;
                })
                .when(runner)
                .cancel(any());
        return new CancelObservation(started, snapshot, startedWhenCancelled);
    }

    /// Records the dispatch pause the status shows each time the orchestrator writes a usage
    /// workpad section, separately for paused sections and for cleanups.
    private static WorkpadObservation observeStatusOnWorkpadWrites(AtomicReference<SymphonyOrchestrator> orchestrator) {
        // Operation threads record while the test thread reads, so the lists must be thread-safe.
        List<RuntimeSnapshot.DispatchPause> pausesSeenByUpdates = new CopyOnWriteArrayList<>();
        List<Optional<RuntimeSnapshot.DispatchPause>> pausesSeenByCleanups = new CopyOnWriteArrayList<>();
        TrelloHandoffToolHandler handler = mock();
        doAnswer(invocation -> {
                    RuntimeSnapshot.DispatchPause shown =
                            orchestrator.get().snapshot().dispatchPause();
                    if (invocation.getArgument(2) == null) {
                        pausesSeenByCleanups.add(Optional.ofNullable(shown));
                    } else {
                        pausesSeenByUpdates.add(shown);
                    }
                    return true;
                })
                .when(handler)
                .updateCodexUsageSection(any(), anyString(), nullable(String.class));
        return new WorkpadObservation(handler, pausesSeenByUpdates, pausesSeenByCleanups);
    }

    private record WorkpadObservation(
            TrelloHandoffToolHandler handler,
            List<RuntimeSnapshot.DispatchPause> pausesSeenByUpdates,
            List<Optional<RuntimeSnapshot.DispatchPause>> pausesSeenByCleanups) {}

    /// Captures the status at the first prompt refresh from now on, which a tick runs right before
    /// it dispatches a card.
    private static AtomicReference<RuntimeSnapshot> captureStatusAtNextPromptRefresh(
            FakeTracker tracker, SymphonyOrchestrator orchestrator) {
        int promptRefreshesBefore = tracker.promptStateFetches.get();
        var captured = new AtomicReference<RuntimeSnapshot>();
        tracker.stateFetchHook = () -> {
            if (tracker.promptStateFetches.get() > promptRefreshesBefore) {
                captured.compareAndSet(null, orchestrator.snapshot());
            }
        };
        return captured;
    }

    private record CancelObservation(
            CountDownLatch started,
            AtomicReference<RuntimeSnapshot> snapshot,
            AtomicReference<Boolean> startedWhenCancelled) {}
}
