package ch.fmartin.symphony.trello.orchestrator;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import java.time.Instant;
import java.util.concurrent.Future;

final class RunningEntry {
    final String cardId;
    final String workerIdentity;
    final Instant startedAt;
    final Integer retryAttempt;
    final Card dispatchSource;
    final EffectiveConfig launchConfig;
    final TrackerTarget launchTarget;
    final String launchCommand;
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
    volatile Future<?> workerTask;
    int turnCount;

    RunningEntry(
            Card card,
            Card dispatchSource,
            String workerIdentity,
            Integer retryAttempt,
            Instant startedAt,
            EffectiveConfig launchConfig) {
        this.card = card;
        this.dispatchSource = dispatchSource;
        this.cardId = card.id();
        this.workerIdentity = workerIdentity;
        this.retryAttempt = retryAttempt;
        this.startedAt = startedAt;
        this.launchConfig = launchConfig;
        this.launchTarget = TrackerTarget.from(launchConfig);
        this.launchCommand = launchConfig.codex().command();
        this.lastEventAt = this.startedAt;
    }

    String identifier() {
        return card.identifier();
    }
}
