# Usage reporting maintainer runbook

This page is for the maintainer who owns the PostHog project that receives installation telemetry.
Users do not need it; their controls are in [docs/telemetry-privacy.md](telemetry-privacy.md). The
dashboard queries are in [docs/telemetry-dashboard.md](telemetry-dashboard.md).

Every checklist item below is either done with evidence, open, or unknown. Do not tick an item
without the named evidence.

## Provider setup

PostHog EU, in the maintainer's organization. The two projects, their privacy settings, the
disabled GeoIP transformation, the dashboard, and its insights are defined as code under
`infra/posthog/` and applied with OpenTofu; [docs/posthog-infrastructure.md](posthog-infrastructure.md)
is the operating guide and [ADR 0080](adr/0080-posthog-infrastructure-as-code.md) the decision.
Change the definition first, apply, and read back with `scripts/posthog-infra verify`; do not
change these projects in the PostHog UI. The projects are separate from the maintainer's website
project so that no analytics query joins installation IDs with website visitors; that separation
is a configuration the organization's administrator could undo, not a technical impossibility.

Current deployment, rebuilt from the definition on 2026-09-22 (the projects created by hand that
morning, ids 280816 and 280817, were retired the same day and are pending deletion):

| Role | Project | Id | Dashboard | Purpose |
| --- | --- | --- | --- | --- |
| production | Symphony for Trello | 281084 | 967446 | Reports from installed copies; its token is in the release secret |
| test | Symphony for Trello (test) | 281083 | 967445 | Synthetic events for the fixture check and the erasure harness |

What the definition enforces on both projects, read back by the report on that day (outcome
verified; the retention fields are advisory and unknown because the API returns none for these
projects): client IP discarding on; the GeoIP transformation present and disabled; cookieless
hashing off; session replay, performance, exception, web-vitals, heatmap, and survey capture off;
autocapture, console-log capture, and dead-click capture off; no destinations, batch exports, or
public sharing on any dashboard or insight; timezone UTC. The capture toggles govern what the
PostHog SDKs send and what the project expects; they do not make the ingestion endpoint reject an
arbitrary event posted with the public token, which is why the application sends one custom event
and the dashboard queries filter on it.

Shared organization controls the definition does not own, read on 2026-09-22 and reported by
`verify` as informational: `is_ai_training_opted_in` is false (the organization is not opted in
to model training), `is_ai_data_processing_approved` is true (PostHog's AI features may process
data), and `allow_publicly_shared_resources` is true (sharing stays off per dashboard). They apply
to every project in the organization, including `fmartin.ch`, so a change there is an
organization decision, not a Symphony one.

## Release prerequisites

- [x] Activation: the production project's public token (Project settings, "Project API key",
      starts with `phc_`) is stored as the GitHub repository secret `POSTHOG_PROJECT_TOKEN`, set on
      2026-09-22 from project 281084 by `scripts/posthog-infra release-token` after the rebuild
      (an earlier value from the retired project 280816 was replaced). The release workflow passes it to
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
- [x] Product and model development opt-out: PostHog's privacy policy states customer content may be
      used for product and model development unless the customer opts out through the service
      settings, and the DPA states no third party may use it to train AI models. The control is the
      organization field `is_ai_training_opted_in`, read as false on 2026-09-22 (not opted in);
      `verify` reports it. It is an organization-wide setting; keep it false.
- [ ] Collection basis review: the design is opt-out with a notice and a five-minute first-worker
      grace period. That is a product decision, not a legal finding. Whether Swiss, EU, or other
      rules on device access and consent apply to this globally published tool has not been settled.
      Record the maintainer's decision and any advice obtained. Status: open.
- [ ] Dashboard access: only the maintainer's account has access to the organization. Keep it that
      way, or restrict the project with PostHog's access control if more members join.
- [ ] Retention: the API returned no `event_retention_months` or `events_retention_enforced`
      value for either project on 2026-09-22 (the verification report marks them UNKNOWN), so the
      effective retention window is unknown. Read both fields again from
      `GET /api/projects/281084/` before release and record them here with the date. PostHog documents that a retention window hides events from
      queries; it is not evidence of physical deletion of events, profiles, backups, or
      infrastructure logs. The privacy page promises analysis of at most one year of detailed
      events, not a deletion deadline.
- [ ] Person profiles: reports set `$process_person_profile: true` so each installation ID has a
      minimal profile. Confirm in the test project that no profile property other than the ID
      appears, and document the profile lifetime separately from the event window. Status: open.
