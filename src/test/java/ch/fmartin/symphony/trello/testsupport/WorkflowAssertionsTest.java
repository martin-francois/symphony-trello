package ch.fmartin.symphony.trello.testsupport;

import static ch.fmartin.symphony.trello.testsupport.WorkflowAssertions.assertThatWorkflow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WorkflowAssertionsTest {
    @TempDir
    Path tempDir;

    @Test
    void writableRootMustAppearInCodexConfiguration() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Path root = Path.of("shared");
        Files.writeString(
                workflow,
                """
                ---
                workspace:
                  root: shared
                codex: {}
                ---
                shared
                """);

        // when
        WorkflowAssertions assertions = assertThatWorkflow(workflow);

        // then
        assertThatThrownBy(() -> assertions.hasAdditionalWritableRoot(root)).isInstanceOf(AssertionError.class);
        assertions.hasNoAdditionalWritableRoot(root).hasNoAdditionalWritableRoots();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{additional_writable_roots: ['shared folder']}",
                "\n  additional_writable_roots:\n    - >-\n      shared\n      folder"
            })
    void writableRootsAreParsedRegardlessOfYamlLayout(String codexYaml) throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                codex: %s
                ---
                """
                        .formatted(codexYaml));

        Path root = Path.of("shared folder");

        // when
        WorkflowAssertions assertions = assertThatWorkflow(workflow);

        // then
        assertions.hasAdditionalWritableRoot(root);
        assertThatThrownBy(() -> assertions.hasNoAdditionalWritableRoot(root)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(assertions::hasNoAdditionalWritableRoots).isInstanceOf(AssertionError.class);
    }
}
