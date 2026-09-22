# PostHog merge mitigations and the accepted threat model

Researched on 2026-09-22 against official documentation and PostHog source revision
`e6fddf2700753989840cdae6c4e8a1cb4dc534dc`. The new findings below are `SOURCE_ONLY` or
design analysis. No capture, merge, deletion or project configuration was changed for this
investigation. This note preserves the hosted verification gate in
[ADR 0081](adr/0081-gate-automated-erasure-on-hosted-verification.md) and makes no
user-facing contract change.

The subsequent [alternatives comparison](telemetry-erasure-alternatives.md) adds live evidence:
the filter blocks normal merge events but fails open on an execution error, and private capture
tokens do not close numeric project-ID ingestion. The hidden-ID candidate below remains a
disclosure mitigation and is not the preferred solution to the merge boundary.

## What the merge risk requires

The previous [lifecycle investigation](telemetry-native-erasure-lifecycle-research.md)
established that person-wide deletion is broader than installation-wide deletion after a
merge. That is a real API limitation. It does not establish that an attacker sending random
strings can merge or delete existing installations.

PostHog's merge handler takes two concrete IDs. `$create_alias` and `$merge_dangerously`
use the event's `distinct_id` and `properties.alias`. `$identify` uses `distinct_id` and
`properties.$anon_distinct_id`. The inspected handler passes these exact strings to the
person store. It does not accept a wildcard, property filter or request to merge all people.
[Merge dispatch and request construction](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-merge-service.ts#L100).

The documented public APIs accept capture and feature-flag requests using the project token.
Reading people or querying analytics requires private API authentication. No public endpoint
that enumerates installation IDs was found in the inspected API documentation. This is a
bounded finding, not proof that every optional PostHog feature or customer-created endpoint
cannot disclose an ID. Publicly shared dashboards, exports and our own responses still need
to avoid disclosing installation IDs.
[API authentication and endpoint categories](https://posthog.com/docs/api#authentication).

Therefore the attack discussed earlier assumes the attacker learns at least one ID already
associated with a victim's person. If installations remain separate and their IDs are
independent unpredictable values, blind merge attempts face the same guessing problem as
blind deletion attempts. A UUIDv4 has 122 random bits; with `N` victims and `q` independent
guesses, the union bound is approximately `q * N / 2^122`. For a million victims and a
trillion guesses, that bound is about `1.9e-19`. This is a probability calculation under
uniform random generation, not a measured service guarantee.
[UUIDv4 bit layout](https://www.rfc-editor.org/rfc/rfc9562.html#section-5.4).

For the maintainer's stated threat model, random guessing alone does not make merge support
an automatic rejection of PostHog. A known-ID attacker is a stronger threat. We should name
that distinction when choosing between the simple design and additional isolation.

## What PostHog's built-in protections do

Ordinary identity operations refuse to merge a source person that is already identified.
Marking installations identified therefore reduces accidental merges. It does not prevent
an attacker from using `$merge_dangerously`: the implementation sets
`allowIdentifiedSources` to true for that event, and its source test verifies this behavior.
[Merge request](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-merge-service.ts#L338),
[test](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/persons/person-merge-service.test.ts#L183).
The official forced-merge example uses the ordinary project token and explicitly supplies
both IDs. It does not require a personal API key.
[Forced merge documentation](https://posthog.com/docs/product-analytics/identify#how-to-merge-users).

A transformation that drops all identity events is useful protection against mistakes and
normal-path abuse, but the earlier source investigation found that transformation execution
errors let ingestion continue. It does not establish isolation against a known-ID attacker
during those errors. Likewise, checking a person's ID list before requesting deletion rejects
an existing unexpected merge but leaves a race with a later merge. Neither measure proves an
atomic installation-only deletion.
[Transformation failure handling](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/hog-transformations/hog-transformer.service.ts#L209),
[deletion endpoint](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/person.py#L905).

There is also a provider-operated ingestion restriction with an event-name filter and a
`drop_event_from_ingestion` action. Its model permits a rule for the three identity event
names across every distinct ID in the project. The inspected customer API only exposes a
GET action with `project:read`; editing uses PostHog's Django admin.
[Restriction model](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/event_ingestion_restriction_config.py),
[customer read endpoint](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/team.py#L2987),
[admin editor](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/admin/admins/event_ingestion_restriction_config.py).
This gives PostHog support a concrete capability to discuss. It is not an established
self-service solution. The Node restriction manager also catches dynamic configuration
loading errors and retains static restrictions, so that code alone does not establish a
fail-closed merge prohibition.
[Restriction loading and fallback](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/common/utils/event-ingestion-restrictions/manager.ts#L82).

## A stronger candidate within PostHog

A native incoming webhook can authenticate telemetry and construct the stored event itself.
PostHog documents `postHogCapture` inside that source, including explicit selection of the
stored `distinct_id`. The existing ownership PoC has separately proved HMAC verification
in the hosted source environment. Their combination is feasible from those components;
the complete authenticated capture design has not been live-tested.
[Incoming webhook event capture](https://posthog.com/docs/cdp/sources/incoming-webhooks#capturing-a-posthog-event),
[ownership PoC](telemetry-ownership-poc.md).

The candidate keeps two IDs with different purposes:

- The installation holds its credential ID and installation HMAC key, as in the tested
  issuance design.
- The source derives the analytics distinct ID with a server secret and a separate HMAC
  purpose, for example `HMAC(serverSecret, "analytics-id|project|credentialId")`.

The client signs telemetry using its installation key. After verification, the webhook
builds an allowed event with the derived analytics ID. The same server derivation gives the
erasure handler its deletion target. It needs no identity binding event, database lookup or
periodic upload. Event retention does not affect the derivation. Retaining the server key
and its historical versions remains necessary.

The webhook must choose the event name from an allowlist and build allowed properties. It
must not forward client-supplied `distinct_id`, `alias`, `$anon_distinct_id`, special identity
event names or a general property object into `postHogCapture`. Otherwise the new endpoint
would itself expose a way to issue merge operations. It must not return the derived analytics
ID in responses, errors or client-visible status. These are design requirements inferred from
the merge inputs above, not claims about an implemented endpoint.

This raises the attack requirement. Learning a public credential ID no longer reveals the
victim's analytics distinct ID. An attacker who bypasses the webhook and submits a raw capture
request still needs the hidden analytics ID to join a victim's person. The project token can
remain public without providing that ID. This is cryptographic guessing resistance, not a
claim that PostHog's merge feature has been disabled. A PostHog read-access compromise or
analytics-ID disclosure still exposes the person-wide deletion issue.

## Choosing the next proof

For blind random requests alone, independent high-entropy installation IDs plus the tested
ownership protocol address the stated attack. Avoid shared IDs and intentional cross-installation
aliases. Reject an already merged person as an additional check, while documenting its race.
The unresolved provider scope limitation matters if the contract also requires isolation after
a victim ID becomes known.

If that stronger protection is worth routing telemetry through one more native function, the
derived private analytics ID is the next candidate to test. A bounded hosted PoC should verify
that valid signed telemetry uses only its derived ID, forbidden identity payloads create no
merge, wrong signatures create no event, and responses disclose no analytics ID. It should use
two synthetic installations and verify their separation through private reads. Existing events
under client-visible IDs require an explicit migration decision; hiding future IDs does not
repair old identities or old merges.

The current automated-deletion gate remains in place. This investigation corrects the threat
assessment and identifies a native candidate; it does not prove deletion completion, replay
recovery, same-ID reuse or all the lifecycle cases required by ADR 0081.
