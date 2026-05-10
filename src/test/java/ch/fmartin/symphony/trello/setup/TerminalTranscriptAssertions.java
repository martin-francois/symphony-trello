package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

final class TerminalTranscriptAssertions {
    private final String transcript;

    private TerminalTranscriptAssertions(String transcript) {
        this.transcript = transcript;
    }

    static TerminalTranscriptAssertions assertThatTranscript(String transcript) {
        return new TerminalTranscriptAssertions(transcript);
    }

    TerminalTranscriptAssertions containsSectionsInOrder(String... sections) {
        int cursor = 0;
        for (String section : sections) {
            int next = transcript.indexOf(section, cursor);
            assertThat(next)
                    .as("section %s appears after offset %s", section, cursor)
                    .isGreaterThanOrEqualTo(cursor);
            cursor = next + section.length();
        }
        return this;
    }

    TerminalTranscriptAssertions doesNotLeak(String secret) {
        assertThat(transcript).doesNotContain(secret);
        return this;
    }

    TerminalTranscriptAssertions containsPrompt(String prompt) {
        assertThat(transcript).contains(prompt);
        return this;
    }
}
