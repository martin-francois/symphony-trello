package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.setup.TutorialTrello.Board;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/// Settles the temporary tutorial board exactly once: archived or kept.
///
/// The tutorial thread settles the board when the walkthrough ends or fails. A shutdown hook
/// settles it when the user presses Ctrl+C. Both can run at the same time during JVM shutdown, so
/// the first caller claims the board before it calls Trello and every later caller does nothing.
/// That keeps a "keep the board" answer from being overridden and avoids a second archive request.
final class TutorialBoardCleanup {
    private final Consumer<Board> archiver;
    private final AtomicReference<Board> board = new AtomicReference<>();
    private final AtomicReference<Settlement> settlement = new AtomicReference<>(Settlement.OPEN);

    TutorialBoardCleanup(Consumer<Board> archiver) {
        this.archiver = archiver;
    }

    void track(Board createdBoard) {
        board.set(createdBoard);
    }

    /// Archives the tracked board unless it was already archived or kept. Returns whether this
    /// call archived it.
    boolean archive() {
        Board tracked = board.get();
        if (tracked == null || !settlement.compareAndSet(Settlement.OPEN, Settlement.ARCHIVED)) {
            return false;
        }
        archiver.accept(tracked);
        return true;
    }

    /// Keeps the tracked board. Returns whether this call settled it, so a board is reported as kept
    /// only once.
    boolean keep() {
        return settlement.compareAndSet(Settlement.OPEN, Settlement.KEPT);
    }

    private enum Settlement {
        OPEN,
        ARCHIVED,
        KEPT
    }
}
