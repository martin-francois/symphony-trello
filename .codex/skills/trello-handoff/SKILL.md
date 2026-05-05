---
name: trello-handoff
description: >
  Move the current Trello card through the configured Symphony-for-Trello
  lifecycle. Use when picking up work, handing off for human review, marking a
  blocker, or completing landing.
---

# Trello Handoff

## Goals

- Move only the current card through configured board columns.
- Leave clear Trello comments for humans.
- Keep active columns free of blocked or completed work.
- Use the workpad for detailed state and a short comment for the visible
  handoff.

## Current-Card Tools

Use these scoped tools when they are advertised:

- `trello_move_current_card`
- `trello_add_comment`
- `trello_upsert_workpad`

The move tool uses Trello's API term `list_name` for a board column name.

## Pickup

When a card starts in `Ready for Codex` and an `In Progress` column is
configured, first move it to `In Progress`:

```json
{
  "tool": "trello_move_current_card",
  "arguments": {
    "list_name": "In Progress"
  }
}
```

If no in-progress column is configured, leave the card in its active column and
continue work.

## Human Review

Before moving to `Human Review`:

1. Update the workpad with final summary, acceptance criteria status, validation
   evidence, PR URL, and known limitations.
2. Add one concise visible handoff comment.
3. Move the card to `Human Review`.

Do not attempt landing from `Human Review`; that column is for a person.

## Blocked

Move to `Blocked` when the card cannot proceed safely because of missing auth,
inaccessible files, unclear requirements, failing external systems, or unsafe
merge conditions.

If no `Blocked` column exists but the workflow configured a review column as
the blocked destination, move there so the card leaves the active queue. In the
comment, say that the card is blocked.

## Merging And Done

Use `Merging` only when a human has approved the work and the workflow enables a
landing flow. After successful landing, update the workpad, add a concise
completion comment when useful, and move to `Done`.

## Stop Conditions

- The destination column is not in the configured move allowlist.
- The handoff comment would expose secrets or unrelated local details.
- Required validation, review sweep, or landing checks are incomplete.
