---
status: accepted
date: 2026-09-22
decision-makers: [François Martin]
consulted:
  - "[PostHog capture API](https://posthog.com/docs/api/capture)"
  - "[PostHog event retention](https://posthog.com/docs/data/events-retention)"
  - "[PostHog data storage and deletion](https://posthog.com/docs/privacy/data-storage)"
  - "[Next.js telemetry](https://nextjs.org/telemetry)"
  - "[OSHI](https://www.oshi.ooo/)"
informed: [Future maintainers, Contributors]
---

# Report Installation Heartbeats To A Dedicated PostHog EU Project

## Context and Problem Statement

Symphony for Trello is distributed through a public repository and an installer. The maintainer
has no view of how many installations exist, which releases they run, how many Trello boards they
connect, whether the import and create flows are used, or which operating systems deserve testing.
Those questions decide what to support and what to fix first.

The maintainer already uses PostHog for `fmartin.ch` and wants the same hosted analytics for
Symphony without linking installation data to website visitors. Before this decision, the
alternatives were discussed at length: opt-in versus opt-out, several event types versus one,
system-derived versus random identities, short versus long first-report delays, and how much
review a user should see before disabling. This ADR records what was selected, what was
considered and left out, what is deferred, and what remains unresolved. Sections 1 to 9 of the
implementation brief are the product contract; this document explains the reasoning behind it.

Nothing here claims that the selected default, delay, or provider setup is lawful in every
jurisdiction. Swiss data protection law applies to the maintainer; whether foreign privacy or
device-access rules apply to individual installations is not settled by this ADR.

## Decision Drivers

- The maintainer wants counts of reporting installations, latest installed versions, board counts,
  import and create adoption, platform families, and 1, 7, and 30 day activity; 30 days is the
  inactivity cutoff.
- The maintainer is concerned that an opt-in default would make still-used releases look unused
  and prefers the Next.js style opt-out experience.
- One installation with five boards must count once; one installation upgraded ten times must
  keep one identity.
- Telemetry is subordinate: a failure in it must never fail setup, a worker, or Trello or Codex
  work, and it must not add a daemon, a database, or a backend.
- The complete application JSON that leaves the machine must be visible in advance, with the
  same serializer used for preview, log, and wire; network and TLS metadata exist outside it.
- Multiple worker JVMs share one installation state and must not create duplicate identities,
  duplicate daily reports, or lost counter increments.
- Development, test, and CI runs must never send production telemetry.
- The maintainer's existing PostHog account should carry the data without routine hosting work.

## Considered Options

The dimensions below are independent. Each list names the selected option first.

For the participation default (A01), the considered options are:

- default-enabled reporting with a non-blocking notice, a five-minute first-worker grace period,
  and a persistent opt-out
- an explicit setup consent question defaulting to no, with no collection until enabled

For the analytics backend (A02, A03, A04), the considered options are:

- a dedicated project in PostHog Cloud EU, reached directly from the application
- Mixpanel, Aptabase, or self-hosted analytics
- a custom ingestion and storage service on Cloudflare Workers and D1 or similar
- a proxy or backend between Symphony and the provider

For the client integration (A05), the considered options are:

- a narrow typed capture adapter on the JDK `java.net.http.HttpClient`
- the PostHog Java SDK
- the Quarkus REST Client
- the PostHog browser SDK with autocapture

For the identity (A06, A07, A09), the considered options are:

- one random UUID per installation, persisted in the state directory, with the registration date
  being the day that UUID was created
- an identifier derived or hashed from hardware, hostname, user name, or `/etc/machine-id`
- PostHog cookieless server-side hashes that rotate daily
- per-worker or per-browser-session identities
- a reconstructed original installation date, a first-server-contact date, a one-time
  registration event, or a `$set_once` profile property for the registration date

For the event model (A08, A10, A11, A12, A13, A14), the considered options are:

- one self-contained `installation_heartbeat` with a fixed scalar property set, cumulative
  successful import and create totals, the installed release from installer metadata, and coarse
  normalized platform fields
- lifecycle events such as `installation_registered`, `board_connected`, and version upgrade
  events in addition to heartbeats
- inferring board additions from heartbeat count changes, or reporting only the origin of the
  boards currently connected
- attempt, failure, and cancellation funnels with failure categories
- board-count buckets, running-worker counts, and a "did work" boolean
- the version of whichever worker sends, or a set of running-worker versions
- OS family alone, or raw OS descriptions, kernel builds, and host probing

For collection and scheduling (A15, A16, A17, A18, A19), the considered options are:

- collection in setup and running-worker code, one accepted snapshot per active UTC day, a
  local due check every minute, a shared five-minute first-worker deadline, a short non-blocking
  notice, and an immediate read-only preview plus a persistent local-only debug mode
- tracking visits to the local dashboard, or a separate telemetry daemon
- hourly reports, a fixed daily time, or 24 hours of continuous uptime before the first report
- immediate first transmission, ten minutes, or 24 hours before the first report
- an acknowledgement menu at setup, or leaving unattended installations pending until a telemetry
  setting is supplied
- debug output only at the daily send, or a `preview --watch` command

For the disable experience (A20, A21, A22, A23, A24), the considered options are:

- an interactive review that shows the full pretty-printed JSON, asks
  `Disable telemetry? [yes/No/privacy]` with Enter keeping the setting, persists nothing until
  yes, disables directly for non-interactive input, accepts `--yes` in a terminal, and ends with
  factual copy plus light humor only after disabling
- disabling immediately because typing the command already expresses intent
- a summary table first, a separate key to reveal JSON, or expecting users to run preview
  themselves
- default yes, no default, or letter menus; persisting disabled when the dialog opens and
  re-enabling on no
