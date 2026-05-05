---
name: commit
description: >
  Create a focused Conventional Commit from the current staged changes. Use when
  asked to commit, prepare a commit, or finalize a Symphony-for-Trello issue
  branch.
---

# Commit

## Goals

- Commit only the intended changes.
- Use a Conventional Commit subject.
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
4. Choose a Conventional Commit type and concise imperative subject.
5. Write a body when the commit is not self-explanatory. Include:
   - Summary of the behavior or docs changed.
   - Important rationale or tradeoffs.
   - Tests or validation run.
6. If implementing a GitHub issue, include a footer:

   ```text
   Refs: https://github.com/martinfrancois/symphony-trello/issues/<number>
   ```

7. Commit with `git commit -F <message-file>` so the message is exactly what was
   reviewed.

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
