package ch.fmartin.symphony.trello.orchestrator;

import static ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestratorTestSupport.waitUntil;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.boardJson;
import static ch.fmartin.symphony.trello.testsupport.FakeTrelloServer.respond;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import ch.fmartin.symphony.trello.testsupport.FakeTrelloServer;
import ch.fmartin.symphony.trello.tracker.TrelloClient;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import ch.fmartin.symphony.trello.workspace.HookRunner;
import ch.fmartin.symphony.trello.workspace.WorkspaceManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Runs the real Trello client against a fake Trello server that answers `429`, so the path from
/// the HTTP response to the next tick's schedule is covered without stubbing the pressure.
final class TrelloRateLimitPollingIntegrationTest {
    private static final Duration CONFIGURED = Duration.ofSeconds(1);
    private static final Duration RETRY_AFTER = Duration.ofSeconds(7);
    /// Time the test itself may spend between the 429 and reading the schedule.
    private static final Duration TEST_SLACK = Duration.ofSeconds(2);

    private FakeTrelloServer trello;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws Exception {
        trello = new FakeTrelloServer()
                .on("/1/boards/board-1", exchange -> respond(exchange, boardJson("board-1", "Board", false)))
                .on("/1/boards/board-1/lists", exchange -> {
                    exchange.getResponseHeaders().add("Retry-After", String.valueOf(RETRY_AFTER.toSeconds()));
                    respond(exchange, 429, "{}");
                })
                .startEmpty();
    }

    @AfterEach
    void stopServer() {
        trello.stop();
    }

    @Test
    void trelloRateLimitSlowsTheWorkerAndTheNextTickWaitsForRetryAfter() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  endpoint: %s
                  api_key: key
                  api_token: token
                  board_id: board-1
                  active_states: [Todo]
                  max_api_retries: 0
                workspace:
                  root: work
                polling:
                  interval_ms: %d
                codex:
                  command: fake
                ---
                {{ card.title }}
                """
                        .formatted(trello.endpoint(), CONFIGURED.toMillis()));
        var workspaces = new WorkspaceManager(new HookRunner());
        var orchestrator = new SymphonyOrchestrator(
                new WorkflowLoader(),
                new ConfigResolver(),
                new TrelloClient(new ObjectMapper()),
                mock(),
                new PromptRenderer(),
                workspaces);
        orchestrator.workflowPath = workflow;

        // when
        orchestrator.start();
        waitUntil(() -> orchestrator.snapshot().polling().slowdownReason().isPresent());
        RuntimeSnapshot.Polling polling = orchestrator.snapshot().polling();
        Duration nextTickDelay = orchestrator.scheduledTickDelayForTests();
        orchestrator.stop();

        // then
        assertThat(polling.configuredInterval()).isEqualTo(CONFIGURED);
        assertThat(polling.effectiveInterval())
                .isEqualTo(CONFIGURED.multipliedBy(AdaptivePollInterval.SLOWDOWN_FACTOR));
        assertThat(polling.lastRateLimitedAt()).isPresent();
        assertThat(nextTickDelay)
                .as("with no retries left, the next tick itself waits out Trello's Retry-After of %s", RETRY_AFTER)
                .isGreaterThan(RETRY_AFTER.minus(TEST_SLACK));
    }
}
