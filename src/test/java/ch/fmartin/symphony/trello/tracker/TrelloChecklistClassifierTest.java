package ch.fmartin.symphony.trello.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.domain.Card;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class TrelloChecklistClassifierTest {
    private static final String TRELLO_CARD_URL_PREFIX = "https://trello.com/c/";
    private static final String TRELLO_BOARD_URL_PREFIX = "https://trello.com/b/";

    @MethodSource("ordinaryChecklists")
    @ParameterizedTest(name = "{0}")
    void ordinaryChecklistDoesNotProducePrerequisitesOrProblems(String scenario, List<String> items) {
        // given
        Card.Checklist checklist = checklist("Any name", items);

        // when
        TrelloChecklistClassifier.ChecklistAnalysis analysis = TrelloChecklistClassifier.analyze(checklist);

        // then
        assertThat(analysis.prerequisites()).as(scenario).isEmpty();
        assertThat(analysis.problems()).as(scenario).isEmpty();
    }

    private static Stream<Arguments> ordinaryChecklists() {
        return Stream.of(
                Arguments.of("empty checklist", List.of()),
                Arguments.of("blank items", List.of(" ", "\t")),
                Arguments.of("ordinary prose", List.of("Write tests", "Update docs")),
                Arguments.of("eight-letter prose is not an unambiguous bare Trello reference", List.of("Frontend")),
                Arguments.of(
                        "markdown Trello reference",
                        List.of("related to " + markdownCardLink("the API card", "ABC12345"))),
                Arguments.of(
                        "only markdown Trello references",
                        List.of(markdownCardLink("refs", "ABC12345"), markdownCardLink("other", "DEF67890"))),
                Arguments.of("non-card Trello URL", List.of(boardUrl("board123"))));
    }

    @Test
    void exactBareReferencesMakeChecklistPrerequisitesRegardlessOfName() {
        // given
        Card.Checklist checklist = checklist("Related", List.of(" " + cardUrl("ABC12345") + " ", "", "DEF67890"));

        // when
        TrelloChecklistClassifier.ChecklistAnalysis analysis = TrelloChecklistClassifier.analyze(checklist);

        // then
        assertThat(analysis.problems()).isEmpty();
        assertThat(analysis.prerequisites())
                .extracting(item -> item.reference().lookupId())
                .containsExactly("ABC12345", "DEF67890");
    }

    @MethodSource("ambiguousChecklists")
    @ParameterizedTest(name = "{0}")
    void ambiguousChecklistProducesBlockingProblem(String scenario, List<String> items, String expectedCode) {
        // given
        Card.Checklist checklist = checklist("Must finish first", items);

        // when
        TrelloChecklistClassifier.ChecklistAnalysis analysis = TrelloChecklistClassifier.analyze(checklist);

        // then
        assertThat(analysis.prerequisites()).as(scenario).isEmpty();
        assertThat(analysis.problems())
                .as(scenario)
                .extracting(Card.PrerequisiteProblem::code)
                .contains(expectedCode);
    }

    private static Stream<Arguments> ambiguousChecklists() {
        return Stream.of(
                Arguments.of(
                        "surrounding text after bare URL",
                        List.of(cardUrl("ABC12345") + " hello"),
                        TrelloChecklistClassifier.AMBIGUOUS_PREREQUISITE_CODE),
                Arguments.of(
                        "plain label before bare URL",
                        List.of("refs: " + cardUrl("ABC12345")),
                        TrelloChecklistClassifier.AMBIGUOUS_PREREQUISITE_CODE),
                Arguments.of(
                        "dependency prose before bare URL",
                        List.of("Wait for " + cardUrl("ABC12345")),
                        TrelloChecklistClassifier.AMBIGUOUS_PREREQUISITE_CODE),
                Arguments.of(
                        "exact prerequisite mixed with note",
                        List.of(cardUrl("ABC12345"), "Update docs"),
                        TrelloChecklistClassifier.MIXED_PREREQUISITE_CODE),
                Arguments.of(
                        "exact prerequisite mixed with markdown ordinary reference",
                        List.of(cardUrl("ABC12345"), markdownCardLink("refs", "DEF67890")),
                        TrelloChecklistClassifier.MIXED_PREREQUISITE_CODE),
                Arguments.of(
                        "markdown link plus separate bare URL",
                        List.of(markdownCardLink("refs", "ABC12345") + " and " + cardUrl("DEF67890")),
                        TrelloChecklistClassifier.AMBIGUOUS_PREREQUISITE_CODE),
                Arguments.of(
                        "multiple bare URLs in one item",
                        List.of(cardUrl("ABC12345") + " " + cardUrl("DEF67890")),
                        TrelloChecklistClassifier.AMBIGUOUS_PREREQUISITE_CODE),
                Arguments.of(
                        "malformed markdown",
                        List.of("[refs](" + cardUrl("ABC12345")),
                        TrelloChecklistClassifier.AMBIGUOUS_PREREQUISITE_CODE),
                Arguments.of(
                        "inline code is not the ordinary-reference escape hatch",
                        List.of("`" + cardUrl("ABC12345") + "`"),
                        TrelloChecklistClassifier.AMBIGUOUS_PREREQUISITE_CODE));
    }

    private static String cardUrl(String shortLink) {
        return TRELLO_CARD_URL_PREFIX + shortLink;
    }

    private static String boardUrl(String id) {
        return TRELLO_BOARD_URL_PREFIX + id + "/example";
    }

    private static String markdownCardLink(String label, String shortLink) {
        return "[" + label + "](" + cardUrl(shortLink) + ")";
    }

    private static Card.Checklist checklist(String name, List<String> items) {
        return new Card.Checklist(
                "checklist-1",
                name,
                items.stream()
                        .map(item -> new Card.ChecklistItem("item-" + Math.abs(item.hashCode()), item, false))
                        .toList());
    }
}
