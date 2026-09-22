# Private capture token behind a native webhook

Research date: 2026-09-22. This report combines source review with the root agent's bounded
TEST evidence. The source review itself read no credentials and made no live requests. The
TEST trial used two synthetic installations and executed no deletion. There is no user-facing
contract change.

## Finding

Reject private capture-token storage as the merge-prevention boundary. A native webhook can
authenticate heartbeats and capture them without exposing the project token, but an attacker
can bypass that webhook using the numeric team ID as `api_key` on the capture API. The TEST
trial stored a synthetic `$merge_dangerously` event sent this way and merged both owned
fixture IDs. The reviewed ledger records the observation at 2026-09-22 15:46:53 UTC.

The root cause is in `TeamManager`. `getTeamByToken` and ID lookup share one loader. That
loader interprets positive decimal strings of up to ten digits, within the signed 32-bit range,
as team IDs and queries `t.id`; other strings query `t.api_token`. It stores results under both
keys. A caller therefore does not need to discover the secret project token for this route.
[Shared lookup and numeric parsing](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/common/utils/team-manager.ts#L114)

The first source pass examined the caller's `getTeamByToken` name and incorrectly inferred
that it matched only project tokens. The live negative test disproved that inference. The
corrected finding follows the shared loader and SQL query. Keeping a normally public capture
token private does not prevent the demonstrated merge attack.

The rest of this report preserves the component findings and migration costs so that a future
provider change can be assessed without repeating the research. PostHog documents capture as
a public endpoint using a project token and private API authentication separately.
[API classification](https://posthog.com/docs/api)

## Native capture and failure handling

The documented native webhook API accepts an event through `postHogCapture`. Its arguments
contain an event name, a distinct ID, and properties, with no project token. The implementation
places these values in an invocation result with the function's own `team_id`. The capture
service looks up that team and supplies `team.api_token` internally.
[Webhook API](https://posthog.com/docs/cdp/sources/incoming-webhooks#capturing-a-posthog-event),
[Hog executor](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hog-executor.service.ts#L236),
[capture service](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/captured-events/captured-events.service.ts#L61)

Unlike a transformation, a source webhook has no incoming analytics event to forward when its
code fails. In the inspected code, the capture service emits only entries in
`capturedPostHogEvents`. An exception before the explicit capture call leaves that list empty.
There is no observed fallback that captures the raw request. This is evidence about the
inspected implementation, not a claim covering every failure in the hosted service.
[Source execution](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/consumers/cdp-source-webhooks.consumer.ts#L345)

All authentication and validation must precede capture. The executor retains its result when
an error occurs, so an event captured before a later error is not guaranteed to roll back.
The handler must construct the event name and allowlisted properties itself. It must not
forward caller-selected event names, distinct IDs, or arbitrary properties such as identity
linking fields. Signing arbitrary client-supplied merge events would defeat this design.
[Executor error handling](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hog-executor.service.ts#L363)

A degraded source function is queued for later execution and returns an empty HTTP 200 in the
inspected consumer. A disabled function returns 429. Deferred execution still executes the
same function; it is not a raw-event capture fallback. Clients must not equate an HTTP 200
with a committed heartbeat. Expiry checks on delayed requests, retry behavior, and duplicate
heartbeats need an explicit policy.
[Degraded execution](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/consumers/cdp-source-webhooks.consumer.ts#L362),
[disabled execution](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/consumers/cdp-source-webhooks.consumer.ts#L461)

## What an unauthenticated caller can discover

The checked ingestion step requires a token, calls `getTeamByToken`, drops unresolved values,
and overwrites any body-supplied `team_id` with the resolved team. However, the downstream
loader resolves numeric token strings as team IDs. This makes a numeric team ID sufficient
on the tested route. The capture edge checks token shape before downstream resolution, so
HTTP acceptance alone does not prove successful ingestion. In this trial, subsequent event
and person queries confirmed ingestion and the merge.
[Team resolution](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/ingestion/common/steps/event-preprocessing/resolve-team.ts#L22),
[edge token validation](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/rust/capture/src/token.rs#L43)

The public remote-config route is `/array/:token/config`. It requires the token in the URL;
the inspected route is not a numeric-project-ID-to-token lookup. Shared dashboard context
uses `TeamPublicSerializer`, whose fields omit `api_token`. A source webhook UUID selects
the function for execution; the inspected consumer does not return its team token by default.
These checks did not find a public token-discovery route. They are not an exhaustive audit of
PostHog's APIs, sharing features, third-party integrations, or future releases.
[Remote config](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/rust/hypercache-server/src/api/remote_config.rs#L87),
[public serializer](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/shared.py#L273),
[shared context](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/sharing.py#L310),
[webhook selection](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/consumers/cdp-source-webhooks.consumer.ts#L436)

Project tokens use a cryptographic random generator with 255 effective random bits in this
revision. They are not derived from project IDs. The numeric lookup bypass means an attacker
does not need to guess them on the tested route. Tokens also face disclosure through an SDK
configuration, frontend bundle, published infrastructure output, log, integration, or member
with project access. The token's ordinary public classification makes accidental disclosure
more likely than for an explicitly secret credential.
[Token generation](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/utils.py#L146)

## Rotation and migration

The inspected project API has an admin-only `PATCH .../reset_token/` action. It generates a
new project token, saves it, invalidates the old token's team cache entry, and sets the new
entry. The code does not retain the old capture token as a backup. Legacy `secret_api_token`
rotation and its backup are separate features and should not be confused with this action.
[Reset action](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/api/team.py#L2669),
[token replacement](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/posthog/models/team/team.py#L958)

This establishes replacement and central cache invalidation, not a measured deadline for
revocation across every hosted worker, queue, or cache. It also leaves numeric team-ID lookup
working. Token rotation therefore does not close the demonstrated bypass. A future migration
would need a bounded test of old-token rejection at the data layer. An immediate capture HTTP
response is insufficient.

If an existing release contains the project token, hiding it in the next release leaves the
old token exposed. The existing token must be replaced, and old clients that submit directly
will stop contributing events. Native capture fetches the team's current token internally,
which avoids putting the replacement token into clients. Other server integrations and
infrastructure state still need reconciliation after rotation.

Rotation also does not undo past merges or revoke management credentials that can merge
persons. Existing persons require inspection and isolation before relying on person-wide
deletion. Starting a dedicated clean project avoids inheriting merges but adds migration of
dashboards, infrastructure, and historical reporting. It also leaves historical data in the
old project needing its own erasure handling. Neither operation is authorized or performed
by this research.

## Assessment and next proof

The authenticated webhook itself passed valid-heartbeat, invalid-signature, raw-merge-payload,
and pre-capture-exception checks in the root agent's TEST trial. Those results establish the
behavior of that endpoint. The numeric capture route bypasses it. Creating another project
or rotating its project token does not fix the shared numeric lookup in the inspected code.

An acceptable stronger design needs a provider-enforced ingestion restriction that also
covers numeric team-ID capture, or deletion that cannot expand beyond the authenticated
installation. Hiding installation IDs remains a separate mitigation, not a fix for the
provider's person-wide deletion behavior. The current evidence does not justify deploying
automatic person deletion on the assumption that a private capture token prevents merges.

The existing [ownership PoC](telemetry-ownership-poc.md) proves native HMAC verification. It
does not establish isolation of person-wide deletion from hostile merges.
