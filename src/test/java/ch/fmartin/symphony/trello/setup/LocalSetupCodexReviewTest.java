package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.testsupport.SetupRunResult;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class LocalSetupCodexReviewTest extends LocalSetupFixtureSupport {
    private static final String QUESTION = "Review changes before handing them to you?";
    private static final String EXPLANATION =
            "Before Codex hands a change to you, a Codex review checks it and Codex fixes what the review finds,"
                    + " for up to 3 rounds, with extra rounds while a serious finding stays open. Cards take longer and"
                    + " use more Codex time.";
    /// Answers the follow-up card question that setup asks right before the review question.
    private static final String KEEP_FOLLOW_UP_CHOICE = "\n";

    @CsvSource({"false,y,3", "false,n,0", "false,'',3", "true,y,3", "true,n,0", "true,'',3"})
    @ParameterizedTest
    void guidedCreationAndImportOfferTheReviewLoop(boolean importing, String answer, int expectedRounds) {
        // given
        String priorAnswers = "\n".repeat(importing ? 8 : 6) + KEEP_FOLLOW_UP_CHOICE;

        // when
        SetupRunResult result = runSetupWithInput(priorAnswers + answer + "\n", arguments(importing, false));

        // then
        result.assertSuccess().stdoutContains("Review before handoff for", EXPLANATION, QUESTION + " [Y/n]");
        assertThat(maxRounds()).as("the answer sets the review round limit").isEqualTo(expectedRounds);
    }

    @CsvSource({"5,'',5,[Y/n]", "5,y,5,[Y/n]", "5,n,0,[Y/n]", "0,y,3,[y/N]", "0,'',0,[y/N]", "0,n,0,[y/N]"})
    @ParameterizedTest
    void keepingExistingBoardsUsesTheCurrentChoice(int before, String answer, int after, String choices)
            throws IOException {
        // given
        runSetup(arguments(false, true)).assertSuccess();
        setMaxRounds(before);
        String previous = Files.readString(workflow());

        // when
        SetupRunResult result = runSetupWithInput("\n" + KEEP_FOLLOW_UP_CHOICE + answer + "\n", "--no-start");

        // then
        result.assertSuccess().stdoutContains(QUESTION + " " + choices);
        assertThat(maxRounds())
                .as("Enter keeps the round limit, Yes restores the default only when the loop is off")
                .isEqualTo(after);
        if (before == after) {
            assertThat(workflow()).content().isEqualTo(previous);
        }
    }

    @Test
    void generatedWorkflowLetsTheExtendedLimitFollowARaisedRoundLimit() throws IOException {
        // given
        runSetup(arguments(false, true)).assertSuccess();

        // when
        setMaxRounds(7);

        // then
        assertThat(workflow()).content().contains("  # codex_review_extended_max_rounds: 5\n");
        assertThat(config().repository().codexReview().extendedMaxRounds()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "\"1\"", "1.0"})
    void turningTheLoopBackOnRaisesAnExtendedLimitBelowTheRoundLimit(String extendedLimit) throws IOException {
        // given
        runSetup(arguments(false, true)).assertSuccess();
        setMaxRounds(0);
        Files.writeString(
                workflow(),
                Files.readString(workflow())
                        .replace(
                                "# codex_review_extended_max_rounds: 5",
                                "codex_review_extended_max_rounds: " + extendedLimit));

        // when
        SetupRunResult result = runSetupWithInput("\n" + KEEP_FOLLOW_UP_CHOICE + "y\n", "--no-start");

        // then
        result.assertSuccess();
        assertThat(config().repository().codexReview())
                .extracting(
                        EffectiveConfig.CodexReviewConfig::maxRounds,
                        EffectiveConfig.CodexReviewConfig::extendedMaxRounds)
                .as("the re-enabled workflow must still pass configuration")
                .containsExactly(3, 3);
    }

    @Test
    void nonInteractiveSetupDoesNotAskAndKeepsTheDefaultLoop() {
        // given

        // when
        SetupRunResult result = runSetup(arguments(false, true));

        // then
        result.assertSuccess().stdoutDoesNotContain(QUESTION);
        assertThat(maxRounds()).isEqualTo(3);
    }

    private String[] arguments(boolean importing, boolean nonInteractive) {
        List<String> arguments = new ArrayList<>(List.of(
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                importing ? "--board" : "--board-name",
                importing ? "board-1" : "Review Queue",
                "--workflow",
                workflow().toString(),
                "--env",
                tempDir.resolve(".env.review").toString(),
                "--no-github",
                "--no-start"));
        if (nonInteractive) {
            arguments.add("--non-interactive");
        }
        return arguments.toArray(String[]::new);
    }

    private Path workflow() {
        return tempDir.resolve("WORKFLOW.review.md");
    }

    private int maxRounds() {
        return config().repository().codexReview().maxRounds();
    }

    private EffectiveConfig config() {
        return new ConfigResolver(name -> Optional.of("synthetic-value"))
                .resolve(new WorkflowLoader().load(workflow()));
    }

    private void setMaxRounds(int rounds) throws IOException {
        Files.writeString(
                workflow(),
                Files.readString(workflow())
                        .replace("codex_review_max_rounds: 3", "codex_review_max_rounds: " + rounds));
    }
}
