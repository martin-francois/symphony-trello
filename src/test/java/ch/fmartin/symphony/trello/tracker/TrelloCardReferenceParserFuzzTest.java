package ch.fmartin.symphony.trello.tracker;

import static ch.fmartin.symphony.trello.tracker.TrelloReferenceFuzzInvariants.analyzeChecklist;
import static ch.fmartin.symphony.trello.tracker.TrelloReferenceFuzzInvariants.assertChecklistClassificationNeverEmitsPrerequisitesWithProblems;
import static ch.fmartin.symphony.trello.tracker.TrelloReferenceFuzzInvariants.assertReferenceParsingKeepsLookupIdsAndUrlsStable;
import static ch.fmartin.symphony.trello.tracker.TrelloReferenceFuzzInvariants.checklist;
import static ch.fmartin.symphony.trello.tracker.TrelloReferenceFuzzInvariants.parseReferences;

import ch.fmartin.symphony.trello.domain.Card;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;
import com.code_intelligence.jazzer.mutation.annotation.WithUtf8Length;
import java.util.stream.Stream;
import org.junit.jupiter.params.provider.MethodSource;

final class TrelloCardReferenceParserFuzzTest {
    @SuppressWarnings({"JUnitValueSource", "LexicographicalAnnotationListing"})
    @MethodSource("trelloReferenceTexts")
    @FuzzTest(maxDuration = "10s", maxExecutions = 20_000)
    void trelloReferenceParsingKeepsLookupIdsAndUrlsStable(@NotNull @WithUtf8Length(max = 2_048) String text) {
        // given
        String candidate = text;

        // when
        TrelloReferenceFuzzInvariants.ReferenceParsingResult result = parseReferences(candidate);

        // then
        assertReferenceParsingKeepsLookupIdsAndUrlsStable(candidate, result);
    }

    @SuppressWarnings({"JUnitValueSource", "LexicographicalAnnotationListing"})
    @MethodSource("checklistTexts")
    @FuzzTest(maxDuration = "10s", maxExecutions = 20_000)
    void checklistClassificationNeverEmitsPrerequisitesWithProblems(@NotNull @WithUtf8Length(max = 2_048) String text) {
        // given
        Card.Checklist checklist = checklist(text);

        // when
        TrelloReferenceFuzzInvariants.ChecklistClassificationResult result = analyzeChecklist(checklist);

        // then
        assertChecklistClassificationNeverEmitsPrerequisitesWithProblems(result);
    }

    private static Stream<String> trelloReferenceTexts() {
        return Stream.of(
                "",
                "https://trello.com/c/SYNTH101",
                "[card](https://trello.com/c/SYNTH101)",
                "https://trello.com/c/SYNTH101 and https://trello.com/c/SYNTH102",
                "`https://trello.com/c/SYNTH101`",
                "https://trello.com/b/BOARD/example");
    }

    private static Stream<String> checklistTexts() {
        return Stream.of(
                "",
                "https://trello.com/c/SYNTH101\nhttps://trello.com/c/SYNTH102",
                "https://trello.com/c/SYNTH101\nordinary note",
                "https://trello.com/c/SYNTH101 hello",
                "[card](https://trello.com/c/SYNTH101)");
    }
}