- an extra confirmation flag even in scripts, quirky flag names, or guilt-oriented messages
- privacy wording such as "guaranteed anonymous", "never shared with anyone", or "nothing else
  is sent"

For state, coordination, and delivery (A25, A26), the considered options are:

- one `telemetry.json` plus a stable `telemetry.lock` in the state home, an OS file lock with a
  JVM-local lock, atomic replacement, a 90 second reporting claim, at most one immutable pending
  report, bounded retries, and replacement of stale observations
- per-worker identities or report markers, in-process locks alone, treating a lock file's
  existence as ownership, or holding the lock across HTTP or prompts
- unlimited offline queues, backfilled heartbeats, changed timestamps on retry, or blocking
  startup until delivery succeeds

For retention, profiles, and erasure (A27, A28, A29, A30), the considered options are:

- a 30 day inactivity definition that labels a later report as reactivation, the provider's
  detailed event-analysis window (intended as one year) with repeated summary facts in fresh
  heartbeats, a minimal installation profile keyed by the UUID, and a private maintainer-side
  erasure process
- treating a month of silence as an uninstall
- keeping a tiny linked dataset or the registration event forever, deleting everything older than
  30 days, or deleting inactive profiles after a cutoff
- personless capture, `$set_once` summary properties, remote erasure on every local disable, or
  a public self-service deletion backend
- monthly aggregate exports or a separate historical warehouse

For dashboards and ingestion integrity (A31, A32), the considered options are:

- latest coherent snapshot per recently active installation for state distributions, pre-release
  cohorts for upgrade analysis, installation-level adoption, and only the public capture token in
  the distribution
- grouping all historical events by version, summing cumulative totals, or mixing new installs
  with upgrade delays
- a proxy or authentication layer against fabricated events

## Decision Outcome

Chosen option: "default-enabled daily installation heartbeats to a dedicated PostHog EU project,
sent directly by a narrow JDK HTTP adapter, with a random persisted installation UUID, one
self-contained event, shared file-locked state, a five-minute first-worker grace period, an
immediate preview, and an informative disable review", because it answers the maintainer's
support questions with the smallest schema, keeps every transmitted byte visible and reproducible,
reuses the maintainer's existing hosted analytics, and needs no new service, database, or daemon.

The decision is the maintainer's product and technical choice. Release prerequisites that remain
open are listed under Confirmation and in
[docs/telemetry-maintainer-runbook.md](../telemetry-maintainer-runbook.md).

### Consequences

- Good, because the maintainer can count reporting installations, see the latest installed
  release per installation, and separate single-board from multi-board use without any user
  action.
- Good, because every report is one allowlisted record built by one serializer; `telemetry
  preview`, the request log, and the wire body are the same bytes.
- Good, because a review of the first implementation led to repairs that are now part of the
  decision: one time budget covers response headers and body, one delivery attempt is in flight
  per worker, claims carry a per-attempt token, both state locks share one bounded wait, stored
  state is checked against its invariants before use, a quota drop is a deferral rather than an
  accepted report, and the dashboard reads every field from one winning event.
- Good, because a random persisted UUID gives upgrade continuity without hardware fingerprinting,
  and copying or deleting the state directory is documented rather than worked around.
- Good, because one file plus one lock file coordinates any number of worker JVMs; there is no
  daemon, database, or backend to operate.
- Good, because disabling is one command, works non-interactively, and shows the actual JSON in a
  terminal before the user decides.
- Bad, because opt-out reporting is a privacy trade-off. Users who never read the notice are
  counted after five minutes, and printed service logs do not prove anyone saw it.
- Bad, because the heartbeat cannot distinguish an uninstall from a stopped worker, a blocked
  network, or a disabled setting; 30 days of silence is an operational label only.
- Bad, because successes are counted but attempts are not, so a broken import flow that produces
  few successes looks like low demand.
- Bad, because the project now carries an OSHI dependency and a native-access flag on every JVM
  launch path.
- Neutral, because retries can produce more than one HTTP attempt for the same event UUID and
  PostHog acceptance is not proof of retention; the measurement is best effort, not exactly once.
- Neutral, because installations older than telemetry register when they first run setup or a
  ready worker, so `registered_on` is the telemetry identity date, not an installation date and
  not necessarily the date of the first transmission.
- Neutral, because the application never rotates or deletes an installation id, not even after a
  provider-side erasure; the user only disables, the maintainer erases, and a later enable resumes
  with the same id. A local reset command was implemented briefly during review and removed at the
  maintainer's request. Deleting the state file is never advised: an absent file initializes as
  enabled. Whether PostHog then forms a new profile for the reused id without maintainer work is
  an empirical question the live experiment in
  [docs/telemetry-erasure-verification.md](../telemetry-erasure-verification.md) answers; its
  reuse rows were pending on 2026-09-22.

### Confirmation

Code verified locally on Linux with:

```bash
./mvnw -q spotless:check verify
```

The gate runs the telemetry tests: `PlatformNormalizerTest`, `PlatformDetectorTest`,
`HeartbeatJsonTest`, `TelemetryStateStoreTest`, `TelemetryMultiJvmTest`, `HeartbeatReporterTest`,
`PostHogCaptureClientTest`, `TelemetryServiceTest`, `TelemetryDistributionTest`,
`InstalledVersionTest`, `EffectiveTelemetryTest`, `TelemetryWorkerReporterTest`, and
`TelemetryDocumentationTest`, plus `TelemetryCommandTest` and `LocalSetupTelemetryTest` at the
command boundary and the telemetry cases added to `InstalledCliDefaultsTest`,
`LocalWorkerManagerTest`, `InstallerScriptTest`, `InstallerScriptLifecycleTest`, and
`ReleasePackagingScriptTest`. `TelemetryMultiJvmTest` starts separate JVMs against one state
directory to confirm one identity and one daily claim. `HeartbeatJsonTest` checks the exact
property allowlist, key order, and null-versus-zero behavior. `PostHogCaptureClientTest` runs
against a loopback fake server, including a server that sends headers and then withholds the
body; no test contacts PostHog.

