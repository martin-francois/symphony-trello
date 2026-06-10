package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

final class TrelloBoardIdsTest {
    @CsvSource({
        "SYNTH001, SYNTH001",
        "SYNTH001/, SYNTH001",
        "SYNTH001?utm=test, SYNTH001",
        "'SYNTH001#fragment', SYNTH001",
        "SYNTH001/?utm=test, SYNTH001",
        "000000000000000000000001/, 000000000000000000000001",
        "'000000000000000000000001?utm=test', 000000000000000000000001",
        "https://trello.com/b/SYNTH001/, SYNTH001",
        "https://trello.com/b/SYNTH001/synthetic-board?utm=test, SYNTH001",
        "'https://trello.com/b/SYNTH001/synthetic-board#section', SYNTH001",
    })
    @ParameterizedTest
    void stripsHarmlessUrlDecorationsFromBoardSelectors(String selector, String expected) {
        // given
        String decoratedSelector = selector;

        // when
        String parsedConnected = TrelloBoardIds.parseConnectedBoardSelector(decoratedSelector);
        String parsedImport = TrelloBoardIds.parseImportBoardSelector(decoratedSelector);

        // then
        assertThat(parsedConnected).isEqualTo(expected);
        assertThat(parsedImport).isEqualTo(expected);
    }

    @CsvSource({
        "'Board / Name', 'Board / Name'",
        "'R&D/', 'R&D/'",
        "'What? Board', 'What? Board'",
        "'R&D?x', 'R&D?x'",
        "'Release #5', 'Release #5'",
    })
    @ParameterizedTest
    void keepsBoardNamesContainingSelectorPunctuationUntouched(String selector, String expected) {
        // given
        String nameSelector = selector;

        // when
        String parsed = TrelloBoardIds.parseConnectedBoardSelector(nameSelector);

        // then
        assertThat(parsed).isEqualTo(expected);
    }

    @Test
    void rejectsNameLikeImportBoardSelectorsInsteadOfTruncatingThem() {
        // given
        String nameSelector = "What? Board";

        // when
        Throwable thrown = catchThrowable(() -> TrelloBoardIds.parseImportBoardSelector(nameSelector));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_invalid_arguments");
            assertThat(failure).hasMessageContaining("Invalid --board value");
        });
    }
}
