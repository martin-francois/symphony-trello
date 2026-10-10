package ch.fmartin.symphony.trello.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class TrelloCardSelectorsTest {
    @CsvSource({
        "Ready123,Ready123",
        "5f1e2d3c4b5a69788796a5b4,5f1e2d3c4b5a69788796a5b4",
        "' AbCdEfGh ',AbCdEfGh",
        "https://trello.com/c/Ready123/add-snapshot-tests,Ready123",
        "HTTPS://TRELLO.COM/c/Ready123,Ready123"
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
            strings = {"", "../boards/board-1", "https://trello.com/b/SYNTH001/board", "Ready 123", "Ready123?key=x"})
    void rejectsAnythingElse(String selector) {
        // given

        // when
        Optional<String> lookupId = TrelloCardSelectors.lookupId(selector);

        // then
        assertThat(lookupId).isEmpty();
    }
}
