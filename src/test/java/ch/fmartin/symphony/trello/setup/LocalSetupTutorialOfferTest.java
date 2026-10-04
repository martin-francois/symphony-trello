package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.testsupport.SetupRunResult;
import ch.fmartin.symphony.trello.testsupport.StatefulFakeTrello;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class LocalSetupTutorialOfferTest extends LocalSetupFixtureSupport {
    /// Interactive setup asks for a repository URL, the Codex model, the reasoning effort, the
    /// per-board concurrency, extra workspace paths, and danger-full-access before it finishes.
    /// Blank answers keep each default.
    private static final String DEFAULT_SETUP_ANSWERS = "\n".repeat(6);
    private static final String BOARD_NAME = "Tutorial Offer Queue";
    private static final String TUTORIAL_POINTER = "To practice the card flow on a temporary Trello board first, run:";

    private StatefulFakeTrello statefulTrello;

    @BeforeEach
    void startStatefulTrello() throws IOException {
        statefulTrello = new StatefulFakeTrello().start();
    }

    @AfterEach
    void stopStatefulTrello() {
        statefulTrello.close();
    }

    @Test
    void acceptedOfferRunsTheTutorialAndArchivesOnlyTheTutorialBoard() {
        // given
        String answers = DEFAULT_SETUP_ANSWERS + "y\nq\n";

        // when
        SetupRunResult result = runInteractiveSetup(setup, answers);

        // then
        result.assertSuccess()
                .stdoutContainsSubsequence(
                        "You're good to go - your Trello board is now a queue for Codex work.",
                        "Useful commands:",
                        GuidedTutorial.OFFER_PROMPT,
                        "Guided tutorial",
                        "Board created: \"" + GuidedTutorial.BOARD_NAME + "\"",
                        "Stopped the tutorial.",
                        "OK  Archived the temporary tutorial board")
                .stdoutDoesNotContain(TUTORIAL_POINTER);
        assertThat(statefulTrello.boardNames()).containsExactly(BOARD_NAME, GuidedTutorial.BOARD_NAME);
        assertThat(statefulTrello.archived(statefulTrello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                .as("the tutorial archives its temporary board")
                .isTrue();
        assertThat(statefulTrello.archived(statefulTrello.boardIdNamed(BOARD_NAME)))
                .as("the tutorial never archives the board that setup connected")
                .isFalse();
        assertThat(statefulTrello.listNames(statefulTrello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                .as("a non-GitHub setup gets the non-GitHub tutorial")
                .isEqualTo(TrelloBoardSetup.RECOMMENDED_NON_GITHUB_LISTS);
    }

    @Test
    void declinedOfferNamesTheTutorialCommandAndCreatesNoTutorialBoard() {
        // given
        String answers = DEFAULT_SETUP_ANSWERS + "\n";

        // when
        SetupRunResult result = runInteractiveSetup(setup, answers);

        // then
        result.assertSuccess()
                .stdoutContainsSubsequence(
                        GuidedTutorial.OFFER_PROMPT, "You can start it later with:", "  symphony-trello tutorial");
        assertThat(statefulTrello.boardNames()).containsExactly(BOARD_NAME);
    }

    @Test
    void tutorialFailureLeavesTheFinishedSetupSuccessful() {
        // given
        statefulTrello.failNext("POST", "cards", 500);
        String answers = DEFAULT_SETUP_ANSWERS + "y\n";

        // when
        SetupRunResult result = runInteractiveSetup(setup, answers);

        // then
        assertThat(result.exitCode()).as("stderr:%n%s", result.stderr()).isZero();
        assertThat(result.stderr())
                .contains(
                        "The guided tutorial stopped: Trello write outcome is unknown.",
                        "Setup is complete. Start the tutorial again with:",
                        "  symphony-trello tutorial")
                .doesNotContain("Troubleshooting report written");
        assertThat(result.stdout()).contains("OK  Archived the temporary tutorial board");
        assertThat(statefulTrello.archived(statefulTrello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                .as("a failed tutorial still archives its board")
                .isTrue();
    }

    @Test
    void nonInteractiveSetupNamesTheTutorialCommandWithoutAsking() {
        // given
        String answers = "";

        // when
        SetupRunResult result = runSetupWithInput(setup, answers, setupArguments(true));

        // then
        result.assertSuccess()
                .stdoutContainsSubsequence(TUTORIAL_POINTER, "  symphony-trello tutorial", "Useful commands:")
                .stdoutDoesNotContain(GuidedTutorial.OFFER_PROMPT);
        assertThat(statefulTrello.boardNames()).containsExactly(BOARD_NAME);
    }

    @Test
    void installerDeferredSetupLeavesTheTutorialToTheFinalHandoff() {
        // given
        LocalSetup deferredSetup = setupWithEnvironment(Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR",
                fixture.configDir().toString(),
                "SYMPHONY_TRELLO_COMMAND",
                "symphony-trello",
                LocalSetup.INSTALLER_COMPLETION_ENV,
                "defer"));
        LocalSetup completionSetup = setupWithEnvironment(Map.of(
                "SYMPHONY_TRELLO_CONFIG_DIR",
                fixture.configDir().toString(),
                "SYMPHONY_TRELLO_COMMAND",
                "symphony-trello",
                LocalSetup.INSTALLER_COMPLETION_ENV,
                "print"));

        // when
        SetupRunResult deferred = runInteractiveSetup(deferredSetup, DEFAULT_SETUP_ANSWERS + "y\n");
        SetupRunResult completion = runSetup(completionSetup);

        // then
        deferred.assertSuccess().stdoutDoesNotContain(GuidedTutorial.OFFER_PROMPT, TUTORIAL_POINTER);
        completion
                .assertSuccess()
                .stdoutContainsSubsequence(
                        "You're good to go", TUTORIAL_POINTER, "  symphony-trello tutorial", "Useful commands:");
        assertThat(statefulTrello.boardNames()).containsExactly(BOARD_NAME);
    }

    private SetupRunResult runInteractiveSetup(LocalSetup localSetup, String answers) {
        return runSetupWithInput(localSetup, answers, setupArguments(false));
    }

    private String[] setupArguments(boolean nonInteractive) {
        String[] arguments = {
            "--endpoint",
            statefulTrello.endpoint().toString(),
            "--key",
            "key",
            "--token",
            "token",
            "--board-name",
            BOARD_NAME,
            "--workflow",
            tempDir.resolve("WORKFLOW.tutorial-offer.md").toString(),
            "--env",
            tempDir.resolve(".env.tutorial-offer").toString(),
            "--no-github"
        };
        if (!nonInteractive) {
            return arguments;
        }
        String[] withFlag = new String[arguments.length + 1];
        withFlag[0] = "--non-interactive";
        System.arraycopy(arguments, 0, withFlag, 1, arguments.length);
        return withFlag;
    }
}
