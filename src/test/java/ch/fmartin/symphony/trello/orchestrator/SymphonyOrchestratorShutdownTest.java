package ch.fmartin.symphony.trello.orchestrator;

import static ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestratorTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.agent.AgentRunResult;
import ch.fmartin.symphony.trello.agent.AgentRunner;
import ch.fmartin.symphony.trello.agent.CodexAppServerClient;
import ch.fmartin.symphony.trello.agent.LocalAgentRunner;
import ch.fmartin.symphony.trello.agent.TrelloHandoffToolHandler;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.testsupport.ShutdownAppServer;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class SymphonyOrchestratorShutdownTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stalledUnconfirmedShutdownRetainsOwnershipAcrossRetryAndReload(boolean reloadTarget) throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithStallTimeout(workflow, 1000);
        var clock = new MutableClock(Instant.parse("2026-10-27T00:00:00Z"));
        var card = TestCards.card("card-1", "TRELLO-lifetime", "Todo");
        var tracker = new FakeTracker(List.of(card));
        tracker.resolveConfiguredBoardId = true;
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        Path sentinel = tempDir.resolve("work/TRELLO-lifetime/sentinel");
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            Files.createDirectories(sentinel.getParent());
            Files.writeString(sentinel, "shutdown remains unconfirmed");
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentRunResult.shutdownIncomplete("cannot release test workspace: exit is unconfirmed");
        });
        var orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());
        orchestrator.workerExitCompletionHookForTests = exited::countDown;
        try {
            orchestrator.start();
            assertThat(started.await(5, TimeUnit.SECONDS))
                    .as("the worker starts before the stall")
                    .isTrue();

            // when
            clock.advance(Duration.ofSeconds(2));
            orchestrator.tickNowForTests();
            assertThat(exited.await(5, TimeUnit.SECONDS))
                    .as("the canceled worker reports its shutdown result")
                    .isTrue();
            orchestrator.tickNowForTests();

            // then
            assertThat(orchestrator.snapshot().counts().retrying()).isZero();
            if (reloadTarget) {
                var replacement = cardOnBoard("replacement", card.identifier(), "Todo", "board-2");
                tracker.setCardState(replacement);
                tracker.setCandidateList(List.of(replacement));
                rewriteWorkflowWithTarget(workflow, "https://api.trello.com/1", "board-2", "token", "fake", "work");
            }
            orchestrator.retryNowForTests(card.id());
            orchestrator.tickNowForTests();
            assertThat(sentinel).exists();
            verify(runner).run(any());
        } finally {
            release.countDown();
            orchestrator.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Todo", "Done", "missing"})
    void retryWaitsForCanceledRunnerBeforeDispatchOrRemoval(String state) throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithStallTimeout(workflow, 1000);
        var clock = new MutableClock(Instant.parse("2026-10-27T00:00:00Z"));
        var card = TestCards.card("card-1", "TRELLO-lifetime", "Todo");
        var tracker = new FakeTracker(List.of(card));
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var launches = new AtomicInteger();
        Path sentinel = tempDir.resolve("work/TRELLO-lifetime/sentinel");
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            if (launches.incrementAndGet() > 1) {
                tracker.setCardState(TestCards.card(card.id(), card.identifier(), "Human Review"));
                return AgentRunResult.ok();
            }
            Files.createDirectories(sentinel.getParent());
            Files.writeString(sentinel, "worker is unwinding");
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                assertThat(release.await(10, TimeUnit.SECONDS))
                        .as("the test releases the canceled runner")
                        .isTrue();
            }
            return AgentRunResult.fail("test worker stopped");
        });
        var orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());
        orchestrator.workerExitCompletionHookForTests = exited::countDown;
        try {
            orchestrator.start();
            assertThat(started.await(5, TimeUnit.SECONDS))
                    .as("the worker owns its directory before cancellation")
                    .isTrue();
            clock.advance(Duration.ofSeconds(2));
            orchestrator.tickNowForTests();
            tracker.cardStates = Map.of(
                    card.id(),
                    "missing".equals(state)
                            ? new CardLookupResult.Missing(card.id())
                            : new CardLookupResult.Found(TestCards.card(card.id(), card.identifier(), state)));

            // when
            orchestrator.retryNowForTests(card.id());

            // then
            assertThat(sentinel).exists();
            assertThat(orchestrator.snapshot().counts().running()).isZero();
            release.countDown();
            assertThat(exited.await(5, TimeUnit.SECONDS))
                    .as("the canceled runner returns before retry resumes")
                    .isTrue();
            orchestrator.tickNowForTests();
            orchestrator.retryNowForTests(card.id());
            if ("Todo".equals(state)) {
                waitUntil(() -> launches.get() == 2);
                assertThat(sentinel).exists();
            } else {
                waitUntil(() -> !Files.exists(sentinel));
                assertThat(launches).hasValue(1);
            }
        } finally {
            release.countDown();
            orchestrator.stop();
        }
    }

    @Test
    void unconfirmedUsageProbeReleasesUnrelatedCards() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        var clock = new MutableClock(Instant.parse("2026-10-27T00:00:00Z"));
        var card = TestCards.card("card-1", "TRELLO-lifetime", "Todo");
        var next = TestCards.card("card-2", "TRELLO-next", "Todo");
        var tracker = new FakeTracker(List.of(card));
        List<String> launched = new CopyOnWriteArrayList<>();
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            AgentRunner.AgentRunRequest request = invocation.getArgument(0);
            launched.add(request.card().id());
            if (launched.size() == 1) {
                return AgentRunResult.codexUsageLimit("test usage limit", Optional.empty());
            }
            if (request.card().id().equals(card.id())) {
                return AgentRunResult.shutdownIncomplete("cannot release test workspace: exit is unconfirmed");
            }
            tracker.setCardState(TestCards.card(next.id(), next.identifier(), "Human Review"));
            return AgentRunResult.ok();
        });
        var orchestrator = orchestrator(workflow, tracker, runner, clock, successfulWorkpadHandler());
        try {
            orchestrator.start();
            waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
            Instant deadline = orchestrator.snapshot().dispatchPause().until();
            clock.advance(Duration.between(clock.instant(), deadline));

            tracker.setCandidates(List.of(card, next));

            // when
            orchestrator.dispatchPauseDeadlineNowForTests(deadline);

            // then
            waitUntil(() -> launched.size() == 3);
            assertThat(launched).containsExactly(card.id(), card.id(), next.id());
        } finally {
            orchestrator.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unconfirmedProcessExitRetainsWorkspaceAndClaimWithoutRetry(boolean reloadTarget) throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        var card = TestCards.card("card-1", "TRELLO-lifetime", "Todo");
        var tracker = new FakeTracker(List.of(card));
        tracker.resolveConfiguredBoardId = true;
        var exited = new CountDownLatch(1);
        Path sentinel = tempDir.resolve("work/TRELLO-lifetime/sentinel");
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            Files.createDirectories(sentinel.getParent());
            Files.writeString(sentinel, "process shutdown remains unconfirmed");
            return AgentRunResult.shutdownIncomplete("cannot release test workspace: process exit is unconfirmed");
        });
        var orchestrator = orchestrator(workflow, tracker, runner);
        orchestrator.workerExitCompletionHookForTests = exited::countDown;
        try {

            // when
            orchestrator.start();
            assertThat(exited.await(5, TimeUnit.SECONDS))
                    .as("the failed shutdown result reaches the orchestrator")
                    .isTrue();
            if (reloadTarget) {
                var replacement = cardOnBoard("replacement", card.identifier(), "Todo", "board-2");
                tracker.setCardState(replacement);
                tracker.setCandidateList(List.of(replacement));
                rewriteWorkflowWithTarget(workflow, "https://api.trello.com/1", "board-2", "token", "fake", "work");
            }
            orchestrator.tickNowForTests();
            orchestrator.tickNowForTests();

            // then
            assertThat(sentinel).exists();
            assertThat(orchestrator.snapshot().counts().running()).isZero();
            assertThat(orchestrator.snapshot().counts().retrying()).isZero();
            verify(runner).run(any());
        } finally {
            orchestrator.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realAppServerAndChildExitBeforeAfterRunAndWorkspaceRemoval(boolean terminalCancellation) throws Exception {
        // given
        Path processFiles = Files.createDirectory(tempDir.resolve("process"));
        Path script = tempDir.resolve("app-server.sh");
        Files.writeString(
                script,
                "exec '" + System.getProperty("java.home") + "/bin/java' -cp '"
                        + System.getProperty("java.class.path").replace("'", "'\\''") + "' "
                        + ShutdownAppServer.class.getName() + " '" + processFiles + "' " + !terminalCancellation
                        + " true\n");
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflowWithCommand(workflow, new ObjectMapper().writeValueAsString("bash " + script), "");
        var card = TestCards.card("card-1", "TRELLO-lifetime", "Todo");
        var tracker = new FakeTracker(List.of(card));
        var hooks = mock(HookRunner.class);
        var workspaces = spy(new WorkspaceManager(hooks));
        var afterRun = new CountDownLatch(1);
        var releaseHook = new CountDownLatch(1);
        Path sentinel = tempDir.resolve("work/TRELLO-lifetime/sentinel");
        doAnswer(invocation -> {
                    if ("before_run".equals(invocation.getArgument(0))) {
                        Files.writeString(sentinel, "owned by the runtime");
                    }
                    return null;
                })
                .when(hooks)
                .runRequired(any(), any(), any(), any());
        doAnswer(invocation -> {
                    if ("after_run".equals(invocation.getArgument(0))) {
                        afterRun.countDown();
                        assertThat(releaseHook.await(10, TimeUnit.SECONDS))
                                .as("the test releases after_run after observing process exit")
                                .isTrue();
                    }
                    return null;
                })
                .when(hooks)
                .runBestEffort(any(), any(), any(), any());
        var runner = new LocalAgentRunner(
                workspaces,
                hooks,
                new CodexAppServerClient(
                        new ObjectMapper(), new TrelloHandoffToolHandler(new ObjectMapper(), mock(TrelloClient.class))),
                tracker,
                new PromptRenderer());
        var orchestrator = orchestrator(workflow, tracker, runner, workspaces);
        try {
            orchestrator.start();
            waitUntil(() ->
                    Files.exists(processFiles.resolve("started")) && Files.exists(processFiles.resolve("child/port")));
            tracker.setCandidateList(List.of());
            tracker.setCardState(TestCards.card(card.id(), card.identifier(), "Done"));

            // when
            if (terminalCancellation) {
                orchestrator.requestRefresh();
            } else {
                Files.writeString(processFiles.resolve("complete-turn"), "tracker is terminal");
            }
            waitUntil(() -> Files.exists(processFiles.resolve("stopping"))
                    && Files.exists(processFiles.resolve("child/stopping")));
            releaseProcess(processFiles.resolve("child"));
            releaseProcess(processFiles);
            assertThat(afterRun.await(5, TimeUnit.SECONDS))
                    .as("after_run starts after confirmed process shutdown")
                    .isTrue();

            // then
            assertThat(ownedProcessAlive(processFiles))
                    .as("the direct app-server has exited")
                    .isFalse();
            assertThat(ownedProcessAlive(processFiles.resolve("child")))
                    .as("the attached child has exited independently of its parent's exit")
                    .isFalse();
            assertThat(sentinel).exists();
            releaseHook.countDown();
            waitUntil(() -> !Files.exists(sentinel));
            verify(workspaces).removeForIdentifierIfPresent(any(), any());
        } finally {
            releaseHook.countDown();
            stopOwnedProcess(processFiles.resolve("child"));
            stopOwnedProcess(processFiles);
            orchestrator.stop();
        }
    }

    private static void releaseProcess(Path directory) throws Exception {
        int port = Integer.parseInt(Files.readString(directory.resolve("port")));
        try (var socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.getOutputStream().write(1);
        }
    }

    private static boolean ownedProcessAlive(Path directory) throws Exception {
        long pid = Long.parseLong(Files.readString(directory.resolve("process")));
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static void stopOwnedProcess(Path directory) throws Exception {
        Path identity = directory.resolve("process");
        if (Files.exists(identity)) {
            long pid = Long.parseLong(Files.readString(identity));
            var process = ProcessHandle.of(pid);
            if (process.isEmpty()) {
                return;
            }
            ProcessHandle owned = process.orElseThrow();
            owned.destroyForcibly();
            owned.onExit().get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void terminalCleanupWaitsForRunnerToReturn(boolean missing) throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Path hookMarker = tempDir.resolve("before-remove-completed");
        writeWorkflow(workflow, "60000", "hooks:\n  before_remove: \"sleep 0.2; touch " + hookMarker + "\"\n");
        var card = TestCards.card("card-1", "TRELLO-lifetime", "Todo");
        var tracker = new FakeTracker(List.of(card));
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ticks = new AtomicInteger();
        Path sentinel = tempDir.resolve("work/TRELLO-lifetime/sentinel");
        AgentRunner runner = mock();
        when(runner.run(any())).thenAnswer(invocation -> {
            Files.createDirectories(sentinel.getParent());
            Files.writeString(sentinel, "owned by the running worker");
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS))
                        .as("the test releases the worker after checking its workspace")
                        .isTrue();
                Thread.currentThread().interrupt();
            }
            return AgentRunResult.fail("cannot finish test worker: expected uninterrupted work");
        });
        var orchestrator = orchestrator(workflow, tracker, runner);
        orchestrator.tickCompletionHookForTests = ticks::incrementAndGet;
        try {
            orchestrator.start();
            assertThat(started.await(5, TimeUnit.SECONDS))
                    .as("the worker must own a workspace before cancellation")
                    .isTrue();
            tracker.setCandidateList(List.of());
            tracker.cardStates = Map.of(
                    card.id(),
                    missing
                            ? new CardLookupResult.Missing(card.id())
                            : new CardLookupResult.Found(TestCards.card(card.id(), card.identifier(), "Done")));
            int before = ticks.get();

            // when
            orchestrator.requestRefresh();
            assertThat(interrupted.await(5, TimeUnit.SECONDS))
                    .as("reconciliation requests cancellation while the worker remains alive")
                    .isTrue();
            waitUntil(() -> ticks.get() > before);

            // then
            assertThat(sentinel).exists();
            assertThat(orchestrator.snapshot().counts().retrying()).isZero();
            release.countDown();
            waitUntil(() -> !Files.exists(sentinel));
            assertThat(hookMarker).exists();
            verify(runner).run(any());
        } finally {
            release.countDown();
            orchestrator.stop();
        }
    }
}
