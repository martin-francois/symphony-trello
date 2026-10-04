---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #381](https://github.com/martin-francois/symphony-trello/issues/381)"
  - "[GitHub issue #565](https://github.com/martin-francois/symphony-trello/issues/565)"
  - "[GitHub PR #583](https://github.com/martin-francois/symphony-trello/pull/583)"
  - "[PIT mutation testing](https://pitest.org/)"
  - "[pitest-junit5-plugin](https://github.com/pitest/pitest-junit5-plugin)"
  - "[Lincheck](https://github.com/JetBrains/lincheck)"
  - "[jcstress](https://github.com/openjdk/jcstress)"
  - "[ADR 0051](0051-orchestrator-operation-lock-and-state-monitor.md)"
informed: [Future maintainers, Contributors]
---

# Verify Orchestrator Concurrency With Owning Tests And A Mutation Audit

## Context and Problem Statement

[ADR 0051](0051-orchestrator-operation-lock-and-state-monitor.md) splits `SymphonyOrchestrator`
synchronization into an operation lock for long operations and the instance monitor for state that
status endpoints read. The review of that split found several lifecycle races, so
[GitHub issue #381](https://github.com/martin-francois/symphony-trello/issues/381) asked to prove
that the tests catch concurrency mistakes before anyone redesigns the locking.
[Testing](../agents/testing.md) already requires an owning test for every concurrency mechanism that
fails when the mechanism is removed.

Which tool or method shows whether the orchestrator tests own each synchronization element, and
what does it show today?

## Decision Drivers

* Every synchronization element needs either an owning test that fails without it or a written reason
  why no unit test can own it.
* Owning tests must be deterministic and run in normal CI: latches, hooks and fakes, not repeated
  runs or timing luck.
* Required pull request CI stays near five minutes.
* A tool must handle what the orchestrator does: Trello and file I/O, a scheduled executor, virtual
  worker threads and a workflow file lock.

## Considered Options

* Scripted single-element mutants plus deterministic owning tests
* PIT mutation testing
* Lincheck model checking
* jcstress stress tests

## Decision Outcome

Chosen option: "Scripted single-element mutants plus deterministic owning tests", because it is the
only option that removes a monitor section or lock acquisition on its own and runs the real
orchestrator tests against the result. PIT, Lincheck and jcstress are not added to the build.

The audit ran once, by hand, against this change. It is not a CI job and its script is not checked
in: each mutant is an exact text edit of the current source, so the script stops applying after the
next edit to the orchestrator. The mutant list in More Information is detailed enough to repeat the
audit after a redesign. [ADR 0105](0105-orchestrator-published-read-view.md) uses the results to
choose the next locking design.

### Consequences

* Good, because the record shows exactly which synchronization elements the tests own: 13 of 71
  mutants fail a deterministic owning test. Before this change, 9 did, and one of those (L05) failed
  only through a timing side effect.
* Good, because the new owning tests check domain rules (stop cancels what an in-flight tick
  dispatched, a racing retry respects `agent.max_concurrent_agents`, a refresh racing stop never
  schedules after shutdown), so a correct replacement design keeps them green.
* Bad, because 58 mutants survive. Most of them only matter for data races between status readers
  and writers, which no deterministic unit test can reproduce without one test hook per monitor
  section.
* Bad, because the audit has to be repeated by hand after the orchestrator changes.

### Confirmation

The owning tests run in the normal Maven suite. To repeat the audit, apply each mutant below to
`SymphonyOrchestrator.java` on its own, run
`./mvnw -q -Dtest='SymphonyOrchestrator*Test,UsageLimitPauseIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`,
record the failing tests, and restore the file. A mutant counts as killed only if the same owning
test fails for the same reason on every run.

## Pros and Cons of the Options

### Scripted single-element mutants plus deterministic owning tests

A small script removes or weakens exactly one synchronization element (a lock acquisition, a
`synchronized` block or modifier, a `volatile` modifier, an atomic read-modify-write, or a statement
order), runs the orchestrator test classes, records the result and restores the source. Survivors
get a new deterministic test or a written justification.

* Good, because it targets the mechanisms that matter here, which PIT does not mutate.
* Good, because a run takes about 25 seconds per mutant and 30 minutes for all 71.
* Neutral, because the mutant list is written by hand and is only as complete as the inventory.
* Bad, because the script is tied to the exact source text.

### PIT mutation testing

PIT 1.30.0 with pitest-junit5-plugin 1.2.3 mutates bytecode with its standard operators and reruns
the covering tests for each mutant.

* Good, because it works with this build: the plugin ran the JUnit Jupiter 6.1.3 tests through the
  JUnit Platform 6.1.3 launcher that is already on the test classpath, with no extra setup beyond the
  Mockito agent in `jvmArgs`.
* Good, because it finds general test gaps. A sample of the first 15 mutants per class (45 mutants)
  found six gaps, now tracked in
  [GitHub issue #816](https://github.com/martin-francois/symphony-trello/issues/816).
* Bad, because it has no operator that removes a `synchronized` block or modifier. Its void-call
  operator can delete `operationLock.lock()` or `unlock()` alone, which fails with
  `IllegalMonitorStateException` or a deadlock whatever the tests check.
* Bad, because it is slow on this class. The 45-mutant sample took 2 minutes 16 seconds of analysis
  with 4 threads, 13 mutants timed out, and a run capped at 300 mutants per class did not finish in
  19 minutes. Mutants that stop progress make the concurrency tests wait out their 5-second bounds.
  That rules out required CI and makes a full local run impractical.

### Lincheck model checking

Lincheck 3.7 explores thread interleavings of code run inside `Lincheck.runConcurrentTest`.

* Good, because it would find interleavings that hand-written latches miss.
* Bad, because it cannot run the orchestrator. A spike that started an orchestrator and raced
  `requestRefresh()` with `stop()` first reported a livelock inside class initialization
  (`Pattern.compile` loops). With raised loop bounds it failed with "File operations are not
  supported in Lincheck" when `start()` loaded the workflow file.
* Bad, because it adds Kotlin, coroutines and Byte Buddy 1.14 to a test classpath where Mockito
  uses Byte Buddy 1.17.

### jcstress stress tests

jcstress runs small actor methods against a fresh state object millions of times to expose Java
memory model outcomes.

* Good, because it is the right tool for pure visibility questions such as a missing `volatile`.
* Bad, because each orchestrator needs a workflow file, a tracker, executors and a file lock, so it
  cannot be created millions of times.
* Bad, because the visibility-only survivors here are single immutable references. A jcstress test
  for them would test the JDK's `volatile` guarantee, not project logic.
* Bad, because it needs its own build setup and its last release (0.16) is from February 2023.

## More Information

The audit ran on top of commit `0d135edf` with the tests and test hooks from this change. Mutant IDs:
`L` operation lock, `S` `synchronized` method, `B` `synchronized (this)` block in source order
(blocks 5 to 7 belong to test-only helpers and were skipped), `V` `volatile`, `A` atomic
read-modify-write, `O` statement order.

### Killed mutants

| ID | Mutation | Owning test |
| --- | --- | --- |
| L01 | Remove the operation lock from `start()` | `workflowPathCannotChangeOnceStartHasBegun` |
| L02 | Remove the operation lock from `stop()` | `stopWaitsForAnInFlightTickAndCancelsTheWorkerItDispatched` (new) |
| L03 | Remove the operation lock from `setWorkflowPath()` | `workflowPathCannotChangeOnceStartHasBegun` |
| L04 | Remove the operation lock from `tick()` | `usageDeadlineWaitsForAnInFlightOrchestratorOperation`, `workerExitWaitsForAnInFlightOrchestratorOperation`, both new `SymphonyOrchestratorOperationLockTest` tests |
| L05 | Remove the operation lock from `onWorkerExit()` | `workerExitWaitsForAnInFlightOrchestratorOperation[FAILED_RESULT]` (new case) |
| L06 | Remove the operation lock from `onWorkerExitWithoutResult()` | `workerExitWaitsForAnInFlightOrchestratorOperation[EXCEPTION]` |
| L08 | Remove the operation lock from `onDispatchPauseDeadline()` | `usageDeadlineWaitsForAnInFlightOrchestratorOperation` |
| L09 | Remove the operation lock from `onRetryTimer()` | `retryDispatchRacingAPollingTickRespectsTheConcurrencyLimit` (new) |
| S03 | Remove `synchronized` from `scheduleRefreshIfStartedAndIdle()` | `refreshThatPassedTheStartedCheckSchedulesBeforeStopShutsTheSchedulerDown` (new, uses the new `refreshScheduleHookForTests`) |
| S05 | Remove `synchronized` from `finishTickAndScheduleNext()` | `refreshAtTickCompletionBoundaryIsNotOverwrittenByIntervalSchedule` |
| O01 | Mark stopping after both executors shut down | `refreshAtSchedulerShutdownBoundaryIsANoOp` (its hook now runs right after the scheduler shuts down) |
| O03 | Clear `tickRunning`, consume the refresh and schedule in separate sections | `refreshAtTickCompletionBoundaryIsNotOverwrittenByIntervalSchedule` |
| O05 | Mark stopping at the very end of `stop()` | `refreshAtSchedulerShutdownBoundaryIsANoOp` |

Before this change, L02, L09, S03 and O01 survived, and L05 was killed only by timing-dependent
side effects in `usageDeadlineRechecksOnceExtendsOnRepeatAndClearsOnSuccess` and
`lateSameCommandOldTargetUsagePausesCurrentTargetWithoutTransplantingRetry`, which did not fail
in every run.

### Survivors that are redundant today

These 25 mutants change no possible interleaving. 23 of them remove a monitor section that guards
state only operation-lock holders read or write, or that is nested inside another monitor section.
The other 2 reorder statements inside one monitor section. They need no test.
[ADR 0105](0105-orchestrator-published-read-view.md) removes the 23 sections.

| IDs | Element | Why it is redundant |
| --- | --- | --- |
| S06, S10, S11, S12, B22, B29 | `isDispatchPaused`, `isUsageProbeCard`, `isBoundUsageProbe`, `usageWorkpadMessage`, the pause check in `onDispatchPauseDeadline`, the first read in `handleRetryTimer` | Reads by operation-lock holders of state that only operation-lock holders write |
| S08 | `currentRunningEntry` | Same, for `running` and `ignoredWorkers` |
| S13, S14, S15, B20, B21 | `queueUsageWorkpadCleanup` (three overloads), both sections in `retryPendingUsageWorkpadCleanup` | `pendingUsageWorkpadCleanup` has no lock-free reader |
| S16 | `scheduleDispatchPauseDeadline` | `dispatchPauseTimer` has no lock-free reader |
| S18, B12, B18 | `removeClaim`, the unclaim after a failed prepare, `completeWorkerExit` | `claimed` has no lock-free reader |
| S19, S20 | `removeExpiredIgnoredWorkers`, `trimIgnoredWorkers` | `ignoredWorkers` has no lock-free reader |
| B17 | `completed.add` in `handleWorkerExit` | `completed` is bookkeeping that nothing reads |
| S17, S21 | `promoteToUsageProbeRetry`, `scheduleTick` | Every caller already holds the monitor |
| B01, B04 | The `started` check in `start()`, the body of `setWorkflowPath()` | Operation-lock holders only; the operation lock is owned by L01 and L03 |
| O02, O04 | Reorder statements inside `markStoppingAndCancelTick()` or `finishTickAndScheduleNext()` | The order inside one monitor section is invisible to other threads |

### Survivors that only protect status readers

These 24 elements make `snapshot()` and `cardDetails()` see consistent maps and entries while an
operation writes them. Without one, a status read races with the write. The race is real: in one of
three runs, B16 made a `snapshot()` poll throw `ConcurrentModificationException`. It survived the
other two. A deterministic test would need a hook inside every write section that pauses
the writer while a reader runs. That would pin the monitor itself rather than a domain rule, and it
would mean one hook per section. [ADR 0105](0105-orchestrator-published-read-view.md) replaces these
sections with a published read view instead.

| IDs | Element |
| --- | --- |
| S22, S23 | `synchronized` on `snapshot()` and `cardDetails()` |
| S07 | `applyAgentEvent` |
| S09 | `bindUsageProbe` |
| B03 | State teardown in `stop()` |
| B08 | `applyValidReload` |
| B09 | Running card update in `reconcileRunningCards` |
| B10 | Entry removal in `terminateRunning` |
| B11, B13, B14, B15 | Claim and retry removal, `running.put`, and both `running.remove` failure paths in `dispatch` |
| B16 | Entry removal in `handleWorkerExit` |
| B19 | `installOrExtendUsagePause` |
| B23, B24, B25, B26, B27, B31 | `selectUsageProbeAtDeadline`, `probeOneCandidateAtUsageDeadline`, `rearmUsagePauseWithoutProbe`, `clearUsagePause`, `rearmUsageProbeWithoutResult`, `retireUsageProbeWithoutResult` |
| B28, B30, B32, B33 | `scheduleRetryAt`, the generation-checked removal in `handleRetryTimer`, `deferRetryForDispatchPause`, `rescheduleRetryEntry` |

### Survivors outside the operation lock

These 9 elements order lifecycle state with threads that do not hold the operation lock: refresh
callers, the workflow watcher, `SymphonyMain`, and agent events.

| ID | Mutation | Why no deterministic test owns it |
| --- | --- | --- |
| L07 | Remove the operation lock from `onAgentEventAndReportAccepted()` | The event path does no I/O, and its reads and writes already run in monitor sections. The only new outcome is that an event racing the termination of its own worker is still counted and reported as accepted, as if it had arrived a moment earlier. What remains is a data race on the `running` lookup and on `lastEventAt`, which `reconcileStalls` reads. |
| S01 | Remove `synchronized` from `markStoppingAndCancelTick()` | The next monitor section in `stop()` still waits for an in-flight refresh section, so the refresh-after-shutdown failure cannot happen. What remains is the visibility of `started`. |
| S02 | Remove `synchronized` from `isStarted()` | Visibility of `started` to `SymphonyMain` only. |
| S04 | Remove `synchronized` from `beginTick()` | A refresh that misses `tickRunning = true` schedules one extra immediate tick, which then waits for the operation lock. Same outcome as a refresh right after the tick. |
| B02 | Remove the section that sets `started` and schedules the first tick | The first tick is scheduled at zero delay either way, and `scheduleTick` keeps its own monitor. |
| V01, V02 | Drop `volatile` from `config` or `workflowPath` | Java memory model visibility of one immutable reference to the lock-free local-status getters. |
| A01, A02 | Split `getAndSet(false)` on the refresh or reload flag into `get()` and `set(false)` | The lost-update window lies inside one method with no callback a test could pause in. |

### PIT setup

The PIT runs used a temporary Maven profile that is not checked in: `pitest-maven` 1.30.0 with
`pitest-junit5-plugin` 1.2.3, `targetClasses` `ch.fmartin.symphony.trello.orchestrator.SymphonyOrchestrator*`,
`targetTests` `ch.fmartin.symphony.trello.orchestrator.*Test`, `threads` 4, `timeoutConstant` 2000,
the feature `+CLASSLIMIT(limit[15])`, and `jvmArgs` with the Mockito agent and
`-XX:+EnableDynamicAgentLoading`. Run it after `test-compile` in the same Maven invocation so the
dependency plugin sets the Mockito agent path.
