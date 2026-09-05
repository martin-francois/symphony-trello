## Summary

- Problem: production concurrency guards were not backed by one repository-wide mutation inventory, and a duplicate worker identity could unregister its replacement.
- Why it matters: an apparently redundant lock, monitor, atomic, concurrent collection, or stale-callback check can be removed without review noticing the race it prevents.
- What changed: audited every production concurrency owner, reused compatible mutation evidence from #381 and #561, added deterministic duplicate-identity, stop-boundary, and cross-process-lock regressions, fixed conditional worker cleanup, and removed concurrency wrappers whose mutants survived.
- What did not change: the orchestrator locking architecture, scheduling policy, Trello behavior, public APIs, configuration, and command output remain unchanged.

## Change Type

- [x] Bug fix
- [ ] Feature
- [ ] Documentation
- [x] Refactor required for this change
- [ ] Chore / infrastructure

## Linked Issue

- Fixes #565
- Related #381
- Related #561

## User-Visible Behavior

None. Internally, cancellation remains attached to the newest run if duplicate worker identities overlap.

## Compatibility Decision

- [x] Compatible: no previously supported, working usage stops working
- [ ] Breaking: previously supported, working usage stops working
- [ ] Unsure: maintainer decision required

Why did you choose this option?
`Because the change fixes internal ownership cleanup, strengthens normal-CI concurrency tests, and removes only synchronization proven redundant without changing any supported external contract.`

If you choose `Breaking`, please fill out the following:

What breaks:
`Breaks: `

Migration path:
`Migration: `

Alternative:
`Alternative: `

If this is not a breaking change, you can leave all three fields blank.

## Commit History in Main

- [x] Combine this pull request into one final commit. The branch commits are review steps and do not
      need to remain separate in `main`. (squash)
- [ ] Keep the individual commits. Each commit is independently meaningful and should remain visible
      in `main`. (rebase)

## Root Cause And Guardrail

- Root cause: concurrency mechanisms accumulated at several owning boundaries without a single mutation record. In `LocalAgentRunner`, unconditional cleanup let an older duplicate identity remove the newer run's cancellation registration.
- Test or guardrail added: deterministic tests now pin conditional worker ownership, stop-before-executor-shutdown ordering, the in-process and cross-process worker locks, and the relevant failure side effects. The matrix below records retained and removed mechanisms.
- If no test was added, why not: N/A.

### Production concurrency inventory

| Owner | Protected invariant and production callers |
| --- | --- |
| `CodexAppServerClient` | Concurrent sessions share command-scoped rate limits atomically; the stdout reader publishes responses, early/late turn completion, terminal failure, and server requests to the single session owner; owner and reader writes cannot interleave. Callers are `runTurn`, `runSession`, `request`, `awaitTurnCompleted`, `handleMessage`, `handleNotification`, `failPending`, and `closeWriter`. |
| `LocalAgentRunner` | `run` registers the currently owned worker thread and `cancel` interrupts that owner; cleanup must not remove a replacement with the same identity. |
| `TrelloHandoffToolHandler` | Agent and orchestrator workpad upserts serialize the complete same-card read/modify/write through the stable striped card lock. |
| `SymphonyOrchestrator` | The operation lock serializes lifecycle, tick, retry/deadline, worker-exit, and agent-event I/O; the instance monitor owns reader-visible state and tick transitions; volatile config/path references support lock-free status reads; futures, generations, target/worker identities, and cancellation ordering reject stale work; the workflow file lock prevents duplicate runtimes. |
| `LocalWorkerManager` | A Guava striped lock prevents same-JVM overlap and a Java file lock prevents another process from starting or stopping the same workflow concurrently. `startWithProcessLock` and `stopWithProcessLock` share the acquisition boundary. |
| `CodexModelDefaultsResolver` | A virtual stdout reader publishes ordered lines and terminal failure through the blocking queue to the request owner. |
| Shell/deployment scripts | Source/caller inspection found PID identity and stale-process safety checks, but no production mutex, `flock`, semaphore, atomic update, background worker pool, or equivalent concurrency owner to mutation-test. Ordinary single-threaded `putIfAbsent`/`compute` deduplication in parsers/setup code was likewise excluded. |

### Mutation matrix

All disposable mutations were restored. `Exit 1` means the named normal-CI test killed the mutant at the stated concurrency assertion; the restored test returned exit 0.

