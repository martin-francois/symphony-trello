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
   - actionable comments are addressed or explicitly resolved,
   - local validation is current.
8. After merging:
   - update the workpad with merge evidence,
   - add a short Trello comment if useful,
   - move the card to the configured landing completion list.

## Blocked Landing

If landing cannot proceed, use `trello-handoff` to move the card to `Blocked`
with a concise explanation. Include the exact class of blocker: auth, merge
conflict, failing checks, outstanding feedback, missing PR, or unclear policy.

## Stop Conditions

- The card is in `Human Review` rather than `Merging`.
- The PR cannot be identified.
- CI/checks are failing, pending beyond a reasonable wait, or unavailable when
  required.
- Review feedback is unresolved.
- Merge permissions or repository policy are unclear.
