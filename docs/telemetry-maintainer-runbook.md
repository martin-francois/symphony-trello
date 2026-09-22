# Usage reporting maintainer runbook

This page is for the maintainer who owns the PostHog project that receives installation telemetry.
Users do not need it; their controls are in [docs/telemetry-privacy.md](telemetry-privacy.md). The
dashboard queries are in [docs/telemetry-dashboard.md](telemetry-dashboard.md).

Every checklist item below is either done with evidence, open, or unknown. Do not tick an item
without the named evidence.

## Provider setup as of 2026-09-22

PostHog EU, organization "François's Organization". Two projects were created through the API on
2026-09-22, separate from the `fmartin.ch` website project so installation IDs can never be joined
with website visitors:

| Project | Id | Purpose |
| --- | --- | --- |
| Symphony for Trello | 280816 | Production reports from installed copies |
| Symphony for Trello (test) | 280817 | Synthetic events for query validation and wire checks |

Settings applied to both projects on that day, verified from the API response:

- `anonymize_ips = true` ("Discard client IP data"): the client IP is not stored with events.
- The default "GeoIP" transformation was disabled. Discarding the IP and disabling GeoIP are separate
  settings; PostHog applies transformations before discarding the IP, so both are needed.
- Autocapture, session replay, surveys, heatmaps, console log capture, exception capture, web vitals,
  and performance capture are off. The application never sends those events anyway; the settings
  keep the project from expecting them.
- Timezone `UTC`.

Do not change these settings from the application side. Re-check them after any PostHog project
setting change and after PostHog product changes.

## Release prerequisites

- [x] Activation: the production project's public token (Project settings, "Project API key",
      starts with `phc_`) is stored as the GitHub repository secret `POSTHOG_PROJECT_TOKEN`, set on
      2026-09-22 from project 280816. The release workflow passes it to
      `scripts/package-release-assets.sh` as `SYMPHONY_TRELLO_POSTHOG_PROJECT_TOKEN`, which writes it
      into the `posthog.project-token` key of `src/main/resources/symphony-trello-telemetry.properties`
      only while packaging the release
      archives and verifies the packaged application carries it. The repository keeps the value
      `<unset>`, so source checkouts, tests, CI, and development builds never send. The token is
      public by design: it can only add events. Never put a personal API key or the project's secret
      key in the secret. Removing the secret ships releases that never send.
- [ ] The release that ships the token is the first release that reports. Its release notes must
      state that usage reporting is on by default and how to turn it off; the README and the first
      setup run after the update show the notice.
- [ ] Data processing agreement: PostHog's DPA is self-serve from the organization's legal page
      (`app.posthog.com/legal` in PostHog's documentation; use the `eu.posthog.com` equivalent for
      this organization). Status: not executed. Generate, sign, and file it before release.
- [ ] Subprocessors and transfers: read `https://posthog.com/subprocessors` for the EU data center
      and note the date read. The DPA references the EU-US, UK, and Swiss-US Data Privacy Framework
      and Standard Contractual Clauses for transfers; confirm that covers the maintainer's Swiss
      obligations. Status: unknown, needs the maintainer's review or advice.
- [ ] Product and model development opt-out: PostHog's privacy policy states customer content may be
      used for product and model development unless the customer opts out through the service
      settings, and the DPA states no third party may use it to train AI models. Locate the current
      opt-out setting in the organization or project settings, apply it, and record where it is.
      Status: unknown; the setting was not located through the API on 2026-09-22.
- [ ] Collection basis review: the design is opt-out with a notice and a five-minute first-worker
      grace period. That is a product decision, not a legal finding. Whether Swiss, EU, or other
      rules on device access and consent apply to this globally published tool has not been settled.
      Record the maintainer's decision and any advice obtained. Status: open.
- [ ] Dashboard access: only the maintainer's account has access to the organization. Keep it that
      way, or restrict the project with PostHog's access control if more members join.
- [ ] Retention: the API returned `event_retention_months = null` and
      `events_retention_enforced = null` for both projects on 2026-09-22, so the effective retention
      window is unknown. Read both fields again from `GET /api/projects/280816/` before release and
      record them here with the date. PostHog documents that a retention window hides events from
      queries; it is not evidence of physical deletion of events, profiles, backups, or
      infrastructure logs. The privacy page promises analysis of at most one year of detailed
      events, not a deletion deadline.
- [ ] Person profiles: reports set `$process_person_profile: true` so each installation ID has a
      minimal profile. Confirm in the test project that no profile property other than the ID
      appears, and document the profile lifetime separately from the event window. Status: open.
