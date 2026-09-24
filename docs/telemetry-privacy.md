# Usage reporting privacy

Symphony for Trello can send one small usage report per day while a managed worker is running.
This page says what is in a report, who receives it, what you can choose, and what the maintainer
can and cannot do with it. Print it offline with `symphony-trello telemetry privacy`.

## Purpose

François Martin, the maintainer, uses these reports only to improve symphony-trello. They answer
questions such as: how many installations report, which releases are still in use, how often
installations run one board or several, whether the import and create flows are used, and which
operating systems and architectures deserve testing. The reports are not sold, not used for
advertising, and not combined with visitor data from the maintainer's website.

## What a report contains

Every report is a single `installation_heartbeat` event with these fields and nothing else. Run
`symphony-trello telemetry preview` to see the exact JSON body a report would have right now.

Envelope fields:

- `api_key`: the public PostHog project token of the Symphony for Trello project. It only lets a
  client add events to that project; it cannot read anything.
- `event`: always `installation_heartbeat`.
- `distinct_id`: a random installation ID and, when automatic erasure is configured, a random
  reporting-period ID separated by a dot. PostHog issues the installation ID for this mode; the
  application creates the period locally. Existing installations keep their original UUID.
  Workers share the stored ID. Neither part comes from hardware, hostname, user name, or Trello
  identity.
- `uuid`: a random ID for this one report. Retries of the same report reuse it.
- `timestamp`: the UTC time the report was built. Retries keep the original time.

Properties:

- `telemetry_schema_version`: the version of this field list, currently `1`.
- `registered_on`: the UTC date the installation ID was created. This is not the original
  installation date; installations older than the telemetry feature register when they first
  report.
- `app_version`: the installed release as recorded by the installer, for example `1.2.0`, or `null`
  when unknown. Only release archives carry the project token; builds from a source checkout have no
  token and never send at all.
- `os_family`: `windows`, `macos`, `linux`, `other`, or `unknown`.
- `os_release`: a coarse product release. Windows reports `10`, `11`, or `server_YYYY`. macOS
  reports its major release such as `26`. Linux reports a support-relevant release such as `24.04`
  for Ubuntu or `13` for Debian, `rolling` for rolling distributions, and `unknown` when the release
  is not recognized. Build numbers, kernel versions, and patch levels are never sent.
- `linux_distribution`: on Linux, a distribution ID from a fixed list (for example `ubuntu`,
  `debian`, `fedora`, `arch`, `alpine`, `opensuse-leap`), `other` for an unlisted distribution, or
  `unknown`. On other systems it is `null`.
- `runtime_arch`: the Java runtime architecture: `x64`, `arm64`, `x86`, `other`, or `unknown`.
- `connected_board_count`: how many distinct Trello boards are connected in the local manifest, or
  `null` when the manifest cannot be read. Board names and IDs are never sent.
- `board_imports_total`: how many successful board imports this installation completed while
  reporting was enabled or in debug mode. Boards connected before telemetry existed are not counted.
- `board_creations_total`: the same for board creations.
- `$geoip_disable`: always `true`. It asks PostHog not to add location data derived from the
  connection.
- `$process_person_profile`: always `true`. It keeps a minimal PostHog profile per analytics ID
  so the maintainer can find and erase one installation's reports on request.

Not included, in any form: Trello card, list, or board names and IDs, Trello or GitHub credentials,
prompts, source code, repository URLs, file paths, user names, host names, hardware identifiers,
IP addresses chosen by the client, task counts, error messages, or full operating-system builds.

## Who receives a report

Reports go directly from your machine over HTTPS to PostHog's EU region endpoint,
`https://eu.i.posthog.com/i/v0/e/`. PostHog is a third-party analytics provider based in the United
States that hosts this project in Frankfurt, Germany. PostHog and its own service providers process
the request as a data processor for the maintainer under PostHog's data processing agreement.

Like every HTTPS request, the connection carries network metadata that is not part of the JSON body,
in particular your public IP address and TLS connection details. The Symphony for Trello project is
configured to discard the client IP address instead of storing it with the event, and its GeoIP
enrichment is turned off. PostHog's own infrastructure logs may still hold connection data for a
short time under PostHog's policies. Showing the JSON body therefore proves what the application
sends, not that no metadata exists on the network path.

## Your choices

- Reporting is on by default in installed copies. The first report is sent at the earliest five
  minutes after the first worker starts, so you can turn it off first.
- `symphony-trello telemetry disable` turns it off for the whole installation. In a terminal the
  command first shows the exact JSON a report would contain and asks `Disable telemetry?
  [yes/No/privacy]`; pressing Enter keeps reporting on, and `privacy` shows this page and then asks
  the same question again. Scripts and piped input disable directly, and `--yes` skips the review
  in a terminal.
