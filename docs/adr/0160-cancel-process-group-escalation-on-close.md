---
status: accepted
date: 2026-11-02
decision-makers: [Contributors]
consulted:
  - "[GitHub issue #1134](https://github.com/martin-francois/symphony-trello/issues/1134)"
informed: [Future maintainers, Contributors]
---

# Cancel process-group escalation when command output pipes close

## Context and Problem Statement

The live bug-bash command runner sends SIGTERM to a cancelled command's process group and schedules
SIGKILL five seconds later. It does not retain that timer. If the group closes before the deadline,
the timer can signal a group number that the kernel has since reused.

The runner also resolves after a separate five-second grace following the leader's exit. Descendants
can still hold output pipes at that point. Which completion event should cancel escalation?

## Decision Drivers

* Prevent delayed signals to a closed command's former process-group number.
* Preserve escalation for descendants that ignore SIGTERM and retain inherited output pipes.
* Preserve the existing resolution deadline after a normal leader exit.
* Verify signal ordering without relying on kernel PID reuse or wall-clock timing.

## Considered Options

* Cancel the escalation timer on the child's close event
* Cancel the escalation timer whenever the command promise resolves
* Leave escalation scheduled after command completion

## Decision Outcome

Chosen option: "Cancel the escalation timer on the child's close event".

Retain one escalation timer per command. Repeated cancellation requests share that timer instead of
creating additional untracked callbacks. Cancel it on `close`, when the child's inherited output
pipes have closed, even if the leader-exit grace already resolved the command. Do not cancel it in
the shared promise-resolution helper: that helper also runs while descendants retain the pipes.

The compatibility decision is no user-facing contract change. This repairs contributor tooling.
CLI, workflow configuration, Trello behavior, deployment, release artifacts, and companion
repositories are unchanged. `SPEC.md` Section 17.9 records the harness invariant and its evidence.

### Consequences

* Good, because closed commands do not leave a delayed group signal behind.
* Good, because cancellation still kills a pipe-holding descendant after the leader's exit grace.
* Good, because abort and timeout requests do not create competing escalation timers.
* Bad, because command resolution and pipe closure remain distinct lifecycle events.

### Confirmation

`scripts/live-bugbash/lib/symphony.test.ts` runs real owned process groups with a controlled clock.
Abort, timeout, and overlapping abort/timeout cases check that no SIGKILL follows pipe closure.
A separate case resolves through the leader-exit grace while a descendant retains the pipes and
checks that SIGKILL still follows. Removing the close-time cancellation must fail the first cases;
cancelling the timer in the resolution helper must fail the descendant case. These tests run in
`pnpm run verify:scripts` and the required script-tests CI job.

## Pros and Cons of the Options

### Cancel the escalation timer on the child's close event

Keep the timer until Node reports that the child and its inherited output pipes have closed.

* Good, because it follows the lifecycle boundary that ends the need for escalation.
* Good, because a leader-exit deadline cannot prematurely cancel descendant termination.
* Bad, because the close handler needs cleanup even when the promise has already resolved.

### Cancel the escalation timer whenever the command promise resolves

Clear the timer in the runner's shared completion helper, including leader-exit grace completion.

* Good, because all timer cleanup would be in one helper.
* Bad, because the promise can resolve while descendants still need the scheduled SIGKILL.

### Leave escalation scheduled after command completion

Always let the delayed SIGKILL attempt run, including after the output pipes close.

* Good, because it requires no timer bookkeeping.
* Bad, because the process-group number can belong to another command at the deadline.

## More Information

The regression intercepts stale SIGKILL attempts instead of forcing PID reuse. Fixtures pass the
original group ID explicitly to descendants and reject IDs less than or equal to one before cleanup.
