package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
