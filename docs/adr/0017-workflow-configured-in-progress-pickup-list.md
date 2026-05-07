---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, README.md, OpenAI Symphony Elixir workflow]
informed: [Future maintainers]
---

# Use A Workflow-Configured In-Progress Pickup List

## Context and Problem Statement

Users looking at Trello should be able to tell when Codex is actively working on a card. The
implementation already exposes running work through the status page and API, but a Trello board is
easier to scan when running cards leave the queue list.

How should Symphony for Trello make pickup visible while preserving the specification boundary that
tracker writes are driven by the workflow instead of hard-coded board assumptions?

## Decision Drivers

* Make active Codex work visible from the Trello board.
* Stay aligned with the original Symphony workflow pattern where Codex moves work from a queue state
  to an in-progress state.
* Keep card mutation policy in `WORKFLOW.md`.
* Keep imported existing boards usable when they do not have an in-progress list.
* Preserve restart behavior for cards already moved to the in-progress list.
* Avoid leaving retry/backoff cards in `In Progress` when no worker is running for them.

## Considered Options

* Workflow-configured scheduler move to an in-progress pickup list.
* Prompt-only agent move.
* Add only a pickup comment.
* Keep pickup visible only in the status page/API.

## Decision Outcome

Chosen option: "Workflow-configured scheduler move to an in-progress pickup list".

The recommended board setup creates `In Progress`. Generated workflows include `Ready for Codex` and
`In Progress` in `tracker.active_states`, set `tracker.in_progress_state` to `In Progress`, and
include `In Progress` in `trello_tools.allowed_move_list_names` for workflows that still need the
agent to move cards explicitly. After the orchestrator revalidates a selected card, it moves cards
from earlier active lists into the configured in-progress list before rendering the prompt and
starting Codex. If the attempt fails and waits for retry/backoff, or if a card is visible in
`In Progress` but cannot be run because concurrency slots are full, the orchestrator moves it back
to the previous configured active list when that target can be resolved. Existing-board import
detects a list named `In Progress` or accepts `--in-progress`. If no in-progress list is configured,
the generated workflow leaves the card in the active list while Codex works.

### Consequences

* Good, because the Trello board shows when a queued card was picked up.
* Good, because a card already in `In Progress` remains eligible for continuation after a service
  restart.
* Good, because retry/backoff cards no longer look like currently running work when the previous
  active list can be resolved.
* Good, because existing boards without an in-progress list still work.
* Bad, because the orchestrator now performs one workflow-configured tracker write before the agent
  starts.

### Confirmation

Run `./mvnw -q spotless:check verify`. Review generated workflows from `new-board` and
`import-board`. Live deployment checks should cover both a workflow with an in-progress list and a
workflow without one.

## Pros and Cons of the Options

### Workflow-Configured Scheduler Move To An In-Progress Pickup List

Add an optional in-progress list to the generated workflow and have the scheduler move queue cards
there after revalidation and before Codex starts.

* Good, because it mirrors the original Symphony workflow pattern.
* Good, because the workflow owns the visible board semantics.
* Good, because the pickup move is guaranteed before Codex execution when Trello accepts the move.
* Bad, because the scheduler performs a Trello write for visible pickup state.

### Prompt-Only Agent Move

Prompt Codex to call `trello_move_current_card` before implementation work.

* Good, because it keeps tracker writes fully inside the agent toolchain.
* Bad, because the pickup move is not guaranteed if the agent fails before tool use or misses the
  instruction.

### Add Only A Pickup Comment

Have Codex add a short comment when it starts.

* Good, because it works without another list.
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
lists and scoped Trello move tools.
