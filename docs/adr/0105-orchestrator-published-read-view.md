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

[GitHub issue #815](https://github.com/martin-francois/symphony-trello/issues/815) implemented the
decision. ADR 0051 is superseded by this ADR. More Information records how the code follows the
design and the mutation matrix of the new code.

The design:

* Writers keep the operation lock. Start, stop, ticks, retry timers, pause deadlines, worker exits
  and agent events run under it exactly as before.
* Status readers (`snapshot()`, `cardDetails()`, `isStarted()`) and the local-status getters
  (`selectedBoardId()`, `selectedConfiguredBoardId()`, `selectedWorkflowPath()`,
  `cardIdentifierPrefix()`) read one `volatile` reference to an immutable read view, `StatusView`:
  running rows, retry entries, recent events, token totals, ended runtime, the dispatch pause, rate
  limits, the effective config and the started flag. They take no lock.
* An operation publishes a new view after it changes reader-visible state when more Trello, worker
  or filesystem I/O follows before it releases the operation lock, and always when it releases the
  lock. Some intermediate points are part of the contract:
  `typedUsageLimitInstallsFallbackPauseBeforeTrelloIoAndGatesManualDispatch` requires the usage
  pause to be visible before the worker-exit Trello I/O.
* `started`, `tickRunning`, the tick timer and the refresh flag live in one small object,
  `TickSchedule`, with its own monitor. `requestRefresh()` uses only that monitor, so it still never
  waits for a tick.
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
  `finishTickAndScheduleNext()` and `snapshot()`. More Information describes where its polling
  state goes.
* Bad, because publish points are a new rule that contributors must follow, even though forgetting
  one is now harmless for consistency.

### Confirmation

`SymphonyOrchestrator` contains no `synchronized (this)` block and no `synchronized` method outside
`TickSchedule`. The ADR 0102 owning tests still fail against their mutants, and
`SymphonyOrchestratorStatusViewTest` owns the publish points. To repeat the audit, apply each mutant
in More Information on its own and run
`./mvnw -q -Dtest='SymphonyOrchestrator*Test,UsageLimitPauseIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`,
as ADR 0102 describes.

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

### How the code follows the design

These details were decided while implementing
[GitHub issue #815](https://github.com/martin-francois/symphony-trello/issues/815):

* `publishStatus()` builds the view from the operation state and fails with
  `IllegalStateException` when the caller does not hold the operation lock, so a publish from a
  thread that does not own the state fails at once instead of racing a writer.
* `publishStatusAndUnlock()` ends every operation. The worker-exit operation publishes before its
  test completion hook instead, because tests read the status right after that hook.
* The effective config is no longer `volatile`. Operation-lock holders read and write the field.
  The only other reader is the workflow watcher thread, which reads it once when it starts; `start()`
  starts that thread under the operation lock after it set the config, so `Thread.start()` orders
  the read. The lock-free getters read the config of the view. Startup publishes once right after it loads the
  workflow, so a local-status probe sees the selected board during the startup Trello I/O, as it
  did before.
* `isStarted()` reads the view. It turns true when `start()` returns and false once `stop()` has
  torn down its state. Operations that check the lifecycle read `TickSchedule` instead.
* The refresh flag is a plain field under the `TickSchedule` monitor, so the atomic flag that
  ADR 0102 mutated as A01 is gone.
* The view keeps the internal dispatch pause and the retry entries, which are immutable records.
  `snapshot()` shows the pause only while it belongs to the current Codex command, as before. The
  retry-timer and usage-deadline test callbacks read these from the view without a lock. A first
  version read them under the operation lock, and then the deadline lock mutant L08 survived: the
  callback itself waited for the in-flight tick, so the test no longer checked the deadline's own
  lock.
* `dispatch()` does not publish after it drops a pending retry of the card it dispatches. Cards with
  a pending retry are claimed, and every dispatch path skips claimed cards, so the publish would
  never run.
* Each publish copies the running rows, the retry entries and at most 50 recent events for each
  running or retrying card. Other cards' events stay in the operation state and are not copied.

### Mutation matrix of the read view

The harness from ADR 0102 ran again against the code of
[GitHub issue #815](https://github.com/martin-francois/symphony-trello/issues/815): one mutant at a time, all orchestrator
test classes, one Maven run per mutant. Every mutant ran once more after the code review, and the
mutants that a new test kills ran at least twice with the same owning test. 27 of 48 mutants are killed. The 24 reader-only and 23 redundant monitor sections of
ADR 0102 no longer exist. Mutant IDs: `L` operation lock, `T` `TickSchedule` monitor method, `P`
intermediate publish, `E` end-of-operation publish, `O` statement order, `V` `volatile`, `A` atomic
read-modify-write, `G` guard. There is no P06: it would remove the publish in `dispatch()` after a
pending retry is dropped, which the code does not have (see above). Lock mutants also disable the guard in `publishStatus()` and make
`unlock()` tolerate a lock that is not held, so they fail only through the tests.

#### Killed mutants

| ID | Mutation | Owning test |
| --- | --- | --- |
| L01 | Remove the operation lock from `start()` | `workflowPathCannotChangeOnceStartHasBegun` |
| L02 | Remove the operation lock from `stop()` | `stopWaitsForAnInFlightTickAndCancelsTheWorkerItDispatched` |
| L03 | Remove the operation lock from `setWorkflowPath()` | `workflowPathCannotChangeOnceStartHasBegun` |
| L04 | Remove the operation lock from `tick()` | `usageDeadlineWaitsForAnInFlightOrchestratorOperation`, both `SymphonyOrchestratorOperationLockTest` tests and others |
| L05 | Remove the operation lock from `onWorkerExit()` | `workerExitWaitsForAnInFlightOrchestratorOperation[FAILED_RESULT]` |
| L06 | Remove the operation lock from `onWorkerExitWithoutResult()` | `workerExitWaitsForAnInFlightOrchestratorOperation[EXCEPTION]` |
| L08 | Remove the operation lock from `onDispatchPauseDeadline()` | `usageDeadlineWaitsForAnInFlightOrchestratorOperation` |
| L09 | Remove the operation lock from `onRetryTimer()` | `retryDispatchRacingAPollingTickRespectsTheConcurrencyLimit` |
| T04 | Remove `synchronized` from `TickSchedule.requestRefresh()` | `refreshThatPassedTheStartedCheckSchedulesBeforeStopShutsTheSchedulerDown`, `refreshAtTickCompletionBoundaryIsNotOverwrittenByIntervalSchedule` |
| T07 | Remove `synchronized` from `TickSchedule.finishTickAndScheduleNext()` | `refreshAtTickCompletionBoundaryIsNotOverwrittenByIntervalSchedule` |
| O01 | Mark stopping after both executors shut down | `refreshAtSchedulerShutdownBoundaryIsANoOp`, `stopShowsTheStoppedStateWhileItCancelsWorkers` |
| O05 | Mark stopping at the very end of `stop()` | `refreshAtSchedulerShutdownBoundaryIsANoOp`, `stopShowsTheStoppedStateWhileItCancelsWorkers` |
| P01 | Remove the publish in `start()` after the workflow load | `localStatusShowsTheSelectedBoardWhileStartupCleanupFetchesTerminalCards` |
| P02 | Remove the publish in `stop()` after the state teardown | `stopShowsTheStoppedStateWhileItCancelsWorkers` |
| P03 | Remove the publish in `applyValidReload()` | `reloadedRoutingIsVisibleWhileTheReloadTickReconcilesRunningCards` |
| P04 | Remove the publish after a running card update in `reconcileRunningCards()` | `refreshedCardStateIsVisibleWhileTheTickDispatchesTheNextCard` |
| P05 | Remove the publish after the entry removal in `terminateRunning()` | `terminatedCardLeavesTheRunningRowsBeforeItsWorkerIsCancelled` |
| P07 | Remove the publish after `running.put` in `dispatch()` | `workerSeesItsCardRunningWhileTheDispatchingTickContinues` |
| P11 | Remove the publish after the entry removal in `handleWorkerExit()` | `workerExitRefreshSeesTheCardNoLongerRunning` |
| P12 | Remove the publish in `installOrExtendUsagePause()` | `typedUsageLimitInstallsFallbackPauseBeforeTrelloIoAndGatesManualDispatch`, `rearmedUsagePauseIsVisibleBeforeItsWorkpadUpdate` |
| P13 | Remove the publish in `rearmUsagePauseWithoutProbe()` | `rearmedUsagePauseIsVisibleBeforeItsWorkpadUpdate` |
| P14 | Remove the publish in `clearUsagePause()` | `clearedUsagePauseIsVisibleBeforeItsWorkpadCleanup` |
| P15 | Remove the publish in `scheduleRetryAt()` | `scheduledRetryIsVisibleWhileTheTickDispatchesTheNextCard` |
| P16 | Remove the publish after the retry removal in `handleRetryTimer()` | `retryTimerCardRefreshSeesTheRetryLeaveTheQueue` |
| E00 | `publishStatusAndUnlock()` only unlocks | `cardDetailsAndLifecycleReadsAnswerWhileATickIsBlockedInsideTrelloFetch`, `runningSnapshotCountsContinuationTurns` and others |
| E01 | Unlock without publishing at the end of `start()` | `cardDetailsAndLifecycleReadsAnswerWhileATickIsBlockedInsideTrelloFetch` (`localStatusShowsTheSelectedBoardWhileStartupCleanupFetchesTerminalCards` fails too unless the first tick publishes before it reads) |
| E06 | Unlock without publishing at the end of an agent event | `runningSnapshotCountsContinuationTurns`, `sameRawCardIdAcrossTargetsKeepsRecentEventsIsolated` and others |

#### Survivors

| ID | Mutation | Why no deterministic test owns it |
| --- | --- | --- |
| L07 | Remove the operation lock from `onAgentEventAndReportAccepted()` | Unchanged from ADR 0102. The event path does no I/O; the only new outcome is a data race on the `running` lookup and `lastEventAt`. |
| T01 | Remove `synchronized` from `TickSchedule.isStarted()` | Every caller holds the operation lock, and so does every writer of `started`, so the operation lock already orders them. The monitor stays because refresh callers share the field. |
| T02 | Remove `synchronized` from `markStartedAndScheduleFirstTick()` | Same as B02 in ADR 0102: the first tick is scheduled at zero delay either way, and a refresh that misses `started` only records its flag for that tick. |
| T03 | Remove `synchronized` from `markStoppingAndCancelTick()` | Same as S01 in ADR 0102: the publish after the teardown in `stop()` reads `started` through the monitor, so it still waits for an in-flight refresh section before the scheduler shuts down. |
| T05 | Remove `synchronized` from `beginTick()` | Same as S04 in ADR 0102: a refresh that misses `tickRunning = true` schedules one extra tick, which waits for the operation lock. |
| T06 | Remove `synchronized` from `consumeRefreshRequest()` | Same as A01 in ADR 0102: the lost-update window lies inside one method with no callback a test could pause in. |
| T08 | Remove `synchronized` from `scheduleImmediateTickIfStarted()` | It runs under the operation lock. Racing a refresh can at worst leave one extra zero-delay tick, which waits for the operation lock. |
| V01, V02 | Drop `volatile` from `statusView` or `workflowPath` | Java memory model visibility of one immutable reference, as V01 and V02 in ADR 0102. |
| A02 | Split `getAndSet(false)` on the reload flag | Unchanged from ADR 0102. |
| G01 | Remove the operation-lock guard in `publishStatus()` | An assertion: no code path publishes without the lock, so no test can reach it. |
| P08, P09 | Remove the publish after a prompt render or worker submission failure in `dispatch()` | The release Trello call follows, then `scheduleRetryAt()` publishes. No test reads the status during that release; the view stays stale only for that call. |
| P10 | Remove the publish at the end of `onWorkerExit()` | Every reader-visible change of a worker exit is already published by P11, P12, P14 or P15. A stale worker exit changes nothing readers see. |
| P17 | Remove the publish in `retireUsageProbeWithoutResult()` | The workpad cleanup Trello call follows. No test reads the status during it; the end-of-operation publish covers it. |
| E02 to E05, E07, E08 | Unlock without publishing at the end of `stop()`, `setWorkflowPath()`, `tick()`, `onWorkerExitWithoutResult()`, the usage deadline or the retry timer | Each operation's intermediate publishes already cover every reader-visible change the tests make. E00 shows that the end-of-operation publish is needed as a whole. |

### Polling state from adaptive polling

[GitHub PR #802](https://github.com/martin-francois/symphony-trello/pull/802) adds an adaptive
poll interval and was still open when this ADR was updated. It fits this design as follows:
`tick()` computes the next delay under the operation lock, where `AdaptivePollInterval.nextDelay`
runs and the tracker's rate-limit pressure is drained, and passes it to
`TickSchedule.finishTickAndScheduleNext`, which still chooses between that delay and an immediate
refresh tick. The polling status becomes one more `StatusView` component that `publishStatus()`
fills and `StatusView.snapshot()` copies into `RuntimeSnapshot`. A tick always ends with
`publishStatusAndUnlock()`, so the status shows the new interval as soon as the tick ends.
`AdaptivePollInterval` is then confined to operation-lock holders and needs no monitor.
