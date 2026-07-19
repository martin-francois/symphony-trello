package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.WorkflowServerPortClassification;
import ch.fmartin.symphony.trello.workflow.CodexSandboxPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
                """);
        var editor = new WorkflowConfigEditor();

        // when
        WorkflowListConfiguration lists = editor.listConfiguration(workflow);
        editor.updateServerPort(workflow, 18081);

        // then
        assertThat(lists.activeStates()).containsExactly("Ready for Codex");
        assertThat(editor.serverPort(workflow)).hasValue(18081);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("# Body");
    }

    @Test
    void updatesOneCharacterWorkflowFileName() throws Exception {
        // given
        Path workflow = tempDir.resolve("x");
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
                server:
                  port: 18080
                codex:
                  command: codex app-server
                ---
                # Body
                """);
        var editor = new WorkflowConfigEditor();

        // when
        editor.updateServerPort(workflow, 18081);

        // then
        assertThat(editor.serverPort(workflow)).hasValue(18081);
        assertThat(workflow).content(StandardCharsets.UTF_8).contains("# Body");
    }

    @Test
    void updateServerPortPreservesWorkflowSymlinkAndUpdatesTarget() throws Exception {
        // given
        Path target = tempDir.resolve("WORKFLOW.target.md");
        Path link = tempDir.resolve("WORKFLOW.link.md");
        Files.writeString(target, workflowWithBody("# Body"));
        try {
            Files.createSymbolicLink(link, target.getFileName());
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links are not available: " + e.getMessage());
        }
        assertThat(link).isSymbolicLink();
        var editor = new WorkflowConfigEditor();

        // when
        editor.updateServerPort(link, 18081);

        // then
        assertThat(link).isSymbolicLink();
        assertThat(editor.serverPort(target)).hasValue(18081);
        assertThat(link).content(StandardCharsets.UTF_8).contains("port: 18081", "# Body");
        assertThat(target).content(StandardCharsets.UTF_8).contains("port: 18081", "# Body");
    }

    @Test
    void updateServerPortPreservesPosixPermissionsWhenSupported() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.permissions.md");
        Files.writeString(workflow, workflowWithBody("# Body"));
        assumeTrue(Files.getFileStore(workflow).supportsFileAttributeView("posix"));
        Set<PosixFilePermission> permissions = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        Files.setPosixFilePermissions(workflow, permissions);
        assertThat(Files.getPosixFilePermissions(workflow)).hasSameElementsAs(permissions);
        var editor = new WorkflowConfigEditor();

        // when
        editor.updateServerPort(workflow, 18081);

        // then
        assertThat(editor.serverPort(workflow)).hasValue(18081);
        assertThat(Files.getPosixFilePermissions(workflow)).hasSameElementsAs(permissions);
    }

    @Test
    void updateServerPortLeavesOriginalContentWhenWorkflowIsReadOnly() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.read-only-update.md");
        String original = workflowWithBody("# Body");
        Files.writeString(workflow, original);
        assumeTrue(Files.getFileStore(workflow).supportsFileAttributeView("posix"));
        Set<PosixFilePermission> readOnlyPermissions = Set.of(PosixFilePermission.OWNER_READ);
        Files.setPosixFilePermissions(workflow, readOnlyPermissions);
        assertThat(Files.getPosixFilePermissions(workflow)).hasSameElementsAs(readOnlyPermissions);
        var editor = new WorkflowConfigEditor();

        // when
        Throwable thrown = catchThrowable(() -> editor.updateServerPort(workflow, 18081));

        // then
        assertThat(thrown).isInstanceOf(IOException.class).hasMessageContaining("cannot be updated");
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo(original);
        Files.setPosixFilePermissions(
                workflow, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    @MethodSource("serverPortClassifications")
    @ParameterizedTest
    void classifiesWorkflowServerPortForDiagnostics(
            String name, String portLine, WorkflowServerPortClassification.Kind expected) throws Exception {
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
                        .formatted(portLine));
        var editor = new WorkflowConfigEditor();

        // when
        WorkflowServerPortClassification classification = editor.classifyServerPortForDiagnostics(workflow);

        // then
        assertThat(classification.kind()).as(name).isEqualTo(expected);
    }

    private static Stream<Arguments> serverPortClassifications() {
        return Stream.of(
                Arguments.of("valid port", "server:\n  port: 18080", WorkflowServerPortClassification.Kind.VALID),
                Arguments.of("omitted port", "server: {}", WorkflowServerPortClassification.Kind.OMITTED),
                Arguments.of(
                        "negative port", "server:\n  port: -1", WorkflowServerPortClassification.Kind.OUT_OF_RANGE),
                Arguments.of(
                        "port above range",
                        "server:\n  port: 99999",
                        WorkflowServerPortClassification.Kind.OUT_OF_RANGE),
                Arguments.of(
                        "non-numeric port",
                        "server:\n  port: \"not-a-port\"",
                        WorkflowServerPortClassification.Kind.INVALID_VALUE),
                Arguments.of(
                        "unresolved environment reference",
                        "server:\n  port: $MISSING_PORT_VARIABLE",
                        WorkflowServerPortClassification.Kind.OMITTED),
                Arguments.of(
                        "whole-valued float normalizes",
                        "server:\n  port: 18080.0",
                        WorkflowServerPortClassification.Kind.VALID),
                Arguments.of(
                        "fractional port is invalid, not truncated",
                        "server:\n  port: 18080.5",
                        WorkflowServerPortClassification.Kind.INVALID_VALUE),
                Arguments.of(
                        "whole but huge port is invalid",
                        "server:\n  port: 99999999999999999999999",
                        WorkflowServerPortClassification.Kind.INVALID_VALUE),
                Arguments.of(
                        "zero stays in range for ephemeral runs",
                        "server:\n  port: 0",
                        WorkflowServerPortClassification.Kind.VALID));
    }

    @MethodSource("environmentResolvedServerPortClassifications")
    @ParameterizedTest
    void classifiesEnvironmentResolvedServerPortForDiagnostics(
            String caseName, String resolvedValue, WorkflowServerPortClassification.Kind expected) throws Exception {
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
                """);
        var editor = new WorkflowConfigEditor();
        Function<String, Optional<String>> resolver =
                variable -> "STATUS_PORT".equals(variable) ? Optional.ofNullable(resolvedValue) : Optional.empty();

        // when
        WorkflowServerPortClassification classification = editor.classifyServerPortForDiagnostics(workflow, resolver);

        // then
        assertThat(classification.kind()).as(caseName).isEqualTo(expected);
    }

    private static Stream<Arguments> environmentResolvedServerPortClassifications() {
        return Stream.of(
                Arguments.of("resolved valid port", "20991", WorkflowServerPortClassification.Kind.VALID),
                Arguments.of("resolved out-of-range port", "99999", WorkflowServerPortClassification.Kind.OUT_OF_RANGE),
                Arguments.of(
                        "resolved non-numeric port", "not-a-port", WorkflowServerPortClassification.Kind.INVALID_VALUE),
                Arguments.of(
                        "resolved whole-valued float normalizes",
                        "18080.0",
                        WorkflowServerPortClassification.Kind.VALID),
                Arguments.of(
                        "resolved fractional port is invalid, not truncated",
                        "18080.5",
                        WorkflowServerPortClassification.Kind.INVALID_VALUE),
                Arguments.of("unresolved reference", null, WorkflowServerPortClassification.Kind.OMITTED),
                Arguments.of("blank resolved value", "   ", WorkflowServerPortClassification.Kind.OMITTED));
    }

    @Test
    void classifiesMissingWorkflowFileAsUnreadableForDiagnostics() {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.does-not-exist.md");
        var editor = new WorkflowConfigEditor();

        // when
        WorkflowServerPortClassification classification = editor.classifyServerPortForDiagnostics(workflow);

        // then
        assertThat(classification.kind()).isEqualTo(WorkflowServerPortClassification.Kind.UNREADABLE);
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
                """);
        var editor = new WorkflowConfigEditor();

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
                """);
        var editor = new WorkflowConfigEditor();

        // when
        WorkflowValidation validation = editor.diagnosticsValidation(workflow);

        // then
        assertThat(validation).isEqualTo(WorkflowValidation.warn("overlapping tracker list roles"));
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
                """);
        ConnectedBoard board = ConnectedBoardBuilder.connectedBoard(workflow)
                .withBoardId("board-1")
                .withBoardKey("abc123")
                .withBoardName("Overlap Queue")
                .withBoardUrl("https://trello.example/abc123")
                .withEnvPath(tempDir.resolve(".env"))
                .withWorkspaceRoot(tempDir.resolve("workspaces"))
                .build();
        var editor = new WorkflowConfigEditor();

        // when
        WorkflowValidation validation = editor.validate(board, ignored -> Optional.empty());

        // then
        assertThat(validation)
                .isEqualTo(WorkflowValidation.warn(
                        "Workflow tracker list roles overlap for \"Overlap Queue\": overlapping tracker list roles"));
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
                """);
        var editor = new WorkflowConfigEditor();

        // when
        WorkflowValidation validation = editor.diagnosticsValidation(workflow);

        // then
        assertThat(validation).isEqualTo(WorkflowValidation.valid());
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
                """);
        var editor = new WorkflowConfigEditor();

        // when
        WorkflowValidation validation = editor.diagnosticsValidation(workflow);

        // then
        assertThat(validation).isEqualTo(WorkflowValidation.warn("overlapping tracker list roles"));
    }

    @Test
    void codexAccessUpdateRejectsWritableRootsForExplicitReadOnlySandboxWithoutRewriting() throws Exception {
        // given
        Path privateRoot = tempDir.resolve("Jane Doe");
        Path workflow = privateRoot.resolve("WORKFLOW.read-only.md");
        Files.createDirectories(privateRoot);
        String original = workflowWithCodex(
                """
                codex:
                  command: codex app-server
                  turn_sandbox_policy:
                    type: readOnly
                """,
                currentWorkflowBody());
        Files.writeString(workflow, original);
        var editor = new WorkflowConfigEditor();

        // when
        Throwable thrown =
                catchThrowable(() -> editor.applyCodexAccess(workflow, List.of(privateRoot.resolve("repo")), false));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
            assertThat(failure.getMessage())
                    .contains(
                            "Cannot update Codex access",
                            "codex.additional_writable_roots require a workspaceWrite or dangerFullAccess sandbox policy")
                    .doesNotContain(workflow.toString(), privateRoot.toString(), tempDir.toString(), "Jane Doe");
            assertThat(failure.getCause()).isInstanceOf(CodexSandboxPolicy.InvalidPolicyException.class);
        });
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo(original);
    }

    @Test
    void rejectsCurrentWorkflowWithNullSandboxPolicyWithoutRewriting() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.null-policy.md");
        String original = workflowWithCodex(
                """
                codex:
                  command: codex app-server
                  turn_sandbox_policy:
                """,
                currentWorkflowBody());
        Files.writeString(workflow, original);
        var editor = new WorkflowConfigEditor();

        // when
        Throwable thrown =
                catchThrowable(() -> editor.applyCodexAccess(workflow, List.of(tempDir.resolve("repo")), false));

        // then
        assertThat(thrown)
                .isInstanceOf(TrelloBoardSetupException.class)
                .hasMessageContaining("codex.turn_sandbox_policy must be an object")
                .hasMessageNotContaining(workflow.toString());
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo(original);
    }

    @Test
    void launchHonorsForcedDangerFullAccessBeforeConfiguredSandboxPolicyValidation() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.force-danger-invalid-sandbox.md");
        String original =
                """
                ---
                tracker:
                  kind: trello
                  board_id: "board-1"
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Done"
                server:
                  port: 18080
                codex:
                  command: codex app-server
                  turn_sandbox_policy: []
                ---
                %s
                """
                        .formatted(currentWorkflowBody());
        Files.writeString(workflow, original);
        var editor = new WorkflowConfigEditor();

        // when
        EffectiveConfig config = editor.prepareLaunchWorkflow(workflow, trelloCredentialsAndForcedDanger(), true);

        // then
        assertThat(config.codex())
                .as("Codex config [forceDangerFullAccess, turnSandboxPolicy]")
                .extracting(
                        EffectiveConfig.CodexConfig::forceDangerFullAccess,
                        EffectiveConfig.CodexConfig::turnSandboxPolicy)
                .containsExactly(true, List.of());
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo(original);
    }

    @Test
    void launchPreparationDoesNotRewriteExistingWorkflowContent() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.current-unmarked.md");
        String original =
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: "board-1"
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Done"
                server:
                  port: 18080
                codex:
                  command: codex app-server
                ---
                ## Operating Posture

                This is an existing private workflow. It may mention that operators should record the branch,
                commit, and validation evidence.
                """;
        Files.writeString(workflow, original);
        var editor = new WorkflowConfigEditor();

        // when
        EffectiveConfig config = editor.prepareLaunchWorkflow(workflow, ignored -> Optional.empty(), true);

        // then
        assertThat(config.codex().turnSandboxPolicy()).isNull();
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo(original);
    }

    @Test
    void launchValidationDoesNotRewriteCurrentWorkflowWhenTrackerStructureIsInvalid() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.invalid-tracker.md");
        String original =
                """
                ---
                tracker: []
                server:
                  port: 18080
                codex:
                  command: codex app-server
                ---
                Body
                """;
        Files.writeString(workflow, original);
        var editor = new WorkflowConfigEditor();

        // when
        Throwable thrown = catchThrowable(() -> editor.prepareLaunchWorkflow(workflow, trelloCredentials(), true));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
            assertThat(failure.getMessage()).contains("tracker must be an object", "selected workflow file");
        });
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo(original);
    }

    @Test
    void launchValidationDoesNotRewriteCurrentWorkflowWhenEnvironmentReferenceIsUnresolved() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.unresolved-env.md");
        String original =
                """
                ---
                tracker:
                  kind: trello
                  board_id: "$MISSING_TRELLO_BOARD"
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Done"
                server:
                  port: 18080
                codex:
                  command: codex app-server
                ---
                Body
                """;
        Files.writeString(workflow, original);
        var editor = new WorkflowConfigEditor();

        // when
        Throwable thrown = catchThrowable(() -> editor.prepareLaunchWorkflow(workflow, trelloCredentials(), true));

        // then
        assertThat(thrown).isInstanceOfSatisfying(TrelloBoardSetupException.class, failure -> {
            assertThat(failure.code()).isEqualTo("setup_workflow_invalid");
            assertThat(failure.getMessage()).contains("tracker.board_id", "selected workflow file");
        });
        assertThat(workflow).content(StandardCharsets.UTF_8).isEqualTo(original);
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
                """);
        var editor = new WorkflowConfigEditor();

        // when
        WorkflowValidation validation = editor.diagnosticsValidation(workflow);

        // then
        assertThat(validation).isEqualTo(WorkflowValidation.valid());
    }

    private static String workflowWithBody(String body) {
        return workflowWithCodex(
                """
                codex:
                  command: codex app-server
                """, body);
    }

    private static String workflowWithCodex(String codexYaml, String body) {
        return """
                ---
                tracker:
                  kind: trello
                  board_id: "board-1"
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Done"
                server:
                  port: 18080
                %s
                ---
                %s
                """
                .formatted(codexYaml.stripTrailing(), body);
    }

    private static Function<String, Optional<String>> trelloCredentials() {
        return name -> switch (name) {
            case "TRELLO_API_KEY" -> Optional.of("test-key");
            case "TRELLO_API_TOKEN" -> Optional.of("test-token");
            default -> Optional.empty();
        };
    }

    private static Function<String, Optional<String>> trelloCredentialsAndForcedDanger() {
        return name -> switch (name) {
            case "TRELLO_API_KEY" -> Optional.of("test-key");
            case "TRELLO_API_TOKEN" -> Optional.of("test-token");
            case "SYMPHONY_CODEX_DANGER_FULL_ACCESS" -> Optional.of("true");
            default -> Optional.empty();
        };
    }

    private static String currentWorkflowBody() {
        return """
                ## Operating Posture

                This is an unattended orchestration run. Do not ask a human to perform routine follow-up actions.

                ## Pull Request Publication

                Create a pull request when needed.

                ## Trello List Routing

                Card URL: {{ card.url }}
                """;
    }
}
