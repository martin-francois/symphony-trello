package ch.fmartin.symphony.trello.config;

import org.jspecify.annotations.NullMarked;

/// The fields of the `pull_request_handoff` prompt variable. The prompt renderer fills them, and generated
/// workflow text references them through [#reference()], so a renamed field cannot leave a stale template
/// expression behind. The names live in `config` because setup must not depend on the runtime prompt package.
@NullMarked
public enum PullRequestHandoffField {
    MODE("mode"),
    WORKFLOW_MODE("workflow_mode"),
    SELECTED_BY("selected_by"),
    NO_PR_LABEL_ENABLED("no_pr_label_enabled"),
    NO_PR_LABEL("no_pr_label"),
    CARD_HAS_NO_PR_LABEL("card_has_no_pr_label");

    /// The prompt template variable that holds these fields.
    public static final String VARIABLE = "pull_request_handoff";

    private final String key;

    PullRequestHandoffField(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /// The Pebble expression for this field, for example `pull_request_handoff.mode`.
    public String reference() {
        return VARIABLE + "." + key;
    }
}
