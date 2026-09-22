# PostHog-native erasure capabilities

Researched on 2026-09-22. This note supports a `BLOCKED_NATIVE_CAPABILITY` verdict for
installation-key-authorized erasure. The first blocking prerequisite is a supported, maintained
asymmetric verifier callable inside PostHog Cloud. The documentation and inspected runtime
registries do not provide one. Atomic authenticated acceptance and the complete deletion/reuse
lifecycle also remain unproven. This research made no authenticated API calls or live mutations.
Public ingress, security attacks and unattended completion are **NOT_RUN** in this research.

This finding applies to the original protocol, which prohibited transmitting reusable secrets.
The maintainer later accepted a bearer credential; [ADR 0081](adr/0081-gate-automated-erasure-on-hosted-verification.md)
now records a hash-derived ID as the fallback candidate. It removes the asymmetric-verifier
prerequisite. Its deletion scope and operational lifecycle remain unproven.

Subsequent [ownership proofs](telemetry-ownership-poc.md) demonstrated public HMAC issuance and
verification and the bearer alternative. [Lifecycle research](telemetry-native-erasure-lifecycle-research.md)
also found schedule-run API idempotency distinct from the metrics-only observer described below.
The older findings do not establish that every ownership or admission mechanism is unavailable.

Evidence labels distinguish `DOCUMENTED` product behavior, `SOURCE_ONLY` implementation at a
fixed revision, and `UNKNOWN` account availability or behavior that needs a live test. Source
inspection does not establish which revision PostHog Cloud EU deploys. A failed probe of a guessed
function name is supporting evidence only, not an exhaustive capability test.

## Cryptographic verification

