package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.setup.WorkflowFileText.LineEnding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WorkflowFileTextTest {
    private static final String LF_WORKFLOW = "---\ntracker:\n  kind: trello\n---\n# Card\n\nBody line.\n";

    @Test
    void splitsAfterTheClosingFrontMatterLineAndKeepsEveryByte() {
        // given
        String content = LF_WORKFLOW;

        // when
        WorkflowFileText text = WorkflowFileText.parse(content).orElseThrow();

        // then
        assertThat(text.metadata()).isEqualTo("---\ntracker:\n  kind: trello\n---\n");
        assertThat(text.body()).isEqualTo("# Card\n\nBody line.\n");
        assertThat(text.lineEnding()).isEqualTo(LineEnding.LF_ONLY);
    }

    @Test
    void treatsCrlfAsTheSameBodyAndWritesReplacementsInCrlf() {
        // given
        String crlfWorkflow = LF_WORKFLOW.replace("\n", "\r\n");
        WorkflowFileText text = WorkflowFileText.parse(crlfWorkflow).orElseThrow();

        // when
        String replaced = text.withBody("Before\r\n", "# New\n\nText.\n", "After\r\n");

        // then
        assertThat(text.normalizedBody()).isEqualTo("# Card\n\nBody line.\n");
        assertThat(text.lineEnding()).isEqualTo(LineEnding.CRLF_ONLY);
        assertThat(text.rawBodyOffset("# Card\n\n".length())).isEqualTo("# Card\r\n\r\n".length());
        assertThat(replaced)
                .isEqualTo("---\r\ntracker:\r\n  kind: trello\r\n---\r\nBefore\r\n# New\r\n\r\nText.\r\nAfter\r\n");
    }

    @Test
    void keepsGeneratedLfTextForMixedLineEndings() {
        // given
        WorkflowFileText text =
                WorkflowFileText.parse("---\na: 1\n---\nOne\r\nTwo\n").orElseThrow();

        // when
        String replaced = text.withBody("", "New\nText\n", "");

        // then
        assertThat(text.lineEnding()).isEqualTo(LineEnding.MIXED);
        assertThat(replaced).isEqualTo("---\na: 1\n---\nNew\nText\n");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "# No front matter\n",
                "---\ntracker: {}\n",
                "---\ntracker: {}\r---\nBody\n",
                "Intro\n---\ntracker: {}\n---\nBody\n"
            })
    void rejectsFilesWithoutACompleteFrontMatterSplit(String content) {
        // given
        String workflow = content;

        // when
        var parsed = WorkflowFileText.parse(workflow);

        // then
        assertThat(parsed).isEmpty();
    }

    @Test
    void acceptsAClosingLineWithSurroundingWhitespaceLikeTheRuntimeLoader() {
        // given
        String content = "---\na: 1\n  ---  \nBody\n";

        // when
        WorkflowFileText text = WorkflowFileText.parse(content).orElseThrow();

        // then
        assertThat(text.body()).isEqualTo("Body\n");
    }
}
