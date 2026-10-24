---
name: trello-workpad
description: >
  Maintain the single Codex workpad comment on the current Trello card. Use
  during implementation, retry, rework, review handoff, blocked handoff, or
  merge handoff.
---

# Trello Workpad

## Goals

- Keep exactly one current-card comment that starts with `## Codex Workpad`.
- Preserve useful context across retries, rework, and later human review.
- Avoid noisy progress comments.
- Keep private host paths and unrelated local details out of Trello, except for the
  task checkout and proposal paths required by a successful branch-only handoff.

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
Symphony ends the workpad with a `Managed by Symphony` footer. Do not type the
footer yourself; when you echo the previous workpad back, Symphony replaces the
copied footer so the comment keeps exactly one.

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
- For filesystem blockers, do not copy absolute host paths or per-card
  workspace locations into the workpad. Refer to them as "the requested path"
  or "the per-card workspace", explain that undeclared host paths are blocked by
  default, and point the operator to the allowed-host-path settings.
- At a successful branch-only handoff, use the workflow's labeled fields with the
  absolute task checkout and proposal paths. Replace resolved current blockers with
  completed work and current checks before the review transition. Preserve useful
  history; leave Symphony-managed sections to the tool.
- If the tool fails because the existing workpad cannot be updated, treat the
  card as blocked instead of creating duplicate progress comments.

## Stop Conditions

For branch-only work with both evidence tools disabled, follow the complete handoff and manual-copy
procedure in `trello-handoff`. Disabled evidence tools do not block the review transition.
The final-response handoff satisfies the general workpad prerequisite for human review. End the run
after the permitted review move. A person must copy the handoff onto the card before merge approval.
After a verified branch-only merge, disabled evidence tools also permit final-response completion
evidence and the configured completion move. Failed writes from enabled tools remain blockers.

- `trello_upsert_workpad` is unavailable or disabled and the workflow requires a
  durable workpad for safe handoff without the branch-only fallback above.
- The only way to explain progress would expose secrets or unrelated internals.
