package ch.fmartin.symphony.trello.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.config.ConfigDefaults;
import ch.fmartin.symphony.trello.config.ConfigException;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.StateNames;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkflowConfigPromptTest {
    private final WorkflowLoader loader = new WorkflowLoader();
    private final ConfigResolver configs = new ConfigResolver();
    private final PromptRenderer prompts = new PromptRenderer();

    @TempDir
    Path tempDir;

    @Test
    void parsesFrontMatterAppliesDefaultsAndRendersStrictPrompt() throws Exception {
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
                  priority_labels:
                    Urgent: 1
                    invalid: nope
                workspace:
                  root: workspaces
                agent:
                  max_concurrent_agents_by_state:
                    "Ready   For Codex": 2
                    ignored: 0
                    invalid: nope
                codex:
                  command: "codex app-server --listen stdio://"
                  model: gpt-5.5
                  reasoning_effort: xhigh
                ---
                Work on {{ card.identifier }} / {{ issue.identifier }} attempt={{ attempt }}.
                """);

        // when
        WorkflowDefinition definition = loader.load(workflow);
        var config = configs.resolve(definition);
        String renderedPrompt =
                prompts.render(definition.promptTemplate(), TestCards.card("1", "TRELLO-abc", "Todo"), 3);

        // then
        assertThat(config.tracker().activeStates()).containsExactly("Todo", "In Progress");
        assertThat(config.tracker().priorityLabels())
                .containsEntry("p1", 1)
                .containsEntry("urgent", 1)
                .doesNotContainKey("invalid");
        assertThat(config.agent().maxConcurrentAgentsByState())
                .containsEntry("ready for codex", 2)
                .hasSize(1)
                .doesNotContainKeys("ignored", "invalid");
        assertThat(config.agent().maxConcurrentAgents()).isEqualTo(ConfigDefaults.DEFAULT_MAX_CONCURRENT_AGENTS);
        assertThat(config.polling().interval()).isEqualTo(ConfigDefaults.DEFAULT_POLLING_INTERVAL);
        assertThat(config.codex().model()).isEqualTo("gpt-5.5");
        assertThat(config.codex().reasoningEffort()).isEqualTo("xhigh");
        assertThat(config.workspace().root())
                .isEqualTo(tempDir.resolve("workspaces").toAbsolutePath().normalize());
        assertThat(renderedPrompt).contains("TRELLO-abc / TRELLO-abc attempt=3");
    }

    @Test
    void readsFileBackedTrackerSecrets() throws Exception {
        // given
        Path secretDirectory = tempDir.resolve("secrets");
        Files.createDirectories(secretDirectory);
        Files.writeString(secretDirectory.resolve("trello-api-key"), "key-from-file\n");
        Files.writeString(secretDirectory.resolve("trello-api-token"), "token-from-file\r\n");
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: file:secrets/trello-api-key
                  api_token: file:secrets/trello-api-token
                  board_id: board-1
                ---
                Do the work.
                """);

        // when
        var config = configs.resolve(loader.load(workflow));

        // then
        assertThat(config.tracker().apiKey()).isEqualTo("key-from-file");
        assertThat(config.tracker().apiToken()).isEqualTo("token-from-file");
    }

    @Test
    void rejectsInProgressStateThatIsNotDispatchActive() throws Exception {
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
                  active_states: [Ready for Codex]
                  in_progress_state: In Progress
                codex:
                  command: fake
                ---
                Do the work.
                """);
        var config = configs.resolve(loader.load(workflow));

        // when
        ConfigException error = catchThrowableOfType(ConfigException.class, () -> configs.validateForDispatch(config));

        // then
        assertThat(error.code()).isEqualTo("invalid_in_progress_state");
        assertThat(error).hasMessageContaining("tracker.in_progress_state");
    }

    @Test
    void exampleWorkflowEnforcesBlockersForEveryActiveState() {
        // given
        Path workflow = Path.of("WORKFLOW.example.md");

        // when
        var config = configs.resolve(loader.load(workflow));

        // then
        assertThat(config.tracker().blockerEnforcedStates())
                .containsAll(config.tracker().activeStates().stream()
                        .map(StateNames::normalize)
                        .toList());
        assertThat(config.codex().turnSandboxPolicy())
                .isEqualTo(Map.of("type", "workspaceWrite", "networkAccess", true));
    }

    @Test
    void fileBackedTrackerSecretFailureDoesNotExposeSecretValues() throws Exception {
        // given
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  api_key: file:secrets/missing-api-key
                  api_token: literal-token
                  board_id: board-1
                ---
                Do the work.
                """);

        // when
        ConfigException error =
                catchThrowableOfType(ConfigException.class, () -> configs.resolve(loader.load(workflow)));

        // then
        assertThat(error.code()).isEqualTo("secret_file_read_error");
        assertThat(error)
                .hasMessageContaining("tracker.api_key")
                .hasMessageContaining("missing-api-key")
                .hasMessageNotContaining("literal-token");
    }

    @Test
    void missingWorkflowAndUnknownTemplateVariablesUseTypedErrors() {
        // given
        ThrowingCallable missingWorkflow = () -> loader.load(tempDir.resolve("missing.md"));
        ThrowingCallable unknownTemplateVariable =
                () -> prompts.render("{{ card.nope }}", TestCards.card("1", "TRELLO-abc", "Todo"), null);

        // when
        WorkflowException missingWorkflowError = catchThrowableOfType(WorkflowException.class, missingWorkflow);
        WorkflowException unknownTemplateVariableError =
                catchThrowableOfType(WorkflowException.class, unknownTemplateVariable);

        // then
        assertThat(missingWorkflowError.code()).isEqualTo("missing_workflow_file");
        assertThat(unknownTemplateVariableError.code()).isEqualTo("template_render_error");
    }
}
