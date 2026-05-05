---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, README.md, OpenAI Symphony Elixir workflow]
informed: [Future maintainers]
---

# Use A Workflow-Configured In-Progress Pickup Column

## Context and Problem Statement

Users looking at Trello should be able to tell when Codex has picked up a card. The implementation
already exposes running work through the status page and API, but a Trello board is easier to scan
when picked-up cards leave the queue column.

How should Symphony for Trello make pickup visible while preserving the specification boundary that
tracker writes are driven by the workflow and agent tools rather than hard-coded orchestrator business
logic?

## Decision Drivers

* Make picked-up Codex work visible from the Trello board.
* Stay aligned with the original Symphony workflow pattern where Codex moves work from a queue state
  to an in-progress state.
* Keep card mutation policy in `WORKFLOW.md` and scoped Trello tools.
* Keep imported existing boards usable when they do not have an in-progress column.
* Preserve retry and restart behavior for cards already moved to the in-progress column.

## Considered Options

* Workflow-configured in-progress pickup column.
* Orchestrator-owned claim move.
* Add only a pickup comment.
* Keep pickup visible only in the status page/API.

## Decision Outcome

Chosen option: "Workflow-configured in-progress pickup column".

The recommended board setup creates `In Progress`. Generated workflows include `Ready for Codex` and
`In Progress` in `tracker.active_states`, include `In Progress` in
`trello_tools.allowed_move_list_names`, and instruct Codex to move cards from `Ready for Codex` to
`In Progress` before implementation work. Existing-board import detects a column named `In Progress`
or accepts `--in-progress`. If no in-progress column is configured, the generated workflow leaves the
card in the active column while Codex works.

### Consequences

* Good, because the Trello board shows when a queued card was picked up.
* Good, because a card already in `In Progress` remains eligible for continuation after retry or
  service restart.
* Good, because existing boards without an in-progress column still work.
* Neutral, because pickup visibility depends on the agent successfully making its first Trello tool
  call.
* Bad, because an agent failure before the first move can still leave the card in the queue column.

### Confirmation

Run `./mvnw -q spotless:check verify`. Review generated workflows from `new-board` and
`import-board`. Live deployment checks should cover both a workflow with an in-progress column and a
workflow without one.

## Pros and Cons of the Options

### Workflow-Configured In-Progress Pickup Column

Add an optional in-progress column to the generated workflow and prompt Codex to move the current card
there before implementation work.

* Good, because it mirrors the original Symphony workflow pattern.
* Good, because the workflow owns the visible board semantics.
* Good, because it reuses scoped Trello move policy.
* Bad, because the pickup move is not guaranteed if the agent fails before tool use.

### Orchestrator-Owned Claim Move

Have the scheduler move the card to an in-progress column immediately after claiming it.

* Good, because pickup visibility would not depend on Codex's first tool call.
* Bad, because it moves workflow-specific board semantics into the orchestrator.
* Bad, because the spec treats tracker writes as agent/workflow-driven unless an implementation adds
  a separate external claim mechanism.

### Add Only A Pickup Comment

Have Codex add a short comment when it starts.

* Good, because it works without another column.
* Bad, because comments are not visible in the board scan view.
* Bad, because comments can become noisy.

### Keep Pickup Visible Only In The Status Page/API

Rely on `/` and `/api/v1/state` to show running cards.

* Good, because this already exists.
* Bad, because users managing work in Trello must leave the board to see whether a card was picked
  up.

## More Information

The upstream OpenAI Symphony Elixir workflow asks Codex to move queued Linear issues to
`In Progress` before doing implementation work. Symphony for Trello follows that pattern with Trello
columns and scoped Trello move tools.
