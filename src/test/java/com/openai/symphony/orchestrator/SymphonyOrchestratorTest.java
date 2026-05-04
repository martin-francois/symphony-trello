package com.openai.symphony.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.openai.symphony.TestCards;
import com.openai.symphony.agent.AgentRunResult;
import com.openai.symphony.agent.AgentRunner;
import com.openai.symphony.config.ConfigResolver;
import com.openai.symphony.domain.Card;
import com.openai.symphony.prompt.PromptRenderer;
import com.openai.symphony.tracker.CardLookupResult;
import com.openai.symphony.tracker.TrackerClient;
import com.openai.symphony.workflow.WorkflowLoader;
import com.openai.symphony.workspace.HookRunner;
import com.openai.symphony.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymphonyOrchestratorTest {
    @TempDir
    Path tempDir;

    @Test
    void dispatchesEligibleCardAndQueuesContinuationRetryAfterNormalExit() throws Exception {
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
                workspace:
                  root: work
                polling:
                  interval_ms: 60000
                codex:
                  command: fake
                ---
                {{ card.title }}
                """);
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

        private FakeTracker(List<Card> candidates) {
            this.candidates = new ArrayList<>(candidates);
        }

        @Override
        public String resolveCanonicalBoardId(com.openai.symphony.config.EffectiveConfig config) {
            return "board-1";
        }

        @Override
        public List<Card> fetchCandidateCards(com.openai.symphony.config.EffectiveConfig config) {
            return candidates;
        }

        @Override
        public List<Card> fetchTerminalCards(com.openai.symphony.config.EffectiveConfig config) {
            return List.of();
        }

        @Override
        public Map<String, CardLookupResult> fetchCardStatesByIds(
                com.openai.symphony.config.EffectiveConfig config, List<String> cardIds) {
            return candidates.stream()
                    .filter(card -> cardIds.contains(card.id()))
                    .collect(java.util.stream.Collectors.toMap(Card::id, CardLookupResult.Found::new));
        }
    }
}
