package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.testsupport.TerminalTranscriptAssertions.assertThatTranscript;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.fmartin.symphony.trello.setup.CodexModelSelectionDefaults.CatalogModel;
import ch.fmartin.symphony.trello.testsupport.RecordingTerminal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class CodexModelPickerTest {
    private static final String SOL = "gpt-6.1-sol";
    private static final String TERRA = "gpt-5.6-terra";
    private static final String DAYBREAK = "gpt-daybreak-blue-latest";
    private static final String UNLISTED_MODEL = "gpt-7-preview";
    private static final List<CatalogModel> CATALOG = List.of(
            new CatalogModel(SOL, "GPT-6.1-Sol", false),
            new CatalogModel(TERRA, "GPT-5.6-Terra", true),
            new CatalogModel(DAYBREAK, "Daybreak Blue", false));
    /// The picker lists the catalog, then the other-model choice; the current model is listed.
    private static final int CHOICE_COUNT = CATALOG.size() + 1;
    private static final String OTHER_MODEL_CHOICE = Integer.toString(CHOICE_COUNT);
    private static final String CHOICE_OUT_OF_RANGE = "Choice must be a number between 1 and " + CHOICE_COUNT + ".";
    private static final String BLANK_MODEL_ID = "Model ID must not be blank.";
    /// What a line-based prompt receives when the operator presses the up-arrow key.
    private static final String ARROW_UP_KEY = Character.toString(0x1B) + "[A";
    private static final int TERMINAL_COLUMNS = 80;

    private final CodexModelPicker picker = new CodexModelPicker();

    @Test
    void listsCatalogModelsInCatalogOrderAndPreselectsTheRecommendation() throws Exception {
        // given
        var terminal = new RecordingTerminal("");

        // when
        picker.choose(terminal, CATALOG, TERRA);

        // then
        assertThatTranscript(terminal.stdout())
                .containsSectionsInOrder(
                        "Models listed by the installed Codex CLI:",
                        "  1. " + SOL,
                        "  2. " + TERRA + " (recommended)",
                        "  3. " + DAYBREAK + " - Daybreak Blue",
                        "  " + OTHER_MODEL_CHOICE + ". Other model ID",
                        "Press Enter to keep 2, type another number, or type a model ID.",
                        "Model [2]: ");
        assertThat(terminal.stdout()).doesNotContain("GPT-6.1-Sol", "GPT-5.6-Terra");
    }

    @MethodSource("choiceScenarios")
    @ParameterizedTest
    void returnsTheChosenModelOrEmptyWhenTheCurrentModelIsKept(ChoiceScenario scenario) throws Exception {
        // given
        var terminal = new RecordingTerminal(scenario.answers().toArray(String[]::new));

        // when
        Optional<String> chosen = picker.choose(terminal, CATALOG, TERRA);

        // then
        assertThat(chosen).isEqualTo(scenario.expected());
    }

    @MethodSource("invalidAnswerScenarios")
    @ParameterizedTest
    void rejectsInvalidAnswersWithAnExpectedSetupError(InvalidAnswerScenario scenario) {
        // given
        var terminal = new RecordingTerminal(scenario.answers().toArray(String[]::new));

        // when
        ThrowingCallable choose = () -> picker.choose(terminal, CATALOG, TERRA);

        // then
        assertThatThrownBy(choose)
                .isInstanceOfSatisfying(TrelloBoardSetupException.class, error -> assertThat(error.code())
                        .isEqualTo("setup_invalid_choice"))
                .hasMessage(scenario.message());
    }

    @Test
    void marksAListedWorkflowModelAsCurrentAndPreselectsIt() throws Exception {
        // given
        var terminal = new RecordingTerminal("");

        // when
        Optional<String> chosen = picker.choose(terminal, CATALOG, DAYBREAK);

        // then
        assertThat(chosen).isEmpty();
        assertThatTranscript(terminal.stdout())
                .containsSectionsInOrder(
                        "  2. " + TERRA + " (recommended)",
                        "  3. " + DAYBREAK + " (current) - Daybreak Blue",
                        "  " + OTHER_MODEL_CHOICE + ". Other model ID",
                        "Model [3]: ");
    }

    @Test
    void keepsAnUnlistedWorkflowModelAsItsOwnPreselectedChoice() throws Exception {
        // given
        var terminal = new RecordingTerminal("4");

        // when
        Optional<String> chosen = picker.choose(terminal, CATALOG, "gpt-hand-edited");

        // then
        assertThat(chosen).isEmpty();
        assertThatTranscript(terminal.stdout())
                .containsSectionsInOrder(
                        "  3. " + DAYBREAK + " - Daybreak Blue",
                        "  4. gpt-hand-edited (current, not listed by Codex)",
                        "  5. Other model ID",
                        "Model [4]: ");
    }

    @Test
    void offersToKeepAnOmittedWorkflowModel() throws Exception {
        // given
        var terminal = new RecordingTerminal("");

        // when
        Optional<String> chosen = picker.choose(terminal, CATALOG, null);

        // then
        assertThat(chosen).isEmpty();
        assertThatTranscript(terminal.stdout())
                .containsSectionsInOrder(
                        "  2. " + TERRA + " (recommended)",
                        "  4. Keep the workflow default (Codex chooses the model)",
                        "  5. Other model ID",
                        "Model [4]: ");
    }

    @Test
    void shortensLongDisplayNamesSoPickerLinesFitAnEightyColumnTerminal() throws Exception {
        // given
        String longDisplayName = "Experimental research preview with a very long marketing name";
        var terminal = new RecordingTerminal("");

        // when
        picker.choose(terminal, List.of(new CatalogModel("gpt-long", longDisplayName, true)), "gpt-long");

        // then
        assertThat(terminal.stdout())
                .contains("  1. gpt-long (recommended) - Experimental research preview with a...")
                .doesNotContain(longDisplayName);
        assertThat(terminal.stdout().split("\\R"))
                .allSatisfy(line -> assertThat(line).hasSizeLessThanOrEqualTo(TERMINAL_COLUMNS));
    }

    private static Stream<ChoiceScenario> choiceScenarios() {
        return Stream.of(
                new ChoiceScenario("Enter keeps the preselected model", List.of(""), Optional.empty()),
                new ChoiceScenario("end of input keeps the preselected model", List.of(), Optional.empty()),
                new ChoiceScenario("a number selects a listed model", List.of("1"), Optional.of(SOL)),
                new ChoiceScenario("a padded number selects a listed model", List.of(" 3 "), Optional.of(DAYBREAK)),
                new ChoiceScenario("the preselected number keeps the model", List.of("2"), Optional.empty()),
                new ChoiceScenario("a typed listed model id selects it", List.of(SOL), Optional.of(SOL)),
                new ChoiceScenario(
                        "a typed unlisted model id is accepted", List.of(UNLISTED_MODEL), Optional.of(UNLISTED_MODEL)),
                new ChoiceScenario(
                        "the other-model choice asks for a model id",
                        List.of(OTHER_MODEL_CHOICE, UNLISTED_MODEL),
                        Optional.of(UNLISTED_MODEL)),
                new ChoiceScenario(
                        "the other-model choice with the current model keeps it",
                        List.of(OTHER_MODEL_CHOICE, TERRA),
                        Optional.empty()));
    }

    private static Stream<InvalidAnswerScenario> invalidAnswerScenarios() {
        return Stream.of(
                new InvalidAnswerScenario(
                        "a number above the list", List.of(Integer.toString(CHOICE_COUNT + 1)), CHOICE_OUT_OF_RANGE),
                new InvalidAnswerScenario("zero", List.of("0"), CHOICE_OUT_OF_RANGE),
                new InvalidAnswerScenario("a blank other model id", List.of(OTHER_MODEL_CHOICE, " "), BLANK_MODEL_ID),
                new InvalidAnswerScenario(
                        "end of input at the other model id", List.of(OTHER_MODEL_CHOICE), BLANK_MODEL_ID),
                new InvalidAnswerScenario(
                        "an arrow-key escape sequence",
                        List.of(ARROW_UP_KEY),
                        "Model ID must not contain control characters or line breaks."));
    }

    private record ChoiceScenario(String name, List<String> answers, Optional<String> expected) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record InvalidAnswerScenario(String name, List<String> answers, String message) {
        @Override
        public String toString() {
            return name;
        }
    }
}
