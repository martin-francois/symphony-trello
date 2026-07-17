package ch.fmartin.symphony.trello.orchestrator;

import static ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestratorTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.agent.AgentEvent;
import ch.fmartin.symphony.trello.agent.AgentRunResult;
import ch.fmartin.symphony.trello.agent.AgentRunner;
import ch.fmartin.symphony.trello.agent.CodexUsageWorkpadSection;
import ch.fmartin.symphony.trello.agent.TrelloHandoffToolHandler;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.BlockerRef;
import ch.fmartin.symphony.trello.domain.Card;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class SymphonyOrchestratorUsageLimitPauseTest {
    @TempDir
    Path tempDir;

    @Test
    void typedUsageLimitInstallsFallbackPauseBeforeTrelloIoAndGatesManualDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeRetryableInProgressWorkflow(workflow);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card first = TestCards.card("card-1", "TRELLO-first", "Todo");
        Card second = TestCards.card("card-2", "TRELLO-second", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(first, second));
        tracker.preparedCard = TestCards.card("card-1", "TRELLO-first", "In Progress");
        AtomicBoolean pauseVisibleBeforeTrelloIo = new AtomicBoolean();
        AtomicReference<SymphonyOrchestrator> orchestratorRef = new AtomicReference<>();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            tracker.stateFetchHook = () -> pauseVisibleBeforeTrelloIo.compareAndSet(
                    false, orchestratorRef.get().snapshot().dispatchPause() != null);
            return AgentRunResult.codexUsageLimit("turn_failed: Usage is unavailable.", Optional.empty());
        });
        TrelloHandoffToolHandler workpads = successfulWorkpadHandler();
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads);
        orchestratorRef.set(orchestrator);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        RuntimeSnapshot paused = orchestrator.snapshot();
        int candidateFetchesBeforeManualTick = tracker.candidateFetches.get();
        orchestrator.tickNowForTests();
        RuntimeSnapshot afterManualTick = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(pauseVisibleBeforeTrelloIo).isTrue();
        assertThat(paused.dispatchPause())
                .isEqualTo(new RuntimeSnapshot.DispatchPause("CODEX_USAGE_LIMIT", now, now.plusSeconds(60)));
        assertThat(paused.retrying()).singleElement().satisfies(retry -> {
            assertThat(retry.cardIdentifier()).isEqualTo("TRELLO-first");
            assertThat(retry.attempt()).isEqualTo(1);
            assertThat(retry.dueAt()).isEqualTo(now.plusSeconds(60));
        });
        assertThat(tracker.releasedCards).containsExactly("TRELLO-first");
        assertThat(tracker.candidateFetches).hasValue(candidateFetchesBeforeManualTick);
        assertThat(afterManualTick.counts().running()).isZero();
        assertThat(tracker.prepareForDispatchCalls).hasValue(1);
    }

    @Test
    void schemaValidFarFutureUsageResetSchedulesWithoutMillisecondOverflow() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        Instant farFutureReset = Instant.ofEpochSecond(Instant.MAX.getEpochSecond() - 1);
        MutableClock clock = new MutableClock(now);
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        when(runner.run(any()))
                .thenReturn(AgentRunResult.codexUsageLimit(
                        "turn_failed: Usage is unavailable.", Optional.of(farFutureReset)));
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        RuntimeSnapshot paused = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(paused.dispatchPause())
                .isEqualTo(new RuntimeSnapshot.DispatchPause("CODEX_USAGE_LIMIT", now, farFutureReset));
        assertThat(paused.retrying()).singleElement().satisfies(retry -> assertThat(retry.dueAt())
                .isEqualTo(farFutureReset));
    }

    @Test
    void activeUsagePauseDefersExistingRetryWithoutAttemptOrTrelloIo() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                """
                agent:
                  max_retry_backoff_ms: 60000
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card genericCard = TestCards.card("card-generic", "TRELLO-generic", "Todo");
        Card usageCard = TestCards.card("card-usage", "TRELLO-usage", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(genericCard));
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            return request.card().id().equals("card-usage")
                    ? AgentRunResult.codexUsageLimit("turn_failed: Usage is unavailable.", Optional.empty())
                    : AgentRunResult.fail("generic failure");
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        waitUntil(() -> containsRetryingCard(orchestrator.snapshot(), "TRELLO-generic"));
        tracker.setCandidates(List.of(genericCard, usageCard));
        orchestrator.tickNowForTests();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && orchestrator.snapshot().counts().retrying() == 2);
        int stateFetchesBeforeDeferredTimer = tracker.stateFetches.get();
        orchestrator.retryNowForTests("card-generic");
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(tracker.stateFetches).hasValue(stateFetchesBeforeDeferredTimer);
        assertThat(snapshot.retrying())
                .filteredOn(retry -> retry.cardIdentifier().equals("TRELLO-generic"))
                .singleElement()
                .satisfies(retry -> {
                    assertThat(retry.attempt()).isEqualTo(1);
                    assertThat(retry.dueAt()).isEqualTo(now.plusSeconds(60));
                });
    }

    @Test
    void usageDeadlineDoesNotRunGenericRetryBeforeItsOwnLaterBackoff() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                """
                agent:
                  max_concurrent_agents: 2
                  max_retry_backoff_ms: 60000
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card genericCard = TestCards.card("card-generic", "TRELLO-generic", "Todo");
        Card usageCard = TestCards.card("card-usage", "TRELLO-usage", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(genericCard, usageCard));
        CountDownLatch workersStarted = new CountDownLatch(2);
        CountDownLatch releaseGeneric = new CountDownLatch(1);
        CountDownLatch releaseUsage = new CountDownLatch(1);
        AtomicInteger genericRuns = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            workersStarted.countDown();
            if (request.card().id().equals("card-generic")) {
                genericRuns.incrementAndGet();
                assertThat(releaseGeneric.await(5, TimeUnit.SECONDS)).isTrue();
                return AgentRunResult.fail("generic failure");
            }
            assertThat(releaseUsage.await(5, TimeUnit.SECONDS)).isTrue();
            tracker.setCardState(TestCards.card("card-usage", "TRELLO-usage", "Human Review"));
            return AgentRunResult.codexUsageLimit(
                    "turn_failed: Usage is unavailable.", Optional.of(now.plusSeconds(5)));
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        assertThat(workersStarted.await(5, TimeUnit.SECONDS)).isTrue();
        releaseGeneric.countDown();
        waitUntil(() -> containsRetryingCard(orchestrator.snapshot(), "TRELLO-generic")
                && orchestrator.snapshot().dispatchPause() == null);
        releaseUsage.countDown();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && orchestrator.snapshot().retrying().size() == 1);
        tracker.setCandidateList(List.of(genericCard));
        int prepareCallsBeforeDeadline = tracker.prepareForDispatchCalls.get();
        clock.advance(Duration.ofSeconds(5));
        orchestrator.dispatchPauseDeadlineNowForTests(now.plusSeconds(5));
        RuntimeSnapshot afterEarlyDeadline = orchestrator.snapshot();
        int prepareCallsAfterDeadline = tracker.prepareForDispatchCalls.get();
        orchestrator.stop();

        // then
        assertThat(genericRuns).hasValue(1);
        assertThat(prepareCallsAfterDeadline).isEqualTo(prepareCallsBeforeDeadline);
        assertThat(afterEarlyDeadline.dispatchPause())
                .isEqualTo(new RuntimeSnapshot.DispatchPause("CODEX_USAGE_LIMIT", now, now.plusSeconds(10)));
        assertThat(afterEarlyDeadline.retrying()).singleElement().satisfies(retry -> {
            assertThat(retry.cardIdentifier()).isEqualTo("TRELLO-generic");
            assertThat(retry.attempt()).isEqualTo(1);
            assertThat(retry.dueAt()).isEqualTo(now.plusSeconds(10));
        });
    }

    @Test
    void usageDeadlineRechecksOnceExtendsOnRepeatAndClearsOnSuccess() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                """
                agent:
                  max_retry_backoff_ms: 60000
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card card = TestCards.card("card-1", "TRELLO-abc", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(card));
        AtomicInteger runs = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            int run = runs.incrementAndGet();
            if (run < 3) {
                return AgentRunResult.codexUsageLimit("turn_failed: Usage is unavailable.", Optional.empty());
            }
            tracker.setCardState(TestCards.card("card-1", "TRELLO-abc", "Human Review"));
            return AgentRunResult.ok();
        });
        List<String> sections = new CopyOnWriteArrayList<>();
        AtomicInteger removals = new AtomicInteger();
        TrelloHandoffToolHandler workpads = mock();
        doAnswer(invocation -> {
                    String section = invocation.getArgument(2);
                    if (section == null) {
                        removals.incrementAndGet();
                    } else {
                        sections.add(section);
                    }
                    return true;
                })
                .when(workpads)
                .updateCodexUsageSection(any(), anyString(), nullable(String.class));
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().retrying().stream().anyMatch(retry -> retry.attempt() == 1));
        clock.advance(Duration.ofSeconds(60));
        orchestrator.retryNowForTests("card-1");
        waitUntil(() -> orchestrator.snapshot().retrying().stream().anyMatch(retry -> retry.attempt() == 2));
        RuntimeSnapshot repeated = orchestrator.snapshot();
        clock.advance(Duration.ofSeconds(60));
        orchestrator.retryNowForTests("card-1");
        waitUntil(() -> runs.get() == 3 && orchestrator.snapshot().dispatchPause() == null);
        RuntimeSnapshot recovered = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(repeated.dispatchPause())
                .isEqualTo(new RuntimeSnapshot.DispatchPause("CODEX_USAGE_LIMIT", now, now.plusSeconds(120)));
        assertThat(repeated.retrying()).singleElement().satisfies(retry -> assertThat(retry.attempt())
                .isEqualTo(2));
        assertThat(sections)
                .anySatisfy(section -> assertThat(section).contains("Rechecking Codex usage availability."))
                .anySatisfy(section -> assertThat(section).contains("2026-07-10T12:02:00Z"));
        assertThat(recovered.dispatchPause()).isNull();
        assertThat(recovered.counts().retrying()).isZero();
        assertThat(removals).hasValue(1);
    }

    @Test
    void failedUsageProbeLaunchRearmsUntilAProbeProducesAResult() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeRetryableInProgressWorkflow(workflow);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card card = TestCards.card("card-1", "TRELLO-abc", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(card));
        tracker.preparedCard = TestCards.card("card-1", "TRELLO-abc", "In Progress");
        AtomicInteger runs = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            if (runs.incrementAndGet() == 1) {
                return AgentRunResult.codexUsageLimit("turn_failed: Usage is unavailable.", Optional.empty());
            }
            tracker.setCardState(TestCards.card("card-1", "TRELLO-abc", "Human Review"));
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        tracker.prepareForDispatchFailure = new IllegalStateException("pickup refresh failed");
        clock.advance(Duration.ofSeconds(60));
        orchestrator.retryNowForTests("card-1");
        RuntimeSnapshot relaunched = orchestrator.snapshot();
        tracker.prepareForDispatchFailure = null;
        clock.advance(Duration.ofSeconds(60));
        orchestrator.retryNowForTests("card-1");
        waitUntil(() -> runs.get() == 2 && orchestrator.snapshot().dispatchPause() == null);
        orchestrator.stop();

        // then
        assertThat(relaunched.dispatchPause())
                .isEqualTo(new RuntimeSnapshot.DispatchPause("CODEX_USAGE_LIMIT", now, now.plusSeconds(120)));
        assertThat(relaunched.retrying()).singleElement().satisfies(retry -> {
            assertThat(retry.attempt()).isEqualTo(2);
            assertThat(retry.dueAt()).isEqualTo(now.plusSeconds(120));
        });
        assertThat(runs).hasValue(2);
    }

    @Test
    void reconciledUsageProbeRearmsAndItsLateTypedResultIsIgnored() throws Exception {
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
                {{ card.state }}
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card card = TestCards.cardWithLabels("card-1", "TRELLO-abc", "Todo", List.of("ready for codex"));
        FakeTracker tracker = new FakeTracker(List.of(card));
        tracker.preparedCard =
                TestCards.cardWithLabels("card-1", "TRELLO-abc", "In Progress", List.of("ready for codex"));
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch staleExitHandled = new CountDownLatch(2);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            int run = runs.incrementAndGet();
            if (run == 1) {
                return AgentRunResult.codexUsageLimit("turn_failed: Usage is unavailable.", Optional.empty());
            }
            if (run == 2) {
                probeStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return AgentRunResult.codexUsageLimit(
                        "turn_failed: stale usage result", Optional.of(now.plusSeconds(600)));
            }
            tracker.setCardState(TestCards.card("card-1", "TRELLO-abc", "Human Review"));
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());
        orchestrator.workerExitCompletionHookForTests = staleExitHandled::countDown;

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        tracker.setCardState(card);
        clock.advance(Duration.ofSeconds(60));
        orchestrator.retryNowForTests("card-1");
        assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue();
        tracker.setCardState(TestCards.cardWithLabels("card-1", "TRELLO-abc", "In Progress", List.of()));
        orchestrator.tickNowForTests();
        assertThat(staleExitHandled.await(5, TimeUnit.SECONDS)).isTrue();
        RuntimeSnapshot rearmed = orchestrator.snapshot();
        tracker.setCardState(card);
        clock.advance(Duration.ofSeconds(60));
        orchestrator.retryNowForTests("card-1");
        waitUntil(() -> runs.get() == 3 && orchestrator.snapshot().dispatchPause() == null);
        orchestrator.stop();

        // then
        assertThat(rearmed.dispatchPause())
                .isEqualTo(new RuntimeSnapshot.DispatchPause("CODEX_USAGE_LIMIT", now, now.plusSeconds(120)));
        assertThat(rearmed.retrying()).singleElement().satisfies(retry -> {
            assertThat(retry.attempt()).isEqualTo(2);
            assertThat(retry.dueAt()).isEqualTo(now.plusSeconds(120));
        });
        assertThat(runs).hasValue(3);
    }

    @Test
    void permanentlyReconciledUsageProbeTransfersImmediatelyAndIgnoresItsLateTypedResult() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                """
                agent:
                  max_retry_backoff_ms: 60000
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card first = TestCards.card("card-1", "TRELLO-first", "Todo");
        Card second = TestCards.card("card-2", "TRELLO-second", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(first, second));
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch staleResultReturned = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            int run = runs.incrementAndGet();
            if (run == 1) {
                return AgentRunResult.codexUsageLimit("turn_failed: Usage is unavailable.", Optional.empty());
            }
            if (run == 2) {
                probeStarted.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                staleResultReturned.countDown();
                return AgentRunResult.codexUsageLimit(
                        "turn_failed: stale usage result", Optional.of(now.plusSeconds(600)));
            }
            tracker.setCardState(
                    TestCards.card(request.card().id(), request.card().identifier(), "Human Review"));
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        clock.advance(Duration.ofSeconds(60));
        orchestrator.retryNowForTests("card-1");
        assertThat(probeStarted.await(5, TimeUnit.SECONDS)).isTrue();
        tracker.setCardState(TestCards.card("card-1", "TRELLO-first", "Human Review"));
        tracker.setCandidateList(List.of(second));
        orchestrator.tickNowForTests();
        assertThat(staleResultReturned.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> runs.get() == 3 && orchestrator.snapshot().dispatchPause() == null);
        RuntimeSnapshot recovered = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(recovered.counts().running()).isZero();
        assertThat(recovered.counts().retrying()).isZero();
        assertThat(runs).hasValue(3);
    }

    @Test
    void earlyUsageDeadlineCallbackRearmsItsTimer() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeRetryableInProgressWorkflow(workflow);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        when(runner.run(any()))
                .thenReturn(AgentRunResult.codexUsageLimit("turn_failed: Usage is unavailable.", Optional.empty()));
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());
        AtomicInteger scheduledDeadlines = new AtomicInteger();
        orchestrator.dispatchPauseScheduleHookForTests = scheduledDeadlines::incrementAndGet;

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null);
        RuntimeSnapshot.DispatchPause pause = orchestrator.snapshot().dispatchPause();
        int schedulesBeforeEarlyCallback = scheduledDeadlines.get();
        orchestrator.dispatchPauseDeadlineNowForTests(pause.until());
        RuntimeSnapshot afterEarlyCallback = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(afterEarlyCallback.dispatchPause()).isEqualTo(pause);
        assertThat(scheduledDeadlines).hasValue(schedulesBeforeEarlyCallback + 1);
    }

    @Test
    void genericRetryCreatedByAnAlreadyRunningWorkerIsClampedToActivePause() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                """
                agent:
                  max_concurrent_agents: 2
                  max_retry_backoff_ms: 60000
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        FakeTracker tracker = new FakeTracker(List.of(
                TestCards.card("card-usage", "TRELLO-usage", "Todo"),
                TestCards.card("card-generic", "TRELLO-generic", "Todo")));
        CountDownLatch workersStarted = new CountDownLatch(2);
        CountDownLatch releaseUsage = new CountDownLatch(1);
        CountDownLatch releaseGeneric = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            workersStarted.countDown();
            if (request.card().id().equals("card-usage")) {
                assertThat(releaseUsage.await(5, TimeUnit.SECONDS)).isTrue();
                return AgentRunResult.codexUsageLimit(
                        "turn_failed: Usage is unavailable.", Optional.of(now.plusSeconds(120)));
            }
            assertThat(releaseGeneric.await(5, TimeUnit.SECONDS)).isTrue();
            return AgentRunResult.fail("generic failure");
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        assertThat(workersStarted.await(5, TimeUnit.SECONDS)).isTrue();
        releaseUsage.countDown();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null);
        releaseGeneric.countDown();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 2);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.retrying())
                .filteredOn(retry -> retry.cardIdentifier().equals("TRELLO-generic"))
                .singleElement()
                .satisfies(retry -> assertThat(retry.dueAt()).isEqualTo(now.plusSeconds(120)));
    }

    @Test
    void typedWorkerExitQueuedBehindStopCannotInstallAPause() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch exitHandled = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            workerStarted.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentRunResult.codexUsageLimit("turn_failed: late usage result", Optional.empty());
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);
        orchestrator.workerExitCompletionHookForTests = exitHandled::countDown;

        // when
        orchestrator.start();
        assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.stop();
        assertThat(exitHandled.await(5, TimeUnit.SECONDS)).isTrue();
        RuntimeSnapshot snapshot = orchestrator.snapshot();

        // then
        assertThat(snapshot.dispatchPause()).isNull();
        assertThat(snapshot.counts().running()).isZero();
        assertThat(snapshot.counts().retrying()).isZero();
    }

    @Test
    void exceptionalWorkerExitWaitsForAnInFlightOrchestratorOperation() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch tickFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseTickFetch = new CountDownLatch(1);
        CountDownLatch concurrentWorkerExitFetch = new CountDownLatch(1);
        AtomicInteger hookedFetches = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            workerStarted.countDown();
            assertThat(releaseWorker.await(5, TimeUnit.SECONDS)).isTrue();
            throw new IllegalStateException("agent process exited unexpectedly");
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        tracker.stateFetchHook = () -> {
            if (hookedFetches.incrementAndGet() == 1) {
                tickFetchStarted.countDown();
                try {
                    assertThat(releaseTickFetch.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            } else {
                concurrentWorkerExitFetch.countDown();
            }
        };
        orchestrator.requestRefresh();
        assertThat(tickFetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
        releaseWorker.countDown();

        // then
        try {
            assertThat(concurrentWorkerExitFetch.await(500, TimeUnit.MILLISECONDS))
                    .as("worker-exit tracker I/O must wait for the active orchestrator operation")
                    .isFalse();
        } finally {
            releaseTickFetch.countDown();
        }
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        orchestrator.stop();
    }

    @Test
    void usageDeadlineWaitsForAnInFlightOrchestratorOperation() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                """
                agent:
                  max_concurrent_agents: 2
                  max_retry_backoff_ms: 60000
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card runningCard = TestCards.card("card-running", "TRELLO-running", "Todo");
        Card limiter = TestCards.card("card-limiter", "TRELLO-limiter", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(runningCard, limiter));
        CountDownLatch runningWorkerStarted = new CountDownLatch(1);
        CountDownLatch releaseRunningWorker = new CountDownLatch(1);
        CountDownLatch tickFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseTickFetch = new CountDownLatch(1);
        CountDownLatch concurrentDeadlineFetch = new CountDownLatch(1);
        AtomicInteger hookedFetches = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            if (request.card().id().equals("card-running")) {
                runningWorkerStarted.countDown();
                assertThat(releaseRunningWorker.await(5, TimeUnit.SECONDS)).isTrue();
                return AgentRunResult.ok();
            }
            return AgentRunResult.codexUsageLimit(
                    "turn_failed: Usage is unavailable.", Optional.of(now.plusSeconds(60)));
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        assertThat(runningWorkerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && containsRetryingCard(orchestrator.snapshot(), "TRELLO-limiter"));
        tracker.stateFetchHook = () -> {
            if (hookedFetches.incrementAndGet() == 1) {
                tickFetchStarted.countDown();
                try {
                    assertThat(releaseTickFetch.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            } else {
                concurrentDeadlineFetch.countDown();
            }
        };
        orchestrator.requestRefresh();
        assertThat(tickFetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
        clock.advance(Duration.ofSeconds(60));
        CompletableFuture<Void> deadline =
                CompletableFuture.runAsync(() -> orchestrator.dispatchPauseDeadlineNowForTests(now.plusSeconds(60)));

        // then
        try {
            assertThat(concurrentDeadlineFetch.await(500, TimeUnit.MILLISECONDS))
                    .as("usage-deadline tracker I/O must wait for the active orchestrator operation")
                    .isFalse();
        } finally {
            tracker.setCardState(TestCards.card("card-limiter", "TRELLO-limiter", "Human Review"));
            releaseTickFetch.countDown();
        }
        deadline.get(5, TimeUnit.SECONDS);
        releaseRunningWorker.countDown();
        orchestrator.stop();
    }

    @Test
    void retryCallbackAfterStopCannotPerformTrackerIoOrReschedule() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        when(runner.run(any())).thenReturn(AgentRunResult.fail("generic failure"));
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        orchestrator.stop();
        int stateFetchesAfterStop = tracker.stateFetches.get();
        orchestrator.retryNowForTests("card-1");
        RuntimeSnapshot snapshot = orchestrator.snapshot();

        // then
        assertThat(tracker.stateFetches).hasValue(stateFetchesAfterStop);
        assertThat(snapshot.counts().running()).isZero();
        assertThat(snapshot.counts().retrying()).isZero();
    }

    @Test
    void deadlineWithoutUsageRetryRunsOneControlledCandidateProbeBeforeClearing() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                """
                agent:
                  max_retry_backoff_ms: 60000
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card first = TestCards.card("card-1", "TRELLO-first", "Todo");
        Card second = TestCards.card("card-2", "TRELLO-second", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(first, second));
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch candidateProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseCandidateProbe = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            if (runs.incrementAndGet() == 1) {
                tracker.setCardState(TestCards.card("card-1", "TRELLO-first", "Human Review"));
                return AgentRunResult.codexUsageLimit("turn_failed: Usage is unavailable.", Optional.empty());
            }
            candidateProbeStarted.countDown();
            assertThat(releaseCandidateProbe.await(5, TimeUnit.SECONDS)).isTrue();
            tracker.setCardState(
                    TestCards.card(request.card().id(), request.card().identifier(), "Human Review"));
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && orchestrator.snapshot().counts().retrying() == 0);
        tracker.setCandidateList(List.of(second));
        clock.advance(Duration.ofSeconds(60));
        orchestrator.dispatchPauseDeadlineNowForTests(now.plusSeconds(60));
        assertThat(candidateProbeStarted.await(5, TimeUnit.SECONDS)).isTrue();
        RuntimeSnapshot probing = orchestrator.snapshot();
        releaseCandidateProbe.countDown();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() == null);
        orchestrator.stop();

        // then
        assertThat(probing.dispatchPause()).isNotNull();
        assertThat(probing.running()).singleElement().satisfies(row -> assertThat(row.cardIdentifier())
                .isEqualTo("TRELLO-second"));
        assertThat(runs).hasValue(2);
    }

    @Test
    void permanentlyIneligibleUsageRetryTransfersProbeToNextCandidate() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                """
                agent:
                  max_retry_backoff_ms: 60000
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card first = TestCards.card("card-1", "TRELLO-first", "Todo");
        Card second = TestCards.card("card-2", "TRELLO-second", "Todo");
        UsageProbeScenario scenario = usageProbeScenario(workflow, clock, first, second);

        // when
        scenario.orchestrator().start();
        waitUntil(() -> scenario.orchestrator().snapshot().counts().retrying() == 1);
        scenario.tracker().setCardState(TestCards.card("card-1", "TRELLO-first", "Human Review"));
        scenario.tracker().setCandidateList(List.of(second));
        clock.advance(Duration.ofSeconds(60));
        scenario.orchestrator().retryNowForTests("card-1");
        RuntimeSnapshot recovered = awaitUsageProbeRecovery(scenario);

        // then
        assertThat(recovered.counts().running()).isZero();
        assertThat(recovered.counts().retrying()).isZero();
        assertThat(scenario.runs()).hasValue(2);
    }

    @CsvSource({"removed required label, labels", "added active blocker, blocker"})
    @ParameterizedTest(name = "{0}")
    void newlyIneligibleUsageRetryTransfersProbeWithoutLosingOrdinaryRetry(String ignoredName, String constraint)
            throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String trackerConstraint =
                "labels".equals(constraint) ? "required_labels: [Ready for Codex]" : "blocker_enforced_states: [Todo]";
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
                  %s
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
                """
                        .formatted(trackerConstraint));
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card first = "labels".equals(constraint)
                ? TestCards.cardWithLabels("card-1", "TRELLO-first", "Todo", List.of("Ready for Codex"))
                : TestCards.card("card-1", "TRELLO-first", "Todo");
        Card second = "labels".equals(constraint)
                ? TestCards.cardWithLabels("card-2", "TRELLO-second", "Todo", List.of("Ready for Codex"))
                : TestCards.card("card-2", "TRELLO-second", "Todo");
        Card newlyIneligible = "labels".equals(constraint)
                ? TestCards.cardWithLabels("card-1", "TRELLO-first", "Todo", List.of())
                : cardWithPriorityAndBlockers(
                        "card-1",
                        "TRELLO-first",
                        null,
                        List.of(new BlockerRef("card-blocker", "TRELLO-blocker", "Todo", null)));
        UsageProbeScenario scenario = usageProbeScenario(workflow, clock, first, second);

        // when
        scenario.orchestrator().start();
        waitUntil(() -> scenario.orchestrator().snapshot().counts().retrying() == 1);
        scenario.tracker().setCardState(newlyIneligible);
        scenario.tracker().setCandidateList(List.of(second));
        clock.advance(Duration.ofSeconds(60));
        scenario.orchestrator().retryNowForTests("card-1");
        waitUntil(() ->
                scenario.runs().get() == 2 && scenario.orchestrator().snapshot().dispatchPause() == null);
        RuntimeSnapshot recovered = scenario.orchestrator().snapshot();
        scenario.orchestrator().stop();

        // then
        assertThat(recovered.retrying()).singleElement().satisfies(retry -> {
            assertThat(retry.cardIdentifier()).isEqualTo("TRELLO-first");
            assertThat(retry.attempt()).isEqualTo(2);
        });
        assertThat(scenario.runs()).hasValue(2);
    }

    @Test
    void deeplyBlockedUsageRetryTransfersProbeWhenShallowRefreshStillLooksEligible() throws Exception {
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
                agent:
                  max_retry_backoff_ms: 60000
                codex:
                  command: fake
                ---
                {{ card.title }}
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card first = TestCards.card("card-1", "TRELLO-first", "Todo");
        Card second = TestCards.card("card-2", "TRELLO-second", "Todo");
        Card deeplyBlockedFirst = cardWithPriorityAndBlockers(
                "card-1",
                "TRELLO-first",
                null,
                List.of(new BlockerRef("card-blocker", "TRELLO-blocker", "Todo", null)));
        UsageProbeScenario scenario = usageProbeScenario(workflow, clock, first, second);

        // when
        scenario.orchestrator().start();
        waitUntil(() -> scenario.orchestrator().snapshot().counts().retrying() == 1);
        assertThat(scenario.tracker().cardState("card-1").blockedBy()).isEmpty();
        scenario.tracker().setPromptCardState(deeplyBlockedFirst);
        scenario.tracker().setCandidateList(List.of(second));
        clock.advance(Duration.ofSeconds(60));
        scenario.orchestrator().retryNowForTests("card-1");
        scenario.orchestrator().dispatchPauseDeadlineNowForTests(now.plusSeconds(60));
        RuntimeSnapshot recovered = awaitUsageProbeRecovery(scenario);

        // then
        assertThat(recovered.retrying()).singleElement().satisfies(retry -> {
            assertThat(retry.cardIdentifier()).isEqualTo("TRELLO-first");
            assertThat(retry.attempt()).isEqualTo(2);
        });
        assertThat(scenario.runs()).hasValue(2);
    }

    @Test
    void deadlineWithoutAnyEligibleProbeRearmsWithSafetyFloor() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                """
                agent:
                  max_retry_backoff_ms: 0
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card card = TestCards.card("card-1", "TRELLO-first", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(card));
        AtomicInteger runs = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            runs.incrementAndGet();
            tracker.setCardState(TestCards.card("card-1", "TRELLO-first", "Human Review"));
            return AgentRunResult.codexUsageLimit("turn_failed: Usage is unavailable.", Optional.empty());
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null);
        RuntimeSnapshot.DispatchPause initial = orchestrator.snapshot().dispatchPause();
        tracker.setCandidateList(List.of());
        clock.advance(Duration.ofSeconds(1));
        orchestrator.dispatchPauseDeadlineNowForTests(initial.until());
        RuntimeSnapshot rearmed = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(initial.until()).isEqualTo(now.plusSeconds(1));
        assertThat(rearmed.dispatchPause())
                .isEqualTo(new RuntimeSnapshot.DispatchPause("CODEX_USAGE_LIMIT", now, now.plusSeconds(2)));
        assertThat(runs).hasValue(1);
    }

    @Test
    void staleManagedUsageWorkpadIsRetriedAndRemovedBeforeDispatchAfterRestart() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        String staleSection =
                CodexUsageWorkpadSection.paused("Old process usage limit.", Instant.parse("2026-07-10T11:00:00Z"));
        Card card = TestCards.cardWithComments(
                "card-1",
                "TRELLO-abc",
                "Todo",
                List.of(new Card.Comment(
                        "action-workpad",
                        "## Codex Workpad\n\n" + staleSection,
                        "Codex",
                        Instant.parse("2026-07-10T10:00:00Z"))));
        FakeTracker tracker = new FakeTracker(List.of(card));
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch secondWorkerStarted = new CountDownLatch(1);
        CountDownLatch releaseSecondWorker = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            if (runs.incrementAndGet() == 1) {
                return AgentRunResult.ok();
            }
            secondWorkerStarted.countDown();
            assertThat(releaseSecondWorker.await(5, TimeUnit.SECONDS)).isTrue();
            tracker.setCardState(TestCards.card("card-1", "TRELLO-abc", "Human Review"));
            return AgentRunResult.ok();
        });
        AtomicInteger cleanupAttempts = new AtomicInteger();
        TrelloHandoffToolHandler workpads = mock();
        when(workpads.updateCodexUsageSection(any(), anyString(), nullable(String.class)))
                .thenAnswer(invocation -> {
                    String section = invocation.getArgument(2);
                    return section != null || cleanupAttempts.incrementAndGet() > 1;
                });
        MutableClock clock = new MutableClock(Instant.parse("2026-07-10T12:00:00Z"));
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads);

        // when
        orchestrator.start();
        waitUntil(() ->
                cleanupAttempts.get() == 1 && orchestrator.snapshot().counts().retrying() == 1);
        clock.advance(Duration.ofSeconds(1));
        orchestrator.retryNowForTests("card-1");
        assertThat(secondWorkerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.tickNowForTests();
        assertThat(cleanupAttempts).hasValue(1);
        clock.advance(Duration.ofSeconds(59));
        orchestrator.tickNowForTests();
        waitUntil(() -> cleanupAttempts.get() == 2);
        releaseSecondWorker.countDown();
        waitUntil(() -> orchestrator.snapshot().counts().running() == 0);
        orchestrator.stop();

        // then
        assertThat(cleanupAttempts).hasValue(2);
    }

    @Test
    void laterBoundedUsageFailureNeverShortensPauseOrCancelsAlreadyRunningWorker() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                """
                agent:
                  max_concurrent_agents: 2
                  max_retry_backoff_ms: 60000
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        FakeTracker tracker = new FakeTracker(List.of(
                TestCards.card("card-long", "TRELLO-long", "Todo"),
                TestCards.card("card-short", "TRELLO-short", "Todo")));
        CountDownLatch workersStarted = new CountDownLatch(2);
        CountDownLatch releaseLong = new CountDownLatch(1);
        CountDownLatch releaseShort = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            workersStarted.countDown();
            if (request.card().id().equals("card-long")) {
                assertThat(releaseLong.await(5, TimeUnit.SECONDS)).isTrue();
                return AgentRunResult.codexUsageLimit("turn_failed: Long pause.", Optional.of(now.plusSeconds(120)));
            }
            assertThat(releaseShort.await(5, TimeUnit.SECONDS)).isTrue();
            return AgentRunResult.codexUsageLimit("turn_failed: Short pause.", Optional.of(now.plusSeconds(60)));
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        assertThat(workersStarted.await(5, TimeUnit.SECONDS)).isTrue();
        releaseLong.countDown();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null);
        verify(runner, never()).cancel(anyString());
        releaseShort.countDown();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 2);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        verify(runner, never()).cancel(anyString());
        orchestrator.stop();

        // then
        assertThat(snapshot.dispatchPause())
                .isEqualTo(new RuntimeSnapshot.DispatchPause("CODEX_USAGE_LIMIT", now, now.plusSeconds(120)));
        assertThat(snapshot.retrying())
                .extracting(RuntimeSnapshot.RetryRow::dueAt)
                .containsOnly(now.plusSeconds(120));
    }

    @Test
    void supersededRetryCallbackCannotConsumeReplacementEntry() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                """
                agent:
                  max_concurrent_agents: 2
                  max_retry_backoff_ms: 60000
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card retryCard = TestCards.card("card-retry", "TRELLO-retry", "Todo");
        Card limiter = TestCards.card("card-limiter", "TRELLO-limiter", "Todo");
        Card probe = TestCards.card("card-probe", "TRELLO-probe", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(retryCard, limiter, probe));
        tracker.setCandidateList(List.of(retryCard, limiter));
        AtomicInteger retryCardRuns = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            if (request.card().id().equals("card-retry")) {
                retryCardRuns.incrementAndGet();
                return AgentRunResult.fail("temporary failure");
            }
            tracker.setCardState(
                    TestCards.card(request.card().id(), request.card().identifier(), "Human Review"));
            return request.card().id().equals("card-limiter")
                    ? AgentRunResult.codexUsageLimit(
                            "turn_failed: Usage is unavailable.", Optional.of(now.plusSeconds(5)))
                    : AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());
        CountDownLatch staleCallbackWaiting = new CountDownLatch(1);
        AtomicBoolean launchStaleCallback = new AtomicBoolean(true);
        AtomicReference<CompletableFuture<Void>> staleCallback = new AtomicReference<>();

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && containsRetryingCard(orchestrator.snapshot(), "TRELLO-retry"));
        tracker.setCandidateList(List.of(probe));
        orchestrator.retryTimerWaitingHookForTests = staleCallbackWaiting::countDown;
        orchestrator.retryEntryRescheduleHookForTests = (cardId, generation) -> {
            if (!launchStaleCallback.compareAndSet(true, false)) {
                return;
            }
            staleCallback.set(CompletableFuture.runAsync(() -> orchestrator.retryNowForTests(cardId, generation)));
            try {
                assertThat(staleCallbackWaiting.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        };
        clock.advance(Duration.ofSeconds(5));
        orchestrator.dispatchPauseDeadlineNowForTests(now.plusSeconds(5));
        waitUntil(() -> staleCallback.get() != null);
        staleCallback.get().get(5, TimeUnit.SECONDS);
        RuntimeSnapshot afterStaleCallback = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(afterStaleCallback.retrying()).singleElement().satisfies(retry -> {
            assertThat(retry.cardIdentifier()).isEqualTo("TRELLO-retry");
            assertThat(retry.attempt()).isEqualTo(1);
            assertThat(retry.dueAt()).isEqualTo(now.plusSeconds(10));
        });
        assertThat(retryCardRuns).hasValue(1);
    }

    @Test
    void lateConcurrentUsageResultPreservesTheBoundProbeThatCanClearTheExtendedPause() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(
                workflow,
                "60000",
                """
                agent:
                  max_concurrent_agents: 2
                  max_retry_backoff_ms: 60000
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        FakeTracker tracker = new FakeTracker(List.of(
                TestCards.card("card-probe", "TRELLO-probe", "Todo"),
                TestCards.card("card-late", "TRELLO-late", "Todo")));
        CountDownLatch initialWorkersStarted = new CountDownLatch(2);
        CountDownLatch releaseInitialProbeCard = new CountDownLatch(1);
        CountDownLatch releaseLateWorker = new CountDownLatch(1);
        CountDownLatch availabilityProbeStarted = new CountDownLatch(1);
        CountDownLatch releaseAvailabilityProbe = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        AtomicInteger probeCardRuns = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            runs.incrementAndGet();
            if (request.card().id().equals("card-late")) {
                initialWorkersStarted.countDown();
                assertThat(releaseLateWorker.await(5, TimeUnit.SECONDS)).isTrue();
                return AgentRunResult.codexUsageLimit(
                        "turn_failed: Later concurrent usage result.", Optional.of(now.plusSeconds(120)));
            }
            if (probeCardRuns.incrementAndGet() == 1) {
                initialWorkersStarted.countDown();
                assertThat(releaseInitialProbeCard.await(5, TimeUnit.SECONDS)).isTrue();
                return AgentRunResult.codexUsageLimit(
                        "turn_failed: Initial usage result.", Optional.of(now.plusSeconds(60)));
            }
            availabilityProbeStarted.countDown();
            assertThat(releaseAvailabilityProbe.await(5, TimeUnit.SECONDS)).isTrue();
            tracker.setCardState(TestCards.card("card-probe", "TRELLO-probe", "Human Review"));
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        assertThat(initialWorkersStarted.await(5, TimeUnit.SECONDS)).isTrue();
        releaseInitialProbeCard.countDown();
        waitUntil(() -> containsRetryingCard(orchestrator.snapshot(), "TRELLO-probe"));
        clock.advance(Duration.ofSeconds(60));
        orchestrator.retryNowForTests("card-probe");
        assertThat(availabilityProbeStarted.await(5, TimeUnit.SECONDS)).isTrue();
        releaseLateWorker.countDown();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && orchestrator.snapshot().dispatchPause().until().equals(now.plusSeconds(120))
                && containsRetryingCard(orchestrator.snapshot(), "TRELLO-late"));
        releaseAvailabilityProbe.countDown();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() == null);
        RuntimeSnapshot recovered = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(recovered.retrying()).singleElement().satisfies(retry -> {
            assertThat(retry.cardIdentifier()).isEqualTo("TRELLO-late");
            assertThat(retry.dueAt()).isEqualTo(now.plusSeconds(120));
        });
        assertThat(runs).hasValue(3);
    }

    @Test
    void canonicalRateLimitEventsSurviveLaterAbsentPayloadsInSnapshot() throws Exception {
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
                    request.listener()
                            .onEvent(new AgentEvent(
                                    "account/rateLimits/updated",
                                    COMMENT_TIME.plusSeconds(1),
                                    request.workerIdentity(),
                                    123L,
                                    "thread-1",
                                    "turn-1",
                                    "malformed rate limits",
                                    Map.of(),
                                    new ObjectMapper().getNodeFactory().nullNode()));
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
    void rateLimitListenerAcknowledgesOnlyEventsFromACurrentWorker() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = mock();
        AtomicReference<AgentRunner.AgentRunRequest> request = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        doAnswer(invocation -> {
                    request.set(invocation.getArgument(0));
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
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        boolean currentAccepted = request.get()
                .listener()
                .onEventAndReportAccepted(rateLimitEvent(
                        request.get(),
                        COMMENT_TIME,
                        new ObjectMapper().createObjectNode().put("account", "current")));
        RuntimeSnapshot whileRunning = orchestrator.snapshot();
        orchestrator.stop();
        boolean staleAccepted = request.get()
                .listener()
                .onEventAndReportAccepted(rateLimitEvent(
                        request.get(),
                        COMMENT_TIME.plusSeconds(1),
                        new ObjectMapper().createObjectNode().put("account", "stale")));
        RuntimeSnapshot afterStop = orchestrator.snapshot();

        // then
        assertThat(currentAccepted).isTrue();
        assertThat(staleAccepted).isFalse();
        assertThat(whileRunning.rateLimits().toString()).contains("current").doesNotContain("stale");
        assertThat(afterStop.rateLimits().toString()).contains("current").doesNotContain("stale");
    }

    @Test
    void validCodexCommandReloadClearsOldPauseRetriesWithNewCommandAndCleansWithCurrentConfig() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithCommand(
                workflow,
                "command-a",
                """
                agent:
                  max_retry_backoff_ms: 60000
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card card = TestCards.card("card-1", "TRELLO-retry", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(card));
        List<AgentRunner.AgentRunRequest> requests = new CopyOnWriteArrayList<>();
        CountDownLatch commandBStarted = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            requests.add(request);
            if (request.config().codex().command().equals("command-a")) {
                return AgentRunResult.codexUsageLimit(
                        "turn_failed: Command A is exhausted.", Optional.of(now.plusSeconds(60)));
            }
            commandBStarted.countDown();
            tracker.setCardState(TestCards.card("card-1", "TRELLO-retry", "Human Review"));
            return AgentRunResult.ok();
        });
        List<String> cleanupCommands = new CopyOnWriteArrayList<>();
        TrelloHandoffToolHandler workpads = mock();
        when(workpads.updateCodexUsageSection(any(), anyString(), nullable(String.class)))
                .thenAnswer(invocation -> {
                    EffectiveConfig updateConfig = invocation.getArgument(0);
                    String section = invocation.getArgument(2);
                    if (section == null) {
                        cleanupCommands.add(updateConfig.codex().command());
                    }
                    return true;
                });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && containsRetryingCard(orchestrator.snapshot(), "TRELLO-retry"));
        rewriteWorkflowWithCommand(
                workflow,
                "command-b",
                """
                agent:
                  max_concurrent_agents: 2
                """);
        orchestrator.tickNowForTests();
        RuntimeSnapshot afterReload = orchestrator.snapshot();
        assertThat(commandBStarted.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.stop();

        // then
        assertThat(afterReload.dispatchPause()).isNull();
        assertThat(requests)
                .extracting(request -> request.config().codex().command())
                .containsExactly("command-a", "command-b");
        assertThat(requests.get(1).attempt()).isEqualTo(1);
        assertThat(cleanupCommands).contains("command-b").doesNotContain("command-a");
    }

    @Test
    void commandReloadRestoresGenericRetryNaturalDeadlineWhileRearmingUsageRetry() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithCommand(
                workflow,
                "command-a",
                """
                agent:
                  max_concurrent_agents: 2
                  max_retry_backoff_ms: 60000
                """);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card generic = TestCards.card("card-generic", "TRELLO-generic", "Todo");
        Card usage = TestCards.card("card-usage", "TRELLO-usage", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(generic, usage));
        CountDownLatch firstWorkersStarted = new CountDownLatch(2);
        CountDownLatch releaseGeneric = new CountDownLatch(1);
        CountDownLatch releaseUsage = new CountDownLatch(1);
        CountDownLatch commandBUsageStarted = new CountDownLatch(1);
        AtomicInteger genericRuns = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            if (request.config().codex().command().equals("command-b")) {
                if (request.card().id().equals("card-usage")) {
                    commandBUsageStarted.countDown();
                    tracker.setCardState(TestCards.card("card-usage", "TRELLO-usage", "Human Review"));
                }
                return AgentRunResult.ok();
            }
            firstWorkersStarted.countDown();
            if (request.card().id().equals("card-generic")) {
                genericRuns.incrementAndGet();
                assertThat(releaseGeneric.await(5, TimeUnit.SECONDS)).isTrue();
                return AgentRunResult.fail("generic failure");
            }
            assertThat(releaseUsage.await(5, TimeUnit.SECONDS)).isTrue();
            return AgentRunResult.codexUsageLimit(
                    "turn_failed: Command A is exhausted.", Optional.of(now.plusSeconds(60)));
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        assertThat(firstWorkersStarted.await(5, TimeUnit.SECONDS)).isTrue();
        releaseGeneric.countDown();
        waitUntil(() -> containsRetryingCard(orchestrator.snapshot(), "TRELLO-generic")
                && orchestrator.snapshot().dispatchPause() == null);
        releaseUsage.countDown();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && orchestrator.snapshot().retrying().size() == 2);
        rewriteWorkflowWithCommand(
                workflow,
                "command-b",
                """
                agent:
                  max_concurrent_agents: 2
                """);
        orchestrator.tickNowForTests();
        assertThat(commandBUsageStarted.await(5, TimeUnit.SECONDS)).isTrue();
        RuntimeSnapshot afterReload = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(afterReload.dispatchPause()).isNull();
        assertThat(afterReload.retrying())
                .filteredOn(retry -> retry.cardIdentifier().equals("TRELLO-generic"))
                .singleElement()
                .satisfies(retry -> assertThat(retry.dueAt()).isEqualTo(now.plusSeconds(10)));
        assertThat(genericRuns).hasValue(1);
    }

    @Test
    void currentCommandMalformedFirstRateLimitSnapshotDoesNotFallBackToPreviousCommand() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        ConcurrentCommandScenario scenario = concurrentCommandScenario(workflow);

        // when
        scenario.orchestrator().start();
        waitUntil(() -> scenario.requests().containsKey("command-a"));
        scenario.requests()
                .get("command-a")
                .listener()
                .onEvent(rateLimitEvent(
                        scenario.requests().get("command-a"),
                        COMMENT_TIME,
                        new ObjectMapper().createObjectNode().put("account", "A")));
        AgentRunner.AgentRunRequest commandBRequest = dispatchCommandB(workflow, scenario);
        commandBRequest
                .listener()
                .onEvent(rateLimitEvent(commandBRequest, COMMENT_TIME.plusSeconds(1), new ObjectMapper().nullNode()));
        RuntimeSnapshot snapshot = scenario.orchestrator().snapshot();
        scenario.releaseWorkers().countDown();
        scenario.orchestrator().stop();

        // then
        assertThat(snapshot.rateLimits()).isNull();
    }

    @Test
    void latePreviousCommandRateLimitEventCannotOverwriteCurrentCommandSnapshot() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        ConcurrentCommandScenario scenario = concurrentCommandScenario(workflow);

        // when
        scenario.orchestrator().start();
        waitUntil(() -> scenario.requests().containsKey("command-a"));
        AgentRunner.AgentRunRequest commandBRequest = dispatchCommandB(workflow, scenario);
        commandBRequest
                .listener()
                .onEvent(rateLimitEvent(
                        commandBRequest,
                        COMMENT_TIME,
                        new ObjectMapper().createObjectNode().put("account", "B")));
        scenario.requests()
                .get("command-a")
                .listener()
                .onEvent(rateLimitEvent(
                        scenario.requests().get("command-a"),
                        COMMENT_TIME.plusSeconds(1),
                        new ObjectMapper().createObjectNode().put("account", "A")));
        RuntimeSnapshot snapshot = scenario.orchestrator().snapshot();
        scenario.releaseWorkers().countDown();
        scenario.orchestrator().stop();

        // then
        assertThat(snapshot.rateLimits().toString())
                .contains("\"account\":\"B\"")
                .doesNotContain("A");
    }

    private static AgentRunner.AgentRunRequest dispatchCommandB(Path workflow, ConcurrentCommandScenario scenario)
            throws Exception {
        scenario.tracker().setCandidates(List.of(scenario.first(), scenario.second()));
        rewriteWorkflowWithCommand(
                workflow,
                "command-b",
                """
                agent:
                  max_concurrent_agents: 2
                """);
        scenario.orchestrator().tickNowForTests();
        assertThat(scenario.workersStarted().await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> scenario.requests().containsKey("command-b"));
        return scenario.requests().get("command-b");
    }

    @Test
    void latePreviousCommandUsageResultCannotPauseCurrentCommand() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithCommand(workflow, "command-a", "");
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card card = TestCards.card("card-1", "TRELLO-late", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(card));
        CountDownLatch commandAStarted = new CountDownLatch(1);
        CountDownLatch releaseCommandA = new CountDownLatch(1);
        CountDownLatch commandBStarted = new CountDownLatch(1);
        List<String> workpadCommands = new CopyOnWriteArrayList<>();
        TrelloHandoffToolHandler workpads = mock();
        when(workpads.updateCodexUsageSection(any(), anyString(), nullable(String.class)))
                .thenAnswer(invocation -> {
                    if (invocation.getArgument(2) != null) {
                        EffectiveConfig updateConfig = invocation.getArgument(0);
                        workpadCommands.add(updateConfig.codex().command());
                    }
                    return true;
                });
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            if (request.config().codex().command().equals("command-a")) {
                commandAStarted.countDown();
                assertThat(releaseCommandA.await(5, TimeUnit.SECONDS)).isTrue();
                return AgentRunResult.codexUsageLimit(
                        "turn_failed: Late command A result.", Optional.of(now.plusSeconds(60)));
            }
            commandBStarted.countDown();
            tracker.setCardState(TestCards.card("card-1", "TRELLO-late", "Human Review"));
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads);

        // when
        orchestrator.start();
        assertThat(commandAStarted.await(5, TimeUnit.SECONDS)).isTrue();
        rewriteWorkflowWithCommand(workflow, "command-b", "");
        orchestrator.tickNowForTests();
        releaseCommandA.countDown();
        waitUntil(() -> orchestrator.snapshot().counts().running() == 0
                && orchestrator.snapshot().counts().retrying() == 1);
        RuntimeSnapshot afterLateResult = orchestrator.snapshot();
        orchestrator.retryNowForTests("card-1");
        assertThat(commandBStarted.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.stop();

        // then
        assertThat(afterLateResult.dispatchPause()).isNull();
        assertThat(afterLateResult.retrying()).singleElement().satisfies(retry -> assertThat(retry.dueAt())
                .isEqualTo(now.plusSeconds(10)));
        assertThat(workpadCommands).isEmpty();
    }

    @Test
    void invalidCommandReloadRetainsPreviousCommandPauseAndRateLimits() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithCommand(workflow, "command-a", "");
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card card = TestCards.card("card-1", "TRELLO-invalid-reload", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(card));
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            request.listener()
                    .onEvent(rateLimitEvent(
                            request, now, new ObjectMapper().createObjectNode().put("account", "A")));
            return AgentRunResult.codexUsageLimit(
                    "turn_failed: Command A is exhausted.", Optional.of(now.plusSeconds(60)));
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null);
        RuntimeSnapshot beforeReload = orchestrator.snapshot();
        long previousModified = Files.getLastModifiedTime(workflow).toMillis();
        Files.writeString(
                workflow,
                """
                ---
                tracker: []
                workspace:
                  root: work
                codex:
                  command: command-b
                ---
                {{ card.title }}
                """);
        Files.setLastModifiedTime(workflow, FileTime.fromMillis(previousModified + 2_000));
        orchestrator.tickNowForTests();
        RuntimeSnapshot afterReload = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(afterReload.dispatchPause()).isEqualTo(beforeReload.dispatchPause());
        assertThat(afterReload.rateLimits().toString()).contains("\"account\":\"A\"");
    }

    @Test
    void queuedWorkerUsesLaunchConfigCapturedBeforeCommandReload() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithCommand(workflow, "command-a", "");
        Card card = TestCards.card("card-1", "TRELLO-queued", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(card));
        AtomicReference<String> launchedCommand = new AtomicReference<>();
        CountDownLatch workerClosureEntered = new CountDownLatch(1);
        CountDownLatch releaseWorkerClosure = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            launchedCommand.set(request.config().codex().command());
            tracker.setCardState(TestCards.card("card-1", "TRELLO-queued", "Human Review"));
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);
        orchestrator.workerLaunchHookForTests = () -> {
            workerClosureEntered.countDown();
            try {
                assertThat(releaseWorkerClosure.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        };

        // when
        orchestrator.start();
        assertThat(workerClosureEntered.await(5, TimeUnit.SECONDS)).isTrue();
        rewriteWorkflowWithCommand(workflow, "command-b", "");
        orchestrator.tickNowForTests();
        releaseWorkerClosure.countDown();
        waitUntil(() -> launchedCommand.get() != null);
        orchestrator.stop();

        // then
        assertThat(launchedCommand).hasValue("command-a");
    }

    @Test
    void stalePreviousCommandPauseDeadlineCannotProbeCurrentCommand() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithCommand(workflow, "command-a", "");
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        Instant reset = now.plusSeconds(60);
        MutableClock clock = new MutableClock(now);
        Card card = TestCards.card("card-1", "TRELLO-deadline", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(card));
        AtomicInteger commandBRuns = new AtomicInteger();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            if (request.config().codex().command().equals("command-b")) {
                commandBRuns.incrementAndGet();
            }
            return AgentRunResult.codexUsageLimit("turn_failed: Exhausted.", Optional.of(reset));
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && orchestrator.snapshot().counts().retrying() == 1);
        orchestrator.stopWorkflowWatcherForTests();
        rewriteWorkflowWithCommand(workflow, "command-b", "");
        orchestrator.tickNowForTests();
        waitUntil(() -> commandBRuns.get() == 1 && orchestrator.snapshot().dispatchPause() != null);
        RuntimeSnapshot currentPause = orchestrator.snapshot();
        clock.advance(Duration.ofSeconds(60));
        orchestrator.dispatchPauseDeadlineNowForTests("command-a", reset);
        RuntimeSnapshot afterStaleCallback = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(commandBRuns).hasValue(1);
        assertThat(afterStaleCallback.dispatchPause()).isEqualTo(currentPause.dispatchPause());
        assertThat(afterStaleCallback.toString()).doesNotContain("command-a", "command-b");
    }

    @Test
    void commandReloadRefreshesCleanupBeforeSameCardCurrentCommandSection() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithCommand(workflow, "command-a", "");
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card card = TestCards.card("card-1", "TRELLO-workpad-owner", "Todo");
        FakeTracker tracker = new FakeTracker(List.of(card));
        List<String> workpadEvents = new CopyOnWriteArrayList<>();
        TrelloHandoffToolHandler workpads = mock();
        when(workpads.updateCodexUsageSection(any(), anyString(), nullable(String.class)))
                .thenAnswer(invocation -> {
                    EffectiveConfig updateConfig = invocation.getArgument(0);
                    String section = invocation.getArgument(2);
                    String command = updateConfig.codex().command();
                    workpadEvents.add(command + (section == null ? ":clear" : ":set"));
                    return section != null || !command.equals("command-a");
                });
        CountDownLatch commandBReturnedUsage = new CountDownLatch(1);
        AgentRunner runner = commandScopedUsageLimitRunner(now, commandBReturnedUsage);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null);
        rewriteWorkflowWithCommand(workflow, "command-b", "");
        orchestrator.tickNowForTests();
        assertThat(commandBReturnedUsage.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && orchestrator.snapshot().dispatchPause().until().equals(now.plusSeconds(120)));
        orchestrator.stop();

        // then
        assertThat(workpadEvents).containsSubsequence("command-a:set", "command-b:clear", "command-b:set");
        assertThat(workpadEvents.subList(workpadEvents.indexOf("command-b:set"), workpadEvents.size()))
                .doesNotContain("command-a:clear");
    }

    @Test
    void sameCardIdOnDifferentBoardRetainsCleanupWithPreviousBoardConfig() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithCommandAndBoard(workflow, "command-a", "board-1", "token-a");
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card boardACard = cardOnBoard("shared-card", "TRELLO-board-a", "Todo", "board-1");
        Card boardBCard = cardOnBoard("shared-card", "TRELLO-board-b", "Todo", "board-2");
        FakeTracker tracker = new FakeTracker(List.of(boardACard));
        tracker.resolveConfiguredBoardId = true;
        List<String> workpadEvents = new CopyOnWriteArrayList<>();
        TrelloHandoffToolHandler workpads = mock();
        when(workpads.updateCodexUsageSection(any(), anyString(), nullable(String.class)))
                .thenAnswer(invocation -> {
                    EffectiveConfig owner = invocation.getArgument(0);
                    String section = invocation.getArgument(2);
                    workpadEvents.add(
                            owner.codex().command() + "/" + owner.tracker().resolvedBoardId() + "/"
                                    + owner.tracker().apiToken() + (section == null ? ":clear" : ":set"));
                    return section != null || !owner.codex().command().equals("command-a");
                });
        CountDownLatch commandBReturnedUsage = new CountDownLatch(1);
        AgentRunner runner = commandScopedUsageLimitRunner(now, commandBReturnedUsage);
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null);
        tracker.setCandidates(List.of(boardBCard));
        rewriteWorkflowWithCommandAndBoard(workflow, "command-b", "board-2", "token-b");
        orchestrator.tickNowForTests();
        assertThat(commandBReturnedUsage.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && orchestrator.snapshot().dispatchPause().until().equals(now.plusSeconds(120)));
        orchestrator.stop();

        // then
        int boardBSet = workpadEvents.indexOf("command-b/board-2/token-b:set");
        assertThat(boardBSet).isGreaterThanOrEqualTo(0);
        assertThat(workpadEvents.subList(boardBSet, workpadEvents.size()))
                .contains("command-a/board-1/token-a:clear", "command-b/board-2/token-b:clear");
        assertThat(workpadEvents).doesNotContain("command-a/board-2/token-b:clear");
    }

    @Test
    void runningWorkerKeepsLaunchTrackerScopeAndLateActiveResultDoesNotRetryOnReloadedTarget() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String endpoint = "https://api.example.test/1";
        writeWorkflowWithTarget(workflow, endpoint, "board-a", "token-a", "command", "work-a");
        Card cardA = cardOnBoard("shared-card", "TRELLO-board-a", "Todo", "board-a");
        Card sameIdOnB = cardOnBoard("shared-card", "TRELLO-board-b", "Human Review", "board-b");
        ScopedTracker tracker = new ScopedTracker();
        tracker.setCandidates(endpoint, "board-a", List.of(cardA));
        tracker.setCandidates(endpoint, "board-b", List.of());
        tracker.setCardState(endpoint, "board-b", sameIdOnB);
        CountDownLatch commandAStarted = new CountDownLatch(1);
        CountDownLatch releaseCommandA = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(ignored -> {
            commandAStarted.countDown();
            try {
                releaseCommandA.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AgentRunResult.fail("interrupted");
            }
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        assertThat(commandAStarted.await(5, TimeUnit.SECONDS)).isTrue();
        rewriteWorkflowWithTarget(workflow, endpoint, "board-b", "token-b", "command", "work-b");
        orchestrator.tickNowForTests();
        RuntimeSnapshot whileOldWorkerRuns = orchestrator.snapshot();
        releaseCommandA.countDown();
        waitUntil(() -> orchestrator.snapshot().counts().running() == 0);
        RuntimeSnapshot afterLateResult = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(whileOldWorkerRuns.running()).hasSize(1);
        assertThat(tracker.stateFetchTargets).contains(targetLabel(endpoint, "board-a"));
        assertThat(tracker.releases).singleElement().satisfies(release -> {
            assertThat(release.endpoint()).isEqualTo(endpoint);
            assertThat(release.boardId()).isEqualTo("board-a");
            assertThat(release.workspaceRoot().getFileName().toString()).isEqualTo("work-a");
            assertThat(release.cardId()).isEqualTo("shared-card");
        });
        assertThat(afterLateResult.retrying()).isEmpty();
    }

    @Test
    void lateSameCommandOldTargetUsagePausesCurrentTargetWithoutTransplantingRetry() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String endpoint = "https://api.example.test/1";
        writeWorkflowWithTarget(workflow, endpoint, "board-a", "token-a", "same-command", "work-a");
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card cardA = cardOnBoard("card-a", "TRELLO-late-a", "Todo", "board-a");
        Card cardB = cardOnBoard("card-b", "TRELLO-fresh-b", "Todo", "board-b");
        ScopedTracker tracker = new ScopedTracker();
        tracker.setCandidates(endpoint, "board-a", List.of(cardA));
        tracker.setCandidates(endpoint, "board-b", List.of());
        List<String> workpadEvents = new CopyOnWriteArrayList<>();
        TrelloHandoffToolHandler workpads = recordingWorkpads(workpadEvents, ignored -> true);
        CountDownLatch oldTargetStarted = new CountDownLatch(1);
        CountDownLatch releaseOldTarget = new CountDownLatch(1);
        CountDownLatch newTargetStarted = new CountDownLatch(1);
        List<AgentRunner.AgentRunRequest> requests = new CopyOnWriteArrayList<>();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            requests.add(request);
            if (request.config().tracker().resolvedBoardId().equals("board-a")) {
                oldTargetStarted.countDown();
                assertThat(releaseOldTarget.await(5, TimeUnit.SECONDS)).isTrue();
                return AgentRunResult.codexUsageLimit("turn_failed: Late board A usage limit.", Optional.empty());
            }
            newTargetStarted.countDown();
            tracker.setCardState(
                    endpoint,
                    "board-b",
                    cardOnBoard(request.card().id(), request.card().identifier(), "Human Review", "board-b"));
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads);

        // when
        orchestrator.start();
        assertThat(oldTargetStarted.await(5, TimeUnit.SECONDS)).isTrue();
        rewriteWorkflowWithTarget(workflow, endpoint, "board-b", "token-b", "same-command", "work-b", 120_000, 1);
        orchestrator.tickNowForTests();
        tracker.setCandidates(endpoint, "board-b", List.of(cardB));
        releaseOldTarget.countDown();
        waitUntil(() -> tracker.releases.size() == 1);
        RuntimeSnapshot afterLateUsage = orchestrator.snapshot();
        long boardBFetchesBeforePausedTick = tracker.candidateFetchTargets.stream()
                .filter(targetLabel(endpoint, "board-b")::equals)
                .count();
        orchestrator.tickNowForTests();
        RuntimeSnapshot beforeDeadline = orchestrator.snapshot();
        List<String> workpadEventsBeforeDeadline = List.copyOf(workpadEvents);
        int requestsBeforeDeadline = requests.size();
        long boardBFetchesAfterPausedTick = tracker.candidateFetchTargets.stream()
                .filter(targetLabel(endpoint, "board-b")::equals)
                .count();
        clock.advance(Duration.ofMinutes(2));
        orchestrator.dispatchPauseDeadlineNowForTests(
                afterLateUsage.dispatchPause().until());
        assertThat(newTargetStarted.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() == null);
        RuntimeSnapshot afterFreshProbe = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(afterLateUsage.dispatchPause()).isNotNull();
        assertThat(afterLateUsage.dispatchPause().until()).isEqualTo(now.plusSeconds(120));
        assertThat(afterLateUsage.retrying()).isEmpty();
        assertThat(beforeDeadline.dispatchPause()).isNotNull();
        assertThat(requestsBeforeDeadline).isEqualTo(1);
        assertThat(boardBFetchesAfterPausedTick).isEqualTo(boardBFetchesBeforePausedTick);
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).config().tracker().resolvedBoardId()).isEqualTo("board-b");
        assertThat(requests.get(1).attempt()).isNull();
        assertThat(afterFreshProbe.retrying()).isEmpty();
        assertThat(workpadEventsBeforeDeadline)
                .contains(endpoint + "/board-a/token-a:set")
                .doesNotContain(endpoint + "/board-a/token-a:clear");
        assertThat(tracker.releases).singleElement().satisfies(release -> {
            assertThat(release.endpoint()).isEqualTo(endpoint);
            assertThat(release.boardId()).isEqualTo("board-a");
            assertThat(release.workspaceRoot().getFileName().toString()).isEqualTo("work-a");
            assertThat(release.cardId()).isEqualTo("card-a");
        });
    }

    @Test
    void multiHopTrackerRotationPurgesLateUsageWorkpadOwnershipOutsideNextTarget() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String endpoint = "https://api.example.test/1";
        writeWorkflowWithTarget(workflow, endpoint, "board-a", "token-a", "same-command", "work-a", 60_000, 1);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card cardA = cardOnBoard("card-a", "TRELLO-late-a", "Todo", "board-a");
        ScopedTracker tracker = new ScopedTracker();
        tracker.setCandidates(endpoint, "board-a", List.of(cardA));
        tracker.setCandidates(endpoint, "board-b", List.of());
        tracker.setCandidates(endpoint, "board-c", List.of());
        List<String> workpadEvents = new CopyOnWriteArrayList<>();
        TrelloHandoffToolHandler workpads = recordingWorkpads(workpadEvents, ignored -> true);
        CountDownLatch oldTargetStarted = new CountDownLatch(1);
        CountDownLatch releaseOldTarget = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            oldTargetStarted.countDown();
            assertThat(releaseOldTarget.await(5, TimeUnit.SECONDS)).isTrue();
            return AgentRunResult.codexUsageLimit("turn_failed: Late board A usage limit.", Optional.empty());
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads);

        // when
        orchestrator.start();
        assertThat(oldTargetStarted.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.stopWorkflowWatcherForTests();
        rewriteWorkflowWithTarget(workflow, endpoint, "board-b", "token-b", "same-command", "work-b", 120_000, 1);
        orchestrator.tickNowForTests();
        releaseOldTarget.countDown();
        String oldTargetSet = endpoint + "/board-a/token-a:set";
        String oldTargetClear = endpoint + "/board-a/token-a:clear";
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null && workpadEvents.contains(oldTargetSet));

        rewriteWorkflowWithTarget(workflow, endpoint, "board-c", "token-c", "same-command", "work-c", 180_000, 1);
        orchestrator.tickNowForTests();
        List<String> afterRotation = List.copyOf(workpadEvents);

        clock.advance(Duration.ofMinutes(2));
        orchestrator.dispatchPauseDeadlineNowForTests(now.plusSeconds(120));
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && orchestrator.snapshot().dispatchPause().until().equals(now.plusSeconds(300)));
        List<String> afterRearm = List.copyOf(workpadEvents);
        orchestrator.stop();

        // then
        assertThat(afterRotation).contains(oldTargetSet, oldTargetClear);
        assertThat(afterRearm).filteredOn(oldTargetSet::equals).hasSize(1);
        assertThat(afterRearm).contains(oldTargetClear);
    }

    @Test
    void lateSameTargetUsageUsesCurrentWorkpadOwnerAndFallbackPolicy() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String endpoint = "https://api.example.test/1";
        writeWorkflowWithTarget(workflow, endpoint, "board-a", "token-a", "same-command", "work-a", 60_000, 1);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card card = cardOnBoard("card-a", "TRELLO-same-target", "Todo", "board-a");
        ScopedTracker tracker = new ScopedTracker();
        tracker.setCandidates(endpoint, "board-a", List.of(card));
        List<String> workpadEvents = new CopyOnWriteArrayList<>();
        TrelloHandoffToolHandler workpads = recordingWorkpads(workpadEvents, ignored -> true);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            workerStarted.countDown();
            assertThat(releaseWorker.await(5, TimeUnit.SECONDS)).isTrue();
            return AgentRunResult.codexUsageLimit("turn_failed: Same target late usage.", Optional.empty());
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads);

        // when
        orchestrator.start();
        assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        rewriteWorkflowWithTarget(workflow, endpoint, "board-a", "token-b", "same-command", "work-b", 120_000, 1);
        orchestrator.tickNowForTests();
        releaseWorker.countDown();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && workpadEvents.contains(endpoint + "/board-a/token-b:set"));
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        List<String> eventsWhilePaused = List.copyOf(workpadEvents);
        orchestrator.stop();

        // then
        assertThat(snapshot.dispatchPause().until()).isEqualTo(now.plusSeconds(120));
        assertThat(eventsWhilePaused)
                .contains(endpoint + "/board-a/token-b:set")
                .doesNotContain(endpoint + "/board-a/token-a:set");
    }

    @Test
    void returningToLateUsageTargetRefreshesWorkpadCleanupOwnerAfterRotation() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String endpoint = "https://api.example.test/1";
        writeWorkflowWithTarget(workflow, endpoint, "board-a", "token-a-old", "same-command", "work-a", 60_000, 1);
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card lateCard = cardOnBoard("card-a-late", "TRELLO-late-a", "Todo", "board-a");
        Card freshCard = cardOnBoard("card-a-fresh", "TRELLO-fresh-a", "Todo", "board-a");
        ScopedTracker tracker = new ScopedTracker();
        tracker.setCandidates(endpoint, "board-a", List.of(lateCard));
        tracker.setCandidates(endpoint, "board-b", List.of());
        List<String> workpadEvents = new CopyOnWriteArrayList<>();
        TrelloHandoffToolHandler workpads = mock();
        when(workpads.updateCodexUsageSection(any(), anyString(), nullable(String.class)))
                .thenAnswer(invocation -> {
                    EffectiveConfig owner = invocation.getArgument(0);
                    String cardId = invocation.getArgument(1);
                    String section = invocation.getArgument(2);
                    workpadEvents.add(cardId + "/" + owner.tracker().apiToken() + "/"
                            + owner.agent().maxRetryBackoff().toMillis()
                            + (section == null ? ":clear" : ":set"));
                    return true;
                });
        CountDownLatch oldTargetStarted = new CountDownLatch(1);
        CountDownLatch releaseOldTarget = new CountDownLatch(1);
        CountDownLatch freshTargetStarted = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            if (request.config().tracker().apiToken().equals("token-a-old")) {
                oldTargetStarted.countDown();
                assertThat(releaseOldTarget.await(5, TimeUnit.SECONDS)).isTrue();
                return AgentRunResult.codexUsageLimit("turn_failed: Late A usage.", Optional.empty());
            }
            freshTargetStarted.countDown();
            tracker.setCardState(
                    endpoint,
                    "board-a",
                    cardOnBoard(request.card().id(), request.card().identifier(), "Human Review", "board-a"));
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads);

        // when
        orchestrator.start();
        assertThat(oldTargetStarted.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.stopWorkflowWatcherForTests();
        rewriteWorkflowWithTarget(workflow, endpoint, "board-b", "token-b", "same-command", "work-b", 120_000, 1);
        orchestrator.tickNowForTests();
        releaseOldTarget.countDown();
        waitUntil(() -> tracker.releases.size() == 1
                && orchestrator.snapshot().dispatchPause() != null
                && orchestrator.snapshot().dispatchPause().until().equals(now.plusSeconds(120)));
        tracker.setCandidates(endpoint, "board-a", List.of());
        rewriteWorkflowWithTarget(workflow, endpoint, "board-a", "token-a-new", "same-command", "work-a", 180_000, 1);
        orchestrator.tickNowForTests();
        RuntimeSnapshot afterReturn = orchestrator.snapshot();
        clock.advance(Duration.ofMinutes(2));
        orchestrator.dispatchPauseDeadlineNowForTests(now.plusSeconds(120));
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && orchestrator.snapshot().dispatchPause().until().equals(now.plusSeconds(300)));
        List<String> afterCurrentPolicyRearm = List.copyOf(workpadEvents);
        tracker.setCandidates(endpoint, "board-a", List.of(freshCard));
        clock.advance(Duration.ofMinutes(3));
        orchestrator.dispatchPauseDeadlineNowForTests(now.plusSeconds(300));
        assertThat(freshTargetStarted.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> workpadEvents.contains("card-a-late/token-a-new/180000:clear"));
        RuntimeSnapshot afterRecovery = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(afterReturn.dispatchPause()).isNotNull();
        assertThat(afterReturn.retrying()).isEmpty();
        assertThat(afterCurrentPolicyRearm).contains("card-a-late/token-a-new/180000:set");
        assertThat(workpadEvents)
                .contains("card-a-late/token-a-new/180000:clear", "card-a-fresh/token-a-new/180000:clear")
                .doesNotContain("card-a-late/token-a-old/60000:clear");
        assertThat(afterRecovery.dispatchPause()).isNull();
        assertThat(afterRecovery.retrying()).isEmpty();
    }

    @Test
    void sameCommandBoardReloadDetachesOldUsageOwnershipAndProbesNewBoardFresh() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String endpoint = "https://api.example.test/1";
        writeWorkflowWithTarget(workflow, endpoint, "board-a", "token-a", "same-command", "work-a");
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card cardA = cardOnBoard("card-a", "TRELLO-board-a", "Todo", "board-a");
        Card cardB = cardOnBoard("card-b", "TRELLO-board-b", "Todo", "board-b");
        ScopedTracker tracker = new ScopedTracker();
        tracker.setCandidates(endpoint, "board-a", List.of(cardA));
        tracker.setCandidates(endpoint, "board-b", List.of());
        List<String> workpadEvents = new CopyOnWriteArrayList<>();
        TrelloHandoffToolHandler workpads = recordingWorkpads(workpadEvents, ignored -> true);
        List<AgentRunner.AgentRunRequest> requests = new CopyOnWriteArrayList<>();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            requests.add(request);
            if (request.config().tracker().resolvedBoardId().equals("board-a")) {
                return AgentRunResult.codexUsageLimit(
                        "turn_failed: Board A usage limit.", Optional.of(now.plusSeconds(60)));
            }
            return AgentRunResult.fail("Board B reached Codex without another usage limit.");
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && orchestrator.snapshot().counts().retrying() == 1);
        tracker.setCandidates(endpoint, "board-b", List.of(cardB));
        rewriteWorkflowWithTarget(workflow, endpoint, "board-b", "token-b", "same-command", "work-b");
        orchestrator.tickNowForTests();
        RuntimeSnapshot afterReload = orchestrator.snapshot();
        clock.advance(Duration.ofSeconds(60));
        orchestrator.dispatchPauseDeadlineNowForTests(now.plusSeconds(60));
        waitUntil(() -> requests.size() == 2 && orchestrator.snapshot().dispatchPause() == null);
        orchestrator.stop();

        // then
        assertThat(afterReload.dispatchPause()).isNotNull();
        assertThat(afterReload.retrying()).isEmpty();
        assertThat(workpadEvents).contains(endpoint + "/board-a/token-a:clear");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).config().tracker().resolvedBoardId()).isEqualTo("board-b");
        assertThat(requests.get(1).attempt()).isNull();
    }

    @Test
    void sameTargetCredentialReloadRefreshesActiveWorkpadOwnerConfig() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String endpoint = "https://api.example.test/1";
        writeWorkflowWithTarget(workflow, endpoint, "board-a", "token-a", "same-command", "work");
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        Card card = cardOnBoard("card-a", "TRELLO-board-a", "Todo", "board-a");
        ScopedTracker tracker = new ScopedTracker();
        tracker.setCandidates(endpoint, "board-a", List.of(card));
        List<String> workpadEvents = new CopyOnWriteArrayList<>();
        TrelloHandoffToolHandler workpads = recordingWorkpads(workpadEvents, ignored -> true);
        AgentRunner runner = mock();
        when(runner.run(any()))
                .thenReturn(
                        AgentRunResult.codexUsageLimit("turn_failed: Usage limit.", Optional.of(now.plusSeconds(60))));
        SymphonyOrchestrator orchestrator =
                orchestrator(workflow, tracker, runner, Clock.fixed(now, ZoneOffset.UTC), workpads);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null);
        rewriteWorkflowWithTarget(workflow, endpoint, "board-a", "token-b", "same-command", "work");
        orchestrator.tickNowForTests();
        orchestrator.stop();

        // then
        assertThat(workpadEvents).contains(endpoint + "/board-a/token-a:set", endpoint + "/board-a/token-b:clear");
    }

    @Test
    void differentTrackerSameCardIdDoesNotInheritPreviousTargetRetry() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String endpoint = "https://api.example.test/1";
        writeWorkflowWithTarget(workflow, endpoint, "board-a", "token-a", "command-a", "work-a");
        Card cardA = cardOnBoard("shared-card", "TRELLO-board-a", "Todo", "board-a");
        Card cardB = cardOnBoard("shared-card", "TRELLO-board-b", "Todo", "board-b");
        ScopedTracker tracker = new ScopedTracker();
        tracker.setCandidates(endpoint, "board-a", List.of(cardA));
        tracker.setCandidates(endpoint, "board-b", List.of());
        List<AgentRunner.AgentRunRequest> requests = new CopyOnWriteArrayList<>();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            requests.add(request);
            if (request.config().tracker().resolvedBoardId().equals("board-a")) {
                return AgentRunResult.fail("generic command A failure");
            }
            tracker.setCardState(
                    endpoint,
                    "board-b",
                    cardOnBoard(request.card().id(), request.card().identifier(), "Human Review", "board-b"));
            return AgentRunResult.ok();
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        tracker.setCandidates(endpoint, "board-b", List.of(cardB));
        rewriteWorkflowWithTarget(workflow, endpoint, "board-b", "token-b", "command-b", "work-b");
        orchestrator.tickNowForTests();
        orchestrator.retryNowForTests("shared-card");
        waitUntil(() -> requests.size() == 2);
        orchestrator.stop();

        // then
        assertThat(requests.get(1).config().tracker().resolvedBoardId()).isEqualTo("board-b");
        assertThat(requests.get(1).attempt()).isNull();
    }

    @Test
    void sameBoardAndCardIdsOnDifferentEndpointsKeepIndependentCleanupOwnership() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String endpointA = "https://api-a.example.test/1/";
        String endpointB = "https://api-b.example.test/1";
        writeWorkflowWithTarget(workflow, endpointA, "shared-board", "token-a", "command-a", "work-a");
        Instant now = Instant.parse("2026-07-10T12:00:00Z");
        MutableClock clock = new MutableClock(now);
        Card cardA = cardOnBoard("shared-card", "TRELLO-endpoint-a", "Todo", "shared-board");
        Card cardB = cardOnBoard("shared-card", "TRELLO-endpoint-b", "Todo", "shared-board");
        ScopedTracker tracker = new ScopedTracker();
        tracker.setCandidates(endpointA, "shared-board", List.of(cardA));
        tracker.setCandidates(endpointB, "shared-board", List.of());
        List<String> workpadEvents = new CopyOnWriteArrayList<>();
        AtomicInteger endpointAClears = new AtomicInteger();
        TrelloHandoffToolHandler workpads = recordingWorkpads(workpadEvents, event -> {
            if (event.equals(endpointA + "/shared-board/token-a:clear")) {
                return endpointAClears.incrementAndGet() > 1;
            }
            return true;
        });
        CountDownLatch commandBReturnedUsage = new CountDownLatch(1);
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            if (request.config().tracker().endpoint().equals(endpointB)) {
                commandBReturnedUsage.countDown();
                return AgentRunResult.codexUsageLimit(
                        "turn_failed: Endpoint B usage limit.", Optional.of(now.plusSeconds(120)));
            }
            return AgentRunResult.codexUsageLimit(
                    "turn_failed: Endpoint A usage limit.", Optional.of(now.plusSeconds(60)));
        });
        SymphonyOrchestrator orchestrator = orchestrator(workflow, tracker, runner, clock, workpads);

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null);
        tracker.setCandidates(endpointB, "shared-board", List.of(cardB));
        rewriteWorkflowWithTarget(workflow, endpointB, "shared-board", "token-b", "command-b", "work-b");
        orchestrator.tickNowForTests();
        assertThat(commandBReturnedUsage.await(5, TimeUnit.SECONDS)).isTrue();
        waitUntil(() -> orchestrator.snapshot().dispatchPause() != null
                && orchestrator.snapshot().dispatchPause().until().equals(now.plusSeconds(120)));
        orchestrator.stop();

        // then
        assertThat(endpointAClears).hasValue(2);
        assertThat(workpadEvents)
                .contains(
                        endpointA + "/shared-board/token-a:set",
                        endpointA + "/shared-board/token-a:clear",
                        endpointB + "/shared-board/token-b:set",
                        endpointB + "/shared-board/token-b:clear");
    }
}
