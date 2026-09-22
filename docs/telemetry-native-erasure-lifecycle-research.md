# PostHog-native erasure lifecycle research

Researched on 2026-09-22 against PostHog source revision
`e6fddf2700753989840cdae6c4e8a1cb4dc534dc` and current official documentation. This note
examines deletion scope and continuation separately from authentication. No authenticated API
calls, live mutations, credentials or customer records were used. `SOURCE_ONLY` findings do not
establish the revision deployed in Cloud EU. The proposed hosted cases remain `NOT_RUN`.

Subsequent [ownership PoCs](telemetry-ownership-poc.md) demonstrated ordinary schedule-run
idempotency and a harmless native delay/exit workflow. That separate live evidence does not
establish safe deletion scope, cache-loss recovery or the full lifecycle described here.

The maintainer's threat model permits a per-installation bearer credential and excludes another
backend. That makes the authentication problem smaller. It does not make a person-wide deletion
equivalent to deleting one installation's events. The findings below apply to bearer, HMAC and
public-key authentication alike.

The [merge mitigation follow-up](telemetry-merge-mitigations.md) qualifies the attack prerequisites
against the maintainer's threat model and describes native ingestion with private analytics IDs.
That candidate is not covered by the original direct-capture analysis below and has not been
tested live. The absence of an atomic deletion API is not proof that every native design is unsafe.

## Deletion scope

