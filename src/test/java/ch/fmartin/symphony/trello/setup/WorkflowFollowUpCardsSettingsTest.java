package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class WorkflowFollowUpCardsSettingsTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void choiceChangesOnlyTheFeatureAndRequiredPermissions(boolean enabled) throws IOException {
        // given
        Path workflow = existingWorkflow();
        Map<String, Object> expected = new HashMap<>(configuration(workflow));
        Map<Object, Object> tools = new HashMap<>(tools(workflow));
        Map<Object, Object> followUps = new HashMap<>(followUps(workflow));
        followUps.put("enabled", enabled);
        tools.put("follow_up_cards", followUps);
        if (enabled) {
            for (String permission :
                    List.of("enabled", "allow_writes", "allow_comments", "allow_url_attachments", "allow_checklists")) {
                tools.put(permission, true);
            }
        }
        expected.put("trello_tools", tools);

        // when
        new WorkflowConfigEditor().updateFollowUpCards(workflow, enabled);

        // then
        assertThat(configuration(workflow))
                .as("all unknown and unrelated settings survive the choice")
                .containsExactlyInAnyOrderEntriesOf(expected);
        assertThat(new WorkflowLoader().load(workflow).promptTemplate()).isEqualTo("Existing prompt");
    }

    @Test
    void configuredListIdTakesPrecedenceOverNameInExplanation() throws IOException {
        // given
        Path workflow = existingWorkflow();

        // when
        WorkflowConfigEditor.FollowUpCardsSettings settings = new WorkflowConfigEditor().followUpCards(workflow);

        // then
        assertThat(settings.destination()).isEqualTo("the configured Trello list ID \"destination-id\"");
        assertThat(settings.enabled())
                .as("the saved follow-up choice remains enabled")
                .isTrue();
    }

    @Test
    void explicitRegenerationPreservesAllToolSettings() throws IOException {
        // given
        Path workflow = existingWorkflow();
        Map<Object, Object> expectedTools = new HashMap<>(tools(workflow));
        String generated =
                """
                ---
                tracker:
                  kind: trello
                  board_id: regenerated-board
                trello_tools:
                  enabled: false
                  allowed_move_list_names: [Merging, Done]
                ---
                Regenerated prompt
                """;

        // when
        Files.writeString(workflow, WorkflowConfigEditor.preserveTrelloTools(workflow, generated));

        // then
        assertThat(tools(workflow))
                .as("regeneration preserves the full existing tools map and follow-up subsection")
                .isEqualTo(expectedTools);
        assertThat(configuration(workflow))
                .containsEntry("tracker", Map.of("kind", "trello", "board_id", "regenerated-board"));
        assertThat(new WorkflowLoader().load(workflow).promptTemplate()).isEqualTo("Regenerated prompt");
    }

    private Path existingWorkflow() throws IOException {
        Path workflow = tempDir.resolve("WORKFLOW.md");
        Files.writeString(
                workflow,
                """
                ---
                tracker:
                  kind: trello
                  board_id: existing-board
                custom_setting:
                  sentinel: [one, two]
                trello_tools:
                  enabled: false
                  allow_writes: false
                  allow_comments: false
                  allow_url_attachments: false
                  allow_checklists: false
                  allowed_move_list_names: [Custom review, Done]
                  allowed_move_list_ids: [keep-move-id]
                  custom_tool_setting: keep-tool-value
                  follow_up_cards:
                    enabled: true
                    list_id: destination-id
                    list_name: Later findings
                    max_cards_per_source_card: 2
                    max_cards_per_hour: 7
                    custom_follow_up_setting: keep-follow-up-value
                ---
                Existing prompt
                """);
        return workflow;
    }

    private static Map<String, Object> configuration(Path workflow) {
        return new WorkflowLoader().load(workflow).config();
    }

    private static Map<?, ?> tools(Path workflow) {
        return (Map<?, ?>) configuration(workflow).get("trello_tools");
    }

    private static Map<?, ?> followUps(Path workflow) {
        return (Map<?, ?>) tools(workflow).get("follow_up_cards");
    }
}
