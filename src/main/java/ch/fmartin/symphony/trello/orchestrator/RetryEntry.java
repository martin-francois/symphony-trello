package ch.fmartin.symphony.trello.orchestrator;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;

record RetryEntry(
        String cardId,
        String identifier,
        String cardUrl,
        int attempt,
        Instant dueAt,
        Instant naturalDueAt,
        String codexCommand,
        TrackerTarget trackerTarget,
        long generation,
        ScheduledFuture<?> timer,
        String error,
        boolean codexUsageLimit) {}
