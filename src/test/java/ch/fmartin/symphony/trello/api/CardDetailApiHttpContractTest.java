package ch.fmartin.symphony.trello.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.fmartin.symphony.trello.orchestrator.CardDebugDetails;
import ch.fmartin.symphony.trello.orchestrator.RuntimeSnapshot;
import ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestrator;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.common.mapper.TypeRef;
import io.restassured.response.Response;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
@QuarkusTest
final class CardDetailApiHttpContractTest {
    @Test
    void cardDetailEndpointSerializesDebugDetailsWithSpecSnakeCaseJsonKeys() {
        // given
        SymphonyOrchestrator orchestrator = mock();
        when(orchestrator.cardIdentifierPrefix()).thenReturn("TRELLO");
        when(orchestrator.cardDetails("TRELLO-aBcDeFgH")).thenReturn(Optional.of(cardDetails()));
        QuarkusMock.installMockForType(orchestrator, SymphonyOrchestrator.class);

        // when
        Response response = given().get("/api/v1/TRELLO-aBcDeFgH");

        // then
        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> payload = response.as(new TypeRef<>() {});
        assertThat(payload)
                .containsKeys(
                        "card_identifier",
                        "card_id",
                        "status",
                        "workspace",
                        "attempts",
                        "running",
                        "retry",
                        "logs",
                        "recent_events",
                        "last_error",
                        "tracked")
                .doesNotContainKeys("cardIdentifier", "cardId", "recentEvents", "lastError");
        assertThat(payload)
                .containsEntry("card_identifier", "TRELLO-aBcDeFgH")
                .containsEntry("card_id", "000000000000000000000101")
                .containsEntry("last_error", "latest failure");

        Map<String, Object> workspace = map(payload.get("workspace"));
        assertThat(workspace).containsEntry("path", "workspaces/TRELLO-aBcDeFgH");

        Map<String, Object> attempts = map(payload.get("attempts"));
        assertThat(attempts)
                .containsEntry("restart_count", 2)
                .containsEntry("current_retry_attempt", 3)
                .doesNotContainKeys("restartCount", "currentRetryAttempt");

        Map<String, Object> running = map(payload.get("running"));
        assertThat(running)
                .containsKeys(
                        "card_id",
                        "card_identifier",
                        "card_url",
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

        Map<String, Object> retry = map(payload.get("retry"));
        assertThat(retry)
                .containsKeys("card_id", "card_identifier", "card_url", "due_at")
                .doesNotContainKeys("cardId", "cardIdentifier", "cardUrl", "dueAt");

        Map<String, Object> logs = map(payload.get("logs"));
        assertThat(logs).containsKey("codex_session_logs").doesNotContainKeys("codexSessionLogs");

        Map<String, Object> event = firstMap(payload.get("recent_events"));
        assertThat(event)
                .containsEntry("event", "turn_completed")
                .containsEntry("message", "done")
                .containsKey("at");
    }

    private static CardDebugDetails cardDetails() {
        return new CardDebugDetails(
                "TRELLO-aBcDeFgH",
                "000000000000000000000101",
                "running",
                new CardDebugDetails.WorkspaceInfo(Path.of("workspaces/TRELLO-aBcDeFgH")),
                new CardDebugDetails.AttemptInfo(2, 3),
                runningRow(),
                retryRow(),
                new CardDebugDetails.LogInfo(List.of(Map.of("path", "session.log"))),
                List.of(new CardDebugDetails.EventInfo(
                        Instant.parse("2026-02-24T20:14:59Z"), "turn_completed", "done")),
                "latest failure",
                Map.of("customField", "left unchanged"));
    }

    private static RuntimeSnapshot.RunningRow runningRow() {
        return new RuntimeSnapshot.RunningRow(
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
                Map.of("input_tokens", 1200L, "output_tokens", 800L, "total_tokens", 2000L));
    }

    private static RuntimeSnapshot.RetryRow retryRow() {
        return new RuntimeSnapshot.RetryRow(
                "000000000000000000000202",
                "TRELLO-zYxWvUtS",
                "https://trello.com/c/SYNTH002",
                3,
                Instant.parse("2026-02-24T20:16:00Z"),
                "no available orchestrator slots");
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
