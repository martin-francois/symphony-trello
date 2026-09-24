# PostHog-native erasure trial

Verdict on 2026-09-22: `BLOCKED_NATIVE_CAPABILITY`. No supported maintained asymmetric verifier
was found in hosted Hog's documented functions or inspected execution registries. The EU test
runtime accepted hash calls and rejected the tested verifier names. No secure native erasure
path was established. [Capability research](telemetry-native-erasure-capabilities.md) gives the
primary sources and qualifications; [ADR 0081](adr/0081-gate-automated-erasure-on-hosted-verification.md)
records the decision.

Subsequent result: [HMAC and bearer ownership proofs](telemetry-ownership-poc.md) passed public
native authentication. HMAC is selected for ownership. The original asymmetric result below is
preserved as historical evidence. Safe deletion and complete job processing remain unproven;
[lifecycle research](telemetry-native-erasure-lifecycle-research.md) identifies the scope blocker
and a stronger native admission candidate.

The maintainer subsequently accepted transmitting a per-installation bearer secret over HTTPS.
The accepted fallback candidate derives the public ID from that secret and requires no enrollment.
The blocked verdict above describes the original asymmetric protocol; the bearer candidate is
`NOT_RUN`, with no handler deployed. It needs hashing, which the probe exercised, but still needs
proof of scoped deletion and the complete job lifecycle.
Supported native proof of possession without resending the credential remains the preference.
The original trial did not test HMAC provisioning; the linked subsequent PoC did.

## Executed evidence

The resumed trial used `feat/installation-telemetry`, based on `762a8852`, with the changes that
add this report. At 13:56:27 to 13:56:29 UTC, the selected OpenTofu state and live EU metadata
agreed on the test role, project 281083, its organization and capture token. Production 281084
was not invoked. IDs here record this observation; the command discovers its target from state.

The authenticated `hog_functions/new/invocations` test API executed 16 unsaved destination
configurations with `enabled: false` and `mock_async_functions: true`. This is hosted execution
through the administration API, not a deployed public ingress test. The command created no
handler, captured no event, issued no deletion and provisioned no server credential. There are
no new resources or fixtures to clean up.

| Observation | Result |
| --- | --- |
| `sha256Hex('abc')` | Expected SHA-256 digest |
| `sha256HmacChainHex`, `md5Hex`, `jsonParse`, `generateUUIDv4` | Function values present |
| `base64Decode('YWJj')` | `abc` |
| `ecdsaVerify`, `verifySignature`, `rsaVerify`, `jwtVerify`, `verifyJwt`, `jwtDecode`, `crypto` | `Global variable not found` for each tested name |
| `hmacSha256`, `base64UrlDecode`, `hexDecode` | Same missing-global result; other documented hash/encoding functions exist |

Missing names alone do not prove complete capability absence. The full library and callback
registry inspection supports the blocked verdict. PostHog source is not a deployment manifest;
a supported new API or provider confirmation would justify reopening the investigation.

| Required gate | Evidence and status |
| --- | --- |
| Native public ingress and responses | `DOCUMENTED` and `SOURCE_ONLY`; deployed public route `NOT_RUN` |
| Native standard signature verification | `BLOCKED_NATIVE_CAPABILITY`; authenticated runtime probe plus registry inspection |
| Self-certifying identity and negative requests | Local TypeScript reference passes; Java interoperability and hosted attacks `NOT_RUN` |
| Atomic admission, replay conflicts, concurrent duplicate requests | `UNKNOWN`; live tests `NOT_RUN`. Reference is stateless and accepts a valid replay |
| Capture-injection bypass | Local reference rejects ID/event-only and extra-target requests; native injection experiment `NOT_RUN` |
| Scoped deletion through person merges and late arrivals | `NOT_RUN`; no privileged handler installed |
| Continued processing after client exit/person deletion | `NOT_RUN`; workflow persistence alone does not prove the requested lifecycle |
| Event absence, canary survival, authenticated status, reuse, second erase | `NOT_RUN` in this trial; earlier maintainer experiment remains separate and pending |
| Provisioning and clean recreation | Installed provider schema checked; no automated-erasure resources applied or recreated |
| Entitlement, operational quotas and secret retention | Account-specific workflow entitlement/billing and execution-log behavior `UNKNOWN`; no paid feature enabled |

## Reusable local checks

`scripts/erasure-protocol-reference.ts` uses development-only `jose` for P-256/ES256 and RFC 7638
thumbprints. The namespace bytes are UTF-8 `symphony-trello/installation-id/v1` followed by NUL.
SHA-256 consumes those bytes and the decoded 32-byte thumbprint. Its first 16 bytes receive the
UUIDv8 version and RFC variant bits before lowercase UUID formatting. No application state or
heartbeat uses this identity yet.

The reference accepts only the fixed JSON member order documented in ADR 0081, canonical
unpadded base64url, exact public EC parameters, a UUIDv4 operation ID, nonnegative integer time
claims, a maximum 900-second lifetime, 60-second clock skew and a 4096-byte token bound. It
verifies the signature over the encoded request and derives the subject from the public key.
It supplies neither durable acceptance nor a deletion capability. Copied valid tokens remain
capabilities for the operation if a future service accepts them; signatures alone do not prevent replay.

```bash
pnpm run verify:scripts
```

