---
status: accepted
date: 2026-10-24
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #812](https://github.com/martin-francois/symphony-trello/issues/812)"
  - "[GitHub PR #615](https://github.com/martin-francois/symphony-trello/pull/615)"
  - "[POSIX kill utility](https://pubs.opengroup.org/onlinepubs/9799919799/utilities/kill.html)"
informed: [Future maintainers, Contributors]
---

# Stop Timed-Out Test Process Trees Before Terminating Them

## Context and Problem Statement

`InstallerScriptFixture.run(ProcessBuilder, String, int)` runs installer scripts and other helper
processes for the tests. When a process misses its timeout, the fixture must leave no process from
that tree running, or the leftover keeps files open and uses CPU while later tests run.

[GitHub PR #615](https://github.com/martin-francois/symphony-trello/pull/615) added a cleanup
loop: terminate every current descendant, wait 100 ms, scan again, and stop after one empty scan or
after a one-second discovery window. Then terminate the root. The test
`fixtureTerminatesReplacementDescendantsSpawnedDuringTimeoutCleanup` checked this against a bash root
whose `CHLD` trap starts a new `sleep` whenever a child dies. It failed in the CI `test` job of
several unrelated pull requests, each listed in
[GitHub issue #812](https://github.com/martin-francois/symphony-trello/issues/812), and in 3 of 100
local runs on two CPUs shared with four busy loops.

Bash restores a `CHLD` trap after running it, so the `trap - CHLD` inside the test's trap did not
stick and the root replaced every child the fixture terminated. The scans never came back empty. The
loop ran until the discovery window closed, made a last scan, then terminated the root. A child the
root started between that last scan and its own termination survived: the kernel moved it to the
nearest subreaper, so it was no longer a descendant of the root and no scan could find it. In an
instrumented failing run, the last scan and the root's termination were 12 ms apart. The leftover
`sleep` had the pid right after the one that last scan found, and its parent was the subreaper.

Any order of scanning and terminating leaves such a gap while the processes in the tree keep
running. Which cleanup closes it?

## Decision Drivers

* No process from a timed-out tree may survive cleanup, whatever the tree does in reaction to the
  cleanup.
* The cleanup must not depend on how fast the test JVM runs compared with the processes it cleans
  up, because CI runs two test threads on two vCPUs.
* Tests keep starting processes through a plain `ProcessBuilder`, with no wrapper command around
  each caller's command.
* The cleanup stays inside the test fixture; it does not change production process handling.

## Considered Options

* Stop every process in the tree with SIGSTOP before terminating any of them.
* Keep terminating descendants first and lengthen or repeat the discovery window.
* Terminate the root first, then the descendants found before it.
* Start each process in its own process group and terminate the group.
* Send SIGSTOP through `kill(2)` with the Foreign Function and Memory API.

## Decision Outcome

Chosen option: "Stop every process in the tree with SIGSTOP before terminating any of them",
because a stopped process starts no child and runs no trap, so the tree cannot change between the
fixture's last scan and its termination.

On POSIX systems the fixture runs `kill -s STOP` on the root, then scans the descendants and stops
the ones it has not stopped yet, until a scan finds no new process. After each `kill` it waits until
`ps` reports every signalled process as stopped (`T`) or exited, because the stop takes effect
shortly after `kill` returns, and a process in the middle of a fork can still finish it in that
time. A process that `ps` shows as stopped has finished any fork it was in, so its children are
already in the process table, and the scan after the last stop sees the whole tree. The fixture
then terminates the descendants and the root with `destroyForcibly`, which sends SIGKILL and ends
stopped processes too, and waits for all of them to exit as before.

Windows has no stop signal that Java can send, so on Windows the fixture keeps the loop from
[GitHub PR #615](https://github.com/martin-francois/symphony-trello/pull/615). The
replacement-trap test needs bash and skips on Windows.

### Consequences

* Good, because the tree never sees one of its processes die during cleanup, so a `CHLD` trap or a
  supervisor loop cannot start a replacement.
* Good, because the result does not depend on scheduling: the regression test checks that the trap
  never ran, which holds on every run with the stop and failed on every run with the old loop.
* Good, because a nested process that replaces its children is stopped the same way as the root.
  A second test checks this directly: it stops a three-level tree and reads each state with `ps`.
* Bad, because every timeout on POSIX starts one or more short `kill` and `ps` processes. Timeouts
  happen in a handful of fixture tests and in real hangs, so this costs a few milliseconds in rare
  paths.
* Bad, because the fixture depends on `kill` and `ps` executables on `PATH`. Linux distributions and
  macOS ship both, and the tests already need bash.
* Neutral, because Windows keeps the best-effort loop and its gap.

### Confirmation

This decision remains implemented when
`InstallerScriptTest#fixtureStopsTimedOutProcessTreeBeforeItCanReplaceTerminatedDescendants` passes
for both scenarios and `InstallerScriptTest#fixtureStopsEveryProcessInTheTreeBeforeTerminatingAny`
passes. The root scenario fails on every run against the loop from
[GitHub PR #615](https://github.com/martin-francois/symphony-trello/pull/615). The descendant
scenario also passes when only the root is stopped, because the fixture then terminates the nested
process microseconds after its child and the trap rarely gets to run; it checks that stopping and
terminating a nested tree works, not the order of the stops. The second test covers that order: it
fails when the fixture stops only the root, because `ps` then shows the child and grandchild still
sleeping (`S`) instead of stopped (`T`).

No test pins that the timeout cleanup calls the stop step at all. A cleanup that skips the stop and
terminates the tree at once passes both scenarios, because the root dies microseconds after its
child and its trap rarely runs. That is the same timing race this decision removes, so a review of
`InstallerScriptFixture.terminateProcessTree` has to confirm the call.

## Pros and Cons of the Options

### Stop Every Process In The Tree With SIGSTOP Before Terminating Any Of Them

The fixture runs `kill -s STOP` on the root, then on each descendant a scan finds, waits until `ps`
shows them stopped, and repeats the scan until it finds no process it has not stopped. Only then
does it terminate the tree.

* Good, because it closes the gap between the last scan and the termination.
* Good, because the `kill` utility and its `-s STOP` form are part of POSIX.
* Bad, because it starts a `kill` and at least one `ps` process for each round of stops.

### Keep Terminating Descendants First And Lengthen Or Repeat The Discovery Window

The fixture keeps its loop of terminating the current descendants and scanning again, but waits
longer before it gives up, or runs the loop several times before it terminates the root.

* Good, because it is a small change to the existing loop.
* Bad, because a root that replaces every terminated child keeps the scans non-empty, so any window
  ends with the same last scan, the same gap, and the same leftover child.

### Terminate The Root First, Then The Descendants Found Before It

The fixture scans the descendants once, terminates the root, then terminates the descendants from
that scan.

* Good, because the root dies before it can react to a child's death, with no helper process.
* Bad, because a process in the tree that starts a child on its own between the scan and its
  termination still leaves that child running.
* Bad, because terminating a middle process before its children requires sorting the tree by depth,
  and any process still running during that walk can react to the deaths around it.

### Start Each Process In Its Own Process Group And Terminate The Group

Each test process starts as the leader of a new process group, and the cleanup sends one signal to
the whole group instead of to single processes.

* Good, because one signal to the group reaches every member that stays in it.
* Bad, because `ProcessBuilder` cannot start a process in a new group. Every caller's command would
  need a `setsid` wrapper, which macOS does not ship, and the root pid tests see would change.
* Bad, because a script can leave the group with its own `setsid` and escape the cleanup.

### Send SIGSTOP Through `kill(2)` With The Foreign Function And Memory API

The fixture stops the tree as in the chosen option, but calls the C library's `kill` function from
Java instead of starting the `kill` utility.

* Good, because it needs no helper process.
* Bad, because test JVMs would need `--enable-native-access`, or print a restricted-method warning
  on stderr that tests checking stderr would see.
* Bad, because the SIGSTOP number differs between platforms: 19 on Linux and 17 on macOS.

## More Information

Bash runs a `CHLD` trap once for each child it reaps and installs the trap again afterwards, so
`trap - CHLD` inside the trap body has no lasting effect. Bash 5.3 shows this when the children of
such a root are killed by hand one after another: each kill gets a new `sleep` in return. The
regression test therefore drops `trap - CHLD` and lets the trap replace every child.

The reproduction ran the old test 100 times in one JVM with the JUnit parallelism the build uses,
pinned to two CPUs next to four busy loops. Two such runs each left the replacement alive 3 times.
With the stop, 100 runs each of the old scenario and of both new scenarios had no failure under the
same load.
