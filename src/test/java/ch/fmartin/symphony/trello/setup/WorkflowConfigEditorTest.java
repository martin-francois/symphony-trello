package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkflowConfigEditorTest {
    @TempDir
    Path tempDir;

    @Test
    void readsAndUpdatesCrLfWorkflowFrontMatter() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.crlf.md");
        Files.writeString(
                workflow,
                """
                ---\r
                tracker:\r
                  kind: trello\r
                  board_id: "board-1"\r
                  active_states:\r
                    - "Ready for Codex"\r
                  terminal_states:\r
                    - "Done"\r
                server:\r
                  port: 18080\r
                codex:\r
                  command: codex app-server\r
                ---\r
                # Body\r
                """,
                StandardCharsets.UTF_8);
        WorkflowConfigEditor editor = new WorkflowConfigEditor();

        // when
        WorkflowListConfiguration lists = editor.listConfiguration(workflow);
        editor.updateServerPort(workflow, 18081);

        // then
        assertThat(lists.activeStates()).containsExactly("Ready for Codex");
        assertThat(editor.serverPort(workflow)).contains(18081);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("# Body");
    }

    @Test
    void treatsNullYamlFrontMatterAsMissingMaxAgents() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.null.md");
        Files.writeString(
                workflow,
                """
                ---
                null
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        WorkflowConfigEditor editor = new WorkflowConfigEditor();

        // when
        var maxAgents = editor.maxAgents(workflow);
        var maxAgentsSetting = editor.maxAgentsSetting(workflow);

        // then
        assertThat(maxAgents).isEmpty();
        assertThat(maxAgentsSetting.diagnosticsCell()).isEmpty();
    }

    @Test
    void reportsOverlappingListRolesAsInvalidWorkflowConfiguration() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.overlap.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "board-1"
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Done"
                  in_progress_state: "In Progress"
                  blocked_state: "Ready  for Codex"
                server:
                  port: 18080
                codex:
                  command: codex app-server
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        WorkflowConfigEditor editor = new WorkflowConfigEditor();

        // when
        WorkflowValidation validation = editor.diagnosticsValidation(workflow);

        // then
        assertThat(validation.ok()).isFalse();
        assertThat(validation.message()).isEqualTo("overlapping tracker list roles");
    }

    @Test
    void validateReportsOverlappingListRolesForConnectedBoard() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.connected-overlap.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "board-1"
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Ready  for Codex"
                server:
                  port: 18080
                codex:
                  command: codex app-server
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        ConnectedBoard board = new ConnectedBoard(
                "board-1",
                "abc123",
                "Overlap Queue",
                "https://trello.example/abc123",
                workflow,
                tempDir.resolve(".env"),
                tempDir.resolve("workspaces"),
                18080,
                false,
                List.of(),
                false);
        WorkflowConfigEditor editor = new WorkflowConfigEditor();

        // when
        WorkflowValidation validation = editor.validate(board, ignored -> Optional.empty());

        // then
        assertThat(validation.ok()).isFalse();
        assertThat(validation.message())
                .contains(
                        "Workflow tracker list roles overlap for \"Overlap Queue\"", "overlapping tracker list roles");
    }

    @Test
    void permitsOverlappingListNamesWhenListIdsAreAuthoritative() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.list-ids.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "board-1"
                  active_states:
                    - "Done"
                  active_list_ids:
                    - "list-active-done"
                  terminal_states:
                    - "Done"
                  terminal_list_ids:
                    - "list-terminal-done"
                  blocked_state: "Done"
                server:
                  port: 18080
                codex:
                  command: codex app-server
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        WorkflowConfigEditor editor = new WorkflowConfigEditor();

        // when
        WorkflowValidation validation = editor.diagnosticsValidation(workflow);

        // then
        assertThat(validation.ok()).isTrue();
    }

    @Test
    void reportsOverlappingListIdsAsInvalidWorkflowConfiguration() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.list-id-overlap.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "board-1"
                  active_states:
                    - "Ready for Codex"
                  active_list_ids:
                    - "shared-list-id"
                  terminal_states:
                    - "Done"
                  terminal_list_ids:
                    - "shared-list-id"
                server:
                  port: 18080
                codex:
                  command: codex app-server
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        WorkflowConfigEditor editor = new WorkflowConfigEditor();

        // when
        WorkflowValidation validation = editor.diagnosticsValidation(workflow);

        // then
        assertThat(validation.ok()).isFalse();
        assertThat(validation.message()).isEqualTo("overlapping tracker list roles");
    }

    @Test
    void permitsDuplicateValuesWithinOneListRole() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.duplicate-role-values.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "board-1"
                  active_states:
                    - "Ready for Codex"
                    - "Ready  for Codex"
                  terminal_states:
                    - "Done"
                    - "Done "
                server:
                  port: 18080
                codex:
                  command: codex app-server
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        WorkflowConfigEditor editor = new WorkflowConfigEditor();

        // when
        WorkflowValidation validation = editor.diagnosticsValidation(workflow);

        // then
        assertThat(validation.ok()).isTrue();
    }
}
