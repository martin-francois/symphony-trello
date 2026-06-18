---
status: accepted
date: 2026-06-13
decision-makers: ["martinfrancois"]
consulted:
  - "[GitHub issue #378](https://github.com/martin-francois/symphony-trello/issues/378)"
  - "[GitHub issue #377](https://github.com/martin-francois/symphony-trello/issues/377)"
  - "[GitHub issue #373](https://github.com/martin-francois/symphony-trello/issues/373)"
informed: ["repository contributors"]
---

# Sleep-Based Waits Kept As Polling Boundaries

## Context and Problem Statement

After the orchestrator lock split
([ADR 0051](0051-orchestrator-operation-lock-and-state-monitor.md)) removed the internal defect
that made health probes stall, the code base still contains five `sleep`-based waits: the managed
start wait `LocalHealthChecker.waitForSameWorkflow` (200 ms poll, 60 s budget while the process is
alive), the 150 ms delayed re-probe before `LocalHealthChecker.workflowHealth` reports
`PORT_USED`, the Trello 429/5xx retry backoff in `TrelloClient`, the 500 ms follow poll in
`LocalLogTailer`, and the `kill -0` poll in `uninstall.sh` `wait_for_exit`. Each waits on external
state rather than papering over an internal defect
([GitHub issue #378](https://github.com/martin-francois/symphony-trello/issues/378)).
Should any of these polls be replaced with an event-driven mechanism, and which waits are
inherent?

## Decision Drivers

- The verification a wait performs must stay as strong. The start wait proves over HTTP that the
  listener on the port is the expected worker for the expected workflow and board, not just that
  something is listening.
- A replacement must keep behavior on platforms where file watching is unreliable, such as network
  filesystems, where `WatchService` events may not fire or degrade to internal polling.
- A readiness signal would still need the HTTP verification path, so it can only choose when to
  probe; it cannot replace the probe that proves the selected worker is actually serving.
- Two mechanisms for the same wait double the code and the deterministic test surface. That cost
  must be paid back by a real latency or correctness win, not by style preference.

## Considered Options

- Keep documented bounded polls at every remaining wait site
- Worker-side readiness marker watched with `WatchService` for the managed start wait
- `WatchService`-based follow mode for `logs --follow`
- Remove the `PORT_USED` delayed re-probe now
- Schedule probes instead of blocking, or rely on virtual-thread parking
- Adopt a retry/backoff library such as Failsafe or resilience4j

## Decision Outcome

Chosen option: "Keep documented bounded polls at every remaining wait site", because every
candidate event-driven replacement still needs the poll as fallback while saving at most one poll
interval, and the remaining waits have no event to subscribe to at all.

Per wait site:

- `waitForSameWorkflow`: poll kept; the readiness-marker alternative is deferred (see its option
  below). The wait is bounded by process liveness, so a dead worker returns immediately.
- `PORT_USED` delayed re-probe: kept for genuine GC and CPU pauses. A worker can miss the short
  health-probe timeout even when it is still the intended process, and one cheap re-probe avoids
  transiently reporting that local port as occupied by an unrelated process.
- `TrelloClient` retry backoff: inherent. Trello publishes no event when a rate-limit window or
  outage ends; the client honors `Retry-After` when given and otherwise uses exponential backoff
  with jitter.
- `LocalLogTailer` follow poll: kept; the `WatchService` alternative is rejected (see its option
  below).
- `uninstall.sh` `wait_for_exit`: inherent. POSIX shell `wait` only covers child processes, and
  managed workers are not children of the uninstaller, so a bounded `kill -0` poll is the
  standard mechanism. The PowerShell uninstaller uses `Wait-Process`, which waits on process
  handles directly.

### Consequences

- Good, because each wait site now states in a comment why it polls and what would have to change
  before an event-driven mechanism pays off, so the question is settled instead of resurfacing.
- Good, because no dual mechanism is introduced: one code path per wait, one set of deterministic
  tests.
- Bad, because the managed start wait keeps up to 200 ms of avoidable latency per start or
  restart, and `logs --follow` shows new lines up to 500 ms late.
- Bad, because the `PORT_USED` re-probe adds 150 ms to every probe of a genuinely foreign port
  until deployed workers all carry the lock split and the re-probe can be revisited.

### Confirmation

Each wait site carries a comment naming the rationale or pointing to this ADR:
`LocalHealthChecker.waitForSameWorkflow` and the `PORT_USED` re-probe in `workflowHealth`,
`TrelloClient.sleep`, `LocalLogTailer.follow`, and `wait_for_exit` in `uninstall.sh`. The wait
behavior is pinned by existing tests: `LocalHealthCheckerTest.waitForSameWorkflow*` proves the
start wait returns immediately for a dead process and outlasts a slow startup,
`workflowHealthRetriesTransientLocalStatusFailureBeforeReportingPortUsed` proves the single
delayed re-probe, `TrelloClientTest` exercises 429 retry handling, and
`InstallerScriptLifecycleTest` drives the uninstall process-stop path.

## Pros and Cons of the Options

### Keep documented bounded polls at every remaining wait site

Keep each `sleep`-based wait as it is, bound it by a deadline or liveness check as today, and
document at the wait site why polling is the right mechanism there.

- Good, because one mechanism per wait keeps the code and the test surface small.
- Good, because polling behaves identically on local disks, network filesystems, and all
  supported platforms.
- Neutral, because the poll intervals (150-500 ms) are already chosen per site to be invisible
  next to the external latencies they wait on.
- Bad, because each wait can wake up to one interval later than the event it waits for.

### Worker-side readiness marker watched with `WatchService` for the managed start wait

The worker writes a marker file into its state directory once `local-status` is answerable; the
CLI registers a `WatchService` on that directory after spawning the process and probes when the
marker appears.

- Good, because a freshly started worker would be detected almost immediately instead of up to
  200 ms late.
- Bad, because the HTTP probe stays mandatory anyway: only the answer on the port proves the
  listener is the expected worker for this workflow and board. A marker can be stale from a
  previous run or coexist with a foreign process on the port, so the event only changes when to
  probe, not what to verify.
- Bad, because it means two start-wait mechanisms and a marker contract between CLI and worker, to
  save at most one 200 ms interval on a wait dominated by multi-second JVM startup.
- Bad, because `WatchService` degrades to internal polling on some filesystems, so the latency
  win is not even guaranteed.

This option is deferred, not rejected forever: it becomes worth revisiting if the start wait ever
needs to be much tighter than the poll interval, for example for bulk restarts of many workers.

### `WatchService`-based follow mode for `logs --follow`

Register the managed log directory with `WatchService` and read newly appended bytes when modify
events arrive, instead of polling each log file every 500 ms.

- Good, because new log lines would appear with near-zero latency and the process would sleep
  without waking when logs are idle.
- Bad, because rotation handling becomes watcher state: today each pass re-opens every file by
  path, which follows rotated or recreated logs with no extra logic.
- Bad, because file events do not fire reliably on network filesystems, where polling is the
  documented fallback, so the poll loop would have to remain next to the watcher.
- Bad, because 500 ms latency is already below what a human following logs notices.

### Remove the `PORT_USED` delayed re-probe now

Delete the 150 ms delayed re-probe in `workflowHealth`.

- Good, because probes of genuinely foreign ports would answer 150 ms faster and the probe logic
  would lose a branch.
- Bad, because even fixed workers can miss the 500 ms probe timeout during a GC or CPU pause; one
  cheap re-probe avoids misreporting a healthy worker.

Whether the re-probe can be removed or reduced is tracked in
[GitHub issue #393](https://github.com/martin-francois/symphony-trello/issues/393).

### Schedule probes instead of blocking, or rely on virtual-thread parking

Replace `Thread.sleep` between probes with `ScheduledExecutorService` scheduling (or
`CompletableFuture.delayedExecutor` composition), and use `ProcessHandle.onExit()` to react to
worker exit instead of checking liveness per iteration; alternatively keep blocking code but run
it on virtual threads, where `Thread.sleep` parks the virtual thread at near-zero cost.

- Good, because scheduling frees a platform thread in asynchronous components.
- Good, because virtual threads make plain blocking code the supported modern model on Java 25.
- Bad, because the remaining waits sit on synchronous CLI flows where the blocked thread is the
  CLI's own, so neither change saves a meaningful resource.
- Bad, because the managed start wait selects over three conditions (healthy, dead, deadline) and
  the healthy condition still requires polling an HTTP endpoint, so `onExit()` only replaces the
  cheapest of the three checks while adding future-composition structure.

### Adopt a retry/backoff library such as Failsafe or resilience4j

Express the waits as declarative retry policies from an established library instead of hand-rolled
loops, with jitter, attempt caps, and `Retry-After`-aware delay functions.

- Good, because the Trello client retry backoff is exactly the shape these libraries express, and
  the same primitives serve the adaptive-polling work tracked in
  [GitHub issue #58](https://github.com/martin-francois/symphony-trello/issues/58).
- Good, because library policies centralize interrupt, cap, and jitter handling that hand-rolled
  loops must each get right.
- Bad, because the libraries still sleep or schedule internally: they change the ergonomics of the
  wait implementation, not the polling decision this ADR records.
- Bad, because a new runtime dependency for the two health probes and the log follower would
  replace small bounded loops that already handle deadlines and interruption correctly.

Adopting a library for the Trello retry path specifically is tracked separately in
[GitHub issue #403](https://github.com/martin-francois/symphony-trello/issues/403); this ADR keeps
the polling decision independent of that implementation choice.

## More Information

The start wait budget and its liveness bound were introduced in
[GitHub issue #373](https://github.com/martin-francois/symphony-trello/issues/373). The
`PORT_USED` re-probe was added in
[GitHub PR #370](https://github.com/martin-francois/symphony-trello/pull/370) while investigating
[GitHub issue #213](https://github.com/martin-francois/symphony-trello/issues/213), whose root
cause was later fixed by the lock split in
[GitHub issue #377](https://github.com/martin-francois/symphony-trello/issues/377).
