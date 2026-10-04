---
name: land
description: >
  Merge an approved pull request after a Trello card has been moved to Merging. Use only
  for human-approved work where the workflow explicitly allows merging from Merging.
---

# Land

## Goals

- Merge only after a human moves the Trello card to `Merging`.
- Refuse to merge while checks, mergeability, review feedback, or auth are
  unclear.
- Use the repository's merge policy instead of assuming one merge mode.
- Do not enable auto-merge unless the repository policy explicitly requires it.
- Update the Trello workpad and move the card to the configured merge
  completion list after a successful merge. The recommended workflow uses
  `Done`.

## Preconditions

- The current Trello list is `Merging` or the workflow explicitly names the
  current list as the merge approval list.
- `gh` is installed and authenticated.
- The working tree is clean or changes are intentionally committed.
- A PR can be identified for the current branch or Trello card, or the
  workflow selects a branch-only merge as described below.

## Steps

1. Use `review-sweep` and address all actionable feedback.
   Reply to addressed GitHub review threads, but leave thread resolution to the
   reviewer unless the user explicitly asks you to resolve them. Keep merging
   eligible only when the feedback is otherwise addressed and the workpad records
   any thread still awaiting reviewer resolution.
2. Run local validation required by `AGENTS.md` and the Trello card.
3. Check PR mergeability and branch state:

   ```bash
   gh pr view --json number,url,title,body,state,mergeable,reviewDecision,statusCheckRollup
   gh pr checks
   ```

4. If the branch is stale or conflicts with `origin/main`, use `repo-sync`, then
   `push-pr`, then rerun validation and review sweep.
5. If checks fail, inspect logs, fix, commit, push, and repeat.
6. If merge policy is documented, follow it. If it is not documented, inspect
   repository settings and PR history before choosing. When still unclear,
   block rather than guessing.
   Do not assume squash, merge, rebase, or auto-merge unless repository policy
   or recent accepted PRs make that choice clear.
7. Merge only when:
   - required checks are green,
   - mergeability is clean,
   - required reviews are satisfied,
   - actionable comments and review threads are addressed and replied to,
   - local validation is current.
8. After merging:
   - update the workpad with merge evidence,
   - add a short Trello comment if useful,
   - move the card to the configured merge completion list.

## Branch-Only Merge

Use this section instead of the PR steps when the workflow's Pull Request
Handoff Mode section selects `branch_only` and no open PR exists for the card
branch. A PR that a human opened from the proposed description takes the
normal PR steps above.

1. Pick the target branch: the branch the Trello card names, otherwise the
   repository default branch.
2. Fetch the target branch. Skip the merge when the card's commits are
   already on it: the work was committed directly on the target branch, or
   the card branch tip is an ancestor of the target branch. A card branch
   that no longer exists on origin is not a blocker when the head commit
   named in the earlier branch-only handoff is on the target branch:

   ```bash
   git fetch origin "$target_branch"
   if git ls-remote --exit-code --heads origin "$card_branch" >/dev/null; then
     git fetch origin "$card_branch"
     git merge-base --is-ancestor "origin/$card_branch" "origin/$target_branch"
   else
     git merge-base --is-ancestor "$card_commit" "origin/$target_branch"
   fi
   ```

3. Otherwise merge the card branch into the target branch in a separate
   worktree, following any merge strategy the repository documents. When
   none is documented, fast-forward when possible and create a merge commit
   otherwise. Never commit the proposed description file.
4. Run local validation on the merge result, then push the target branch
   normally. Never force-push it.
5. Do not resolve GitHub review threads; there is no PR.
6. Say in the completion comment which branch was merged into which target,
   or why no merge was needed, then move the card to the configured merge
   completion list.
7. If the merge conflicts, validation fails, or the push is rejected by
   branch protection or a required-PR rule, use the blocked merge path below.

## Blocked Merge

If merging cannot proceed, use `trello-handoff` to move the card to `Blocked`
with a concise explanation. Include the exact class of blocker: auth, merge
conflict, failing checks, outstanding feedback, missing PR, or unclear policy.

## Merge Decision Table

- Move to the configured merge completion list only after the PR merged
  successfully.
- If a human moved the card from the configured review handoff list to the
  merge approval list without adding new feedback, treat that as approval to
  merge when the PR is identifiable, checks and mergeability are clean, required
  reviews are satisfied, and policy is clear.
- If precise, unambiguous feedback was added before the merge approval list,
  and the feedback was addressed exactly with current validation and clean
  checks, merge to the configured merge completion list.
- If final work in the merge approval list required material fixups, broad
  interpretation, or unverifiable changes, move back to the configured review
  handoff list with the exact reason and ask for renewed approval.
- If actionable feedback, required reviews, mergeability, checks, auth, or
  repository policy remain unresolved, move to the configured blocked
  destination with the exact blocker class and next human action.
- Do not leave the Trello card parked in `Merging` after a failed merge
  attempt.

## Stop Conditions

- The card is in the configured review handoff list rather than the merge
  approval list.
- The PR cannot be identified.
- CI/checks are failing, pending beyond a reasonable wait, or unavailable when
  required.
- Review feedback is unresolved.
- Merge permissions or repository policy are unclear.
