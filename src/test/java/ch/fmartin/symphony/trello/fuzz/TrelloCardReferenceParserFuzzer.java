package ch.fmartin.symphony.trello.fuzz;

import ch.fmartin.symphony.trello.tracker.TrelloReferenceFuzzInvariants;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;

public final class TrelloCardReferenceParserFuzzer {
    private TrelloCardReferenceParserFuzzer() {}

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String text = data.consumeString(TrelloReferenceFuzzInvariants.MAX_TEXT_LENGTH);
        TrelloReferenceFuzzInvariants.assertReferenceParsingKeepsLookupIdsAndUrlsStable(
                text, TrelloReferenceFuzzInvariants.parseReferences(text));
    }
}
