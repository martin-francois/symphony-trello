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
3. Before pushing, verify that commits intended for the PR are authored as the
   authenticated GitHub user:

   ```bash
   if ! github_name="$(gh api user --jq 'if (.name // "") == "" then .login else .name end')" || [ -z "$github_name" ]; then
     echo "GitHub identity lookup failed: cannot verify PR commit author name" >&2
     exit 1
   fi
   if ! github_email="$(gh api user --jq '.email // ""')"; then
     echo "GitHub identity lookup failed: cannot verify PR commit author email metadata" >&2
     exit 1
   fi
   if [ -z "$github_email" ]; then
     if ! github_email="$(gh api user/emails --jq '[.[] | select(.email | endswith("@users.noreply.github.com")) | .email][0] // ""' 2>/dev/null)"; then
       echo "GitHub identity lookup failed: gh api user/emails needs the user:email scope; run gh auth refresh -s user:email for this account" >&2
       exit 1
     fi
   fi
   if [ -z "$github_email" ]; then
     echo "GitHub identity lookup failed: configure a public email or accessible GitHub noreply email for this account" >&2
     exit 1
   fi
   github_author="$github_name <$github_email>"
   default_branch="$(git remote show origin | sed -n 's/  HEAD branch: //p')"
   if [ -z "$default_branch" ]; then
     default_branch="$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's#^origin/##' || true)"
   fi
   if [ -z "$default_branch" ]; then
     echo "Git author verification failed: could not resolve origin default branch" >&2
     exit 1
   fi
   if ! git fetch origin "$default_branch"; then
     echo "Git author verification failed: could not fetch origin/$default_branch" >&2
     exit 1
   fi
   base_ref="origin/$default_branch"
   if ! git rev-parse --verify --quiet "$base_ref" >/dev/null; then
     echo "Git author verification failed: missing $base_ref" >&2
     exit 1
   fi
   if ! merge_base="$(git merge-base HEAD "$base_ref")"; then
     echo "Git author verification failed: could not find a merge base with $base_ref" >&2
     exit 1
   fi
   git log --format='%H %an <%ae>' "$merge_base"..HEAD
   ```

   Compare the listed authors with `$github_author`. If an unpublished commit
   uses the wrong author, amend it before pushing. If the branch was already
   pushed, do not rewrite it unless the workflow or human explicitly says a
   force-push is safe. Surface any mismatch in the workpad or handoff comment.
4. Push normally:

   ```bash
   git push -u origin HEAD
   ```

5. If push is rejected because the branch is stale, use `repo-sync`, rerun
   checks, then push again. Use `--force-with-lease` only after a deliberate
   history rewrite.
6. If push fails because of auth, permissions, or branch protection, surface the
   exact failure. Do not rewrite remotes or switch protocols as a workaround.
7. Create or update the PR:

   ```bash
   gh pr view --json number,state,title,url
   ```

   - If there is no open PR, create one.
   - If the branch is tied to a closed or merged PR, create a new branch or ask
     for the intended publishing path.
   - Reconsider the title and body on every update.
8. Use `.github/pull_request_template.md` when present. Fill every section with
   concrete content and remove placeholders.
9. Include validation evidence and any known limitations.
10. Return the PR URL.

## Stop Conditions

- The working tree has uncommitted unrelated changes.
- Required checks have not run and there is no clear reason to publish anyway.
- PR metadata would be misleading or incomplete.
- The branch contains wrong-author commits and rewriting them would be unsafe
  without human approval.
