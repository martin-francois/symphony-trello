package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class CliValueNormalizerTest {
    @Test
    void commaSeparatedValuesPreserveEmptyAndTrailingFields() {
        // given
        String valuesWithEmptyFields = "alpha,,beta,";

        // when
        var fields = CliValueNormalizer.commaSeparatedValues(valuesWithEmptyFields);
        var emptyInputFields = CliValueNormalizer.commaSeparatedValues("");

        // then
        assertThat(fields).containsExactly("alpha", "", "beta", "");
        assertThat(emptyInputFields).containsExactly("");
    }
}
