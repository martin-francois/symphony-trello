# PostHog source and issue follow-up

Checked 2026-09-22. This research adds upstream issue and pull-request evidence to the
[deletion alternatives](telemetry-erasure-alternatives.md). It changes no runtime behavior or
public contract. No upstream messages or live PostHog changes were made.

## What the public history establishes

- PostHog deliberately restricted schema rejection. PR
  [#50251](https://github.com/PostHog/posthog/pull/50251), merged on 2026-03-17, added
  `schema-enforcement-reject`. Its author described ingestion stability concerns when large
  customers enable rejection. The merged API change checks PostHog's own feature flag for
  the requesting user and organization. Creating an identically named flag in our analytics
  project does not satisfy that check. The PR establishes why the gate was introduced; it
  does not promise that support will enable it for us.
- Schema rejection has an implementation, separate from transformation dropping. PR
  [#42450](https://github.com/PostHog/posthog/pull/42450), merged on 2026-02-19, added required
  property presence and type checks. PR
  [#78192](https://github.com/PostHog/posthog/pull/78192), merged on 2026-08-05, added retries
  for transient schema-loading failures after database interruptions crashed workers. These
  changes support investigating schema rejection as a candidate, but neither promises a
  security boundary across every ingestion route.
- Dynamic capture restrictions explicitly permit ingestion to continue when restriction
  loading fails. PR [#43894](https://github.com/PostHog/posthog/pull/43894), merged on
  2026-01-29, describes the Redis restriction manager as fail-open. Asking support to set a
  dynamic restriction therefore does not, by itself, establish blocking during failures.
  Support would need to explain the deployed configuration and any stronger guarantee.

Merged means incorporated into the public source branch. It does not identify the revision
or configuration deployed to our Cloud EU project.

## Corrections to the cited issue evidence

[Issue #54670](https://github.com/PostHog/posthog/issues/54670) remains open. Its reporter
describes unknown project tokens receiving HTTP 200 before downstream ingestion discards
their events. A repository member links a related issue; the inspected comments contain
no confirmation that numeric project IDs are supported credentials. Our separate test of
stored events and merged identities supplies evidence for that behavior. This issue does
not supply it.

[Issue #36469](https://github.com/PostHog/posthog/issues/36469) also remains open. It requests
bulk reset of distinct IDs after person deletion. It does not request deletion by original
event distinct ID or event UUID, and does not establish that such an endpoint exists.

Closed issues need the same care. [Issue #307](https://github.com/PostHog/posthog/issues/307)
has a broad event-deletion title, but a maintainer's
[resolution comment](https://github.com/PostHog/posthog/issues/307#issuecomment-597394235)
describes deletion of an individual's person data. Its closed state is not evidence of an
arbitrary event-deletion API. PRs
[#51565](https://github.com/PostHog/posthog/pull/51565) and
[#51566](https://github.com/PostHog/posthog/pull/51566) proposed further schema enforcement
changes but closed without merging. Their descriptions are not evidence of deployed behavior.

Targeted public issue and PR searches found no supported project-wide merge prohibition or
customer deletion endpoint with merge-independent event scope. This is a search result,
not proof that PostHog has no private or provider-operated capability.

## Source checks that matter for the support question

The inspected source revision is `e6fddf2700753989840cdae6c4e8a1cb4dc534dc`.
The schema manager, validation step, restriction model, and event-definition API files are
byte-identical at `19f273dd3f9125e69885af901de86a171e6dae71`, the public master revision
checked on 2026-09-22. This comparison covers those four files only.

- The [schema validation step](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-preprocessing/validate-event-schema.ts)
  has a separate runtime enablement argument. When disabled, it accepts the input without
  loading a schema. Access to configure rejection does not alone prove runtime enforcement.
- The [schema manager](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/common/utils/event-schema-enforcement-manager.ts)
  loads required property rules. A reject-mode event with no required rules does not become
  an unconditional event-name denial. A rule that merely requires an extra property is
  insufficient against a sender who supplies that property.
- The [restriction manager](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/common/utils/event-ingestion-restrictions/manager.ts#L82)
  catches dynamic loading failures and retains static restrictions. That fallback does not
  establish that a rule stored only in dynamic configuration remains effective.

The useful next support question is whether PostHog will enable and support schema rejection
for blocking these identity operations, with the required ingestion configuration and
failure behavior. Public source answers implementation questions. Support still needs to
confirm availability and guarantees for their hosted service.

## Repeated erasure and identity reuse

The subsequent implementation investigation found a separate lifecycle constraint. The
[person creation service](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-create-service.ts#L39)
uses a [deterministic UUIDv5](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-uuid.ts#L10)
derived from the team and distinct ID. Recreating a profile with the same distinct ID
therefore reuses the same person UUID. Pinning a deletion request to that UUID does not
separate reporting periods.

The [deletion queue](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/person/bulk_delete.py#L521)
ignores conflicts on its unique deletion-type/person-UUID key. Completion marks the queue
row verified rather than removing it; verified rows are excluded from pending work.
[Queue model](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/async_deletion/async_deletion.py#L35),
[pending selection](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/dags/deletes.py#L515),
[completion](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/dags/deletes.py#L1093).
While the completed row remains, a second request for that UUID does not create a fresh
deletion. This is source evidence, not a hosted repetition test or a promise of indefinite
queue-row retention.

The maintainer approved a stable installation identity with a fresh PostHog distinct ID after
completed erasure. [ADR 0082](adr/0082-authenticated-erasure-with-reporting-periods.md) records
that contract change and the PostHog-native implementation. The earlier same-ID gate no longer
applies. Production remains inactive pending hosted completion and repeat-deletion verification.