- [x] Wire check in the test project only: `scripts/posthog-infra fixture` sends the documented
      synthetic installations to the test project through the documented event shape and checks
      every panel; it passed on the rebuilt test project (id 281083) on 2026-09-22. The erasure
      harness (see [docs/telemetry-erasure-verification.md](telemetry-erasure-verification.md))
      sends heartbeats through the application's own serializer and client with that project's
      token; a read-only query of one of its stored events on the same day showed exactly the
      documented properties plus `$geoip_disable`, with `$process_person_profile` consumed at
      ingestion and no `$ip` or `$geoip_*` property added. Repeat both after a schema change.
      Never send synthetic events to the production project.
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
[docs/telemetry-erasure-verification.md](telemetry-erasure-verification.md); steps 1 to 4 are
verified there, steps 5 and 6 were still pending on 2026-09-22 because PostHog batches event
deletion.
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
     "https://eu.posthog.com/api/projects/281084/persons/?distinct_id=<installation uuid>"
   ```

   Expect one result whose `distinct_ids` is exactly `["<installation uuid>"]`. Record its `uuid`
   and the time of this lookup privately before going on: the deletion status in step 5 is keyed by
   that `uuid`, and the request time is what tells this deletion's status row apart from an older
   one on the same profile. An empty result means no profile exists now, which does not mean the
   events are gone. `bulk_delete` queues event deletion only for the persons it finds
   (`persons_found`), so with no profile there is no verified self-service way to delete the
   remaining events; skip to step 6 to measure what remains, then follow "When the profile is
   already gone" below.
3. Delete the profile together with its events:

   ```bash
   curl -sS -X POST -H "Authorization: Bearer $POSTHOG_PERSONAL_API_KEY" \
     -H "Content-Type: application/json" \
     -d '{"distinct_ids": ["<installation uuid>"], "delete_events": true}' \
     "https://eu.posthog.com/api/projects/281084/persons/bulk_delete/"
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
     "https://eu.posthog.com/api/projects/281084/persons/deletion_status/?status=all&person_uuid=<profile uuid>"
   ```

   Expect a row for that `person_uuid` with `status` `completed`, a non-empty `delete_verified_at`
   timestamp, and a `created_at` no earlier than your step 3 request; that row is the evidence,
   and its timestamp goes into the confirmation. A `pending` row means wait. An older `completed`
   row on the same profile belongs to an earlier deletion and proves nothing about this one. No
   row at all is inaccessible or incomplete evidence, not proof that nothing was queued: check
   that the key has `person:read`, that the listing was not paginated past the row, and that step
   3's response reported `persons_queued_for_deletion` 1; if it did, wait and query again, and if
   the row never appears, treat the case as unresolved below.
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

When the profile is already gone but step 6 still returns events, or step 5 never shows a row for
a deletion that step 3 reported as queued, the case is unresolved: no supported call has been
verified to delete events that no longer belong to a findable person, and repeating `bulk_delete`
by installation ID finds nothing to queue. Do not loop on it. Keep the step 2 profile `uuid`, the
step 3 response, and the request time privately, tell the user what remains and that it is being
escalated, and open a PostHog support request with the project id, the installation ID, the
profile `uuid` if known, and the request time, asking for deletion of the remaining events and a
confirmation. Confirm to the user only after step 6 returns zero. The harness in
[docs/telemetry-erasure-verification.md](telemetry-erasure-verification.md) records the same
identifiers for its synthetic subjects so its own cleanup can be handed over the same way.

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

- `POST /api/projects/281084/persons/reset_person_distinct_id/` with body
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

The separate [PostHog-native erasure trial](telemetry-native-erasure-trial.md) found no supported
hosted asymmetric verifier and stopped before deploying an erasure handler. It does not replace
the maintainer procedure above or establish automatic same-ID reuse. The current application's
identity, commands and disabled-state preservation remain unchanged.

- `symphony-trello telemetry status` prints the stored and effective mode, the installation ID, the
  state file path, the next allowed report time, and whether the state file is unreadable.
- A corrupt, foreign, or newer-format `telemetry.json` turns reporting off; it never regenerates an
  ID or re-enables reporting on its own. The status output names the problem.
- `SYMPHONY_TRELLO_TELEMETRY_LOG=1` on a worker prints every request body and the response summary
  into the worker log without changing whether anything is sent.

## Authenticated erasure extension

The [implementation and activation gate](telemetry-erasure-implementation.md) describe the native
service and its durable TEST lifecycle runner. Production remains disabled until that gate passes.
Use `symphony-trello telemetry erase` and `symphony-trello telemetry erase-status` only when the
installed release carries the service configuration. Legacy IDs use the manual procedure above.
Never ask a user to send `telemetry.json`; it contains the ownership secret.

For automatic erasure, inspect the exact bound person UUID and its `persons/deletion_status/`
record. "Accepted" and profile disappearance alone do not prove event deletion. After provider
verification, query exact raw distinct-ID events and preserve an unrelated canary during tests.
Do not reset a retired distinct ID or reuse its completed deletion queue key.

Status flags whose private names start with `symphony-erasure-v1|` belong to deletion operations.
Archive a leftover flag only after checking its bound person's completed deletion record and
profile absence. The client already saves completion before requesting archival. A user whose
local state predates that acknowledgment needs maintainer assistance if the status flag is gone.
Keep the non-deleted flag count below PostHog's 2,000 limit. Preserve every signing-key version
still used by installations and back up provider configuration outside analytics retention.
