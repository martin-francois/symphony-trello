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
}
