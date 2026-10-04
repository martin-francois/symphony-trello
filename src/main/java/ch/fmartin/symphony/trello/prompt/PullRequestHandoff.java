package ch.fmartin.symphony.trello.prompt;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.PullRequestMode;
import ch.fmartin.symphony.trello.config.StateNames;
import ch.fmartin.symphony.trello.domain.Card;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// The pull request handoff mode for one card run, resolved from the workflow's `github` settings and
/// the card's current labels. A Trello comment that asks for a pull request is free text, so the
/// generated workflow lets Codex apply that override; this record covers only the structured sources.
@NullMarked
public record PullRequestHandoff(
        PullRequestMode mode,
        PullRequestMode workflowMode,
        SelectedBy selectedBy,
        @Nullable String noPrLabel,
        boolean cardHasNoPrLabel) {

    public static final String TEMPLATE_VARIABLE = "pull_request_handoff";

    /// Which structured source decided [#mode()].
    public enum SelectedBy {
        WORKFLOW("workflow"),
        CARD_LABEL("card_label");

        private final String templateValue;

        SelectedBy(String templateValue) {
            this.templateValue = templateValue;
        }

        public String templateValue() {
            return templateValue;
        }
    }

    /// A branch-only workflow is strict, so the label only matters on a pull-request-creating board.
    public static PullRequestHandoff resolve(EffectiveConfig.GitHubConfig github, Card card) {
        String label = github.noPrLabel();
        boolean cardHasLabel = label != null && hasLabel(card, label);
        if (github.pullRequestMode() == PullRequestMode.CREATE && cardHasLabel) {
            return new PullRequestHandoff(
                    PullRequestMode.BRANCH_ONLY, github.pullRequestMode(), SelectedBy.CARD_LABEL, label, true);
        }
        return new PullRequestHandoff(
                github.pullRequestMode(), github.pullRequestMode(), SelectedBy.WORKFLOW, label, cardHasLabel);
    }

    private static boolean hasLabel(Card card, String label) {
        String wanted = StateNames.normalize(label);
        return card.labels().stream().map(StateNames::normalize).anyMatch(wanted::equals);
    }

    public Map<String, Object> toTemplateMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("mode", mode.workflowValue());
        values.put("workflow_mode", workflowMode.workflowValue());
        values.put("selected_by", selectedBy.templateValue());
        values.put("no_pr_label_enabled", noPrLabel != null);
        values.put("no_pr_label", noPrLabel == null ? "" : noPrLabel);
        values.put("card_has_no_pr_label", cardHasNoPrLabel);
        return values;
    }
}
