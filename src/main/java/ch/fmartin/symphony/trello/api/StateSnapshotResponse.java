package ch.fmartin.symphony.trello.api;

import ch.fmartin.symphony.trello.orchestrator.RuntimeSnapshot;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

record StateSnapshotResponse(
        @JsonProperty("generated_at") Instant generatedAt,
        Counts counts,
        Routing routing,
        List<RunningRow> running,
        List<RetryRow> retrying,
        @JsonProperty("codex_totals") TokenTotals codexTotals,
        @JsonProperty("rate_limits") Object rateLimits) {
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

    record Counts(int running, int retrying) {
        static Counts from(RuntimeSnapshot.Counts counts) {
            return new Counts(counts.running(), counts.retrying());
        }
    }

    record Routing(
            @JsonProperty("active_lists") List<String> activeLists,
            @JsonProperty("terminal_lists") List<String> terminalLists,
            @JsonProperty("handoff_lists") List<String> handoffLists) {
        static Routing from(RuntimeSnapshot.Routing routing) {
            return new Routing(routing.activeLists(), routing.terminalLists(), routing.handoffLists());
        }
    }

    record RunningRow(
            @JsonProperty("card_id") String cardId,
            @JsonProperty("card_identifier") String cardIdentifier,
            @JsonProperty("card_url") String cardUrl,
            String state,
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("turn_count") int turnCount,
            @JsonProperty("last_event") String lastEvent,
            @JsonProperty("last_message") String lastMessage,
            @JsonProperty("started_at") Instant startedAt,
            @JsonProperty("last_event_at") Instant lastEventAt,
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

    record RetryRow(
            @JsonProperty("card_id") String cardId,
            @JsonProperty("card_identifier") String cardIdentifier,
            @JsonProperty("card_url") String cardUrl,
            int attempt,
            @JsonProperty("due_at") Instant dueAt,
            String error) {
        static RetryRow from(RuntimeSnapshot.RetryRow row) {
            return new RetryRow(
                    row.cardId(), row.cardIdentifier(), row.cardUrl(), row.attempt(), row.dueAt(), row.error());
        }
    }

    record TokenTotals(
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens,
            @JsonProperty("total_tokens") long totalTokens,
            @JsonProperty("seconds_running") double secondsRunning) {
        static TokenTotals from(RuntimeSnapshot.TokenTotals totals) {
            return new TokenTotals(
                    totals.inputTokens(), totals.outputTokens(), totals.totalTokens(), totals.secondsRunning());
        }
    }
}