| Candidate | Evidence | Consequence |
| --- | --- | --- |
| `persons/bulk_delete` with one `distinct_id` | `SOURCE_ONLY`: the endpoint resolves the matching person, then queues that person's UUID. The deletion worker filters `person_id`, not the submitted `distinct_id`. [Endpoint](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/person.py#L905), [queue](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/person/bulk_delete.py#L521), [predicate](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/delete_events.py#L172). | An authenticated installation ID does not restrict this API to that ID's events when identities have merged. |
| Keep the person and delete its events | `SOURCE_ONLY`: `keep_person` skips profile deletion but queues the same person-wide event deletion. The older `delete_events` action does the same. [Implementation](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/person.py#L956). | This avoids removing a profile, but does not narrow event scope. |
| Split one ID, then delete its new person | `DOCUMENTED`: split is asynchronous and returns 201 before completion. It creates deterministic split-off UUIDs. [Split API](https://posthog.com/docs/api/persons#create-persons-split). `SOURCE_ONLY`: split changes person mappings through separate RPC and Kafka operations. [Implementation](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/person/person.py#L226). | No atomic split-and-delete or exclusion of subsequent merges was established. A completed split also does not prove all historical stored `person_id` values now belong to only the intended installation. |
| Filtered deletion through the events API | `SOURCE_ONLY`: `EventViewSet` implements list and retrieve, with no destroy action. [Implementation](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/event.py#L178). | The query filters are read filters, not a supported `DELETE events WHERE distinct_id = ...` API. |
| Internal filtered data-deletion requests | `SOURCE_ONLY`: `DataDeletionRequest` supports HogQL predicates and time bounds. Its entry point is PostHog's Django admin. The design explicitly identifies this as admin-only, and submission requires staff access. [Model](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/data_deletion_request.py), [admin](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/admin/admins/data_deletion_request_admin.py), [provider design](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/docs/plans/2026-07-16-auto-approve-small-event-deletions.md). | This is a useful provider capability to ask about, but not an established customer API callable by our hosted handler. |

Checking a person's current ID list before deleting it does not close this gap. The inspected
delete API accepts no expected ID set, person version, count limit or conditional-match token.
A later identity change is not checked against the caller's earlier observation. Deletion-status
and event-count queries observe state after or separately from admission. They cannot veto a
deletion already queued. These are consequences of the
[delete handler's arguments and call sequence](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/person.py#L905).

## Preventing merges at ingestion

`SOURCE_ONLY`: Hog transformations run before person mutation in the
[analytics pipeline](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/pipelines/analytics/event-subpipeline.ts#L93).
A transformation can reject `$identify`, `$create_alias` and `$merge_dangerously`. However, the
[transformer continues after execution errors](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/hog-transformations/hog-transformer.service.ts#L209).
Therefore a transformation is not a fail-closed authorization boundary. A successful normal-path
capture test would not establish that all public capture callers are isolated during failures.

`SOURCE_ONLY`: the team model has a `person_processing_opt_out` setting. Preprocessing forces
`$process_person_profile = false`, and normalization drops the identity-changing event types.
This is stronger than a client choosing that property for its own requests.
[Restriction step](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-preprocessing/apply-person-processing-restrictions.ts),
[normalization](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-processing/normalize-process-person-flag-step.ts).

Two limitations prevent selecting that setting as the solution. The inspected customer
[team configuration field list](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/team.py#L506)
does not expose it. Also, personless capture assigns a synthetic person UUID when no profile
exists, while person deletion first resolves an actual profile. Existing profiles still influence
personless event assignment. Thus globally disabling person processing does not itself provide a
supported way to delete those events or repair existing merges.
[Personless processing](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-processing/process-personless-step.ts#L86),
[person resolution](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/person/bulk_delete.py#L123).

## Cutoff, retries and later reporting

`SOURCE_ONLY`: event deletion uses `_timestamp <= AsyncDeletion.created_at`. The cutoff belongs
to the provider's accepted deletion row. The caller cannot supply the authenticated operation's
original cutoff. The queue creates rows with a unique constraint on deletion type and person
key, ignoring conflicts. Those are person deletion records, not our subject/request-ID ledger.
[Predicate](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/delete_events.py#L182),
[queue insertion](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/person/bulk_delete.py#L529),
[model constraints](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/async_deletion.py#L14).

This creates two cases to test. A retry that resolves a different person UUID after reuse queues
a different deletion with a later cutoff. A second intended erasure that resolves the same UUID
while its old deletion row remains encounters the existing unique key. Neither case is resolved
by attaching an unused `jti` or `cutoff` field to a bulk-delete request.

`DOCUMENTED`: PostHog processes event deletion asynchronously and warns against reusing a deleted
distinct ID before completion. A reset endpoint exists.
[Storage documentation](https://posthog.com/docs/privacy/data-storage#right-to-be-forgotten).
`SOURCE_ONLY`: `POST persons/reset_person_distinct_id` requires `person:write` and a nonempty
string. Its helper raises the mapping version, and resets an existing person's ClickHouse row.
This is callable automation, not a UI-only tool. Its source explicitly separates the person RPC
from Kafka publication, so a 202 alone does not prove all query state is restored.
[Endpoint](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/person.py#L1604),
[helper](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/person/deletion.py#L25).

## Native admission and continuation

The earlier research found a metrics-only duplicate observer. There is also a different,
stronger mechanism worth testing. `SOURCE_ONLY`: the authenticated workflow management
`POST hog_flows/:id/run` endpoint accepts `Idempotency-Key` for active schedule-triggered
workflows. It reserves the key with `cache.add`, keeps it for 24 hours, returns the saved
invocation ID for retries, and retains an in-progress reservation after a read timeout.
It rejects webhook-triggered workflows on that route.
[Run endpoint](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/products/workflows/backend/api/hog_flow.py#L5846),
[expiry](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/products/workflows/backend/api/hog_flow.py#L2191).

This is a plausible native admission candidate when a verified ingress calls it with a
server-derived key and short-lived requests. It is not yet proof of the full contract. The cache
does not compare request bodies, queueing and caching the response are separate operations,
and no durable cache-loss guarantee is documented in that implementation. A timeout can leave
the caller without an invocation ID. Deriving the key from the canonical authenticated request
would avoid conflicting bodies under one key, but needs a protocol test.

The 24-hour TTL is not automatically disqualifying. A protocol with a shorter enforced request
lifetime can reject a copied request after that window without retaining an annual replay row.
That design still has to handle cache loss within the window and accepted jobs that continue
after it. Annual analytics retention is unrelated to whether this short operation window is
sufficient. This is design analysis, not a provider guarantee.

`SOURCE_ONLY`: workflow execution persists state separately from its current person object,
and its worker continues when the person has disappeared. Delays schedule a later execution.
A schedule-triggered run also avoids using an analytics event as its admission record.
[Executor](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hogflows/hogflow-executor.service.ts#L87),
[worker](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/consumers/cdp-cyclotron-worker-hogflow.consumer.ts#L171).
This supports a continuation proof of concept. Account availability, polling behavior, retained
status, recovery after a lost response and complete deletion/reuse remain unverified.

## Recommendation and next decisive tests

Keep authentication selection separate from the deletion blocker. A bearer secret that derives
the public installation ID needs no old binding event, and accepting that tradeoff does not
require an external backend. Do not add registration or periodic key uploads to solve a lifecycle
problem they do not address.

The most useful remaining provider capability is a customer-accessible deletion operation with
an exact distinct-ID predicate and a fixed cutoff, or a documented atomic check that limits
person deletion to the intended installation. The inspected APIs do not establish either.
Do not grant a public handler deletion authority merely because its credential checks pass.

If a supported isolation mechanism is established, test native schedule-run admission with a
short-lived authenticated operation, a request-derived idempotency key, dropped replies and
concurrent retries. Then test autonomous completion, reset, same-ID reporting and a second erasure.
Keep a separate installation as a canary, force an identity merge, and verify historical event
survival as well as current profile membership. No first binding event should exist in those tests,
and authentication should use unchanged credentials across the one-year boundary.

Until those cases pass, the evidence supports an authentication proof of concept and continued
investigation, not activation of automated erasure. This note changes no user-facing contract,
runtime behavior, dependency or infrastructure resource.
