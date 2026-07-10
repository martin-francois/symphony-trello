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
        DispatchPause dispatchPause,
        Object rateLimits) {
    public RuntimeSnapshot {
        running = List.copyOf(running);
        retrying = List.copyOf(retrying);
    }

    public record Counts(int running, int retrying) {}

    public record Routing(List<String> activeLists, List<String> terminalLists, List<String> handoffLists) {
        public Routing {
            activeLists = List.copyOf(activeLists);
            terminalLists = List.copyOf(terminalLists);
            handoffLists = List.copyOf(handoffLists);
        }
    }

    public record RunningRow(
            String cardId,
            String cardIdentifier,
            String cardUrl,
            String state,
            String sessionId,
            int turnCount,
            String lastEvent,
            String lastMessage,
            Instant startedAt,
            Instant lastEventAt,
            Map<String, Long> tokens) {
        public RunningRow {
            tokens = tokens == null ? null : Map.copyOf(tokens);
        }
    }

    public record RetryRow(
            String cardId, String cardIdentifier, String cardUrl, int attempt, Instant dueAt, String error) {}

    public record TokenTotals(long inputTokens, long outputTokens, long totalTokens, double secondsRunning) {}

    public record DispatchPause(String code, Instant detected, Instant until) {}
}
