---
name: commit
description: >
  Create a focused commit from the current staged changes. Use when asked to
  commit, prepare a commit, or finalize a Symphony-for-Trello issue branch.
---

# Commit

## Goals

- Commit only the intended changes.
- Match the target repository's commit-message convention.
- For branches that may become GitHub pull requests, make the commit author
  match the authenticated GitHub user before creating commits.
- Preserve unrelated user changes.
- Include validation evidence in the commit body when it helps review.
- Reference the implemented GitHub issue in a footer when the work belongs to an
  issue.

## Steps

1. Inspect `git status --short --branch`, `git diff`, and `git diff --staged`.
2. Stage only files that belong to the current task.
3. Re-read newly added files before committing. Do not commit generated output,
   secrets, local run artifacts, private board names, card ids, account names,
   or host paths.
4. If this branch may be pushed to GitHub or published as a pull request, resolve
   the GitHub identity before committing:

   ```bash
   if ! github_name="$(gh api user --jq 'if (.name // "") == "" then .login else .name end')" || [ -z "$github_name" ]; then
     echo "GitHub identity lookup failed: could not resolve user name" >&2
     exit 1
   fi
   if ! github_email="$(gh api user --jq '.email // ""')"; then
     echo "GitHub identity lookup failed: could not resolve user email metadata" >&2
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
   git config user.name "$github_name"
   git config user.email "$github_email"
   ```

   Use the same `gh` authentication context that will create or update the PR.
   If this lookup fails for PR-bound work, stop, update the Trello workpad or
   handoff comment with the exact blocker, and do not create a commit with a
   generic fallback author. If the card explicitly says the work is local-only
   or must not be pushed, keep the existing local Git identity and mention that
   no PR author identity was needed.
5. Choose the commit message style:

   - First, read the target repository's `CONTRIBUTING.md` or equivalent
     contributor guide when one exists, and follow any commit-message
     convention it defines.
   - If no contributor guide defines a convention, inspect the last 20 to 50
     commit subjects on the default branch and infer the established style.
   - If the repository has no commits, no reachable default-branch history, or
     only one commit without a documented convention, treat the history as too
     small to infer from and default to Conventional Commits.
   - If the guide is silent and recent history is mixed or unclear, default to
     Conventional Commits with a concise imperative subject.
   - Do not force Conventional Commits on a repository that clearly uses a
     different convention.

6. Write a body when the commit is not self-explanatory. Include:
   - Summary of the behavior or docs changed.
   - Important rationale or tradeoffs.
   - Tests or validation run.
7. If implementing a GitHub issue, include a footer:

   ```text
   Refs: https://github.com/martinfrancois/symphony-trello/issues/<number>
   ```

8. Commit with `git commit -F <message-file>` so the message is exactly what was
   reviewed.

After committing, inspect `git log -1 --format='%an <%ae>'`. If the author is
wrong and the commit has not been pushed, amend it before publishing. If the
wrong-author commit has already been pushed, do not rewrite history unless the
workflow or human explicitly says a force-push is safe.

## Checks

Follow `AGENTS.md` for the verification expected before finalizing a branch.
For normal code changes, run:

```bash
./mvnw -q spotless:check verify
```

## Stop Conditions

- The staged diff includes unrelated user changes.
- The commit would include secrets or local/private operational details.
- The requested commit message would misrepresent the staged changes.
- PR-bound work cannot resolve the authenticated GitHub identity before the
  first commit.
