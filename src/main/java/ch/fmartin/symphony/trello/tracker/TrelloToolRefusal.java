package ch.fmartin.symphony.trello.tracker;

/// Structured errors for Trello tool calls that the workflow's `trello_tools` policy refuses. The
/// current-card handoff tools and the board-session tools report the same code and message for the
/// same policy setting.
public enum TrelloToolRefusal {
    TOOLS_DISABLED("trello_tools_disabled", "Trello tools are disabled by trello_tools.enabled."),
    WRITES_DISABLED("trello_writes_disabled", "Trello writes are disabled by trello_tools.allow_writes."),
    COMMENTS_DISABLED("trello_comments_disabled", "Trello comments are disabled by trello_tools.allow_comments."),
    CHECKLISTS_DISABLED(
            "trello_checklists_disabled", "Trello checklists are disabled by trello_tools.allow_checklists."),
    URL_ATTACHMENTS_DISABLED(
            "trello_url_attachments_disabled",
            "Trello URL attachments are disabled by trello_tools.allow_url_attachments."),
    MOVE_ALLOWLIST_REQUIRED(
            "trello_move_allowlist_required",
            "Trello card moves require trello_tools.allowed_move_list_ids or allowed_move_list_names.");

    private final String code;
    private final String message;

    TrelloToolRefusal(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
