package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class PromptSupportTest {
    @CsvSource(
            nullValues = "END_OF_INPUT",
            value = {
                "END_OF_INPUT, true",
                "'', true",
                "'  ', true",
                "Y, true",
                "yes, true",
                "n, false",
                "No, false",
                "maybe, false"
            })
    @ParameterizedTest
    void defaultYesAnswerAcceptsOnlyBlankEndOfInputOrYes(String answer, boolean expected) {
        // given
        String typed = answer;

        // when
        boolean accepted = PromptSupport.yesDefaultTrue(typed);

        // then
        assertThat(accepted)
                .as("a [Y/n] prompt accepts a blank answer, end of input, or an answer starting with y")
                .isEqualTo(expected);
    }
}
