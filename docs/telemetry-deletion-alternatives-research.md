# Alternatives to person-wide telemetry deletion

Researched on 2026-09-22 using official PostHog documentation and source revision
`e6fddf2700753989840cdae6c4e8a1cb4dc534dc`. These findings are `SOURCE_ONLY`. No credentials,
customer records or live mutations were used. This note adds evidence to the
[lifecycle investigation](telemetry-native-erasure-lifecycle-research.md), changes no product
contract and does not establish what is deployed in Cloud EU.

A subsequent [live alternatives trial](telemetry-erasure-alternatives.md) checked schema
availability on a disposable event definition. The account rejected mode `reject` because the
required provider feature flag was off. The source analysis below remains useful for a future
provider-supported option, but it is not an available path in this tested account.

## Result

PostHog has internal deletion paths that avoid person merges. The strongest is an exact event
UUID queue, which the earlier investigation did not cover. Its supported producer is Replay
Vision's recording deletion, not a general customer event-deletion API. The source does not
establish a way for our native webhook to enqueue arbitrary heartbeat UUIDs.

The provider's internal HogQL deletion request is still the closest fit for deleting one
installation's historical events with a fixed cutoff. It requires provider staff access.
Changing from persons to groups, cohorts or personless events does not expose that operation
through a customer API.

## Exact event UUID deletion

