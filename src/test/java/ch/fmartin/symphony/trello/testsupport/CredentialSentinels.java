package ch.fmartin.symphony.trello.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

/// Conspicuous fixed credential values for terminal-snapshot scenarios.
///
/// Snapshot scenarios pass these as Trello credentials. Before a transcript is compared with its
/// baseline, [TerminalSnapshots] checks that no sentinel appears in any captured stream. The
/// sentinels are deliberately never registered with [TranscriptNormalizer]: replacing them with a
/// placeholder would hide the leak this check exists to catch.
public final class CredentialSentinels {
    public static final String TRELLO_API_KEY = "sentinel-trello-api-key-must-not-be-printed";
    public static final String TRELLO_API_TOKEN = "sentinel-trello-api-token-must-not-be-printed";

    static final List<String> ALL = List.of(TRELLO_API_KEY, TRELLO_API_TOKEN);

    private CredentialSentinels() {}

    static void assertAbsent(String scenario, String streamName, String output) {
        assertThat(output)
                .as("snapshot %s must not print a credential sentinel on %s", scenario, streamName)
                .doesNotContain(ALL);
    }
}
