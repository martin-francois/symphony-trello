# Private-context redaction

## Scope

How to keep private project internals out of committed files, GitHub issues, and user-facing
summaries, and how to use the local diagnostics tooling for private investigation.

## Redaction rules

- Redact private project names, host paths, Trello card ids, short links, account names, and similar
  internals from committed files, GitHub issues, and user-facing summaries unless the user explicitly
  asks to preserve them. Keep only the minimum technical detail needed to reproduce or understand the
  issue. Do not copy live Trello board ids, short links, card ids, board URLs, or account names from
  diagnostics, live runs, GitHub issues, PR comments, or logs into tests, fixtures, docs, commit
  messages, or new issue text. When a Trello-shaped value is needed, replace it with a clearly
  synthetic same-shape value such as `SYNTH001` for an 8-character short link or
  `000000000000000000000001` for a 24-character board id, and use synthetic URLs such as
  `https://trello.com/b/SYNTH001/synthetic-board`. GitHub Secret Scanning is the hosted safety net
  for repository history and hosted GitHub text that matches GitHub-supported patterns. Do not rely
  on GitHub custom patterns for Symphony-specific private context unless the GitHub plan explicitly
  supports them and the docs are updated. Use `scripts/check-private-context` before committing files
  or manually posting copied diagnostic text from live reproduction work, logs, or local setup
  output. The scanner delegates to BetterLeaks plus repository-specific private-context rules. For
  manual GitHub posts where the exact text is available, scan it first with
  `scripts/check-private-context --stdin --label <safe-label>`. When the scanner reports
  Trello-shaped test data, make the fixture clearly synthetic rather than suppressing the finding.

## Diagnostics tooling

- When investigating local setup, diagnostics, worker, or board-routing failures on a machine where
  the installed command is available, use `symphony-trello diagnostics` for the public-safe overview.
  If the failure depends on information the default report intentionally omits, run
  `symphony-trello diagnostics --deep` to add deeper public-safe checks such as Codex and GitHub
  auth-status probes. If the sanitized report uses `board_hash`, `key_hash`, or `<path:...>` tokens
  and you need to map them back to the real local board, workflow, env file, workspace, state
  directory, or log file, run `symphony-trello diagnostics --show-private-context` locally,
  optionally with `--board` or `--workflow` to narrow the scope. Treat that output as private
  investigation data: do not paste it into GitHub issues, Trello comments, PR descriptions, committed
  files, or final user summaries. Use it to decide what to inspect or fix, then report only sanitized
  conclusions unless the user explicitly asks for local private values.

## References

- [Deployment & live verification](deployment-and-live-verification.md)
- [Default workflow](default-workflow.md)
