package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.domain.Card;
import java.util.Arrays;
import java.util.List;

public final class TrelloReferenceFuzzInvariants {
    public static final int MAX_TEXT_LENGTH = 2_048;
    public static final int MAX_CHECKLIST_ITEMS = 8;
    private static final String TRELLO_CARD_URL_PREFIX = "https://trello.com/c/";

    private TrelloReferenceFuzzInvariants() {}

    public static ReferenceParsingResult parseReferences(String text) {
        return new ReferenceParsingResult(
                TrelloCardReferenceParser.containsTrelloCardUrl(text),
                TrelloCardReferenceParser.referencesIn(text).stream()
                        .map(reference -> new ParsedReference(reference.lookupId(), reference.url()))
                        .toList());
    }

    public static void assertReferenceParsingKeepsLookupIdsAndUrlsStable(String text, ReferenceParsingResult result) {
        if (result.containsCardUrl() != !result.references().isEmpty()) {
            throw new AssertionError("containsCardUrl disagrees with parsed references");
        }
        for (ParsedReference reference : result.references()) {
            if (reference.lookupId().isBlank()) {
                throw new AssertionError("blank Trello lookup id");
            }
            if (!reference.lookupId().matches("[A-Za-z0-9]+")) {
                throw new AssertionError("invalid Trello lookup id");
            }
            if (!reference.url().equals(TRELLO_CARD_URL_PREFIX + reference.lookupId())) {
                throw new AssertionError("Trello reference URL is not normalized from the lookup id");
            }
        }
        if (!result.containsCardUrl() && !TrelloCardReferenceParser.allTrelloCardUrlsAreMarkdownLinks(text)) {
            throw new AssertionError("markdown-only URL detection disagrees with containsCardUrl");
        }
    }

    public static ChecklistClassificationResult analyzeChecklist(Card.Checklist checklist) {
        TrelloChecklistClassifier.ChecklistAnalysis analysis = TrelloChecklistClassifier.analyze(checklist);
        return new ChecklistClassificationResult(
                analysis.problems().stream().map(Card.PrerequisiteProblem::code).toList(),
                analysis.prerequisites().stream()
                        .map(item -> new ParsedReference(
                                item.reference().lookupId(), item.reference().url()))
                        .toList());
    }

    public static void assertChecklistClassificationNeverEmitsPrerequisitesWithProblems(
            ChecklistClassificationResult result) {
        if (!result.problemCodes().isEmpty()) {
            if (!result.prerequisiteReferences().isEmpty()) {
                throw new AssertionError("problem checklist emitted scheduler prerequisites");
            }
            for (String code : result.problemCodes()) {
                if (!TrelloChecklistClassifier.AMBIGUOUS_PREREQUISITE_CODE.equals(code)
                        && !TrelloChecklistClassifier.MIXED_PREREQUISITE_CODE.equals(code)) {
                    throw new AssertionError("unexpected checklist prerequisite problem code");
                }
            }
        }
        for (ParsedReference reference : result.prerequisiteReferences()) {
            if (!reference.url().equals(TRELLO_CARD_URL_PREFIX + reference.lookupId())) {
                throw new AssertionError("Trello prerequisite URL is not normalized from the lookup id");
            }
        }
    }

    public static Card.Checklist checklist(String text) {
        return new Card.Checklist("checklist-1", "Prerequisites", checklistItems(text));
    }

    private static List<Card.ChecklistItem> checklistItems(String text) {
        return Arrays.stream(text.split("\\R", -1))
                .limit(MAX_CHECKLIST_ITEMS)
                .map(item -> new Card.ChecklistItem("item-" + Integer.toUnsignedString(item.hashCode()), item, false))
                .toList();
    }

    public record ReferenceParsingResult(boolean containsCardUrl, List<ParsedReference> references) {}

    public record ChecklistClassificationResult(
            List<String> problemCodes, List<ParsedReference> prerequisiteReferences) {}

    public record ParsedReference(String lookupId, String url) {}
}
