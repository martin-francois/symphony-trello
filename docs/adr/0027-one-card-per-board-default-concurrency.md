---
status: accepted
date: 2026-05-19
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, README.md, ConfigResolver]
informed: [Future maintainers]
---

# Default to One Active Card per Trello Board

## Context and Problem Statement

Symphony for Trello runs one workflow per connected Trello board. Each workflow has its own runtime
configuration and can process cards independently from other connected boards.

The adapted upstream specification originally documented `agent.max_concurrent_agents` with a
default of `10`. That default was preserved in runtime config resolution, while generated setup
workflows explicitly wrote `max_concurrent_agents: 1`. The result was inconsistent: generated
workflows processed one card at a time per board, but hand-written workflows that omitted
`agent.max_concurrent_agents` could process up to ten cards from one board at once.

What should Symphony for Trello do when a workflow omits `agent.max_concurrent_agents`?

## Decision Drivers

* Match the intended Trello operating model: one card at a time per board by default.
* Still allow multiple connected Trello boards to run in parallel.
* Keep generated workflows and hand-written workflows consistent.
* Preserve an explicit opt-in path for higher per-board concurrency.
* Avoid surprising users who expect Trello list order to control the next card processed on a board.

## Considered Options

* Default omitted `agent.max_concurrent_agents` to `1`.
* Keep runtime default `10` while generated workflows write `1`.
* Change generated workflows to omit the field and rely on runtime default `10`.
* Add a migration or compatibility alias that rewrites older workflows.

## Decision Outcome

Chosen option: "Default omitted `agent.max_concurrent_agents` to `1`", because it gives generated
and hand-written workflows the same behavior and matches the product expectation that one board
processes one card at a time unless the operator explicitly opts into more concurrency.

The default is per workflow and therefore per connected Trello board. If ten boards are connected
and each has one ready card, those boards may be processed in parallel. If one board has two ready
cards, only one card from that board is processed at a time unless that board's workflow raises
`agent.max_concurrent_agents`.

### Consequences

* Good, because omitted config now matches generated workflow behavior.
* Good, because first-time users get predictable Trello board ordering by default.
* Good, because operators can still raise `agent.max_concurrent_agents` for a specific board.
* Neutral, because workflows that already set `agent.max_concurrent_agents` keep their explicit
  value.
* Bad, because hand-written workflows that relied on omission meaning ten concurrent cards now need
  to set the value explicitly.

### Confirmation

Run `./mvnw -q spotless:check verify`. A workflow that omits `agent.max_concurrent_agents` should
resolve to `ConfigDefaults.DEFAULT_MAX_CONCURRENT_AGENTS`, which is `1`. Generated workflows should
continue to write `max_concurrent_agents: 1`.

## Pros and Cons of the Options

### Default omitted `agent.max_concurrent_agents` to `1`

Make the runtime fallback and setup-generated value the same.

* Good, because it matches the intended one-card-per-board default.
* Good, because it removes the generated-versus-hand-written workflow mismatch.
* Good, because higher concurrency remains an explicit workflow setting.
* Bad, because it changes the fallback for hand-written workflows that omitted the field.

### Keep runtime default `10` while generated workflows write `1`

Preserve the upstream imported runtime fallback and keep generated workflows conservative.

* Good, because it preserves the earlier fallback for hand-written workflows.
* Bad, because two workflows with the same visible user intent can behave differently depending on
  whether setup generated the field.
* Bad, because it contradicts the intended Trello default.

### Change generated workflows to omit the field and rely on runtime default `10`

Make generated and hand-written workflows consistent by making both use the upstream fallback.

* Good, because it removes the mismatch.
* Bad, because it makes first-time setup process multiple cards from one board by default.
* Bad, because it makes the README's one-card-per-board guidance false.

### Add a Rewrite Alias for Previous Workflow Files

Introduce rewrite logic to distinguish previous setup output from hand-written workflows.

* Good, because it could preserve older behavior for selected workflows.
* Bad, because no migration is needed: explicit workflow values already preserve intentional
  concurrency.
* Bad, because it adds complexity for a default correction.

## More Information

The upstream imported specification line documenting default `10` came from the original Symphony
specification. Symphony for Trello uses a different Trello-board operating model, where board-level
serial processing is the safer default.
