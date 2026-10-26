---
status: accepted
date: 2026-10-27
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #1072](https://github.com/martin-francois/symphony-trello/issues/1072)"
informed: [Future maintainers, Operators]
---

# Confirm runtime shutdown before workspace removal

## Context and Problem Statement

Terminal and missing-card reconciliation cancels a worker Future and immediately removes its
workspace. Cancellation marks a Future done before its computation exits. The runner still owns
its app-server and `after_run` hook at that point. A latch-controlled regression reproduces deletion
while the canceled runner remains alive for both terminal and missing cards.

The app-server separately requests forcible termination without a final exit wait. Its return alone
therefore does not establish that its process has released the workspace.

## Decision Drivers

* Delete only after the worker and its owned foreground processes have stopped.
* Do not block reconciliation while an interrupted runner unwinds or a hook finishes.
* Preserve card claims, launch-workspace ownership and stale-worker filtering during cleanup.
* Bound native shutdown waits and retain the workspace when exit cannot be confirmed.

## Considered Options

* Confirm worker return and bounded foreground-process shutdown before removal.
* Wait a fixed grace period, then remove the workspace without exit confirmation.
* Wait indefinitely while holding the orchestrator operation lock.

## Decision Outcome

Chosen option: "Confirm worker return and bounded foreground-process shutdown before removal".

The orchestrator records execution completion separately from Future cancellation. Pending workspace
removal retains the card claim until the captured worker returns. The runner's return includes
`after_run`. Cleanup uses the original workspace configuration and does not cancel or delete a
replacement worker's workspace. Retained paths also block a different card or tracker target from
reusing the same normalized workspace path. Pending removals share the existing 128-entry dispatch threshold.
A worker that does not return leaves its workspace retained; the
runtime reports this state instead of treating a cancellation request as acknowledgement.

Lifetime ownership is independent of deletion intent. Nonterminal cancellation and handoff expiry
retain the captured worker until shutdown is confirmed, even when tracker state requires no removal.
Pending retries preserve their metadata while waiting and cannot dispatch or remove that directory.
An unconfirmed exit cancels that worker's retry and retires its bound usage probe, allowing unrelated
cards to continue. `before_remove` clears a cancellation interrupt for its configured execution budget
and restores the caller's interruption afterwards.

Runtime process shutdown captures the direct process and its attached descendants before signaling
termination. It allows two seconds for graceful termination and then two seconds after forcible
termination, with one shared deadline per phase. Interruptions do not skip exit confirmation; the
interrupt flag is restored afterwards. Failure to confirm exit produces a typed retained-workspace
result. Secondary final-response finish or close failures preserve that result and emit the existing
privacy-safe storage diagnostic. Ordinary storage-failure handling remains unchanged.
The orchestrator retains that workspace and claim for operator inspection instead of
retrying work in a directory that remains in use. `after_run` is skipped when app-server exit is
unconfirmed; otherwise it runs before the runner acknowledges completion. A removal hook whose exit
remains unconfirmed also retains the workspace. Ordinary best-effort hook failures keep their existing behavior after
confirmed process exit. Claims remain process-local, so operators must verify process exit
before service restart as well as before manual removal.

Unconfirmed ownership uses an immutable record of the original identifier, configuration and owner,
independently of a running worker. Every workspace-removal caller uses the same retention boundary,
including startup and terminal/missing retry cleanup. Pending runner completion remains separate.
Claims, normalized-path dispatch checks and the 128-key threshold include both forms of ownership.
Startup stops starting removal hooks at that threshold; unrelated cards continue below it.

The owned process set is a shutdown-time snapshot, not an operating-system containment boundary.
Configured commands and hooks must remain foreground processes, keep their children attached and
not spawn replacements during shutdown. Detached or reparented processes and hostile fork loops
are outside this contract. This repair does not add the archived Java-managed Git process framework.

### Consequences

* Good, because terminal deletion follows observed lifetime rather than Future state.
* Good, because a slow worker does not hold the reconciliation lock while it unwinds.
* Bad, because an unconfirmed shutdown retains a workspace and card claim until operator intervention
  or service restart. Operators must establish that its processes stopped before removing it.
* Neutral, because no CLI flags, workflow fields or Trello permissions change.

### Confirmation

The orchestrator regression holds the canceled runner alive and asserts that its workspace remains.
It then releases the runner and checks exactly one cleanup without another dispatch. Real owned
fake-app-server tests check shutdown and hook ordering. A controllable process test covers the
no-exit outcome. Removing execution acknowledgement must fail the lifetime regression.

## Pros and Cons of the Options

### Confirm worker return and bounded foreground-process shutdown before removal

Track actual runner return and require process exit confirmation before deletion.

* Good, because it preserves workspace safety without a long orchestrator lock hold.
* Bad, because uncertain shutdown requires retained cleanup state.

### Wait a fixed grace period, then remove without confirmation

Delay deletion by a constant interval regardless of actual process state.

* Good, because implementation is small.
* Bad, because slow shutdown still leaves a live process using a deleted workspace.

### Wait indefinitely under the operation lock

Join the worker before allowing reconciliation to proceed.

* Good, because the lifetime ordering is explicit.
* Bad, because callbacks and unrelated cards stop making progress behind a hung worker.

## More Information

This implements the bounded-policy choice in SPEC Section 8.5. The initial red proof establishes
ordering on this test host; it does not claim a native survivor, filesystem failure or data loss on
other platforms. The process tests use owned fixtures and require no live credentials.
