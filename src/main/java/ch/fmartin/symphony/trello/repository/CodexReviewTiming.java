package ch.fmartin.symphony.trello.repository;

import ch.fmartin.symphony.trello.config.EffectiveConfig;
import java.time.Duration;
import org.jspecify.annotations.NullMarked;

/// Every time-dependent part of the review section: the loop budget, the limit on each review run,
/// what a run that the limit stops means, and the handoff note after the budget runs out. The
/// rest of [CodexReviewPrompt] only says "time budget", so changing how the review is timed means
/// changing this class. See ADR 0121.
@NullMarked
final class CodexReviewTiming {
    /// Wraps each `codex review` run so it ends when the loop budget does.
    static final String COMMAND_PREFIX = "timeout <remaining>s ";

    /// How to fill in `<remaining>` in the command.
    static final String COMMAND_TIME_LIMIT = "with the time left in the budget rounded up to whole seconds";

    /// The longest wait per poll on a running review. Each poll ends in an app-server event, so waits at
    /// most this long, and at most half of `codex.stall_timeout_ms`, keep the stall check from taking
    /// the wait for a stuck turn.
    static final Duration MAX_POLL_WAIT = Duration.ofMinutes(2);

    private CodexReviewTiming() {}

    /// The "Before round 1" bullet that starts the clock.
    static String budgetBullet(EffectiveConfig.CodexReviewConfig codexReview) {
        return """
                the loop start time, for example with `date +%%s`. The whole loop, every round included, has %d
                  seconds. Do not start a round when no whole second of the budget is left, because `timeout 0s`
                  runs without a limit; that means the time budget ran out."""
                .formatted(budgetSeconds(codexReview));
    }

    /// Agents stopped slow review runs themselves, sometimes after acting on partial output, so only
    /// the wrapper may end a run early.
    static String waitRule(EffectiveConfig.CodexConfig codex) {
        return """
                A `codex review` run often prints little for many minutes. Never cancel, interrupt, or kill a
                   running `codex review`: not with Ctrl-C, not by ending its session, and not by starting another
                   review, even when it looks stuck, prints nothing for a long time, or seems slow. Only the
                   `timeout` wrapper ends a run early. Keep polling until the command exits on its own or the
                   wrapper stops it, and do not act on findings until then. Wait at most %d seconds per poll, so
                   the stall check (`codex.stall_timeout_ms`) and the turn's silence timeout never see the wait as
                   silence.
                   A run the wrapper stopped means the time budget ran out: the loop ends, and the rule below for an
                   unfinished review applies."""
                .formatted(pollWaitSeconds(codex.stallTimeout()));
    }

    /// The handoff note when the budget, not a clean round or the round limit, ended the loop.
    static String budgetEndedNote(EffectiveConfig.CodexReviewConfig codexReview) {
        return """
                When the time budget ended it, say plainly in the handoff comment and the workpad that the review
                stopped because its time budget ran out, and suggest raising `%s` in the workflow file, which is now
                `%d`."""
                .formatted(
                        EffectiveConfig.CodexReviewConfig.TIMEOUT_MS_PATH,
                        codexReview.timeout().toMillis());
    }

    private static long pollWaitSeconds(Duration stallTimeout) {
        if (!stallTimeout.isPositive()) {
            return MAX_POLL_WAIT.toSeconds();
        }
        return Math.clamp(stallTimeout.dividedBy(2).toSeconds(), 1L, MAX_POLL_WAIT.toSeconds());
    }

    /// `timeout` takes whole seconds; rounding up keeps a sub-second remainder from shortening the budget.
    private static long budgetSeconds(EffectiveConfig.CodexReviewConfig codexReview) {
        return Math.ceilDiv(codexReview.timeout().toMillis(), 1000L);
    }
}
