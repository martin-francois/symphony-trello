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
the installation indefinitely.

The flag key is `erasure-` followed by HMAC-SHA-256 over
`symphony-trello/status/v1|<audience>|<period>`, using the installation credential. Public
`/flags?v=2` evaluation returns only the opaque key and status. Management credentials alone
write it. A completed result is saved locally before one best-effort flag archival request. The client then stops maintenance traffic.
Failure to archive does not undo completion; operators remove verified leftovers before
PostHog's 2,000 non-deleted flag limit. Missing status never authorizes resume.

The installation keeps its credential and registration date. Explicit enable after completion
creates a fresh period. Ordinary disable/enable preserves it. An old request cannot address
that new period. Restoring an old state backup does not restore a valid reporting period.
Existing UUID-only installations retain their state and use maintainer assistance.

## Evidence and remaining gate

Hosted TEST checks on 2026-09-22 established native issuance, cross-runtime key derivation,
forgery rejection, immutable person binding, asynchronous deletion acceptance, public status
readback and survival of an unrelated canary profile. The source was disabled and archived
after the run. The deletion job continues within PostHog.

Physical event deletion was pending at the time of that observation. It is not a lifecycle
pass. The durable runner below records each stage and can resume after PostHog's job completes.
Production activation requires `TWO_PERIOD_LIFECYCLE_PASS`, including event absence, profile
absence, lost-response retries, partial profile-deletion recovery, canary event survival and a replay of the first period while the second exists.
Provider completion timing is outside this repository's control. The partial-failure fixture
queues deletion with `keep_person=true`, then verifies that the native service removes the
remaining profile. This tests the failure postcondition without causing a provider outage.

```bash
SYMPHONY_TRELLO_ERASURE_LIFECYCLE=1 node scripts/erasure-lifecycle-live.mjs
SYMPHONY_TRELLO_ERASURE_LIFECYCLE=1 node scripts/erasure-lifecycle-live.mjs --resume
```

The runner verifies the selected managed TEST project before any mutation. It stores a private
ledger in `/var/tmp/symphony-erasure-lifecycle`; set
`SYMPHONY_TRELLO_ERASURE_LIFECYCLE_DIR` to another protected directory to retain it across host
cleanup. The ledger contains synthetic ownership credentials and must not be published. Do not
start again over an existing ledger; resume it. Temporary sources use the canonical handler and
are archived after each invocation of the runner. Cleanup failures are recorded separately
from lifecycle success; resume retries cleanup without repeating completed validation. Status flags and synthetic data are retained
while their deletion and canary checks are pending.

Ordinary CI uses loopback HTTP and no PostHog credential. It covers persisted credentials,
concurrent disable during issuance, disable followed by erasure during a dispatched heartbeat, delivery without enough remaining transport time, late status responses after a new period starts, retries,
unknown status, legacy identity refusal and explicit resume after completion.

## Deployment and operations

The optional OpenTofu resources default to disabled for both roles. Follow
[native erasure deployment](posthog-infrastructure.md#native-erasure-deployment), including a
project-scoped service credential, protected versioned master keys and the normal plan/readback
workflow. The release URL and audience remain unset until production activation.

The merge filter drops `$identify`, `$create_alias` and `$merge_dangerously`. A multi-ID person
fails the deletion precheck. The maintainer accepts the remaining merge race for the stated
low-value analytics threat model. This is not an atomic provider guarantee of deletion scope.
Delayed ingestion or restoring an old client backup requires maintainer investigation; the
service does not claim to erase future captures under a retired ID.

Contract impact: Section 19.6, CLI commands, state, privacy text, dashboard grouping, release
packaging and managed PostHog definitions change together. Trello and Codex workflows, event
properties and the first-report grace period are unchanged. The optional ownership state is
part of format version 1; original state without that field remains a supported legacy profile.
Older binaries reject the extended state and suppress reporting instead of dropping its secret.
