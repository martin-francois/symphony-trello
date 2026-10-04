package ch.fmartin.symphony.trello.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import ch.fmartin.symphony.trello.TestCards;
import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.PullRequestMode;
import ch.fmartin.symphony.trello.domain.Card;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class PullRequestHandoffTest {
    private static final String NO_PR = "No PR";

    @MethodSource("precedence")
    @ParameterizedTest(name = "{0}")
    void resolvesEffectiveModeFromWorkflowDefaultAndCardLabel(
            String name, EffectiveConfig.GitHubConfig github, List<String> cardLabels, PullRequestHandoff expected) {
        // given
        Card card = TestCards.cardWithLabels("card-1", "TRELLO-1", "Ready for Codex", cardLabels);

        // when
        PullRequestHandoff handoff = PullRequestHandoff.resolve(github, card);

        // then
        assertThat(handoff).isEqualTo(expected);
    }

    private static Stream<Arguments> precedence() {
        var createBoard = new EffectiveConfig.GitHubConfig(PullRequestMode.CREATE, NO_PR);
        var branchOnlyBoard = new EffectiveConfig.GitHubConfig(PullRequestMode.BRANCH_ONLY, NO_PR);
        var labelDisabled = new EffectiveConfig.GitHubConfig(PullRequestMode.CREATE, null);
        return Stream.of(
                Arguments.of(
                        "create board without label keeps pull requests",
                        createBoard,
                        List.of("p1"),
                        handoff(
                                PullRequestMode.CREATE,
                                PullRequestMode.CREATE,
                                PullRequestHandoff.SelectedBy.WORKFLOW,
                                false)),
                Arguments.of(
                        "create board with label selects branch-only",
                        createBoard,
                        List.of("no pr"),
                        handoff(
                                PullRequestMode.BRANCH_ONLY,
                                PullRequestMode.CREATE,
                                PullRequestHandoff.SelectedBy.CARD_LABEL,
                                true)),
                Arguments.of(
                        "label matching ignores case and surrounding whitespace",
                        createBoard,
                        List.of("  NO   pr "),
                        handoff(
                                PullRequestMode.BRANCH_ONLY,
                                PullRequestMode.CREATE,
                                PullRequestHandoff.SelectedBy.CARD_LABEL,
                                true)),
                Arguments.of(
                        "branch-only board is strict without label",
                        branchOnlyBoard,
                        List.of(),
                        handoff(
                                PullRequestMode.BRANCH_ONLY,
                                PullRequestMode.BRANCH_ONLY,
                                PullRequestHandoff.SelectedBy.WORKFLOW,
                                false)),
                Arguments.of(
                        "branch-only board stays the deciding source with label",
                        branchOnlyBoard,
                        List.of("no pr"),
                        handoff(
                                PullRequestMode.BRANCH_ONLY,
                                PullRequestMode.BRANCH_ONLY,
                                PullRequestHandoff.SelectedBy.WORKFLOW,
                                true)),
                Arguments.of(
                        "disabled label leaves a labelled card pull-request backed",
                        labelDisabled,
                        List.of("no pr"),
                        new PullRequestHandoff(
                                PullRequestMode.CREATE,
                                PullRequestMode.CREATE,
                                PullRequestHandoff.SelectedBy.WORKFLOW,
                                null,
                                false)));
    }

    private static PullRequestHandoff handoff(
            PullRequestMode mode,
            PullRequestMode workflowMode,
            PullRequestHandoff.SelectedBy selectedBy,
            boolean cardHasNoPrLabel) {
        return new PullRequestHandoff(mode, workflowMode, selectedBy, NO_PR, cardHasNoPrLabel);
    }
}
