---
name: trello-workpad
description: >
  Maintain the single Codex workpad comment on the current Trello card. Use
  during implementation, retry, rework, review handoff, blocked handoff, or
  landing.
---

# Trello Workpad

## Goals

- Keep exactly one current-card comment that starts with `## Codex Workpad`.
- Preserve useful context across retries, rework, and later human review.
- Avoid noisy progress comments.
- Keep private host paths and unrelated local details out of Trello.

## Required Sections

Use concise Markdown. Include sections that are relevant to the card:

- `Plan`
- `Acceptance Criteria`
- `Progress`
- `Validation`
- `PR / Review`
- `Blockers`
- `Handoff`

## How To Update

Use the scoped Trello tool when it is available:

```json
{
  "tool": "trello_upsert_workpad",
  "arguments": {
    "text": "## Codex Workpad\n\n..."
  }
}
```

Symphony scopes the tool to the current card. Do not include a card id.

Update the workpad:

- Before implementation with the plan and acceptance criteria.
- After reproducing a bug or capturing current behavior.
- After meaningful implementation progress.
- After validation.
- Before moving to `Human Review`, `Blocked`, `Merging`, or `Done`.
- When returning to work after review comments or a retry.

## Content Rules

- Record evidence, not only intentions.
- Prefer short bullet points over long status narratives.
- Do not include secrets, API tokens, private board ids, account names, or
  unrelated host paths.
- For filesystem blockers, include the inaccessible path only when it is
  necessary for the operator to fix the issue. Prefer sanitized repository or
  workspace names when enough.
- If the tool fails because the existing workpad cannot be updated, treat the
  card as blocked instead of creating duplicate progress comments.

## Stop Conditions

- `trello_upsert_workpad` is unavailable or disabled and the workflow requires a
  durable workpad for safe handoff.
- The only way to explain progress would expose secrets or unrelated internals.
