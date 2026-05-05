---
status: accepted
date: 2026-05-04
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, Installed Codex app-server schema]
informed: [Future maintainers]
---

# Treat the Codex App-Server Schema as the Protocol Boundary

## Context and Problem Statement

The Symphony specification states that Codex app-server protocol schema is the source of truth. The
local Codex CLI can expose that schema, and protocol details may change between Codex versions.

How should the Java backend call Codex without freezing implementation details that belong to the
installed Codex app-server?

## Decision Drivers

* Preserve compatibility with the installed Codex app-server protocol.
* Keep Codex policy values configurable through `WORKFLOW.md`.
* Avoid hand-maintained enum copies for policy strings controlled by Codex.
* Avoid worker sessions stalling indefinitely on unsupported dynamic requests.
* Keep protocol behavior covered by focused client tests.

## Considered Options

* Implement the installed Codex app-server flow directly and keep policy values pass-through.
* Generate Java client types from the Codex schema.
* Shell out to a higher-level Codex CLI command instead of using app-server JSON-RPC.

## Decision Outcome

Chosen option: "Implement the installed Codex app-server flow directly and keep policy values
pass-through", because it aligns with the current protocol while keeping the implementation small and
avoiding generated-code churn.

### Consequences

* Good, because startup and turn handling match the generated schema used during implementation.
* Good, because approval and sandbox policy values can follow Codex without Java enum changes.
* Good, because unsupported requests are handled explicitly instead of blocking a worker forever.
* Bad, because future Codex protocol changes may require focused updates to the Java client.
* Bad, because pass-through policy strings rely on workflow validation and operator discipline.

### Confirmation

Run `./mvnw -q spotless:check verify`. Tests around the Codex client should fail if startup message
order, completion handling, or unsupported request handling changes unexpectedly.

## Pros and Cons of the Options

### Implement the installed Codex app-server flow directly and keep policy values pass-through

The Java client launches the configured command in the card workspace, sends `initialize`,
`initialized`, `thread/start`, and `turn/start`, then streams notifications until `turn/completed`.

* Good, because the implementation is explicit and debuggable.
* Good, because it avoids generated source and build-step complexity.
* Neutral, because client tests must be updated when Codex protocol behavior changes.
* Bad, because the Java code still knows the current startup flow.

### Generate Java client types from the Codex schema

Generate request/response types from the installed Codex app-server schema.

* Good, because generated types can catch schema drift at compile time.
* Good, because request and response shape would be less hand-written.
* Bad, because schema generation adds build complexity for a narrow client surface.
* Bad, because generated code can obscure the few protocol messages this service actually uses.

### Shell out to a higher-level Codex CLI command instead of using app-server JSON-RPC

Run Codex as a simple command and parse terminal output.

* Good, because it avoids maintaining a JSON-RPC client.
* Bad, because it loses structured dynamic tool handling.
* Bad, because progress, approval, and completion semantics become harder to test reliably.

## More Information

Approval requests are accepted for the current session in the trusted default posture. User-input and
unsupported dynamic tool requests receive immediate responses so a run does not stall indefinitely.
