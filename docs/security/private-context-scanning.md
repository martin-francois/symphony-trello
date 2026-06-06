# Private Context Scanning

Symphony for Trello uses `scripts/check-private-context` to catch secrets and private operational
context before they are committed or shared. The scanner reports rule names and locations, but does
not print matched values.

## Local Checks

Use these commands before publishing changes or text copied from diagnostics:

```bash
scripts/check-private-context --worktree
scripts/check-private-context --git-range origin/main..HEAD
scripts/check-private-context --stdin
```

Use clearly synthetic Trello-shaped fixtures in tests and docs, for example
`https://trello.com/b/SYNTH001/synthetic-board` and `000000000000000000000001`.

## GitHub Posting Checks

Use `scripts/gh-safe` for command-line GitHub issue, pull request, review, and comment writes. It
scans title/body/comment text before calling `gh`.

The `GitHub Content Scan` workflow also scans content after it is posted or edited. It covers these
GitHub event fields:

| event | fields |
| --- | --- |
| `issues` | issue title, issue body |
| `issue_comment` | comment body |
| `pull_request` | pull request title, pull request body |
| `pull_request_review` | review body |
| `pull_request_review_comment` | review comment body |

The workflow fails when private context is detected. It does not edit, minimize, label, or comment on
the GitHub content because it runs with read-only permissions and should not expose matched values in
logs or comments.
