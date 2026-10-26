package ch.fmartin.symphony.trello.orchestrator;

import static ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestratorTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.agent.AgentEvent;
import ch.fmartin.symphony.trello.agent.AgentRunResult;
import ch.fmartin.symphony.trello.agent.AgentRunner;
import ch.fmartin.symphony.trello.agent.CardHandoffMove;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SymphonyOrchestratorHandoffCleanupTest {
    @TempDir
    Path tempDir;

    @Test
    void backlogPausesNewDispatchUntilRecoveryWithoutCappingConfiguredConcurrency() throws Exception {
        // given
        List<Card> original = cards("pending", 129);
        var tracker = new FakeTracker(original);
        var clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        var registered = new CountDownLatch(original.size());
        var exits = new CountDownLatch(original.size());
        var unrelatedStarts = new AtomicInteger();
        var tickCount = new AtomicInteger();
        // Worker registrations append deadlines while the test thread observes and fires them.
        List<Runnable> deadlines = new CopyOnWriteArrayList<>();
        try (ScheduledExecutorService deadlineScheduler = mock()) {
            when(deadlineScheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                    .thenAnswer(invocation -> {
                        deadlines.add(invocation.getArgument(0));
                        return mock(ScheduledFuture.class);
                    });
            AgentRunner runner = mock();
            when(runner.run(any())).thenAnswer(invocation -> {
                AgentRunner.AgentRunRequest request = invocation.getArgument(0);
                if (request.card().id().startsWith("pending-")) {
                    String turn = "turn-" + request.card().id();
                    request.listener()
                            .onEvent(new AgentEvent(
                                    "session_started",
                                    clock.instant(),
                                    request.workerIdentity(),
                                    null,
                                    "thread",
                                    turn,
                                    null,
                                    Map.of(),
                                    null));
                    var move = new CardHandoffMove(
                            request.card().id(),
                            request.workerIdentity(),
                            "thread",
                            turn,
                            request.card().id(),
                            "board-1",
                            "list-done");
                    assertThat(request.listener().beforeHandoffMove(move))
                            .as("each dispatched worker owns its pending handoff")
                            .isTrue();
                    request.listener().afterHandoffMove(move, true);
                    registered.countDown();
                } else {
                    unrelatedStarts.incrementAndGet();
                }
                try {
                    new CountDownLatch(1).await(30, TimeUnit.SECONDS);
                    return AgentRunResult.fail("cannot complete test worker: expected cancellation");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return AgentRunResult.fail("cannot continue test worker: expected an uninterrupted attempt");
                }
            });
            Path workflow = tempDir.resolve("WORKFLOW.md");
            writeWorkflow(workflow, "60000", "agent:\n  max_concurrent_agents: 129");
            WorkspaceManager workspaces = mock();
            var orchestrator = new SymphonyOrchestrator(
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
            orchestrator.tickCompletionHookForTests = tickCount::incrementAndGet;
            orchestrator.workerExitCompletionHookForTests = exits::countDown;
            try {
                orchestrator.start();
                assertThat(registered.await(10, TimeUnit.SECONDS))
                        .as("all in-flight workers register before expiry")
                        .isTrue();
                Map<String, CardLookupResult> failed = new HashMap<>();
                for (Card card : original) {
                    failed.put(
                            card.id(),
                            new CardLookupResult.Failed(card.id(), "unavailable", "Tracker is unavailable."));
                }
                tracker.cardStates = failed;
                tracker.setCandidateList(List.of());
                clock.advance(Duration.ofSeconds(60));
                for (Runnable deadline : deadlines) {
                    deadline.run();
                }
                assertThat(exits.await(10, TimeUnit.SECONDS))
                        .as("all cancelled workers exit before new dispatch")
                        .isTrue();
                waitUntil(() -> orchestrator.snapshot().counts().running() == 0);
                List<Card> unrelated = cards("unrelated", 129);
                for (Card card : unrelated) {
                    tracker.setCardState(card);
                }
                List<Card> candidates = new ArrayList<>(unrelated);
                candidates.add(original.get(2));
                tracker.setPromptCardState(original.get(2));
                tracker.setCandidateList(candidates);

                // when
                refresh(orchestrator, tickCount);

                // then
                assertThat(unrelatedStarts)
                        .as("129 pending cleanups pause new dispatch")
                        .hasValue(0);
                tracker.setCardState(TestCards.card("pending-0", "PENDING-0", "Todo"));
                refresh(orchestrator, tickCount);
                assertThat(unrelatedStarts)
                        .as("the threshold of 128 still pauses new dispatch")
                        .hasValue(0);
                tracker.setCardState(TestCards.card("pending-1", "PENDING-1", "Done"));
                refresh(orchestrator, tickCount);
                waitUntil(() -> unrelatedStarts.get() == 129);
                assertThat(orchestrator.snapshot().counts().running())
                        .as("127 pending cleanups leave the configured 129 worker slots available")
                        .isEqualTo(129);
                assertThat(orchestrator.snapshot().counts().retrying()).isZero();
                verify(runner, times(258)).run(any());
                verify(runner, times(129)).cancel(any());
                verify(workspaces, never()).removeForIdentifierIfPresent(eq("PENDING-0"), any());
                verify(workspaces).removeForIdentifierIfPresent(eq("PENDING-1"), any());
            } finally {
                orchestrator.stop();
            }
        }
    }

    private static List<Card> cards(String prefix, int count) {
        List<Card> cards = new ArrayList<>();
        String identifierPrefix = prefix.toUpperCase(Locale.ROOT);
        for (int index = 0; index < count; index++) {
            cards.add(TestCards.card(prefix + "-" + index, identifierPrefix + "-" + index, "Todo"));
        }
        return List.copyOf(cards);
    }

    private static void refresh(SymphonyOrchestrator orchestrator, AtomicInteger tickCount) throws Exception {
        int before = tickCount.get();
        orchestrator.requestRefresh();
        waitUntil(() -> tickCount.get() > before);
    }
}
