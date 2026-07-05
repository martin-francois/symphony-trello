package ch.fmartin.symphony.trello.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WorkflowLoaderTest {
    @TempDir
    Path tempDir;

    private final WorkflowLoader loader = new WorkflowLoader();

    @Test
    void loadsCurrentWorkflowWithoutAddingGeneratedMetadataOrSandboxPolicy() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        String original = workflowWithBody(
                """
                ## Operating Posture

                Current workflow body.
                """);
        Files.writeString(workflow, original, StandardCharsets.UTF_8);

        // when
        WorkflowDefinition loaded = loader.load(workflow);

        // then
        assertThat(loaded.config()).doesNotContainKey("symphony");
        assertThat(codex(loaded)).doesNotContainKey("turn_sandbox_policy");
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo(original);
    }

    @Test
    void preservesHandAuthoredSymphonySectionAsPlainConfig() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.hand-authored-symphony.md");
        String original = workflowWithExtraFrontMatter(
                "symphony: local-notes",
                """
                # Custom workflow

                This workflow uses a private top-level symphony note.
                """);
        Files.writeString(workflow, original, StandardCharsets.UTF_8);

        // when
        WorkflowDefinition loaded = loader.load(workflow);

        // then
        assertThat(loaded.config()).containsEntry("symphony", "local-notes");
        assertThat(codex(loaded)).doesNotContainKey("turn_sandbox_policy");
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo(original);
    }

    @Test
    void loadsExistingWorkflowBodyWithoutAdaptingOrRejectingIt() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.current-unmarked.md");
        String original = workflowWithBody(
                """
                ## Operating Posture

                This is an unattended orchestration run.

                ## Pull Request Publication

                Run relevant validation, then record the branch, commit, and validation evidence.

                ## Trello List Routing

                Card URL: {{ card.url }}
                """);
        Files.writeString(workflow, original, StandardCharsets.UTF_8);

        // when
        WorkflowDefinition loaded = loader.load(workflow);

        // then
        assertThat(codex(loaded)).doesNotContainKey("turn_sandbox_policy");
        assertThat(loaded.promptTemplate()).contains("validation evidence");
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"~:\n", "repository:\n"})
    void rejectsNullTopLevelFrontMatterEntries(String frontMatter) throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.null-top-level.md");
        Files.writeString(workflow, workflowWithExtraFrontMatter(frontMatter, "Body"), StandardCharsets.UTF_8);

        // when
        WorkflowException failure = catchThrowableOfType(() -> loader.load(workflow), WorkflowException.class);

        // then
        assertThat(failure.code()).isEqualTo("workflow_parse_error");
        assertThat(failure).hasMessage("Workflow front matter top-level keys and values must not be null");
        assertThat(workflow).content(StandardCharsets.UTF_8).contains(frontMatter);
    }

    @Test
    void preservesNestedNullConfigValues() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.nested-null.md");
        String original = workflowWithExtraFrontMatter(
                """
                repository:
                  default_url: null
                  default_path: null
                """,
                "Body");
        Files.writeString(workflow, original, StandardCharsets.UTF_8);

        // when
        WorkflowDefinition loaded = loader.load(workflow);

        // then
        assertThat(repository(loaded)).containsEntry("default_url", null).containsEntry("default_path", null);
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo(original);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> codex(WorkflowDefinition loaded) {
        return (Map<String, Object>) loaded.config().get("codex");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> repository(WorkflowDefinition loaded) {
        return (Map<String, Object>) loaded.config().get("repository");
    }

    private static String workflowWithBody(String body) {
        return workflowWithExtraFrontMatter("", body);
    }

    private static String workflowWithExtraFrontMatter(String extraFrontMatter, String body) {
        return """
                ---
                tracker:
                  kind: trello
                  board_id: board-1
                server:
                  port: 18080
                codex:
                  command: codex app-server
                %s
                ---
                %s
                """
                .formatted(extraFrontMatter.stripTrailing(), body);
    }
}
