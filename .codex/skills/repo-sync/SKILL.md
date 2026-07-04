---
name: repo-sync
description: >
  Sync a feature branch with the default branch and resolve conflicts. Use when
  the branch is stale, a push is rejected, CI needs the latest main, or merging
  needs a conflict-free branch.
---

# Repo Sync

## Goals

- Keep the feature branch current with `origin/main`.
- Preserve the branch's intent and the user's changes.
- Resolve conflicts by understanding both sides before editing.
- Rerun the relevant checks after conflict resolution.

## Steps

1. Confirm the current branch and working tree with `git status --short --branch`.
2. If there are uncommitted changes, commit them with the `commit` skill or stop
   and ask how to handle them.
3. Enable recorded conflict reuse locally:

   ```bash
   git config rerere.enabled true
   git config rerere.autoupdate true
   ```

4. Fetch and fast-forward the current branch if the remote branch advanced:

   ```bash
   branch="$(git branch --show-current)"
   git fetch origin
   git pull --ff-only origin "$branch"
   ```

5. Merge latest main with conflict context:

   ```bash
   git -c merge.conflictstyle=zdiff3 merge origin/main
   ```

6. If conflicts occur:
   - Use `git status`, `git diff --merge`, and nearby code/tests/docs to
     understand both sides.
   - Resolve the intended behavior first, then edit.
   - Do not choose `ours` or `theirs` for a whole file unless that is clearly
     correct.
   - Run `git diff --check` before committing the merge.
7. Run the relevant checks from `AGENTS.md`.
8. Summarize conflict files, decisions, and validation in the branch notes or
   handoff comment.

## Stop Conditions

- The conflict depends on product intent that is not inferable from the repo,
  issue, or current user instructions.
- The merge would drop user changes or alter an external contract without clear
  confirmation.
