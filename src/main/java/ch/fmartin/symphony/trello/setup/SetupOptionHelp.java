package ch.fmartin.symphony.trello.setup;

/// Help text shared by setup commands that declare the same option in different option classes,
/// so the descriptions and the documented max-agents bound cannot drift apart.
final class SetupOptionHelp {
    static final String SERVER_PORT = "Local HTTP status port.";
    static final String MAX_AGENTS = "Maximum cards processed concurrently for this board (1-"
            + TrelloBoardSetup.MAX_SETUP_CONCURRENT_AGENTS
            + "). Each card runs its own Codex agent plus builds and tests; keep 1 until the machine, repository, and prerequisite checklists are ready for parallel work.";
    static final String CODEX_MODEL = "Codex model ID to write into generated workflows without prompting. Any model ID"
            + " is accepted. Omit it in guided setup to pick from the installed Codex model list or enter another ID.";
    static final String CODEX_REASONING_EFFORT = "Codex reasoning effort to write into generated workflows.";
    static final String CODEX_REVIEW_MODEL =
            "Codex model ID for the review before handoff. Omit it to review with" + " the workflow's Codex model.";
    static final String CODEX_REVIEW_REASONING_EFFORT = "Codex reasoning effort for the review before handoff. Omit it"
            + " to review with the workflow's reasoning effort.";

    private SetupOptionHelp() {}
}
