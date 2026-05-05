---
name: review-sweep
description: >
  Gather and resolve pull request feedback before Trello handoff or landing.
  Use when a Trello card links to a PR, a branch has a PR, or work is being
  moved to Human Review or Merging.
---

# Review Sweep

## Goals

- Find the PR related to the current Trello card or branch.
- Gather top-level comments, inline review comments, review states, and checks.
- Treat actionable human, bot, and Codex review feedback as blocking until
  handled.
- Reply in the right place when accepting, clarifying, or pushing back.
- Keep PR title, body, branch, and metadata aligned with the current Trello
  scope when an existing PR is reused.
- Record the outcome in the Trello workpad before handoff.

## Finding The PR

Try these sources in order:

1. PR URLs in the Trello card description.
2. PR URLs in recent Trello comments.
3. Branch names in the Trello card description or comments.
4. The current branch:

   ```bash
   git branch --show-current
   gh pr view --json number,url,title,state,headRefName,baseRefName
   ```

5. Open PRs whose branch, title, body, or labels match card context:

   ```bash
   gh pr list --state open --json number,url,title,body,headRefName,labels
   ```

If no PR exists, skip GitHub review checks and continue with local validation.

## Feedback Sweep

For a PR number:

```bash
pr_number="<number>"
gh pr view "$pr_number" --json number,title,state,mergeable,reviewDecision,statusCheckRollup
gh api "repos/{owner}/{repo}/issues/$pr_number/comments"
gh api "repos/{owner}/{repo}/pulls/$pr_number/comments"
gh api "repos/{owner}/{repo}/pulls/$pr_number/reviews"
gh pr checks "$pr_number"
```

Treat Codex review issue comments as part of the PR feedback set when the
repository uses GitHub issue comments for Codex review output.

Classify each actionable item as one of:

- correctness
- design
- style
- clarification
- scope

For each item, choose one outcome:

- Address it with code, tests, docs, or PR metadata.
- Ask a targeted clarification in the same thread.
- Push back with rationale and concrete validation when correctness is involved.
- Defer it only when it is out of scope, already tracked elsewhere, or conflicts
  with the current Trello card.

When replying as Codex, prefix the reply with `[codex]` if the repository expects
agent-authored GitHub comments to be marked.

Use inline replies for inline review comments. Use the PR issue thread for
top-level comments and Codex review issue comments.

If the PR title, body, branch, labels, or linked card references no longer
match the work actually completed, update the PR metadata before handoff.

## Checks

- Failing or stale required checks mean the PR is not ready for handoff.
- After feedback-driven changes, rerun local validation and refresh PR checks.
- Repeat the sweep until no actionable comments remain or the card is blocked.

## Trello Handoff

Before moving a card to `Human Review` or landing from `Merging`, update the
workpad with:

- PR URL, if one exists.
- Feedback classes found.
- What changed or why feedback was declined.
- Check status and validation evidence.
- Remaining blockers, if any.

## Stop Conditions

- The associated PR cannot be identified but the card clearly requires one.
- GitHub auth is missing for a required PR sweep.
- There are unresolved correctness comments or failing checks.
