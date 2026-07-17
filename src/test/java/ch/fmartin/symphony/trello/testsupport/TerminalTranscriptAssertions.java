package ch.fmartin.symphony.trello.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;

public final class TerminalTranscriptAssertions {
    private final String transcript;

    private TerminalTranscriptAssertions(String transcript) {
        this.transcript = transcript;
    }

    public static TerminalTranscriptAssertions assertThatTranscript(String transcript) {
        return new TerminalTranscriptAssertions(transcript);
    }

    public TerminalTranscriptAssertions containsSectionsInOrder(String... sections) {
        if (sections.length > 0) {
            assertThat(Objects.requireNonNull(transcript)).containsSubsequence(sections);
        }
        return this;
    }

    public TerminalTranscriptAssertions doesNotLeak(String secret) {
        assertThat(transcript).doesNotContain(secret);
        return this;
    }

    public TerminalTranscriptAssertions containsPrompt(String prompt) {
        assertThat(transcript).contains(prompt);
        return this;
    }
}
