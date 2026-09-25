# Telemetry erasure research log

## Scope and status

This log keeps the evidence behind
[ADR 0081](adr/0081-gate-automated-erasure-on-hosted-verification.md) and
[ADR 0082](adr/0082-authenticated-erasure-with-reporting-periods.md). It is not a specification:
`SPEC.md` holds the contract, the ADRs hold the decisions and rejected options, and the
[implementation and activation gate](telemetry-erasure-implementation.md) describes what ships,
including the status feature flags. All research dates from 2026-09-22. Unless a link says
otherwise, source citations are pinned to PostHog revision
`e6fddf2700753989840cdae6c4e8a1cb4dc534dc`, which is not necessarily the revision PostHog
Cloud EU runs. Live trials ran only in the managed EU TEST project, with synthetic fixtures that
the run created. Production was never invoked, and no trial requested an event-deletion job,
reset, project deletion or token rotation. The maintainer's private run ledgers hold the exact
project IDs and resource IDs.

Evidence labels:

- `DOCUMENTED`: stated in official PostHog documentation.
- `SOURCE_ONLY`: read in PostHog source at the pinned revision.
- `LOCAL_COMPONENT_CHECK`: provider code extracted and run locally, not a hosted test.
- `TEST`: observed live in the TEST project.
- `UNKNOWN`: needs an account read-back or a live test. `NOT_RUN`: a required case not yet run.

## Threat model and merge prerequisites

The principal threat is an anonymous caller sending random requests to erase other
installations. Knowing an installation ID is a separate, stronger attack condition.