- [x] Wire check in the test project only: done on 2026-09-22 by the erasure harness, which sent
      real heartbeats through the application's own serializer and client with the test project's
      token (see [docs/telemetry-erasure-verification.md](telemetry-erasure-verification.md)). The
      stored control event `7a2ae1a3-76dd-4ccc-bc65-8c82e6dc210d` carries exactly the documented
      properties plus `$geoip_disable`; `$process_person_profile` is consumed at ingestion, and no
      `$ip` or `$geoip_*` property was added. Repeat after a schema change with the same harness or
      a development build started with `-Dsymphony.trello.telemetry.project-token=<test project
      token>` (the endpoint can be overridden the same way with
      `-Dsymphony.trello.telemetry.endpoint=` for a local fake). Never send synthetic events to
      project 280816.
- [ ] Quota: the organization is on the pay-as-you-go tier with the free monthly allowance of one
      million events. One report per installation per day is far below that, but check "Billing"
      for dropped or quota-limited events before reading any decline in activity as real. A
      quota-limited report is answered with HTTP 200 and is not retried.

## Interpreting the dashboard

- Read the rules at the top of [docs/telemetry-dashboard.md](telemetry-dashboard.md) before drawing
  conclusions. Counts are reporting installations, not people or downloads.
- A drop in active installations can mean a PostHog ingestion problem, a quota limit, a broken
  release, or a firewall change as well as lost users. Check ingestion and quota first.
- Installations that predate telemetry register when they first report, so the first weeks after
  release show a registration spike that is not new adoption.

## Erasure requests

The user-facing side is three steps: run `symphony-trello telemetry disable`, send the installation
ID through the private "Report a vulnerability" advisory form (private vulnerability reporting is
enabled on the repository; verified through the GitHub API on 2026-09-22), and wait for the
confirmation. GitHub has no private direct messages, so do not point users anywhere else. The user
has no local step after that: no reset command, no ID rotation, no file edit, no reinstallation.
Never tell a user to delete `telemetry.json` or the state directory; an absent file initializes as
enabled and the next worker start registers a new ID after the grace period.

Four identifiers take part, and they are not interchangeable:

- the installation ID, a UUID from `telemetry.json`, is what the application sends as
  `distinct_id`;
- each report carries its own event UUID (`uuid` in the body);
- PostHog gives the profile a numeric `id` and a profile `uuid` of its own, both returned by the
  person API;
- the deletion status is keyed by that profile `uuid`, not by the installation ID.

The steps below were executed against the test project with the harness described in
[docs/telemetry-erasure-verification.md](telemetry-erasure-verification.md); steps 1 to 5 are
verified there, step 6 was still pending on 2026-09-22 because PostHog batches event deletion.
Replace `<installation uuid>` with the user's ID and keep `$POSTHOG_PERSONAL_API_KEY` in the
maintainer's shell only; the key needs `person:read`, `person:write`, and `query:read`.

1. Confirm the request contains a UUID and nothing else that identifies a person. Do not ask for
   more identifying data. Ask the user to confirm that reporting is disabled on that installation.
   Disabling discards any report that was pending locally, but a report that was already on the
   wire when the user disabled can still arrive, and PostHog deletes only events captured before
   the deletion request. If the user disabled the same day, wait a day before step 3, and treat any
   event received after the request as a separate deletion.
2. Find the profile and check that it is mapped to this installation ID only:

   ```bash
   curl -sS -H "Authorization: Bearer $POSTHOG_PERSONAL_API_KEY" \
     "https://eu.posthog.com/api/projects/280816/persons/?distinct_id=<installation uuid>"
   ```

   Expect one result whose `distinct_ids` is exactly `["<installation uuid>"]`. Note its `uuid`;
   the deletion status in step 5 is keyed by it. An empty result means no profile exists now,
   which does not mean the events are gone: continue with step 3, which deletes by installation
   ID, and verify by step 6.
3. Delete the profile together with its events:

   ```bash
   curl -sS -X POST -H "Authorization: Bearer $POSTHOG_PERSONAL_API_KEY" \
     -H "Content-Type: application/json" \
     -d '{"distinct_ids": ["<installation uuid>"], "delete_events": true}' \
     "https://eu.posthog.com/api/projects/280816/persons/bulk_delete/"
   ```

   Read the body, not only the 202: `persons_found` 1, `persons_queued_for_deletion` 1,
   `events_queued_for_deletion` true, `deletion_errors` empty. `persons_deleted` is 0 because the
   deletion is queued. An entry in `deletion_errors` means the person was not deleted; repeat the
   request for it. A repeated request for an already queued deletion is safe; PostHog does not
   queue it twice.
