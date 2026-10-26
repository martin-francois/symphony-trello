package ch.fmartin.symphony.trello.orchestrator;

import static ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestratorTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.agent.AgentRunResult;
import ch.fmartin.symphony.trello.agent.AgentRunner;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.process.RuntimeProcessShutdown;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class SymphonyOrchestratorRemovalTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void startupSkipsBlankIdentifiersBeforeOtherCleanup(String identifier) throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        var valid = TestCards.card("valid", "TRELLO-valid", "Done");
        Files.createDirectories(tempDir.resolve("work").resolve(valid.identifier()));
        var tracker = spy(new FakeTracker(List.of()));
        when(tracker.fetchTerminalCards(any()))
                .thenReturn(List.of(TestCards.card("invalid", identifier, "Done"), valid));
        var hooks = mock(HookRunner.class);
        var orchestrator = orchestrator(workflow, tracker, mock(AgentRunner.class), new WorkspaceManager(hooks));
        try {

            // when
            orchestrator.start();

            // then
            verify(hooks).runBestEffort(any(), any(), any(), any());
            assertThat(tempDir.resolve("work").resolve(valid.identifier())).doesNotExist();
        } finally {
            orchestrator.stop();
        }
    }

    @Test
    void startupStopsStartingRemovalHooksAtTheRetentionThreshold() throws Exception {
        // given
        int threshold = 128;
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000", "hooks:\n  before_remove: true\n");
        var terminal = new ArrayList<Card>();
        for (int index = 0; index <= threshold; index++) {
            var card = TestCards.card("terminal-" + index, "TRELLO-" + index, "Done");
            terminal.add(card);
            Files.createDirectories(tempDir.resolve("work").resolve(card.identifier()));
        }
        var tracker = spy(new FakeTracker(List.of()));
        when(tracker.fetchTerminalCards(any())).thenReturn(terminal);
        var hooks = mock(HookRunner.class);
        var removals = new AtomicInteger();
        doAnswer(invocation -> {
                    removals.incrementAndGet();
                    throw new RuntimeProcessShutdown.UnconfirmedExitException();
                })
                .when(hooks)
                .runBestEffort(any(), any(), any(), any());
        var orchestrator = orchestrator(workflow, tracker, mock(AgentRunner.class), new WorkspaceManager(hooks));
        try {

            // when
            orchestrator.start();

            // then
            assertThat(removals).hasValue(threshold);
            assertThat(tempDir.resolve("work/TRELLO-" + threshold)).exists();
        } finally {
            orchestrator.stop();
        }
    }

    @CsvSource({"startup,false", "startup,true", "terminal,false", "terminal,true", "missing,false", "missing,true"})
    @ParameterizedTest
    void unconfirmedRemovalHookRetainsOwnershipWithoutBlockingOtherCards(String route, boolean reloadTarget)
            throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000", "hooks:\n  before_remove: true\n");
        var card = TestCards.card("card-1", "TRELLO-retained", "Todo");
        var terminal = TestCards.card(card.id(), card.identifier(), "Done");
        boolean startup = "startup".equals(route);
        var tracker = spy(new FakeTracker(startup ? List.of() : List.of(card)));
        tracker.resolveConfiguredBoardId = true;
        when(tracker.fetchTerminalCards(any())).thenReturn(startup ? List.of(terminal) : List.of());
        Path sentinel = tempDir.resolve("work/TRELLO-retained/sentinel");
        Files.createDirectories(sentinel.getParent());
        Files.writeString(sentinel, "owned by an unconfirmed removal hook");
        var hooks = mock(HookRunner.class);
        var hookInvoked = new CountDownLatch(1);
        doAnswer(invocation -> {
                    if ("before_remove".equals(invocation.getArgument(0))) {
                        hookInvoked.countDown();
                        throw new RuntimeProcessShutdown.UnconfirmedExitException();
                    }
                    return null;
                })
                .when(hooks)
                .runBestEffort(any(), any(), any(), any());
        var releaseUnexpectedWorker = new CountDownLatch(1);
        List<String> launched = new CopyOnWriteArrayList<>();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            launched.add(request.card().id());
            if ("other".equals(request.card().id())) {
                tracker.setCardState(cardOnBoard(
                        "other", "TRELLO-other", "Human Review", request.card().boardId()));
                return AgentRunResult.ok();
            }
            if (!startup && launched.size() == 1) {
                return AgentRunResult.fail("controlled failure before retry cleanup");
            }
            releaseUnexpectedWorker.await(10, TimeUnit.SECONDS);
            return AgentRunResult.fail("unexpected dispatch into retained workspace");
        });
        var orchestrator = orchestrator(workflow, tracker, runner, new WorkspaceManager(hooks));
        try {
            orchestrator.start();
            Throwable cleanupFailure = null;
            if (!startup) {
                waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
                tracker.setCandidateList(List.of());
                tracker.cardStates = Map.of(
                        card.id(),
                        "missing".equals(route)
                                ? new CardLookupResult.Missing(card.id())
                                : new CardLookupResult.Found(terminal));
                cleanupFailure = catchThrowable(() -> orchestrator.retryNowForTests(card.id()));
            }
            assertThat(hookInvoked.await(5, TimeUnit.SECONDS))
                    .as("the selected cleanup route invokes before_remove")
                    .isTrue();

            // when
            String board = reloadTarget ? "board-2" : "board-1";
            var reactivated = reloadTarget ? cardOnBoard("replacement", card.identifier(), "Todo", board) : card;
            tracker.setCandidates(List.of(reactivated));
            if (reloadTarget) {
                rewriteWorkflowWithTarget(workflow, "https://api.trello.com/1", board, "token", "fake", "work");
            }
            orchestrator.tickNowForTests();

            // then
            assertThat(sentinel).exists();
            assertThat(orchestrator.snapshot().counts().running()).isZero();
            assertThat(orchestrator.snapshot().counts().retrying()).isZero();
            assertThat(cleanupFailure).isNull();
            var other = cardOnBoard("other", "TRELLO-other", "Todo", board);
            tracker.setCandidates(List.of(reactivated, other));
            orchestrator.tickNowForTests();
            waitUntil(() -> launched.contains(other.id()));
            assertThat(launched)
                    .containsExactlyElementsOf(startup ? List.of(other.id()) : List.of(card.id(), other.id()));
        } finally {
            releaseUnexpectedWorker.countDown();
            orchestrator.stop();
        }
    }
}