Repairs made after an external review of the first implementation, each with its own regression:
the stalled-body timeout (reproduced first on Java 25 against the real client, which stayed blocked
past three seconds with a 500 ms budget), one in-flight delivery per worker, per-attempt claim
tokens, the shared lock budget (`TelemetryStateStoreTest` blocks one transaction behind a latch),
state invariants (`TelemetryStateStoreTest` mutates a valid file into eight inconsistent shapes),
quota deferral, the privacy answer returning to the question, truthful review outcomes, tolerant
loading of an invalid endpoint override, the explicit manifest path for the board count, clock
rollback, the worker notice after setup disclosure, the runbook's erasure procedure (disable
first, verify event deletion by query, no local reset), and, after a second review, the capture
response reading: an unreadable, truncated, oversized, or unparseable body is a failed attempt
that keeps the pending event for a retry rather than an accepted report. A stored disable is tested to survive
restarts and checks beyond the grace period, and enable is tested to keep the identity and
counters without replaying a discarded report.

The erasure procedure was executed live against the test project on 2026-09-22 with
`TelemetryErasureExperimentIT`, an opt-in harness that `verify` skips, guarded by
`ErasureExperimentSafetyTest` against an in-memory PostHog. Established: the baseline of profiles
and events for synthetic installations, that a local disable keeps the identity and sends nothing
across restarts, and that `bulk_delete` queues profile and event deletion with a checkable body;
profile deletion completed within minutes while the events remained queryable with the deleted
profile's id until PostHog's batched event deletion. Pending on that date: the verified event
deletion, same-id reuse with and without a prior reset, the conditional repair, and the cleanup.
The evidence and the resume command are in
[docs/telemetry-erasure-verification.md](../telemetry-erasure-verification.md); no claim about
seamless re-enablement is made until those rows are observed.

Not run locally: Windows and macOS platform detection. The Windows CI job covers the installer
scripts, not OSHI output. Real Windows 10 versus 11 and macOS product-version results are
confirmed only by OSHI's own mapping and by fixture tests of the normalizer.

Provider checks done through the PostHog API on 2026-09-22: projects 280816 "Symphony for Trello"
and 280817 "Symphony for Trello (test)" were created in the maintainer's EU organization; client
IP discarding is on; the default GeoIP transformation is disabled; autocapture, session replay,
surveys, heatmaps, console logs, and web vitals are off on both. Every query in
[docs/telemetry-dashboard.md](../telemetry-dashboard.md) was executed against the documented
synthetic fixture in the test project and returned the documented results, including the
known-to-null board count and the adopt-then-downgrade case. The production dashboard was created
from the first version of the queries; republishing the repaired queries there is a maintainer
step listed in the runbook.

Not established on that date: legal applicability of opt-out reporting outside Switzerland; the
PostHog product and model-development opt-out state for the organization; the projects'
`event_retention_months` and `events_retention_enforced` values, which the API returned as null;
execution of the PostHog data processing agreement. These are release prerequisites tracked in
the runbook, not consequences of this decision.

## Pros and Cons of the Options

### Default-enabled reporting with notice, grace period, and persistent opt-out

Reporting is on in installed copies. Setup and the first ready worker print a short notice, the
first report leaves at the earliest five minutes after the first worker is ready, and
`telemetry disable` or `SYMPHONY_TRELLO_TELEMETRY_DISABLED=1` turns it off for good. Status:
selected, explicit maintainer preference.

- Good, because the maintainer's stated concern is addressed: releases that are still in use are
  less likely to appear unused.
- Good, because the flow matches the Next.js precedent the maintainer asked for.
- Bad, because opt-out still misses disabled, blocked, offline, and pre-telemetry installations,
  so it is not evidence of a representative sample; the ADR does not claim reduced statistical
  bias.
- Bad, because the lawfulness of opt-out reporting is not established for every jurisdiction.
  Swiss obligations and possible foreign device-access rules remain separate questions.

### Explicit opt-in consent question at setup

Setup asks whether to enable reporting, defaults to no, and collects nothing until the user says
yes. Status: considered but not selected. The assistant initially recommended it as the
conservative privacy approach.

- Good, because participation is affirmative and easier to justify under strict readings of
  device-access rules.
- Good, because no data leaves a machine whose operator never chose to share it.
- Bad, because the maintainer expects low participation, which would hide still-used versions.
- Bad, because unattended installs would never answer, leaving headless servers uncounted.

### PostHog Cloud EU, dedicated project, direct requests

Symphony posts directly to `https://eu.i.posthog.com/i/v0/e/` with the public token of a project
that holds only Symphony data, separate from the `fmartin.ch` project in the same organization.
Status: selected; the maintainer explicitly agreed to direct delivery and already uses PostHog.

- Good, because the maintainer keeps one analytics account, gets hosted storage and dashboards,
  and does no routine hosting work.
- Good, because a separate project keeps the purpose, token, dashboards, and identities apart
  from website analytics.
- Good, because the organization now runs on pay-as-you-go, which allows several projects; the
  maintainer asked to stay inside the free allowance.
- Bad, because PostHog and its own service providers process the request, including network
  metadata that the JSON body does not show.
- Neutral, because the organization arrangement (second free organization versus billing on the
  existing one) was an administrative choice, not an architectural one; billing on the existing
  organization is what the maintainer chose on 2026-09-22.

