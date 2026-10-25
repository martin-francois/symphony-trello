package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.HostEnvironment.CLEARED;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.runPseudoTerminalDialog;
import static ch.fmartin.symphony.trello.setup.InstallerScriptFixture.shellQuote;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.PseudoTerminalResult;
import ch.fmartin.symphony.trello.setup.InstallerScriptFixture.TerminalAnswer;
import ch.fmartin.symphony.trello.testsupport.StatefulFakeTrello;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class TutorialOfferCommandTest {
    @TempDir
    Path temporaryDirectory;

    @ParameterizedTest(name = "declines answer {index}")
    @ValueSource(strings = {"n", "", "\u0004"})
    void declinedOfferNeedsNoCredentialsOrConfiguration(String answer) throws Exception {
        // given
        Path missingConfig = temporaryDirectory.resolve("missing-config");

        // when
        var result = runOffer(
                List.of(new TerminalAnswer(GuidedTutorial.OFFER_PROMPT, answer, !answer.equals("\u0004"))),
                "--config-dir",
                missingConfig.toString());

        // then
        result.assertSuccess();
        assertThat(result.transcript())
                .contains(GuidedTutorial.OFFER_PROMPT, "You can start it later with:")
                .doesNotContain("Board created:", "setup_failed");
        assertThat(missingConfig).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptedOfferRunsAndArchivesOnlyItsTemporaryBoard(boolean interrupt) throws Exception {
        // given
        try (var trello = new StatefulFakeTrello().start()) {
            String existingBoard = trello.givenBoard("Existing queue");

            List<TerminalAnswer> answers = new ArrayList<>(List.of(
                    new TerminalAnswer(GuidedTutorial.OFFER_PROMPT, "y"),
                    new TerminalAnswer(GuidedTutorial.CHECK_PROMPT, interrupt ? "\u0003" : "q", !interrupt)));
            if (!interrupt) {
                answers.add(new TerminalAnswer(GuidedTutorial.CLEANUP_PROMPT, ""));
            }

            // when
            var result = runOffer(
                    answers,
                    "--endpoint",
                    trello.endpoint().toString(),
                    "--key",
                    "synthetic-key",
                    "--token",
                    "synthetic-token",
                    "--no-github");

            // then
            assertThat(result.exitCode()).as(result.transcript()).isEqualTo(interrupt ? 130 : 0);
            assertThat(result.transcript())
                    .containsSubsequence(GuidedTutorial.OFFER_PROMPT, "Board created:", "OK  Archived");
            assertThat(trello.boardNames()).containsExactly("Existing queue", GuidedTutorial.BOARD_NAME);
            assertThat(trello.archived(trello.boardIdNamed(GuidedTutorial.BOARD_NAME)))
                    .as("the accepted tutorial archives its own board")
                    .isTrue();
            assertThat(trello.archived(existingBoard))
                    .as("the offer never archives an existing board")
                    .isFalse();
        }
    }

    @Test
    void acceptedOfferReportsMissingCredentialsWithoutFailingTheCompletedCaller() throws Exception {
        // given
        Path missingConfig = temporaryDirectory.resolve("missing-config");

        // when
        var result = runOffer(
                List.of(new TerminalAnswer(GuidedTutorial.OFFER_PROMPT, "y")),
                "--config-dir",
                missingConfig.toString());

        // then
        result.assertSuccess();
        assertThat(result.transcript())
                .contains("The guided tutorial stopped:", "Start the tutorial again with:")
                .doesNotContain("Board created:", "setup_failed", missingConfig.toString());
        assertThat(missingConfig).doesNotExist();
    }

    private PseudoTerminalResult runOffer(List<TerminalAnswer> answers, String... options)
            throws IOException, InterruptedException {
        assumeTrue(System.getProperty("os.name").equalsIgnoreCase("Linux"), "the fixture uses a POSIX terminal");
        return runPseudoTerminalDialog(
                CLEARED,
                Map.of(
                        "HOME", temporaryDirectory.toString(),
                        "TERM", "xterm-256color",
                        "PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin")),
                temporaryDirectory,
                tutorialCommand(options),
                answers);
    }

    private static String tutorialCommand(String... options) {
        var command = new StringJoiner(" ");
        for (String argument : List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                TrelloBoardSetupMain.class.getName(),
                "tutorial",
                "--offer")) {
            command.add(shellQuote(argument));
        }
        for (String option : options) {
            command.add(shellQuote(option));
        }
        return InstallerScriptFixture.withTerminalSize("exec " + command);
    }
}
