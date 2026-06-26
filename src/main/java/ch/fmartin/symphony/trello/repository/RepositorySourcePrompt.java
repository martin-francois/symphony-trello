package ch.fmartin.symphony.trello.repository;

public final class RepositorySourcePrompt {
    private RepositorySourcePrompt() {}

    public static String render(RepositorySourceSelection selection) {
        return switch (selection.status()) {
            case SELECTED -> selected(selection.source());
            case INVALID_SELECTED -> invalid(selection.problem());
            case NONE -> none();
        };
    }

    private static String selected(RepositorySource source) {
        return switch (source.kind()) {
            case REMOTE -> selectedRemote(source);
            case LOCAL_PATH -> selectedLocalPath(source);
        };
    }

    private static String selectedRemote(RepositorySource source) {
        return String.join(
                "\n",
                "## Repository Source Context",
                "",
                "This context is computed from explicit Trello card source labels and trusted workflow configuration.",
                "",
                "- Status: selected",
                "- Selected by: " + origin(source),
                "- Type: URL",
                "- Credential-free remote: " + source.value(),
                "- Repository identity: " + source.identity().key(),
                selectedInstruction(source),
                selectedPrivacyInstruction());
    }

    private static String selectedLocalPath(RepositorySource source) {
        return String.join(
                "\n",
                "## Repository Source Context",
                "",
                "This context is computed from explicit Trello card source labels and trusted workflow configuration.",
                "",
                "- Status: selected",
                "- Selected by: " + origin(source),
                "- Type: local path",
                "- Resolved local path: " + source.path(),
                selectedInstruction(source),
                selectedPrivacyInstruction());
    }

    private static String invalid(RepositorySourceProblem problem) {
        return String.join(
                "\n",
                "## Repository Source Context",
                "",
                "This context is computed from explicit Trello card source labels and trusted workflow configuration.",
                "",
                "- Status: invalid selected source",
                "- Code: " + problem.code(),
                "- Guidance: " + problem.guidance(),
                "- Do not use workflow defaults or other lower-priority fallbacks for this Trello card.",
                "- Do not copy private repository URLs, local host paths, credentials, or raw parser details into Trello-visible comments or status text.");
    }

    private static String none() {
        return """
                ## Repository Source Context

                No workflow repository default is configured, and the Trello card does not include an explicit repository source label. No repository source is selected.
                """
                .stripTrailing();
    }

    private static String selectedInstruction(RepositorySource source) {
        if (source.explicitCardSource()) {
            return "- Explicit Trello card repository sources are authoritative and suppress workflow defaults.";
        }
        return "- Use this workflow default only when the current Trello card names no explicit repository URL or local checkout path.";
    }

    private static String selectedPrivacyInstruction() {
        return "- Do not copy private repository URLs, local host paths, credentials, or source details from this context into Trello-visible comments or status text.";
    }

    private static String origin(RepositorySource source) {
        return switch (source.origin()) {
            case CARD -> "explicit Trello card source";
            case WORKFLOW_DEFAULT_URL -> "workflow repository.default_url";
            case WORKFLOW_DEFAULT_PATH -> "workflow repository.default_path";
        };
    }
}