### Mixpanel, Aptabase, or self-hosted analytics

Another hosted product or a self-hosted PostHog or equivalent receives the same events. Status:
considered but not selected, at discussion level only; no benchmark was run.

- Good, because Mixpanel is a plausible hosted alternative with comparable capabilities.
- Good, because self-hosting keeps all data under the maintainer's control.
- Bad, because there was no recorded reason for an existing PostHog user to switch.
- Bad, because Aptabase's default cross-day identification model was judged a poor fit for
  longitudinal installation tracking.
- Bad, because self-hosting adds operations that the maintainer wants to avoid.
- Neutral, because vendor prices, quotas, and report limits mentioned in the discussion were not
  verified and are not repeated here as current facts.

### Custom ingestion and storage service

A small Cloudflare Workers and D1 style service receives, stores, and dashboards the events.
Status: considered but not selected.

- Good, because rolling deletion and retention could be enforced exactly.
- Bad, because it needs ingestion, access control, dashboard, and deletion-job code and
  operations for a tiny schema.

### Proxy or telemetry backend in front of the provider

An intermediary validates payloads, masks source addresses from the provider, or supports
administrative workflows. Status: considered but not selected; a revisit trigger is a strict
lifecycle requirement or demonstrated abuse.

- Good, because it could sanitize payloads and hide client addresses from PostHog.
- Bad, because it adds deployment, credentials, logs, and responsibilities, and relocates rather
  than removes IP processing.
- Bad, because it is not a privacy-law exemption by itself.

### Narrow typed capture adapter on the JDK HTTP client

`PostHogCaptureClient` posts exactly the serialized body with two explicit headers, never follows
redirects, bounds timeouts and response size, and maps responses to accepted, quota-limited,
transient, or permanent outcomes. Status: selected.

- Good, because one event, a small retry policy, and exact-body inspection need no SDK queueing
  or lifecycle.
- Good, because the CLI runs outside Quarkus and the repository's Trello clients already use
  `java.net.http.HttpClient`; no new transport convention is introduced.
- Good, because no hidden SDK fields can be appended after the preview boundary.
- Bad, because the project owns and must verify the wire behavior itself.

### PostHog Java SDK

The official SDK batches events, manages a queue, and adds library metadata. Status: considered
but not selected.

- Good, because it provides tested queueing and retries with little code.
- Bad, because its automatic properties and batching make the exact body harder to preview and
  freeze.
- Neutral, because SDKs are not inherently unsafe; the trade is explicitness for maintenance.

### Quarkus REST Client

A `@RegisterRestClient` interface with Jackson serialization sends the event. Status: considered
but not selected.

- Good, because it fits the worker's Quarkus runtime and configuration model.
- Bad, because the `telemetry` CLI commands run in a plain JVM without CDI, so a second transport
  would be needed there.

### Browser SDK with autocapture

The local status page loads `posthog-js`. Status: rejected as unsuitable for installation metrics.

- Bad, because it measures browser visits, not running installations, and multiplies identities
  per browser.

### Random persisted installation UUID

`telemetry.json` holds one random UUID created locally the first time an installed,
token-configured, enabled context registers, at setup or at the first ready worker, and its
creation date is `registered_on`. Status: selected.

- Good, because upgrades keep the identity and five workers share it.
- Good, because deleting the state directory ends recognition; nothing on the machine can
  recreate the old identity.
- Bad, because copying the whole state directory copies the identity, and deleting it creates a
  second reporting context; both are documented instead of fingerprinted away.
- Neutral, because a random UUID does not prove legal anonymity, and avoiding a stored UUID would
  not avoid device-access questions.

### System-derived or hashed machine identifier

The identity comes from `/etc/machine-id`, MAC addresses, hostname, or a hash of them. Status:
considered but not selected.

- Good, because it survives state deletion.
- Bad, because it recognizes a machine after the user intentionally removed the state, and
  hashing does not change that.
- Bad, because it adds platform-specific probes for no benefit the requirement needs.

### PostHog cookieless daily-rotating hashes

PostHog derives a server-side identity that changes every day. Status: considered but not
selected.

- Good, because no identifier is stored on the client.
- Bad, because daily rotation removes the cross-day linkage needed for upgrade tracking, 30 day
  activity, and reactivation; adding a permanent ID would restore exactly what it removes.

### Per-worker or per-session identities

Each worker JVM or each browser session reports as its own unit. Status: rejected.

- Bad, because a five-board installation would count five times and every restart or browser
  visit could add more.

### Registration date alternatives

Reconstructing the original installation date, using the first successful server contact,
sending one permanent registration event, or preserving the date through `$set_once`. Status:
considered but not selected; the maintainer explicitly accepted telemetry registration as the
starting point.

- Good, because a profile property or a registration event could preserve the fact without
  repeating it.
- Bad, because reconstructing history is unreliable and unnecessary.
- Bad, because a delayed first delivery would silently redefine registration.
- Bad, because a single old event is fragile under retention and event loss; repeating a few
  scalars in every heartbeat is intentional.

### One self-contained heartbeat with cumulative counters and normalized platform fields

The only event carries the registration date, installed release, platform fields, current board
count, and cumulative successful import and create totals. Status: selected.

- Good, because any recent event is independently useful, with no dependency on older records
  surviving retention.
- Good, because cumulative totals answer the import-versus-create question that net board-count
  changes cannot.
- Good, because the installed release comes from the installer's `install-context.properties`,
  which is rewritten only after a successful install or update, not from whichever worker won the
  reporting lock.
