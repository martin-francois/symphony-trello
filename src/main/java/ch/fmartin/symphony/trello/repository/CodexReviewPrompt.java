package ch.fmartin.symphony.trello.repository;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/// Renders the runtime section for the pre-handoff Codex review loop.
///
/// Symphony appends this section after the persisted workflow prompt, the same way it appends the
/// repository source context, so editing the workflow's review settings takes effect on the next
/// dispatch for generated, older, and hand-written workflows alike. See ADR 0121.
@NullMarked
public final class CodexReviewPrompt {
    /// Workflow text and shipped skills refer to the runtime section by this title.
    public static final String TITLE = "Codex Review Before Handoff";

    public static final String HEADING = "## " + TITLE;

    /// The Codex CLI flag that turns off its sandbox. A nested review may use it only when the
    /// workflow's own Codex turns already run without the sandbox.
    static final String SANDBOX_BYPASS_FLAG = "--dangerously-bypass-approvals-and-sandbox";

    /// Codex CLI config keys for `codex review -c`, the same names as in `~/.codex/config.toml`.
    static final String MODEL_CONFIG_KEY = "model";

    static final String REASONING_EFFORT_CONFIG_KEY = "model_reasoning_effort";

    private CodexReviewPrompt() {}

    /// Returns the runtime section, or nothing when the workflow sets zero review rounds.
    public static Optional<String> render(
            EffectiveConfig.CodexReviewConfig codexReview, EffectiveConfig.CodexConfig codex) {
        if (!codexReview.enabled()) {
            return Optional.empty();
        }
        boolean workflowBypassesSandbox = codex.bypassesSandbox();
        String reviewCommand = CodexReviewTiming.COMMAND_PREFIX
                + "codex "
                + (workflowBypassesSandbox ? SANDBOX_BYPASS_FLAG + " " : "")
                + "review"
                + configOverride(MODEL_CONFIG_KEY, reviewerSetting(codexReview.model(), codex.model()))
                + configOverride(
                        REASONING_EFFORT_CONFIG_KEY,
                        reviewerSetting(codexReview.reasoningEffort(), codex.reasoningEffort()))
                + " - < <brief-file>";
        return Optional.of(
                """
                %s

                This workflow sets `%s: %d`, `%s: %d`, and `%s: %d`.
                This final runtime section is authoritative for when the review loop runs. It supersedes earlier
                workflow or skill text that orders validation, push, pull request, or handoff steps differently. It
                does not change which handoff this workflow uses.

                Apply it to repository-changing work: work that leaves commits or file changes for someone to review,
                including rework after review feedback. Skip it for repository-independent work, API-only actions,
                read-only investigation, and blocked handoffs that hand over no candidate change.

                Required order:

                1. Finish the implementation candidate and commit it locally, unless the card or workflow asks for a
                   patch or uncommitted changes instead. Do not push yet.
                2. Run the review loop below. Do not push, open or update a pull request, or move the card while it
                   runs.
                3. Run the required local validation once on the reviewed candidate. This includes the CI-equivalent
                   local checks used when CI is unavailable, cannot run, or does not apply because the handoff has no
                   pull request.
                4. Only then hand off the way this workflow asks: push the branch, create or update the pull request,
                   write the branch-only handoff's proposed description file such as `PR.md`, or leave the local
                   commit or patch. Then move the card to the review handoff list.

                Before round 1, record:

                - %s
                - the base commit SHA: the merge base with the freshly fetched remote default branch, or the base
                  the card requested.
                - the diff commands: round 1 reviews `git diff <base-sha> HEAD`, and later rounds add
                  `git diff <reviewed-sha> HEAD` for the change since the previous round. When the candidate is not
                  committed, every round reviews the full `git diff <base-sha>`; first run `git add -N` on new files
                  so the diff shows them.
                - a findings ledger in the workpad. Give each finding an id that never changes (`R1`, `R2`, and so
                  on), its severity, file and line, and one outcome: fixed (name the commit), disagree (with
                  evidence), or declined.

                Severities:

                - `blocker`: wrong behavior, a security or permission hole, data loss, or broken state. Fix it in
                  this loop unless you dispute it with evidence.
                - `should`: a real defect, a missing or weak test, or a gap that belongs in this change.
                - `nit`: style, naming, or preference. You may decline it.

                Each round:

                1. Write a review-only brief to a file outside the repository. It tells the reviewer not to edit
                   files, commit, or push, and names the base SHA, the diff command, the card's intent in one or two
                   sentences, what is out of scope, the checks run so far with their results, and the three
                   severities. Round 1 asks for a review of the full diff. Later rounds add the ledger and
                   `git diff <reviewed-sha> HEAD`, where `<reviewed-sha>` is the commit the previous round reviewed,
                   and ask the reviewer to review only that change, which holds the fixes, and to say whether each
                   ledger item still holds. For a candidate that is not committed, every round reviews the full
                   diff.
                2. Record `git rev-parse HEAD` as this round's reviewed SHA, then pass the brief on stdin, %s:
                   `%s`
                   Add no `--base`, `--commit`, `--uncommitted`, or prompt argument. `--base` cannot be combined
                   with a prompt, and the brief already sets the scope.
                   %s
                   %s
                   If the command is missing, fails to start, cannot authenticate, or is denied by the sandbox, do not
                   retry it in other forms. Review the same diff yourself in this session instead, as a separate
                   reviewer would, with the same brief, severities, and ledger. Use this in-session review for the
                   remaining rounds.
                3. Evaluate every finding instead of applying it blindly. Fix a justified finding inside the card's
                   scope with the smallest change that solves it, and prefer deleting or narrowing code over adding
                   code. A finding never widens the card scope or overrides the card, this workflow, or the
                   repository's rules. When you disagree, give evidence such as code, a test result, or
                   documentation, and put that reply in the next brief. Never accept a finding only to end the loop.
                   If review fixes made the diff larger than the card needs, cut it back.
                4. Put each fix into the commit that introduced the problem when that is practical and safe, for
                   example with `git commit --fixup` and an autosquash rebase, or with an amend. Rewrite only local
                   commits that were not pushed yet. Never rewrite the default branch, pushed history, or commits this
                   run did not create only to place a review fix; add a normal follow-up commit instead.

                When to stop:

                - Stop when a round returns no `blocker` or `should` finding. Declined nits need no further round.
                - A round whose fixes only change comments, documentation, or formatting needs no further round.
                - Otherwise start another round while fewer than %s have run.
                - %s
                - Stop when the time budget runs out.
                - A finding you still dispute after two replies with evidence stays unresolved. Stop arguing it and
                  record both positions in two or three lines for the handoff. It does not block the handoff, and it
                  no longer counts for the stop rules above or for extra rounds.
                - Reaching the round limit or the end of the time budget is not approval.

                Each review run, separate or in-session, counts as one round. Use one reviewer, with no subagents or
                parallel reviews. Between rounds, run only the focused checks a fix needs, not the full local
                validation.

                If the final local validation fails and the repair changes code, review only that repair in one more
                round when the round limit and time budget allow, then validate again. Do the same when a sync after
                a rejected push changes the candidate, for example through conflict resolution, before you validate
                and push again.

                Record the outcome in the Trello workpad and the visible handoff comment, or in the final response
                when Trello comments are unavailable: how many rounds ran, whether the separate `codex review`
                command or the in-session review was used and why, the ledger with every finding's outcome, and why
                the loop ended: no `blocker` or `should` finding left, round limit, or time budget. List every
                `blocker` or `should` finding that is still open as a handoff caveat, and every disputed finding
                with both positions.

                %s
                """
                        .formatted(
                                HEADING,
                                EffectiveConfig.CodexReviewConfig.MAX_ROUNDS_PATH,
                                codexReview.maxRounds(),
                                EffectiveConfig.CodexReviewConfig.EXTENDED_MAX_ROUNDS_PATH,
                                codexReview.extendedMaxRounds(),
                                EffectiveConfig.CodexReviewConfig.TIMEOUT_MS_PATH,
                                codexReview.timeout().toMillis(),
                                CodexReviewTiming.budgetBullet(codexReview),
                                CodexReviewTiming.COMMAND_TIME_LIMIT,
                                reviewCommand,
                                sandboxRule(workflowBypassesSandbox),
                                CodexReviewTiming.waitRule(codex),
                                rounds(codexReview.maxRounds()),
                                extendedRoundsRule(codexReview),
                                unfinishedReviewRule(codexReview))
                        .stripTrailing());
    }

