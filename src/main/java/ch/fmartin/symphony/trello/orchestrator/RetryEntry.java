package ch.fmartin.symphony.trello.orchestrator;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

record RetryEntry(
        String cardId,
        String identifier,
        String cardUrl,
        int attempt,
        Instant dueAt,
        ScheduledFuture<?> timer,
        String error) {}
