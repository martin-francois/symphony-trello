package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    @MethodSource("serverPortClassifications")
    @ParameterizedTest
    void classifiesWorkflowServerPortForDiagnostics(
            String name, String portLine, WorkflowConfigEditor.WorkflowServerPortClassification.Kind expected)
            throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.classify.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "board-1"
                %s
                ---
                Body
                """
                        .formatted(portLine),
                StandardCharsets.UTF_8);
        WorkflowConfigEditor editor = new WorkflowConfigEditor();

        // when
        WorkflowConfigEditor.WorkflowServerPortClassification classification =
                editor.classifyServerPortForDiagnostics(workflow);

        // then
        assertThat(classification.kind()).as(name).isEqualTo(expected);
    }

    private static Stream<Arguments> serverPortClassifications() {
        return Stream.of(
                Arguments.of(
                        "valid port",
                        "server:\n  port: 18080",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.VALID),
                Arguments.of(
                        "omitted port",
                        "server: {}",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.OMITTED),
                Arguments.of(
                        "negative port",
                        "server:\n  port: -1",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.OUT_OF_RANGE),
                Arguments.of(
                        "port above range",
                        "server:\n  port: 99999",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.OUT_OF_RANGE),
                Arguments.of(
                        "non-numeric port",
                        "server:\n  port: \"not-a-port\"",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.INVALID_VALUE),
                Arguments.of(
                        "unresolved environment reference",
                        "server:\n  port: $MISSING_PORT_VARIABLE",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.OMITTED),
                Arguments.of(
                        "whole-valued float normalizes",
                        "server:\n  port: 18080.0",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.VALID),
                Arguments.of(
                        "fractional port is invalid, not truncated",
                        "server:\n  port: 18080.5",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.INVALID_VALUE),
                Arguments.of(
                        "whole but huge port is invalid",
                        "server:\n  port: 99999999999999999999999",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.INVALID_VALUE),
                Arguments.of(
                        "zero stays in range for ephemeral runs",
                        "server:\n  port: 0",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.VALID));
    }

    @MethodSource("environmentResolvedServerPortClassifications")
    @ParameterizedTest
    void classifiesEnvironmentResolvedServerPortForDiagnostics(
            String caseName, String resolvedValue, WorkflowConfigEditor.WorkflowServerPortClassification.Kind expected)
            throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.env-classify.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: "board-1"
                server:
                  port: $STATUS_PORT
                ---
                Body
                """,
                StandardCharsets.UTF_8);
        WorkflowConfigEditor editor = new WorkflowConfigEditor();
        Function<String, Optional<String>> resolver =
                variable -> "STATUS_PORT".equals(variable) ? Optional.ofNullable(resolvedValue) : Optional.empty();

        // when
        WorkflowConfigEditor.WorkflowServerPortClassification classification =
                editor.classifyServerPortForDiagnostics(workflow, resolver);

        // then
        assertThat(classification.kind()).as(caseName).isEqualTo(expected);
    }

    private static Stream<Arguments> environmentResolvedServerPortClassifications() {
        return Stream.of(
                Arguments.of(
                        "resolved valid port",
                        "20991",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.VALID),
                Arguments.of(
                        "resolved out-of-range port",
                        "99999",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.OUT_OF_RANGE),
                Arguments.of(
                        "resolved non-numeric port",
                        "not-a-port",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.INVALID_VALUE),
                Arguments.of(
                        "resolved whole-valued float normalizes",
                        "18080.0",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.VALID),
                Arguments.of(
                        "resolved fractional port is invalid, not truncated",
                        "18080.5",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.INVALID_VALUE),
                Arguments.of(
                        "unresolved reference",
                        null,
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.OMITTED),
                Arguments.of(
                        "blank resolved value",
                        "   ",
                        WorkflowConfigEditor.WorkflowServerPortClassification.Kind.OMITTED));
    }

    @Test
    void classifiesMissingWorkflowFileAsUnreadableForDiagnostics() {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.does-not-exist.md");
        WorkflowConfigEditor editor = new WorkflowConfigEditor();

        // when
        WorkflowConfigEditor.WorkflowServerPortClassification classification =
                editor.classifyServerPortForDiagnostics(workflow);

        // then
        assertThat(classification.kind())
                .isEqualTo(WorkflowConfigEditor.WorkflowServerPortClassification.Kind.UNREADABLE);
        assertThat(classification.probeOrSkipPort()).isEmpty();
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
