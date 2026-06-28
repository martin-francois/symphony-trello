---
name: land
description: >
  Land a pull request after a Trello card has been moved to Merging. Use only
  for human-approved work where the workflow explicitly allows landing.
---

# Land

## Goals

- Land only after a human moves the Trello card to `Merging`.
- Refuse to merge while checks, mergeability, review feedback, or auth are
  unclear.
- Use the repository's merge policy instead of assuming one merge mode.
- Do not enable auto-merge unless the repository policy explicitly requires it.
- Update the Trello workpad and move the card to the configured landing
  completion list after a successful landing. The recommended workflow uses
  `Done`.

## Preconditions

- The current Trello list is `Merging` or the workflow explicitly names the
  current list as the landing list.
- `gh` is installed and authenticated.
- The working tree is clean or changes are intentionally committed.
- A PR can be identified for the current branch or Trello card.

## Steps

1. Use `review-sweep` and address all actionable feedback.
   Reply to addressed GitHub review threads, but leave thread resolution to the
   reviewer unless the user explicitly asks you to resolve them. Keep landing
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
   - move the card to the configured landing completion list.

## Blocked Landing

If landing cannot proceed, use `trello-handoff` to move the card to `Blocked`
with a concise explanation. Include the exact class of blocker: auth, merge
conflict, failing checks, outstanding feedback, missing PR, or unclear policy.

## Landing Decision Table

- Move to the configured landing completion list only after the PR merged
  successfully.
- If a human moved the card from the configured review handoff list to the
  landing approval list without adding new feedback, treat that as approval to
  land when the PR is identifiable, checks and mergeability are clean, required
  reviews are satisfied, and policy is clear.
- If precise, unambiguous feedback was added before the landing approval list,
  and the feedback was addressed exactly with current validation and clean
  checks, land to the configured landing completion list.
- If final work in the landing approval list required material fixups, broad
  interpretation, or unverifiable changes, move back to the configured review
  handoff list with the exact reason and ask for renewed approval.
- If actionable feedback, required reviews, mergeability, checks, auth, or
  repository policy remain unresolved, move to the configured blocked
  destination with the exact blocker class and next human action.
- Do not leave the Trello card parked in `Merging` after a failed landing
  attempt.

## Stop Conditions

- The card is in the configured review handoff list rather than the landing
  approval list.
- The PR cannot be identified.
- CI/checks are failing, pending beyond a reasonable wait, or unavailable when
  required.
- Review feedback is unresolved.
- Merge permissions or repository policy are unclear.
