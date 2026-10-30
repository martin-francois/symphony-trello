package ch.fmartin.symphony.trello.orchestrator;

import ch.fmartin.symphony.trello.agent.CardHandoffMove;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.tracker.TrackerTarget;
import java.time.Instant;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

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
    ScheduledFuture<?> handoffTimer;
    CardHandoffMove handoffMove;
    Instant handoffDeadline;
    boolean handoffSucceeded;
    final AtomicBoolean handoffRunnerCancellationStarted = new AtomicBoolean();
    /// One atomic orders the handoff deadline's claim against every removal from the
    /// orchestrator's running map and against a failed move that ends the handoff protection:
    /// whichever comes first wins. The deadline claims without the operation lock (ADR 0119), so
    /// separate fields read one after the other could not give that order.
    private final AtomicReference<Lifecycle> lifecycle = new AtomicReference<>(Lifecycle.RUNNING);

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

    boolean handoffCancellationRequested() {
        return lifecycle.get().handoffCancelledAt() != null;
    }

    /// When an expired handoff deadline claimed this entry, or `null` while none did; its runtime
    /// stops there.
    @Nullable
    Instant handoffCancelledAt() {
        return lifecycle.get().handoffCancelledAt();
    }

    /// Succeeds once, and only while the entry is running, its handoff protection holds and no
    /// deadline claimed it.
    boolean claimHandoffCancellation(Instant cancelledAt) {
        return lifecycle
                .getAndUpdate(state -> state.claimable() ? state.claimedAt(cancelledAt) : state)
                .claimable();
    }

    /// Call when a handoff move starts protecting the current turn again.
    void protectHandoff() {
        lifecycle.updateAndGet(state -> state.withHandoffProtected(true));
    }

    /// Call when a failed move leaves no handoff to protect. A deadline task that already started
    /// can then no longer claim the entry.
    void endHandoffProtection() {
        lifecycle.updateAndGet(state -> state.withHandoffProtected(false));
    }

    /// Call when the entry leaves the running map. A later deadline can no longer claim it.
    void markRemovedFromRunning() {
        lifecycle.updateAndGet(Lifecycle::removed);
    }

    /// Marks the entry removed from the running map and returns `true`, or returns `false` and
    /// leaves it alone when a deadline claimed it first.
    boolean markRemovedUnlessHandoffClaimed() {
        return lifecycle
                        .getAndUpdate(state -> state.handoffCancelledAt() == null ? state.removed() : state)
                        .handoffCancelledAt()
                == null;
    }

    private record Lifecycle(
            boolean removedFromRunning, boolean handoffProtected, @Nullable Instant handoffCancelledAt) {
        static final Lifecycle RUNNING = new Lifecycle(false, true, null);

        boolean claimable() {
            return !removedFromRunning && handoffProtected && handoffCancelledAt == null;
        }

        Lifecycle claimedAt(Instant cancelledAt) {
            return new Lifecycle(false, true, cancelledAt);
        }

        Lifecycle withHandoffProtected(boolean value) {
            return new Lifecycle(removedFromRunning, value, handoffCancelledAt);
        }

        Lifecycle removed() {
            return new Lifecycle(true, handoffProtected, handoffCancelledAt);
        }
    }
}
