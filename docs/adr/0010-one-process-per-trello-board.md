---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, README.md, docs/live-e2e.md]
informed: [Future maintainers]
---

# Run One Symphony Process per Trello Board

## Context and Problem Statement

Symphony for Trello can be used across several projects, but a single `WORKFLOW.md` contains one
Trello `tracker.board_id` and one prompt/runtime policy. Users still need to run multiple boards at
the same time, and cards within one board need predictable dispatch ordering and a clear concurrency
limit.

How should the service support multiple Trello boards without introducing cross-board coordination
state or making the workflow contract harder to reason about?

## Decision Drivers

* Keep `WORKFLOW.md` as the complete contract for one board's prompt, Trello lists, and runtime
  policy.
* Preserve the adapted Symphony spec's assumption of one active orchestrator process per configured
  Trello board.
* Make card dispatch order predictable inside a board.
* Allow parallel card work when the board's workflow opts in.
* Avoid distributed locking, shared queues, or a database before the spec requires them.
* Keep multi-board live verification reproducible with independent ports and workflow files.

## Considered Options

* One process per workflow file and Trello board.
* One process that watches several boards from one merged configuration.
* Multiple processes allowed to watch the same Trello board.

## Decision Outcome

Chosen option: "One process per workflow file and Trello board", because it keeps each board's
policy independent while still allowing several projects to run at the same time through separate
processes.

### Consequences

* Good, because a workflow file can be read as the full policy for exactly one board.
* Good, because multiple projects can run concurrently by starting multiple service processes on
  different HTTP ports.
* Good, because per-board `max_concurrent_agents` is easy to explain and test.
* Good, because cards dispatch in the board-local order defined by the spec: priority, configured
  active-list order, Trello card position, creation time, and stable identifiers.
* Bad, because operators must start one process per board instead of registering every board in one
  service instance.
* Bad, because two processes pointed at the same board remain unsupported without an external claim
  mechanism.

### Confirmation

Run `./mvnw -q spotless:check verify`. For live behavior, follow `docs/live-e2e.md` and verify one
board with `max_concurrent_agents: 1`, one board with `max_concurrent_agents: 2`, and multiple
boards running through separate processes and ports.

## Pros and Cons of the Options

### One process per workflow file and Trello board

Start one Symphony process for each `WORKFLOW.md`. Each workflow resolves one Trello board and owns
that board's scheduling state.

* Good, because process boundaries match the workflow contract.
* Good, because board-specific prompts, Trello list mappings, credentials, and Codex settings do not
  bleed into other boards.
* Good, because live E2E can verify concurrency by starting independent processes.
* Neutral, because deployment automation can still manage several processes as a group.
* Bad, because an operator needs a process supervisor or separate terminals for multiple boards.

### One process that watches several boards from one merged configuration

Let one service instance load a list of boards and run a shared scheduler across all of them.

* Good, because operators would start fewer processes.
* Bad, because the current `WORKFLOW.md` contract would need another multi-board layer.
* Bad, because board-specific prompts, credentials, and limits would be easier to mix up.
* Bad, because shared concurrency policy across boards would need new semantics.

### Multiple processes allowed to watch the same Trello board

Permit several service instances to point at one board and rely on local checks to avoid duplicate
work.

* Good, because it could scale a busy board horizontally.
* Bad, because Trello alone does not provide the claim lock needed to avoid duplicate dispatch.
* Bad, because the adapted spec requires an external claim mechanism before multiple orchestrators
  target the same board.

## More Information

The README documents this in the workflow contract section rather than the opening text. The opening
should focus on what Symphony for Trello does, while this process-to-board mapping is an operational
detail readers need when configuring real workflows.
