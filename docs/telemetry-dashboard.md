# Usage reporting dashboard

This page explains the maintainer dashboard for installation telemetry, one section per panel. The
queries themselves are the files under `infra/posthog/queries/`: OpenTofu creates each saved insight
from its file (see [docs/posthog-infrastructure.md](posthog-infrastructure.md)), and
`scripts/posthog-infra fixture` runs the same files against the test project and compares the
results with `infra/posthog/fixtures/dashboard-fixture.json`. Change a query in its file, apply,
then update the explanation here. Read [docs/telemetry-privacy.md](telemetry-privacy.md) first for
what a report contains and [docs/telemetry-maintainer-runbook.md](telemetry-maintainer-runbook.md)
for project setup.

## Rules every panel follows

- Every report is one `installation_heartbeat` event. `distinct_id` is the installation ID.
- Current-state panels use one latest coherent snapshot per installation: a single `argMax` over a
  tuple of all fields, ordered by `(timestamp, uuid)`, grouped by `distinct_id`. One winning event
  supplies every field, nulls included. A separate `argMax` per field would skip null arguments and
  could pair today's version with last week's board count.
- `board_imports_total` and `board_creations_total` are cumulative counters repeated in every daily
  report. Never `sum()` them across reports. Read them from the latest snapshot per installation
  and count installations, not operations.
- `null` stays separate from `0`. `connected_board_count` is `null` when the manifest was
  unreadable; `app_version` is `null` for source builds. Show them as their own bucket.
- Every number is bounded by the observation window. An installation that stopped reporting before
  the window started is invisible, and a repeated old `registered_on` in a fresh event does not bring
  back installations that were erased from history. No panel proves an uninstall, satisfaction, or
  a real-time online state.
- Activity means a report was received. Thirty days without a report defines inactive; a later
  report is reactivation.

Properties arrive as JSON. PostHog infers a type per property from the first values it sees:
in the test project on 2026-09-22, `registered_on` read as a DateTime, `connected_board_count`
as a number, and a JSON `null` read as SQL `NULL`. The queries therefore use `toDateTime(toString())` on
`registered_on`, which works whether the property is typed as a DateTime or as a string, and `toInt()` on the counters, and
they treat `NULL` as the unknown bucket. Re-run the fixture below after any change to the
property definitions in "Data management".

## Latest snapshot per installation

Most panels start from this subquery. It picks the newest event per installation inside the chosen
window, deterministically (`uuid` breaks ties between equal timestamps), and keeps all fields of that
one event as a tuple. A `null` in the newest event stays `null`; ClickHouse `argMax` skips rows only
when the whole argument is `null`, and a tuple never is.

```sql
SELECT
    distinct_id,
    max(timestamp) AS last_seen,
    argMax(tuple(
        properties.app_version,
        properties.os_family,
        properties.os_release,
        properties.linux_distribution,
        properties.runtime_arch,
        properties.connected_board_count,
        properties.board_imports_total,
        properties.board_creations_total,
        properties.registered_on), tuple(timestamp, uuid)) AS latest
FROM events
WHERE event = 'installation_heartbeat'
  AND timestamp >= now() - interval 30 day
GROUP BY distinct_id
```

Fields are read back positionally: `latest.1` is `app_version`, `latest.2` `os_family`, `latest.3`
`os_release`, `latest.4` `linux_distribution`, `latest.5` `runtime_arch`, `latest.6`
`connected_board_count`, `latest.7` `board_imports_total`, `latest.8` `board_creations_total`, and
`latest.9` `registered_on`. The 30-day window is the activity cutoff; widen it only for panels that
say so.

## Panel: active installations over 1, 7, and 30 days

Query file: [`infra/posthog/queries/active-installations.sql`](../infra/posthog/queries/active-installations.sql)

Read "active" as "sent at least one accepted report in the period". A laptop that was closed for a
week is not active for that week and is not uninstalled.

## Panel: latest version distribution

Query file: [`infra/posthog/queries/latest-version-distribution.sql`](../infra/posthog/queries/latest-version-distribution.sql)

