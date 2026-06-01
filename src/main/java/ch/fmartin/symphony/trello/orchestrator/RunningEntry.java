package ch.fmartin.symphony.trello.orchestrator;

import ch.fmartin.symphony.trello.domain.Card;
import java.time.Instant;

final class RunningEntry {
    final String cardId;
    final String workerIdentity;
    final Instant startedAt;
    final Integer retryAttempt;
    volatile Card card;
    volatile String sessionId;
    volatile String threadId;
    volatile String turnId;
    volatile String lastEvent;
    volatile String lastMessage;
    volatile Instant lastEventAt;
    volatile long inputTokens;
    volatile long outputTokens;
    volatile long totalTokens;
    volatile long lastReportedInputTokens;
    volatile long lastReportedOutputTokens;
    volatile long lastReportedTotalTokens;
    int turnCount;

    RunningEntry(Card card, String workerIdentity, Integer retryAttempt, Instant startedAt) {
        this.card = card;
        this.cardId = card.id();
        this.workerIdentity = workerIdentity;
        this.retryAttempt = retryAttempt;
        this.startedAt = startedAt;
        this.lastEventAt = this.startedAt;
    }

    String identifier() {
        return card.identifier();
    }
}
