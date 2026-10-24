package ch.fmartin.symphony.trello.fuzz;

import ch.fmartin.symphony.trello.tracker.TrelloCardPayloadFuzzInvariants;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;

public final class TrelloCardPayloadFuzzer {
    private TrelloCardPayloadFuzzer() {}

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        TrelloCardPayloadFuzzInvariants.assertMappingKeepsItsContract(
                TrelloCardPayloadFuzzInvariants.mapCardPayload(data));
    }
}