The normal script suite contains fixed public identity/signature vectors and negative cases for
key substitution, tampering, algorithm confusion, duplicate/extra fields, malformed EC points and
signatures, remote key directives, private fields, target injection, encodings and time boundaries.
All keys generated by the tests stay local. The fixed signed fixture contains no private key.
The suite never invokes PostHog. Renovate's local extraction identified `jose` 6.2.12 under the
npm manager's `package.json` development dependencies.

## Repeat the harmless hosted probe

Use the existing private infrastructure state and protected key-file mechanism. The wrapper checks
the selected backend; the supplement checks the managed test resource and live project/environment
IDs, organization, name and capture token. When the provider stores `@current`, the probe resolves
it and requires agreement with that pinned project. A changed current organization does not choose
another target. Token values never enter the result ledger.

```bash
SYMPHONY_TRELLO_POSTHOG_LIVE_PROBE=1 scripts/posthog-infra hog-probe
```

This is an explicit live command, absent from CI. It makes bounded API calls, runs each of the 16
probes once and does not retry the trial automatically. The existing HTTP client limits each
request to 60 seconds and 4 MiB. An unexpected response exits nonzero. Exit zero means the probe
observations were understood, not that native erasure passed.

The command writes `hog-probe-<UTC timestamp>.json`, mode 0600, in the selected private state
directory. It refuses an existing ledger path. The ledger contains run ID, times, exact test
project/organization IDs, observations and empty resource/event/deletion lists. An interrupted
ledger remains `started`; a new probe run is safe because no persistent handler or operation exists.
Keep these local operational identifiers out of public evidence exports.

The maintainer's private run ledger records the resumed observation on 2026-09-22 at 13:56 UTC.
No native deletion job is pending. The older maintainer-driven experiment `erasure-b000c2f3`
remains at its saved `ERASURE_REQUESTED` checkpoint; this trial did not poll it or change its
subjects. Its last provider evidence, pending rows and exact command
template remain in [erasure verification](telemetry-erasure-verification.md). Reuse its original
experiment directory when resuming; creating another directory creates another experiment.

## What must change before integration

### Retention and enrollment-loss gates

The design must work without any original binding event. Test against a one-year event window,
including deployments where PostHog has not yet enabled retention enforcement. The following
hosted cases are all `NOT_RUN` and are required before integration:

- Remove or suppress every enrollment event while keeping recent telemetry. The owner still
  authenticates and erases only its own data; forged capture events confer no authority.
- Make all events older than one year unavailable. An installation first enrolled more than a
  year ago still authenticates. Repeat after more than a year offline and a new heartbeat.
- For enrollment-based candidates, drop the first enrollment request, then separately drop its
  response after the server commits. Restart the client at each persistence boundary and retry.
  Recovery preserves the owner and committed identity; no analytics precedes confirmed enrollment.
- Attempt recovery and conflicting enrollment with only the public installation ID. Neither
  returns a credential nor replaces the owner, including during concurrent retries.
- Complete erasure, remove the person and old events, resume the same ID, then erase again.
  Authentication still works, while replaying the first request does not erase the new data.

For the key-derived protocol, no enrollment exchange exists. Demonstrate authentication from a
valid signed request with no preexisting binding record. For HMAC, first prove a supported native
enrollment and credential mechanism independent of event retention; no such mechanism was tested
in this trial. Do not substitute a repeating analytics event or a mutable person property.

For the hash-derived bearer candidate, demonstrate these additional cases, all `NOT_RUN`:

- With no binding record, the original secret derives the reported ID and authorizes only its data.
- Public IDs, malformed secrets, missing fields, random unknown secrets and supplied target/filter
  overrides never authorize another installation or trigger unfiltered deletion.
- An attacker injects capture events and person properties or causes an identity merge. The handler
  still cannot erase an unrelated installation through the provider's person-level deletion API.
- The server accepts a request but its response is lost. Retrying that operation and later replaying
  it after reporting resumes does not erase the later reporting period. Test fresh requests from a
  secret holder separately; bearer possession intentionally authorizes those requests.
- Inspect native request logs and document who can retrieve a submitted credential. Routine
  heartbeats, application logs and URLs contain no secret. Do not claim end-to-end log redaction
  solely because application logging omits it.

This clarification changed SPEC's future integration gates and ADR 0081's design constraints.
Apart from the development-only `jose` dependency of the protocol reference, the trial itself
added no runtime behavior, other dependency, deployment resource, CLI command, release artifact or
companion-repository change. [ADR 0082](adr/0082-authenticated-erasure-with-reporting-periods.md)
later added the `erase` and `erase-status` commands, persisted ownership state and the native
deployment resources.

### Remaining native capabilities

For the original protocol, a maintained asymmetric verifier callable from native ingress remains
missing. The fallback bearer candidate removes that prerequisite. Both still need a supported
atomic operation ledger keyed by deployment, subject and request ID. The ledger must survive
person erasure and deployment recreation, reject content
conflicts, and drive native scheduling and authenticated status. Scope-safe deletion and reuse
still need their own live tests. The installed provider supports `source_webhook` and secret
inputs; workflows currently require API-supplement ownership if this gate later passes.

No external service, public webhook password, event-membership authorization, handwritten crypto,
or client-side-only verifier was substituted. The trial left the Java identity, CLI, persistence,
telemetry schema, privacy text and user erasure procedure unchanged. ADR 0082 changed them later.
Production activation is disabled.
