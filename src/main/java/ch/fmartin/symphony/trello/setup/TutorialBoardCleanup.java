package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.setup.TutorialTrello.Board;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/// Settles the temporary tutorial board exactly once: archived or kept.
///
/// The tutorial thread settles the board when the walkthrough ends or fails. A shutdown hook
/// settles it when the user presses Ctrl+C. Both can run at the same time during JVM shutdown, so
/// the first caller claims the board before it calls Trello and later callers wait for that request to finish.
/// That keeps a "keep the board" answer from being overridden and avoids a second archive request.
/// A hook that runs while the board request waits for Trello's answer waits for that answer, so
/// the board Trello returns is still archived.
final class TutorialBoardCleanup {
    private final Consumer<Board> archiver;
    private final Duration creationAnswerWait;
    private final AtomicReference<Board> board = new AtomicReference<>();
    private final AtomicReference<Settlement> settlement = new AtomicReference<>(Settlement.OPEN);
    private final CountDownLatch creationAnswered = new CountDownLatch(1);
    private final CountDownLatch archiveFinished = new CountDownLatch(1);
    private volatile boolean creationStarted;

    /// `creationAnswerWait` is the longest a settlement waits for the board request to be answered.
    TutorialBoardCleanup(Consumer<Board> archiver, Duration creationAnswerWait) {
        this.archiver = archiver;
        this.creationAnswerWait = creationAnswerWait;
    }

    /// Sends the board request and tracks the board that Trello returns.
    Board create(Supplier<Board> request) {
        creationStarted = true;
        try {
            Board created = request.get();
            board.set(created);
            return created;
        } finally {
            creationAnswered.countDown();
        }
    }

    /// Whether the board request was sent, so Trello may have created a board.
    boolean creationStarted() {
        return creationStarted;
    }

    boolean creationPending() {
        return creationStarted && creationAnswered.getCount() != 0;
    }

    /// The board Trello returned, after waiting for a board request that is still in flight. Empty
    /// when the request failed or got no answer within the wait.
    Optional<Board> createdBoard() {
        return Optional.ofNullable(awaitCreatedBoard());
    }

    /// Archives the tracked board unless it was already archived or kept. Returns whether this
    /// call archived it.
    boolean archive() {
        Board tracked = awaitCreatedBoard();
        if (tracked == null) {
            return false;
        }
        if (!settlement.compareAndSet(Settlement.OPEN, Settlement.ARCHIVED)) {
            if (settlement.get() == Settlement.ARCHIVED) {
                awaitAnswer(archiveFinished);
            }
            return false;
        }
        try {
            archiver.accept(tracked);
            return true;
        } finally {
            archiveFinished.countDown();
        }
    }

    /// Keeps the tracked board. Returns whether this call settled it, so a board is reported as kept
    /// only once.
    boolean keep() {
        return settlement.compareAndSet(Settlement.OPEN, Settlement.KEPT);
    }

    private @Nullable Board awaitCreatedBoard() {
        boolean answered = !creationStarted || awaitAnswer(creationAnswered);
        return answered ? board.get() : null;
    }

    /// Returns false when no answer arrived in time. The caller then reports that the board may
    /// exist in Trello.
    private boolean awaitAnswer(CountDownLatch answer) {
        try {
            return answer.await(creationAnswerWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private enum Settlement {
        OPEN,
        ARCHIVED,
        KEPT
    }
}
