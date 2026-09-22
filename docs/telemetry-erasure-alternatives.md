# Choosing a PostHog erasure design

Investigation completed on 2026-09-22. The maintainer wants a proportionate solution for
installation analytics, with no external backend, retained binding event or periodic key upload.
The principal threat is anonymous callers submitting random requests to erase other installations.
Knowing an installation ID is a separate, stronger attack condition.

## Recommendation

Keep the tested server-derived HMAC ownership component. For the narrower guessing threat,
direct capture with unpredictable installation IDs and a native filter dropping identity events
is the simplest mitigation. Do not add a second hidden identifier or an authenticated heartbeat
proxy solely to claim that merges have been prevented.

That recommendation does not establish safe person-wide deletion after an installation ID leaks.
The filter fails open, which this investigation reproduced with synthetic installations. A
preflight check should refuse an unexpected multi-ID person, but cannot prevent a subsequent
merge. These are additional protections, not an atomic installation-only deletion boundary.

The existing activation gate remains. Before selecting automatic person deletion under a broader
contract, obtain a provider-supported merge prohibition or exact-installation deletion operation.
Native admission, completion, replay handling and same-ID reuse also need their existing tests.
The current maintainer erasure flow remains unchanged. No production resource was activated.

## Comparison

| Alternative | Evidence | Decision |
| --- | --- | --- |
| HMAC ownership and unpredictable IDs | Native authorization PoC passed. No binding event or annual registration is needed. | Keep. Prevents guessing credentials; does not constrain PostHog person membership. |
| Drop the three identity event types | Live filter blocked `$identify`, `$create_alias` and `$merge_dangerously`. An injected execution error passed a merge through. | Use as additional protection for the narrower threat model. Do not describe it as fail-closed. |
| Refuse a person with unexpected IDs | Observes an existing merge; deletion has no atomic expected-membership condition. | Add a preflight guard, with its race documented. |
| Hide the analytics ID | Raises the effort needed to obtain a merge target, but disclosure restores the attack. | Extra complexity without removing the merge weakness. Not preferred. |
| Hide the capture token behind a native webhook | Live raw capture using a numeric project ID stored a merge. Source explains the numeric lookup. | Reject as a merge-prevention boundary. |
| Mark profiles identified or split before deletion | Forced merges bypass identified-source protection; splitting lacks historical and atomic isolation guarantees. | Not a security boundary. |
| Globally disable person processing | Internal setting blocks identity events, but is not exposed in the inspected customer configuration and leaves no ordinary profile-deletion target. | No complete customer-only solution established. |
| Schema rejection | Account rejected `enforcement_mode: reject` because its provider feature flag is off. A type-based workaround is also undocumented. | Unavailable here; do not build on it. |
| Delete exact raw distinct IDs or event UUIDs | Internal provider implementations exist, but no general customer admission API was found. | Best provider capability to request for strict deletion scope. |
| Groups, cohorts or one project per installation | Groups/cohorts supply no verified replacement erasure API; per-installation projects add disproportionate provisioning and reporting work. | Reject for this use case. |

The detailed sources are in [merge mitigations](telemetry-merge-mitigations.md),
[private-ingestion research](telemetry-private-ingestion-research.md), and
[deletion alternatives](telemetry-deletion-alternatives-research.md). The original
[ownership PoC](telemetry-ownership-poc.md) remains valid. Changing authentication to a bearer
credential would not change any person-merge behavior.

## New live evidence

All trials checked the existing state, EU organization, project/environment mapping and capture
token association before mutation. They used the managed TEST role and its existing default
pipeline allowance. All subjects were fresh synthetic fixtures. Production, billing, existing
installation state and the earlier pending erasure experiment were untouched. No event-deletion
job, reset, project deletion or token rotation was requested.

### Authenticated source and numeric project-ID bypass

The source reused the tested native HMAC verifier with a separate `heartbeat-probe` action and a
fixed allowlist of two trial subjects. It constructed only one harmless probe event type and
explicit properties. It received no deletion credentials. This source accepted three valid
requests, rejected wrong signatures/actions, extra merge fields and raw identity events, and
returned 500 for an injected error before capture. A later valid request succeeded. The stored
rows contained exactly the three expected source-created probes, with no event from that error.

