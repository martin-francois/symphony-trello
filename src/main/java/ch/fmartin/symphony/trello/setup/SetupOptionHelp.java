package ch.fmartin.symphony.trello.setup;

/**
 * Help text shared by setup commands that declare the same option in different option classes,
 * so the descriptions and the documented max-agents bound cannot drift apart.
 */
final class SetupOptionHelp {
    static final String SERVER_PORT = "Local HTTP status port.";
    static final String MAX_AGENTS = "Maximum cards processed concurrently for this board (1-"
            + TrelloBoardSetup.MAX_SETUP_CONCURRENT_AGENTS
            + "). Each card runs its own Codex agent plus builds and tests; keep 1 until the machine, repository, and prerequisite checklists are ready for parallel work.";
    static final String CODEX_MODEL = "Codex model to write into generated workflows.";
    static final String CODEX_REASONING_EFFORT = "Codex reasoning effort to write into generated workflows.";

    private SetupOptionHelp() {}
}
