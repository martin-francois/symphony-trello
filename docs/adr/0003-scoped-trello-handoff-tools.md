# 0003 Scoped Trello Handoff Tools

## Status

Accepted

## Context

The Symphony spec keeps the orchestrator as the single authority for scheduling state and describes it
as a Trello reader. That boundary is easy to misread as a fully read-only workflow where operators
must watch logs and manually move cards after each agent run.

That manual loop is inconvenient and loses useful context. The coding agent is the component that
knows whether the requested work is reviewable, blocked, or unsafe to hand off, so the handoff signal
should be produced from the workflow prompt when writes are explicitly enabled.

## Decision

The Java implementation exposes high-level Codex app-server dynamic tools instead of a generic
Trello REST bridge for the initial write-capable handoff:

- `trello_add_comment`
- `trello_move_current_card`

The tools are advertised only when `trello_tools.enabled=true` and `trello_tools.allow_writes=true`.
Comment writes also require `trello_tools.allow_comments=true`. Card moves require
`trello_tools.allowed_move_list_ids` or `trello_tools.allowed_move_list_names`, and the destination
must resolve to an open list on the configured board.

Both tools are scoped to the current worker session's card. The agent cannot pass an arbitrary card
id to these tools.

## Consequences

The orchestrator remains a scheduler and Trello reader; it does not decide when a card is ready for
review. The workflow prompt owns that policy and asks the agent to call the handoff tools.

The initial implementation covers the common comment-and-move handoff. Checklist and URL attachment
helpers can be added later as additional typed tools without widening the generic API surface.

Generic `trello_rest` remains a documented optional extension, but this implementation intentionally
starts with the narrower typed tools because they are easier to reason about, test, and operate.
