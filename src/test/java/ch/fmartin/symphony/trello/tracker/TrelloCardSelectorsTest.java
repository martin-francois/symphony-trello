package ch.fmartin.symphony.trello.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class TrelloCardSelectorsTest {
    @CsvSource({
        "SYNTH101,SYNTH101",
        "000000000000000000000101,000000000000000000000101",
        "' AbCdEfGh ',AbCdEfGh",
        "https://trello.com/c/SYNTH101/add-snapshot-tests,SYNTH101",
        "HTTPS://TRELLO.COM/c/SYNTH101,SYNTH101"
    })
    @ParameterizedTest
    void acceptsIdsShortLinksAndCardUrls(String selector, String expected) {
        // given

        // when
        Optional<String> lookupId = TrelloCardSelectors.lookupId(selector);

        // then
        assertThat(lookupId).hasValue(expected);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"", "../boards/board-1", "https://trello.com/b/SYNTH001/board", "SYNTH 101", "SYNTH101?key=x"})
    void rejectsAnythingElse(String selector) {
        // given

        // when
        Optional<String> lookupId = TrelloCardSelectors.lookupId(selector);

        // then
        assertThat(lookupId).isEmpty();
    }
}