- Bad, because precise operation and upgrade timestamps and funnels are lost.
- Bad, because zero means no operation observed while counting was enabled, not proven lifetime
  non-use; boards connected before telemetry have unknown history.
- Neutral, because totals need not add up to the current board count and are deliberately never
  decremented on disconnect.

### Lifecycle events in addition to heartbeats

`installation_registered`, `board_connected`, and version events accompany heartbeats. Status:
considered but not selected; the discussion moved from several events to two and then to one.

- Good, because precise timing and funnels become possible.
- Bad, because analysis then depends on delivery and retention of older records.

### Board inventory inference or current-origin categories

Infer additions from count increases, or report only the origin of the currently connected boards.
Status: considered but not selected.

- Good, because no counters need to be stored.
- Bad, because a count increase cannot tell import from creation, and current-origin categories
  forget history once a board is disconnected.

### Attempt and failure funnels

Started and finished events with success, failure, and cancellation outcomes and failure
categories. Status: deferred, not rejected as useless.

- Good, because a broken import flow would show as many attempts with few successes.
- Bad, because it needs extra event types and error categories that the minimal contract excludes.
- Neutral, because low success counts now cannot separate no demand from unsuccessful attempts;
  the dashboard documentation carries that qualification.

### Buckets, worker counts, and a "did work" boolean

Board-count buckets such as 6-10 or 21+, running-worker counts, and a boolean separating a running
daemon from useful work. Status: buckets appeared in the initial proposal and later schemas used
exact scalars without a recorded maintainer rejection; worker counts and the boolean are out of
scope.

- Good, because the boolean would answer engagement better than a heartbeat that only proves a
  running worker.
- Neutral, because exact small counts were kept for simplicity, not because buckets were judged
  wrong.

### Reporting-worker version or version sets

The event carries the build of the sending worker, both installed and running versions, or the
set of running-worker versions. Status: considered but not selected.

- Good, because a version set would show mixed states after an upgrade.
- Bad, because an old worker can stay alive and win the reporting lock, so its build is not the
  installed release; the selected meaning matches the adoption question without a set.

### Coarse normalized platform fields through OSHI plus os-release

`oshi-core-ffm` 7.6.1, the Foreign Function and Memory implementation without JNA, supplies the
Windows product release (10, 11, or Server year from the build number) and the macOS product
version. On Linux, a small reader takes `ID` and `VERSION_ID` from `/etc/os-release` because OSHI
exposes the distribution `NAME` rather than the stable `ID`. `PlatformNormalizer` maps everything
onto a fixed vocabulary. Status: selected; the maintainer wanted actionable platform detail.

- Good, because Windows generations, macOS majors, and Linux distributions with support-relevant
  precision are available without raw strings.
- Good, because a maintained detector avoids accumulating fragile custom probes for Windows and
  macOS.
- Bad, because the dependency needs `--enable-native-access=ALL-UNNAMED`, now added to both
  installed wrappers, the worker launcher, and the runner-jar manifest.
- Bad, because OSHI's Linux implementation runs `uname` once at class initialization and Windows
  detection uses WMI; both are lazy and cached but not free.
- Neutral, because a container, WSL, or VM reports its Linux runtime environment; no host or
  virtualization probing exists.

### OS family alone, raw builds, or host probing

Send only `os_family`; or send kernel builds and raw descriptions; or detect the host under a
container or VM. Status: considered but not selected.

- Good, because family alone is the smallest possible field.
- Bad, because family alone omits the release distinctions the maintainer asked for, raw builds
  exceed the question, and host probing adds identification risk.

### Worker-level collection with one accepted snapshot per active UTC day

Setup updates counters locally; only running workers send. A worker checks when ready and then
every minute through Quarkus scheduling; after the shared deadline it attempts promptly when no
heartbeat for today's UTC date is accepted. Status: selected.

- Good, because installations whose users never open the dashboard are represented, and several
  browsers do not multiply installations.
- Good, because laptops report on eligible startup or the first check after resume; brief
  sessions are not missed by an uptime requirement.
- Bad, because a session shorter than five minutes is never counted, and days without a running
  worker are not backfilled.
- Neutral, because UTC-day scheduling can report twice close to midnight; that is accepted.

### Dashboard visits or a separate daemon

Track visits to the local status page, or keep a telemetry-only process alive. Status: considered
but not selected.

- Good, because a daemon would report even when no board is running.
- Bad, because a daemon reports independently of actual use and adds lifecycle work, and visit
  tracking measures browsers, not installations.

### Hourly, fixed-time, or continuous-uptime schedules

Report every hour, at a fixed clock time, or after 24 hours of continuous runtime. Status:
considered but not selected.

- Good, because a fixed time is easy to reason about.
- Bad, because fixed-time and continuous-uptime designs miss brief laptop sessions; the specific
  rejected failure was waiting for long uninterrupted uptime, not every rolling-24-hour design.
- Bad, because hourly reports add volume without value at the accepted daily precision.

### Five minutes versus immediate, ten minutes, or 24 hours

The first report may leave five minutes after the first ready worker; explicit `telemetry
enable` needs no new grace period. Status: selected as a compromise. The assistant first
recommended 24 hours; the maintainer objected that a brief trial could end before the first
report; ten minutes was considered, then five.

- Good, because users get a moment to act before dispatch, and the countdown starts at the
  first ready worker rather than during a possibly long installation.
- Bad, because trials shorter than five minutes are still missed.
- Neutral, because no evidence established five or ten minutes as a statutory or empirically
  optimal threshold; a timer is not a substitute for consent where consent applies.

### Non-blocking notice versus menus and pending headless installs

Setup and the first ready worker print a short notice; unattended installs keep the default and
honor `SYMPHONY_TRELLO_TELEMETRY_DISABLED=1` from the start. Status: selected; the maintainer
found an acknowledgement menu too close to opt-in.

