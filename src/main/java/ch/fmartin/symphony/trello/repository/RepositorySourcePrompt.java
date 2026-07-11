package ch.fmartin.symphony.trello.repository;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import java.nio.file.Path;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class RepositorySourcePrompt {
    private RepositorySourcePrompt() {}

    public static String render(RepositorySourceSelection selection) {
        return render(selection, new EffectiveConfig.RepositoryConfig(null, null));
    }

    public static String render(RepositorySourceSelection selection, EffectiveConfig.RepositoryConfig repository) {
        String sourceContext =
                switch (selection.status()) {
                    case SELECTED -> selected(selection.source());
                    case INVALID_SELECTED -> invalid(selection.problem(), selection.invalidWorkflowFallback());
                    case NONE -> none();
                };
        if (selection.status() == RepositorySourceSelection.Status.INVALID_SELECTED
                && !selection.invalidWorkflowFallback()) {
            return sourceContext;
        }
        return sourceContext + configuredCheckoutCandidate(selection, repository) + "\n\n"
                + checkoutPreparationPolicy();
    }

    public static String checkoutPreparationPolicy() {
        return """
                ## Repository Checkout Preparation

                Apply this section only when the classified task needs repository files. Repository identity must
                already be selected from the card or workflow before local checkout discovery starts.

                1. If a selected local checkout path is shown above, use that repository. If a configured checkout
                   path candidate is shown above, inspect its Git remotes first and use that configured path before
                   searching for another local checkout, but only when read-only remote inspection confirms that it
                   matches the selected repository identity. Never use a configured path for a different repository.
                2. Otherwise, search the local checkouts that this run can access. Match a local checkout by repository
                   identity from its configured Git remotes. Do not match by directory name, current branch, proximity,
                   prior cards, or workspace residue. If one matching local repository exists, reuse it and do not clone
                   the repository again. If multiple matching repositories make the choice unsafe, block instead of
                   guessing.
                3. If no matching local repository exists, clone the selected repository from its normal
                   credential-free clone source into a repository-control directory inside the per-card workspace. Do
                   not clone merely because an existing matching checkout is on another branch or has local changes.
                4. From the chosen existing or newly cloned repository, determine the remote default branch, then fetch
                   the remote default branch before creating the task worktree. Create a separate task worktree inside
                   the per-card workspace from the freshly fetched remote default branch. Do not implement the task in
                   an arbitrary existing working tree, and do not modify the shared checkout worktree.
                5. If the card explicitly requests another branch, ref, base, or checkout arrangement, follow that
                   instruction instead of the default-branch worktree behavior. Fetch the requested remote ref before
                   creating the task worktree when a fetch is required.

                If a selected local repository cannot be read, fetched, or used to create the required task worktree,
                block with path-safe guidance instead of silently cloning another copy or editing an arbitrary checkout.
                """
                .stripTrailing();
    }

    private static String configuredCheckoutCandidate(
            RepositorySourceSelection selection, EffectiveConfig.RepositoryConfig repository) {
        Path path = repository.defaultPath();
        if (path == null || !canOfferConfiguredCheckoutCandidate(selection)) {
            return "";
        }
        String eligibility = selection.invalidWorkflowFallback()
                ? "- Consider this candidate only after card context supplies exactly one repository identity that overrides the invalid workflow URL."
                : "- Consider this candidate only after repository identity is already selected from the card or the valid selected remote above.";
        return String.join(
                "\n",
                "",
                "- Configured checkout path candidate: " + path.toAbsolutePath().normalize(),
                eligibility,
                "- This candidate never establishes or replaces repository identity. Use it only when read-only inspection of its Git remotes confirms that it matches the repository identity already selected from the card or valid remote.",
                "- Do not assume that it matches merely because the path and a workflow URL were configured together. Consider it before general local checkout discovery only after the remote-match guard succeeds.");
    }

    private static boolean canOfferConfiguredCheckoutCandidate(RepositorySourceSelection selection) {
        if (selection.status() == RepositorySourceSelection.Status.INVALID_SELECTED) {
            return selection.invalidWorkflowFallback();
        }
        RepositorySource source = selection.source();
        return selection.status() == RepositorySourceSelection.Status.SELECTED
                && source != null
                && source.kind() == RepositorySource.Kind.REMOTE;
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
                runtimeAuthorityInstruction(),
                "- Selected by: " + origin(source),
                "- Type: URL",
                "- Credential-free remote: " + source.value(),
                "- Repository identity: " + source.identity().key(),
                selectedInstruction(source),
                selectedTaskPolicy(source),
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
                runtimeAuthorityInstruction(),
                "- Selected by: " + origin(source),
                "- Type: local path",
                "- Resolved local path: " + source.path(),
                selectedInstruction(source),
                selectedTaskPolicy(source),
                selectedPrivacyInstruction());
    }

    private static String invalid(RepositorySourceProblem problem, boolean workflowFallback) {
        if (workflowFallback) {
            return invalidWorkflowFallback(problem);
        }
        return String.join(
                "\n",
                "## Repository Source Context",
                "",
                "This context is computed from explicit Trello card source labels and trusted workflow configuration.",
                "",
                "- Status: invalid selected source",
                runtimeAuthorityInstruction(),
                "- Code: " + problem.code(),
                "- Guidance: " + problem.guidance(),
                "- This selected source is invalid and must block the Trello card. Do not treat it as if no source were configured.",
                "- Block even when the requested task would otherwise be repository-independent.",
                "- Do not use workflow defaults or other lower-priority fallbacks for this Trello card.",
                "- Do not copy private repository URLs, local host paths, credentials, or raw parser details into Trello-visible comments or status text.");
    }

    private static String invalidWorkflowFallback(RepositorySourceProblem problem) {
        return String.join(
                "\n",
                "## Repository Source Context",
                "",
                "This context describes an invalid workflow fallback, not an explicit Trello card source.",
                "",
                "- Status: invalid workflow fallback",
                runtimeAuthorityInstruction(),
                "- Code: " + problem.code(),
                "- Guidance: " + problem.guidance(),
                "- First inspect ordinary Trello card context for exactly one unambiguous repository identity.",
                "- If card context supplies exactly one repository identity, ignore this unused workflow fallback and use the card repository identity. A full repository, issue, pull request, or file URL; owner/repository notation; or equivalent single identity can supply it.",
                "- A fully qualified GitHub issue or pull request URL is a direct external target. For an API-only action, act directly on that target and do not create a checkout.",
                "- When the requested work needs repository files, derive the card repository's normal credential-free clone URL and prepare a checkout only because the requested work needs repository files.",
                "- If the card supplies no repository identity, block unconditionally because the configured workflow fallback is invalid, even when the task would otherwise be repository-independent. Do not treat it as absent or use a lower-priority fallback.",
                "- If card context supplies conflicting repository identities, block. Do not select one arbitrarily or use the malformed workflow fallback.",
                "- A lower-priority configured path never establishes repository identity. Do not promote it over this invalid selected workflow URL. It may be considered only after exactly one card identity overrides the URL and read-only inspection confirms that the path's Git remotes match that card identity.",
                "- Do not copy private repository URLs, local host paths, credentials, or raw parser details into Trello-visible comments or status text.");
    }

    private static String none() {
        return String.join(
                "\n",
                "## Repository Source Context",
                "",
                "- Status: no selected source",
                runtimeAuthorityInstruction(),
                "- Ignore any earlier unconditional instruction to block solely because no repository source is selected. Classify the current task before deciding whether the missing source is a blocker.",
                "- No workflow repository default is configured, and the Trello card does not include an explicit repository source label.",
                "- The absence of a selected repository is not a blocker by itself. Classify the current task before applying repository-source blockers.",
                "- Repository-independent work without repository-relative references can run without a selected source.",
                "- A fully qualified GitHub issue or pull request URL is a direct external target. Use that target as written, and do not require a repository checkout for that API action.",
                "- A single unambiguous repository identity in the Trello card is sufficient, even when it is ordinary task context rather than an explicit repository source label. It may be supplied by a full repository, issue, pull request, or file URL; owner/repository notation; or equivalent context that clearly identifies one repository.",
                "- When that card identity is unambiguous, use it for repository-relative references, derive its normal credential-free clone URL, and prepare a writable checkout when the requested work needs repository files.",
                "- Do not create a checkout for an API-only action merely because its target identifies a repository.",
                "- Block only when the required repository identity is absent, conflicting, or unusable, or when the required checkout cannot be prepared.",
                "- Do not infer repository identity from unrelated checkouts, prior Trello cards, or leftover workspace contents.");
    }

    private static String runtimeAuthorityInstruction() {
        return "- Runtime authority: This final runtime repository-source context is authoritative for repository-source selection, task classification, and source blocker decisions. It supersedes any conflicting repository-source guidance earlier in this prompt.";
    }

    private static String selectedTaskPolicy(RepositorySource source) {
        return String.join(
                "\n",
                selectedValidationPolicy(source),
                selectedIdentityPolicy(source),
                "- Do not create or reuse a task checkout unless the classified task requires repository files.",
                "- A fully qualified GitHub issue or pull request URL remains its own direct target, even when it names a repository other than the selected source.",
                selectedUnusablePolicy(source));
    }

    private static String selectedValidationPolicy(RepositorySource source) {
        if (source.explicitCardSource()) {
            return "- Validate this selected source before executing the task. Use a read-only probe that can confirm the source is available, readable, and can support a checkout. Do not create a checkout or write to the selected source during validation; source validation does not make the task repository-changing.";
        }
        return "- This workflow source is a fallback. First inspect the Trello card for a single unambiguous repository identity in ordinary task context. When the card clearly identifies another repository, use the card repository and do not validate or prepare this unused fallback. Otherwise, validate this workflow source with a read-only probe before executing the task; validation must not create a checkout or write to the source.";
    }

    private static String selectedIdentityPolicy(RepositorySource source) {
        return switch (source.kind()) {
            case REMOTE ->
                "- This selected remote source supplies repository identity for repository-relative references, but it does not by itself make the task repository-changing.";
            case LOCAL_PATH ->
                """
                - For a repository-relative issue or pull request reference, use this local source only when read-only inspection finds exactly one explicit, unambiguous compatible remote that supplies repository identity.
                - If no such remote supplies identity, block instead of deriving identity from the local path, directory name, or branch. Request a fully qualified repository URL together with the issue or pull request number.
                """
                        .stripTrailing();
        };
    }

    private static String selectedUnusablePolicy(RepositorySource source) {
        if (source.explicitCardSource()) {
            return "- If this selected source is unavailable, unreadable, or cannot support a checkout, block the card instead of treating the source as absent or using a fallback, even when the task would otherwise be repository-independent.";
        }
        return "- If this workflow fallback is actually needed and is unavailable, unreadable, or cannot support a checkout, block the card instead of treating it as absent. Do not block because an unused fallback is unavailable when the card clearly identifies another repository.";
    }

    private static String selectedInstruction(RepositorySource source) {
        if (source.explicitCardSource()) {
            return "- Explicit Trello card repository sources are authoritative and suppress workflow defaults.";
        }
        return "- Use this workflow default only when the current Trello card supplies no explicit source and no unambiguous repository identity in ordinary task context.";
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
