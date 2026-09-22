# Erasure and same-ID reuse verification

This note records the live experiment that backs the erasure procedure in
[docs/telemetry-maintainer-runbook.md](telemetry-maintainer-runbook.md). It says what was executed
against PostHog, what was observed, and what is still pending. The runbook holds the procedure;
this page holds the evidence and how to reproduce or resume it.

The later [native erasure trial](telemetry-native-erasure-trial.md) is a separate capability
investigation. It stopped before installing a privileged handler and did not advance this
maintainer-driven experiment. Neither report proves unattended native completion or same-ID reuse.

Everything here runs against the dedicated test project "Symphony for Trello (test)"; the
production project is passed to the harness as forbidden and receives nothing. The projects were
rebuilt from the OpenTofu definition on 2026-09-22 (see
[docs/posthog-infrastructure.md](posthog-infrastructure.md)), so the first run below targets the
retired test project 280817 and is historical: its pending rows can never complete there. The
current run targets the rebuilt test project 281083 with production 281084 forbidden.

## What is tested

The intended workflow is: the user runs `symphony-trello telemetry disable`, hands the installation
ID to the maintainer privately, the maintainer deletes the profile and events and confirms, and the
user does nothing else locally. A later `symphony-trello telemetry enable` should resume with the
same ID. The open question is whether PostHog lets events for a deleted `distinct_id` form a new
profile again without further maintainer work, and where the documented
`reset_person_distinct_id` call belongs in that sequence.

Three synthetic installations take part:

| Subject | Role |
| --- | --- |
| A | Deletion, then same-ID reuse without any reset |
| B | Deletion, then `reset_person_distinct_id` before the first new event, then reuse |
| C | Control: one event that must stay visible while A and B are erased |

After the erasure is verified and A and B are re-enabled, the harness treats two questions
separately. First, ingestion: the new event UUIDs must become queryable, and until they do the run
is pending. Second, reuse: after a bounded propagation window it records, for each subject on its
own, whether the person API shows a profile and whether analytics resolve the new events to a
profile in the persons table. A missing mapping is an observation, not a reason to wait longer.
Only a subject whose mapping is missing then gets the after-event `reset_person_distinct_id`
call, once (the request time is checkpointed first, so a resumed run observes instead of resetting
again). If the reset does not resolve the mapping within its window, the subject needs one more
heartbeat, which the application's daily gate allows only on a later UTC day; the run parks in the
`REPAIR_AWAITING_HEARTBEAT` phase and a later invocation sends it. Cleanup starts only after every
subject has a recorded repair outcome.

Deletion evidence is strict. A deletion counts as complete only when the status row for the
profile UUID has status `completed`, a parseable `delete_verified_at`, and a `created_at` no
earlier than the recorded request time (minus a five-minute skew allowance); the timestamp in the
evidence comes from that row. An older completed row on a reused profile, a row for another person,
a missing or blank timestamp, an empty listing, a malformed answer, or a query that only reports
its status is never treated as completion.

The harness drives the real application components: the state store, `telemetry disable` and
`telemetry enable` through `TelemetryService`, the worker's `HeartbeatReporter` with an injected
clock, the single serializer, and the real capture client. Only the platform, the board count, and
the installed version are fixtures. It never touches a real state directory.

## API contract used

Verified on 2026-09-22 from PostHog's documentation, the live EU OpenAPI schema at
`https://eu.posthog.com/api/schema/`, and the public source at commit
`d49f665f26d3a7e7b6dff8db90a160659ccf64cd` (`posthog/api/person.py` and
`posthog/models/person/deletion.py`). Where the generated documentation and the source disagree,
the source is followed.

| Call | Contract |
| --- | --- |
| `GET /api/projects/{id}/` | Project `id`, `name`, and public `api_token`. The harness binds the numeric ID from its environment, requires the live name to match, and takes the capture token from this response only. |
| `GET /api/projects/{id}/persons/?distinct_id=` | Scope `person:read`. Results carry PostHog's numeric `id`, the profile `uuid`, and `distinct_ids`. The installation ID is a `distinct_id`; the profile `uuid` is a different identifier that the deletion status is keyed by. |
| `POST /api/projects/{id}/persons/bulk_delete/` | Scope `person:write`. Body `{"distinct_ids": [...], "delete_events": true}`. Returns 202 with `persons_found`, `persons_queued_for_deletion`, `persons_deleted` (0 when queued), `events_queued_for_deletion`, and `deletion_errors`. Only events captured before the request are deleted. |
| `GET /api/projects/{id}/persons/deletion_status/?status=all&person_uuid=` | Scope `person:read`. Rows with `status` `pending` or `completed` and `delete_verified_at`; it tracks event deletions only. A missing row is not completion. |
| `POST /api/projects/{id}/persons/reset_person_distinct_id/` | Scope `person:write`. The source reads `request.data["distinct_id"]` and answers 202 with no body; the generated reference shows a wrong request schema and a 200. In `deletion.py`, when no current person exists for the ID (it has not been reused yet) the function logs that and skips the ClickHouse writes, so a 202 before the first new event is not evidence of anything. |
| `POST /api/projects/{id}/query/` | Scope `query:read`. HogQL with `refresh: force_blocking`; the harness rejects any answer with `is_cached: true`. |

