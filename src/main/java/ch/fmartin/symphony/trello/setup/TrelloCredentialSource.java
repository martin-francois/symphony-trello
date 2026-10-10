package ch.fmartin.symphony.trello.setup;

/// Where a Trello credential value came from. Setup, the CLI commands, and worker start share this
/// one list so persistence and diagnostics name the same source for the same input.
enum TrelloCredentialSource {
    /// Passed with `--key` or `--token`, or entered at the setup prompt. Only these values are
    /// written to the credential file.
    DIRECT_INPUT,
    SHELL_ENVIRONMENT,
    DOTENV_FILE,
    /// Worker start only: the workflow sets a literal or `file:` value instead of an environment
    /// reference, so neither the shell environment nor the credential file is read.
    WORKFLOW_CONFIG,
    MISSING
}
