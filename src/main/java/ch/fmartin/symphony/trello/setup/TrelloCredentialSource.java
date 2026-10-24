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
    MISSING;

    /// Where a key and token pair came from, for messages that name one place when both values
    /// share it and list both places otherwise.
    enum Pair {
        SHELL_ENVIRONMENT,
        DOTENV_FILE,
        /// Every other pair, including two direct, two workflow, or two missing values.
        MIXED;

        static Pair of(TrelloCredentialSource apiKeySource, TrelloCredentialSource apiTokenSource) {
            if (apiKeySource == TrelloCredentialSource.SHELL_ENVIRONMENT
                    && apiTokenSource == TrelloCredentialSource.SHELL_ENVIRONMENT) {
                return SHELL_ENVIRONMENT;
            }
            if (apiKeySource == TrelloCredentialSource.DOTENV_FILE
                    && apiTokenSource == TrelloCredentialSource.DOTENV_FILE) {
                return DOTENV_FILE;
            }
            return MIXED;
        }
    }
}
