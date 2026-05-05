package ch.fmartin.symphony.trello.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.TestCards;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

class CardTemplateMapTest {
    @Test
    void exposesBlockersAsTemplateFriendlyMaps() {
        // given
        Card card = new Card(
                TestCards.card("card-1", "TRELLO-abc", "Ready for Codex").id(),
                "TRELLO-abc",
                "Implement feature",
                "Description",
                null,
                "Ready for Codex",
                "list",
                "list-ready",
                "Ready for Codex",
                false,
                "board-1",
                false,
                false,
                1,
                "abc",
                "https://trello.com/c/abc",
                null,
                "https://trello.com/c/abc",
                List.of("p1"),
                List.of("label-1"),
                List.of("member-1"),
                List.of(new BlockerRef("blocker-1", "TRELLO-blocker", "Done", "https://trello.com/c/blocker")),
                null,
                null,
                null,
                null,
                null);

        // when
        Map<String, Object> template = card.toTemplateMap();

        // then
        assertThat(template.get("blocked_by"))
                .asList()
                .singleElement()
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("id", "blocker-1")
                .containsEntry("identifier", "TRELLO-blocker")
                .containsEntry("state", "Done")
                .containsEntry("url", "https://trello.com/c/blocker");
    }
}
