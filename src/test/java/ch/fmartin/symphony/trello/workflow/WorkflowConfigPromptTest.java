package ch.fmartin.symphony.trello.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.prompt.PromptRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowConfigPromptTest {
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
                codex:
                  command: "codex app-server --listen stdio://"
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
        assertThat(config.tracker().priorityLabels()).containsEntry("urgent", 1).doesNotContainKey("invalid");
        assertThat(config.agent().maxConcurrentAgentsByState()).containsEntry("ready for codex", 2);
        assertThat(config.workspace().root())
                .isEqualTo(tempDir.resolve("workspaces").toAbsolutePath().normalize());
        assertThat(renderedPrompt).contains("TRELLO-abc / TRELLO-abc attempt=3");
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
