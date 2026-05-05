---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, README.md, GitHub issues #11 and #12]
informed: [Future maintainers]
---

# Use A Workflow-Configured Blocked Handoff Column

## Context and Problem Statement

A live run showed that a workflow prompt which tells Codex to comment on blocked work and leave the
card in the active column can produce repeated blocker comments. Symphony sees the card still in an
active column and may continue processing it, which follows the specification's continuation behavior
but is surprising for blocked work.

How should Symphony for Trello make blocked work visible while preserving the specification boundary
that the orchestrator does not own business-specific card mutation policy?

## Decision Drivers

* Make blocked work visible from the Trello board.
* Prevent blocked cards from staying in active dispatch columns by default.
* Keep handoff policy in `WORKFLOW.md` and scoped Trello tools, not hard-coded orchestrator logic.
* Preserve useful continuation behavior for cards that genuinely remain active after a successful
  turn.
* Keep imported existing boards easy to configure without requiring the recommended board layout.

## Considered Options

* Workflow-configured blocked handoff column.
* Move blocked cards to review when no blocked column exists.
* Detect blocker comments in the orchestrator and suppress redispatch.
* Leave blocked cards active and rely on operators to read comments.

## Decision Outcome

Chosen option: "Workflow-configured blocked handoff column", with a review-column fallback when no
blocked column is configured.

The recommended board setup creates `Blocked` and the generated workflow allows Codex to move blocked
work there. Existing-board import detects a column named `Blocked`, and also accepts an explicit
`--blocked` column name. If there is no blocked column but there is a review handoff column, the
generated prompt tells Codex to move blocked work to review so it leaves the active column.

### Consequences

* Good, because blocked work is visible without opening each Trello card.
* Good, because the orchestrator remains a scheduler and does not parse blocker text.
* Good, because existing boards can use their own blocked-column name.
* Neutral, because blocked work without a dedicated column falls back to review instead of a separate
  board state.
* Bad, because read-only workflows or boards without any handoff column can still leave blocked work
  active unless operators move the card manually.

### Confirmation

Run `./mvnw -q spotless:check verify`. Review the generated workflows from both `new-board` and
`import-board` to confirm blocked work never stays in the active column when a handoff column is
available.

## Pros and Cons of the Options

### Workflow-Configured Blocked Handoff Column

Create or detect a board-local blocked column and include it in `trello_tools.allowed_move_list_names`.
The prompt tells Codex to comment and move blocked work to that column.

* Good, because the blocked state is visible in the board columns.
* Good, because the destination remains explicit workflow configuration.
* Good, because it follows the same scoped tool model as review handoff.
* Bad, because teams importing existing boards may need to create or name a blocked column.

### Move Blocked Cards To Review When No Blocked Column Exists

Use the review handoff column for blocked work when a dedicated blocked column is not configured.

* Good, because the card leaves the active column and avoids repeated execution.
* Good, because it works with the common existing-board shape that already has review.
* Bad, because blocked and reviewable work share the same column and must be distinguished by the
  Trello comment.

### Detect Blocker Comments In The Orchestrator And Suppress Redispatch

Have the scheduler inspect comments or worker output and treat blocker wording as a stop condition.

* Good, because it could work even without a blocked column.
* Bad, because text matching is brittle.
* Bad, because it moves subjective business policy into the orchestrator.
* Bad, because it does not make blocked work visible in the board columns by itself.

### Leave Blocked Cards Active And Rely On Operators To Read Comments

Keep the previous prompt behavior and require humans to notice blocker comments manually.

* Good, because it requires no new setup behavior.
* Bad, because the board view hides blocked work.
* Bad, because an active card can be run again and receive repeated blocker comments.

## More Information

Related issues:

* #11: make blocked Codex work visible on the Trello board.
* #12: prevent repeated blocker comments when blocked cards remain active.
