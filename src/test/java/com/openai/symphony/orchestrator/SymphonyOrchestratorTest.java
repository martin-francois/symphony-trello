package com.openai.symphony.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.symphony.TestCards;
import com.openai.symphony.agent.AgentEvent;
import com.openai.symphony.agent.AgentRunResult;
import com.openai.symphony.agent.AgentRunner;
import com.openai.symphony.config.ConfigResolver;
import com.openai.symphony.config.EffectiveConfig;
import com.openai.symphony.domain.Card;
import com.openai.symphony.prompt.PromptRenderer;
import com.openai.symphony.tracker.CardLookupResult;
import com.openai.symphony.tracker.TrackerClient;
import com.openai.symphony.workflow.WorkflowLoader;
import com.openai.symphony.workspace.HookRunner;
import com.openai.symphony.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymphonyOrchestratorTest {
    @TempDir
    Path tempDir;

    @Test
    void dispatchesEligibleCardAndQueuesContinuationRetryAfterNormalExit() throws Exception {
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = new ImmediateRunner();
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                runner,
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()));
        orchestrator.workflowPath = workflow;

        orchestrator.start();
        Thread.sleep(500);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        assertThat(snapshot.counts().retrying()).isEqualTo(1);
        assertThat(snapshot.retrying()).singleElement().satisfies(row -> {
            assertThat(row.cardIdentifier()).isEqualTo("TRELLO-abc");
            assertThat(row.attempt()).isEqualTo(1);
        });
    }

    @Test
    void workflowWatcherQueuesRefreshWhenWorkflowFileChanges() throws Exception {
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of());
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                new ImmediateRunner(),
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()));
        orchestrator.workflowPath = workflow;

        orchestrator.start();
        waitUntil(() -> tracker.candidateFetches.get() >= 1);
        Thread.sleep(20);
        writeWorkflow(workflow, "60000");
        waitUntil(() -> tracker.candidateFetches.get() >= 2);
        orchestrator.stop();

        assertThat(tracker.candidateFetches.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void refreshRequestedDuringActiveTickRunsImmediatelyAfterTickCompletes() throws Exception {
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        BlockingTracker tracker = new BlockingTracker();
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                new ImmediateRunner(),
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()));
        orchestrator.workflowPath = workflow;

        orchestrator.start();
        assertThat(tracker.firstFetchStarted.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.requestRefresh();
        tracker.releaseFirstFetch.countDown();
        waitUntil(() -> tracker.candidateFetches.get() >= 2);
        orchestrator.stop();

        assertThat(tracker.candidateFetches.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void rateLimitEventsAreExposedInSnapshot() throws Exception {
        Path workflow = tempDir.resolve("WORKFLOW.md");
        writeWorkflow(workflow, "60000");
        FakeTracker tracker = new FakeTracker(List.of(TestCards.card("card-1", "TRELLO-abc", "Todo")));
        AgentRunner runner = new AgentRunner() {
            @Override
            public AgentRunResult run(AgentRunRequest request) {
                request.listener()
                        .onEvent(new AgentEvent(
                                "account/rateLimits/updated",
                                Instant.now(),
                                request.workerIdentity(),
                                123L,
                                "thread-1",
                                "turn-1",
                                "rate limits",
                                Map.of(),
                                new ObjectMapper().createObjectNode().put("primary", "ok")));
                return AgentRunResult.ok();
            }

            @Override
            public void cancel(String workerIdentity) {}
        };
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                tracker,
                runner,
                new PromptRenderer(),
                new WorkspaceManager(new HookRunner()));
        orchestrator.workflowPath = workflow;

        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().rateLimits() != null);
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        assertThat(snapshot.rateLimits().toString()).contains("primary");
    }

    private static void writeWorkflow(Path workflow, String pollIntervalMs) throws Exception {
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
                codex:
                  command: fake
                ---
                {{ card.title }}
                """
                        .formatted(pollIntervalMs));
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

    private static final class ImmediateRunner implements AgentRunner {
        @Override
        public AgentRunResult run(AgentRunRequest request) {
            return AgentRunResult.ok();
        }

        @Override
        public void cancel(String workerIdentity) {}
    }

    private static final class FakeTracker implements TrackerClient {
        private final List<Card> candidates;
        private final AtomicInteger candidateFetches = new AtomicInteger();

        private FakeTracker(List<Card> candidates) {
            this.candidates = new ArrayList<>(candidates);
        }

        @Override
        public String resolveCanonicalBoardId(EffectiveConfig config) {
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
            return candidates.stream()
                    .filter(card -> cardIds.contains(card.id()))
                    .collect(Collectors.toMap(Card::id, CardLookupResult.Found::new));
        }
    }

    private static final class BlockingTracker implements TrackerClient {
        private final AtomicInteger candidateFetches = new AtomicInteger();
        private final CountDownLatch firstFetchStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstFetch = new CountDownLatch(1);

        @Override
        public String resolveCanonicalBoardId(EffectiveConfig config) {
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