4. Confirm the profile is gone by repeating step 2 and expecting no result. This happens within
   minutes and proves nothing about the events.
5. Wait for the event deletion. PostHog batches it; its documentation says at weekends, its source
   at 05:00 UTC on Sundays. Check with the profile `uuid` from step 2:

   ```bash
   curl -sS -H "Authorization: Bearer $POSTHOG_PERSONAL_API_KEY" \
     "https://eu.posthog.com/api/projects/280816/persons/deletion_status/?status=all&person_uuid=<profile uuid>"
   ```

   Expect a row with `status` `completed` and a `delete_verified_at` timestamp. A `pending` row
   means wait; no row at all means the deletion was not queued for that profile, so go back to
   step 3.
6. Verify the events independently of the status row. Run this in the project (SQL insight, or the
   query API with `"refresh": "force_blocking"` so a cached answer cannot mislead) over the full
   retained window and expect zero:

   ```sql
   SELECT count() AS remaining_events
   FROM events
   WHERE event = 'installation_heartbeat'
     AND distinct_id = '<installation uuid>'
     AND timestamp >= now() - interval 400 day
   ```

   Do not filter by person: after step 4 the events still exist for days, still carry the deleted
   profile's UUID, and a person-based filter would hide them. Between steps 4 and 6 the events are
   queryable by `distinct_id` and nothing else about them has changed.
7. Confirm to the user only when step 5 shows `completed` and step 6 returns zero. Template:

   > The PostHog person profile for installation ID `<installation uuid>` and every
   > `installation_heartbeat` event stored under it were deleted. PostHog verified the event
   > deletion on `<delete_verified_at>`, and a query over the full retained window returns no
   > event for that ID as of `<date>`. This covers the analytics data the maintainer controls;
   > PostHog's own backups and infrastructure logs expire on PostHog's schedules, which the
   > maintainer cannot shorten. Reporting on your installation stays off until you run
   > `symphony-trello telemetry enable`.

Keep the personal API key on the maintainer's machine only. It is never part of the application,
the repository, or the CI configuration.

## Re-enabling after an erasure

The application never rotates or deletes an installation ID. If the user later runs
`symphony-trello telemetry enable`, the next reports carry the same ID, the same registration date,
and the counters frozen at the time of disabling; nothing from the disabled period is backfilled
and no report discarded by the disable is replayed. Those new snapshots do not restore the erased
events, but they do repeat the registration date and the cumulative counters, so the user should
know that enabling again shares those summary facts again.

Whether PostHog then builds a new profile for the reused ID without maintainer work is what rows 4
to 6 of the verification note test, and they were pending on 2026-09-22. What is known from the
API contract until then:

- `POST /api/projects/280816/persons/reset_person_distinct_id/` with body
  `{"distinct_id": "<installation uuid>"}` answers 202 whenever the ID exists. Its implementation
  does nothing when the ID has no current profile, which is the case right after an erasure and
  before the first new event. A 202 at that point is not a successful preparation, so do not run
  it during erasure handling and do not report it as done.
- PostHog's own guidance is that reusing a deleted ID while the deletion is still being processed
  gives unexpected results. Wait for step 5 before advising anything about re-enabling.
- If a user reports that they re-enabled and the dashboard shows the ID's events without a
  profile, the call above, made after those new events exist, is the documented repair. Do that
  only for an ID whose deletion status is `completed`, and record here the date and what it
  changed. Never send a heartbeat yourself or create a profile to make the reset work; the user's
  own reports are the only activity that may exist for their ID.
- Do not build any of this into the client. If the tested sequence turns out to need a maintainer
  step after re-enabling, that is a limitation to document in the privacy page, not a reason for
  a reset command or a new identity.

## Local diagnostics

- `symphony-trello telemetry status` prints the stored and effective mode, the installation ID, the
  state file path, the next allowed report time, and whether the state file is unreadable.
- A corrupt, foreign, or newer-format `telemetry.json` turns reporting off; it never regenerates an
  ID or re-enables reporting on its own. The status output names the problem.
- `SYMPHONY_TRELLO_TELEMETRY_LOG=1` on a worker prints every request body and the response summary
  into the worker log without changing whether anything is sent.
