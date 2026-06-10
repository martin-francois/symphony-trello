---
status: accepted
date: 2026-06-10
decision-makers: ["martinfrancois"]
consulted:
  - "[GitHub issue #206](https://github.com/martin-francois/symphony-trello/issues/206)"
informed: ["repository contributors"]
---

# Bounded Setup Value For Max Concurrent Agents

## Context and Problem Statement

`new-board`, `import-board`, and `setup-local` accepted any positive `--max-agents` value and wrote
it directly into the generated workflow. A value such as `999999` is syntactically valid but
operationally unsafe: each concurrent card runs its own Codex agent plus builds and tests, and high
concurrency also makes Trello ordering assumptions unsafe while prerequisite card relationships are
not modeled. How should setup protect users from accidentally configuring unsafe concurrency?

## Decision Drivers

- Setup must fail before Trello side effects such as board creation.
- Users should not need to inspect workflow internals to recover from the error.
- The smooth default path should not gain extra confirmation prompts.

## Considered Options

- Documented upper bound enforced by setup validation
- Explicit confirmation flag (for example `--allow-high-max-agents`) above a safe threshold
- Interactive confirmation prompt above a safe threshold

## Decision Outcome

Chosen option: "Documented upper bound enforced by setup validation", because it keeps the CLI
deterministic and non-interactive, avoids adding a positive flag that only restates intent, and a
shared bound of `32` is far above any realistic single-host concurrency while still preventing
accidental extreme values. The bound is validated centrally in the setup request records, so every
setup path (`new-board`, `import-board`, `setup-local`, guided setup) rejects out-of-range values
before any Trello request.

### Consequences

- Good, because invalid values fail fast with the expected `setup_invalid_max_agents` error and an
  actionable range in the message.
- Good, because the limit and its rationale live in one constant
  (`TrelloBoardSetup.MAX_SETUP_CONCURRENT_AGENTS`).
- Bad, because an operator with genuinely larger hardware cannot exceed `32` through setup and must
  edit the workflow file directly (the runtime still only requires a positive value).

### Confirmation

`TrelloBoardSetupTest` and the CLI tests assert that values above the bound are rejected before any
Trello board is created, and the `--max-agents` help text documents the range.

## Pros and Cons of the Options

### Documented upper bound enforced by setup validation

- Good, because it is deterministic, testable, and needs no new flags or prompts.
- Bad, because the specific number is a judgment call.

### Explicit confirmation flag above a safe threshold

- Good, because expert users could opt in to any value.
- Bad, because it adds a rarely used positive flag, which the CLI guidelines avoid.

### Interactive confirmation prompt

- Good, because it explains the risk at decision time.
- Bad, because direct setup commands are intentionally non-interactive and scriptable.

## More Information

- [GitHub issue #206](https://github.com/martin-francois/symphony-trello/issues/206) documents the
  live evidence, including `new-board` creating a real Trello board before any concurrency check.
- The runtime config contract still only requires `agent.max_concurrent_agents` to be positive;
  the bound applies to setup inputs.
