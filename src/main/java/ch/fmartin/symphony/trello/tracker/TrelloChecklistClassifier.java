package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.domain.Card;
import java.util.ArrayList;
import java.util.List;

final class TrelloChecklistClassifier {
    static final String AMBIGUOUS_PREREQUISITE_CODE = "trello_prerequisite_checklist_ambiguous";
    static final String MIXED_PREREQUISITE_CODE = "trello_prerequisite_checklist_mixed";

    private TrelloChecklistClassifier() {}

    static ChecklistAnalysis analyze(Card.Checklist checklist) {
        List<PrerequisiteItem> prerequisites = new ArrayList<>();
        boolean hasAmbiguousItem = false;
        boolean hasOrdinaryItem = false;
        for (Card.ChecklistItem item : checklist.items()) {
            String text = item.text() == null ? "" : item.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            ItemClassification classification = classifyItem(text);
            if (classification instanceof ItemClassification.Exact exact) {
                prerequisites.add(new PrerequisiteItem(checklist, item, exact.reference()));
            } else if (classification instanceof ItemClassification.Ambiguous) {
                hasAmbiguousItem = true;
            } else {
                hasOrdinaryItem = true;
            }
        }
        if (hasAmbiguousItem) {
            return new ChecklistAnalysis(
                    List.of(),
                    List.of(problem(
                            AMBIGUOUS_PREREQUISITE_CODE,
                            "Checklist contains a Trello card reference that is not one exact bare prerequisite item.",
                            checklist)));
        }
        if (!prerequisites.isEmpty() && hasOrdinaryItem) {
            return new ChecklistAnalysis(
                    List.of(),
                    List.of(problem(
                            MIXED_PREREQUISITE_CODE,
                            "Checklist mixes prerequisite references with notes or ordinary Trello references.",
                            checklist)));
        }
        return new ChecklistAnalysis(List.copyOf(prerequisites), List.of());
    }

    static boolean isAmbiguousReference(String text) {
        return TrelloCardReferenceParser.containsTrelloCardUrl(text)
                && !TrelloCardReferenceParser.allTrelloCardUrlsAreMarkdownLinks(text);
    }

    private static ItemClassification classifyItem(String text) {
        return TrelloCardReferenceParser.exactReference(text)
                .<ItemClassification>map(ItemClassification.Exact::new)
                .orElseGet(
                        () -> isAmbiguousReference(text) ? ItemClassification.AMBIGUOUS : ItemClassification.ORDINARY);
    }

    private static Card.PrerequisiteProblem problem(String code, String message, Card.Checklist checklist) {
        return new Card.PrerequisiteProblem(code, message, checklist.name());
    }

    record ChecklistAnalysis(List<PrerequisiteItem> prerequisites, List<Card.PrerequisiteProblem> problems) {}

    record PrerequisiteItem(Card.Checklist checklist, Card.ChecklistItem item, TrelloCardReference reference) {}

    private sealed interface ItemClassification {
        ItemClassification ORDINARY = new Ordinary();
        ItemClassification AMBIGUOUS = new Ambiguous();

        record Exact(TrelloCardReference reference) implements ItemClassification {}

        record Ordinary() implements ItemClassification {}

        record Ambiguous() implements ItemClassification {}
    }
}
