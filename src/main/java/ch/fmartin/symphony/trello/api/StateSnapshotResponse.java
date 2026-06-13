package ch.fmartin.symphony.trello.api;

import ch.fmartin.symphony.trello.orchestrator.RuntimeSnapshot;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record StateSnapshotResponse(
        Instant generatedAt,
        Counts counts,
        Routing routing,
        List<RunningRow> running,
        List<RetryRow> retrying,
        TokenTotals codexTotals,
        Object rateLimits) {
    static StateSnapshotResponse from(RuntimeSnapshot snapshot) {
        return new StateSnapshotResponse(
                snapshot.generatedAt(),
                Counts.from(snapshot.counts()),
                Routing.from(snapshot.routing()),
                snapshot.running().stream().map(RunningRow::from).toList(),
                snapshot.retrying().stream().map(RetryRow::from).toList(),
                TokenTotals.from(snapshot.codexTotals()),
                snapshot.rateLimits());
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Counts(int running, int retrying) {
        static Counts from(RuntimeSnapshot.Counts counts) {
            return new Counts(counts.running(), counts.retrying());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Routing(List<String> activeLists, List<String> terminalLists, List<String> handoffLists) {
        static Routing from(RuntimeSnapshot.Routing routing) {
            return new Routing(routing.activeLists(), routing.terminalLists(), routing.handoffLists());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record RunningRow(
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
        static RunningRow from(RuntimeSnapshot.RunningRow row) {
            return new RunningRow(
                    row.cardId(),
                    row.cardIdentifier(),
                    row.cardUrl(),
                    row.state(),
                    row.sessionId(),
                    row.turnCount(),
                    row.lastEvent(),
                    row.lastMessage(),
                    row.startedAt(),
                    row.lastEventAt(),
                    row.tokens());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record RetryRow(String cardId, String cardIdentifier, String cardUrl, int attempt, Instant dueAt, String error) {
        static RetryRow from(RuntimeSnapshot.RetryRow row) {
            return new RetryRow(
                    row.cardId(), row.cardIdentifier(), row.cardUrl(), row.attempt(), row.dueAt(), row.error());
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record TokenTotals(long inputTokens, long outputTokens, long totalTokens, double secondsRunning) {
        static TokenTotals from(RuntimeSnapshot.TokenTotals totals) {
            return new TokenTotals(
                    totals.inputTokens(), totals.outputTokens(), totals.totalTokens(), totals.secondsRunning());
        }
    }
}
