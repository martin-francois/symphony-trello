package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void ctrlCDuringAnArchiveInProgressDoesNotSendASecondArchive() throws Exception {
        // given
        var archiveStarted = new CountDownLatch(1);
        var finishArchive = new CountDownLatch(1);
        List<Board> archived = new CopyOnWriteArrayList<>();
        var cleanup = new TutorialBoardCleanup(board -> {
            archived.add(board);
            if (archived.size() == 1) {
                archiveStarted.countDown();
                await(finishArchive);
            }
        });
        cleanup.track(TUTORIAL_BOARD);
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
        var cleanup = new TutorialBoardCleanup(archived::add);
        cleanup.track(TUTORIAL_BOARD);

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
        var cleanup = new TutorialBoardCleanup(archived::add);

        // when
        boolean archivedWithoutBoard = cleanup.archive();
        cleanup.track(TUTORIAL_BOARD);
        boolean archivedWithBoard = cleanup.archive();

        // then
        assertThat(archivedWithoutBoard).as("there is no board to archive yet").isFalse();
        assertThat(archivedWithBoard).as("the board is archived once it exists").isTrue();
        assertThat(archived).containsExactly(TUTORIAL_BOARD);
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
