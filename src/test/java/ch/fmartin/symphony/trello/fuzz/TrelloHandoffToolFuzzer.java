package ch.fmartin.symphony.trello.fuzz;

import ch.fmartin.symphony.trello.agent.TrelloHandoffToolFuzzInvariants;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;

public final class TrelloHandoffToolFuzzer {
    private TrelloHandoffToolFuzzer() {}

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        TrelloHandoffToolFuzzInvariants.assertCallStaysInWriteScope(TrelloHandoffToolFuzzInvariants.execute(data));
    }
}
