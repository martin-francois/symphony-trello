package ch.fmartin.symphony.trello.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;

public record AgentEvent(
        String event,
        Instant timestamp,
        String workerIdentity,
        Long codexAppServerPid,
        String threadId,
        String turnId,
        String message,
        Map<String, Long> usage,
        JsonNode payload) {
    public AgentEvent {
        usage = usage == null ? null : Map.copyOf(usage);
    }
}
