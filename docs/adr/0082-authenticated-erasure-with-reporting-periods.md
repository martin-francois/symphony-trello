---
status: accepted
date: 2026-09-22
decision-makers: [François Martin, Codex]
consulted:
  - "[Ownership proof](../telemetry-erasure-research.md#ownership-proof-options)"
  - "[Source investigation](../telemetry-erasure-research.md#cutoff-queue-key-and-identity-reuse)"
informed: [Contributors, Maintainers]
---

# Authenticated erasure with separate reporting periods

## Context and Problem Statement

An installation must authorize deletion without an account, a retained enrollment event,
or another backend. The hosted HMAC proof works. PostHog derives person UUIDs from distinct
IDs and retains completed deletion records, so reusing one analytics ID does not establish
repeatable deletion. The maintainer approved a stable installation ID with a fresh analytics
ID after completed erasure.

## Decision Drivers

- Prevent random requests from deleting another installation's analytics.
- Keep ownership independent of one-year event retention and first-message delivery.
- Let PostHog finish accepted event deletion without keeping the client running.
- Keep the installation identity and dashboard counts stable across reporting periods.
- Use PostHog-managed facilities and the Java client, without another backend.

## Considered Options

- HMAC ownership with reporting periods
- Reuse one distinct ID and reset its mapping
- Bearer ownership credentials
- Separate hosted deletion service

## Decision Outcome

Chosen option: "HMAC ownership with reporting periods".

Use the proven server-derived HMAC credential. The issuer selects the installation UUID;
the client persists the credential before its first report. An unanswered issuance can be
abandoned because the client has not reported under that identity. Preserve master-key
versions in protected configuration and a separate operator backup.

Each reporting period has a random UUID. Its analytics distinct ID contains the stable
installation UUID followed by a dot and the period UUID. Queries group by the installation
part. No secret, signature, or operation receipt is included in an analytics event.

An erase request disables reporting and durably records its operation before contacting
the service. Each dispatch persists its drain deadline independently of the report claim,
so disable cannot remove the wait for an in-flight heartbeat. Signed requests bind the action, audience, installation, period and operation.
Retries keep the same period. Workers wait half the time since the request between retries,
at least one minute and at most six hours, because provider deletion can take days;
`erase-status` checks immediately once a dispatched heartbeat has settled: its drain deadline
plus one hour for PostHog ingestion. Failed credential issuance uses the heartbeat retry backoff. Accepted work continues within PostHog. Acceptance is not
completion; status must establish provider deletion completion. Resuming after completion
creates a new period and never resets or reuses the erased ID. Ordinary disable/enable
preserves the period. Explicit enable is still required after erase. After a refused erasure,
enable also starts a new period; the refused period's data stays with the maintainer, and the
new analytics ID cannot join the merged profile.

PostHog incoming webhooks return before their first external fetch completes. The client
therefore treats HTTP acceptance only as receipt. A background invocation writes a per-period
feature flag, keyed by an HMAC-derived opaque value. Only management credentials write this
status. Public flag evaluation returns pending, accepted, complete or refused, never credentials
or installation identifiers. Private flag metadata binds the immutable person UUID before any
delete call. Status refreshes inspect the provider deletion queue and profile absence.
If a report never arrived or all data expired, no person exists to delete. The service then
requires a fresh, uncached raw-ID event count of zero before recording completion. This avoids
stranding an installation after an unsuccessful first capture. The client quiesces its active
heartbeat before requesting erasure. Completion covers retained data; delayed ingestion and
restored pre-erasure backups require maintainer investigation.

These flags are operational state, independent of event retention. After saving completion,
the client makes one best-effort archival request; a failed acknowledgment leaves a flag for operator cleanup.
PostHog permits 2,000 non-deleted flags per project. Monitor and archive verified completed
operations before that limit. Missing or malformed status never means completion. Restoring a
backup from before erasure is not a supported way to resume reporting. Keep the completed local
state or use maintainer assistance. Account flag limits and management API quotas remain
deployment gates.

Drop all three identity-changing event types and refuse deletion when the target person
has unexpected distinct IDs. The maintainer accepts the remaining merge race for low-value
analytics and the stated random-guessing threat. These safeguards do not establish strict
isolation if a known-ID attacker gets a merge through during a filter failure.

Existing installations without credentials retain their identities and preferences. They
use maintainer-assisted deletion; a public issuer must not grant ownership of an existing
ID based on knowledge of that ID. This is deliberate compatibility with the original
telemetry profile, not an implicit claim that legacy IDs have ownership proofs.

