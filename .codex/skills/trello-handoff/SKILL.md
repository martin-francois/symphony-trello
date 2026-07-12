---
name: trello-handoff
description: >
  Move the current Trello card through the configured Symphony-for-Trello
  lifecycle. Use when picking up work, handing off for human review, marking a
  blocker, or completing a merge.
---

# Trello Handoff

## Goals

- Move only the current card through configured board lists.
- Leave clear Trello comments for humans.
- Keep active lists free of blocked or completed work.
- Use the workpad for detailed state and a short comment for the visible
  handoff.

## Current-Card Tools

Use these scoped tools when they are advertised:

- `trello_move_current_card`
- `trello_add_comment`
- `trello_upsert_workpad`
- `trello_update_blocker_recheck_status`

The move tool uses Trello's term `list_name` for a board list name.

## Pickup

When a card starts in `Ready for Codex` and an `In Progress` list is
configured, Symphony normally moves it to `In Progress` before Codex starts.
If Codex still needs to request the pickup move, apply the stale-blocker check
below first and publish any required `checking` status before that tool call:

```json
{
  "tool": "trello_move_current_card",
  "arguments": {
    "list_name": "In Progress"
  }
}
```

If no in-progress list is configured, leave the card in its active list and
continue work.

## Stale Blocker Recheck

This section applies only when `trello_update_blocker_recheck_status` is
advertised. When it is unavailable, do not attempt a managed recheck write;
follow the workflow's tool-disabled final-response or manual handoff path.

The rendered prompt contains only recent Trello comments, so do not use it to
decide whether a stale blocker exists. When the tool is advertised, always call
it with status `checking` before changing code. The tool inspects the deep
current-card comment window and classifies the newest ordinary comment after
ignoring the `## Codex Workpad` and Symphony-managed prerequisite comments. A
Symphony-managed recheck status ends with the exact
`Managed by Symphony` footer and a link to the qualifying blocker comment on
the current card. Similar visible text, or a link to another card, remains an
ordinary comment. Do not scan past a newer ordinary human comment to find an
older blocker.

The newest ordinary comment qualifies only when its first non-blank line starts
with `Blocked:` or `Blocked by ...`, matched without case sensitivity. A human
discussion that merely contains the word `blocked` does not qualify.

The newest ordinary `Blocked:` or `Blocked by ...` comment is the comment being
rechecked; leave it unchanged. Call `checking` to create or update a separate
Symphony-managed status comment.

Make the initial `checking` call before any Codex-requested pickup move or
workpad write. When a blocker qualifies, this call is the first Codex-requested
Trello write. When the tool returns `blocker_recheck_not_needed`, continue
without creating a managed status. Only after the call succeeds may you request
a move or update the workpad. Symphony's automatic pre-dispatch move may
already have happened before Codex starts.

If the initial `checking` call returns a tool failure, including
`trello_blocker_recheck_refresh_failed` or
`trello_blocker_recheck_card_missing`, stop the current attempt. Do not test the
blocker, call `resumed`, or request another Trello write. Report the failure in
the final response; the next dispatched retry must begin with `checking` again.
Do not use the ordinary blocked handoff after this failure because its comment,
workpad, or move would be an unsafe later Trello write.

Always make the initial `checking` call before testing whether a blocker still
applies or making another Trello write. The absence of an
existing managed recheck comment is not a reason to skip this call; `checking`
creates the managed comment. Call the tool with status `checking`:

```json
{
  "tool": "trello_update_blocker_recheck_status",
  "arguments": {
    "status": "checking"
  }
}
```

Only after the recheck succeeds and confirms that exact blocker no longer
applies, update the same managed comment with status `resumed`:

```json
{
  "tool": "trello_update_blocker_recheck_status",
  "arguments": {
    "status": "resumed"
  }
}
```

