package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import ch.fmartin.symphony.trello.setup.CodexModelSelectionDefaults.ReasoningEffortOption;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CodexModelSelectionDefaultsTest {
    @ParameterizedTest
    @ValueSource(ints = {0, '\n', '\r', 0x1B, 0x85})
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

    @ParameterizedTest
    @ValueSource(ints = {0, '\n', '\r', 0x1B, 0x85})
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
}
