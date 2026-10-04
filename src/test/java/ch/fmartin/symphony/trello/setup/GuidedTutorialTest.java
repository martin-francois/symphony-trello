package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.fmartin.symphony.trello.setup.GuidedTutorial.Outcome;
import ch.fmartin.symphony.trello.setup.GuidedTutorial.Pacing;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.TrelloCredentials;
import ch.fmartin.symphony.trello.testsupport.StatefulFakeTrello;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class GuidedTutorialTest {
    /// Automatic checks wait a minute, so scripted answers always arrive first.
    private static final Pacing ANSWER_DRIVEN = new Pacing(Duration.ofMinutes(1), 3);
    /// Automatic checks run quickly, so a card move is detected without an answer.
    private static final Pacing FAST_POLLING = new Pacing(Duration.ofMillis(20), 250);
    private static final Pacing SHORT_WINDOW = new Pacing(Duration.ofMillis(10), 3);
    private static final String CLI = "symphony-trello";
    private static final String USER_BOARD = "Existing Queue";
    private static final String USER_CHANGE_REQUEST = "Please also print the date";

    private StatefulFakeTrello trello;
    private RecordingShutdownHooks hooks;
    private String userBoardId;

    @BeforeEach
    void startTrello() throws IOException {
        trello = new StatefulFakeTrello().start();
        userBoardId = trello.givenBoard(USER_BOARD);
        hooks = new RecordingShutdownHooks();
    }

    @AfterEach
    void stopTrello() {
        trello.close();
    }

    @Test
    void walksThroughTheTrelloOnlyFlowAndArchivesOnlyTheTutorialBoard() throws IOException {
        // given
        var terminal = new ScriptedTerminal(
                user(() -> move(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE), ""),
                answer(""),
                answer(""),
                user(this::requestChange, ""),
                answer(""),
                user(() -> move(TrelloBoardSetup.RECOMMENDED_DONE_STATE), ""),
                answer(""));

        // when
        Outcome outcome = run(terminal, ANSWER_DRIVEN, trelloOnly());

        // then
        assertThat(outcome).isEqualTo(Outcome.COMPLETED);
        assertThat(terminal.stdout())
                .containsSubsequence(
                        "Board created: \"" + GuidedTutorial.BOARD_NAME + "\"",
                        "What each Trello list means:",
                        "Step 1 of 3: queue the card",
                        "OK  The card is in \"Ready for Codex\"",
                        "OK  Moved the card to \"In Progress\"",
                        "OK  Added the Codex Workpad comment",
                        "OK  Moved the card to \"Human Review\"",
                        "Step 2 of 3: ask for a change",
                        "OK  The card is back in \"Ready for Codex\" with your new Trello comment",
                        "Symphony treats this as rework",
                        GuidedTutorial.CONCURRENCY_NOTE,
                        ProjectDocs.WORKFLOW_CONTRACT_URL,
                        "Step 3 of 3: accept the result",
                        "OK  The card is in \"Done\"",
                        "What GitHub pull request integration adds",
                        "  symphony-trello setup-local configure-github",
                        GuidedTutorial.CLEANUP_PROMPT,
                        "OK  Archived the temporary tutorial board")
                .doesNotContain(GuidedTutorial.MERGING_EXAMPLE);
        String tutorialBoardId = trello.boardIdNamed(GuidedTutorial.BOARD_NAME);
        assertThat(trello.listNames(tutorialBoardId))
                .containsExactlyElementsOf(TrelloBoardSetup.RECOMMENDED_NON_GITHUB_LISTS);
        String cardId = trello.onlyCardId(tutorialBoardId);
        assertThat(trello.cardName(cardId)).isEqualTo(GuidedTutorial.CARD_NAME);
        assertThat(trello.listOf(cardId)).isEqualTo(TrelloBoardSetup.RECOMMENDED_DONE_STATE);
        assertThat(trello.commentTexts(cardId))
                .satisfiesExactly(
                        workpad -> assertThat(workpad)
                                .startsWith("## Codex Workpad")
                                .contains(
                                        "No Codex work ran.",
                                        "Rework input: your Trello comment \"" + USER_CHANGE_REQUEST + "\""),
                        comment -> assertThat(comment).isEqualTo(USER_CHANGE_REQUEST));
        assertThat(trello.archived(tutorialBoardId))
                .as("the tutorial archives its own board")
                .isTrue();
        assertThat(trello.archived(userBoardId))
                .as("the tutorial never archives a board it did not create")
                .isFalse();
        assertThat(hooks.registered())
                .as("the Ctrl+C hook is removed after the run")
                .isFalse();
    }

    @Test
    void githubWalkthroughShowsThePullRequestLineAndFinishesThroughMerging() throws IOException {
        // given
        var handoffWorkpad = new AtomicReference<String>();
        var terminal = new ScriptedTerminal(
                user(() -> move(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE), ""),
                answer(""),
                user(() -> handoffWorkpad.set(workpad()), ""),
                user(this::requestChange, ""),
                answer(""),
                user(() -> move(TrelloBoardSetup.RECOMMENDED_MERGING_STATE), ""),
                answer(""));

        // when
        Outcome outcome = run(terminal, ANSWER_DRIVEN, withGithub());

        // then
        assertThat(outcome).isEqualTo(Outcome.COMPLETED);
        assertThat(terminal.stdout())
                .containsSubsequence(
                        "Look for the \"Pull request\" line in the workpad.",
                        "review and comment on either the pull request in GitHub or the Trello card",
                        "write review comments on the pull request in GitHub",
                        "With GitHub integration, it normally updates the existing pull request.",
                        GuidedTutorial.CONCURRENCY_NOTE,
                        "Step 3 of 3: approve the pull request with \"Merging\"",
                        GuidedTutorial.MERGING_EXAMPLE,
                        "OK  The card is in \"Merging\"",
                        "OK  Moved the card to \"Done\"",
                        "OK  Archived the temporary tutorial board")
                .doesNotContain("What GitHub pull request integration adds");
        assertThat(handoffWorkpad.get()).contains("Pull request: demo only, no pull request was created.");
        String tutorialBoardId = trello.boardIdNamed(GuidedTutorial.BOARD_NAME);
        assertThat(trello.listNames(tutorialBoardId)).containsExactlyElementsOf(TrelloBoardSetup.RECOMMENDED_LISTS);
        assertThat(trello.listOf(trello.onlyCardId(tutorialBoardId)))
                .isEqualTo(TrelloBoardSetup.RECOMMENDED_DONE_STATE);
        assertThat(workpad()).contains("Status: Merged", "nothing was merged");
    }

    @Test
    void detectsTheCardMoveByPollingWithoutAnAnswer() throws IOException {
        // given
        var cardReads = new AtomicInteger();
        trello.beforeCardRead(cardId -> {
            if (cardReads.incrementAndGet() == 3) {
                trello.moveCard(cardId, TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE);
            }
        });
        var terminal = new ScriptedTerminal(afterOutput(GuidedTutorial.CONTINUE_PROMPT, ""), answer("q"), answer(""));

        // when
        Outcome outcome = run(terminal, FAST_POLLING, trelloOnly());

        // then
        assertThat(outcome).isEqualTo(Outcome.STOPPED);
        assertThat(terminal.stdout())
                .containsSubsequence(
                        "The tutorial checks the board every 20 milliseconds for up to 5 seconds.",
                        GuidedTutorial.CHECK_PROMPT,
                        "OK  The card is in \"Ready for Codex\"",
                        "OK  Moved the card to \"In Progress\"",
                        "Stopped the tutorial.",
                        "OK  Archived the temporary tutorial board");
        assertThat(cardReads).hasValue(3);
    }

    @Test
    void wrongListGetsTheExpectedStateAndBoardUrl() throws IOException {
        // given
        var terminal = new ScriptedTerminal(
                user(() -> move(TrelloBoardSetup.RECOMMENDED_DONE_STATE), ""),
                user(() -> move(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE), ""),
                answer("q"),
                answer("n"));

        // when
        Outcome outcome = run(terminal, ANSWER_DRIVEN, trelloOnly());

        // then
        String tutorialBoardId = trello.boardIdNamed(GuidedTutorial.BOARD_NAME);
        assertThat(outcome).isEqualTo(Outcome.STOPPED);
        assertThat(terminal.stdout())
                .containsSubsequence(
                        "Not yet: the card is in \"Done\".",
                        "Expected: the card is in \"Ready for Codex\".",
                        "Board:",
                        "    https://trello.com/b/SYNTH002/synthetic-board",
                        "OK  The card is in \"Ready for Codex\"",
                        "The temporary tutorial board stays in Trello:",
                        "  https://trello.com/b/SYNTH002/synthetic-board",
                        "It is not connected to Symphony")
                .doesNotContain("Archived the temporary tutorial board");
        assertThat(trello.archived(tutorialBoardId))
                .as("answering n keeps the tutorial board")
                .isFalse();
    }

    @Test
    void reworkWithoutANewCommentGetsTheMissingCommentCorrection() throws IOException {
        // given
        var terminal = new ScriptedTerminal(
                answer("s"),
                answer(""),
                answer(""),
                user(() -> move(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE), ""),
                user(() -> trello.addComment(cardId(), USER_CHANGE_REQUEST), ""),
                answer("q"),
                answer(""));

        // when
        Outcome outcome = run(terminal, ANSWER_DRIVEN, trelloOnly());

        // then
        assertThat(outcome).isEqualTo(Outcome.STOPPED);
        assertThat(terminal.stdout())
                .containsSubsequence(
                        "Not yet: the card is in \"Ready for Codex\", but it has no new Trello comment.",
                        "Expected: the card is in \"Ready for Codex\" and has a new Trello comment that asks for a"
                                + " change.",
                        "https://trello.com/b/SYNTH002/synthetic-board",
                        "OK  The card is back in \"Ready for Codex\" with your new Trello comment");
        assertThat(workpad()).contains("Rework input: your Trello comment \"" + USER_CHANGE_REQUEST + "\"");
    }

    @Test
    void stopsAutomaticChecksAfterTheWindowAndChecksAgainOnRequest() throws IOException {
        // given
        var cardReads = new AtomicInteger();
        trello.beforeCardRead(cardId -> cardReads.incrementAndGet());
        var terminal = new ScriptedTerminal(
                afterOutput(
                        "Not yet: the card is still in \"Inbox\".",
                        () -> move(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE),
                        ""),
                answer("q"),
                answer(""));

        // when
        Outcome outcome = run(terminal, SHORT_WINDOW, trelloOnly());

        // then
        assertThat(outcome).isEqualTo(Outcome.STOPPED);
        assertThat(terminal.stdout())
                .containsSubsequence(
                        "The tutorial checks the board every 10 milliseconds for up to 30 milliseconds.",
                        "Stopped checking automatically after 30 milliseconds.",
                        "Not yet: the card is still in \"Inbox\".",
                        "Expected: the card is in \"Ready for Codex\".",
                        "OK  The card is in \"Ready for Codex\"");
        assertThat(cardReads)
                .as("one read per automatic check, one after the window closes, and one on request")
                .hasValue(SHORT_WINDOW.automaticChecks() + 2);
    }

    @Test
    void skipLetsTheTutorialPerformEveryStep() throws IOException {
        // given
        var terminal = new ScriptedTerminal(
                answer("s"), answer(""), answer(""), answer("skip"), answer(""), answer("s"), answer("y"));

        // when
        Outcome outcome = run(terminal, ANSWER_DRIVEN, trelloOnly());

        // then
        assertThat(outcome).isEqualTo(Outcome.COMPLETED);
        assertThat(terminal.stdout())
                .containsSubsequence(
                        "OK  The tutorial moved the card to \"Ready for Codex\" for you",
                        "OK  The tutorial added the comment \"" + GuidedTutorial.SAMPLE_CHANGE_REQUEST
                                + "\" and moved the card to \"Ready for Codex\" for you",
                        "OK  The tutorial moved the card to \"Done\" for you",
                        "OK  Archived the temporary tutorial board");
        assertThat(workpad())
                .contains("Rework input: your Trello comment \"" + GuidedTutorial.SAMPLE_CHANGE_REQUEST + "\"");
        assertThat(trello.listOf(cardId())).isEqualTo(TrelloBoardSetup.RECOMMENDED_DONE_STATE);
    }

    @Test
    void endOfInputStopsTheTutorialAndArchivesTheBoardByDefault() throws IOException {
        // given
        var terminal = new ScriptedTerminal();

        // when
        Outcome outcome = run(terminal, ANSWER_DRIVEN, trelloOnly());

        // then
        assertThat(outcome).isEqualTo(Outcome.STOPPED);
        assertThat(terminal.stdout()).contains("Stopped the tutorial.", "OK  Archived the temporary tutorial board");
        assertThat(trello.archived(trello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                .as("the default cleanup answer archives the tutorial board")
                .isTrue();
    }

    @Test
    void noCleanupKeepsTheBoardWithoutAsking() throws IOException {
        // given
        var terminal = new ScriptedTerminal(answer("q"));

        // when
        Outcome outcome = run(terminal, ANSWER_DRIVEN, trelloOnlyKeepingTheBoard());

        // then
        assertThat(outcome).isEqualTo(Outcome.STOPPED);
        assertThat(terminal.stdout())
                .contains("The temporary tutorial board stays in Trello:")
                .doesNotContain(GuidedTutorial.CLEANUP_PROMPT, "Archived");
        assertThat(trello.archived(trello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                .as("--no-cleanup keeps the tutorial board")
                .isFalse();
    }

    @Test
    void trelloFailureArchivesTheBoardBeforeReportingTheError() {
        // given
        trello.failNext("PUT", "cards/", 500);
        var terminal = new ScriptedTerminal(user(() -> move(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE), ""));

        // when
        Throwable failure = catchThrowable(() -> run(terminal, ANSWER_DRIVEN, trelloOnly()));

        // then
        assertThat(failure)
                .isInstanceOf(TrelloBoardSetupException.class)
                .extracting(error -> ((TrelloBoardSetupException) error).code())
                .isEqualTo("trello_write_outcome_unknown");
        assertThat(terminal.stdout())
                .containsSubsequence(
                        "The tutorial stopped because of an error.", "OK  Archived the temporary tutorial board");
        assertThat(trello.archived(trello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                .as("a failed tutorial still archives its board")
                .isTrue();
        assertThat(hooks.registered())
                .as("the Ctrl+C hook is removed after a failure")
                .isFalse();
    }

    @Test
    void deletedCardStopsWithAnExpectedFailureAndArchivesTheBoard() {
        // given
        var terminal = new ScriptedTerminal(user(() -> trello.deleteCard(cardId()), ""));

        // when
        Throwable failure = catchThrowable(() -> run(terminal, ANSWER_DRIVEN, trelloOnly()));

        // then
        assertThat(failure)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessage("The tutorial card is no longer in Trello. Start the tutorial again with: symphony-trello"
                        + " tutorial")
                .extracting(error -> ((TrelloBoardSetupException) error).code())
                .isEqualTo("setup_tutorial_card_missing");
        assertThat(SetupDiagnosticReporter.shouldReport((TrelloBoardSetupException) failure))
                .as("a deleted tutorial card is a user action, not a bug report")
                .isFalse();
        assertThat(trello.archived(trello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                .as("the tutorial board is archived after the failure")
                .isTrue();
    }

    @Test
    void ctrlCHookArchivesTheBoardOnceEvenWhenTheTutorialThreadSettlesLater() throws IOException {
        // given
        var terminal = new ScriptedTerminal(user(() -> hooks.fire(), null));

        // when
        Outcome outcome = run(terminal, ANSWER_DRIVEN, trelloOnly());

        // then
        assertThat(outcome).isEqualTo(Outcome.STOPPED);
        assertThat(trello.requests())
                .filteredOn(request -> request.startsWith("PUT /boards/"))
                .hasSize(1);
        assertThat(terminal.stdout()).containsOnlyOnce("OK  Archived the temporary tutorial board");
        assertThat(trello.archived(trello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                .as("the Ctrl+C hook archives the tutorial board")
                .isTrue();
        assertThat(trello.archived(userBoardId))
                .as("the Ctrl+C hook never archives another board")
                .isFalse();
    }

    @Test
    void asksForTheWorkspaceWhenTheTokenSeesSeveral() throws IOException {
        // given
        trello.givenWorkspaces("Personal", "Engineering");
        var terminal = new ScriptedTerminal(answer("2"), answer("q"), answer(""));

        // when
        run(terminal, ANSWER_DRIVEN, trelloOnly());

        // then
        assertThat(terminal.stdout())
                .containsSubsequence(
                        "Choose the Trello Workspace for the temporary tutorial board:",
                        "1. \"Personal\"",
                        "2. \"Engineering\"",
                        "Workspace: ");
        assertThat(trello.workspaceOf(trello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                .isEqualTo("workspace-2");
    }

    @CsvSource({"PT3M, 3 minutes", "PT5S, 5 seconds", "PT1S, 1 second", "PT90S, 90 seconds", "PT0.03S, 30 milliseconds"
    })
    @ParameterizedTest
    void describesPollingDurationsInWholeUnits(Duration duration, String expected) {
        // given
        Duration window = duration;

        // when
        String description = GuidedTutorial.describe(window);

        // then
        assertThat(description).isEqualTo(expected);
    }

    @Test
    void defaultPacingChecksEveryFiveSecondsForThreeMinutes() {
        // given
        Pacing pacing = Pacing.DEFAULT;

        // when
        Duration window = pacing.automaticWindow();

        // then
        assertThat(pacing.interval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(window).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void unansweredBoardCreationTellsTheUserToCheckTrello() {
        // given
        trello.failNext("POST", "boards", 500);
        var terminal = new ScriptedTerminal();

        // when
        Throwable failure = catchThrowable(() -> run(terminal, ANSWER_DRIVEN, trelloOnly()));

        // then
        assertThat(failure).isInstanceOf(TrelloBoardSetupException.class);
        assertThat(terminal.stdout())
                .contains(
                        "The tutorial stopped because of an error.",
                        "If Trello created the board \"" + GuidedTutorial.BOARD_NAME + "\", archive it in Trello.");
    }

    private static GuidedTutorial.Options trelloOnly() {
        return new GuidedTutorial.Options(false, true, Optional.empty(), CLI);
    }

    private static GuidedTutorial.Options withGithub() {
        return new GuidedTutorial.Options(true, true, Optional.empty(), CLI);
    }

    private static GuidedTutorial.Options trelloOnlyKeepingTheBoard() {
        return new GuidedTutorial.Options(false, false, Optional.empty(), CLI);
    }

    private Outcome run(ScriptedTerminal terminal, Pacing pacing, GuidedTutorial.Options options) throws IOException {
        return new GuidedTutorial(
                        new TutorialTrello(trello.endpoint(), new TrelloCredentials("key", "token")),
                        terminal,
                        hooks,
                        pacing)
                .run(options);
    }

    private String cardId() {
        return trello.onlyCardId(trello.boardIdNamed(GuidedTutorial.BOARD_NAME));
    }

    private void move(String listName) {
        trello.moveCard(cardId(), listName);
    }

    private void requestChange() {
        trello.addComment(cardId(), USER_CHANGE_REQUEST);
        move(TrelloBoardSetup.RECOMMENDED_ACTIVE_STATE);
    }

    private String workpad() {
        return trello.commentTexts(cardId()).getFirst();
    }

    private static Reply answer(String line) {
        return terminal -> line;
    }

    private static Reply user(Runnable action, String line) {
        return terminal -> {
            action.run();
            return line;
        };
    }

    private static Reply afterOutput(String marker, String line) {
        return afterOutput(marker, () -> {}, line);
    }

    private static Reply afterOutput(String marker, Runnable action, String line) {
        return terminal -> {
            terminal.awaitOutput(marker);
            action.run();
            return line;
        };
    }

    @FunctionalInterface
    private interface Reply {
        String answer(ScriptedTerminal terminal);
    }

    /// Answers each prompt with the next scripted reply, like a person who acts in Trello and then
    /// types an answer. It answers end of input once the script runs out.
    private static final class ScriptedTerminal implements Terminal {
        private static final Duration OUTPUT_WAIT = Duration.ofSeconds(10);

        private final Deque<Reply> replies = new ConcurrentLinkedDeque<>();
        private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        private final PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8);

        private ScriptedTerminal(Reply... replies) {
            Collections.addAll(this.replies, replies);
        }

        @Override
        public String readLine(String prompt) {
            out.print(prompt);
            Reply reply = replies.poll();
            return reply == null ? null : reply.answer(this);
        }

        @Override
        public char[] readSecret(String prompt) {
            throw new UnsupportedOperationException("The tutorial never reads secrets");
        }

        @Override
        public void info(String line) {
            out.println(line);
        }

        @Override
        public void warn(String line) {
            out.println(line);
        }

        @Override
        public void error(String line) {
            err.println(line);
        }

        @Override
        public PrintStream out() {
            return out;
        }

        @Override
        public PrintStream err() {
            return err;
        }

        private String stdout() {
            return stdout.toString(StandardCharsets.UTF_8);
        }

        /// Waits until the tutorial printed the marker, like a person reading the terminal.
        private void awaitOutput(String marker) {
            long deadline = System.nanoTime() + OUTPUT_WAIT.toNanos();
            while (!stdout().contains(marker)) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("Timed out waiting for output: " + marker + "\n" + stdout());
                }
                try {
                    Thread.sleep(Duration.ofMillis(5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting for output", e);
                }
            }
        }
    }

    /// Captures the Ctrl+C hook instead of registering it with the JVM, so a test can fire it.
    private static final class RecordingShutdownHooks implements GuidedTutorial.ShutdownHooks {
        private final AtomicReference<Runnable> action = new AtomicReference<>();

        @Override
        public Runnable register(Runnable hook) {
            action.set(hook);
            return () -> action.set(null);
        }

        private boolean registered() {
            return action.get() != null;
        }

        private void fire() {
            action.get().run();
        }
    }
}
