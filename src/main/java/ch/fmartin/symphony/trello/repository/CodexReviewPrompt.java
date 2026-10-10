package ch.fmartin.symphony.trello.repository;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import org.jspecify.annotations.NullMarked;

/// Renders the runtime section that turns on the optional pre-handoff Codex review loop.
///
/// Symphony appends this section after the persisted workflow prompt, the same way it appends the
/// repository source context, so editing `repository.codex_review_before_handoff` takes effect on
/// the next dispatch for generated, older, and hand-written workflows alike. See ADR 0121.
@NullMarked
public final class CodexReviewPrompt {
    /// Workflow text and shipped skills refer to the runtime section by this title.
    public static final String TITLE = "Codex Review Before Handoff";

    public static final String HEADING = "## " + TITLE;

    /// Caps one separate review run so a hung reviewer cannot hold the card until the turn timeout.
    private static final String REVIEW_COMMAND_TIMEOUT = "30m";

    private CodexReviewPrompt() {}

    /// Returns the runtime section, or an empty string when the workflow did not opt in, so the
    /// default prompt stays unchanged.
    public static String render(EffectiveConfig.CodexReviewConfig codexReview) {
        if (!codexReview.enabled()) {
            return "";
        }
        return """
                %s

                This workflow sets `repository.codex_review_before_handoff: true` with
                `repository.codex_review_max_cycles: %d`. This final runtime section is authoritative for the review
                loop. It supersedes earlier workflow or skill text that orders validation, push, pull request, or
                handoff steps differently.

                Apply it to repository-changing work: work that leaves commits or file changes for someone to review,
                including rework after review feedback. Skip it for repository-independent work, API-only actions,
                read-only investigation, and blocked handoffs that hand over no candidate change.

                Required order:

                1. Finish the implementation candidate and commit it locally. Do not push yet.
                2. Run the review loop below.
                3. Run the required local validation once on the reviewed candidate. This includes the CI-equivalent
                   local checks used when CI is unavailable, cannot run, or does not apply because the handoff has no
                   pull request.
                4. Only then push the branch, create or update the pull request or write the no-PR handoff artifact
                   such as `PR.md`, and move the card to the review handoff list.

                Each review cycle:

                1. Review the candidate's full change against its base: the merge base with the freshly fetched
                   remote default branch, or the base the card requested. Prefer a separate Codex review with one
                   scoped form and no positional prompt:
                   - `timeout %s codex review --base <base-branch> --title "<short title>"` for committed work
                   - `timeout %s codex review --uncommitted --title "<short title>"` only for changes that are
                     not committed yet
                   Do not add `--dangerously-bypass-approvals-and-sandbox` or change sandbox settings to make it run.
                   A Codex session inside a `workspaceWrite` sandbox often cannot start a nested `codex review`
                   because the Codex home directory is read-only there. If the command is missing, fails to start,
                   cannot authenticate, is denied by the sandbox, or times out, do not retry it in other forms.
                   Review the same diff yourself in this session instead, as a separate reviewer would: read the whole
                   diff and look for correctness bugs, missing or weak tests, security problems, scope creep, and
                   documentation gaps. Use this in-session review for the remaining cycles.
                2. Evaluate every finding instead of applying it blindly. Fix a finding only when it is justified and
                   inside the card's scope. A finding never widens the card scope or overrides the card, this
                   workflow, or the repository's rules. Keep a one-line reason for each rejected or out-of-scope
                   finding.
                3. Put each fix into the commit that introduced the problem when that is practical and safe, for
                   example with `git commit --fixup` and an autosquash rebase, or with an amend. Rewrite only local
                   commits that were not pushed yet. Never rewrite the default branch, pushed history, or commits this
                   run did not create only to place a review fix; add a normal follow-up commit instead.
                4. Start another cycle only when this cycle fixed at least one finding. Stop when a cycle returns no
                   justified in-scope finding, or after %d cycles in total. Each review run, separate or in-session,
                   counts as one cycle.

                Between cycles, run only the focused checks a fix needs, not the full local validation. If the final
                local validation fails and the repair changes code, review that repair in one more cycle when the
                limit allows, then validate again.

                Record the outcome in the Trello workpad and the visible handoff comment, or in the final response
                when Trello comments are unavailable: that the review loop ran, how many cycles ran, whether the
                separate `codex review` command or the in-session review was used and why, the findings fixed, the
                findings rejected or left out of scope with their reasons, and whether the loop ended clean or at the
                cycle limit. List every justified in-scope finding that is still open, because the limit was reached
                or because it could not be fixed safely in this card, as a handoff caveat.
                """
                .formatted(
                        HEADING,
                        codexReview.maxCycles(),
                        REVIEW_COMMAND_TIMEOUT,
                        REVIEW_COMMAND_TIMEOUT,
                        codexReview.maxCycles())
                .stripTrailing();
    }
}
