---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #381](https://github.com/martin-francois/symphony-trello/issues/381)"
  - "[GitHub PR #379](https://github.com/martin-francois/symphony-trello/pull/379)"
  - "[GitHub PR #802](https://github.com/martin-francois/symphony-trello/pull/802)"
  - "[ADR 0051](0051-orchestrator-operation-lock-and-state-monitor.md)"
  - "[ADR 0102](0102-orchestrator-concurrency-mutation-audit.md)"
informed: [Future maintainers, Contributors]
---

# Replace The Orchestrator State Monitor With A Published Read View

## Context and Problem Statement

[ADR 0051](0051-orchestrator-operation-lock-and-state-monitor.md) serializes long orchestrator
operations with an operation lock and keeps the instance monitor around every write that status
readers can see. [ADR 0102](0102-orchestrator-concurrency-mutation-audit.md) removed each
synchronization element one at a time. Of the 53 monitor sections and `synchronized` methods, 23 are
redundant because only operation-lock holders touch their state, and 24 exist only so that
`snapshot()` and `cardDetails()` read consistent maps. No deterministic test owns those 24, and one
audit run showed the race they prevent as a `ConcurrentModificationException` in a status poll.

[GitHub issue #381](https://github.com/martin-francois/symphony-trello/issues/381) asks whether a
simpler design can replace this one without a public behavior change. Which design should the
orchestrator move to?

## Decision Drivers

* Status reads must never wait for Trello I/O (the driver of ADR 0051).
* The order of Trello calls and the operation-level mutual exclusion must not change.
* No public behavior change: local-status JSON fields, `/api/v1/state`,
  `/api/v1/<card_identifier>`, `BoardHealthKind` semantics, and the refresh and stop contracts.
* A forgotten step in new code should fail safe, and the remaining mechanisms should each have an
  owning test as [Testing](../agents/testing.md) requires.
* The change should be reviewable on its own and should not force a rewrite of work in flight.

## Considered Options

* Operation lock plus a published read view and a tick-schedule monitor
* Keep the current design and accept the untestable sections
* Single-writer confinement on the scheduler thread
* Copy-on-write state for all orchestrator state

## Decision Outcome

Chosen option: "Operation lock plus a published read view and a tick-schedule monitor", because it
removes the 47 monitor sections that the audit shows are either redundant or untestable, keeps every
operation and Trello call as it is, and turns a forgotten publish into a stale but consistent status
view instead of a data race.

[GitHub issue #815](https://github.com/martin-francois/symphony-trello/issues/815) implements the
decision in its own pull request. Until it lands, ADR 0051 describes the code. That pull request
marks ADR 0051 as superseded by this ADR and records a new mutation matrix here.

The design:

* Writers keep the operation lock. Start, stop, ticks, retry timers, pause deadlines, worker exits
  and agent events run under it exactly as today.
* Status readers (`snapshot()`, `cardDetails()`, `isStarted()`) read one `volatile` reference to an
  immutable read view: running rows, retry rows, recent events, token totals, ended runtime,
  dispatch pause, rate limits and the effective config. They take no lock.
* An operation publishes a new view at each point where a monitor section ends today with
  reader-visible state, and always when it releases the operation lock. Some intermediate points are
  part of the contract: `typedUsageLimitInstallsFallbackPauseBeforeTrelloIoAndGatesManualDispatch`
  requires the usage pause to be visible before the worker-exit Trello I/O.
* `started`, `tickRunning`, the tick timer and the refresh flag move into one small object with its
  own monitor. `requestRefresh()` keeps using only that monitor, so it still never waits for a tick.
* The 23 redundant sections from ADR 0102 are deleted without replacement.

### Consequences

* Good, because status readers no longer share any lock with writers, so no write section can be
  forgotten in a way that corrupts a read.
* Good, because a missed intermediate publish leaves the view stale only until the operation
  releases the lock, and a test that reads the status at that point can catch it deterministically.
* Good, because the remaining mechanisms are the operation lock (owned by the ADR 0102 tests except
  for agent events), the tick-schedule monitor (owned by the refresh and stop boundary tests), and
  one `volatile` reference.
* Neutral, because each publish copies the running rows, retry rows and the bounded recent-event
  lists. Agent events publish once per event, which means copying a few running entries and at most
  50 events per card.
* Bad, because the change touches most methods of `SymphonyOrchestrator` and conflicts with
  [GitHub PR #802](https://github.com/martin-francois/symphony-trello/pull/802), which changes
  `finishTickAndScheduleNext()` and `snapshot()`. Implementing it after that pull request merges
  avoids a large rebase.
* Bad, because publish points are a new rule that contributors must follow, even though forgetting
  one is now harmless for consistency.

### Confirmation

[GitHub issue #815](https://github.com/martin-francois/symphony-trello/issues/815) is done when the orchestrator tests pass unchanged, the ADR 0102 owning tests still
fail against their mutants, `SymphonyOrchestrator` contains no `synchronized (this)` block or
`synchronized` method outside the tick-schedule object, and a rerun of the ADR 0102 mutant list is
recorded in this ADR.

## Pros and Cons of the Options

### Operation lock plus a published read view and a tick-schedule monitor

Writers keep the operation lock, status readers read an immutable view that operations publish, and
the tick scheduling state that refresh callers share gets its own small monitor. This is the design
described in Decision Outcome.

* Good, because it removes the only class of mechanism that no deterministic test can own.
* Good, because Trello call order and operation-level exclusion stay unchanged.
* Neutral, because it brings back the copy-on-write idea that ADR 0051 rejected, at a smaller cost:
  it copies only what status readers see, not every piece of state.
* Bad, because it is a large internal refactor of the product core.

### Keep the current design and accept the untestable sections

Keep ADR 0051 as it is, document the lock rules, and record the 24 reader sections as justified
survivors. [GitHub issue #381](https://github.com/martin-francois/symphony-trello/issues/381)
suggested verifying this design with Lincheck or jcstress.

* Good, because it costs nothing now and the current design passes every deterministic test.
* Bad, because ADR 0102 showed that Lincheck cannot run the orchestrator (it rejects the workflow
  file I/O in `start()`) and that jcstress does not fit an object that needs files, executors and a
  file lock. The 24 sections would stay without an owning test.
* Bad, because the next forgotten monitor section around a new write would again be a silent data
  race, which is the hazard ADR 0051 already listed.

### Single-writer confinement on the scheduler thread

Route every state change through the single-threaded scheduler: worker exits, agent events and
lifecycle calls submit tasks instead of taking a lock, and readers get published snapshots.

* Good, because writers need no lock at all.
* Bad, because the operation lock already gives single-writer behavior without moving work between
  threads, so the gain over the chosen option is only the lock object itself.
* Bad, because agent events must return whether they were accepted, and `stop()` must wait for the
  last operation. Both would block on a submitted task, which adds a deadlock risk while `stop()`
  shuts the scheduler down.
* Bad, because readers would still need the published view, so this option includes the chosen
  design's main change.

### Copy-on-write state for all orchestrator state

Keep all orchestrator state in one immutable object and swap it atomically on every change, as
ADR 0051 considered.

* Good, because readers and writers would both see whole states only.
* Bad, because every mutation site must build a new state object, which is the change size ADR 0051
  rejected, and the audit gives no reason to accept it now: writers are already serialized.
* Bad, because state that no reader sees, such as claims and ignored workers, would pay the copying
  cost too.

## More Information

A throwaway spike counted status reads that happened while another thread held the operation lock.
The orchestrator tests made 81 such reads from 51 places. Most are `waitUntil` polls that would only
succeed a little later if the view were published at the end of the operation. The usage-pause test
named above reads the status from inside the operation's own Trello I/O, which is why the design
keeps intermediate publish points.

[GitHub PR #802](https://github.com/martin-francois/symphony-trello/pull/802) adds an adaptive poll
interval. Its interval state is updated in `finishTickAndScheduleNext()` and read by `snapshot()`, so
in this design it belongs to the tick and its status goes into the published view. Its lock-free
collector of Trello `429` responses lives in the tracker client, is shared by ticks and Codex tool
calls, and needs no change. That pull request also lets a rate-limited read wait up to 30 seconds
while a tick holds the operation lock. `stop()`, worker exits, retry timers and agent events wait
for the operation lock today as well, and this design does not change that.
