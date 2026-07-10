package ch.fmartin.symphony.trello.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.orchestrator.RuntimeSnapshot;
import ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestrator;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.common.mapper.TypeRef;
import io.restassured.response.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
@QuarkusTest
final class StateApiHttpContractTest {

    @Test
    void stateEndpointSerializesAnIdleDispatchPauseAsNull() {
        // given
        SymphonyOrchestrator orchestrator = mock();
        RuntimeSnapshot active = snapshotWithRunningAndRetryingCards();
        when(orchestrator.snapshot())
                .thenReturn(new RuntimeSnapshot(
                        active.generatedAt(),
                        active.counts(),
                        active.routing(),
                        active.running(),
                        active.retrying(),
                        active.codexTotals(),
                        null,
                        active.rateLimits()));
        QuarkusMock.installMockForType(orchestrator, SymphonyOrchestrator.class);

        // when
        Response response = given().get("/api/v1/state");

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> payload = response.as(new TypeRef<>() {});
        assertThat(payload).containsEntry("dispatch_pause", null);
    }

    @Test
    void stateEndpointSerializesSnapshotRowsWithSpecSnakeCaseJsonKeys() {
        // given
        SymphonyOrchestrator orchestrator = mock();
        when(orchestrator.snapshot()).thenReturn(snapshotWithRunningAndRetryingCards());
        QuarkusMock.installMockForType(orchestrator, SymphonyOrchestrator.class);

        // when
        Response response = given().get("/api/v1/state");

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> payload = response.as(new TypeRef<>() {});
        assertThat(payload)
                .containsKeys(
                        "generated_at",
                        "counts",
                        "routing",
                        "running",
                        "retrying",
                        "codex_totals",
                        "dispatch_pause",
                        "rate_limits")
                .doesNotContainKeys("generatedAt", "codexTotals", "dispatchPause", "rateLimits");

        Map<String, Object> routing = map(payload.get("routing"));
        assertThat(routing)
                .containsKeys("active_lists", "terminal_lists", "handoff_lists")
                .doesNotContainKeys("activeLists", "terminalLists", "handoffLists");

        Map<String, Object> running = firstMap(payload.get("running"));
        assertThat(running)
                .containsKeys(
                        "card_id",
                        "card_identifier",
                        "card_url",
                        "state",
                        "session_id",
                        "turn_count",
                        "last_event",
                        "last_message",
                        "started_at",
                        "last_event_at",
                        "tokens")
                .doesNotContainKeys(
                        "cardId",
                        "cardIdentifier",
                        "cardUrl",
                        "sessionId",
                        "turnCount",
                        "lastEvent",
                        "lastMessage",
                        "startedAt",
                        "lastEventAt");
        assertThat(running)
                .containsEntry("card_id", "000000000000000000000101")
                .containsEntry("card_identifier", "TRELLO-aBcDeFgH")
                .containsEntry("card_url", "https://trello.com/c/SYNTH001")
                .containsEntry("session_id", "thread-1-turn-1")
                .containsEntry("turn_count", 7)
                .containsEntry("last_event", "turn_completed");
        assertThat(map(running.get("tokens")))
                .containsKeys("input_tokens", "output_tokens", "total_tokens")
                .doesNotContainKeys("inputTokens", "outputTokens", "totalTokens");

        Map<String, Object> retrying = firstMap(payload.get("retrying"));
        assertThat(retrying)
                .containsKeys("card_id", "card_identifier", "card_url", "attempt", "due_at", "error")
                .doesNotContainKeys("cardId", "cardIdentifier", "cardUrl", "dueAt");
        assertThat(retrying)
                .containsEntry("card_id", "000000000000000000000202")
                .containsEntry("card_identifier", "TRELLO-zYxWvUtS")
                .containsEntry("card_url", "https://trello.com/c/SYNTH002")
                .containsEntry("attempt", 3)
                .containsEntry("due_at", "2026-02-24T20:16:00Z");

        Map<String, Object> codexTotals = map(payload.get("codex_totals"));
        assertThat(codexTotals)
                .containsKeys("input_tokens", "output_tokens", "total_tokens", "seconds_running")
                .doesNotContainKeys("inputTokens", "outputTokens", "totalTokens", "secondsRunning");

        assertThat(map(payload.get("dispatch_pause")))
                .containsOnlyKeys("code", "detected", "until")
                .containsEntry("code", "CODEX_USAGE_LIMIT")
                .containsEntry("detected", "2026-02-24T20:15:31Z")
                .containsEntry("until", "2026-02-24T21:15:30Z");

        Map<String, Object> rateLimits = map(payload.get("rate_limits"));
        assertThat(rateLimits)
                .containsEntry("limitType", "tokens")
                .containsEntry("remainingRequests", 42)
                .doesNotContainKeys("limit_type", "remaining_requests");
    }

    private static RuntimeSnapshot snapshotWithRunningAndRetryingCards() {
        return new RuntimeSnapshot(
                Instant.parse("2026-02-24T20:15:30Z"),
                new RuntimeSnapshot.Counts(1, 1),
                new RuntimeSnapshot.Routing(List.of("Ready for Codex"), List.of("Done"), List.of("human review")),
                List.of(new RuntimeSnapshot.RunningRow(
                        "000000000000000000000101",
                        "TRELLO-aBcDeFgH",
                        "https://trello.com/c/SYNTH001",
                        "In Progress",
                        "thread-1-turn-1",
                        7,
                        "turn_completed",
                        "",
                        Instant.parse("2026-02-24T20:10:12Z"),
                        Instant.parse("2026-02-24T20:14:59Z"),
                        Map.of("input_tokens", 1200L, "output_tokens", 800L, "total_tokens", 2000L))),
                List.of(new RuntimeSnapshot.RetryRow(
                        "000000000000000000000202",
                        "TRELLO-zYxWvUtS",
                        "https://trello.com/c/SYNTH002",
                        3,
                        Instant.parse("2026-02-24T20:16:00Z"),
                        "no available orchestrator slots")),
                new RuntimeSnapshot.TokenTotals(5000, 2400, 7400, 1834.2),
                new RuntimeSnapshot.DispatchPause(
                        "CODEX_USAGE_LIMIT",
                        Instant.parse("2026-02-24T20:15:31Z"),
                        Instant.parse("2026-02-24T21:15:30Z")),
                codexRateLimitsPayload());
    }

    private static ObjectNode codexRateLimitsPayload() {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("limitType", "tokens");
        payload.put("remainingRequests", 42);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstMap(Object value) {
        return ((List<Map<String, Object>>) value).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
