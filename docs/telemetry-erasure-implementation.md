# Native erasure implementation and activation gate

[ADR 0082](adr/0082-authenticated-erasure-with-reporting-periods.md) records the approved design.
The client and native handler use HMAC ownership and separate reporting periods. No second
backend is required. Production activation remains disabled.

## Protocol

The issuer chooses the installation UUID and derives its credential with HMAC-SHA-256 over
`symphony-trello/owner/v1|<audience>|h1-<key-version>-<installation-uuid>`.
The master and derived credential are lowercase hex strings used as UTF-8 HMAC keys.
Issuance returns the credential over HTTPS. The client saves it before reporting.

Subsequent requests use canonical JSON with a single `payload` string. Its fields are
`h2|action|audience|subject|period|operation|issued|expires|mac`. The MAC covers the preceding
eight fields, including separators. Validity is at most 900 seconds with 60 seconds of clock
skew. The server rejects extra fields, unknown versions and malformed identifiers. It derives
the target from the authenticated installation and period; callers supply no person UUID.

PostHog sources return before external fetches complete and allow only five async steps.
The first invocation persists an immutable person binding in private flag metadata, then
queues event deletion with the profile retained, within four async steps. A later request
confirms the matching queue record before removing the checked profile. Retries recover
interrupted work without moving the event-deletion cutoff. Subsequent status requests update
the public status flag. A 201 receipt means queued, not accepted or complete.
When no profile exists, a fresh uncached exact-ID query must prove there are no retained events
before the service records completion. An unsuccessful first capture therefore does not strand
the installation indefinitely. The flag for such an empty result has the private name
`symphony-erasure-empty-v2|<analytics id>`, so cleanup can query that ID again for late events.
Flags named `symphony-erasure-empty-v1` come from the earlier handler and carry no ID. A `status`
request whose flag is missing takes the same path as `erase`. Archiving an empty or complete flag
therefore never strands a client: its next request recreates the result. The handler skips the
flag update when the status has not changed.
Once the bound person's deletion record reads `completed` with `delete_verified_at`, the same kind
of fresh uncached count, over the exact analytics ID or the bound person UUID, must return zero
before the service records `complete`. Otherwise it records `incomplete`, a final state that `ack`
does not archive: PostHog keeps one deletion record per person, so a verified request cannot be
queued again, and the leftover goes to the maintainer. That status path uses all five async steps.

