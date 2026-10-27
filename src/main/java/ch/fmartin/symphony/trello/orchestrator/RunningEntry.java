package ch.fmartin.symphony.trello.orchestrator;

import ch.fmartin.symphony.trello.agent.CardHandoffMove;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.tracker.TrackerTarget;
import java.time.Instant;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

final class RunningEntry {
    final String cardId;
    final String workerIdentity;
    final Instant startedAt;
    final Integer retryAttempt;
    final Card dispatchSource;
    final EffectiveConfig launchConfig;
    final TrackerTarget launchTarget;
    final String launchCommand;
    Card card;
    String sessionId;
    String threadId;
    String turnId;
    String lastEvent;
    String lastMessage;
    Instant lastEventAt;
    long inputTokens;
    long outputTokens;
    long totalTokens;
    long lastReportedInputTokens;
    long lastReportedOutputTokens;
    long lastReportedTotalTokens;
    Future<?> workerTask;
    volatile boolean workerExited;
    volatile boolean workspaceReleased = true;
    boolean removeWorkspaceAfterExit;
    int turnCount;
    CardHandoffMove handoffMove;
    Instant handoffDeadline;
    ScheduledFuture<?> handoffTimer;
    boolean handoffSucceeded;
    Instant handoffCancelledAt;
    volatile boolean handoffCancellationRequested;
    final AtomicBoolean handoffRunnerCancellationStarted = new AtomicBoolean();

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
