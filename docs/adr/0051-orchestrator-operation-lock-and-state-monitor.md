---
status: accepted
date: 2026-06-10
decision-makers: ["martinfrancois"]
consulted:
  - "[GitHub issue #377](https://github.com/martin-francois/symphony-trello/issues/377)"
  - "[GitHub issue #213](https://github.com/martin-francois/symphony-trello/issues/213)"
informed: ["repository contributors"]
---

# Orchestrator Operation Lock And State Monitor Split

## Context and Problem Statement

`SymphonyOrchestrator` used one instance monitor both to serialize its long-running operations
(start, stop, polling tick, worker exit, retry timers, agent events) and to guard the state that
the status endpoints read. The polling tick and the other operations perform Trello network I/O
while holding that monitor, so `/api/v1/local-status` and `/api/v1/state` requests queued behind
in-flight Trello round-trips. Lifecycle health probes use a 500 ms timeout, so a probe landing
mid-tick timed out and misreported a healthy managed worker as `PORT_USED`
([GitHub issue #213](https://github.com/martin-francois/symphony-trello/issues/213),
[GitHub issue #377](https://github.com/martin-francois/symphony-trello/issues/377)).
How should the orchestrator isolate status reads from operation I/O without changing how
operations interact with Trello?

## Decision Drivers

- Status reads must answer in milliseconds regardless of polling activity.
- The order and grouping of Trello calls must not change; the operation logic is the product core
  and is covered by behavior tests that must stay meaningful.
- The locking rules must be simple enough that future maintainers can extend operations without
  reintroducing the contention.

## Considered Options

- Dedicated operation lock plus a short state monitor
- Copy-on-write immutable state snapshot
- Phase-split operations with queued Trello actions
- Longer probe timeouts or client-side probe retries only

## Decision Outcome

Chosen option: "Dedicated operation lock plus a short state monitor", because it removes the
contention without reordering a single Trello interaction and leaves an explicit, checkable rule
for future code.

A private `ReentrantLock` (`operationLock`) now serializes the long-running operations exactly as
the old synchronized methods did. The instance monitor only guards reader-visible state and is
held for short, I/O-free sections around writes. Status reads take only the monitor, and the
local-status getters are lock-free reads of the volatile resolved config. Writes to shared state
happen under both locks, so reads inside operations need no monitor: every writer holds the
operation lock, which orders them, and the monitor exists only so readers see consistent state.

### Consequences

- Good, because `/api/v1/local-status` and `/api/v1/state` answer immediately during polling, and
  lifecycle probes no longer misreport healthy workers as `PORT_USED` because of lock contention.
- Good, because operation behavior is unchanged: the same mutual exclusion, the same Trello call
  order, no new interleavings between operations.
- Good, because the rule is mechanical: I/O may run under the operation lock, never under the
  monitor; state writes take the monitor.
- Bad, because two locks are more to understand than one, and a forgotten monitor section around a
  new state write would be a reader-visibility bug that tests may not catch.
- Bad, because a status snapshot taken mid-operation can observe intermediate state, for example a
  terminated card before its retry is scheduled. Status endpoints are point-in-time samples, so
  callers already tolerate this.

### Confirmation

`SymphonyOrchestratorTest.statusReadsAnswerWhileTickIsBlockedInsideTrelloFetch` blocks the first
candidate fetch inside a tick and asserts that the local-status getters and the state snapshot
answer within a bounded time. The test fails on the previous single-monitor design. Lifecycle
atomicity is confirmed by `refreshAtTickCompletionBoundaryIsNotOverwrittenByIntervalSchedule`,
`refreshRequestedDuringAndAfterStopIsANoOpAndDoesNotThrow`, and
`workflowPathCannotChangeOnceStartHasBegun`, which pin that tick completion, refresh scheduling,
stop, and workflow path changes keep their single-monitor semantics under the split locks.

## Pros and Cons of the Options

### Dedicated operation lock plus a short state monitor

Replace the monitor's serialization role with a dedicated `ReentrantLock` held for whole
operations, narrow the monitor to short I/O-free write sections, and keep readers on the monitor
only. Operation code keeps its existing structure and Trello call order.

- Good, because no Trello interaction is reordered, so behavior risk is minimal.
- Good, because readers never wait for network I/O.
- Neutral, because operations still serialize against each other, so a slow Trello call still
  delays the next tick. That matches the previous behavior.
- Bad, because every new write to reader-visible state must remember the monitor section.

### Copy-on-write immutable state snapshot

Keep all reader-visible state in one immutable object replaced atomically on every change, so
readers never lock at all.

- Good, because readers become wait-free and trivially consistent.
- Bad, because every mutation site in the orchestrator must be rewritten to build a new state
  object, a much larger and riskier change for the product core.
- Bad, because frequent per-event copies (agent events stream during runs) add avoidable work.

### Phase-split operations with queued Trello actions

Restructure each operation into decide-under-lock, perform-I/O-unlocked, and commit-under-lock
phases, queuing Trello releases and moves for execution outside any lock.

- Good, because no lock is ever held across I/O, so even operations stop delaying each other.
- Bad, because it reorders Trello interactions relative to state changes and to each other, which
  changes observable behavior and invalidates the current behavior tests.
- Bad, because staleness re-checks after every fetch add a new class of race-condition bugs.

### Longer probe timeouts or client-side probe retries only

Keep the orchestrator unchanged and make lifecycle probes more tolerant with longer timeouts or
delayed re-probes.

- Good, because the change is small and fully on the probing side.
- Bad, because it treats the symptom: the worker still cannot answer during a poll, and every
  probe of a genuinely foreign non-HTTP port pays the longer timeout.
- Bad, because slow status endpoints stay slow for users opening the status page.

## More Information

The delayed re-probe added on the lifecycle side while investigating
[GitHub issue #213](https://github.com/martin-francois/symphony-trello/issues/213) remains as a
compatibility net for already-deployed workers that do not carry this fix.