Raw capture without an `api_key` returned 401. Raw capture with the decimal TEST project ID as
`api_key` returned 200, stored a synthetic `$merge_dangerously` event, and joined the two fixture
IDs into one person. A second harmless numeric-token capture was also stored. The project token
was independently confirmed to have the normal `phc_` form and not equal its project number.
Knowing the installation ID and project number was sufficient for this observed merge route.

The pinned source explains the result. `getTeamByToken` shares a loader with numeric team lookup.
Positive decimal inputs are resolved through the team's numeric ID. Our earlier inference from
the method name missed that downstream behavior. Token secrecy, rotation and a fresh project
therefore do not establish the proposed protection.
[Lookup implementation](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/common/utils/team-manager.ts#L114).

The private ledger is `ingestion-5808ae56-cd5f-4941-9f33-62abf4ef69c0.json` in the task evidence
directory. The source trial ran at 15:44:04-15:44:06 UTC; independent observations through
15:46:53 UTC confirmed five stored events and the shared person. Its initial status remains
`FAILED` because the proposed token boundary failed. The source function was disabled and
soft-deleted, with GET 404 verified.

### Merge filter, including an execution failure

A temporary transformation matched only four run-owned IDs. It dropped the three identity
event types for those IDs and marked ordinary probe events to show it had executed. A separate
owned pair exercised a deliberate error before the drop. This fault hook was part of the test,
not a claim that an attacker can trigger that exact failure in a constant production filter.

Three initial creation attempts failed compilation and created no resources. An unsaved
component probe isolated the cause: the global event object cannot be assigned to directly.
Assigning it to a local variable first compiled. The first saved trial stopped before sending
merge attempts because only two of four baseline events appeared within its observation window.
Its function was removed. The resumed trial reused the same four IDs with the standard capture
token and confirmed the marker on every subject before continuing.

The resumed trial blocked all three identity operations and preserved the first pair as separate
single-ID persons. The injected transformation error on the other pair allowed the merge event
to be stored and combined both IDs into one person. This confirms the executor's inspected
fail-open behavior in the hosted TEST path.
[Executor behavior](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/hog-transformations/hog-transformer.service.ts#L209).

The final ledger is `merge-filter-b86c9e1b-5cc9-4073-9f10-d144ca1c0d65-resume.json`, observed
15:52:08-15:53:03 UTC. All six assertions passed with verdict `FILTER_WORKS_BUT_FAILS_OPEN`.
Across the initial and resumed filter trials, fourteen capture requests produced eleven observed
stored events, including the deliberately permitted merge. Both functions were disabled and
soft-deleted, with GET 404 verified. The six total synthetic installation IDs and their test events
remain as evidence; no claim is made that those data or internal soft-deleted configurations were
physically erased.

### Schema availability

The trial created one uniquely named ordinary event definition with mode `allow`. Changing only
that disposable definition to `reject` returned HTTP 400 with this diagnostic:

```text
Setting schema enforcement mode to "reject" requires the schema-enforcement-reject feature flag
```

Readback remained `allow`. No real identity definition, schema group, schema binding or provider
feature flag changed, and this trial captured no events. Definition deletion was acknowledged.
The subsequent detail GET returned HTTP 500; listing by its unique name confirmed absence.
The private ledger is `schema-53d6f236-508b-481d-9edb-1cf609ff8479.json`, recorded at 15:54 UTC.
The detail-read error remains a provider API observation, not evidence that schema rejection works.

## Reproduction and decision boundary

The bounded trial scripts and credential-free ledgers are grouped under
`/var/tmp/symphony-ownership-poc`. They are experiment artifacts, not application components.
The source trial intentionally retains its failing token-boundary assertion. Reproduction must
use fresh run-owned subjects or the explicit filter resume path, verify the TEST binding, and
record cleanup. Do not repeat it against production or unrelated identities.

The provider question is concrete: can the project reject identity-changing events before every
person mutation, including numeric-ID ingestion, and remain protected when rule loading or
execution fails? Alternatively, can a native worker submit an exact raw-distinct-ID/fixed-cutoff
deletion or exact event UUID deletion through a supported customer API?

No message to PostHog was sent. Until that stronger boundary exists, the recommendation must
state which threat model it meets. Protection against blind guessing is established by the
ownership design. Protection against arbitrary known-ID merges followed by person-wide deletion
is not established by these alternatives.
