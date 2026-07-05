package ch.fmartin.symphony.trello.fuzz;

import ch.fmartin.symphony.trello.domain.Card;
import ch.fmartin.symphony.trello.tracker.TrelloReferenceFuzzInvariants;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;

public final class TrelloChecklistClassifierFuzzer {
    private TrelloChecklistClassifierFuzzer() {}

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String text = data.consumeString(TrelloReferenceFuzzInvariants.MAX_TEXT_LENGTH);
        Card.Checklist checklist = TrelloReferenceFuzzInvariants.checklist(text);
        TrelloReferenceFuzzInvariants.ChecklistClassificationResult result =
                TrelloReferenceFuzzInvariants.analyzeChecklist(checklist);
        TrelloReferenceFuzzInvariants.assertChecklistClassificationNeverEmitsPrerequisitesWithProblems(result);
    }
}