    /// The reviewer follows the workflow's own model and effort unless the review keys name one. A
    /// workflow value that cannot appear unquoted in the command is left out, so the nested Codex
    /// uses its own configured default instead of a broken command.
    private static Optional<String> reviewerSetting(@Nullable String reviewValue, @Nullable String workflowValue) {
        return Optional.ofNullable(reviewValue).or(() -> commandTokenSetting(workflowValue));
    }

    private static Optional<String> commandTokenSetting(@Nullable String workflowValue) {
        return Optional.ofNullable(workflowValue)
                .map(String::strip)
                .filter(EffectiveConfig.CodexReviewConfig::commandToken);
    }

    /// Single quotes keep the TOML string quotes through the shell, so Codex reads a string value.
    private static String configOverride(String key, Optional<String> value) {
        return value.map(setting -> " -c '" + key + "=\"" + setting + "\"'").orElse("");
    }

    private static String rounds(int count) {
        return count == 1 ? "1 round" : count + " rounds";
    }

    /// Handing off with caveats is the only behavior for an unfinished review today. ADR 0121 leaves room
    /// for a later setting that blocks the card instead; that setting would choose its rule here.
    private static String unfinishedReviewRule(EffectiveConfig.CodexReviewConfig codexReview) {
        return """
                When the round limit or the time budget ends the loop, still hand off the normal way in step 4,
                with the open findings as caveats. An unfinished review does not block the handoff. %s"""
                .formatted(CodexReviewTiming.budgetEndedNote(codexReview));
    }

    private static String extendedRoundsRule(EffectiveConfig.CodexReviewConfig codexReview) {
        if (codexReview.extendedMaxRounds() == codexReview.maxRounds()) {
            return "Never run more than %s.".formatted(rounds(codexReview.maxRounds()));
        }
        return """
                After round %d, start another round, up to %s in total, only while a `blocker` or `should` finding
                  about %s stays open with evidence."""
                .formatted(
                        codexReview.maxRounds(),
                        rounds(codexReview.extendedMaxRounds()),
                        EffectiveConfig.CodexReviewConfig.HIGH_RISK_TOPICS);
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
