package ch.fmartin.symphony.trello.orchestrator;

import static ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestratorTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.agent.AgentEvent;
import ch.fmartin.symphony.trello.agent.AgentEventListener;
import ch.fmartin.symphony.trello.agent.AgentRunResult;
import ch.fmartin.symphony.trello.agent.AgentRunner;
import ch.fmartin.symphony.trello.agent.CardHandoffMove;
import ch.fmartin.symphony.trello.agent.CodexAppServerClient;
import ch.fmartin.symphony.trello.agent.TrelloHandoffToolHandler;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

final class SymphonyOrchestratorHandoffTest {
    private static final String CARD_ID = "card-1";
    private static final String CARD_IDENTIFIER = "TRELLO-handoff";
    private static final String REVIEW_LIST = "Human Review";
    private static final String ACTIVE_STATE = "Todo";
    private static final int DEFAULT_TURN_TIMEOUT_MS = 15000;
    private static final Duration HANDOFF_COMPLETION_WINDOW = Duration.ofSeconds(60);
    private static final Duration DEADLINE_BOUNDARY_OFFSET = Duration.ofSeconds(1);
    private static final Duration STALL_TIMEOUT_ADVANCE = Duration.ofMinutes(6);

    @TempDir
    Path tempDir;

    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ownHandoffFinishesCurrentTurnAcrossReconciliation(boolean refreshBeforeMoveResponse) throws Exception {
        // given
        try (var run = new HandoffRun(refreshBeforeMoveResponse, false, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            if (!refreshBeforeMoveResponse) {
                run.awaitFinalStarted();
            }

            // when
            run.refresh();

            // then
            assertThat(run.orchestrator.snapshot().counts().running())
                    .as("reconciliation must retain the current turn after its own handoff")
                    .isOne();
            run.releaseMove.countDown();
            run.finish();
            run.assertCompletedFinal();
            assertThat(run.orchestrator.snapshot().counts().retrying()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing",
                "archived",
                "list-archived",
                "board-archived",
                "other-board",
                "other-list",
                "unknown-list-open",
                "unknown-board-open"
            })
    void externalStateChangesStillCancelCurrentTurn(String change) throws Exception {
        // given
        try (var run = new HandoffRun(false, false, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            if ("missing".equals(change)) {
                run.tracker.cardStates = Map.of(CARD_ID, new CardLookupResult.Missing(CARD_ID));
            } else {
                run.tracker.setCardState(destination(REVIEW_LIST, change));
            }

            // when
            run.refresh();

            // then
            assertThat(run.orchestrator.snapshot().counts().running()).isZero();
            String workerIdentity = run.move().workerIdentity();
            verify(run.runner).cancel(workerIdentity);
        }
    }

    @Test
    void failedMoveAndLateSuccessCallbackDoNotProtectInactiveCard() throws Exception {
        // given
        try (var run = new HandoffRun(false, true, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            run.listener.get().afterHandoffMove(run.move(), true);

            // when
            run.refresh();

            // then
            assertThat(run.orchestrator.snapshot().counts().running()).isZero();
            String workerIdentity = run.move().workerIdentity();
            verify(run.runner).cancel(workerIdentity);
        }
    }

    @Test
    void repeatedRegistrationAndEventsDoNotRenewCompletionDeadline() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            run.clock.advance(HANDOFF_COMPLETION_WINDOW.minus(DEADLINE_BOUNDARY_OFFSET));
            CardHandoffMove first = run.move();
            var repeated = new CardHandoffMove(
                    "repeat",
                    first.workerIdentity(),
                    first.threadId(),
                    first.turnId(),
                    first.cardId(),
                    first.boardId(),
                    first.listId());
            assertThat(run.listener.get().beforeHandoffMove(repeated))
                    .as("a repeated owned move shares the original completion deadline")
                    .isTrue();
            run.listener.get().afterHandoffMove(repeated, true);
            run.listener
                    .get()
                    .onEvent(new AgentEvent(
                            "item/agentMessage/delta",
                            run.clock.instant(),
                            first.workerIdentity(),
                            null,
                            first.threadId(),
                            first.turnId(),
                            "still finishing",
                            Map.of(),
                            null));
            run.refresh();
            assertThat(run.orchestrator.snapshot().counts().running()).isOne();

            // when
            run.clock.advance(DEADLINE_BOUNDARY_OFFSET);
            run.refresh();

            // then
            assertThat(run.orchestrator.snapshot().counts().running()).isZero();
            verify(run.runner).cancel(first.workerIdentity());
            assertThat(run.listener.get().beforeHandoffMove(repeated))
                    .as("an expired worker cannot register another handoff")
                    .isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "thread", "turn", "board", "card"})
    void registrationRejectsWrongOwner(String mismatch) throws Exception {
        // given
        try (var run = new HandoffRun(false, false, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            CardHandoffMove first = run.move();
            var other = new CardHandoffMove(
                    "other",
                    "worker".equals(mismatch) ? "stale-worker" : first.workerIdentity(),
                    "thread".equals(mismatch) ? "stale-thread" : first.threadId(),
                    "turn".equals(mismatch) ? "stale-turn" : first.turnId(),
                    "card".equals(mismatch) ? "another-card" : first.cardId(),
                    "board".equals(mismatch) ? "another-board" : first.boardId(),
                    "list-other");

            // when
            boolean accepted = run.listener.get().beforeHandoffMove(other);
            run.listener.get().afterHandoffMove(other, true);
            run.tracker.setCardState(destination(REVIEW_LIST, "other-list"));
            run.refresh();

            // then
            assertThat(accepted)
                    .as("only the current worker, turn, card and board own a handoff")
                    .isFalse();
            assertThat(run.orchestrator.snapshot().counts().running()).isZero();
        }
    }

    @Test
    void reactivationDoesNotStartAnotherTurnInTheHandoffSession() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            run.tracker.setCardState(TestCards.card(CARD_ID, CARD_IDENTIFIER, ACTIVE_STATE));
            run.refresh();

            // when
            run.finish();

            // then
            run.assertCompletedFinal();
        }
    }

    @Test
    void terminalHandoffKeepsWorkspaceUntilTheFinalResponseCompletes() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, "Done", DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();

            // when
            run.refresh();

            // then
            assertThat(run.workspace.get()).isDirectory();
            assertThat(run.orchestrator.snapshot().counts().running()).isOne();
            run.finish();
            run.assertCompletedFinal();
            waitUntil(() -> !Files.exists(run.workspace.get()));
        }
    }

    @Test
    void ownedHandoffDoesNotExtendTurnTimeout() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, REVIEW_LIST, 2000)) {
            run.start();
            run.awaitFinalStarted();

            // when
            waitUntil(() -> run.result.get() != null);

            // then
            assertThat(run.result.get().success())
                    .as("the existing turn timeout remains in force")
                    .isFalse();
            assertThat(run.result.get().reason()).startsWith("turn_timeout:");
            assertThat(run.continuationCalls).hasValue(0);
        }
    }

    @Test
    void lateCallbacksCannotProtectAReplacementWorker() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            CardHandoffMove oldMove = run.move();
            AgentEventListener oldListener = run.listener.get();
            run.replaceWorker();

            // when
            boolean accepted = oldListener.beforeHandoffMove(oldMove);
            oldListener.afterHandoffMove(oldMove, true);
            run.tracker.setCardState(destination(REVIEW_LIST, "other-list"));
            run.refresh();

            // then
            assertThat(accepted)
                    .as("a replaced worker cannot register a move for its successor")
                    .isFalse();
            assertThat(run.orchestrator.snapshot().counts().running()).isZero();
        }
    }

    @Test
    void expiredHandoffWithUnconfirmedShutdownRetainsActiveWorkspace() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS)) {
            run.unconfirmedShutdown = true;
            var exited = new CountDownLatch(1);
            run.orchestrator.workerExitCompletionHookForTests = exited::countDown;
            run.start();
            run.awaitFinalStarted();
            run.tracker.setCardState(TestCards.card(CARD_ID, CARD_IDENTIFIER, ACTIVE_STATE));

            // when
            run.clock.advance(HANDOFF_COMPLETION_WINDOW);
            run.deadlineTasks.getFirst().run();
            assertThat(exited.await(5, TimeUnit.SECONDS))
                    .as("the expired handoff reports unconfirmed exit")
                    .isTrue();
            run.refresh();

            // then
            assertThat(run.workspace.get()).exists();
            assertThat(run.orchestrator.snapshot().counts().running()).isZero();
            assertThat(run.orchestrator.snapshot().counts().retrying()).isZero();
            verify(run.runner).run(any());
        }
    }

    @Test
    void ownedHandoffDoesNotExtendStallTimeout() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS, "stall_timeout_ms: 1000")) {
            run.start();
            run.awaitFinalStarted();

            // when
            run.clock.advance(Duration.ofSeconds(2));
            run.refresh();

            // then
            assertThat(run.orchestrator.snapshot().counts().running()).isZero();
            String workerIdentity = run.move().workerIdentity();
            verify(run.runner).cancel(workerIdentity);
        }
    }

    @Test
    void completedTurnCannotRegisterAnotherHandoffMove() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS)) {
            run.afterTurnMove = true;
            Path script = tempDir.resolve("handoff-app-server.sh");
            String toolRequest = "echo '{\"id\":100,\"method\":\"item/tool/call\"";
            String completed = "echo '{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread-handoff\","
                    + "\"turn\":{\"id\":\"turn-handoff\",\"status\":\"completed\",\"error\":null}}}'";
            Files.writeString(script, Files.readString(script).replace(toolRequest, completed + "\n" + toolRequest));

            // when
            run.orchestrator.start();
            waitUntil(() -> run.result.get() != null);

            // then
            assertThat(run.result.get()).isEqualTo(AgentRunResult.ok());
            verify(run.trello, never()).moveCardToList(any(), any(), any());
        }
    }

    @Test
    void deadlineAfterStallSelectionRetainsCleanupAndCardClaim() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, "Done", DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            String worker = run.move().workerIdentity();
            var selected = new CountDownLatch(1);
            var releaseStall = new CountDownLatch(1);
            run.orchestrator.stallSelectionHookForTests = () -> {
                selected.countDown();
                try {
                    assertThat(releaseStall.await(10, TimeUnit.SECONDS))
                            .as("the test resumes stall termination after the owned deadline fires")
                            .isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "cannot finish stall test: expected release before interruption", e);
                }
            };
            Card candidate = TestCards.card(CARD_ID, CARD_IDENTIFIER, ACTIVE_STATE);
            run.tracker.setCandidateList(List.of(candidate));
            run.tracker.setPromptCardState(candidate);
            run.tracker.cardStates = Map.of(
                    CARD_ID,
                    new CardLookupResult.Failed(CARD_ID, "test_unavailable", "Tracker is unavailable in this test."));
            run.clock.advance(STALL_TIMEOUT_ADVANCE);
            int beforeTick = run.completedTicks.get();
            try {
                run.orchestrator.requestRefresh();
                assertThat(selected.await(5, TimeUnit.SECONDS))
                        .as("the stalled entry is selected before the deadline changes its cancellation state")
                        .isTrue();

                // when
                run.deadlineTasks.getFirst().run();
                waitUntil(() -> run.result.get() != null);
                run.orchestrator.stallSelectionHookForTests = () -> {};
                releaseStall.countDown();
                waitUntil(() -> run.completedTicks.get() > beforeTick);

                // then
                assertThat(run.orchestrator.snapshot().counts().running())
                        .as("unknown cleanup keeps the original claim instead of redispatching the card")
                        .isZero();
                assertThat(run.orchestrator.snapshot().counts().retrying()).isZero();
                assertThat(run.workspace.get()).isDirectory();
                run.refresh();
                assertThat(run.orchestrator.snapshot().counts().running()).isZero();
                run.tracker.setCandidateList(List.of());
                run.tracker.setCardState(destination("Done", "open"));
                run.refresh();
                assertThat(run.workspace.get()).doesNotExist();
                verify(run.workspaces).removeForIdentifierIfPresent(eq(CARD_IDENTIFIER), any());
                verify(run.runner).run(any());
                verify(run.runner).cancel(worker);
            } finally {
                run.orchestrator.stallSelectionHookForTests = () -> {};
                releaseStall.countDown();
            }
        }
    }

    @Test
    void deadlineCancelsWorkerBeforeBlockedTrackerReconciliationReturns() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            var trackerEntered = new CountDownLatch(1);
            var releaseTracker = new CountDownLatch(1);
            var cancelled = new CountDownLatch(1);
            String worker = run.move().workerIdentity();
            doAnswer(invocation -> {
                        cancelled.countDown();
                        return null;
                    })
                    .when(run.runner)
                    .cancel(worker);
            run.tracker.stateFetchHook = () -> {
                trackerEntered.countDown();
                try {
                    assertThat(releaseTracker.await(10, TimeUnit.SECONDS))
                            .as("the test releases the blocked tracker after observing cancellation")
                            .isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "cannot finish tracker test: expected release before interruption", e);
                }
            };
            try {
                run.orchestrator.requestRefresh();
                assertThat(trackerEntered.await(5, TimeUnit.SECONDS))
                        .as("reconciliation entered the tracker")
                        .isTrue();
                int blockedTick = run.completedTicks.get();
                assertThat(run.deadlineTasks)
                        .as("the owned move schedules its independent deadline")
                        .hasSize(1);
                run.clock.advance(HANDOFF_COMPLETION_WINDOW);

                // when
                Thread deadline = Thread.ofVirtual().start(run.deadlineTasks.getFirst());

                // then
                assertThat(cancelled.await(2, TimeUnit.SECONDS))
                        .as("deadline cancellation must precede release of the blocked tracker")
                        .isTrue();
                waitUntil(() -> run.result.get() != null);
                assertThat(run.result.get().success())
                        .as("deadline stops the unfinished protocol turn")
                        .isFalse();
                assertThat(run.orchestrator.snapshot().counts().running())
                        .as("cancellation removes a worker from live counts before blocked reconciliation returns")
                        .isZero();
                assertThat(run.orchestrator.cardDetails(CARD_IDENTIFIER)).isEmpty();
                double stoppedRuntime =
                        run.orchestrator.snapshot().codexTotals().secondsRunning();
                run.clock.advance(Duration.ofSeconds(10));
                assertThat(run.orchestrator.snapshot().codexTotals().secondsRunning())
                        .isEqualTo(stoppedRuntime);
                assertThat(releaseTracker.getCount()).isOne();
                releaseTracker.countDown();
                deadline.join(Duration.ofSeconds(5));
                waitUntil(() -> run.completedTicks.get() > blockedTick
                        && run.orchestrator.snapshot().counts().running() == 0);
                assertThat(run.orchestrator.snapshot().codexTotals().secondsRunning())
                        .isEqualTo(stoppedRuntime);
                verify(run.runner).cancel(worker);
            } finally {
                releaseTracker.countDown();
                run.tracker.stateFetchHook = () -> {};
            }
        }
    }

    @Test
    void deadlineTaskDoesNotWaitForRunnerCancellation() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            var cancelEntered = new CountDownLatch(1);
            var releaseCancel = new CountDownLatch(1);
            String worker = run.move().workerIdentity();
            doAnswer(invocation -> {
                        cancelEntered.countDown();
                        assertThat(releaseCancel.await(10, TimeUnit.SECONDS))
                                .as("the test releases runner cancellation after observing the deadline task")
                                .isTrue();
                        return null;
                    })
                    .when(run.runner)
                    .cancel(worker);
            run.clock.advance(HANDOFF_COMPLETION_WINDOW);
            try {

                // when
                Thread deadline = Thread.ofVirtual().start(run.deadlineTasks.getFirst());
                assertThat(cancelEntered.await(5, TimeUnit.SECONDS))
                        .as("runner cancellation begins")
                        .isTrue();
                deadline.join(Duration.ofSeconds(2));

                // then
                assertThat(deadline.isAlive())
                        .as("one slow runner cannot occupy the deadline scheduler")
                        .isFalse();
                waitUntil(() -> run.result.get() != null);
                assertThat(run.result.get().success())
                        .as("the worker is interrupted before runner cancellation returns")
                        .isFalse();
                assertThat(releaseCancel.getCount()).isOne();
            } finally {
                releaseCancel.countDown();
            }
        }
    }

    @Test
    void stopFinishesRunnerCancellationWhenDeadlineCallbackIsRejected() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            String worker = run.move().workerIdentity();
            var marked = new CountDownLatch(1);
            var releaseDeadline = new CountDownLatch(1);
            run.orchestrator.handoffCancellationMarkedHookForTests = () -> {
                marked.countDown();
                try {
                    assertThat(releaseDeadline.await(10, TimeUnit.SECONDS))
                            .as("the test resumes the deadline after stop closes its executor")
                            .isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "cannot finish deadline test: expected release before interruption", e);
                }
            };
            run.clock.advance(HANDOFF_COMPLETION_WINDOW);
            Thread deadline = Thread.ofVirtual().start(run.deadlineTasks.getFirst());
            try {
                assertThat(marked.await(5, TimeUnit.SECONDS))
                        .as("deadline claims its current worker")
                        .isTrue();

                // when
                run.orchestrator.stop();
                releaseDeadline.countDown();
                deadline.join(Duration.ofSeconds(5));

                // then
                assertThat(deadline.isAlive())
                        .as("the rejected deadline callback finishes after shutdown")
                        .isFalse();
                verify(run.runner).cancel(worker);
                verify(run.deadlineScheduler).shutdownNow();
                waitUntil(() -> run.result.get() != null);
                assertThat(run.result.get().success())
                        .as("shutdown interrupts the unfinished worker")
                        .isFalse();
            } finally {
                releaseDeadline.countDown();
            }
        }
    }

    @Test
    void staleDeadlineTaskDoesNotCancelReplacementWorker() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, REVIEW_LIST, DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            Runnable staleDeadline = run.deadlineTasks.getFirst();
            run.replaceWorker();
            String replacement = run.move().workerIdentity();
            run.clock.advance(HANDOFF_COMPLETION_WINDOW);

            // when
            Thread deadline = Thread.ofVirtual().start(staleDeadline);
            deadline.join(Duration.ofSeconds(5));

            // then
            assertThat(deadline.isAlive())
                    .as("a stale deadline task returns without touching its successor")
                    .isFalse();
            verify(run.runner, never()).cancel(replacement);
            assertThat(run.orchestrator.snapshot().counts().running()).isOne();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "absent", "exception"})
    void terminalCleanupRemainsPendingUntilTrackerRecovers(String failure) throws Exception {
        // given
        try (var run = new HandoffRun(false, false, "Done", DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            String worker = run.move().workerIdentity();
            run.expireThroughTrackerOutage(failure);
            assertThat(run.orchestrator.snapshot().counts().running())
                    .as("a cancelled worker does not consume running capacity during cleanup")
                    .isZero();
            assertThat(run.orchestrator.snapshot().counts().retrying()).isZero();
            assertThat(run.workspace.get()).isDirectory();

            // when
            run.tracker.stateFetchHook = () -> {};
            run.tracker.setCardState(destination("Done", "open"));
            run.refresh();

            // then
            assertThat(run.orchestrator.snapshot().counts().running()).isZero();
            assertThat(run.orchestrator.snapshot().counts().retrying()).isZero();
            assertThat(run.workspace.get()).doesNotExist();
            verify(run.workspaces).removeForIdentifierIfPresent(eq(CARD_IDENTIFIER), any());
            verify(run.runner).run(any());
            verify(run.runner).cancel(worker);
        }
    }

    @Test
    void activeRecoveryDiscardsCancelledEntryWithoutRemovingWorkspace() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, "Done", DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            run.expireThroughTrackerOutage("failed");
            assertThat(run.orchestrator.snapshot().counts().running())
                    .as("unknown cleanup state does not report a healthy running worker")
                    .isZero();

            // when
            run.tracker.setCardState(TestCards.card(CARD_ID, CARD_IDENTIFIER, ACTIVE_STATE));
            run.refresh();

            // then
            assertThat(run.orchestrator.snapshot().counts().running()).isZero();
            assertThat(run.orchestrator.snapshot().counts().retrying()).isZero();
            assertThat(run.workspace.get()).isDirectory();
            verify(run.workspaces, never()).removeForIdentifierIfPresent(any(), any());
            verify(run.runner).run(any());
        }
    }

    @Test
    void confirmedMissingCardStillCompletesDeadlineCleanup() throws Exception {
        // given
        try (var run = new HandoffRun(false, false, "Done", DEFAULT_TURN_TIMEOUT_MS)) {
            run.start();
            run.awaitFinalStarted();
            run.tracker.setCandidateList(List.of());
            run.tracker.cardStates = Map.of(CARD_ID, new CardLookupResult.Missing(CARD_ID));
            run.clock.advance(HANDOFF_COMPLETION_WINDOW);

            // when
            run.deadlineTasks.getFirst().run();
            waitUntil(() -> run.orchestrator.snapshot().counts().running() == 0 && !Files.exists(run.workspace.get()));

            // then
            assertThat(run.orchestrator.snapshot().counts().retrying()).isZero();
            verify(run.workspaces).removeForIdentifierIfPresent(eq(CARD_IDENTIFIER), any());
            verify(run.runner).run(any());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void pendingCleanupUsesMatchingTrackerCredentialsAndOriginalWorkspace(boolean equivalentTarget) throws Exception {
        // given
        try (var run = new HandoffRun(false, false, "Done", DEFAULT_TURN_TIMEOUT_MS)) {
            run.tracker.resolveConfiguredBoardId = true;
            run.start();
            run.awaitFinalStarted();
            run.expireThroughTrackerOutage("failed");
            run.orchestrator.stopWorkflowWatcherForTests();
            Path workflow = tempDir.resolve("WORKFLOW.md");
            long modified = Files.getLastModifiedTime(workflow).toMillis();
            String selectedBoard = equivalentTarget ? "board-1" : "board-2";
            Files.writeString(
                    workflow,
                    Files.readString(workflow)
                            .replace("kind: trello", "kind: trello\n  endpoint: https://api.trello.com/1/")
                            .replace("api_token: token", "api_token: rotated-token")
                            .replace("board_id: board-1", "board_id: " + selectedBoard)
                            .replace("root: work", "root: replacement-work"));
            Files.setLastModifiedTime(workflow, FileTime.fromMillis(modified + 2000));
            // The polling thread records lookup configs while the test observes their completion.
            List<EffectiveConfig> lookups = new CopyOnWriteArrayList<>();
            doAnswer(invocation -> {
                        lookups.add(invocation.getArgument(0));
                        return Map.of(CARD_ID, new CardLookupResult.Found(destination("Done", "open")));
                    })
                    .when(run.tracker)
                    .fetchCardStatesByIds(any(), any());

            // when
            run.refresh();

            // then
            assertThat(run.orchestrator.selectedBoardId()).isEqualTo(selectedBoard);
            assertThat(lookups).isNotEmpty().allSatisfy(lookup -> {
                assertThat(lookup.tracker().resolvedBoardId()).isEqualTo("board-1");
                assertThat(lookup.tracker().apiToken()).isEqualTo(equivalentTarget ? "rotated-token" : "token");
            });
            assertThat(run.workspace.get()).doesNotExist();
            ArgumentCaptor<EffectiveConfig> cleanupConfig = ArgumentCaptor.forClass(EffectiveConfig.class);
            verify(run.workspaces).removeForIdentifierIfPresent(eq(CARD_IDENTIFIER), cleanupConfig.capture());
            assertThat(cleanupConfig.getValue().workspace().root())
                    .isEqualTo(run.workspace.get().getParent());
            verify(run.runner).run(any());
        }
    }

    private final class HandoffRun implements AutoCloseable {
        final Path releaseFinal = tempDir.resolve("release-final");
        final Path finalStarted = tempDir.resolve("final-started");
        final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        final FakeTracker tracker =
                spy(new FakeTracker(List.of(TestCards.card(CARD_ID, CARD_IDENTIFIER, ACTIVE_STATE))));
        final CountDownLatch moved = new CountDownLatch(1);
        final CountDownLatch releaseMove;
        final AtomicReference<AgentRunResult> result = new AtomicReference<>();
        final AtomicReference<AgentEventListener> listener = new AtomicReference<>();
        final AtomicReference<Path> workspace = new AtomicReference<>();
        final AtomicInteger continuationCalls = new AtomicInteger();
        final AtomicInteger completedTicks = new AtomicInteger();
        final ScheduledExecutorService deadlineScheduler = mock();
        // Worker registrations append deadlines while the test thread observes and fires them.
        final List<Runnable> deadlineTasks = new CopyOnWriteArrayList<>();
        final WorkspaceManager workspaces = spy(new WorkspaceManager(new HookRunner()));
        final AgentRunner runner = mock();
        final TrelloClient trello = mock();
        boolean afterTurnMove;
        boolean unconfirmedShutdown;
        final SymphonyOrchestrator orchestrator;

        HandoffRun(boolean pauseMove, boolean failMove, String target, int timeoutMillis) throws Exception {
            this(pauseMove, failMove, target, timeoutMillis, "");
        }

        HandoffRun(boolean pauseMove, boolean failMove, String target, int timeoutMillis, String extraCodexConfig)
                throws Exception {
            releaseMove = new CountDownLatch(pauseMove ? 1 : 0);
            Path appServer = appServer(releaseFinal, finalStarted, target);
            Path workflow = workflow(appServer, target, timeoutMillis, extraCodexConfig);
            when(trello.fetchBoardLists(any()))
                    .thenReturn(List.of(new TrelloClient.BoardList("list-review", target, false)));
            when(trello.moveCardToList(any(), eq(CARD_ID), eq("list-review"))).thenAnswer(invocation -> {
                tracker.setCardState(destination(target, "open"));
                moved.countDown();
                assertThat(releaseMove.await(10, TimeUnit.SECONDS))
                        .as("the move response must be released after the forced reconciliation")
                        .isTrue();
                if (failMove) {
                    throw new IllegalStateException("cannot move current card: expected a successful test response");
                }
                return Map.of("id", CARD_ID, "idList", "list-review");
            });
            var client = new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, trello), clock);
            when(runner.run(any())).thenAnswer(invocation -> {
                AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                workspace.set(request.config().workspace().root().resolve(CARD_IDENTIFIER));
                Files.createDirectories(workspace.get());
                listener.set(spy(request.listener()));
                if (afterTurnMove) {
                    doAnswer(callback -> {
                                waitUntil(() -> Files.exists(finalStarted));
                                return null;
                            })
                            .when(listener.get())
                            .onSuccessfulTurn(any(), any());
                }
                AgentRunResult completed = client.runSession(
                        request.config(),
                        request.card(),
                        workspace.get(),
                        request.prompt(),
                        request.workerIdentity(),
                        listener.get(),
                        turns -> {
                            continuationCalls.incrementAndGet();
                            if (afterTurnMove) {
                                return CodexAppServerClient.TurnDecision.stop();
                            }
                            return CodexAppServerClient.TurnDecision.continueWith("This turn must not start.");
                        });
                if (unconfirmedShutdown) {
                    completed = AgentRunResult.shutdownIncomplete("cannot release test workspace: exit is unconfirmed");
                }
                result.set(completed);
                return completed;
            });
            when(deadlineScheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenAnswer(invocation -> {
                        deadlineTasks.add(invocation.getArgument(0));
                        return mock(ScheduledFuture.class);
                    });
            orchestrator = new SymphonyOrchestrator(
                    new WorkflowLoader(),
                    new ConfigResolver(),
                    tracker,
                    runner,
                    new PromptRenderer(),
                    workspaces,
                    clock,
                    Optional.of(successfulWorkpadHandler()),
                    NO_JITTER,
                    Optional.of(deadlineScheduler));
            orchestrator.workflowPath = workflow;
            orchestrator.tickCompletionHookForTests = completedTicks::incrementAndGet;
        }

        void replaceWorker() throws Exception {
            AgentEventListener previous = listener.get();
            tracker.setCardState(destination(REVIEW_LIST, "other-list"));
            refresh();
            waitUntil(() -> result.get() != null);
            result.set(null);
            Files.delete(finalStarted);
            tracker.setCardState(TestCards.card(CARD_ID, CARD_IDENTIFIER, ACTIVE_STATE));
            refresh();
            waitUntil(() -> !previous.equals(listener.get()) && Files.exists(finalStarted));
        }

        void expireThroughTrackerOutage(String failure) throws Exception {
            var exitFinished = new CountDownLatch(1);
            orchestrator.workerExitCompletionHookForTests = exitFinished::countDown;
            tracker.setCandidateList(List.of());
            switch (failure) {
                case "failed" -> {
                    tracker.cardStates = Map.of(
                            CARD_ID,
                            new CardLookupResult.Failed(
                                    CARD_ID, "test_unavailable", "Tracker is unavailable in this test."));
                }
                case "absent" -> {
                    tracker.cardStates = Map.of();
                }
                case "exception" -> {
                    tracker.stateFetchHook = () -> {
                        throw new IllegalStateException("cannot refresh test tracker: expected successful recovery");
                    };
                }
                default ->
                    throw new IllegalArgumentException("cannot configure tracker outage: expected a known test mode");
            }
            clock.advance(HANDOFF_COMPLETION_WINDOW);
            deadlineTasks.getFirst().run();
            assertThat(exitFinished.await(5, TimeUnit.SECONDS))
                    .as("the interrupted worker finishes its failed cleanup lookup before recovery")
                    .isTrue();
            waitUntil(() -> result.get() != null);
            clock.advance(STALL_TIMEOUT_ADVANCE);
            refresh();
        }

        void start() throws Exception {
            orchestrator.start();
            boolean moveObserved = moved.await(8, TimeUnit.SECONDS);
            assertThat(moveObserved)
                    .as("the owned move must reach Trello; worker result: %s", result.get())
                    .isTrue();
        }

        void awaitFinalStarted() throws Exception {
            waitUntil(() -> Files.exists(finalStarted));
        }

        void refresh() throws Exception {
            int beforeRefresh = completedTicks.get();
            orchestrator.requestRefresh();
            waitUntil(() -> completedTicks.get() > beforeRefresh);
        }

        void finish() throws Exception {
            awaitFinalStarted();
            Files.writeString(releaseFinal, "finish");
            waitUntil(() ->
                    result.get() != null && orchestrator.snapshot().counts().running() == 0);
        }

        CardHandoffMove move() {
            ArgumentCaptor<CardHandoffMove> moves = ArgumentCaptor.forClass(CardHandoffMove.class);
            verify(listener.get(), atLeastOnce()).beforeHandoffMove(moves.capture());
            return moves.getAllValues().getFirst();
        }

        void assertCompletedFinal() {
            assertThat(result.get()).isEqualTo(AgentRunResult.ok());
            assertThat(continuationCalls).hasValue(0);
            ArgumentCaptor<AgentEvent> events = ArgumentCaptor.forClass(AgentEvent.class);
            verify(listener.get(), atLeastOnce()).onEvent(events.capture());
            assertThat(events.getAllValues())
                    .filteredOn(event -> "item/completed".equals(event.event()))
                    .singleElement()
                    .satisfies(
                            event -> assertThat(event.payload().at("/item/text").asText())
                                    .isEqualTo("Complete handoff. Copy this evidence before merge approval."));
            verify(listener.get()).onSuccessfulTurn("thread-handoff", "turn-handoff");
        }

        @Override
        public void close() throws Exception {
            releaseMove.countDown();
            Files.writeString(releaseFinal, "finish");
            orchestrator.stop();
        }
    }

    private Card destination(String state, String change) {
        return new Card(
                CARD_ID,
                CARD_IDENTIFIER,
                "Handoff",
                "Description",
                null,
                state,
                "list",
                "other-list".equals(change) ? "list-other" : "list-review",
                state,
                "unknown-list-open".equals(change) ? null : "list-archived".equals(change),
                "other-board".equals(change) ? "board-other" : "board-1",
                "unknown-board-open".equals(change) ? null : "board-archived".equals(change),
                "archived".equals(change),
                1,
                "SYNTH101",
                "https://trello.com/c/SYNTH101",
                null,
                "https://trello.com/c/SYNTH101",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                false,
                null);
    }

    private Path workflow(Path appServer, String target, int timeoutMillis, String extraCodexConfig) throws Exception {
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow, """
                ---
                tracker:
                  kind: trello
                  api_key: key
                  api_token: token
                  board_id: board-1
                  active_states: [%s]
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                trello_tools:
                  enabled: true
                  allow_writes: true
                  allow_comments: false
                  allowed_move_list_names: [%s]
                codex:
                  command: "bash %s"
                  read_timeout_ms: 5000
                  turn_timeout_ms: %d
                %s---
                Finish the handoff and its final response.
                """.formatted(ACTIVE_STATE, target, appServer, timeoutMillis, extraCodexConfig.indent(2)));
        return workflow;
    }

    private Path appServer(Path releaseFinal, Path finalStarted, String target) throws Exception {
        Path script = tempDir.resolve("handoff-app-server.sh");
        Files.writeString(script, """
                #!/usr/bin/env bash
                set -eu
                while IFS= read -r line; do
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo '{"id":1,"result":{"userAgent":"handoff-test"}}' ;;
                    *\\"method\\":\\"thread/start\\"*) echo '{"id":2,"result":{"thread":{"id":"thread-handoff"}}}' ;;
                    *\\"method\\":\\"turn/start\\"*)
                      echo '{"id":3,"result":{"turn":{"id":"turn-handoff"}}}'
                      echo '{"method":"turn/started","params":{"threadId":"thread-handoff","turn":{"id":"turn-handoff"}}}'
                      echo '{"id":100,"method":"item/tool/call","params":{"threadId":"thread-handoff","turnId":"turn-handoff","tool":"trello_move_current_card","arguments":{"list_name":"%s"}}}'
                      ;;
                    *\\"id\\":100*)
                      echo '{"method":"item/started","params":{"threadId":"thread-handoff","turnId":"turn-handoff","item":{"id":"final-handoff","type":"agentMessage","phase":"final_answer","text":"Handoff:"}}}'
                      touch '__FINAL_STARTED__'
                      for attempt in {1..1000}; do
                        if test -e '__RELEASE_FINAL__'; then break; fi
                        sleep 0.01
                      done
                      test -e '__RELEASE_FINAL__'
                      echo '{"method":"item/completed","params":{"threadId":"thread-handoff","turnId":"turn-handoff","item":{"id":"final-handoff","type":"agentMessage","phase":"final_answer","text":"Complete handoff. Copy this evidence before merge approval."}}}'
                      echo '{"method":"turn/completed","params":{"threadId":"thread-handoff","turn":{"id":"turn-handoff","status":"completed","error":null}}}'
                      ;;
                  esac
                done
                """.formatted(target)
                .replace("__FINAL_STARTED__", finalStarted.toString())
                .replace("__RELEASE_FINAL__", releaseFinal.toString()));
        return script;
    }
}
