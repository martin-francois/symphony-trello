package ch.fmartin.symphony.trello.setup;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.config.ConfigResolver;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.testsupport.SetupRunResult;
import ch.fmartin.symphony.trello.workflow.WorkflowLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class LocalSetupFollowUpCardsTest extends LocalSetupFixtureSupport {
    private static final String QUESTION = "Allow Codex to create cards for these findings?";
    private static final String EXPLANATION =
            "While working on a task, Codex may find bugs or improvements outside that task's scope."
                    + " It can save these as separate, linked Trello cards in Inbox for you to review and prioritize."
                    + " Creating a card does not start work on it.";

    @CsvSource({"false,y,true", "false,n,false", "false,'',false", "true,y,true", "true,n,false", "true,'',false"})
    @ParameterizedTest
    void guidedCreationAndImportOfferFollowUpCards(boolean importing, String answer, boolean enabled) {
        // given
        String priorAnswers = "\n".repeat(importing ? 8 : 6);

        // when
        SetupRunResult result = runSetupWithInput(priorAnswers + answer + "\n", arguments(importing, false));

        // then
        result.assertSuccess().stdoutContains(EXPLANATION, QUESTION + " [y/N]");
        EffectiveConfig.TrelloToolsConfig tools = config().trelloTools();
        assertThat(tools.followUpCards().enabled())
                .as("the selected follow-up choice is persisted for creation and import")
                .isEqualTo(enabled);
        assertThat(tools.allowChecklists())
                .as("only Yes enables checklist writes")
                .isEqualTo(enabled);
        assertThat(tools.allowUrlAttachments())
                .as("only Yes enables URL attachment writes")
                .isEqualTo(enabled);
        assertThat(tools.enabled()).as("ordinary handoff tools remain enabled").isTrue();
        assertThat(tools.allowWrites())
                .as("ordinary handoff writes remain enabled")
                .isTrue();
        assertThat(tools.allowComments())
                .as("ordinary handoff comments remain enabled")
                .isTrue();
    }

    @CsvSource({"false,y,true", "true,n,false", "true,'',true", "false,'',false"})
    @ParameterizedTest
    void keepingExistingBoardPreservesSettingsAndUsesCurrentChoice(boolean before, String answer, boolean after)
            throws IOException {
        // given
        runSetup(arguments(false, true)).assertSuccess();
        configureExistingFollowUps(before);
        String previous = Files.readString(workflow());
        EffectiveConfig.TrelloToolsConfig previousTools = config().trelloTools();

        // when
        SetupRunResult result = runSetupWithInput("\n" + answer + "\n", "--no-start");

        // then
        result.assertSuccess()
                .stdoutContains(
                        "Follow-up cards for \"Follow-up Queue\"",
                        QUESTION + (before ? " [Y/n]" : " [y/N]"),
                        "in \"Later findings\"");
        EffectiveConfig.TrelloToolsConfig tools = config().trelloTools();
        assertThat(tools.followUpCards().enabled())
                .as("existing choice follows the explicit answer or Enter")
                .isEqualTo(after);
        assertThat(tools.followUpCards().listName()).isEqualTo("Later findings");
        assertThat(tools.followUpCards().maxCardsPerSourceCard()).isEqualTo(2);
        assertThat(tools.followUpCards().maxCardsPerHour()).isEqualTo(7);
        assertThat(tools.allowedMoveListNames()).containsExactlyElementsOf(previousTools.allowedMoveListNames());
        if (answer.isEmpty()) {
            assertThat(workflow()).content().isEqualTo(previous);
        } else if (!after) {
            assertThat(tools.allowChecklists())
                    .as("No preserves existing checklist permission")
                    .isEqualTo(previousTools.allowChecklists());
            assertThat(tools.allowUrlAttachments())
                    .as("No preserves existing URL attachment permission")
                    .isEqualTo(previousTools.allowUrlAttachments());
        } else {
            assertThat(tools.allowChecklists())
                    .as("Yes grants required checklist permission")
                    .isTrue();
            assertThat(tools.allowUrlAttachments())
                    .as("Yes grants required URL attachment permission")
                    .isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nonInteractiveSetupDoesNotPromptOrChangeExistingChoice(boolean enabled) throws IOException {
        // given
        SetupRunResult created = runSetup(arguments(false, true));
        created.assertSuccess().stdoutDoesNotContain(QUESTION);
        assertThat(config().trelloTools().followUpCards().enabled())
                .as("new noninteractive configuration defaults off")
                .isFalse();
        configureExistingFollowUps(enabled);
        String previous = Files.readString(workflow());

        // when
        SetupRunResult result = runSetup("--non-interactive", "--no-start");

        // then
        result.assertSuccess().stdoutDoesNotContain(QUESTION);
        assertThat(workflow()).content().isEqualTo(previous);
    }

    @Test
    void forcedNonInteractiveImportPreservesExistingToolsAndFollowUpSettings() throws IOException {
        // given
        runSetup(arguments(true, true)).assertSuccess();
        configureExistingFollowUps(true);
        Files.writeString(
                workflow(),
                Files.readString(workflow()).replace("trello_tools:", "trello_tools:\n  custom_tool_setting: keep-me"));
        Object previousTools = new WorkflowLoader().load(workflow()).config().get("trello_tools");
        List<String> forced = new ArrayList<>(List.of(arguments(true, true)));
        forced.add("--force");

        // when
        SetupRunResult result = runSetup(forced.toArray(String[]::new));

        // then
        result.assertSuccess().stdoutDoesNotContain(QUESTION);
        assertThat(new WorkflowLoader().load(workflow()).config().get("trello_tools"))
                .as("explicit import retains every existing tool setting, move list and follow-up setting")
                .isEqualTo(previousTools);
    }

    @Test
    void githubUpgradePreservesAllToolSettingsIncludingCustomMoveNames() throws IOException {
        // given
        runSetup(arguments(false, true)).assertSuccess();
        configureExistingFollowUps(true);
        Files.writeString(
                workflow(),
                Files.readString(workflow())
                        .replace("  allowed_move_list_names:\n", "  allowed_move_list_names:\n    - Custom review\n"));
        Files.writeString(
                workflow(),
                Files.readString(workflow()).replace("trello_tools:", "trello_tools:\n  custom_tool_setting: keep-me"));
        var previousTools =
                (Map<?, ?>) new WorkflowLoader().load(workflow()).config().get("trello_tools");
        prepareNextSetupRunWithGithubAuth();

        // when
        SetupRunResult result =
                runSetup("--non-interactive", "--endpoint", endpoint(), "configure-github", "--no-start");

        // then
        result.assertSuccess().stdoutDoesNotContain(QUESTION);
        var updatedTools =
                (Map<?, ?>) new WorkflowLoader().load(workflow()).config().get("trello_tools");
        assertThat(updatedTools)
                .as("GitHub upgrade preserves permissions, custom move lists and the complete follow-up subsection")
                .isEqualTo(previousTools);
        assertThat(trello.createdLists()).containsExactly("Merging");
    }

    private String[] arguments(boolean importing, boolean nonInteractive) {
        List<String> arguments = new ArrayList<>(List.of(
                "--endpoint",
                endpoint(),
                "--key",
                "key",
                "--token",
                "token",
                importing ? "--board" : "--board-name",
                importing ? "board-1" : "Follow-up Queue",
                "--workflow",
                workflow().toString(),
                "--env",
                tempDir.resolve(".env.follow-ups").toString(),
                "--no-github",
                "--no-start"));
        if (nonInteractive) {
            arguments.add("--non-interactive");
        }
        return arguments.toArray(String[]::new);
    }

    private Path workflow() {
        return tempDir.resolve("WORKFLOW.follow-ups.md");
    }

    private EffectiveConfig config() {
        return new ConfigResolver(name -> Optional.of("synthetic-value"))
                .resolve(new WorkflowLoader().load(workflow()));
    }

    private void configureExistingFollowUps(boolean enabled) throws IOException {
        String content = Files.readString(workflow());
        Files.writeString(
                workflow(),
                content.replace(
                        "  allow_url_attachments: false",
                        """
                  allow_url_attachments: false
                  follow_up_cards:
                    enabled: %s
                    list_name: Later findings
                    max_cards_per_source_card: 2
                    max_cards_per_hour: 7
                """
                                .formatted(enabled)
                                .stripTrailing()));
    }
}
