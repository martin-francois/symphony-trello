---
name: review-sweep
description: >
  Gather and resolve pull request feedback before Trello handoff or merging.
  Use when a Trello card links to a PR, a branch has a PR, or work is being
  moved to Human Review or Merging.
---

# Review Sweep

## Goals

- Find the PR related to the current Trello card or branch.
- Gather top-level comments, inline review comments, review states, and checks.
- Gather GitHub review threads, including whether each thread is resolved.
- Treat actionable human, bot, and Codex review feedback as blocking until
  handled.
- Reply to addressed GitHub review threads, but leave thread resolution to the
  reviewer unless the user explicitly asks you to resolve them.
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

Also fetch review threads through GraphQL when the repository supports them.
Paginate the review-thread connection so large PRs do not silently omit
feedback:

```bash
owner="$(gh repo view --json owner --jq .owner.login)"
repo="$(gh repo view --json name --jq .name)"
gh api graphql --paginate \
  -f owner="$owner" \
  -f repo="$repo" \
  -F number="$pr_number" \
  -f query='
    query($owner: String!, $repo: String!, $number: Int!, $endCursor: String) {
      repository(owner: $owner, name: $repo) {
        pullRequest(number: $number) {
          reviewThreads(first: 100, after: $endCursor) {
            pageInfo { hasNextPage endCursor }
            nodes {
              id
              isResolved
              isOutdated
              path
              line
              comments(first: 100) {
                nodes {
                  id
                  body
                  author { login }
                  createdAt
                }
              }
            }
          }
        }
      }
    }'
```

If a returned thread has 100 comments, fetch that thread's comments with a
separate paginated query before classifying the thread. Do not treat a possible
incomplete comments page as a complete feedback sweep:

```bash
gh api graphql --paginate \
  -f thread_id="$thread_id" \
  -f query='
    query($thread_id: ID!, $endCursor: String) {
      node(id: $thread_id) {
        ... on PullRequestReviewThread {
          comments(first: 100, after: $endCursor) {
            pageInfo { hasNextPage endCursor }
            nodes {
              id
              body
              author { login }
              createdAt
            }
          }
        }
      }
    }'
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

Use inline replies for inline review comments. Use the PR issue thread for
top-level comments and Codex review issue comments.

When an inline review thread is addressed, reply in that thread with the change
or rationale and leave the thread unresolved for the reviewer. Resolve review
threads only when the user explicitly asks you to do so. If resolution was not
requested, do not claim the thread was resolved.

When a reply reports a code, test, documentation, configuration, or PR-metadata
change, the reply must name the short commit hash containing the change and
include fenced code blocks showing the final relevant code, text, or
configuration. Include enough surrounding context to review the result without
hunting through the diff. A prose summary, file link, or commit link does not
replace the final snippet. Use multiple focused blocks when one comment caused
changes in multiple relevant places. When no file content changed, say that
explicitly and give the rationale or validation evidence instead of inventing a
snippet.

If the PR title, body, branch, labels, or linked card references no longer
match the work actually completed, update the PR metadata before handoff.

## Checks

- If a failing check is related to the card's changes, the current branch, or
  can be reproduced by equivalent local validation, keep the card active, fix
  the failure, push again, and repeat the sweep.
- If a related CI check fails, rerun that check or the closest local equivalent.
  If it fails locally, fix it before handoff. If it passes locally and the
  failure looks flaky after a reasonable refresh or rerun, hand off with the
  local evidence and flaky-check caveat.
- If checks are pending or stale, wait, refresh, or rerun them while the card
  remains active unless a true external blocker appears.
- Wait for GitHub Actions and other required CI checks, but do not block only on
  a pending CodeRabbit status context. Treat CodeRabbit as asynchronous review
  feedback: act on posted comments or requested changes, and mention if it is
  still pending after actual CI is green.
- If CI cannot run because of external quota or infrastructure limits, run
  equivalent local CI checks. Hand off only when those checks pass or only have
  failures clearly unrelated to the card.
- If CI fails for a reason clearly unrelated to the current card, do not spend
  time reproducing that unrelated failure locally. Record why it is unrelated
  and hand off when the card-specific validation and related checks are clean.
- Record the check state, local commands used when local validation was
  required, and any unavailable, flaky, or unrelated check caveat in the workpad
  and handoff comment.
- After feedback-driven changes, rerun local validation and refresh PR checks.
- Repeat the sweep until no actionable comments remain and local-reproducible
  check failures are fixed, or the card reaches a true blocker.

## Trello Handoff

Before moving a card to `Human Review` or merging from `Merging`, update the
workpad with:

- PR URL, if one exists.
- Feedback classes found.
- What changed, which review threads were answered, and which ones remain for
  reviewer resolution.
- Check status and validation evidence.
- Remaining blockers, if any.

## Stop Conditions

- The associated PR cannot be identified but the card clearly requires one.
- GitHub auth is missing for a required PR sweep.
- There are unresolved correctness comments.
- Checks fail in a way that is related to the card's changes or current branch
  and cannot be fixed in-session.
