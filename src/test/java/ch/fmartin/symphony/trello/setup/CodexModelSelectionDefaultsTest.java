package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_LINE_SEPARATOR;
import static ch.fmartin.symphony.trello.TextCharacterMatchers.UNICODE_PARAGRAPH_SEPARATOR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import ch.fmartin.symphony.trello.setup.CodexModelSelectionDefaults.ReasoningEffortOption;
import ch.fmartin.symphony.trello.setup.TrelloBoardSetup.CodexModelDefaults;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class CodexModelSelectionDefaultsTest {
    @Test
    void legacyReasoningEffortChoicesAreNormalizedAndEmptyModelsAreOmitted() {
        // given
        Map<String, List<String>> choicesByModel = new LinkedHashMap<>();
        choicesByModel.put(" gpt-5.6-sol ", List.of(" low ", "", "medium"));
        choicesByModel.put("empty", List.of("", " "));
        choicesByModel.put(" duplicate ", List.of("low"));
        choicesByModel.put("duplicate", List.of("high"));

        // when
        CodexModelSelectionDefaults defaults =
                new CodexModelSelectionDefaults(CodexModelDefaults.fallback(), Map.of(), choicesByModel);

        // then
        assertThat(defaults.reasoningEffortChoicesForModel("gpt-5.6-sol")).contains(List.of("low", "medium"));
        assertThat(defaults.reasoningEffortChoicesForModel("empty")).isEmpty();
        assertThat(defaults.reasoningEffortChoicesForModel("duplicate")).contains(List.of("high"));
    }

    @Test
    void reasoningEffortOptionsKeepCatalogOrderAndFirstDuplicateDescription() {
        // given
        Map<String, List<ReasoningEffortOption>> optionsByModel = new LinkedHashMap<>();
        optionsByModel.put(
                " gpt-5.6-sol ",
                List.of(
                        new ReasoningEffortOption(" low ", "First description"),
                        new ReasoningEffortOption("low", "Ignored duplicate"),
                        new ReasoningEffortOption("high", "Deep reasoning")));
        optionsByModel.put("empty", List.of());
        optionsByModel.put(" duplicate ", List.of(new ReasoningEffortOption("low", null)));
        optionsByModel.put("duplicate", List.of(new ReasoningEffortOption("medium", null)));

        // when
        CodexModelSelectionDefaults defaults = CodexModelSelectionDefaults.withReasoningEffortOptions(
                CodexModelDefaults.fallback(), Map.of(), optionsByModel);

        // then
        assertThat(defaults.reasoningEffortOptionsForModel("gpt-5.6-sol"))
                .contains(List.of(
                        new ReasoningEffortOption("low", "First description"),
                        new ReasoningEffortOption("high", "Deep reasoning")));
        assertThat(defaults.reasoningEffortOptionsForModel("empty")).isEmpty();
        assertThat(defaults.reasoningEffortChoicesForModel("duplicate")).contains(List.of("medium"));
    }

    @MethodSource("unsafeSingleLineCharacters")
    @ParameterizedTest
    void reasoningEffortOptionRejectsControlCharactersInReasoningEffort(int controlCharacter) {
        // given
        String reasoningEffort = "med" + Character.toString(controlCharacter) + "ium";

        // when
        ThrowingCallable createOption = () -> new ReasoningEffortOption(reasoningEffort, "Balanced reasoning");

        // then
        assertThatIllegalArgumentException()
                .isThrownBy(createOption)
                .withMessage("reasoningEffort must not contain control characters");
    }

    @MethodSource("unsafeSingleLineCharacters")
    @ParameterizedTest
    void reasoningEffortOptionRejectsControlCharactersInDescription(int controlCharacter) {
        // given
        String description = "Balanced" + Character.toString(controlCharacter) + "reasoning";

        // when
        ThrowingCallable createOption = () -> new ReasoningEffortOption("medium", description);

        // then
        assertThatIllegalArgumentException()
                .isThrownBy(createOption)
                .withMessage("description must not contain control characters");
    }

    private static IntStream unsafeSingleLineCharacters() {
        return IntStream.of(0, '\n', '\r', 0x1B, 0x85, UNICODE_LINE_SEPARATOR, UNICODE_PARAGRAPH_SEPARATOR);
    }
}
