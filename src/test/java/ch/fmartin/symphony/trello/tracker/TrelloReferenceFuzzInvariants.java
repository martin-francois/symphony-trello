package ch.fmartin.symphony.trello.tracker;

import ch.fmartin.symphony.trello.domain.Card;
import com.google.common.base.Splitter;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class TrelloReferenceFuzzInvariants {
    public static final int MAX_TEXT_LENGTH = 2_048;
    public static final int MAX_CHECKLIST_ITEMS = 8;
    private static final String TRELLO_CARD_URL_PREFIX = "https://trello.com/c/";
    private static final Splitter LOGICAL_LINE_BREAK = Splitter.onPattern("\\R");

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
        // Each reference consumes the URL prefix plus at least one id character, and references do not overlap.
        if (result.references().size() > text.length() / (TRELLO_CARD_URL_PREFIX.length() + 1)) {
            throw new AssertionError("more Trello references than the text can hold");
        }
        assertNormalizedUrlsParseBackToTheSameReferences(result.references());
    }

    private static void assertNormalizedUrlsParseBackToTheSameReferences(List<ParsedReference> references) {
        List<String> urls = references.stream().map(ParsedReference::url).toList();
        if (!parseReferences(String.join(" ", urls)).references().equals(references)) {
            throw new AssertionError("normalized Trello URLs do not parse back to the same references");
        }
        for (ParsedReference reference : references) {
            if (!TrelloCardReferenceParser.exactReference(reference.url())
                    .map(exact -> new ParsedReference(exact.lookupId(), exact.url()))
                    .equals(Optional.of(reference))) {
                throw new AssertionError("a normalized Trello URL is not an exact reference to itself");
            }
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

    /// Checks properties that compare the classification of `checklist` with related checklists: item
    /// order must not change the outcome, and a checklist rebuilt from the normalized prerequisite URLs must
    /// classify to the same prerequisites.
    public static void assertChecklistClassificationIsStable(
            Card.Checklist checklist, ChecklistClassificationResult result) {
        ChecklistClassificationResult reversed = analyzeChecklist(new Card.Checklist(
                checklist.id(), checklist.name(), checklist.items().reversed()));
        if (!reversed.problemCodes().equals(result.problemCodes())
                || !reversed.prerequisiteReferences()
                        .equals(result.prerequisiteReferences().reversed())) {
            throw new AssertionError("checklist classification depends on item order");
        }
        if (result.prerequisiteReferences().isEmpty()) {
            return;
        }
        String normalized = result.prerequisiteReferences().stream()
                .map(ParsedReference::url)
                .collect(Collectors.joining("\n"));
        if (!analyzeChecklist(checklist(normalized)).equals(result)) {
            throw new AssertionError("normalized prerequisite URLs do not classify back to the same prerequisites");
        }
    }

    public static Card.Checklist checklist(String text) {
        return new Card.Checklist("checklist-1", "Prerequisites", checklistItems(text));
    }

    private static List<Card.ChecklistItem> checklistItems(String text) {
        return LOGICAL_LINE_BREAK
                .splitToStream(text)
                .limit(MAX_CHECKLIST_ITEMS)
                .map(item -> new Card.ChecklistItem("item-" + Integer.toUnsignedString(item.hashCode()), item, false))
                .toList();
    }

    public record ReferenceParsingResult(boolean containsCardUrl, List<ParsedReference> references) {}

    public record ChecklistClassificationResult(
            List<String> problemCodes, List<ParsedReference> prerequisiteReferences) {}

    public record ParsedReference(String lookupId, String url) {}
}
