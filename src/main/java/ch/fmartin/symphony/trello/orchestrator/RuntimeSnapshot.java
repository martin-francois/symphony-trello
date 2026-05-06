package ch.fmartin.symphony.trello.orchestrator;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RuntimeSnapshot(
        Instant generatedAt,
        Counts counts,
        Routing routing,
        List<RunningRow> running,
        List<RetryRow> retrying,
        TokenTotals codexTotals,
        Object rateLimits) {

    public record Counts(int running, int retrying) {}

    public record Routing(List<String> activeLists, List<String> terminalLists, List<String> handoffLists) {}

    public record RunningRow(
            String cardId,
            String cardIdentifier,
            String state,
            String sessionId,
            int turnCount,
            String lastEvent,
            String lastMessage,
            Instant startedAt,
            Instant lastEventAt,
            Map<String, Long> tokens) {}

    public record RetryRow(String cardId, String cardIdentifier, int attempt, Instant dueAt, String error) {}

    public record TokenTotals(long inputTokens, long outputTokens, long totalTokens, double secondsRunning) {}
}
