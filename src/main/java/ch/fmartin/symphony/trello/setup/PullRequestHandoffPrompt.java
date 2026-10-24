package ch.fmartin.symphony.trello.setup;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import ch.fmartin.symphony.trello.config.PullRequestHandoffField;

/// Generated workflow text for the branch-only pull request handoff in `SPEC.md` Section 19.6.
/// Branch-only handoff stays agent-owned: Java resolves the mode from structured workflow and label
/// data, and this text tells Codex how to push without a pull request. See ADR 0119.
final class PullRequestHandoffPrompt {
    static final String SECTION_TITLE = "Pull Request Handoff Mode";

    static final String REWORK_NOTE =
            """
            For a branch-only handoff without a pull request, continue the same branch and checkout, update the
            proposed description file when the instructions change, and keep that file uncommitted.""";

    private static final String CREATE_LATER_TITLE = "Creating The Pull Request Later";
    private static final String WORKFLOW_MODE_KEY =
            EffectiveConfig.GitHubConfig.qualifiedKey(EffectiveConfig.GitHubConfig.PULL_REQUEST_MODE_KEY);
    private static final String LABEL_KEY =
            EffectiveConfig.GitHubConfig.qualifiedKey(EffectiveConfig.GitHubConfig.NO_PR_LABEL_KEY);

    private PullRequestHandoffPrompt() {}

    static String modeSection(String reviewHandoff, String handoffEvidence, String pushPrSkill) {
        return String.join(
                "\n\n",
                resolvedMode(),
                precedenceRules(),
                branchOnlyHandoff(reviewHandoff, handoffEvidence, pushPrSkill),
                createLater(reviewHandoff, pushPrSkill));
    }

    /// The merge rules for a card branch that has no pull request, appended to the generated merge section.
    static String mergeRules(
            String mergingState, String reviewHandoff, String doneDestination, String blockedText, String landSkill) {
        return """
                Branch-only exception: when the %s section selects `branch_only` and no open
                pull request exists for the card branch, skip the PR steps above and merge the branch itself.
                Follow the branch-only merge steps in `%s`. Merge into the target branch the card
                names, or the repository default branch otherwise. If the branch commits are already on the target
                branch, skip the merge. Push the target branch normally and never force-push it. Do not apply pull
                request review-thread handling. After the merge, or when no merge was needed, say in the completion
                comment which branch was merged into which target, or why no merge was needed, and move the card to
                %s. If the merge or push is rejected, %s.

                When the effective mode is `create`, no pull request exists for the card branch, and an earlier
                branch-only handoff pushed that branch, for example because a human removed the label and moved the
                card straight to %s, do not merge the branch directly. Create the pull request first as described
                in %s, but keep the card in %s instead of moving it to %s. Then follow the PR
                merge steps above for the new pull request."""
                .formatted(
                        SECTION_TITLE,
                        landSkill,
                        doneDestination,
                        blockedText,
                        mergingState,
                        CREATE_LATER_TITLE,
                        mergingState,
                        reviewHandoff);
    }

    private static String resolvedMode() {
        return """
                ## %s

                Symphony resolves the pull request handoff mode for each run from the workflow's `%s` settings
                and the card's current Trello labels:

                - Effective mode for this run: `{{ %s }}`, selected by
                  `{{ %s }}`.
                - Workflow default from `%s`: `{{ %s }}`.
                - Card label from `%s`: {%% if %s %%}`{{ %s }}`. This card has it: {%% if %s %%}yes{%% else %%}no{%% endif %%}{%% else %%}turned off in this workflow{%% endif %%}."""
                .formatted(
                        SECTION_TITLE,
                        EffectiveConfig.GitHubConfig.SECTION_KEY,
                        PullRequestHandoffField.MODE.reference(),
                        PullRequestHandoffField.SELECTED_BY.reference(),
                        WORKFLOW_MODE_KEY,
                        PullRequestHandoffField.WORKFLOW_MODE.reference(),
                        LABEL_KEY,
                        PullRequestHandoffField.NO_PR_LABEL_ENABLED.reference(),
                        PullRequestHandoffField.NO_PR_LABEL.reference(),
                        PullRequestHandoffField.CARD_HAS_NO_PR_LABEL.reference());
    }