The single-person `DELETE /persons/{id}/?delete_events=true` also exists and runs synchronously,
but it is excluded from the OpenAPI schema and reports nothing about what it queued, so the
procedure uses `bulk_delete`, whose response can be checked field by field.

PostHog's data-storage page states that event deletion runs asynchronously during non-peak times,
on PostHog Cloud at weekends; the source of the single-person endpoint says the deletion task is
batched and runs at 05:00 UTC on Sundays. The persons page states that a deleted `distinct_id`
should not be reused while the deletion is being processed and points at the "Reset deleted
person" tool for reuse.

The source's Sunday time is not a guaranteed completion deadline. Current public documentation
promises asynchronous off-peak processing, on weekends in Cloud. Resume based on verified deletion
status and event absence, not merely because Sunday has passed. Ownership verification does not
depend on that batch, and an accepted automated job must not require the client to remain online.

## Running the harness

The harness is `TelemetryErasureExperimentIT`, an opt-in failsafe test that `verify` and CI skip.
It runs only with every variable below set; there are no defaults for project IDs. Its safety
guards are covered by `ErasureExperimentSafetyTest` against an in-memory PostHog: forbidden
project, project name mismatch, a profile mapped to a foreign ID, a missing scope, an empty
deletion status, a deletion error, a cached query answer, a resumed run, and secret-free output.

```bash
SYMPHONY_TRELLO_TELEMETRY_LIVE_EXPERIMENT=1 \
SYMPHONY_TRELLO_TELEMETRY_LIVE_EXPERIMENT_DIR="$HOME/.local/state/symphony-trello/erasure-experiment" \
SYMPHONY_TRELLO_POSTHOG_PERSONAL_API_KEY_FILE="$HOME/posthog-personal-api-key" \
SYMPHONY_TRELLO_POSTHOG_TEST_PROJECT_ID=281083 \
SYMPHONY_TRELLO_POSTHOG_TEST_PROJECT_NAME='Symphony for Trello (test)' \
SYMPHONY_TRELLO_POSTHOG_FORBIDDEN_PROJECT_IDS=281084 \
./mvnw test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=TelemetryErasureExperimentIT -Djacoco.skip=true -Dfailsafe.failIfNoSpecifiedTests=false
```

The key file holds a personal API key with `person:read`, `person:write`, `query:read`, and
project read access; it is read inside the JVM and never printed. The experiment directory keeps
`checkpoint.json` (phase, synthetic IDs, event UUIDs, PostHog person identifiers, deletion
responses), `evidence.jsonl` (one sanitized line per step), `summary.md` (the result table), and
the three subjects' state directories. Rerunning the same command with the same directory resumes
from the checkpoint; it never reseeds, repeats a deletion, or resets before the deletion is
verified. Per invocation the harness sends at most 150 management requests and waits at most 15
minutes; when PostHog has not finished it stops with `PENDING` and the phase to resume from.

## Historical run erasure-e40e8ff7 on 2026-09-22 (retired project 280817)

Worktree at commit `c9b5fe07` plus the harness itself, which lands in the commit that adds this
page. All times UTC. The project was retired the same day and is pending deletion, so rows 3 to 8
of this run stay as they were; the current run in the next section replaces it. Its state directory
was archived as `erasure-experiment-project-280817-retired` beside the current one.

| Row | Status | Evidence |
| --- | --- | --- |
| 1. Old event/profile baseline established | PASS | Five heartbeats accepted with HTTP 200 between 09:20:28 and 09:20:36 (A and B two each, dated 20 and 21 September; C one). At 09:21:10 every event UUID was queryable by raw `distinct_id`, the person API returned exactly one profile per ID mapped only to that ID, and the `person_id` on the events equalled the profile UUID, which the `persons` table also listed. |
| 2. Local disable preserves UUID and prevents transmission | PASS | `telemetry disable` on A and B: mode `DISABLED`, same ID, same `registered_on` 2026-09-19, counters 4 and 2 unchanged, no pending report. Two simulated worker restarts per subject, 6 minutes and 1 day past the grace deadline, returned `DISABLED` and issued no request. |
| 3. Old event deletion completed and independently verified | PENDING | `bulk_delete` at 09:21:10 answered 202 with `persons_found` 2, `persons_queued_for_deletion` 2, `persons_deleted` 0, `events_queued_for_deletion` true, no `deletion_errors`. By 09:24:17 the person API returned no profile for A or B and the `persons` table no longer listed them, but all four old events were still queryable and their `person_id` still pointed at the deleted profile UUIDs; `deletion_status` showed one `pending` row per profile with `delete_verified_at` null. C stayed visible. |
| 4. A: reuse without reset | NOT RUN | Waits for row 3. |
| 5. B: reset before first new event | NOT RUN | Waits for row 3. |
| 6. Conditional reset after a new event | NOT RUN | Waits for rows 4 and 5. |
| 7. Old events absent and new records usable | NOT RUN | Waits for row 3. |
| 8. Reused ID erasable, fixtures cleaned up | NOT RUN | Waits for row 7. The three subjects still exist in the test project until then. |