The flag key is `erasure-` followed by HMAC-SHA-256 over
`symphony-trello/status/v1|<audience>|<period>`, using the installation credential. Public
`/flags?v=2` evaluation returns only the opaque key and status. A request to the TEST project
with its public token on 2026-10-05 returned, per flag, the key, `enabled`, the status as
`variant`, a generic match reason, and metadata with a numeric id, a version and a null
`description`. The flag name, which holds the analytics ID, was absent from the response.
Management credentials alone
write it. A completed result is saved locally before one best-effort flag archival request. The client then stops maintenance traffic.
Failure to archive does not undo completion. `scripts/posthog-infra erasure-flags` removes
leftovers before PostHog's 2,000 non-deleted flag limit (see
[Deployment and operations](#deployment-and-operations)). Missing status never authorizes resume.

The client waits until one hour after a dispatched heartbeat's drain deadline before its first
profile lookup, so a first heartbeat that PostHog is still ingesting is not taken for an empty
period. Workers then retry at half the time since the request, between one minute and six hours.
`erase-status` checks immediately after the heartbeat has settled. After a refusal, `enable` starts a
new reporting period and leaves the refused data to the maintainer.

The installation keeps its credential and registration date. Explicit enable after completion
creates a fresh period. Ordinary disable/enable preserves it. An old request cannot address
that new period. Restoring a state backup taken before erasure is unsupported: the client
cannot detect it and would report under the erased analytics ID again, which then needs
maintainer-assisted erasure.
Existing UUID-only installations retain their state and use maintainer assistance.

## Evidence and remaining gate

Hosted TEST checks on 2026-09-22 established native issuance, cross-runtime key derivation,
forgery rejection, immutable person binding, asynchronous deletion acceptance, public status
readback and survival of an unrelated canary profile. The source was disabled and archived
after the run. The deletion job continues within PostHog.

Physical event deletion was pending at the time of that observation. It is not a lifecycle
pass. The durable runner below records each stage and can resume after PostHog's job completes.
Production activation requires `TWO_PERIOD_LIFECYCLE_PASS`, including profile absence,
lost-response retries, partial profile-deletion recovery, canary event survival, a replay of the
first period while the second exists, and correct handling of each period's provider outcome.
The gate tests this service, not whether PostHog deletes reliably (decided 2026-10-31, see
[ADR 0150](adr/0150-report-erasure-complete-only-after-the-events-are-gone.md)): after PostHog
reports a period's deletion verified, the public status must be `complete` with no event left, or
`incomplete` with events left and the flag retained through `ack`; the ledger records which
outcome each period saw. Provider completion timing is outside this repository's control. The partial-failure fixture
queues deletion with `keep_person=true`, then verifies that the native service removes the
remaining profile. This tests the failure postcondition without causing a provider outage.

```bash
SYMPHONY_TRELLO_ERASURE_LIFECYCLE=1 node scripts/erasure-lifecycle-live.ts
SYMPHONY_TRELLO_ERASURE_LIFECYCLE=1 node scripts/erasure-lifecycle-live.ts --resume
```

The runner verifies the selected managed TEST project before any mutation. It stores a private
ledger in `~/.local/state/symphony-trello/erasure-lifecycle/ledger.json`. Set
`SYMPHONY_TRELLO_ERASURE_LIFECYCLE_DIR` to use another protected directory, and set the same value
for every `--resume` run. The ledger contains synthetic ownership credentials and must not be
published. Do not start again over an existing ledger; resume it. Temporary sources use the
canonical handler and are archived after each invocation of the runner. Cleanup failures are
recorded separately from lifecycle success; resume retries cleanup without repeating completed
validation. Status flags and synthetic data are retained while their deletion and canary checks are
pending, so do not run `erasure-flags --archive` against the test role during a run.

A pass authorizes only the handler that ran every stage. The ledger records the SHA-256 of
`infra/posthog/erasure-service.hog.tftpl` when a run starts. A resume on a different template, or a
ledger written before this field existed, is marked `handlerMixed` and can never authorize
production. The run that started on 2026-09-23 used an earlier handler. Resuming it still shows
whether PostHog physically deletes the data, but production needs a new full run on the current
handler.

On 2026-09-25 a new run started on the current handler. Every admission check passed: issuance and
key derivation, the empty-period path, partial-failure recovery, forgery rejection, lost-response
retry, provider-backed acceptance and canary survival. It stopped at
`ACCEPTED_PHYSICAL_COMPLETION_PENDING` and needs `--resume` after PostHog's deletion batch; it keeps
its ledger in a separate `SYMPHONY_TRELLO_ERASURE_LIFECYCLE_DIR`. Unsaved Hog probes in the TEST
project confirmed that `typeof` returns `object` for a dictionary and `null` for a missing field,
that the nested status check and the flag-name split work, and that the rendered handler runs.

A `--resume` on 2026-10-05 passed `period 1: physical event absence and public completion` and
marked the first period complete. The same invocation ran the second period through the replay of
the first period, forgery rejection, lost-response retry and provider-backed acceptance, all
passing, and stopped again at `ACCEPTED_PHYSICAL_COMPLETION_PENDING` for the second period. The run
from 2026-09-23 on the earlier handler reached the same point on the same day.

A `--resume` on 2026-10-17 failed `period 2: physical event absence and public completion` on the
current handler, so production stays disabled. PostHog's deletion record for the second period's
profile reads `completed` with `delete_verified_at` 2026-10-09T12:35:17Z, yet the period's one
event, captured and queryable twelve seconds before the deletion request, is still returned by
the events query with that profile's `person_id`. The run on the earlier handler, whose second
period was requested two minutes earlier the same night, passed the same check and reached
`TWO_PERIOD_LIFECYCLE_PASS`, which authorizes nothing because its handler is not the current one.
A deletion PostHog reports as verified can therefore leave an event behind, and the gate is right
to read the events instead of the record. The ledger keeps the failed check and can be resumed;
a new run needs its own `SYMPHONY_TRELLO_ERASURE_LIFECYCLE_DIR`.

A `--resume` on 2026-10-25 failed the same check: the event was still returned 20 days after the
deletion request, so that run will not pass by waiting. A new run started on the handler of that
day in `~/.local/state/symphony-trello/erasure-lifecycle-v3`. All 14 admission checks passed and
it stopped at `ACCEPTED_PHYSICAL_COMPLETION_PENDING` for the first period. A `--resume` on
2026-10-31 passed the first period and stopped at the same point for the second. That run is
superseded by the handler change below and is not resumed.

### Verified deletions that leave events (2026-10-31)

Read-only probes of the TEST project on 2026-10-31 compared every person deletion the lifecycle
runs had requested. PostHog's deletion record alone does not prove that the events are gone:

| Run | Period | Deletion requested | Record | Events left on 2026-10-31 |
| --- | --- | --- | --- | --- |
| 2026-09-23, earlier handler | 1 | 2026-09-22 18:50 UTC | verified 2026-09-25 | 0 |
| 2026-09-23, earlier handler | 2 | 2026-10-05 00:23 UTC | verified 2026-10-09 | 0 |
| v2, 2026-09-25 | 1 | 2026-09-25 14:12 UTC | verified 2026-10-02 | 0 |
| v2, 2026-09-25 | 2 | 2026-10-05 00:22 UTC | verified 2026-10-09 | 1 |
| v3, 2026-10-25 | 1 | 2026-10-25 13:48 UTC | verified 2026-10-30 | 0 |
| v3, 2026-10-25 | 2 | 2026-10-31 09:40 UTC | pending | 1, as expected |

The surviving event of the v2 second period (uuid `01a10970-279d-7a72-868d-ab434b4e05d7`) was
captured and queryable twelve seconds before its deletion request, carries the deleted person's
UUID in both the raw and the override-resolved `person_id` column, has no override row, and was
returned by twelve consecutive uncached counts, by analytics ID and by person UUID. The deletion
verified in the same batch, 79 seconds later, removed its event. Nothing in the API separates the
two cases, and PostHog's own source says a deletion run can mark requests verified without proving
the rows are gone: since [PostHog PR 90689](https://github.com/PostHog/posthog/pull/90689),
[PostHog PR 100051](https://github.com/PostHog/posthog/pull/100051) and
[PostHog PR 105317](https://github.com/PostHog/posthog/pull/105317), merged between 2026-08-28 and
2026-09-23, the deletion job reports the rows it left behind instead of holding the request
back, and the proposal to let a person deletion be requested again,
[PostHog PR 93059](https://github.com/PostHog/posthog/pull/93059), was closed without merging.
The case is reported upstream as
[PostHog issue 126750](https://github.com/PostHog/posthog/issues/126750).

Until then the handler set `complete` from the deletion record and profile absence alone, so
production could have told a user their data was erased while a report remained. Since
2026-10-31 the handler counts the period's events itself once the record is verified and reports
`incomplete` when the count is not zero ([ADR 0150](adr/0150-report-erasure-complete-only-after-the-events-are-gone.md)).
The count runs with the service credential's existing `query:read` scope, the same scope the
empty-period check already uses, so the OpenTofu module is unchanged. The lifecycle runner checks
the rule in each period (`public status matches the remaining events`), records the outcome in
the ledger, and passes on either outcome; for an incomplete period it also checks that `ack` keeps
the flag and that the first-period replay leaves it unchanged. User-facing text says only that
the data is deleted within 30 days of the request; the maintainer asks PostHog support to delete
a leftover, as the [runbook](telemetry-maintainer-runbook.md#incomplete-automatic-erasure) says.

A new run, v4, started on the changed handler the same day in
`~/.local/state/symphony-trello/erasure-lifecycle-v4`; the ledger binds the new template hash.
Its eight admission checks up to the period 1 lost-response ingress passed, then
`period 1: deletion admitted without further client writes` failed: no deletion record appeared
within 190 seconds of the 201 receipt, where the v2 and v3 runs saw theirs within 4 to 21 seconds.
A read-only look fifteen minutes later found the period's profile still present, no deletion
record and no status flag, so on that call the handler never reached its flag creation, or the
invocation never ran. A first `erase` takes the flag lookup, the person lookup, the flag creation
and `bulk_delete`; the changed branch needs an existing flag and a verified deletion record, which
that call could not have. The empty-period and partial-recovery checks had passed on the same
source two minutes earlier. The source's logs were gone by then, because the runner archives the
source after every invocation, so the cause is not known. The runner now writes the source's log
entries and metrics into the ledger (`diagnostics`) when a check fails, before the teardown, so
the next failure says whether the invocation ran, returned early or errored. A `--resume` the same
day, on a new source with the same handler, admitted the period 1 deletion six seconds after the
discarded erase call, passed the lost-response retry, provider-backed acceptance and canary checks,
and stopped at `ACCEPTED_PHYSICAL_COMPLETION_PENDING`. PostHog's EU deletion batch has run on
Fridays around 12:00 UTC, so resume v4 with that directory after 2026-11-06, and again a week
later for the second period.

### Risks and how they are handled

Review found four risks, and the lifecycle runs found a fifth. Their handling:

- Flag-limit exhaustion is an accepted residual risk. `issue-v1` issues a credential to any
  caller, and each `erase` for a new empty period creates a status flag. About 2,000 such requests
  reach PostHog's limit of 2,000 non-deleted flags, after which every real erasure stays pending.
  PostHog alone offers no rate limit that could stop this. The attacker cannot delete another
  installation's data, and the manual procedure keeps working. `verify` fails its advisory
  `erasure.status_flags` check at 500 flags. Once the requests stop, `erasure-flags --archive`
  restores capacity. Each such request also runs a forced HogQL query against the management key's
  quota.
- Flags that `ack` never archives: `erasure-flags` archives empty and complete results older than
  a day. It reports incomplete and refused operations, and pending or accepted ones older than 14
  days, for the maintainer.
- Verified deletions that leave an event: the handler counts the period's events after PostHog
  reports the deletion verified and publishes `incomplete` instead of `complete` when any remain.
  The client tells the user the erasure is still being processed and the data is deleted within
  30 days of the request, `erasure-flags` reports the flag as `incomplete` with the support
  request to make, and the maintainer follows the
  [runbook](telemetry-maintainer-runbook.md#incomplete-automatic-erasure).
- Late ingestion: the client waits an hour after a heartbeat's drain deadline before its first
  lookup. An empty result names its analytics ID, and `erasure-flags` counts that ID's events
  again before archiving. A late event is reported as `late-events`; delete it with the
  [manual procedure](telemetry-maintainer-runbook.md#erasure-requests), then archive the flag.
- Unchecked activation: the root `erasure_enabled` variable refuses `production = true` unless
  `erasure_lifecycle_pass` equals the `filesha256` of the current handler template.
  `scripts/posthog-infra` supplies that value only from a `TWO_PERIOD_LIFECYCLE_PASS` ledger that
  is not `handlerMixed`. The offline tests in `infra/posthog/tests` prove the refusal. Release
  packaging refuses an erasure audience other than `symphony:<id>` with the ID in
  `infra/posthog/production-project-id`, and `verify` checks that file against the production
  project.

Ordinary CI uses loopback HTTP and no PostHog credential. It covers persisted credentials,
concurrent disable during issuance, disable followed by erasure during a dispatched heartbeat, delivery without enough remaining transport time, late status responses after a new period starts, retries,
unknown status, legacy identity refusal, explicit resume after completion or refusal, retry
backoff, the ingestion wait, the wire contract between the client and the handler template, flag
review, the activation refusal and the release audience check.

## Deployment and operations

The optional OpenTofu resources default to disabled for both roles. Follow
[native erasure deployment](posthog-infrastructure.md#native-erasure-deployment), including a
project-scoped service credential, protected versioned master keys and the normal plan/readback
workflow. The release URL and audience remain unset until production activation.

Activation order: pass the lifecycle on the TEST role with the current handler, where each period
counts as passed on correct handling of its provider outcome (`complete` with no event left, or
`incomplete` with the flag retained), apply with `erasure_enabled.production = true` (the wrapper
reads the pass from the ledger), run `verify`, then set the release variables described there.
Review status flags regularly, and at once when `verify` reports `erasure.status_flags`:

```bash
scripts/posthog-infra erasure-flags production            # dry run: what would be archived
scripts/posthog-infra erasure-flags production --archive  # archive settled results
```

The command exits 1 when a flag needs the maintainer: `late-events` (delete that analytics ID's
events manually), `incomplete` (a verified deletion left an event; ask PostHog support to delete
the person's remaining events, as the command's output says), `refused` (see the runbook) or
`stale`.

The merge filter drops `$identify`, `$create_alias` and `$merge_dangerously`. A multi-ID person
fails the deletion precheck. The maintainer accepts the remaining merge race for the stated
low-value analytics threat model. This is not an atomic provider guarantee of deletion scope.
Restoring an old client backup requires maintainer investigation, and late ingestion is handled
as described above. The service does not claim to erase future captures under a retired ID.

Contract impact: Section 19.9, CLI commands, state, privacy text, dashboard grouping, release
packaging and managed PostHog definitions change together. Trello and Codex workflows, event
properties and the first-report grace period are unchanged. The optional ownership state is
part of format version 1; original state without that field remains a supported legacy profile.
Older binaries reject the extended state and suppress reporting instead of dropping its secret.
