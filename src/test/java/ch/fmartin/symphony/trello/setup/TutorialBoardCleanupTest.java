package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.setup.TutorialTrello.Board;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class TutorialBoardCleanupTest {
    private static final Board TUTORIAL_BOARD =
            new Board("000000000000000000000001", "https://trello.com/b/SYNTH001/synthetic-board");
    private static final Duration LATCH_WAIT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(5);

    @Test
    void ctrlCDuringAnArchiveInProgressDoesNotSendASecondArchive() throws Exception {
        // given
        var archiveStarted = new CountDownLatch(1);
        var finishArchive = new CountDownLatch(1);
        List<Board> archived = new CopyOnWriteArrayList<>();
        var cleanup = new TutorialBoardCleanup(
                board -> {
                    archived.add(board);
                    if (archived.size() == 1) {
                        archiveStarted.countDown();
                        await(finishArchive);
                    }
                },
                LATCH_WAIT);
        cleanup.create(() -> TUTORIAL_BOARD);
        CompletableFuture<Boolean> tutorialThread = CompletableFuture.supplyAsync(cleanup::archive);
        await(archiveStarted);

        // when
        boolean shutdownHookArchived = cleanup.archive();
        finishArchive.countDown();

        // then
        assertThat(shutdownHookArchived)
                .as("the hook sees that the tutorial thread already claimed the board")
                .isFalse();
        assertThat(tutorialThread.get(LATCH_WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .as("the tutorial thread archived the board")
                .isTrue();
        assertThat(archived).containsExactly(TUTORIAL_BOARD);
    }

    @Test
    void keptBoardIsNeverArchivedLater() {
        // given
        List<Board> archived = new CopyOnWriteArrayList<>();
        var cleanup = new TutorialBoardCleanup(archived::add, LATCH_WAIT);
        cleanup.create(() -> TUTORIAL_BOARD);

        // when
        boolean kept = cleanup.keep();
        boolean archivedAfterKeep = cleanup.archive();

        // then
        assertThat(kept).as("the first settlement keeps the board").isTrue();
        assertThat(archivedAfterKeep)
                .as("a later Ctrl+C must not archive a kept board")
                .isFalse();
        assertThat(archived).isEmpty();
    }

    @Test
    void nothingIsArchivedBeforeTheBoardExists() {
        // given
        List<Board> archived = new CopyOnWriteArrayList<>();
        var cleanup = new TutorialBoardCleanup(archived::add, LATCH_WAIT);

        // when
        boolean archivedWithoutBoard = cleanup.archive();
        cleanup.create(() -> TUTORIAL_BOARD);
        boolean archivedWithBoard = cleanup.archive();

        // then
        assertThat(archivedWithoutBoard).as("there is no board to archive yet").isFalse();
        assertThat(archivedWithBoard).as("the board is archived once it exists").isTrue();
        assertThat(archived).containsExactly(TUTORIAL_BOARD);
    }

    @Test
    void ctrlCWhileTheBoardRequestWaitsForTrelloArchivesTheBoardTrelloReturns() throws Exception {
        // given
        var boardRequestSent = new CountDownLatch(1);
        var trelloAnswers = new CountDownLatch(1);
        List<Board> archived = new CopyOnWriteArrayList<>();
        var cleanup = new TutorialBoardCleanup(archived::add, LATCH_WAIT);
        CompletableFuture<Board> tutorialThread = CompletableFuture.supplyAsync(() -> cleanup.create(() -> {
            boardRequestSent.countDown();
            await(trelloAnswers);
            return TUTORIAL_BOARD;
        }));
        await(boardRequestSent);

        // when
        var shutdownHookArchived = new CompletableFuture<Boolean>();
        Thread shutdownHook = Thread.ofPlatform().start(() -> shutdownHookArchived.complete(cleanup.archive()));
        awaitBlockedOrFinished(shutdownHook);
        trelloAnswers.countDown();

        // then
        assertThat(shutdownHookArchived.get(LATCH_WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .as("the hook waits for the board request and archives the board Trello returns")
                .isTrue();
        assertThat(tutorialThread.get(LATCH_WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isEqualTo(TUTORIAL_BOARD);
        assertThat(archived).containsExactly(TUTORIAL_BOARD);
    }

    @Test
    void failedBoardRequestLeavesNothingToArchive() {
        // given
        List<Board> archived = new CopyOnWriteArrayList<>();
        var cleanup = new TutorialBoardCleanup(archived::add, LATCH_WAIT);
        Throwable failure = catchThrowable(() -> cleanup.create(() -> {
            throw new TrelloBoardSetupException("trello_write_outcome_unknown", "no answer");
        }));

        // when
        boolean archivedAfterFailure = cleanup.archive();

        // then
        assertThat(failure).isInstanceOf(TrelloBoardSetupException.class);
        assertThat(cleanup.creationStarted())
                .as("the request was sent, so Trello may have created the board")
                .isTrue();
        assertThat(cleanup.createdBoard()).isEmpty();
        assertThat(archivedAfterFailure)
                .as("no board came back, so there is nothing to archive")
                .isFalse();
        assertThat(archived).isEmpty();
    }

    /// Waits until the hook thread blocks or ends, so Trello answers only after the hook looked for
    /// the board.
    private static void awaitBlockedOrFinished(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + LATCH_WAIT.toNanos();
        while (thread.getState() == Thread.State.NEW || thread.getState() == Thread.State.RUNNABLE) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for the hook thread to block or finish");
            }
            Thread.sleep(POLL_INTERVAL);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(LATCH_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Timed out waiting for the archive to start or finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }
}