- Good, because installation stays smooth and headless installs complete.
- Bad, because a printed notice in a service log does not prove a human read it; no consent
  record is created, and an installer `--yes` is not treated as informed consent.
- Neutral, because the earlier proposal to leave headless installs pending is superseded, not a
  second required behavior.

### Immediate preview and persistent debug without a watch command

`telemetry preview` builds and prints the full JSON now without any write; `telemetry debug`
switches the installation to local-only mode and workers print a preview at startup and when the
snapshot changes; `SYMPHONY_TRELLO_TELEMETRY_LOG=1` logs actual requests. No `--watch`. Status:
selected; the maintainer rejected watch as unnecessary complexity.

- Good, because snapshot construction is independent of the upload schedule, so inspection needs
  no traffic and no waiting for the daily send.
- Good, because timestamp and UUID differences alone never trigger repeated debug output.
- Bad, because there is no live view of changes other than watching the worker log.

### Informative disable review with default No and no early persistence

In a terminal without `--yes`, the command prints the full JSON first, then asks
`Disable telemetry? [yes/No/privacy]`; Enter, no, end of input, or a crash leave the preference
unchanged; yes persists atomically and discards pending work. Non-interactive input disables
directly. Completion copy is factual first, with light humor only after disabling. Status:
selected; explicit maintainer preference from a real Next.js experience where seeing the small
report changed a colleague's decision.

- Good, because the evidence is at the moment of concern with no extra discovery step, and pretty
  JSON is legible for this developer audience.
- Good, because cancellation is a no-write operation, so it cannot overwrite another command's
  concurrent change, and a confirmed disable survives later crashes.
- Bad, because it adds one interaction to a reversible action; the assistant's earlier
  recommendation to disable immediately had merit and is not claimed to be wrong.
- Neutral, because no cross-worker pause exists while the dialog is open; a report already in
  flight cannot be retracted, and the CLI does not claim otherwise.
- Neutral, because neither default No nor the prompt shape is a legal requirement or clearance.

### Immediate disable, summary tables, or default yes

Disable on command without review; show a summary table or a separate key to reveal JSON;
default to yes because the command says disable; persist disabled before the review and
re-enable on no. Status: considered but not selected; the maintainer specifically rejected
changing the saved mode on opening or crashing.

- Good, because immediate disable and default yes reduce opt-out friction.
- Bad, because a table can hide fields and a hidden JSON step defeats the purpose of the review.
- Bad, because persisting before confirmation turns a crash into a silent preference change.

### Extra script flags and guilt-oriented copy

Require a second confirmation flag even in scripts, name it after "not helping", or print a sad
message. Status: considered but not selected; the maintainer wanted less-boring copy and a visible
thank-you, never judgment.

- Bad, because self-disparaging phrasing is friction for automation and privacy choices.
- Neutral, because wording iterations are UX history, not architecture decisions.

### Purpose-only wording versus broad privacy promises

The notice says "François Martin uses these reports only to improve symphony-trello", with the JSON
as evidence and the bundled privacy document for detail. Status: selected.

- Good, because the sentence describes a commitment the maintainer controls.
- Bad, because "guaranteed anonymous", "never shared", or "nothing else is sent" would be false:
  PostHog and its providers receive the data, and network metadata exists outside the JSON.
- Neutral, because the provider's secondary-use opt-out still has to be verified; friendlier
  wording establishes no legal or provider fact.

### Shared file state with OS lock, atomic replace, and short claims

`TelemetryStateStore` opens `telemetry.lock`, takes an OS `FileLock` after a JVM striped lock,
reads `telemetry.json`, applies a short transaction, and replaces the file atomically. A worker
claims the day's report for 90 seconds, releases the lock, sends asynchronously, and records the
outcome only if its claim and the preference revision are unchanged. Corrupt, future-format, or
unlockable state suppresses reporting instead of regenerating identities. Status: selected.

- Good, because several JVMs cannot create duplicate identities or reports or lose increments,
  and a dead claim owner cannot silence the installation for longer than the claim lifetime.
- Good, because the lock is never held across a request or a prompt, so setup and preferences
  stay responsive.
- Bad, because a maximum-one-pending design and a stale-completion fence are more code than a
  single-process design would need.
- Neutral, because a database or transactional ledger would be excessive for approximate,
  subordinate telemetry.

### Per-worker markers, in-process locks, or lock-file existence

Each worker keeps its own identity or marker; a Java `synchronized` block or Quarkus `SKIP`
coordinates; an existing lock file means ownership; the lock is held during HTTP. Status:
considered but not selected.

- Bad, because in-process mechanisms do not coordinate separate JVMs, lock-file existence is not
  ownership, and holding the lock across HTTP blocks setup during a slow request.

### Best-effort bounded delivery with stale replacement

At most one pending report; retries reuse its UUID, timestamp, and properties with backoff of 1,
5, 15, then 60 minutes plus up to 30 seconds of jitter, honoring `Retry-After` up to one hour;
permanent errors defer to the next UTC day; a pending report expires at the next UTC day and is
replaced by a current observation. Status: selected.

- Good, because old buffered activity never masquerades as present use, and telemetry never
  blocks Trello or Codex work.
- Bad, because small undercounts and ambiguous retry duplicates are possible; this is not an
  exactly-once transport, and PostHog acceptance is not proof of retention.

### Unlimited queues, backfill, or blocking startup

Queue offline days, backfill missed heartbeats, change timestamps on retry, or block startup until
telemetry succeeds. Status: rejected.

- Bad, because each either misrepresents present use or lets optional analytics impair the
  product.