| Protection | Temporary mutation | Killing test | Exit / intended failure |
| --- | --- | --- | --- |
| Shared per-command rate-limit map | `ConcurrentHashMap` to `HashMap` | `atomicallySharesConcurrentSparseRateLimitsWithUsageDeadline` | Exit 1; a concurrent session lost the combined sparse snapshot. Reused from #561. |
| Rate-limit decision monitor | Removed the synchronized snapshot/update boundary | `usageDeadlineWaitsForAnInFlightRateLimitUpdateDecision` (accepted and rejected cases) | Exit 1; the failure deadline raced ahead of the listener decision. Reused from #561. |
| Same-card workpad serialization | Returned a fresh lock per call | `serializesAgentAndOrchestratorWorkpadReadModifyWriteForSameCard` | Exit 1; the second read entered before the first read/modify/write completed. Reused from #561. |
| Orchestrator operation lock: exceptional exit | Removed the worker-exit acquisition | `exceptionalWorkerExitWaitsForAnInFlightOrchestratorOperation` | Exit 1; worker-exit tracker I/O overlapped the active tick. Reused from #561. |
| Orchestrator operation lock: usage deadline | Removed the deadline acquisition | `usageDeadlineWaitsForAnInFlightOrchestratorOperation` | Exit 1; deadline tracker I/O overlapped the active tick. Reused from #561. |
| Retry callback identity | Removed the paired retry-generation checks | `supersededRetryCallbackCannotConsumeReplacementEntry` | Exit 1; a stale callback consumed its replacement. Reused from #561. |
| Pause callback identity | Removed command, generation, and deadline checks | `stalePreviousCommandPauseDeadlineCannotProbeCurrentCommand` | Exit 1; an old command deadline probed current state. Reused from #561. |
| Tracker-qualified runtime ownership | Compared only raw card IDs | `sameRawCardIdAcrossTargetsRunsIndependentlyWithoutCrossRemoval`; `sameRawCardIdAcrossTargetsKeepsRecentEventsIsolated` | Exit 1; one tracker target removed or observed another target's state. Reused from #561. |
| Bound-probe replacement guard | Dropped late-result probe retention | `lateConcurrentUsageResultPreservesTheBoundProbeThatCanClearTheExtendedPause` | Exit 1; the late result displaced the only probe able to clear the pause. Reused from #561. |
| Tick completion monitor/order | Restored the old split clear/consume/schedule transition | `refreshAtTickCompletionBoundaryIsNotOverwrittenByIntervalSchedule` | Exit 1; a boundary refresh was overwritten. Reused from #381. |
| Workflow-path lifecycle ownership | Removed the operation-lock boundary from `setWorkflowPath` | `workflowPathCannotChangeOnceStartHasBegun` | Exit 1; the path changed after startup began. Reused from #381. |
| Stop lifecycle ordering | Moved `markStoppingAndCancelTick` after executor shutdown | `refreshAtExecutorShutdownBoundaryIsANoOp` | Exit 1; boundary refresh raised `RejectedExecutionException`. Added here. |
| Workflow process lock | Removed `acquireWorkflowProcessLock` | `duplicateWorkflowRuntimeFailsBeforeTrelloResolution` | Exit 1; a second runtime reached startup instead of failing before Trello resolution. Confirmed here. |
| Active worker ownership | Replaced conditional `remove(identity, thread)` with unconditional `remove(identity)` | `completedDuplicateIdentityCannotUnregisterNewerActiveWorker` | Exit 1; cancelling the replacement did not interrupt it. Added here. |
| Same-JVM worker lock | Removed the striped lock/unlock boundary | `concurrentStartsForSameBoardReportOnlyOneStartAction` | Exit 1; the second caller failed with `OverlappingFileLockException` instead of waiting. Strengthened here. |
| Cross-process worker lock | Removed `FileChannel.lock` and validity check | `startWaitsForAnotherProcessHoldingTheWorkerLock` | Exit 1; forbidden `platform.start` count was 1 instead of 0 while another JVM held the lock. Added here. |
| App-server request/turn publication | Removed response or completion publication/failure handoff at the owning reader boundary | `failsPromptlyWhenAppServerExitsBeforeRequestResponse`; `handlesTurnCompletedArrivingBeforeAwaiterIsRegistered`; turn failure/cancellation tests | Exit 1; the owner timed out or missed the early terminal event. Existing normal-CI coverage retained. |
| Scheduler/future cancellation and stale ownership | Removed current worker/target/generation/cancellation checks at their owning callback boundaries | stale retry, pause, cross-target, reload, late-result, and stop tests above plus the orchestrator focused suite | Exit 1; stale callbacks consumed replacements, crossed targets, or scheduled after stop. Existing/reused coverage retained. |