`SOURCE_ONLY`: the merge handler takes two concrete IDs. `$create_alias` and
`$merge_dangerously` use the event's `distinct_id` and `properties.alias`; `$identify` uses
`distinct_id` and `properties.$anon_distinct_id`. The handler passes these exact strings to the
person store and accepts no wildcard, property filter or merge-all request.
[Merge dispatch](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-merge-service.ts#L100).

`DOCUMENTED`: capture and feature-flag requests use the project token; reading people or querying
analytics needs private API authentication. No public endpoint that lists installation IDs was
found in the API documentation. That is a bounded search. Shared dashboards, exports and our own
responses must still not disclose IDs.
[API authentication](https://posthog.com/docs/api#authentication).

A UUIDv4 has 122 random bits. With `N` victims and `q` independent guesses the union bound is
about `q * N / 2^122`, so a million victims and a trillion guesses give about `1.9e-19`. This
assumes uniform random generation; it is not a measured service guarantee.
[RFC 9562 section 5.4](https://www.rfc-editor.org/rfc/rfc9562.html#section-5.4).

## PostHog deletion API behavior

### Deletion scope

No inspected customer API deletes one installation's events when its identity has merged with
another. The candidates:

| Candidate | Evidence | Consequence |
| --- | --- | --- |
| `persons/bulk_delete` with one `distinct_id` | `SOURCE_ONLY`: resolves the matching person, then queues that person's UUID. The worker filters `person_id`, not the submitted `distinct_id`. [Endpoint](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/person.py#L905), [queue](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/person/bulk_delete.py#L521), [predicate](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/delete_events.py#L172). | After a merge, deletion covers every merged installation. |
| `keep_person`, or the older `delete_events` action | `SOURCE_ONLY`: skips profile deletion but queues the same person-wide event deletion. [Implementation](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/person.py#L956). | Keeps the profile; does not narrow event scope. |
| Split one ID, then delete | `DOCUMENTED`: split is asynchronous, returns 201 before completion and creates deterministic split-off UUIDs. [Split API](https://posthog.com/docs/api/persons#create-persons-split). `SOURCE_ONLY`: mappings change through separate RPC and Kafka operations. [Implementation](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/person/person.py#L226). | No atomic split-and-delete, no exclusion of later merges, and no proof that historical stored `person_id` values move. |
| Events API | `DOCUMENTED`: the [Events API reference](https://posthog.com/docs/api/events) lists only GET actions. `SOURCE_ONLY`: `EventViewSet` has list and retrieve only. [Event view](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/event.py#L178). | Its filters are read filters, not `DELETE events WHERE distinct_id = ...`. |
| HogQL `DataDeletionRequest` | `SOURCE_ONLY`: accepts an event predicate and time bounds. Submission is Django admin only and needs staff access; the provider design says so. [Model](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/data_deletion_request.py), [admin](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/admin/admins/data_deletion_request_admin.py), [design](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/docs/plans/2026-07-16-auto-approve-small-event-deletions.md). | Closest fit: a raw `distinct_id` predicate with a fixed cutoff avoids person membership. Provider-only. |
| One installation per group | `SOURCE_ONLY`: the older async worker filters the stored `$group_N` value, independent of person ID, with no cutoff. The [Groups API](https://posthog.com/docs/api/groups) and [group views](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/ee/clickhouse/views/groups.py#L320) have no per-group event deletion. [Group predicate](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/delete_events.py#L157). | No verified erasure path. |
| One installation per cohort | `SOURCE_ONLY`: cohort deletion removes the definition and membership, not matching events. [Worker](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/delete_cohorts.py). | Not an event deletion. |
| Delete an event definition | `DOCUMENTED`: deletes the reusable definition. [Event definitions](https://posthog.com/docs/api/event-definitions). | Leaves historical events. |
| One project per installation | `DOCUMENTED`: [project deletion](https://posthog.com/docs/privacy/data-storage#data-deletion) is a data-deletion boundary. | Per-installation provisioning and cross-project queries; disproportionate for heartbeats. |

Checking the person's ID list before deleting does not close the gap. The delete handler accepts
no expected ID set, person version, count limit or match token, and status or event-count queries
run after admission and cannot veto a queued deletion.
[Delete handler](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/person.py#L905).

### Exact event UUID queue

`SOURCE_ONLY`: `AsyncDeletion` includes `DeletionType.Event = 5`. The Dagster predicate matches the
project and event UUID, so merges do not change it. It has no time cutoff by design, to catch an
event still being ingested.
[Queue model](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/async_deletion.py#L4),
[worker predicate](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/dags/deletes.py#L169).
Its only producer is Replay Vision's recording deletion, which writes one queue row per
`$recording_observed` event from an internal Postgres writer in the same statement.
[Coverage document](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/docs/internal/clickhouse-deletion-coverage.md#the-sweeps),
[introducing commit](https://github.com/PostHog/posthog/commit/976afed79fbb828665cfeb1a537edc1245a9aca2).
A bounded search of 603 Python API and admin files and 334 view and deletion files found no
customer admission action for it. If PostHog exposed it, a native worker could list the
installation's raw events up to a fixed cutoff and submit their UUIDs; that still needs
pagination, retry and late-ingestion tests.

### Cutoff, queue key and identity reuse

`SOURCE_ONLY`: event deletion uses `_timestamp <= AsyncDeletion.created_at`, the provider's
acceptance time. The caller cannot supply its own cutoff. Queue rows have a unique key on deletion
type and person UUID, and insertion ignores conflicts. Completion marks the row verified instead of
removing it, and verified rows are excluded from pending work.
[Predicate](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/delete_events.py#L182),
[insertion](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/person/bulk_delete.py#L529),
[constraints](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/async_deletion.py#L14),
[verified field](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/async_deletion.py#L35),
[pending selection](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/dags/deletes.py#L515),
[completion](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/dags/deletes.py#L1093).

`SOURCE_ONLY`: person UUIDs are a
[deterministic UUIDv5](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-uuid.ts#L10)
of team and distinct ID, set by the
[person creation service](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-create-service.ts#L39).
Recreating a profile under the same distinct ID gives the same UUID, so while the verified row
remains, a second deletion for that UUID creates no new job. Pinning a request to the UUID does
not separate reporting periods. This is why ADR 0082 gives each reporting period a fresh
distinct ID. No hosted repetition test was run, and indefinite row retention is not promised.

`DOCUMENTED`: deletion is asynchronous, and PostHog warns against reusing a deleted distinct ID
before it completes.
[Right to be forgotten](https://posthog.com/docs/privacy/data-storage#right-to-be-forgotten).
`SOURCE_ONLY`: `POST persons/reset_person_distinct_id` needs `person:write` and a nonempty string,
raises the mapping version and resets an existing ClickHouse row. The person RPC and the Kafka
publication are separate, so a 202 does not prove query state is restored.
[Endpoint](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/person.py#L1604),
[helper](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/person/deletion.py#L25).

## Identity merges and the merge filter

### Built-in protections

`SOURCE_ONLY`: ordinary identity operations refuse to merge an identified source person, but
`$merge_dangerously` sets `allowIdentifiedSources` to true, and a provider test checks this.
[Merge request](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-merge-service.ts#L338),
[test](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-merge-service.test.ts#L183).
`DOCUMENTED`: the forced-merge example uses the ordinary project token and names both IDs; no
personal API key is needed.
[Forced merge](https://posthog.com/docs/product-analytics/identify#how-to-merge-users).
Marking profiles identified reduces accidental merges only.

### Transformation filter

`SOURCE_ONLY`: Hog transformations run before person mutation and can drop the three identity
event types, but the transformer continues after an execution error.
[Pipeline order](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/pipelines/analytics/event-subpipeline.ts#L93),
[fail-open handling](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/hog-transformations/hog-transformer.service.ts#L209).

`TEST`, verdict `FILTER_WORKS_BUT_FAILS_OPEN`, 15:52:08 to 15:53:03 UTC: a temporary transformation
matched four run-owned IDs, dropped `$identify`, `$create_alias` and `$merge_dangerously` for them,
and marked ordinary probe events to prove it ran. One pair stayed two single-ID persons. The other
pair hit a deliberate error before the drop; its merge event was stored and joined both IDs. The
fault hook was part of the test, not a claim that an attacker can trigger that error. All six
assertions passed. Across the first and resumed trials, 14 capture requests produced 11 stored
events, including the permitted merge.

Two trial details matter when rebuilding the filter. Three creation attempts failed to compile
because Hog cannot assign to the global event object directly; assigning it to a local variable
first compiles. The first saved trial saw only two of four baseline events within its observation
window and stopped, so the resumed trial waits for the marker on every subject. Both functions were
disabled and soft-deleted, and GET returned 404. The six synthetic IDs and their events remain.

### Provider ingestion restrictions

`SOURCE_ONLY`: a provider-operated restriction supports an event-name filter and a
`drop_event_from_ingestion` action, so one rule could cover the three identity events for every
distinct ID. Customers get only a GET action with `project:read`; editing happens in Django admin.
The Node manager catches dynamic loading errors and keeps only static restrictions, and
[PostHog PR #43894](https://github.com/PostHog/posthog/pull/43894), merged 2026-01-29, calls the
Redis manager fail-open. A support-set dynamic rule is therefore not fail-closed on its own.
[Model](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/event_ingestion_restriction_config.py),
[customer read](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/team.py#L2987),
[admin editor](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/admin/admins/event_ingestion_restriction_config.py),
[loading fallback](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/common/utils/event-ingestion-restrictions/manager.ts#L82).

### Disabling person processing

Setting `$process_person_profile = false` on our heartbeat does not bind an attacker's separate
request. `SOURCE_ONLY`: the internal team setting `person_processing_opt_out` forces personless
processing, and normalization drops identity-changing events. The customer team serializer does
not expose it. Personless events get a deterministic synthetic person UUID, while person deletion
resolves a real profile first, and `POST persons` refuses creation in favor of `$identify`. A
globally personless project therefore leaves no customer deletion target.
[Restriction step](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-preprocessing/apply-person-processing-restrictions.ts),
[normalization](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-processing/normalize-process-person-flag-step.ts),
[serializer fields](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/team.py#L506),
[personless assignment](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-processing/process-personless-step.ts#L86),
[person resolution](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/person/bulk_delete.py#L123),
[creation refusal](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/person.py#L1451).

### Schema enforcement

`SOURCE_ONLY`: the Event Definitions API accepts `enforcement_mode = reject` only when PostHog's
own `schema-enforcement-reject` flag is on for the user and organization; an identically named
flag in our project does not count. Ingestion has a separate enablement switch, true by default in
source, and the validation step skips schema loading when disabled. Validation runs before person
processing and merge-fold planning, drops invalid events, and lets schema-loading errors propagate
instead of passing, which is stricter than the transformation executor.
[API validation](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/event_definition.py#L254),
[ingestion config](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/config.ts#L415),
[preprocessing order](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/pipelines/analytics/post-team-preprocessing-subpipeline.ts#L108),
[merge planning order](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/pipelines/analytics/joined-ingestion-pipeline.ts#L236),
[step exceptions](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/framework/step-pipeline.ts#L47).

It validates required property types; it cannot ban an event name. An attacker supplies the
required property. Conflicting types across groups are unioned, not made impossible, and there is
no deny-all or fixed-value rule.
[Schema loader](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/common/utils/event-schema-enforcement-manager.ts#L92),
[type validator](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-preprocessing/validate-event-schema.ts#L75).

One undocumented combination would block merges: require Boolean `alias` on `$create_alias` and
`$merge_dangerously`, and Boolean `$anon_distinct_id` on `$identify`. The validator accepts only
`true`, `false` and the strings `"true"` and `"false"`; the merge handler stringifies them, and
the illegal-ID check, also used by folded identify processing, rejects both.
[Boolean coercion](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-preprocessing/validate-event-schema.ts#L37),
[merge input](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-merge-service.ts#L113),
[illegal IDs](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/common/persons/person-utils.ts#L34),
[fold check](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-merge-fold.ts#L159).
`LOCAL_COMPONENT_CHECK`: the provider's coercion and illegal-ID functions, extracted unchanged,
took 21 JSON values; four passed Boolean validation and all four were unmergeable. The extracted
step dropped a string alias, accepted a Boolean one, propagated a schema-lookup exception when
enabled and skipped lookup when disabled. The provider's own
[step tests](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-preprocessing/validate-event-schema.test.ts#L346)
have no lookup-exception case. None of this shows flag availability, config propagation, or the
absence of event rewrites that run after validation.

`TEST`, 15:54 UTC: a disposable ordinary event definition was created in `allow` mode. Patching it
to `reject` returned HTTP 400, readback stayed `allow`, and no events were captured:

```text
Setting schema enforcement mode to "reject" requires the schema-enforcement-reject feature flag
```

Deletion was acknowledged, the detail GET then returned HTTP 500, and a list by its unique name
confirmed it was gone.

Upstream history: [PR #42450](https://github.com/PostHog/posthog/pull/42450), merged 2026-02-19,
added required-property checks. [PR #50251](https://github.com/PostHog/posthog/pull/50251), merged
2026-03-17, added the flag gate, citing ingestion stability when large customers enable rejection.
[PR #78192](https://github.com/PostHog/posthog/pull/78192), merged 2026-08-05, added retries after
database interruptions crashed workers.
[PR #51565](https://github.com/PostHog/posthog/pull/51565) and
[PR #51566](https://github.com/PostHog/posthog/pull/51566) closed unmerged. The schema manager,
validation step, restriction model and event-definition API files were byte-identical at master
`19f273dd3f9125e69885af901de86a171e6dae71` on 2026-09-22; only those four files were compared.

## Ownership proof options

### HMAC and bearer compared

`TEST`, 15:01:11 to 15:01:16 UTC: both designs passed through public `webhooks.eu.posthog.com`
source functions.

| Property | Server-derived HMAC | Hash-derived bearer |
| --- | --- | --- |
| First binding event, binding database, periodic key upload | None | None |
| Secret sent on each erasure | No | Yes |
| Extra operational secret | Server master key | None |
| Issuance | Server assigns a fresh ID and key once | Client generates its secret locally |
| Lost issuance response | Start fresh before any reporting uses the ID | No issuance request |
| One-year event expiry | Irrelevant to verification | Irrelevant to verification |
| Replay rejected by authentication alone | No | No |

A leaked HMAC request exposes a short-lived proof for one operation. A leaked bearer request
exposes lasting authority. A master-key compromise affects every derived credential, so the key
and its old versions must be kept independently of analytics; it stays in PostHog secret
configuration and never ships to installations.

### What the live proof showed

Two runs each created an issuer, an HMAC verifier and a bearer verifier, with no `fetch`, capture
or deletion credential. The final run recorded 49 assertions, including management and cleanup
checks and two local simulated-age cases; it is not 49 end-to-end tests.

- Issuance returns a fresh ID and a key that matches Node's HMAC. Supplying an existing ID to
  issuance is rejected, and an unanswered issuance can be abandoned for a fresh ID.
- Both verifiers derive the target with no enrollment record or analytics history.
- Rejected: public IDs alone, duplicate JSON members, extra targets, wrong audience, expired or
  future requests, excess lifetime, malformed data, oversized bodies and GET. Altering the HMAC
  target or operation invalidates the request.
- A random well-formed bearer secret derives its own unrelated ID.
- A restored serialized credential works without re-enrollment, including after 400 days on a
  simulated local clock. Hosted storage was not aged.
- Repeated and concurrent valid proofs are accepted: the verifiers are stateless and admission is
  a separate layer.

Management responses omitted the master key. Immediate function-log reads were empty, which proves
nothing about retention or redaction; provider traces and execution state still need assessment.
The management test-invocation API rejects `source_webhook` with `Invalid function type`, and the
same code compiled as an unsaved destination returned a null result, so only saved public sources
count as evidence. Direct function DELETE returns 403 for a personal API key; cleanup disables the
function, sets `deleted=true` and checks GET 404. Soft-deleted configuration is not claimed purged.

### Prototype protocol

The HMAC issuer prefixes a fresh UUIDv4 with `h1-k1-`. Master and installation secrets are 32 bytes
written as 64 lowercase hex characters, and HMAC keys are the UTF-8 bytes of that hex text on both
runtimes.

```text
installationKey = HMAC-SHA256(masterHex,
  "symphony-trello/owner/v1|" + audience + "|" + installationId)

unsigned = "h1|erase|" + audience + "|" + installationId
  + "|" + operationId + "|" + issuedAt + "|" + expiresAt

payload = unsigned + "|" + HMAC-SHA256(installationKeyHex, unsigned)
```

The bearer ID is `b1-` plus the lowercase SHA-256 of `symphony-trello/bearer/v1|<audience>|<secretHex>`.
Its payload uses version `b1`, carries the secret as subject and has no MAC. Transport is the exact
compact JSON `{"payload":"<payload>"}` over HTTPS POST; unknown or duplicate members, escaping and
extra whitespace fail a raw-body equality check. Operation IDs are UUIDv4, timestamps ten-digit
Unix seconds, lifetime at most 900 seconds with 60 seconds of clock tolerance, bodies at most 1,200
characters. The native verifier compares keyed hashes of both MACs rather than the MACs, which is
not a claim about constant-time behavior. The client must save an issued credential before its
first heartbeat.

### ES256 and JOSE rejected

Verdict `BLOCKED_NATIVE_CAPABILITY` for the original protocol, which banned sending any reusable
secret. `DOCUMENTED`: [Hog crypto](https://posthog.com/docs/hog#cryptographic-functions) lists
MD5, SHA-256 and SHA-256 HMAC, with no asymmetric verifier, JOSE or JWK validation, Web Crypto or
package imports. `SOURCE_ONLY`: the
[TypeScript registry](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/common/hogvm/typescript/src/stl/stl.ts#L1109),
[crypto module](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/common/hogvm/typescript/src/stl/crypto.ts)
and [Python registry](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/common/hogvm/python/stl/__init__.py)
add SHA-1 wrappers but no asymmetric function. The
[VM dispatch](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/common/hogvm/typescript/src/execute.ts#L813),
[CDP wrapper](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/utils/hog-exec.ts),
[executor callbacks](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hog-executor.service.ts#L210),
[async executor](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hog-executor-async.service.ts#L229)
and [async registrations](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/async-functions/index.ts)
pass Node crypto only as an internal dependency. A local `jose` install does not extend this.
[Standard Webhooks](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/utils/standard-webhooks.ts)
signs outbound requests with HMAC-SHA256, and the
[incoming webhook template](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/templates/_sources/webhook/incoming_webhook.template.ts)
compares a fixed authorization header only.

`TEST`, 13:56:27 to 13:56:29 UTC: `hog_functions/new/invocations` ran 16 unsaved destination
configurations with `enabled: false` and `mock_async_functions: true`. That is hosted execution
through the administration API, not public ingress.

| Probe | Result |
| --- | --- |
| `sha256Hex('abc')` | Expected digest |
| `sha256HmacChainHex`, `md5Hex`, `jsonParse`, `generateUUIDv4` | Present |
| `base64Decode('YWJj')` | `abc` |
| `ecdsaVerify`, `verifySignature`, `rsaVerify`, `jwtVerify`, `verifyJwt`, `jwtDecode`, `crypto` | `Global variable not found` |
| `hmacSha256`, `base64UrlDecode`, `hexDecode` | `Global variable not found` |

Missing names alone prove little; the registry inspection carries the verdict. A supported
verifier or provider confirmation would reopen it. Hashes, JWT decoding, a shared password,
handwritten elliptic-curve code or an external verifier do not qualify.

The removed local ES256 reference, `scripts/erasure-protocol-reference.ts`, used `jose` for P-256
and RFC 7638 thumbprints. Its installation ID was SHA-256 over UTF-8
`symphony-trello/installation-id/v1`, a NUL byte and the 32-byte thumbprint, truncated to 16 bytes
with UUIDv8 version and RFC variant bits. It accepted only the ADR 0081 member order, canonical
unpadded base64url, exact public EC parameters, a UUIDv4 operation ID, nonnegative integer times,
a 900-second maximum lifetime, 60-second skew and a 4096-byte token. Its tests held fixed vectors
and negative cases for key substitution, tampering, algorithm confusion, duplicate or extra fields,
malformed points and signatures, remote key directives, private fields, target injection, encodings
and time boundaries. It was stateless and accepted a valid replay.

### Retention and enrollment cases

These were `NOT_RUN` when recorded; SPEC now carries them as gates. Authentication must work with
every enrollment event removed and every event older than a year unavailable, including after a
year offline. Enrollment designs must survive a dropped request, a dropped response after commit
and a client restart at each persistence boundary, and public-ID-only recovery must never return or
replace a credential. After erasure and same-ID reuse, replaying the first request must not erase
new data. For the bearer design: no binding record needed; public IDs, malformed or random secrets
and supplied target or filter overrides never reach another installation; injected events, person
properties and merges never let it erase an unrelated installation; a lost response and a later
replay never erase a later period; and native request logs are checked for who can read a
submitted credential.

## PostHog-native execution

### Incoming webhook sources

- `DOCUMENTED`: [incoming webhooks](https://posthog.com/docs/cdp/sources/incoming-webhooks) give a
  unique URL, parsed request data and Hog code, with a 500 KB payload limit; `fetch` queues
  background work and returns 201. `postHogCapture` takes an event name, distinct ID and properties
  and no token. [Capture section](https://posthog.com/docs/cdp/sources/incoming-webhooks#capturing-a-posthog-event).
- `SOURCE_ONLY`: the [source consumer](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/consumers/cdp-source-webhooks.consumer.ts)
  accepts a synchronous `httpResponse` with status and body, supplies `request.stringBody`, queues
  unfinished execution and accepts caller-supplied `$variables`, which need validation. A degraded
  function is queued and returns an empty HTTP 200
  ([L362](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/consumers/cdp-source-webhooks.consumer.ts#L362));
  a disabled one returns 429
  ([L461](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/consumers/cdp-source-webhooks.consumer.ts#L461)).
  A client must not read HTTP 200 as a committed heartbeat. The UUID only selects the function
  ([L436](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/consumers/cdp-source-webhooks.consumer.ts#L436)).
- `SOURCE_ONLY`: captures carry the function's own `team_id`, and the capture service adds
  `team.api_token`. Only entries in `capturedPostHogEvents` are emitted, so an exception before the
  capture call emits nothing, and there is no raw-request fallback. An event captured before a
  later error is kept, so all validation must come first.
  [Hog executor](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hog-executor.service.ts#L236),
  [capture service](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/captured-events/captured-events.service.ts#L61),
  [source execution](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/consumers/cdp-source-webhooks.consumer.ts#L345),
  [error handling](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hog-executor.service.ts#L363).
- `DOCUMENTED`: [destinations](https://posthog.com/docs/cdp/destinations/customizing-destinations)
  send requests out, and [endpoints](https://posthog.com/docs/endpoints) expose saved query results;
  neither is an incoming handler or state store.

### Limits, secrets and provisioning

- `DOCUMENTED`: destination [secret inputs](https://posthog.com/docs/cdp/destinations/customizing-destinations#guidelines-for-modifying-a-destination)
  are encrypted at rest and omitted from later UI responses. A function errors after more than five
  `fetch` calls. `SOURCE_ONLY`: the
  [Hog executor](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hog-executor.service.ts)
  allows five async steps, limits logs and scrubs known secret values from `print` output;
  [function storage](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/products/cdp/backend/models/hog_functions/hog_function.py#L139)
  keeps encrypted inputs apart. Request bodies, exceptions, responses and VM snapshots are not
  shown to be redacted, and documented payload logging exposes bodies when enabled.
- `DOCUMENTED`: [pricing](https://posthog.com/pricing) lists 10,000 data-pipeline events plus
  1 million rows, and 10,000 workflow messages per channel, each month.
  [Workflow quota checks](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hogflows/hogflow-quota-limiting.ts)
  include destination dispatches. The TEST account's remaining allowance was `UNKNOWN`.
- `SOURCE_ONLY`, checked against the installed provider schema: OpenTofu provider 1.0.21 supports
  `type = "source_webhook"`, code, filters, input schema and `sensitive_inputs_json`, and has no
  workflow resource, so workflows need the API supplement. A sensitive input still needs private
  state; plan redaction is not state encryption.
  [Hog function schema](https://github.com/PostHog/terraform-provider-posthog/blob/736a4fbd631b90c378bed2a083be010bf4725764/docs/resources/hog_function.md),
  [resource registry](https://github.com/PostHog/terraform-provider-posthog/blob/736a4fbd631b90c378bed2a083be010bf4725764/internal/provider/provider.go).

### Workflows and continuation

`DOCUMENTED`: [webhook triggers](https://posthog.com/docs/workflows/workflow-builder#webhook-triggers)
start a workflow directly, and the [workflow API](https://posthog.com/docs/workflows/surfaces/api)
manages graphs and invocations with `hog_flow:read` and `hog_flow:write`. No keyed conditional
insert, compare-and-swap, or transaction joining acceptance with scheduling is documented.
`SOURCE_ONLY`: the
[worker](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/consumers/cdp-cyclotron-worker-hogflow.consumer.ts#L171)
warns and continues when an event-triggered run has no person, and the
[executor](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hogflows/hogflow-executor.service.ts#L87)
keeps the person out of persisted state, so deleting the person does not abort a run. Delays
schedule later execution. The
[duplicate observer](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hogflows/hogflow-duplicate-observer.service.ts)
uses a 15-minute Redis key, tolerates store failures, and its
[caller](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hogflows/hogflow-executor.service.ts#L240)
discards the result: it records metrics and is not a replay ledger.

### Schedule runs and idempotency

`SOURCE_ONLY`: `POST hog_flows/:id/run` accepts `Idempotency-Key` for active schedule-triggered
workflows and rejects webhook-triggered ones. It reserves the key with `cache.add` for 24 hours,
returns the saved invocation ID on retry, and keeps an in-progress reservation after a read
timeout. It does not compare bodies, queueing and caching the response are separate steps, and no
cache-loss guarantee exists; a timeout can leave the caller without an invocation ID. A request
lifetime shorter than 24 hours bounds replay without a yearly ledger.
[Run endpoint](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/products/workflows/backend/api/hog_flow.py#L5846),
[expiry](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/products/workflows/backend/api/hog_flow.py#L2191).

`TEST`, completed 15:09:01 UTC: a workflow with only a schedule trigger, a five-second delay and an
exit read back `schedules: []` and `billable_action_types: []`. Three concurrent runs with one key
and `{"variables":{}}` gave one queued invocation and two 409 responses; a retry after completion
returned the same invocation ID. The result reported `status: succeeded`, an empty `person_id`, and
appeared after about twelve seconds. The results API returns 404 until then, which stopped a first
attempt early. Cancellation alone does not prove parked jobs stopped. Not proven: cache-loss
recovery, body-conflict rejection, atomic public admission and the 24-hour expiry.

`UNKNOWN` at the time: a supported store with authenticated subject and `jti` uniqueness, conflict
detection, leases, replay retention independent of analytics, rebuild survival and status lookup.
Do not infer one from internal Redis or Cyclotron storage.

## Private ingestion and capture-token secrecy

A native webhook can authenticate heartbeats and capture them without exposing the project
token. That does not stop merges, because capture resolves a numeric team ID as a token.

`SOURCE_ONLY`: in `TeamManager`, `getTeamByToken` shares one loader with ID lookup. Positive
decimal strings of up to ten digits in the signed 32-bit range query `t.id`; others query
`t.api_token`; results are cached under both keys. Ingestion requires a token, drops unresolved
ones and overwrites any body `team_id`. The capture edge checks only token shape, so HTTP 200 does
not prove ingestion.
[Shared loader](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/common/utils/team-manager.ts#L114),
[team resolution](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-preprocessing/resolve-team.ts#L22),
[edge validation](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/rust/capture/src/token.rs#L43).

`TEST`, 15:44:04 to 15:44:06 UTC, observed through 15:46:53 UTC, status `FAILED`: a source reused
the HMAC verifier with a `heartbeat-probe` action and a two-subject allowlist, building one harmless
event type with fixed properties. It accepted three valid requests; rejected wrong signatures and
actions, extra merge fields and raw identity events; returned 500 for an injected pre-capture error
and stored nothing from it. Raw capture without `api_key` returned 401. Raw capture with the decimal
TEST project ID as `api_key` returned 200, stored a synthetic `$merge_dangerously` and joined both
fixture IDs into one person; a second harmless numeric-ID capture was also stored. The real token
had the `phc_` form and did not equal the project number.

`SOURCE_ONLY` on token discovery: `/array/:token/config` needs the token in the URL,
`TeamPublicSerializer` omits `api_token`, and tokens carry 255 random bits and are not derived from
project IDs. Disclosure through SDK config, bundles, published outputs, logs or members stays
possible. `PATCH .../reset_token/` is admin-only, keeps no old token and invalidates the cache
entry, with no measured revocation deadline; legacy `secret_api_token` rotation is a separate
feature. Rotation leaves numeric lookup working and does not undo past merges. A new clean project
avoids inherited merges but moves dashboards, infrastructure and history, and the old project's
data still needs erasure.
[Remote config](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/rust/hypercache-server/src/api/remote_config.rs#L87),
[public serializer](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/shared.py#L273),
[shared context](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/sharing.py#L310),
[token generation](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/utils.py#L146),
[reset action](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/team.py#L2669),
[token replacement](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/team/team.py#L958).

A variant hides the analytics ID instead: the webhook derives it as
`HMAC(serverSecret, "analytics-id|project|credentialId")`, builds only allowlisted events and
properties, never forwards `distinct_id`, `alias`, `$anon_distinct_id` or identity event names, and
never returns the derived ID. It was not built. It raises the bar only until the ID leaks and
leaves existing client-visible IDs and merges unchanged.

[PostHog issue #54670](https://github.com/PostHog/posthog/issues/54670), open, reports HTTP 200 for
unknown tokens before downstream ingestion drops events. Its comments do not confirm numeric IDs as
credentials; the TEST observation above is the evidence for that.

## Open provider questions and the support request

No message had been sent to PostHog when this was recorded. The questions:

1. Can the project reject `$identify`, `$create_alias` and `$merge_dangerously` before every person
   mutation, including numeric-ID capture, and stay closed when rule loading or execution fails?
   Candidates are the ingestion restriction and schema rejection with its runtime switch.
2. Can a native worker submit a raw distinct-ID deletion with a fixed cutoff, or exact event UUIDs,
   through a supported customer API?

Public history does not answer these.
[PostHog issue #36469](https://github.com/PostHog/posthog/issues/36469), open, asks for bulk
distinct-ID reset after person deletion, not deletion by distinct ID or UUID.
[PostHog issue #307](https://github.com/PostHog/posthog/issues/307) has a broad title, but its
[resolution comment](https://github.com/PostHog/posthog/issues/307#issuecomment-597394235) covers
person data only. Targeted issue and PR searches found no merge prohibition or merge-independent
deletion endpoint; that is a search result, not proof of absence. Merged source does not identify
what Cloud EU runs.

## How to reproduce

Source findings: open the pinned links above. Live runs must check the selected OpenTofu backend and
the managed test role against the live project, environment, organization and capture token, use
fresh run-owned subjects and recorded cleanup. Never run them against production or unrelated
identities.

### Hog runtime probe

```bash
SYMPHONY_TRELLO_POSTHOG_LIVE_PROBE=1 scripts/posthog-infra hog-probe
```

It resolves a stored `@current` against the pinned project, runs each of the 16 probes once, limits
requests to 60 seconds and 4 MiB, and exits nonzero on an unexpected response. Exit zero means the
observations were understood. It writes `hog-probe-<UTC timestamp>.json` with mode 0600 in the private
state directory, refuses an existing path, and keeps tokens out. An interrupted ledger stays
`started` and is safe to rerun. The older maintainer-driven experiment is tracked separately in
[erasure verification](telemetry-erasure-verification.md).

### Ownership proof of concept

The prototype and runners were removed after ADR 0082. Restore them from commit `15760c9f`:

```bash
git checkout 15760c9f -- scripts/erasure-ownership-poc.ts scripts/erasure-ownership-poc.test.ts \
  scripts/erasure-ownership-live-poc.mjs scripts/erasure-ownership-live-poc.test.mjs
node --test scripts/erasure-ownership-poc.test.ts
SYMPHONY_TRELLO_OWNERSHIP_POC=1 node scripts/erasure-ownership-live-poc.mjs
```

The live runner writes a credential-free ledger to its default private directory, or to
`SYMPHONY_TRELLO_OWNERSHIP_POC_DIR`. For the ES256 reference, restore
`scripts/erasure-protocol-reference.ts` and `scripts/erasure-protocol-reference.test.ts` from the
same commit together with `jose` 6.2.12 as a development dependency. The lifecycle runner that was
`scripts/erasure-lifecycle-live.mjs` at that commit now lives at `scripts/erasure-lifecycle-live.ts`;
the [implementation page](telemetry-erasure-implementation.md) gives its commands.

### Workflow idempotency

Create a uniquely named draft flow with `exit_condition: exit_only_at_end` and actions `trigger`
(`{"type":"schedule"}`), `delay` (`{"delay_duration":"5s"}`) and `exit` (`{}`), joined in that
order by `continue` edges, with no variables. Check the empty schedules and billable actions,
activate with PATCH, send the concurrent run group, then read
`hog_flows/<id>/invocation_results/<invocation_id>/` until a terminal status or a bounded deadline,
treating 404 as not yet visible. Clean up by setting `draft`, calling `invocations/cancel/` with
`{"all":true}`, deleting the flow and checking GET 404.

### Filter, token and schema trials

These scripts were one-off artifacts kept outside the repository. To repeat the schema check, create
a disposable ordinary event definition in `allow` mode and patch only it to `reject`. If that is
ever accepted, add a run-specific
[schema property group](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/schema_property_group.py#L14)
with required `alias: Boolean`, bind it through
[`event_schemas`](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/event_schema.py#L9),
and capture positive and negative ordinary events after propagation. Deleting the binding, group
and definition removes configuration, not captured events.