### Thirty days of silence as inactivity, not uninstall

An installation is active within the last 1, 7, or 30 days when it reported; after 30 days it is
inactive; a later report is reactivation. Status: selected as the maintainer's operational
definition with a qualified label. The maintainer's earlier assumption treated a month of silence
as uninstalling; six-week breaks were raised as a counterexample, without evidence of how common
they are.

- Good, because the definition is simple and stable.
- Bad, because telemetry cannot distinguish removal from stopped workers, blocked traffic,
  disabling, or a later return.

### One-year event-analysis horizon without a custom purge

Use the provider's detailed event window for analysis, with the registration date and cumulative
counters repeated in fresh heartbeats so summary facts outlive individual events. Status:
selected; a rolling 30 day purge and permanent linked retention are both not chosen. The
maintainer initially favored keeping less data forever to preserve registration context;
self-contained snapshots removed that need.

- Good, because no export service, warehouse, or deletion job is needed for the first release.
- Bad, because a query window does not prove physical deletion of rows, profiles, backups, or
  logs; the project's `event_retention_months` and `events_retention_enforced` were null on
  2026-09-22 and must be verified.
- Neutral, because 30 days is an activity rule, not the desirable history length, and deleting
  inactive profiles is not the same as deleting all old events of active installations.

### Minimal profile and private maintainer-side erasure

`$process_person_profile: true` keeps one profile per installation UUID so the maintainer can
locate and delete an installation's events; no `identify`, `alias`, `$set`, or `$set_once` is
sent. Ordinary disable stops collection and freezes counters without sending an erasure request
or rotating the identity. After a provider-side erasure the user has no local action: the stored
disable keeps reporting off, and a later enable resumes with the same identity and counters.
Three sequences were distinguished: a local reset or identity retirement (not selected; the
maintainer rejected it during review), ordinary disable and re-enable with the same identity
(selected), and a provider-side repair through PostHog's `reset_person_distinct_id` call. The
repair is not part of the client and is documented in the runbook only for the case the live
experiment shows it to be required; the source of that call shows it does nothing before the id
has been reused, so it is never run as a proactive step during erasure handling. Status:
selected.

- Good, because erasure is possible without a backend or an embedded administrative key.
- Good, because repeated snapshots make summary properties unnecessary.
- Bad, because erasure is a manual private process, and whether an opt-out obliges erasure is
  case-dependent; the ADR concludes neither always nor never.
- Neutral, because personless capture was considered; it was not established that personless
  records can never be deleted, only that a profile makes the process straightforward.

### Personless capture, profile summaries, or self-service deletion

Capture without profiles, keep `$set_once` summaries, erase remotely on every local disable, or
offer a public deletion backend. Status: considered but not selected.

- Good, because personless capture is cheaper and a backend would make erasure self-service.
- Bad, because a local setting change is not a remote request, a backend needs credentials and
  operations, and an embedded admin key is unacceptable.

### Aggregate exports or a historical warehouse

Export monthly coarse aggregates for trends beyond the provider window. Status: deferred after
the maintainer preferred the simpler one-year setup; not rejected as inappropriate.

- Good, because trends could extend past the event window.
- Bad, because it adds a scheduled workflow and aggregation and privacy decisions the first
  release does not need.
- Neutral, because a saved dashboard is not an archive, and repeated registration dates do not
  reconstruct installations that left retained history.

### Latest-snapshot dashboards with defined cohorts

Every current-state panel picks one latest coherent event per recently active installation,
upgrade analysis uses installations observed before a release, and adoption is counted per
installation. Status: selected; queries live in
[docs/telemetry-dashboard.md](../telemetry-dashboard.md).

- Good, because the same installation under several historical versions, the same total repeated
  daily, and fresh installs that never upgraded are not miscounted.
- Bad, because measured delay is first-observed adoption, not exact update time, and cohort
  completeness is bounded by retained observations.

### Public token only, no anti-fraud layer

Only the dedicated project's public capture token ships with the application. The repository keeps
`src/main/resources/symphony-trello-telemetry.properties` at `<unset>`; the release workflow writes
the token from the `POSTHOG_PROJECT_TOKEN` repository secret into that file while packaging the
release archives, so builds from a checkout, tests, CI, and development runs have no token at all.
No administrative key exists in the distribution; duplicates, test traffic, and client metadata are
prevented locally. Status: selected; the maintainer asked for the release-time injection so that
CI and development cannot send by construction, and considers deliberate spoofing an unlikely
practical concern.

- Good, because the public token cannot read data, and test and development runs never send: they
  carry no token, and the installed-context gate is a second layer.
- Bad, because installs made with `install.sh --from-source` build from a checkout and therefore
  never report.
- Bad, because a public ingestion endpoint can receive fabricated events; this is documented, not
  prevented. Evidence of abuse is the trigger to revisit.
- Neutral, because omitted task, error, and repository metrics are intentionally out of scope
  regardless of how easy an SDK would make them.

## More Information

Related repository decisions:

- [ADR 0042](0042-application-clock-boundaries.md): all telemetry timing takes an injected
  `Clock`.
- [ADR 0049](0049-release-archive-installer.md) and [ADR 0063](0063-microos-and-xdg-installer-layout.md):
  the installer writes `install-context.properties` into the state home, which telemetry reads
  for the installed release and as the installed-context marker.
- [ADR 0057](0057-path-safe-filesystem-blockers.md): no local path is transmitted.

Supporting documents in this change:

- [docs/telemetry-privacy.md](../telemetry-privacy.md): the bundled user-facing privacy text.
- [docs/telemetry-dashboard.md](../telemetry-dashboard.md): panel definitions and HogQL queries.
- [docs/telemetry-maintainer-runbook.md](../telemetry-maintainer-runbook.md): provider
  configuration, verification, erasure, and reset procedures.

