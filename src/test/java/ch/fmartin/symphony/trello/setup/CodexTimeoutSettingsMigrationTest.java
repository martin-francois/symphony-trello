package ch.fmartin.symphony.trello.setup;

import static ch.fmartin.symphony.trello.setup.CodexTimeoutSettingsMigration.RELEASED_GENERATED_TURN_TIMEOUT_MS;
import static ch.fmartin.symphony.trello.setup.CodexTimeoutSettingsMigration.TARGET_TURN_TIMEOUT_MS;
import static ch.fmartin.symphony.trello.setup.WorkflowMigrationFixture.turnTimeoutLine;
import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.setup.CodexTimeoutSettingsMigration.Change;
import ch.fmartin.symphony.trello.setup.CodexTimeoutSettingsMigration.Plan;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class CodexTimeoutSettingsMigrationTest {
    private static final Path WORKFLOW = Path.of("/workflows/WORKFLOW.synth-board.md");
    private static final String BODY = "# Trello Card\n\nYou are working on {{ card.identifier }}.\n";
    private static final String OLD_TURN_TIMEOUT_LINE = turnTimeoutLine(RELEASED_GENERATED_TURN_TIMEOUT_MS);
    private static final String NEW_TURN_TIMEOUT_LINE = turnTimeoutLine(TARGET_TURN_TIMEOUT_MS);
    private static final String REPOSITORY_LINES = "  default_url: null\n  default_path: null\n";

    /// The front matter layout that setup wrote in the 1.x releases, before the review-loop settings
    /// and the three-hour turn timeout existed.
    private static final String RELEASED_GENERATED_METADATA =
            """
            ---
            tracker:
              kind: trello
              api_key: $TRELLO_API_KEY
              api_token: $TRELLO_API_TOKEN
              board_id: SYNTH001
              active_states:
                - "Ready for Codex"
                - "In Progress"
              in_progress_state: "In Progress"
              terminal_states:
                - "Done"
            workspace:
              root: "/workspaces/synth-board"
            repository:
            %sserver:
              port: 18080
            polling:
              interval_ms: 5000
            trello_tools:
              enabled: true
              allow_writes: true
            agent:
              max_concurrent_agents: 1
            codex:
              command: codex app-server
              turn_sandbox_policy:
                type: workspaceWrite
                networkAccess: true
              approval_policy: never
            %s  read_timeout_ms: 5000
              stall_timeout_ms: 300000
            ---
            """;

    @Test
    void raisesOnlyTheTurnTimeoutOfAReleasedGeneratedWorkflow() {
        // given
        String released = releasedWorkflow(REPOSITORY_LINES, OLD_TURN_TIMEOUT_LINE);

        // when
        Optional<Plan> plan = CodexTimeoutSettingsMigration.plan(WORKFLOW, released);

        // then
        assertThat(plan)
                .get()
                .satisfies(update -> assertThat(update.updatedContent())
                        .hasValue(releasedWorkflow(REPOSITORY_LINES, NEW_TURN_TIMEOUT_LINE)))
                .extracting(Plan::change)
                .extracting(Change::path, Change::from, Change::to)
                .containsExactly("codex.turn_timeout_ms", RELEASED_GENERATED_TURN_TIMEOUT_MS, TARGET_TURN_TIMEOUT_MS);
    }

    @Test
    void keepsCommentsSpacingAndCrlfLineBreaksOnEveryOtherLine() {
        // given
        String repositoryLines = "  default_url: null # set by setup\n  default_path: null\n\n  # keep this comment\n";
        String released = releasedWorkflow(
                        repositoryLines,
                        "  turn_timeout_ms:   " + RELEASED_GENERATED_TURN_TIMEOUT_MS + "   # one hour\n")
                .replace("\n", "\r\n");

        // when
        Optional<Plan> plan = CodexTimeoutSettingsMigration.plan(WORKFLOW, released);

        // then
        assertThat(plan)
                .get()
                .extracting(Plan::updatedContent)
                .isEqualTo(Optional.of(releasedWorkflow(
                                repositoryLines, "  turn_timeout_ms:   " + TARGET_TURN_TIMEOUT_MS + "   # one hour\n")
                        .replace("\n", "\r\n")));
    }

    @ParameterizedTest(name = "turn_timeout_ms: {0}")
    @ValueSource(strings = {"5400000", "1800000", "\"3600000\"", "36000000"})
    void keepsEveryOtherExplicitTurnTimeout(String configuredTurnTimeout) {
        // given
        String workflow = releasedWorkflow(REPOSITORY_LINES, "  turn_timeout_ms: " + configuredTurnTimeout + "\n");

        // when
        Optional<Plan> plan = CodexTimeoutSettingsMigration.plan(WORKFLOW, workflow);

        // then
        assertThat(plan).isEmpty();
    }

    @Test
    void leavesConfiguredReviewSettingsAsTheyAre() {
        // given
        String repositoryLines = REPOSITORY_LINES + "  codex_review_max_rounds: 0\n";
        String workflow = releasedWorkflow(repositoryLines, OLD_TURN_TIMEOUT_LINE);

        // when
        Optional<Plan> plan = CodexTimeoutSettingsMigration.plan(WORKFLOW, workflow);

        // then
        assertThat(plan)
                .get()
                .extracting(Plan::updatedContent)
                .isEqualTo(Optional.of(releasedWorkflow(repositoryLines, NEW_TURN_TIMEOUT_LINE)));
    }

    @Test
    void leavesAWorkflowWithTheCurrentTurnTimeoutUnchanged() {
        // given
        String current = releasedWorkflow(REPOSITORY_LINES, NEW_TURN_TIMEOUT_LINE);

        // when
        Optional<Plan> plan = CodexTimeoutSettingsMigration.plan(WORKFLOW, current);

        // then
        assertThat(plan).isEmpty();
    }

    @Test
    void updatesAWorkflowWithoutARepositorySection() {
        // given
        String workflow = "---\ncodex:\n  command: codex app-server\n" + OLD_TURN_TIMEOUT_LINE + "---\n" + BODY;

        // when
        Optional<Plan> plan = CodexTimeoutSettingsMigration.plan(WORKFLOW, workflow);

        // then
        assertThat(plan)
                .get()
                .extracting(Plan::updatedContent)
                .isEqualTo(Optional.of(
                        "---\ncodex:\n  command: codex app-server\n" + NEW_TURN_TIMEOUT_LINE + "---\n" + BODY));
    }

    @Test
    void asksForAManualEditWhenTheOldValueIsNotOnItsOwnLine() {
        // given
        String flowStyle = "---\nrepository:\n  default_url: null\n"
                + "codex: {command: codex app-server, turn_timeout_ms: " + RELEASED_GENERATED_TURN_TIMEOUT_MS
                + "}\n---\n"
                + BODY;

        // when
        Optional<Plan> plan = CodexTimeoutSettingsMigration.plan(WORKFLOW, flowStyle);

        // then
        assertThat(plan)
                .get()
                .satisfies(manual -> assertThat(manual.updatedContent()).isEmpty())
                .extracting(Plan::change)
                .extracting(Change::path)
                .isEqualTo("codex.turn_timeout_ms");
    }

    @Test
    void ignoresAWorkflowWhoseFrontMatterCannotBeParsed() {
        // given
        String invalid = "---\ncodex: [unclosed\n---\n" + BODY;

        // when
        Optional<Plan> plan = CodexTimeoutSettingsMigration.plan(WORKFLOW, invalid);

        // then
        assertThat(plan).isEmpty();
    }

    private static String releasedWorkflow(String repositoryLines, String turnTimeoutLine) {
        return RELEASED_GENERATED_METADATA.formatted(repositoryLines, turnTimeoutLine) + BODY;
    }
}