- `DOCUMENTED`: [Hog's crypto documentation](https://posthog.com/docs/hog#cryptographic-functions)
  lists MD5, SHA-256 and SHA-256 HMAC. It does not document ES256, another asymmetric verifier,
  JOSE/JWK validation, Web Crypto, or a package import mechanism.
- `SOURCE_ONLY`: PostHog revision `e6fddf2700753989840cdae6c4e8a1cb4dc534dc` exposes
  MD5, SHA-1, SHA-256 and SHA-1/SHA-256 HMAC wrappers through its
  [TypeScript standard-library registry](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/common/hogvm/typescript/src/stl/stl.ts#L1109)
  and [crypto implementation](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/common/hogvm/typescript/src/stl/crypto.ts).
  Inspection of the full registry, its bytecode-library entries, and the
  [Python registry](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/common/hogvm/python/stl/__init__.py)
  found no standard asymmetric verification function.
- `SOURCE_ONLY`: The [VM dispatch code](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/common/hogvm/typescript/src/execute.ts#L813)
  resolves registered host callbacks and library functions. The
  [CDP execution wrapper](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/utils/hog-exec.ts)
  passes Node crypto as an implementation dependency, not as a request-accessible JavaScript
  module. The [executor callbacks](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hog-executor.service.ts#L210),
  [async executor](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hog-executor-async.service.ts#L229),
  and [async registration imports](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/async-functions/index.ts)
  expose no asymmetric verifier. Installing `jose` locally does not extend this registry.
- `SOURCE_ONLY`: [Standard Webhooks support](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/utils/standard-webhooks.ts)
  signs outbound requests with HMAC-SHA256. The
  [incoming webhook template](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/templates/_sources/webhook/incoming_webhook.template.ts)
  compares an optional fixed authorization header. Neither implements installation-specific
  public-key verification or the required self-certifying target binding.

No supported ES256, RSA or Ed25519 alternative was found in these registries or documentation.
A provider-supported verifier added to hosted Hog would address the first prerequisite. Hashes,
JWT decoding, a shared bearer password and custom handwritten elliptic-curve code do not satisfy
it. A call to an external verifier violates this trial's native-only constraint. These findings
support stopping before privileged deletion; they do not prove that every unpublished PostHog
capability is absent.

## Ingress and responses

| Evidence | Capability | Consequence for this trial |
| --- | --- | --- |
| `DOCUMENTED` | [Incoming webhooks](https://posthog.com/docs/cdp/sources/incoming-webhooks) provide a unique URL, parsed request data and Hog code. The payload limit is 500 KB. `fetch` queues background work and returns 201. | Native ingress exists. HTTP acceptance does not establish signature verification or durable authenticated admission. |
| `DOCUMENTED` | [Workflow webhook triggers](https://posthog.com/docs/workflows/workflow-builder#webhook-triggers) start a workflow directly. | A workflow does not need an analytics-event trigger or a messaging account merely to start. |
| `SOURCE_ONLY` | The [source consumer](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/consumers/cdp-source-webhooks.consumer.ts) accepts a synchronous `httpResponse`, including status/body, and supplies `request.stringBody`. It queues unfinished source-function execution. Workflow-trigger code rejects delayed processing. | A synchronous error or receipt is structurally possible, but a management fetch cannot be assumed to finish before the response. Strict parsing and truthful receipts still need Cloud tests. |
| `DOCUMENTED` | [Destinations](https://posthog.com/docs/cdp/destinations/customizing-destinations) send requests out from PostHog. [Endpoints](https://posthog.com/docs/endpoints) expose saved query results. | An outbound webhook is not an incoming control handler. A query endpoint is not a documented programmable verifier or atomic state store. |

`UNKNOWN`: Public EU invocation, authenticated status/resume, request-size policy below the provider
limit, duplicate JSON handling, endpoint lifecycle and complete fresh provisioning were not tested
here. An unsaved management test invocation does not prove a saved public route works.

## Trusted state and continuation

`DOCUMENTED`: Workflows have delays and output variables, and the
[workflow API](https://posthog.com/docs/workflows/surfaces/api) manages graphs and invocations with
project-scoped `hog_flow:read` and `hog_flow:write` credentials. This documents workflow persistence,
but does not specify an application-keyed conditional insert, compare-and-swap, or a transaction
combining erasure acceptance with scheduling.

`SOURCE_ONLY`: The
[workflow worker](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/consumers/cdp-cyclotron-worker-hogflow.consumer.ts#L171)
warns when an event-triggered run has no person and continues constructing the invocation. The
[executor](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hogflows/hogflow-executor.service.ts#L87)
keeps the person outside persisted state. Therefore this revision does **not** justify claiming
that deleting the triggering person always aborts a workflow. Conversely, it does not prove that
this erasure workflow survives deletion, retries, merge/repoint behavior and later reuse in Cloud.

`SOURCE_ONLY`: The
[duplicate observer](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hogflows/hogflow-duplicate-observer.service.ts)
uses a 15-minute Redis key for workflow/event/action identity and permits store failures. Its
[caller](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hogflows/hogflow-executor.service.ts#L240)
discards the duplicate result. It records metrics; it is not the required fail-closed replay ledger.
The source consumer also accepts caller-supplied `$variables`, so initial variables require
validation before they become trusted operation state. Per-run output variables do not establish
atomic agreement between separate requests carrying the same signed `jti`.

`UNKNOWN`: No supported store was established with authenticated subject/`jti` uniqueness,
content-conflict detection, leases, replay retention independent of analytics, rebuild survival,
and authenticated status lookup. Do not infer such a contract from internal Redis or Cyclotron
storage. Concurrent admission, stale retries after enablement, scope preservation through merges,
and native event-erasure/reuse completion remain required live gates.

## Secrets, limits and infrastructure

- `DOCUMENTED`: [Destination inputs](https://posthog.com/docs/cdp/destinations/customizing-destinations#guidelines-for-modifying-a-destination)
  support secrets encrypted at rest and omitted from later UI responses. A function errors after
  more than five `fetch` calls. This is not an unlimited polling worker.
- `SOURCE_ONLY`: The [Hog executor](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hog-executor.service.ts)
  sets five async steps, limits logs and sanitizes explicit `print` output against known secret
  values. [Function storage](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/products/cdp/backend/models/hog_functions/hog_function.py#L139)
  separates encrypted inputs. Neither fact establishes that request bodies, exceptions, responses
  or serialized VM snapshots are wholly redacted. Incoming-webhook payload logging is separately
  documented and exposes submitted bodies when enabled.
- `DOCUMENTED`: The [pricing page](https://posthog.com/pricing) lists monthly allowances of
  10,000 data-pipeline events plus 1 million rows, and 10,000 workflow messages per channel.
  `SOURCE_ONLY`: [Workflow quota checks](https://github.com/PostHog/posthog/blob/e6fddf2700753989840cdae6c4e8a1cb4dc534dc/nodejs/src/cdp/services/hogflows/hogflow-quota-limiting.ts)
  include destination dispatches. `UNKNOWN`: The TEST organization's remaining allowances,
  feature flags and exact billing treatment of this proposed flow require account read-back.
  Public pricing does not establish account entitlement, and no paid feature was enabled here.
- `SOURCE_ONLY`, confirmed against the installed provider schema on 2026-09-22: The repository
  pins provider 1.0.21. That provider's
  [Hog function schema](https://github.com/PostHog/terraform-provider-posthog/blob/736a4fbd631b90c378bed2a083be010bf4725764/docs/resources/hog_function.md)
  supports `type = "source_webhook"`, code, filters, input schema and `sensitive_inputs_json`.
  Its [resource registry](https://github.com/PostHog/terraform-provider-posthog/blob/736a4fbd631b90c378bed2a083be010bf4725764/internal/provider/provider.go)
  has no workflow resource. The local `scripts/posthog-infra tofu providers schema -json`
  output confirms these fields and the missing workflow resource. Workflow management therefore
  needs the existing API supplement if
  the security gates eventually pass. A sensitive input still requires private OpenTofu state;
  plan-output redaction is not state encryption.

For the original protocol, the minimum provider work is a maintained hosted asymmetric verifier plus a
supported atomic operational-state contract. The completed solution also needs native scheduling,
authenticated status and tested deletion/reuse scope. Adding verification alone is insufficient.
Keep normal application identity unchanged and automated production erasure unavailable until
the full live gates pass.