Primary sources consulted:

- [PostHog capture API](https://posthog.com/docs/api/capture): endpoint, body fields, and the
  note that missing names or distinct IDs are dropped with a 200 response.
- [PostHog event retention](https://posthog.com/docs/data/events-retention): the
  `event_retention_months` and `events_retention_enforced` fields and the query-window behavior.
- [PostHog data storage and deletion](https://posthog.com/docs/privacy/data-storage): IP
  discarding, GeoIP, and asynchronous person deletion.
- [PostHog privacy policy](https://posthog.com/privacy) and [PostHog DPA](https://posthog.com/dpa):
  product and model-development use of customer content and the opt-out through service settings.
- [PostHog cookieless tracking](https://posthog.com/tutorials/cookieless-tracking): the daily
  rotating identity model.
- [PostHog SQL aggregations](https://posthog.com/docs/sql/aggregations): `argMax`, `uniq`, and
  related functions used by the dashboard queries.
- [Next.js telemetry](https://nextjs.org/telemetry): the opt-out and debug precedent the
  maintainer referred to.
- [Quarkus scheduler reference](https://quarkus.io/guides/scheduler-reference) and
  [Quarkus REST Client](https://quarkus.io/guides/rest-client).
- [OSHI](https://www.oshi.ooo/): the FFM implementation and its native-access requirement.
- [Java FileLock](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html).
- [MADR](https://adr.github.io/madr/) and the
  [full MADR 4.0.0 template](https://raw.githubusercontent.com/adr/madr/4.0.0/template/adr-template.md).
- [Swiss FDPIC factsheet on cookies and similar technologies](https://www.edoeb.admin.ch/en/factsheet-cookies),
  [GDPR territorial scope](https://eur-lex.europa.eu/eli/reg/2016/679/oj/eng), and
  [Germany's device-access rule](https://www.gesetze-im-internet.de/ttdsg/__25.html): the
  applicability questions that remain open.

Coverage of the decision-history catalog:

| ID | Dimension | Status | Where |
| --- | --- | --- | --- |
| A01 | Opt-out versus opt-in | Selected, maintainer preference | Default-enabled reporting; Explicit opt-in consent question |
| A02 | PostHog versus other backends | Selected; alternatives at discussion level | PostHog Cloud EU; Mixpanel, Aptabase, or self-hosted; Custom ingestion service |
| A03 | Separate project versus mixing | Selected | PostHog Cloud EU, dedicated project |
| A04 | Direct ingestion versus proxy | Selected, maintainer agreed | Proxy or telemetry backend |
| A05 | Narrow HTTP versus SDK | Selected | Narrow typed capture adapter; PostHog Java SDK; Quarkus REST Client; Browser SDK |
| A06 | Random UUID versus derived IDs | Selected | Random persisted installation UUID; System-derived identifier; Cookieless hashes |
| A07 | Installation as counting unit | Selected | Random persisted installation UUID; Per-worker identities |
| A08 | One heartbeat versus lifecycle events | Selected | One self-contained heartbeat; Lifecycle events |
| A09 | Registration date definition | Selected, maintainer accepted | Registration date alternatives |
| A10 | Board count plus operation totals | Selected | One self-contained heartbeat; Board inventory inference |
| A11 | Successes versus funnels | Deferred | Attempt and failure funnels; docs/telemetry-dashboard.md |
| A12 | Scalars versus buckets | Selected without recorded rejection of buckets | Buckets, worker counts, and a boolean |
| A13 | Installed release versus worker build | Selected | One self-contained heartbeat; Reporting-worker version |
| A14 | Coarse platform detail | Selected, maintainer wanted detail | Coarse normalized platform fields; OS family alone |
| A15 | Worker-level collection | Selected | Worker-level collection; Dashboard visits or a daemon |
| A16 | Daily active-day schedule | Selected | Worker-level collection; Hourly, fixed-time, or uptime schedules |
| A17 | Five-minute first report | Selected as compromise; threshold unresolved | Five minutes versus alternatives |
| A18 | Non-blocking notice | Selected, maintainer preference; consent evidence unresolved | Non-blocking notice versus menus |
| A19 | Immediate preview and debug, no watch | Selected, watch rejected by maintainer | Immediate preview and persistent debug |
| A20 | Actual JSON in the review | Selected, maintainer preference | Informative disable review |
| A21 | Review versus immediate disable | Selected, maintainer preference | Informative disable review; Immediate disable |
| A22 | Default No and crash semantics | Selected, maintainer rejected early persistence | Informative disable review; Default yes |
| A23 | Script flags and humor | Selected | Extra script flags and guilt-oriented copy |
| A24 | Purpose-only wording | Selected; provider opt-out unresolved | Purpose-only wording; docs/telemetry-privacy.md |
| A25 | Shared file state | Selected | Shared file state; Per-worker markers |
| A26 | Bounded best-effort delivery | Selected | Best-effort bounded delivery; Unlimited queues |
| A27 | Thirty-day inactivity label | Selected with qualified label | Thirty days of silence |
| A28 | One-year horizon | Selected; deletion guarantees unresolved | One-year event-analysis horizon; docs/telemetry-maintainer-runbook.md |
| A29 | Profiles and erasure | Selected; legal obligation case-dependent | Minimal profile; Personless capture |
| A30 | Archives | Deferred | Aggregate exports or a historical warehouse |
| A31 | Dashboard measures | Selected | Latest-snapshot dashboards; docs/telemetry-dashboard.md |
| A32 | Ingestion integrity | Selected, no anti-fraud | Public token only |
