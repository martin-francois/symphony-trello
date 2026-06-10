package ch.fmartin.symphony.trello.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesWorkflowEnvironmentReferencesForBoardScopeAndServerPort() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: $SYMPHONY_BOARD_ID
                  resolved_board_id: $SYMPHONY_RESOLVED_BOARD_ID
                server:
                  port: $SYMPHONY_TEST_PORT
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(name -> switch (name) {
            case "SYMPHONY_BOARD_ID" -> Optional.of("board-shortlink");
            case "SYMPHONY_RESOLVED_BOARD_ID" -> Optional.of("resolved-board-id");
            case "SYMPHONY_TEST_PORT" -> Optional.of("19093");
            default -> Optional.empty();
        });

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));

        // then
        assertThat(config.tracker().boardId()).isEqualTo("board-shortlink");
        assertThat(config.tracker().resolvedBoardId()).isEqualTo("resolved-board-id");
        assertThat(config.server().port()).hasValue(19093);
    }

    @Test
    void unresolvedWorkflowBoardIdEnvironmentReferenceFailsBeforeDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: $SYMPHONY_MISSING_BOARD_ID
                codex:
                  command: fake
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(ignored -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(config.tracker().boardId()).isNull();
        assertThat(error.code()).isEqualTo("missing_tracker_board_id");
        assertThat(error).hasMessage("tracker.board_id is required");
    }

    @Test
    void overlappingWorkflowListRolesFailBeforeDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.overlap.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-id
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Ready  for Codex"
                codex:
                  command: fake
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver();

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error.code()).isEqualTo("overlapping_tracker_list_roles");
        assertThat(error).hasMessageContaining("active and terminal both use Ready for Codex");
    }

    @Test
    void overlappingBlockedWorkflowListRoleFailsBeforeDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.blocked-overlap.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-id
                  active_states:
                    - "Ready for Codex"
                  terminal_states:
                    - "Done"
                  blocked_state: "Ready  for Codex"
                codex:
                  command: fake
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver();

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error.code()).isEqualTo("overlapping_tracker_list_roles");
        assertThat(error).hasMessageContaining("active and blocked both use Ready for Codex");
    }

    @Test
    void overlappingListNamesAreAllowedWhenListIdsAreAuthoritative() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.list-ids.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-id
                  active_states:
                    - "Done"
                  active_list_ids:
                    - "list-active-done"
                  terminal_states:
                    - "Done"
                  terminal_list_ids:
                    - "list-terminal-done"
                  blocked_state: "Done"
                codex:
                  command: fake
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver();

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error).isNull();
    }

    @Test
    void overlappingListIdsFailBeforeDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.list-id-overlap.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-id
                  active_states:
                    - "Ready for Codex"
                  active_list_ids:
                    - "shared-list-id"
                  terminal_states:
                    - "Done"
                  terminal_list_ids:
                    - "shared-list-id"
                codex:
                  command: fake
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver();

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error.code()).isEqualTo("overlapping_tracker_list_roles");
        assertThat(error).hasMessageContaining("active and terminal both use shared-list-id");
    }

    @Test
    void duplicateValuesWithinOneRoleAreAllowedBeforeDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.duplicate-role-values.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-id
                  active_states:
                    - "Ready for Codex"
                    - "Ready  for Codex"
                  terminal_states:
                    - "Done"
                    - "Done "
                codex:
                  command: fake
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver();

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error).isNull();
    }

    @Test
    void rejectsNonPositivePollingInterval() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                polling:
                  interval_ms: 0
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(name -> Optional.empty());

        // when
        ConfigException error = catchThrowableOfType(
                ConfigException.class, () -> resolver.resolve(new WorkflowLoader().load(workflow)));

        // then
        assertThat(error).hasMessageContaining("interval_ms must be positive");
    }

    @Test
    void rejectsInvalidTrackerEndpointBeforeDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                  endpoint: "not-a-url"
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(name -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error).hasMessageContaining("tracker.endpoint must be an absolute http(s) URL");
    }

    @Test
    void acceptsLoopbackHttpTrackerEndpointForDispatch() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: literal-key
                  api_token: literal-token
                  board_id: board-1
                  endpoint: "http://127.0.0.1:18099/1"
                ---
                Prompt
                """);
        ConfigResolver resolver = new ConfigResolver(name -> Optional.empty());

        // when
        EffectiveConfig config = resolver.resolve(new WorkflowLoader().load(workflow));
        Throwable error = catchThrowableOfType(ConfigException.class, () -> resolver.validateForDispatch(config));

        // then
        assertThat(error).isNull();
    }
}