Surviving mutants were removed instead of retaining unproved complexity:

- app-server request IDs are now a plain owner-confined `int` rather than `AtomicInteger`;
- turn-completion maps are monitor-owned `HashMap`s rather than `ConcurrentHashMap`s;
- the stdout-reader startup latch was removed because registration/futures already handle either scheduling order;
- `RunningEntry` mutable fields and `tickRunning` are plain monitor/operation-owned fields rather than `volatile`;
- model-default reader failure is a plain field published by the subsequent blocking-queue insertion;
- earlier #561 evidence had already removed the monitor-owned rate-limit `AtomicReference`, an unreachable inner retry-generation comparison, and an unreachable cleanup snapshot comparison.

## Validation

- [x] `./mvnw -q spotless:check verify`
- [x] Installer or script checks, if touched
- [x] Documentation lint, if Markdown changed
- [ ] Manual or live check, if behavior changed

Details:

```text
./mvnw -q spotless:check verify
  pass (full Java 25 suite, static analysis, fuzz/chaos regression gates, and coverage)
pnpm run verify:scripts
  pass (TypeScript check and 65 script tests)
bash -n install.sh uninstall.sh scripts/betterleaks-docker.sh scripts/package-release-assets.sh scripts/check-private-context
  pass
./scripts/semgrep-docker.sh
  pass (426 rules, 449 tracked files, 0 findings)
scripts/check-private-context --worktree
  pass
git diff --check
  pass

Focused restored suite:
./mvnw -q -Dtest=LocalAgentRunnerTest,CodexAppServerClientTest,TrelloHandoffToolHandlerTest,CodexModelDefaultsResolverTest,LocalWorkerManagerTest,SymphonyOrchestratorTest,SymphonyOrchestratorUsageLimitPauseTest test
  pass

Codex review/fix loop:
1. Found PMD AssignmentInOperand and a timing-only file-lock assertion.
2. Split the increment and synchronized the test on the thread reaching the JDK file-lock call.
3. Re-ran the exact cross-process mutant: exit 1 at forbidden start count 1 vs 0; restored test passed.
4. Second review: no actionable correctness issues; reviewer also reran the full Maven verification successfully.
```

## Human Verification

Describe what you tried manually and what result you saw. If the change cannot be tried manually,
explain why.

```text
No live Trello or deployment check was appropriate: this is internal concurrency hardening. The races were exercised deterministically with latches, lifecycle hooks, a separately launched JVM holding the exact worker lock, and disposable production mutations. Each restored path passed the same focused test that killed its mutant.
```

## Review Checklist

- [x] Docs updated, or N/A
- [x] ADR updated for architecture decisions or tradeoffs, or N/A
- [x] PR title and every commit that will remain in `main` use Conventional Commits and are
      release-note ready
- [x] Compatibility and commit-history choices are complete. For a breaking change, the message
      reaching `main` contains both required breaking markers.
- [x] Live E2E/deployment notes included when behavior or deployment changed, or N/A
- [x] Redaction checked: no Trello credentials, Codex auth files, GitHub tokens, private board links,
      account names, private host paths, or deployment-specific paths

## AI Assistance (if used)

- [x] AI-assisted PR
- [x] I confirm I understand what the code does

<details>
<summary>AI prompts / session logs (optional, but super helpful)</summary>

```text
The contributor asked Codex to implement issue #565 in an isolated worktree, in parallel with issue #563, and to run the Codex review/fix loop before completion. Codex inventoried Java and script concurrency owners, inspected callers, reused public mutation evidence from #381/#561, applied five disposable local mutants, added or strengthened deterministic tests for surviving gaps, and removed redundant concurrency wrappers. The first formal review found a PMD violation and a timing-based assertion; both were fixed, the exact mutant was rechecked, and the second review was clean. Full Maven, script, Semgrep, private-context, diff, and commit metadata checks were then run. Repository paths and local environment details were omitted from this trace.
```

</details>
