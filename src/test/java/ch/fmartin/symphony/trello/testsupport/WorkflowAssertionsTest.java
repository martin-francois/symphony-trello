package ch.fmartin.symphony.trello.testsupport;

import static ch.fmartin.symphony.trello.testsupport.WorkflowAssertions.assertThatWorkflow;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    @MethodSource("writableRootLayouts")
    @ParameterizedTest
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
        assertThatThrownBy(() -> assertions.hasNoAdditionalWritableRoot(root))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("shared folder");
        assertThatThrownBy(assertions::hasNoAdditionalWritableRoots)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("shared folder");
    }

    static Stream<Arguments> writableRootLayouts() {
        return Stream.of(
                argumentSet("flow mapping", "{additional_writable_roots: ['shared folder']}"),
                argumentSet(
                        "folded block scalar", "\n  additional_writable_roots:\n    - >-\n      shared\n      folder"),
                argumentSet(
                        "double-quoted scalar continued after a backslash",
                        "\n  additional_writable_roots:\n    - \"shared\\\n      \\ folder\""));
    }
}
