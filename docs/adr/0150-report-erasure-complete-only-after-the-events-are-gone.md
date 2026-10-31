---
status: accepted
date: 2026-10-31
decision-makers: [François Martin, Claude]
consulted:
  - "[Verified deletions that leave events](../telemetry-erasure-implementation.md#verified-deletions-that-leave-events-2026-10-31)"
  - "[PostHog issue 126750](https://github.com/PostHog/posthog/issues/126750)"
informed: [Contributors, Maintainers]
---

# Report erasure complete only after the events are gone

[ADR 0082](0082-authenticated-erasure-with-reporting-periods.md) lets the native service report
an erasure as complete once PostHog's deletion record for the bound person reads `completed` with
`delete_verified_at` and the profile is gone. This decision adds a condition to that rule, says
what the user is told instead, and separates hosted verification from production activation.

## Context and Problem Statement

The hosted lifecycle runs on the TEST project requested six person deletions between September
and October 2026. PostHog verified five of them and deleted their events. It verified the sixth
in the same batch as one of the five, yet that person's one event was still returned by uncached
queries 26 days later, by analytics ID and by person UUID, with nothing in the API that sets it
apart from the deleted ones. PostHog's own source says a deletion run can mark requests verified
without proving the rows are gone, and a verified request cannot be queued again because PostHog
keeps one deletion record per person. The case is reported as
[PostHog issue 126750](https://github.com/PostHog/posthog/issues/126750).

The handler set `complete` from the record and the missing profile alone. In production that
would tell a user their data is erased while a report remains. The production gate required zero
events in both periods, so a leftover PostHog caused would hold production back for as long as
PostHog kept producing them. How should the service decide completion, what should the user be
told, and what should the gate measure?

## Decision Drivers

- Never tell a user that data is erased while a report is still stored.
- Keep the service inside PostHog, within the five fetches a Hog function may make.
- Make the outcome final: a verified request cannot be re-queued, so waiting changes nothing.
- Ship on this service's correct handling, not on PostHog's deletion reliability.
- Keep user-facing text simple: one promise, deletion within 30 days of the request.
- Keep the public status opaque, the client's exit-code conventions, and the lifecycle evidence bound
  to the exact handler that ran.

## Considered Options

- Count the period's events in the handler, report `incomplete`, and pass the gate on either
  outcome
- Keep trusting the deletion record
- Keep the status at `accepted` until a later count reaches zero
- Wait for PostHog to delete reliably before shipping
- Count the events in the client

## Decision Outcome

Chosen option: "Count the period's events in the handler, report `incomplete`, and pass the gate
on either outcome".

After the deletion record reads `completed` with `delete_verified_at` and the profile is gone, the
handler runs one more uncached HogQL count, `distinct_id = <analytics id> OR person_id = <bound
person uuid>`, with the service credential's existing `query:read` scope. Zero gives `complete`.
Anything else gives `incomplete`, a final status that `ack` does not archive and that no later
request recomputes, so the flag stays for monitoring until the maintainer archives it. A cached or malformed count answer leaves the status unchanged, as the
empty-period check already does. The status path then uses all five async steps.

The client stores `incomplete` as a final phase next to `complete` and `refused`. `erase-status`
says the erasure is still being processed and the remaining data is deleted within 30 days of the
request; it never claims completion and exits 0, because the user has nothing to do. `enable`
starts a new reporting period and repeats the 30-day promise for the previous one. The privacy
text and the specification promise the same 30 days and say nothing about PostHog's batches or
about manual removal; that explanation lives in the runbook, the implementation notes and here.

`scripts/posthog-infra erasure-flags` reports an `incomplete` flag as needing the maintainer and
prints the action: ask PostHog support to delete the person's remaining events, citing the
upstream issue. It never archives such a flag; the maintainer archives it after a fresh count is
zero. No automated removal of leftovers is built now; that is a later decision.

The lifecycle observation keeps every check on admission, forgery, lost-response retries, partial
recovery, the canary, the replay and the handler hash binding. For each period it now accepts
either outcome as correct handling: after PostHog reports the deletion verified, the public status
is `complete` with no event left, or `incomplete` with events left, the profile is absent in both
cases, and an incomplete flag survives `ack` and the replay. The ledger records the outcome of
each period, so a pass says what it saw.

On 2026-11-01 the maintainer confirmed that production erasure is part of the enabled final
configuration. Neither PostHog's batch timing nor completion of the two-period hosted observation
is a merge, release or activation prerequisite. This supersedes the activation lock in ADR 0082.
OpenTofu requires valid per-project credentials and versioned master keys, and deployment readback
checks the enabled canonical handler and identity merge filter. The ordinary tests remain required.
The hosted lifecycle runner continues to record observed outcomes and handler hashes, but its ledger
does not authorize deployment. Pending runs remain pending; no successful result is fabricated.
Keeping the activation lock was rejected because it leaves the final service unavailable for a
provider-controlled wait. Replacing it with a fabricated passing ledger was rejected because it
would misrepresent verification. The accepted tradeoff is that production is enabled before the
current hosted run has observed every final outcome; deterministic tests cover those paths and the
existing pending, incomplete and maintainer-escalation behavior remains in force.

### Consequences

- Good, because `complete` now means what the privacy text says: no report of the period is
  stored, and the user's promise is one number they can hold the maintainer to.
- Good, because a PostHog leftover no longer blocks production. Tests cover the service's handling,
  and monitoring shows the maintainer what to escalate.
- Good, because the leftover is visible to the maintainer and to the flag review, with the
  support request spelled out.
- Bad, because the status path spends its last async step on the count; a later change to that
  path must drop a fetch first.
- Bad, because the 30-day promise depends on a PostHog support request the maintainer cannot
  speed up; the maintainer must open it as soon as the flag appears.
- Bad, because the user is not told that their data is still stored beyond "still being
  processed"; the specification records that this is the chosen wording.
- Neutral, because hosted lifecycle evidence remains bound to its handler and reports its actual
  coverage without controlling production activation.

### Confirmation

- `TelemetryErasureContractTest` requires the handler, the flag review and the runner to use
  exactly the client's status values, so `incomplete` exists on every side or the build fails.
- `TelemetryErasureTest` covers the final phase: exit 0, the 30-day wording without a completion
  claim or a provider name, no acknowledgment, no retry, and a new period after `enable`.
- `scripts/erasure-lifecycle-live.test.ts` pins the handler's count and its decision, the runner's
  rule that `complete` requires zero events, and which outcome keeps the flag after `ack`.
- The lifecycle run in the TEST project records the handler hash and each period's actual outcome.
  OpenTofu tests prove that enabled production plans without a lifecycle ledger, while missing or
  invalid credentials still fail. Deployment readback confirms the canonical handler is enabled.

## Pros and Cons of the Options

### Count the period's events in the handler, report `incomplete`, and pass the gate on either outcome

The handler keeps PostHog's record as the acceptance signal and adds its own uncached count by
exact analytics ID and bound person UUID before it publishes `complete`. A non-zero count
publishes a final `incomplete` that stays for monitoring. The gate passes a period on either
outcome, as long as the service handled it correctly.

- Good, because the completion claim rests on the data, not on a record that can be wrong.
- Good, because it reuses the query the empty-period path already makes, with no new scope.
- Good, because production depends on what this repository controls.
- Bad, because it adds a fetch to the status path and reaches the five-fetch limit.
- Bad, because a leftover becomes a maintainer task with a deadline.

### Keep trusting the deletion record

Leave the handler as it was and treat `delete_verified_at` as proof.

- Good, because nothing changes and the status path keeps a spare async step.
- Bad, because one of six verified deletions in the test project left an event behind, and the
  privacy text would be false for that user.

### Keep the status at `accepted` until a later count reaches zero

Report `accepted` while events remain and let the client keep polling.

- Good, because the client needs no new state.
- Bad, because a verified request cannot be queued again: the event stayed for 26 days and the
  client would poll every six hours for nothing, while `erase-status` never explains why.

### Wait for PostHog to delete reliably before shipping

Keep the gate's requirement of zero events in both periods and start new runs until two periods
in a row see every event gone.

- Good, because a pass would mean the user's data was in fact deleted in the test.
- Bad, because the gate would then measure PostHog, which the repository cannot fix, and the
  upstream issue has no fix date; a pass on one week says nothing about the next.
- Bad, because the handler's handling of a leftover, the case that matters for the user, would
  never be exercised by the gate.

### Count the events in the client

Let the Java client query the events itself before it stores completion.

- Bad, because the client holds only the public capture token, which cannot run queries, and a
  management credential in the client is out of the question.

## More Information

- [Implementation and activation gate](../telemetry-erasure-implementation.md)
- [Maintainer runbook, incomplete automatic erasure](../telemetry-maintainer-runbook.md#incomplete-automatic-erasure)
- [ADR 0082](0082-authenticated-erasure-with-reporting-periods.md)
