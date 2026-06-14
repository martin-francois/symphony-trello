package ch.fmartin.symphony.trello.api;

import ch.fmartin.symphony.trello.orchestrator.CardDebugDetails;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record CardDebugDetailsResponse(
        String cardIdentifier,
        String cardId,
        String status,
        WorkspaceInfo workspace,
        AttemptInfo attempts,
        StateSnapshotResponse.RunningRow running,
        StateSnapshotResponse.RetryRow retry,
        LogInfo logs,
        List<EventInfo> recentEvents,
        String lastError,
        Map<String, Object> tracked) {
    static CardDebugDetailsResponse from(CardDebugDetails details) {
        return new CardDebugDetailsResponse(
                details.cardIdentifier(),
                details.cardId(),
                details.status(),
                WorkspaceInfo.from(details.workspace()),
                AttemptInfo.from(details.attempts()),
                details.running() == null ? null : StateSnapshotResponse.RunningRow.from(details.running()),
                details.retry() == null ? null : StateSnapshotResponse.RetryRow.from(details.retry()),
                LogInfo.from(details.logs()),
                details.recentEvents().stream().map(EventInfo::from).toList(),
                details.lastError(),
                details.tracked());
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record WorkspaceInfo(String path) {
        static WorkspaceInfo from(CardDebugDetails.WorkspaceInfo workspace) {
            return new WorkspaceInfo(workspace.path().toString());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record AttemptInfo(int restartCount, Integer currentRetryAttempt) {
        static AttemptInfo from(CardDebugDetails.AttemptInfo attempts) {
            return new AttemptInfo(attempts.restartCount(), attempts.currentRetryAttempt());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record LogInfo(List<Map<String, Object>> codexSessionLogs) {
        static LogInfo from(CardDebugDetails.LogInfo logs) {
            return new LogInfo(logs.codexSessionLogs());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record EventInfo(Instant at, String event, String message) {
        static EventInfo from(CardDebugDetails.EventInfo event) {
            return new EventInfo(event.at(), event.event(), event.message());
        }
    }
}