Synthetic identifiers of this run, so the pending rows can be checked by hand:

| Subject | Installation ID (`distinct_id`) | Profile UUID before erasure | Old event UUIDs |
| --- | --- | --- | --- |
| A | `6e9a624b-9339-4d07-8c46-8cd05dfa04e1` | `51410cf7-1a40-508b-ba2b-68727dca2a9b` | `81f19deb-912a-439e-8c9d-577c6e97cf62`, `cf1a7c67-74d3-4a83-a0cd-aba43c341971` |
| B | `fe015367-2197-4e02-b176-3b59b4be3e7a` | `b9079019-d999-5662-9dc8-ac23a2f009ed` | `cdbf2892-6885-40a5-8d21-93682af00293`, `bd064949-1425-4d45-b35a-222b49e34dc5` |
| C | `789cb67e-646b-4d98-ba5d-7cf3369aa79d` | `52d93905-50b7-5765-b90a-f56f7a8faa15` | `7a2ae1a3-76dd-4ccc-bc65-8c82e6dc210d` |

Wire check, done on the same day with a read-only query of C's stored event: its properties are
exactly the documented fields plus `$geoip_disable`; `$process_person_profile` is consumed at
ingestion, and no `$ip` or `$geoip_*` property was added.

What this run established beyond the table: profile deletion is quick (under three minutes here)
and precedes event deletion by days, so during that window an installation ID has no profile while
its events still exist and still carry the old profile UUID. A missing profile is therefore not
evidence that the events are gone, and the `deletion_status` row is the only signal that ties the
event deletion to the profile that was deleted.

## Current run erasure-b000c2f3 on 2026-09-22 (rebuilt project 281083)

Worktree at the commit that adds the PostHog infrastructure (the harness unchanged since the
historical run). All times UTC. The sequence and outcome match the historical run: rows 1 and 2
PASS, row 3 PENDING, rows 4 to 8 NOT RUN.

| Row | Status | Evidence |
| --- | --- | --- |
| 1. Old event/profile baseline established | PASS | Five heartbeats accepted from 10:11:00; every event UUID queryable by raw `distinct_id`, one profile per ID mapped only to that ID, events' `person_id` equal to the profile UUID present in the persons table. |
| 2. Local disable preserves UUID and prevents transmission | PASS | Same ID, registration date, and counters after disable; simulated restarts past the grace period returned `DISABLED` and issued no request. |
| 3. Old event deletion completed and independently verified | PENDING | `bulk_delete` at 10:12:06: 202, `persons_found` 2, `persons_queued_for_deletion` 2, `events_queued_for_deletion` true, no errors. By 10:15:13 the profiles were gone from the person API while the old events remained queryable; `deletion_status` pending. |
| 4. to 8. | NOT RUN | Wait for row 3; resume with the command above after PostHog's next deletion batch (Sunday 05:00 UTC). |

| Subject | Installation ID (`distinct_id`) | Profile UUID before erasure | Old event UUIDs |
| --- | --- | --- | --- |
| A | `837ca992-d8a3-45da-8265-4be9d59ac223` | `c3eea7d9-7e7f-5c41-8e54-1928997d8a8f` | `880462ec-b6f6-45a0-85e6-5f02f6f02a2f`, `93e2b50f-75a9-4d3b-9fe8-0dc50cd16b0e` |
| B | `5def6e20-dd13-46cb-af16-792e5f0d950a` | `b19a5ec2-d056-5379-8131-dbb8cfc032d1` | `29c28799-07c7-43ce-9a85-0e9ebf55d035`, `9e30e168-226d-400e-a6ef-703547fcd5b8` |
| C | `5903a3d7-6998-4383-85fe-eb0a9e726276` | `None` | `3066215a-e605-4dba-8275-67b0f629aba5` |

## Resuming

Run the exact command above again with the same experiment directory. The harness reloads
`checkpoint.json`, polls the deletion status and the event queries, and continues through the reuse
phases, the conditional repair (including a run parked for a next-day heartbeat), and the cleanup
when PostHog reports the deletion verified. Each
resumed invocation appends to `evidence.jsonl` and rewrites `summary.md`; copy the new rows into
the table above and update the runbook's "Re-enabling after an erasure" section with what rows 4
to 6 show. The cleanup at the end of the experiment is itself a second erasure of the reused IDs
and finishes only when PostHog verifies that deletion too, so a complete run spans at least two of
PostHog's deletion batches.

Nothing runs between invocations: no scheduled job, no background process. A person has to run the
command again.
