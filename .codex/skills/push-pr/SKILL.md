---
name: push-pr
description: >
  Push a branch and create or update the matching GitHub pull request. Use when
  publishing work, updating a PR, or preparing a branch for human review.
---

# Push PR

## Goals

- Push the current branch without changing remotes behind the user's back.
- Create a ready-for-review, non-draft PR when none exists, or update the
  existing PR.
- Use a draft PR only when the Trello card explicitly asks for a draft PR.
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
   authenticated GitHub login:

   ```bash
   if ! github_name="$(gh api user --jq '.login // ""')" || [ -z "$github_name" ]; then
     echo "GitHub identity lookup failed: cannot verify PR commit author login" >&2
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
   symphony_trello_author_rewrite=false
   wrong_authors="$(
     git log --format='%H%x09%an <%ae>' "$merge_base"..HEAD |
       while IFS="$(printf '\t')" read -r commit author; do
         if [ "$author" != "$github_author" ]; then
           printf '%s %s\n' "$commit" "$author"
         fi
       done
   )"
   if [ -n "$wrong_authors" ]; then
     current_branch="$(git branch --show-current)"
     if [ -z "$current_branch" ] || [ "$current_branch" = "$default_branch" ]; then
       printf 'Git author verification failed: expected PR commits authored as %s\n%s\n' "$github_author" "$wrong_authors" >&2
       echo "Refusing to rewrite author metadata on the default branch or an unnamed branch" >&2
       exit 1
     fi
     printf 'Git author verification will rewrite PR branch commits to %s\n%s\n' "$github_author" "$wrong_authors" >&2
     export GIT_AUTHOR_NAME="$github_name"
     export GIT_AUTHOR_EMAIL="$github_email"
     export GIT_COMMITTER_NAME="$github_name"
     export GIT_COMMITTER_EMAIL="$github_email"
     if ! git rebase --exec 'git commit --amend --no-edit --reset-author' "$merge_base"; then
       echo "Git author verification failed: could not rewrite PR commit authors" >&2
       exit 1
     fi
     unset GIT_AUTHOR_NAME GIT_AUTHOR_EMAIL GIT_COMMITTER_NAME GIT_COMMITTER_EMAIL
     wrong_authors="$(
       git log --format='%H%x09%an <%ae>' "$merge_base"..HEAD |
         while IFS="$(printf '\t')" read -r commit author; do
           if [ "$author" != "$github_author" ]; then
             printf '%s %s\n' "$commit" "$author"
           fi
         done
     )"
     if [ -n "$wrong_authors" ]; then
       printf 'Git author verification failed after rewrite: expected PR commits authored as %s\n%s\n' "$github_author" "$wrong_authors" >&2
       exit 1
     fi
     symphony_trello_author_rewrite=true
   fi
   ```

   This generated workflow explicitly allows a narrow author-only rewrite of the
   current non-default PR branch when the PR range contains wrong-author commits,
   because a card must not reach Human Review with a PR authored as a generic
   Codex identity. Do not rewrite the default branch, an unnamed branch, or a
   branch that contains unrelated human-owned work; stop and surface the exact
   mismatch instead.
4. Push normally:

   ```bash
   if [ "${symphony_trello_author_rewrite:-false}" = true ]; then
     git push --force-with-lease -u origin HEAD
   else
     git push -u origin HEAD
   fi
   ```

5. If push is rejected because the branch is stale, use `repo-sync`, rerun
   checks, then push again. Use `--force-with-lease` only after a deliberate
   history rewrite.
6. If push fails because of auth, permissions, or branch protection, surface the
   exact failure. Do not rewrite remotes or switch protocols as a workaround.
7. Create or update the PR:

   ```bash
   gh pr view --json number,state,title,url,isDraft
   ```

   - If there is no open PR, create a ready-for-review PR. Do not pass
     `--draft` unless the Trello card explicitly asks for a draft PR.
   - If the branch is tied to a closed or merged PR, create a new branch or ask
     for the intended publishing path.
   - If an existing PR is draft and the card did not ask for draft, mark it
     ready for review before handoff, for example with `gh pr ready`.
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
  because the branch is the default branch, unnamed, or contains unrelated
  human-owned work.