This decision supersedes the same-analytics-ID requirement in ADR 0079 and the corresponding
investigation gate in ADR 0081. Production activation still requires passing hosted lifecycle
checks. Implementation and deployment evidence must distinguish accepted work from physically
verified event deletion.

### Consequences

- Ownership needs no enrollment history or periodic key uploads.
- Stable installation counts require grouping the analytics ID by its installation part.
- The local state contains a secret and must remain private, atomic and recoverable.
- Repeated erasure uses separate provider queue keys and cannot target a later period by replay.
- Losing the credential requires maintainer assistance for old data.
- Provider deletion remains asynchronous; the CLI must report pending work honestly.
- The driver to finish deletion without the client is only partly met. PostHog finishes queued
  event deletion without the client. The first `erase` queues it with `keep_person: true`. The
  service removes the profile record only on a later signed request from the client, sent by
  `erase-status` or a running worker's retry.

Review found four risks. The maintainer accepted the first as a residual risk; the other three
are mitigated. The
[implementation document](../telemetry-erasure-implementation.md#risks-and-how-they-are-handled)
has the details.

- Flag-limit exhaustion. `issue-v1` issues a credential to any caller, so about 2,000 `erase`
  requests for new empty periods can fill PostHog's limit of 2,000 non-deleted flags and leave
  every real erasure pending. PostHog alone offers no way to rate-limit this. It blocks automatic
  erasure but cannot delete another installation's data, and the manual procedure keeps working.
  `verify` warns at 500 flags, and `scripts/posthog-infra erasure-flags --archive` restores
  capacity after the requests stop. Archiving is safe because a `status` request recreates a
  missing empty or complete result.
- Flags that `ack` never archives. `erasure-flags` archives settled results older than a day and
  reports refused and stale operations.
- Late ingestion. The client waits an hour after a heartbeat's drain deadline before its first
  lookup. Empty results name their analytics ID, and `erasure-flags` counts its events again
  before archiving and reports any late event for manual deletion.
- Unchecked activation. A validation on `erasure_enabled` refuses production unless
  `erasure_lifecycle_pass` matches the current handler template's hash, which the wrapper reads
  only from a passing lifecycle ledger bound to that handler. Release packaging requires the
  production project's audience from `infra/posthog/production-project-id`.

### Confirmation

Verify issuance loss and concurrent registration, secret redaction, signed target binding,
concurrent and lost-response retries, malformed requests, multi-ID refusal, two reporting
periods, replay after the second period starts, and survival of an unrelated canary. Verify
accepted work continues after the client exits. Keep ordinary CI offline and use only the
managed TEST project for live verification before production activation.

## Pros and Cons of the Options

### HMAC ownership with reporting periods

Keep a stable credential and use a separate analytics ID for each post-erasure period.
Signed requests authorize erasure, and a PostHog webhook runs the deletion. Status: selected.

- Good, because requests carry a signature, never the credential, and bind action, audience,
  installation, period and operation.
- Good, because each period has its own person UUID and deletion queue key, so a replay cannot
  reach a later period.
- Good, because it runs inside PostHog and needs no other backend.
- Bad, because the local state holds a secret and more fields that must stay private and atomic.
- Bad, because dashboards must group analytics IDs by their installation part.
- Bad, because status flags count toward PostHog's flag limit and need operator cleanup.

### Reuse one distinct ID and reset its mapping

Keep one distinct ID and reset PostHog's mapping after deletion.

- Good, because the analytics ID and dashboard grouping stay unchanged.
- Bad, because the inspected reset does not create a different deterministic person UUID.
- Bad, because the reset does not remove the old deletion queue key, so a second erasure of the
  same ID does not get a separate deletion record.

### Bearer ownership credentials

Send the stored secret to authorize each erasure. The service compares it with the derived value.

- Good, because the server check is a plain comparison without request signing.
- Bad, because every erasure request exposes the secret to the webhook and its logs.
- Neutral, because it remains an accepted fallback if the hosted HMAC check stops working.

### Separate hosted deletion service

Run another backend with its own durable operation store.

- Good, because operation records could live outside PostHog's flag limit.
- Bad, because the maintainer excludes the operational cost of another backend.
- Bad, because it does not itself narrow PostHog's person-wide deletion scope.

## More Information

See the [implementation and activation gate](../telemetry-erasure-implementation.md) for the
wire protocol, hosted evidence and remaining physical-deletion checks. The canonical deployment
is in `infra/posthog/modules/project/erasure.tf`.
