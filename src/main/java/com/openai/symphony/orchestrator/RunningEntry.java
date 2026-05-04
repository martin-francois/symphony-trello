package com.openai.symphony.orchestrator;

import com.openai.symphony.domain.Card;
import java.time.Instant;
import java.util.concurrent.Future;

final class RunningEntry {
    final String cardId;
    final String workerIdentity;
    final Instant startedAt;
    final Integer retryAttempt;
    volatile Card card;
    volatile Future<?> workerHandle;
    volatile String sessionId;
    volatile String threadId;
    volatile String turnId;
    volatile Long codexAppServerPid;
    volatile String lastEvent;
    volatile String lastMessage;
    volatile Instant lastEventAt;
    volatile long inputTokens;
    volatile long outputTokens;
    volatile long totalTokens;
    volatile long lastReportedInputTokens;
    volatile long lastReportedOutputTokens;
    volatile long lastReportedTotalTokens;
    volatile int turnCount;

    RunningEntry(Card card, String workerIdentity, Integer retryAttempt) {
        this.card = card;
        this.cardId = card.id();
        this.workerIdentity = workerIdentity;
        this.retryAttempt = retryAttempt;
        this.startedAt = Instant.now();
        this.lastEventAt = this.startedAt;
    }

    String identifier() {
        return card.identifier();
    }
}
