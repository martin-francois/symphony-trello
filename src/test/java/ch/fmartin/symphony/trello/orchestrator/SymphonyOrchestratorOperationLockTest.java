package ch.fmartin.symphony.trello.orchestrator;

import static ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestratorTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.agent.AgentRunResult;
import ch.fmartin.symphony.trello.agent.AgentRunner;
import ch.fmartin.symphony.trello.domain.Card;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Owns the operation-lock acquisitions in stop() and the retry timer callback. The mutation record
/// in ADR 0102 lists the other acquisitions and their owning tests.
final class SymphonyOrchestratorOperationLockTest {
    @TempDir
    Path tempDir;

    @Test
    void stopWaitsForAnInFlightTickAndCancelsTheWorkerItDispatched() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        var tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        BlockingRun blockingRun = blockRunnerUntilCancelled(runner);
        var tickPausedBeforeDispatch = new CountDownLatch(1);
        var resumeTick = new CountDownLatch(1);
        tracker.stateFetchHook = () -> {
            tickPausedBeforeDispatch.countDown();
            awaitRelease(resumeTick, "the tick paused before its dispatch");
        };
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);
        orchestrator.start();
        assertThat(tickPausedBeforeDispatch.await(5, TimeUnit.SECONDS))
                .as("the first tick should reach its pre-dispatch card refresh within 5 seconds")
                .isTrue();

        // when
        CompletableFuture<Integer> preparesWhenStopReturned = CompletableFuture.supplyAsync(() -> {
            orchestrator.stop();
            return tracker.prepareForDispatchCalls.get();
        });
        waitForBoundedQuietPeriod(preparesWhenStopReturned::isDone);
        resumeTick.countDown();

        // then
        assertThat(preparesWhenStopReturned)
                .as("stop must let the in-flight tick finish its dispatch before tearing down")
                .succeedsWithin(Duration.ofSeconds(5))
                .isEqualTo(1);
        assertThat(blockingRun.cancelled())
                .as("stop must cancel the worker that the in-flight tick dispatched")
                .hasValue(1);
    }

    @Test
    void retryDispatchRacingAPollingTickRespectsTheConcurrencyLimit() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000", "agent:\n  max_concurrent_agents: 1");
        Card retried = cardWithPriorityAndBlockers("card-retry", "TRELLO-retry", 3, List.of());
        Card waiting = cardWithPriorityAndBlockers("card-new", "TRELLO-new", 1, List.of());
        var tracker = new FakeTracker(List.of(retried));
        AgentRunner runner = mock();
        var firstRun = new AtomicBoolean(true);
        doAnswer(invocation -> {
                    if (firstRun.getAndSet(false)) {
                        return AgentRunResult.fail("first attempt failed");
                    }
                    blockUntilInterruptedOrTimedOut(Duration.ofSeconds(30));
                    return AgentRunResult.fail("interrupted");
                })
                .when(runner)
                .run(any());
        var tickCompletions = new AtomicInteger();
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);
        orchestrator.tickCompletionHookForTests = tickCompletions::incrementAndGet;
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1
                && orchestrator.snapshot().counts().running() == 0);
        tracker.setCandidates(List.of(retried, waiting));

        Thread retrier = Thread.ofPlatform().unstarted(() -> orchestrator.retryNowForTests("card-retry"));
        var retrierFetches = new AtomicInteger();
        var retryPausedBeforeDispatch = new CountDownLatch(1);
        var resumeRetry = new CountDownLatch(1);
        // The retry's second fetch is the prompt refresh: its claim is already released and its
        // slot budget already computed, which is the widest window a concurrent tick could use.
        tracker.stateFetchHook = () -> {
            if (Thread.currentThread().equals(retrier) && retrierFetches.incrementAndGet() == 2) {
                retryPausedBeforeDispatch.countDown();
                awaitRelease(resumeRetry, "the retry paused before its dispatch");
            }
        };
        retrier.start();
        assertThat(retryPausedBeforeDispatch.await(5, TimeUnit.SECONDS))
                .as("the retry should reach its pre-dispatch prompt refresh within 5 seconds")
                .isTrue();
        int ticksBeforeRefresh = tickCompletions.get();

        // when
        orchestrator.requestRefresh();
        waitForBoundedQuietPeriod(() -> tickCompletions.get() > ticksBeforeRefresh);
        resumeRetry.countDown();
        waitUntil(() -> !retrier.isAlive() && tickCompletions.get() > ticksBeforeRefresh);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.running())
                .as("a retry dispatch racing a polling tick must respect max_concurrent_agents: 1")
                .singleElement()
                .extracting(RuntimeSnapshot.RunningRow::cardIdentifier)
                .isEqualTo("TRELLO-retry");
    }
}
