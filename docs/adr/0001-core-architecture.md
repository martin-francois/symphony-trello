---
status: accepted
date: 2026-05-04
decision-makers: [François Martin, Codex]
consulted: [SPEC.md]
informed: [Future maintainers]
---

# Build Symphony for Trello as a Modular Quarkus Service

## Context and Problem Statement

The adapted Symphony specification requires a Trello-backed scheduler, deterministic workspaces,
strict prompt construction, Codex app-server execution, and operator-visible runtime state. The first
implementation needed boundaries that are easy to test and evolve without adding persistent storage
before the spec requires it.

How should the backend be structured so Trello polling, workspace lifecycle, prompt rendering, Codex
execution, and runtime state remain understandable and independently testable?

## Decision Drivers

* Preserve the Symphony-for-Trello contract from `SPEC.md`.
* Keep the initial implementation simple enough to maintain without a database.
* Make external boundaries testable with focused unit and integration tests.
* Keep scheduler state in one place to avoid race-prone duplicated maps.
* Leave room for future persistence without leaking it into Trello or Codex adapters.

## Considered Options

* Modular Quarkus service with explicit package boundaries.
* Single orchestration class that talks directly to Trello, workspaces, prompts, and Codex.
* Event-driven service with persistent queue and database-backed state.

## Decision Outcome

Chosen option: "Modular Quarkus service with explicit package boundaries", because it satisfies the
spec, keeps the first implementation small, and gives each external boundary a clear test surface.

### Consequences

* Good, because `tracker`, `workspace`, `prompt`, `agent`, `orchestrator`, and `api` can evolve
  independently.
* Good, because tests can isolate Trello transport, prompt rendering, workspace hooks, and scheduler
  behavior.
* Good, because the orchestrator remains the single owner of mutable scheduling state and worker
  identity validation.
* Bad, because restart recovery depends on Trello and preserved workspaces rather than restored
  in-memory retry timers.
* Bad, because future persistence will require a deliberate change behind the orchestrator boundary.

### Confirmation

Run `./mvnw -q spotless:check verify`. Code review should confirm that Trello REST access stays in
`tracker`, workspace filesystem logic stays in `workspace`, prompt rendering stays in `prompt`,
Codex protocol handling stays in `agent`, and scheduler state stays in `orchestrator`.

## Pros and Cons of the Options

### Modular Quarkus service with explicit package boundaries

Implement the service as a Quarkus application with packages for workflow/config parsing, Trello
transport, workspace lifecycle, prompt rendering, Codex execution, orchestration, and HTTP status.

* Good, because package boundaries mirror the spec's external systems and responsibilities.
* Good, because it avoids introducing persistence before there is a concrete recovery requirement.
* Good, because future adapters can be replaced behind narrow interfaces.
* Neutral, because the orchestrator still coordinates several collaborators.
* Bad, because boundaries must be defended in review to avoid slow coupling drift.

### Single orchestration class that talks directly to every subsystem

Put most behavior in one service class.

* Good, because it is the smallest amount of code at the start.
* Bad, because Trello parsing, prompt rendering, workspace hooks, and Codex protocol handling would
  become difficult to test independently.
* Bad, because future changes would increase the chance of scheduler regressions.

### Event-driven service with persistent queue and database-backed state

Use a database and queue to store scheduling state, retries, and worker lifecycle.

* Good, because restart behavior could preserve retry timers and detailed run state.
* Neutral, because it may be useful if the service later needs durable multi-process coordination.
* Bad, because it adds operational complexity that the current spec does not need.
* Bad, because it would make local setup and live Trello testing heavier.

## More Information

The current package layout is:

* `workflow` and `config` for `WORKFLOW.md` parsing, defaulting, and validation.
* `tracker` for Trello REST transport and card/list mapping.
* `workspace` for directory lifecycle and hooks.
* `prompt` for strict Pebble template rendering.
* `agent` for local Codex app-server execution.
* `orchestrator` for scheduler state.
* `api` for status and refresh endpoints.
