---
name: push-pr
description: >
  Push a branch and create or update the matching GitHub pull request. Use when
  publishing work, updating a PR, or preparing a branch for human review.
---

# Push PR

## Goals

- Push the current branch without changing remotes behind the user's back.
- Create a PR when none exists, or update the existing PR.
- Keep PR title and body aligned with the full branch scope.
- Distinguish stale-branch problems from auth or permission problems.

## Preconditions

- `gh` is installed and authenticated for this repository.
- Local verification required by `AGENTS.md` has passed or the blocker is known.

## Steps

1. Inspect the branch and diff:

   ```bash
   branch="$(git branch --show-current)"
   git status --short --branch
   git log --oneline --decorate -5
   ```

2. Run the required local checks for the change.
3. Push normally:

   ```bash
   git push -u origin HEAD
   ```

4. If push is rejected because the branch is stale, use `repo-sync`, rerun
   checks, then push again. Use `--force-with-lease` only after a deliberate
   history rewrite.
5. If push fails because of auth, permissions, or branch protection, surface the
   exact failure. Do not rewrite remotes or switch protocols as a workaround.
6. Create or update the PR:

   ```bash
   gh pr view --json number,state,title,url
   ```

   - If there is no open PR, create one.
   - If the branch is tied to a closed or merged PR, create a new branch or ask
     for the intended publishing path.
   - Reconsider the title and body on every update.
7. Use `.github/pull_request_template.md` when present. Fill every section with
   concrete content and remove placeholders.
8. Include validation evidence and any known limitations.
9. Return the PR URL.

## Stop Conditions

- The working tree has uncommitted unrelated changes.
- Required checks have not run and there is no clear reason to publish anyway.
- PR metadata would be misleading or incomplete.
