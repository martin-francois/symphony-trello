package ch.fmartin.symphony.trello.setup;

record Prerequisites(
        ToolStatus git,
        ToolStatus java,
        ToolStatus codex,
        ToolStatus codexAuth,
        ToolStatus githubCli,
        ToolStatus githubAuth) {
    boolean requiredReady() {
        return git.available() && java.available() && codex.available() && codexAuth.available();
    }

    boolean readyFor(LocalSetup.Options options) {
        return requiredReady() && (!options.githubMode().orElse(false) || githubAuth.available());
    }
}
