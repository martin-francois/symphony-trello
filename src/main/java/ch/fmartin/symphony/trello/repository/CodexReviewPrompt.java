package ch.fmartin.symphony.trello.repository;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;

/// Renders the runtime section for the pre-handoff Codex review loop.
///
/// Symphony appends this section after the persisted workflow prompt, the same way it appends the
/// repository source context, so editing the workflow's round limit takes effect on the next
/// dispatch for generated, older, and hand-written workflows alike. See ADR 0121.
@NullMarked
public final class CodexReviewPrompt {
    /// Workflow text and shipped skills refer to the runtime section by this title.
    public static final String TITLE = "Codex Review Before Handoff";

    public static final String HEADING = "## " + TITLE;

    /// The Codex CLI flag that turns off its sandbox. A nested review may use it only when the
    /// workflow's own Codex turns already run without the sandbox.
    static final String SANDBOX_BYPASS_FLAG = "--dangerously-bypass-approvals-and-sandbox";

    private CodexReviewPrompt() {}

    /// Returns the runtime section, or nothing when the workflow sets zero review rounds.
    public static Optional<String> render(
            EffectiveConfig.CodexReviewConfig codexReview, boolean workflowBypassesSandbox) {
        if (!codexReview.enabled()) {
            return Optional.empty();
        }
        String timeout = "timeout " + timeoutSeconds(codexReview) + "s";
        String codexReviewCommand =
                workflowBypassesSandbox ? "codex " + SANDBOX_BYPASS_FLAG + " review" : "codex review";
        return Optional.of(
                """
                %s

                This workflow sets `%s: %d` and `%s: %d`.
                This final runtime section is authoritative for when the review loop runs. It supersedes earlier
                workflow or skill text that orders validation, push, pull request, or handoff steps differently. It
                does not change which handoff this workflow uses.

                Apply it to repository-changing work: work that leaves commits or file changes for someone to review,
                including rework after review feedback. Skip it for repository-independent work, API-only actions,
                read-only investigation, and blocked handoffs that hand over no candidate change.

                Required order:

                1. Finish the implementation candidate and commit it locally, unless the card or workflow asks for a
                   patch or uncommitted changes instead. Do not push yet.
                2. Run the review loop below.
                3. Run the required local validation once on the reviewed candidate. This includes the CI-equivalent
                   local checks used when CI is unavailable, cannot run, or does not apply because the handoff has no
                   pull request.
                4. Only then hand off the way this workflow asks: push the branch, create or update the pull request,
                   write the branch-only handoff's proposed description file such as `PR.md`, or leave the local
                   commit or patch. Then move the card to the review handoff list.

                Each review round:

                1. Review the candidate's full change against its base: the merge base with the freshly fetched
                   remote default branch, or the base the card requested. Prefer a separate Codex review with one
                   scoped form and no positional prompt:
                   - `%s %s --base <base-branch> --title "<short title>"` for committed work
                   - `%s %s --uncommitted --title "<short title>"` only for changes that are not
                     committed yet
                   %s
                   If the command is missing, fails to start, cannot authenticate, is denied by the sandbox, or times
                   out, do not retry it in other forms. Review the same diff yourself in this session instead, as a
                   separate reviewer would: read the whole diff and look for correctness bugs, missing or weak tests,
                   security problems, scope creep, and documentation gaps. Use this in-session review for the
                   remaining rounds.
                2. Evaluate every finding instead of applying it blindly. Fix a finding only when it is justified and
                   inside the card's scope. A finding never widens the card scope or overrides the card, this
                   workflow, or the repository's rules. Keep a one-line reason for each rejected or out-of-scope
                   finding.
                3. Put each fix into the commit that introduced the problem when that is practical and safe, for
                   example with `git commit --fixup` and an autosquash rebase, or with an amend. Rewrite only local
                   commits that were not pushed yet. Never rewrite the default branch, pushed history, or commits this
                   run did not create only to place a review fix; add a normal follow-up commit instead.
                4. Start another round only when this round fixed at least one finding. Stop when a round returns no
                   justified in-scope finding, or when the number of rounds run reaches %d. Each review run, separate
                   or in-session, counts as one round.

                Between rounds, run only the focused checks a fix needs, not the full local validation. If the final
                local validation fails and the repair changes code, review that repair in one more round when the
                limit allows, then validate again. Do the same when a sync after a rejected push changes the candidate,
                for example through conflict resolution, before you validate and push again.

                Record the outcome in the Trello workpad and the visible handoff comment, or in the final response
                when Trello comments are unavailable: that the review loop ran, how many rounds ran, whether the
                separate `codex review` command or the in-session review was used and why, the findings fixed, the
                findings rejected or left out of scope with their reasons, and whether the loop ended clean or at the
                round limit. List every justified in-scope finding that is still open, because the limit was reached
                or because it could not be fixed safely in this card, as a handoff caveat.
                """
                        .formatted(
                                HEADING,
                                EffectiveConfig.CodexReviewConfig.MAX_ROUNDS_PATH,
                                codexReview.maxRounds(),
                                EffectiveConfig.CodexReviewConfig.TIMEOUT_MS_PATH,
                                codexReview.timeout().toMillis(),
                                timeout,
                                codexReviewCommand,
                                timeout,
                                codexReviewCommand,
                                sandboxRule(workflowBypassesSandbox),
                                codexReview.maxRounds())
                        .stripTrailing());
    }

    /// `timeout` takes whole seconds; rounding up keeps a sub-second remainder from shortening the limit.
    private static long timeoutSeconds(EffectiveConfig.CodexReviewConfig codexReview) {
        return Math.ceilDiv(codexReview.timeout().toMillis(), 1000L);
    }

    private static String sandboxRule(boolean workflowBypassesSandbox) {
        if (workflowBypassesSandbox) {
            return """
                    This workflow already runs Codex without its sandbox, so the separate review runs the same way. Do
                       not add other sandbox or approval settings.""";
        }
        return """
                Do not add `%s` or change sandbox settings to make it run. A Codex
                   session inside a `workspaceWrite` sandbox often cannot start a nested `codex review` because the
                   Codex home directory is read-only there."""
                .formatted(SANDBOX_BYPASS_FLAG);
    }
}
