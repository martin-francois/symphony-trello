package ch.fmartin.symphony.trello.domain;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.TestCards;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;

final class CardTemplateMapTest {
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
                "https://trello.com/c/SYNTH101",
                null,
                "https://trello.com/c/SYNTH101",
                List.of("p1"),
                List.of("label-1"),
                List.of("member-1"),
                List.of(new Card.Checklist(
                        "checklist-1",
                        "Must finish first",
                        List.of(new Card.ChecklistItem("checkitem-1", "https://trello.com/c/SYNTH102", true)))),
                List.of(new Card.Attachment("attachment-1", "Design card", "https://trello.com/c/SYNTH103")),
                List.of(new Card.TrelloReference(
                        "description",
                        "See https://trello.com/c/SYNTH102",
                        "SYNTH102",
                        "TRELLO-blocker",
                        "Blocking card",
                        "Done",
                        "https://trello.com/c/SYNTH102",
                        "found",
                        true)),
                List.of(new Card.PrerequisiteProblem(
                        "trello_prerequisite_checklist_ambiguous", "Fix the checklist shape.", "Related")),
                List.of(new BlockerRef("blocker-1", "TRELLO-blocker", "Done", "https://trello.com/c/SYNTH102")),
                List.of(new Card.Comment(
                        "comment-1",
                        "Please handle the review note.",
                        "Reviewer",
                        Instant.parse("2026-01-03T00:00:00Z"))),
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
                .containsEntry("url", "https://trello.com/c/SYNTH102");
        assertThat(template.get("checklists"))
                .asList()
                .singleElement()
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("id", "checklist-1")
                .containsEntry("name", "Must finish first");
        assertThat(template.get("attachments"))
                .asList()
                .singleElement()
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("id", "attachment-1")
                .containsEntry("url", "https://trello.com/c/SYNTH103");
        assertThat(template.get("trello_references"))
                .asList()
                .singleElement()
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("source", "description")
                .containsEntry("lookup_id", "SYNTH102")
                .containsEntry("terminal", true);
        assertThat(template.get("prerequisite_problems"))
                .asList()
                .singleElement()
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("code", "trello_prerequisite_checklist_ambiguous")
                .containsEntry("checklist", "Related");
        assertThat(template.get("comments"))
                .asList()
                .singleElement()
                .asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("id", "comment-1")
                .containsEntry("text", "Please handle the review note.")
                .containsEntry("author", "Reviewer")
                .containsEntry("created_at", Instant.parse("2026-01-03T00:00:00Z"));
    }
}
