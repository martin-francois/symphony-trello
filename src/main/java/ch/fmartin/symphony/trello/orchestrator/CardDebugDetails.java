package ch.fmartin.symphony.trello.orchestrator;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record CardDebugDetails(
        String cardIdentifier,
        String cardId,
        String status,
        WorkspaceInfo workspace,
        AttemptInfo attempts,
        RuntimeSnapshot.RunningRow running,
        RuntimeSnapshot.RetryRow retry,
        LogInfo logs,
        List<EventInfo> recentEvents,
        String lastError,
        Map<String, Object> tracked) {
    public CardDebugDetails {
        recentEvents = List.copyOf(recentEvents);
        tracked = Map.copyOf(tracked);
    }

    public record WorkspaceInfo(Path path) {}

    public record AttemptInfo(int restartCount, Integer currentRetryAttempt) {}

    public record LogInfo(List<Map<String, Object>> codexSessionLogs) {
        public LogInfo {
            codexSessionLogs = codexSessionLogs.stream().map(Map::copyOf).toList();
        }

        @Override
        public List<Map<String, Object>> codexSessionLogs() {
            return codexSessionLogs.stream().map(Map::copyOf).toList();
        }
    }

    public record EventInfo(Instant at, String event, String message) {}
}
