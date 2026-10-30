package ch.fmartin.symphony.trello.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class TrelloListRolesTest {
    private static final TrelloClient.BoardList READY =
            new TrelloClient.BoardList("list-ready", "Ready for Codex", false);
    private static final TrelloClient.BoardList PROGRESS =
            new TrelloClient.BoardList("list-progress", "In Progress", false);
    private static final TrelloClient.BoardList REVIEW =
            new TrelloClient.BoardList("list-review", "Human Review", false);
    private static final TrelloClient.BoardList MERGING = new TrelloClient.BoardList("list-merging", "Merging", false);
    private static final TrelloClient.BoardList DONE = new TrelloClient.BoardList("list-done", "Done", false);

    private final EffectiveConfig config = TrelloTestConfigs.trackerConfig(
            Path.of("WORKFLOW.md"),
            "https://api.trello.com/1",
            "board-1",
            Map.of(
                    "active_states", List.of("Ready for Codex", "In Progress", "Merging"),
                    "in_progress_state", "In Progress",
                    "terminal_states", List.of("Done")));

    @Test
    void activeRoleLeavesOutTheInProgressAndMergingLists() {
        // given
        TrelloListRoles roles = TrelloListRoles.of(config, "Human Review", "Merging");

        // when
        List<TrelloListRoles.Role> readyRoles = roles.rolesOf(READY);
        List<TrelloListRoles.Role> progressRoles = roles.rolesOf(PROGRESS);
        List<TrelloListRoles.Role> mergingRoles = roles.rolesOf(MERGING);

        // then
        assertThat(readyRoles).containsExactly(TrelloListRoles.Role.ACTIVE);
        assertThat(progressRoles).containsExactly(TrelloListRoles.Role.IN_PROGRESS);
        assertThat(mergingRoles).containsExactly(TrelloListRoles.Role.MERGING);
    }

    @Test
    void configuredListIdsDecideTheActiveAndTerminalRoles() {
        // given
        var readyNamesake = new TrelloClient.BoardList("list-ready-copy", "Ready for Codex", false);
        var doneNamesake = new TrelloClient.BoardList("list-done-copy", "Done", false);
        EffectiveConfig byListId = TrelloTestConfigs.trackerConfig(
                Path.of("WORKFLOW.md"),
                "https://api.trello.com/1",
                "board-1",
                Map.of(
                        "active_states", List.of("Ready for Codex", "In Progress"),
                        "active_list_ids", List.of(READY.id(), PROGRESS.id()),
                        "in_progress_state", "In Progress",
                        "terminal_states", List.of("Done"),
                        "terminal_list_ids", List.of(DONE.id())));
        TrelloListRoles roles = TrelloListRoles.of(byListId, null, null);
        List<TrelloClient.BoardList> openLists = List.of(READY, readyNamesake, PROGRESS, DONE, doneNamesake);

        // when
        TrelloListRoles.Destination active = roles.resolve(openLists, "active");
        TrelloListRoles.Destination terminal = roles.resolve(openLists, "terminal");
        List<TrelloListRoles.Role> readyNamesakeRoles = roles.rolesOf(readyNamesake);
        List<TrelloListRoles.Role> doneNamesakeRoles = roles.rolesOf(doneNamesake);

        // then
        assertThat(active).isEqualTo(new TrelloListRoles.Destination.Found(READY));
        assertThat(terminal).isEqualTo(new TrelloListRoles.Destination.Found(DONE));
        assertThat(readyNamesakeRoles).isEmpty();
        assertThat(doneNamesakeRoles).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"human-review", "Human Review", "HUMAN_REVIEW", " human review "})
    void roleNamesIgnoreCaseAndSeparators(String typed) {
        // given
        TrelloListRoles roles = TrelloListRoles.of(config, "Human Review", null);

        // when
        TrelloListRoles.Destination destination = roles.resolve(List.of(READY, PROGRESS, REVIEW, DONE), typed);

        // then
        assertThat(destination).isEqualTo(new TrelloListRoles.Destination.Found(REVIEW));
    }

    @Test
    void aWorkflowWithoutRecordedReviewListHasNoHumanReviewRole() {
        // given
        TrelloListRoles roles = TrelloListRoles.of(config, null, null);

        // when
        TrelloListRoles.Destination destination =
                roles.resolve(List.of(READY, PROGRESS, MERGING, DONE), "human-review");

        // then
        assertThat(destination)
                .isEqualTo(new TrelloListRoles.Destination.RoleWithoutList(TrelloListRoles.Role.HUMAN_REVIEW));
    }

    @Test
    void roleNamesListEveryRoleKeyInOrder() {
        // given
        TrelloListRoles.Role[] roles = TrelloListRoles.Role.values();

        // when
        String keys = Arrays.stream(roles).map(TrelloListRoles.Role::key).collect(Collectors.joining(", "));

        // then
        assertThat(TrelloListRoles.ROLE_NAMES).isEqualTo(keys);
    }

    @Test
    void anUnknownNameIsNeitherAListNorARole() {
        // given
        TrelloListRoles roles = TrelloListRoles.of(config, "Human Review", null);

        // when
        TrelloListRoles.Destination destination = roles.resolve(List.of(READY, REVIEW), "Backlog");

        // then
        assertThat(destination).isEqualTo(new TrelloListRoles.Destination.UnknownList());
    }
}