    private static String precedenceRules() {
        return """
                Apply these rules in order:

                1. A workflow default of `branch_only` is strict. Symphony creates no pull request for any card on
                   this board, whatever its labels or comments say. If a card or comment asks for a pull request,
                   do the branch-only handoff and explain that a human can open the pull request from the proposed
                   description, or change `%s` to `create` in the workflow.
                2. On a `create` board, a Trello comment that explicitly asks Symphony to create a pull request
                   overrides the card label for this run when the comment is newer than the latest branch-only
                   handoff. Treat the run as `create`.
                3. Otherwise the card label selects `branch_only`.
                4. Without these signals, use the pull request publication flow above.

                Once a pull request exists for the card branch, keep using the pull request flow for rework and
                merging, even when the label is still on the card."""
                .formatted(WORKFLOW_MODE_KEY);
    }

    private static String branchOnlyHandoff(String reviewHandoff, String handoffEvidence, String pushPrSkill) {
        return """
                ### Branch-Only Handoff

                When the effective mode is `branch_only` and no pull request exists for the card branch, the pull
                request requirement above does not apply. For repository-changing work:

                1. Implement and commit on a non-default task branch with the same commit author rules as PR-bound
                   work.
                2. Before pushing, run the local checks that would normally gate CI for this repository, such as its
                   CI workflow commands and the checks named in `AGENTS.md` or `CONTRIBUTING.md`, because no pull
                   request or CI run may follow. If a check fails because of the change, keep the card active and
                   fix it. Record failures that are clearly unrelated. If required checks cannot run, follow the
                   validation blocker rule above.
                3. Write the proposed pull request title and description to `PR.md` in the root of the task
                   checkout. Start the file with `# <proposed PR title>`, then a blank line, then the description.
                   Build the description from the repository pull request template as described above. If `PR.md`
                   already exists and an earlier branch-only handoff of this card did not create it, use the first
                   unused name of `PR-2.md`, `PR-3.md`, and so on, and say which file you used. On rework, reuse
                   and update the file named in the earlier handoff.
                4. Never stage or commit the proposed description file. Add its file name to the checkout's local
                   exclude file (`git rev-parse --git-path info/exclude`), stage only explicit paths, and before
                   each commit and push confirm that the file is absent from `git diff --cached --name-only` and
                   from every commit on the branch.
                5. Verify commit authors and push the branch with `%s`, then stop before its pull
                   request steps. Do not create a pull request.
                6. In %s, say that this is a branch-only handoff without a pull request and whether the
                   workflow default or the card label selected it. Include the path of the task checkout, the branch
                   name and its pushed head commit, the branch link on its own line as `Branch: <https://github.com/owner/repo/tree/branch-name>`
                   when the repository is on GitHub, the proposed description file path, and the local check
                   results. Naming the task checkout path is a deliberate exception to the rules that keep host
                   paths out of Trello text, because the user needs it to find the work. Name no other local path.
                7. Move the card to %s.

                On rework, continue the same branch and checkout, and update the code and the proposed description
                file from the new Trello comments."""
                .formatted(pushPrSkill, handoffEvidence, reviewHandoff);
    }

    private static String createLater(String reviewHandoff, String pushPrSkill) {
        return """
                ### %s

                When the effective mode is `create`, for example after a human removed the label, or a comment
                override applies, and an earlier branch-only handoff left a proposed description file, create the
                pull request with `%s`. Use the file's first `# ` heading as the title and the rest as
                the body, reconciled with the repository pull request template. Keep the file uncommitted, record
                the PR link as usual, and move the card to %s."""
                .formatted(CREATE_LATER_TITLE, pushPrSkill, reviewHandoff);
    }
}