- `symphony-trello telemetry enable` turns it back on. The next report is sent by a running worker.
- `symphony-trello telemetry debug` keeps everything local: workers print what they would send and
  send nothing. It does not replace a disabled setting; enable first if you want that.
- `symphony-trello telemetry status` shows the stored and effective mode, the installation and
  analytics IDs, the erasure state when one exists, and the last accepted report.
  `symphony-trello telemetry preview` prints the JSON body without sending, storing, or
  registering anything.
- `SYMPHONY_TRELLO_TELEMETRY_DISABLED=1` in a process's environment disables reporting for that
  process and its children, including counting. `SYMPHONY_TRELLO_TELEMETRY_DEBUG=1` switches those
  processes to local-only mode and cannot override a stored disable. `SYMPHONY_TRELLO_TELEMETRY_LOG=1`
  prints every actual request and its outcome into the worker log; it never enables sending.
- Setting `SYMPHONY_TRELLO_TELEMETRY_DISABLED=1` before an unattended installation stores the
  disabled preference before any worker starts.

Turning reporting off stops new reports and freezes the counters. It does not delete reports that
were already received; see the erasure section below. Turning it back on keeps the same
installation ID.

## Persistent identifiers

The installation ID, the registration date, the counters, and the preference are stored in
`telemetry.json` in the Symphony for Trello state directory (by default
`~/.local/state/symphony-trello`). Copying that directory to another machine copies the ID;
deleting it and reporting again creates a new ID and a new registration date. The maintainer does
not try to recognize a machine across such changes.

## Access and erasure

When the installed release has automatic erasure configured, run
`symphony-trello telemetry erase`. It turns reporting off and saves the request locally.
`symphony-trello telemetry erase-status` checks progress; running workers also retry, less often
as the request ages, and at least every six hours. If this installation never sent a report,
`erase` only turns reporting off because there is nothing to erase.
"Accepted" means PostHog has queued deletion. "Complete" means one of three things. PostHog
verified event deletion and the person profile is absent. Or a fresh check found no profile and
no retained events. Or the current reporting period never started sending a report, so the
command marks erasure complete locally without contacting PostHog. Deletion is asynchronous, so
completion can take days.
Reporting stays off across restarts and updates. Explicit `symphony-trello telemetry enable`
after completion starts a new reporting period while preserving the installation ID,
registration date and counters. Ordinary disable/enable keeps the current period.

Ownership uses a secret stored in `telemetry.json`. Keep this file and its backups private.
The secret is issued over HTTPS before the first report. Erasure requests send a signature,
never the secret. PostHog stores versioned signing keys separately from analytics events, so
ownership does not depend on an enrollment event or one-year event retention. An opaque status
flag records deletion progress. After the client saves completion locally, it sends one
best-effort request to archive the flag and ignores a failure. The maintainer archives leftover
flags. Do not restore a pre-erasure backup to resume reporting under an erased period.

If automatic erasure is unavailable or the installation predates ownership credentials,
disable reporting and send the installation and analytics IDs from
`symphony-trello telemetry status` through the private "Report a vulnerability" form linked in
`SECURITY.md`. Do not post the IDs or state file publicly. The maintainer checks the matching
profile and confirms event deletion separately. A profile containing unexpected IDs requires
manual investigation. Merge filtering reduces the risk of deleting another installation's
merged data but does not eliminate it during provider filter failures.

Automatic erasure can be refused when PostHog's profile for the analytics ID is in an
unexpected state, for example when it holds other IDs after a merge. `erase-status` then says so.
The installation stays disabled: `symphony-trello telemetry enable` refuses while the erasure is
refused, and no command clears that state. Send the installation and analytics IDs from
`symphony-trello telemetry status` through the same private form. The maintainer investigates
the merge, then deletes the data manually or asks PostHog support to delete it.

If `telemetry.json` was lost, `symphony-trello telemetry status` shows "not registered yet" and
the old IDs cannot be recovered from the installation. Send them from earlier status output if
you still have it. Without the IDs, the maintainer cannot tell which reports came from your
installation and cannot erase them on request. They stay subject to the retention described
below.

Backups and infrastructure logs follow PostHog's own schedules. Disabling reporting alone does
not erase reports already received. The automatic path remains disabled in production until
its hosted lifecycle checks pass.

## Retention

PostHog keeps event data for analysis according to the project's plan and settings. The maintainer
intends to analyze at most one year of detailed events and to rely on the repeated registration
date and counters in fresh reports for anything older. Turning reporting off does not erase
history. Thirty days without a report is how the maintainer defines an inactive installation; it
does not delete anything.

## Limitations

- A report counts a reporting installation, not a person, a download, or a confirmed uninstall.
- An installation that is offline, blocked, disabled, or on a release before telemetry existed is
  not counted.
- Delivery is best effort. A dropped or quota-limited report is not retried the next day; the next
  daily report carries the current state again.
- Nothing here is legal advice, and no default or waiting period is claimed to be legally required.