Grouping all historical events by version would count the same installation once per version it
ran; the snapshot subquery avoids that. `unknown` is the `null` bucket for source builds and missing
installer metadata. This panel answers "which release is each installation on now", not how fast a
release was adopted; that is the adoption panel below.

## Panel: platform and architecture distributions

Operating system family and release:

Query file: [`infra/posthog/queries/os-family-and-release-distribution.sql`](../infra/posthog/queries/os-family-and-release-distribution.sql)

Runtime architecture:

Query file: [`infra/posthog/queries/runtime-architecture-distribution.sql`](../infra/posthog/queries/runtime-architecture-distribution.sql)

`other` and `unknown` are real buckets; do not fold them into the largest platform.

## Panel: current board distribution

Query file: [`infra/posthog/queries/current-board-distribution.sql`](../infra/posthog/queries/current-board-distribution.sql)

The buckets are a display choice; the reported value is the exact count. `unknown` means the
manifest was unreadable when the newest report was built, which is different from zero boards, and
it is not replaced by an older known count.

## Panel: import and create adoption

Query file: [`infra/posthog/queries/import-and-create-adoption.sql`](../infra/posthog/queries/import-and-create-adoption.sql)

This counts installations, not operations. `neither` means no successful import or create was
observed while reporting was enabled; boards connected before telemetry existed, or while it was
disabled, are not in the counters. A low `import only` share cannot tell demand from failed
attempts, because failed attempts are not reported.

## Panel: registration cohorts

Query file: [`infra/posthog/queries/registration-cohorts.sql`](../infra/posthog/queries/registration-cohorts.sql)

`registered_on` is the date the installation ID was created, during setup or at the first ready
worker, not the original install date, so installations that predate telemetry all register in the
release month that introduced it. A cohort only contains installations that reported inside the
window; earlier churned installations are not reconstructed.

## Panel: release adoption and delay

For one release, this panel answers two questions per installation: could it have upgraded
(cohort), and when was it first observed on the release (adoption). The first observation on the
target release is kept even if the installation later moved to another release, so a later upgrade
or downgrade never erases an adoption. The release date and the exact version string are the
`release_date` and `release_version` inputs of the OpenTofu configuration; the template file renders
them into the saved insight.

Query file: [`infra/posthog/queries/release-adoption-and-delay.sql.tftpl`](../infra/posthog/queries/release-adoption-and-delay.sql.tftpl)

How to read it:

- `observed before release` is the upgrade cohort: installations with at least one report before
  the release date. Their `not observed on ...` rows are non-adopters or installations that stopped
  reporting; both stay in the denominator instead of disappearing.
- `registered after release` installations never upgraded from anything; they are new adoption.
- `first observed after release` installations were registered earlier but have no report before
  the release date inside the window (a long gap or the history boundary), so their upgrade delay
  cannot be measured.
- The delay is the first report on the release, measured in whole days from the release date, not
  the moment of the update. Daily heartbeats give day precision at best, and a report on the day
  before the release date is impossible, so `within 3 days` starts at day 0.
- An installation that ran the release and then a newer one is still counted as adopted; the
  current-version panel above answers what it runs today.

## Panel: inactivity and reactivation

Installations that were active in the retained history but sent nothing in the last 30 days:

Query file: [`infra/posthog/queries/inactive-installations.sql`](../infra/posthog/queries/inactive-installations.sql)

Reactivations in the last 30 days, defined as a report that follows a gap of more than 30 days for
the same installation:

Query file: [`infra/posthog/queries/reactivated-installations.sql`](../infra/posthog/queries/reactivated-installations.sql)

Inactive is a reporting definition. It includes installations that were uninstalled, that turned
reporting off, that are blocked by a firewall, and that simply have no worker running. The panel
cannot say which.

## Creating the insights and the dashboard

The dashboard "Symphony for Trello installations" and its ten insights are created and updated by
OpenTofu from the query files; the panel names, descriptions, and tile layout are in
`infra/posthog/modules/project/main.tf`. Never edit an insight or the dashboard in the PostHog UI:
the next apply restores the definition. To change a panel, edit its query file, run
`scripts/posthog-infra plan` and `apply`, then `scripts/posthog-infra fixture` against the test
project, and update this page.