If the `resumed` call returns any tool failure, including
`trello_blocker_recheck_stale`, `trello_blocker_recheck_not_started`,
`trello_blocker_recheck_refresh_failed`, or
`trello_blocker_recheck_card_missing`, stop the current attempt. Do not claim
that work resumed, use the ordinary blocked handoff, or request another Trello
write. Report the failure in the final response; the next dispatched retry
must begin with `checking` again. A stale result means the newly qualifying
blocker must enter its own `checking` episode before it can resume.

If the recheck fails or the card is still blocked, do not call `resumed`.
Update the workpad and follow the blocked handoff. A failed not-yet-resumed
episode must stay in `checking` and must not claim that work resumed. Repeated
pickup or retry calls reuse the same managed status comment. An already-resumed
retry for the same blocker comment retains its last-confirmed resumed state. A
new qualifying blocker comment starts a new action-bound recheck episode and
must enter `checking` before it can resume.

## Human Review

Before moving to `Human Review`:

1. For repository-changing work, create or update the PR unless the card
   explicitly asks for local-only/no-push work.
2. Update the workpad with final summary, acceptance criteria status,
   validation evidence, PR URL when applicable, and known limitations.
   Format PR links in Trello-visible text on their own line as
   `PR: <https://github.com/owner/repo/pull/123>` so punctuation cannot become
   part of the link.
3. Add one concise visible handoff comment.
4. Move the card to `Human Review`.

Do not merge from `Human Review`; that list is for a person.

## Rework

When a human moves a card from `Human Review` back to an active list such as
`Ready for Codex`, treat it as rework rather than a new task.

Before changing code:

1. Reread the full card description and recent Trello comments.
2. Read the existing `## Codex Workpad` comment.
3. Run `review-sweep` when a PR or branch exists.
4. Identify what changed since the last handoff.
5. Update the workpad with a short rework plan.

Preserve completed work that still satisfies the current card. Do not close the
existing PR, delete the workpad, create a new branch, or restart from scratch
unless the Trello card or a human explicitly asks for a reset.

Before moving back to `Human Review`, rerun card-specific validation and the PR
feedback sweep, update the existing workpad with evidence, and add one concise
handoff comment. Do not create duplicate progress summary comments when the
workpad already contains the details.

## Blocked

Move to `Blocked` when the card cannot proceed safely because of missing auth,
inaccessible files, unclear requirements, or unsafe merge conditions.

Do not move to `Blocked` only because PR checks are pending or stale. Keep the
card active, wait, refresh, or rerun the checks unless a true external blocker
appears.

Do not move to `Blocked` only because CI is unavailable due to external quota or
infrastructure limits. Run equivalent local CI checks and move to `Human Review`
when those checks pass or only have failures clearly unrelated to the card.

Do not move to `Blocked` only because CI fails for a reason clearly unrelated to
the current card. Do not spend time reproducing that unrelated failure locally.
Move to `Human Review` when the card-specific validation and related checks are
clean, with the PR link and a concise caveat explaining why the CI failure is
unrelated.

If CI fails because of the card's changes or current branch, keep the card
active. Rerun the failing check or closest local equivalent first. If it fails
locally, fix it before handoff. If it passes locally and the failure looks flaky
after a reasonable refresh or rerun, move to `Human Review` with the local
evidence and flaky-check caveat. Use `Blocked` only when the failure cannot be
fixed in-session and the exact blocker is clear.

If no `Blocked` list exists but the workflow configured a review list as
the blocked destination, move there so the card leaves the active queue. In the
comment, say that the card is blocked.

For filesystem blockers, keep Trello-visible text path-safe. Do not copy
absolute host paths or per-card workspace locations into Trello comments or the
workpad. Refer to them as "the requested path" or "the per-card workspace",
explain that undeclared host paths are blocked by default, and point the
operator to the allowed-host-path settings.

## Merging And Done

Use `Merging` only when a human has approved the work and the workflow enables a
merge flow. After successful merge, update the workpad, add a concise
completion comment when useful, and move to `Done`.

## Stop Conditions

- The destination list is not in the configured move allowlist.
- The handoff comment would expose secrets or unrelated local details.
- Required validation or review sweep is incomplete for a reason that Codex can
  still address in-session.