`AsyncDeletion` now includes `DeletionType.Event = 5`. The Dagster deletion predicate matches
the project and event UUID directly. Person identity merges do not change that predicate.
The event arm deliberately has no time cutoff, because it names one event UUID and must catch
that event even if it is still being ingested when deletion is requested.
[Queue model](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/async_deletion.py#L4),
[worker predicate](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/dags/deletes.py#L169).

PostHog's own deletion-coverage document identifies the producer. The recording API writes
one queue entry per `$recording_observed` event while deleting that recording's observations.
The write happens in the same database statement. This requires an internal Postgres writer;
it is not an arbitrary event UUID parameter on the customer recording-deletion API.
[Provider coverage document](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/docs/internal/clickhouse-deletion-coverage.md#the-sweeps),
[introducing commit](https://github.com/PostHog/posthog/commit/976afed79fbb828665cfeb1a537edc1245a9aca2).

The current [Events API reference](https://posthog.com/docs/api/events) lists only GET actions.
The pinned `EventViewSet` has no destroy or bulk-delete action. A bounded search across 603
Python API/admin files and 334 view/deletion files found no general customer admission action
for `DeletionType.Event`. That search is evidence of the inspected implementation, not proof
that PostHog offers no private or future capability.
[Event view](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/event.py#L178).

If PostHog exposes this queue to customers, a native worker could enumerate raw events for
the authenticated installation up to a fixed cutoff and submit their UUIDs. That would still
need pagination, retry and late-ingestion tests. It would avoid the person-merge race because
the deletion target would be the selected events, not a mutable person membership.

## Other deletion boundaries

| Alternative | What the source establishes | Fit for this use case |
| --- | --- | --- |
| HogQL-filtered deletion | `DataDeletionRequest` accepts an event predicate and time bounds. The documented submission flow is Django admin only. | Best provider-side fit, but no customer API was established. A predicate on raw `distinct_id` avoids a person-membership lookup. |
| One installation per group | The older async worker filters the stored `$group_N` value, independently of person ID. | No individual group event-deletion action appears in the current Groups API or `GroupsViewSet`. The worker's group predicate also lacks a cutoff. Reworking telemetry around groups does not provide a verified erasure path. |
| One installation per cohort | Cohorts select persons. Deleting a cohort removes its definition/membership, not all matching event rows. | Does not turn person deletion into exact-installation deletion. |
| Delete an event definition | The API deletes the reusable event definition. | It is not deletion of selected historical events. Naming every installation as an event type would also distort the event model. |
| Split a person, then delete | Supported split is asynchronous and changes identity mappings separately from deletion. | No atomic split-and-delete or exclusion of later merges. Existing historical event assignment still needs verification. |
| Delete an isolated project | Project deletion is a documented data-deletion boundary. | A project for every installation would replace one analytics project with per-installation provisioning and cross-project queries. Disproportionate for heartbeat analytics. |

Sources for the table:
[HogQL request model](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/data_deletion_request.py),
[admin-only design](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/docs/plans/2026-07-16-auto-approve-small-event-deletions.md),
[group deletion predicate](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/delete_events.py#L157),
[Groups API](https://posthog.com/docs/api/groups),
[group views](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/ee/clickhouse/views/groups.py#L320),
[cohort deletion worker](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/delete_cohorts.py),
[event definition API](https://posthog.com/docs/api/event-definitions),
[split API](https://posthog.com/docs/api/persons#create-persons-split),
[project deletion](https://posthog.com/docs/privacy/data-storage#data-deletion).

## Disabling person processing

Setting `$process_person_profile = false` on our own heartbeat does not control an attacker's
separate capture request. The internal project setting `person_processing_opt_out` does force
personless processing across incoming events, and normalization rejects identity-changing
events in that mode. The inspected customer project serializer does not expose that setting.
[Restriction application](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-preprocessing/apply-person-processing-restrictions.ts),
[normalization](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-processing/normalize-process-person-flag-step.ts),
[project serializer](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/team.py#L506).

Personless processing creates a deterministic synthetic person UUID when no profile exists.
Person deletion resolves an actual profile first. Precreating profiles through a private API
does not repair this gap: `POST persons` explicitly rejects creation and directs callers to
send an `$identify` event. Thus a globally personless project does not yield a supported
customer erasure design with the inspected APIs.
[Personless assignment](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-processing/process-personless-step.ts#L86),
[person creation rejection](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/person.py#L1451).

## Schema enforcement as a merge prohibition

The customer Event Definitions API accepts `enforcement_mode = reject`. Its validator refuses
that setting unless PostHog's `schema-enforcement-reject` feature flag is enabled for the
authenticated user and organization. The ingestion configuration independently controls
whether schema enforcement runs, with a source default of true. A successful configuration
readback alone does not prove enforcement in the hosted ingestion path.
[API validation](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/event_definition.py#L254),
[ingestion configuration](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/config.ts#L415).

Schema validation precedes person processing and merge-fold planning. An invalid event returns
a drop result. Schema loading errors propagate through the step pipeline rather than becoming
successful validation. That is stronger failure handling than the transformation executor.
[Preprocessing order](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/pipelines/analytics/post-team-preprocessing-subpipeline.ts#L108),
[merge planning order](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/pipelines/analytics/joined-ingestion-pipeline.ts#L236),
[step exception handling](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/framework/step-pipeline.ts#L47).

However, this is required-property type validation, not an event-name prohibition. An attacker
can supply an ordinary required property. Defining incompatible types for the same property in
several groups does not make an impossible schema: the loader unions the types and accepts any
one of them. There is no deny-all or fixed-value rule in the inspected validator.
[Schema loader](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/common/utils/event-schema-enforcement-manager.ts#L92),
[type validator](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-preprocessing/validate-event-schema.ts#L75).

One undocumented combination has a source-level argument. Require `alias` to have Boolean
type for `$create_alias` and `$merge_dangerously`, and require `$anon_distinct_id` to have Boolean
type for `$identify`. The validator accepts only the booleans true and false and the exact
strings `true` and `false`. The merge handler converts these values to strings, and its illegal-ID
check rejects both results. Folded identify processing uses the same illegal-ID check.
[Boolean coercion](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-preprocessing/validate-event-schema.ts#L37),
[merge input conversion](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-merge-service.ts#L113),
[illegal IDs](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/common/persons/person-utils.ts#L34),
[fold check](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-merge-fold.ts#L159).

A local component check extracted the provider's coercion and illegal-ID functions unchanged,
removed TypeScript annotations and tried 21 JSON values. Four passed Boolean validation and
all four were unmergeable. This is `LOCAL_COMPONENT_CHECK`, not a live enforcement test or an
end-to-end proof. It does not establish availability of the provider feature flag, configuration
propagation or the absence of downstream event/property rewrites. Those rewrites run after
schema validation and require separate review.

Four further checks exercised the extracted validation step. It dropped a string alias,
accepted a Boolean alias, propagated a controlled schema-lookup exception when enabled, and
bypassed lookup when disabled. The provider's existing test file covers disabled enforcement
and invalid-property drops; no schema-lookup exception case was found in the inspected step
or manager tests. The local checks establish component behavior, not an outage guarantee for
the hosted service.
[Provider step tests](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-preprocessing/validate-event-schema.test.ts#L346).

This combination intentionally gives identity fields the wrong schema type to make another
subsystem reject them. It is a poor security contract without provider support. A documented
project-wide merge prohibition would be simpler and easier to maintain. Keep the combination
as an unproven alternative, not the selected implementation.

A harmless hosted precondition check can use a disposable ordinary event name in TEST.
Create its event definition in allow mode, then patch only that definition to reject mode.
Successful readback establishes configuration acceptance. To test enforcement, create a
run-specific schema property group containing required `alias: Boolean`, attach it through
`event_schemas`, and capture positive and negative ordinary-event cases after configuration
propagation. This leaves the real identity event definitions untouched. Deleting the schema
binding, group and definition afterward removes configuration, not captured event rows.
[Property-group serializer](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/schema_property_group.py#L14),
[event-schema serializer](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/event_schema.py#L9).

## What to prioritize

Provider-enforced rejection of identity events is another direct prevention option. The
existing [merge mitigation note](telemetry-merge-mitigations.md) describes the internal
ingestion restrictions and their configuration-loading failure behavior. A provider guarantee
is needed before describing those restrictions as unconditional. Customer Drop Events
transformations remain useful additional protection, but their fail-open execution behavior
prevents treating them as the sole authorization boundary.

For strict installation isolation, the best provider request is exact raw-distinct-ID deletion
with a fixed cutoff, access to the exact event UUID queue, or enforced rejection of the three
identity event types. Under the accepted blind-guessing threat model, the simpler ownership
protocol with unpredictable IDs and an event-drop transformation remains proportionate. It
does not promise isolation after a victim ID is disclosed during transformation failure.

The previous ownership PoC remains valid. None of these source findings depend on a first
binding event, annual retention or periodic public-key transmission.