## Fixture results for the test project

`scripts/posthog-infra fixture` sends these synthetic events to the test project with its own token,
never to the production project, waits for ingestion, and checks every panel. The events and the
expected results are `infra/posthog/fixtures/dashboard-fixture.json`; this table explains them.
`uuid` and `timestamp` change per event, everything else is fixed per installation unless a cell
says otherwise. Dates are relative to the day of the run; the table uses "D" for today. Reports are
sent at 09:15 UTC unless a cell names a later time.

| Installation | Reports on | app_version | os_family | os_release | linux_distribution | runtime_arch | connected_board_count | imports | creations | registered_on |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `11111111-1111-4111-8111-111111111111` | D-2, D-1, D 09:15, D 10:15 | `1.3.0` | `linux` | `24.04` | `ubuntu` | `x64` | `3`, then null on D 10:15 | `1` on D-2, then `2` | `1` | D-40 |
| `22222222-2222-4222-8222-222222222222` | D-45, D | `1.2.0` on D-45, `1.3.0` on D | `macos` | `26` | null | `arm64` | `1` | `0` | `1` | D-45 |
| `33333333-3333-4333-8333-333333333333` | D-35 | `1.2.0` | `windows` | `11` | null | `x64` | null | `0` | `0` | D-35 |
| `44444444-4444-4444-8444-444444444444` | D-5, D | `1.3.0` | `macos` | `26` | null | `arm64` | `2` | `1` | `0` | D-5 |
| `55555555-5555-4555-8555-555555555555` | D-20, D-9, D-8, D-1 | `1.2.0`, then `1.3.0` on D-9, then `1.2.0` again | `windows` | `11` | null | `x64` | `1` | `0` | `0` | D-60 |

Every event also carries `telemetry_schema_version: 1`, `$geoip_disable: true`, and
`$process_person_profile: true`. Installation 1's last event of the day reports an unreadable
manifest (`connected_board_count: null`) so the known-to-null transition is covered; installation 5
adopts the release and then downgrades.

Expected panel results, for a release date of D-10 and target version `1.3.0`:

| Panel | Expected |
| --- | --- |
| Active 1d / 7d / 30d | 4 / 4 / 4 when run before 09:15 UTC on D, otherwise 3 / 4 / 4 (installation 5 last reported on D-1 at 09:15; installation 3 last reported on D-35) |
| Latest version distribution | `1.3.0`: 3, `1.2.0`: 1 (installation 5 downgraded) |
| OS family and release | `macos 26 -`: 2, `linux 24.04 ubuntu`: 1, `windows 11 -`: 1 |
| Runtime architecture | `x64`: 2, `arm64`: 2 |
| Board distribution | `unknown`: 1 (installation 1's newest report), `1`: 2, `2-5`: 1; the older known count 3 must not reappear |
| Import and create adoption | `both`: 1, `create only`: 1, `import only`: 1, `neither`: 1; installation 1 counts once although its counter rose from 1 to 2 |
| Registration cohorts | five installations spread over the months of D-60, D-45, D-40, D-35, and D-5 (on 2026-09-22: July 1, August 3, September 1) |
| Release adoption and delay | `observed before release` / `within 3 days`: 1 (installation 5, first on the release on D-9, later downgraded); `observed before release` / `within 30 days`: 1 (installation 2, first on the release on D); `observed before release` / `not observed on 1.3.0`: 1 (installation 3); `registered after release` / `within 7 days`: 1 (installation 4); `first observed after release` / `within 30 days`: 1 (installation 1, no report before D-10) |
| Inactive installations | 1 (installation 3) |
| Reactivated installations | 1 (installation 2 returned after a 45-day gap; installation 5's 11-day gap does not count) |

These results were produced against the rebuilt test project on 2026-09-22 with exactly these
query files. If a `null` bucket ever shows an empty string instead of `unknown`, the property
definition changed and the `multiIf` conditions need `OR latest.1 = ''` before the panel is
trusted.
