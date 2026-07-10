package ch.fmartin.symphony.trello.orchestrator;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.agent.CodexAppServerClient;
import ch.fmartin.symphony.trello.agent.LocalAgentRunner;
import ch.fmartin.symphony.trello.agent.TrelloHandoffToolHandler;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.tracker.CardLookupResult;
import ch.fmartin.symphony.trello.tracker.TrackerClient;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class UsageLimitPauseIntegrationTest {
    private static final String FIRST_CARD = "TRELLO-first";
    private static final String SECOND_CARD = "TRELLO-second";

    @TempDir
    Path tempDir;

    @Test
    void typedUsageLimitStopsTheNextPriorityCardFromChurningThroughInProgress() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Path fakeAppServer = writeUsageLimitAppServer();
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: key
                  api_token: token
                  board_id: board-1
                  active_states: [Ready for Codex, In Progress]
                  in_progress_state: In Progress
                workspace:
                  root: workspaces
                polling:
                  interval_ms: 60000
                agent:
                  max_concurrent_agents: 1
                  max_retry_backoff_ms: 60000
                codex:
                  command: %s
                  read_timeout_ms: 5000
                  turn_timeout_ms: 5000
                trello_tools:
                  enabled: false
                ---
                {{ card.identifier }}
                """
                        .formatted(fakeAppServer));
        Card first = TestCards.cardWithLabels("card-1", FIRST_CARD, "Ready for Codex", List.of("p1"));
        Card second = TestCards.cardWithLabels("card-2", SECOND_CARD, "Ready for Codex", List.of("p2"));
        LocalTracker tracker = new LocalTracker(List.of(first, second));
        tracker.setPreparedCard(inProgress(first));
        ObjectMapper json = new ObjectMapper();
        WorkspaceManager workspaces = new WorkspaceManager(new HookRunner());
        var codex = new CodexAppServerClient(json, new TrelloHandoffToolHandler(json, new TrelloClient(json)));
        var runner = new LocalAgentRunner(workspaces, new HookRunner(), codex, tracker, new PromptRenderer());
        SymphonyOrchestrator orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(), new ConfigResolver(), tracker, runner, new PromptRenderer(), workspaces);
        orchestrator.workflowPath = workflow;

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().counts().retrying() == 1);
        int candidateFetchesBeforeRefresh = tracker.candidateFetches.get();
        orchestrator.tickNowForTests();
        RuntimeSnapshot snapshot = orchestrator.snapshot();
        orchestrator.stop();

        // then
        assertThat(snapshot.retrying()).singleElement().satisfies(retry -> {
            assertThat(retry.cardIdentifier()).isEqualTo(FIRST_CARD);
            assertThat(retry.error()).contains("Synthetic Codex usage limit");
        });
        assertThat(tracker.preparedIdentifiers)
                .as("the workflow-wide usage pause must stop the second card before its pickup move")
                .containsExactly(FIRST_CARD);
        assertThat(tracker.candidateFetches)
                .as("manual refresh must stop before Trello candidate I/O while usage is paused")
                .hasValue(candidateFetchesBeforeRefresh);
        assertThat(tracker.releasedIdentifiers).containsExactly(FIRST_CARD);
        assertThat(tracker.cardState("card-2").state()).isEqualTo("Ready for Codex");
    }

    private static Card inProgress(Card card) {
        return TestCards.cardWithLabels(card.id(), card.identifier(), "In Progress", card.labels());
    }

    private Path writeUsageLimitAppServer() throws Exception {
        Path script = tempDir.resolve("usage-limit-app-server.sh");
        Files.writeString(
                script,
                """
                #!/usr/bin/env bash
                while IFS= read -r line; do
                  case "$line" in
                    *\\"method\\":\\"initialize\\"*) echo '{"id":1,"result":{"userAgent":"usage-limit-test"}}' ;;
                    *\\"method\\":\\"thread/start\\"*) echo '{"id":2,"result":{"thread":{"id":"thread-usage"}}}' ;;
                    *\\"method\\":\\"turn/start\\"*)
                      echo '{"id":3,"result":{"turn":{"id":"turn-usage"}}}'
                      echo '{"method":"account/rateLimits/updated","params":{"rateLimits":{"primary":{"usedPercent":100,"resetsAt":4102444800},"secondary":null}}}'
                      echo '{"method":"turn/completed","params":{"threadId":"thread-usage","turn":{"id":"turn-usage","items":[],"status":"failed","error":{"message":"Synthetic Codex usage limit.","additionalDetails":"private account detail","codexErrorInfo":"usageLimitExceeded"}}}}'
                      ;;
                  esac
                done
                """);
        script.toFile().setExecutable(true);
        return script;
    }

    private static void waitUntil(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
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

    private static final class LocalTracker implements TrackerClient {
        private final List<Card> candidates;
        private volatile Map<String, CardLookupResult> cardStates;
        private final AtomicInteger candidateFetches = new AtomicInteger();
        private final List<String> preparedIdentifiers = new CopyOnWriteArrayList<>();
        private final List<String> releasedIdentifiers = new CopyOnWriteArrayList<>();
        private final Map<String, Card> preparedCards = new LinkedHashMap<>();

        private LocalTracker(List<Card> candidates) {
            this.candidates = List.copyOf(candidates);
            cardStates = candidates.stream().collect(toImmutableMap(Card::id, CardLookupResult.Found::new));
        }

        private void setPreparedCard(Card card) {
            preparedCards.put(card.id(), card);
        }

        private Card cardState(String cardId) {
            return ((CardLookupResult.Found) cardStates.get(cardId)).card();
        }

        private void setCardState(Card card) {
            Map<String, CardLookupResult> updated = new LinkedHashMap<>(cardStates);
            updated.put(card.id(), new CardLookupResult.Found(card));
            cardStates = Map.copyOf(updated);
        }

        @Override
        public String resolveBoardId(EffectiveConfig config) {
            return "board-1";
        }

        @Override
        public List<Card> fetchCandidateCards(EffectiveConfig config) {
            candidateFetches.incrementAndGet();
            return new ArrayList<>(candidates);
        }

        @Override
        public List<Card> fetchTerminalCards(EffectiveConfig config) {
            return List.of();
        }

        @Override
        public Map<String, CardLookupResult> fetchCardStatesByIds(EffectiveConfig config, List<String> cardIds) {
            return cardStates.entrySet().stream()
                    .filter(entry -> cardIds.contains(entry.getKey()))
                    .collect(toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        @Override
        public Card prepareForDispatch(EffectiveConfig config, Card card) {
            preparedIdentifiers.add(card.identifier());
            Card prepared = preparedCards.getOrDefault(card.id(), inProgress(card));
            setCardState(prepared);
            return prepared;
        }

        @Override
        public void releaseFromDispatch(EffectiveConfig config, Card card, Card dispatchSource) {
            releasedIdentifiers.add(card.identifier());
            setCardState(TestCards.cardWithLabels(card.id(), card.identifier(), dispatchSource.state(), card.labels()));
        }
    }
}
