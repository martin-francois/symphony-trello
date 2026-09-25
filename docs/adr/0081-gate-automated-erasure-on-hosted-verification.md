---
status: "superseded by [ADR 0082](0082-authenticated-erasure-with-reporting-periods.md)"
date: 2026-09-22
decision-makers: [François Martin, Codex]
consulted:
  - "[Native capability research](../telemetry-erasure-research.md#posthog-native-execution)"
  - "[Native trial evidence](../telemetry-erasure-research.md#es256-and-jose-rejected)"
  - "[PostHog Hog documentation](https://posthog.com/docs/hog)"
  - "[jose](https://github.com/panva/jose)"
informed: [Future maintainers, Contributors]
---

# Gate automated erasure on hosted verification

[ADR 0082](0082-authenticated-erasure-with-reporting-periods.md) updates identity and erasure
behavior. It replaces this ADR's constraint to keep the Java CLI, state schema, heartbeat fields
and OpenTofu deployed resources unchanged. The CLI gains erasure commands, the state gains
ownership fields, `distinct_id` gains a reporting-period part, and OpenTofu gains optional
erasure resources. The other telemetry requirements and production verification gate still
apply.

## Context and Problem Statement

The maintainer wants an installation to request erasure, exit, and later resume reporting under
the same identity without a support queue or a separately hosted service. A public capture token
allows callers to submit arbitrary installation IDs. An ID, even paired with a matching event,
therefore proves no authority to erase that installation.

The proposed protocol binds a random P-256 key to one UUIDv8 through its RFC 7638 thumbprint.
An ES256 request authorizes one operation on that ID. It requires server verification, durable
atomic admission, scoped asynchronous deletion and automated reuse. Should that protocol replace
the current random UUID and maintainer erasure process before those capabilities are proven?

## Decision Drivers

- Public analytics writes must confer no erasure authority.
- Ownership verification must survive missing enrollment events and a one-year analytics window.
- The accepted job must continue after the client exits and the person disappears.
- Replays must not erase a later reporting period.
- The completed service must run entirely in PostHog-managed facilities.
- Existing disabled preferences, identities and pending provider experiments must survive this trial.
- A local cryptographic test is not evidence of hosted execution.

## Considered Options

- Supported PostHog-native ES256 verification and trusted job state
- Another supported native asymmetric algorithm with equivalent binding and lifecycle
- A local bearer secret with a hash-derived public ID and no enrollment
- A per-installation HMAC credential with trusted enrollment and independent credential state
- A public event, shared secret or client-side verification as authorization
- Handwritten asymmetric verification in Hog
- An external verifier and durable worker
- Stop at the capability gate and retain a guarded probe and local protocol vectors

## Decision Outcome

Retain the probes and local vectors; do not activate automated erasure. The original trial verdict is
`BLOCKED_NATIVE_CAPABILITY`. Current documentation and the inspected standard-library and host
registries expose hashing and HMAC but no maintained asymmetric verifier. The authenticated EU
test invocation confirms hashes work and the tested verifier names are absent. It does not prove
public ingress behavior or exclude unpublished capabilities. No supported equivalent was found.

That verdict applies to the original protocol, which prohibited sending a reusable secret. The
maintainer subsequently accepted a per-installation bearer credential as a fallback and reaffirmed
that adding another hosted tool or backend is out of scope. The maintainer still prefers supported
proof of possession without revealing the credential on each request when implementation and
maintenance remain simple. Stronger authentication is a preference, not a requirement that
justifies extra infrastructure or disproportionate complexity for installation analytics.
Neither candidate may require periodic credential or public-key uploads to outlive event retention.
The subsequent [ownership proofs of concept](../telemetry-erasure-research.md#ownership-proof-options) demonstrated native HMAC
issuance and verification through public TEST webhooks. Select server-derived HMAC for ownership;
keep hash-derived bearer authentication as the accepted fallback. Both passed authentication tests,
and neither has passed the complete erasure lifecycle.

Trusted atomic admission and end-to-end deletion/reuse also remain unproven. A provider-supported
verifier alone does not establish the complete design. The remaining concrete blocker is provider
deletion scope through identity merges. Resolve that boundary, then test native admission and the
remaining lifecycle gates before changing application identity or enabling production.

### Merge alternatives and proportionality

The [alternatives investigation](../telemetry-erasure-research.md#identity-merges-and-the-merge-filter) separates the maintainer's
primary blind-guessing threat from an attacker who already knows another installation ID. Keep
HMAC ownership and unpredictable installation IDs. Prefer a native identity-event drop filter
as additional protection over a second hidden ID or a heartbeat proxy introduced only to prevent
merges. This is the simplest candidate for the narrower threat model, not permission to claim
strict isolation after an ID is disclosed or to bypass the existing activation gate.

The hosted TEST filter blocked all three identity event types during normal execution, but an
injected execution error allowed a merge. A preflight person-membership check also leaves a race.
Keeping the capture token private fails as an alternative: a raw request using the numeric TEST
project ID stored a merge, matching the source's shared token/ID loader. Do not rotate tokens or
change heartbeat transport on the assumption that those operations solve the merge boundary.

Exact-event and raw-distinct-ID deletion exist internally but no general customer admission API
was established. Schema rejection was unavailable in the tested account; a type-based workaround
also depends on undocumented interactions. For strict scope, require supported provider rejection
of identity changes or precise event deletion. Continue the current maintainer erasure flow until
the remaining automated-erasure requirements are proved. No additional backend is selected.

### Selected ownership component: server-derived HMAC

The native issuer generates a fresh installation ID and derives a unique key from a protected
server master key. It returns the pair once over HTTPS. The client persists it before reporting,
then authenticates erasure requests with HMAC-SHA-256. The native verifier recomputes that key
without looking up any binding event or installation registry. No periodic key upload is needed.

The public TEST proof rejected caller-selected enrollment IDs and demonstrated Node/Hog HMAC
interoperability. Lost issuance responses are abandoned before any client data uses the unanswered
ID; retrying issues another fresh pair. This is simpler than recovering unused issuance records
through a new database. It is not permission to replace a credential after reporting has begun.

Compared with bearer authentication, the extra cost is one small native endpoint and master-key
management. Erasure requests reveal only a short-lived proof for their operation. That benefit is
worth the additional endpoint for the maintainer's stated preference. Keep the master outside
analytics retention, back it up and preserve required historical key versions. Those operational
steps and client persistence are not implemented by the authentication PoC.

### Accepted fallback candidate: hash-derived bearer credential

Generate and persist a cryptographically random 32-byte secret locally before the first heartbeat.
Derive the public installation ID using SHA-256 with a fixed protocol namespace and deployment
scope. The next trial must fix an unambiguous encoding and ID format with interoperability tests;
prefer the full digest to preserve its preimage resistance. Heartbeats contain the public ID only.
The client sends the secret to the PostHog erasure endpoint over HTTPS when requesting deletion.
The endpoint derives its sole target itself. It never accepts a caller-selected person, project,
filter or alternative target ID. No initial registration, secret-bearing analytics event or
binding registry is required.

This is possession-based authorization. It addresses the maintainer's primary concern: repeated
random requests must not erase existing installations or expand into project-wide deletion.
Strict credential parsing and deletion scoping remain mandatory even with an unguessable secret.
An unknown derived ID is a harmless no-match; malformed or missing input never becomes an empty
filter. Bound request size and apply available native throttling to limit abuse costs.

Anyone who obtains the reusable secret can authorize fresh deletions for that installation,
including after it resumes reporting with the same credential. The maintainer accepts that
credential-theft risk in preference to adding a backend. Keep the secret out of routine events,
URLs and application logs. Inspect and document native request-log exposure; do not claim it is
absent without evidence. A copied exact request must still obey operation deduplication, but
deduplication cannot prevent a secret holder from making a new authorized request.

The binding is recomputed on every request, so missing first events, a lost binding message and
one-year analytics expiry cannot destroy it. Persisting the local secret remains necessary; public
ID knowledge cannot recover it. Existing random-UUID installations need a separately reviewed
migration and cannot be claimed by presenting their public ID. Provider deletion scope, person
merges, unattended completion, retries and reuse remain separate live acceptance gates.

The development-only reference used `jose` for JWK thumbprints and JOSE signing/verification,
with Node's SHA-256 for the fixed namespace derivation. It accepted a fixed JSON representation:
header members `alg`, `typ`, `jwk`; JWK members `kty`, `crv`, `x`, `y`; claims `action`, `sub`,
`aud`, `jti`, `iat`, `exp`, in those orders. Segments use canonical unpadded base64url. Exact
reserialization rejects duplicate fields, alternate escaping and extra targets without a custom
JSON parser. This narrows general JOSE JSON and would need matching Java vectors before adoption.
It does not implement RFC 8785. Admission remained absent; a valid replay passed the stateless
reference deliberately, and a test recorded that limitation. The reference, its tests and `jose`
were removed after [ADR 0082](0082-authenticated-erasure-with-reporting-periods.md) chose HMAC
credentials. Commit `15760c9f` still contains `scripts/erasure-protocol-reference.ts` and
`scripts/erasure-protocol-reference.test.ts`.

### Binding lifetime and enrollment recovery

Treat events older than one year as unavailable, even while provider retention enforcement is
disabled. PostHog documents a one-year free-plan event window and a gradual enforcement rollout.
That window is neither a credential lifetime nor proof of physical deletion. An installation
must still prove ownership after its first event disappears, after a year offline, and after
person erasure followed by reporting under the same ID.

Reject a first analytics event as the authoritative credential binding. Repeating such an event
does not fix the trust problem: public capture callers can forge it. Mutable person properties
also fail the ownership boundary and disappear during erasure.

The key-derived ID in the reference avoids enrollment entirely. Each erasure request carries
the public key and a signature; the verifier derives the ID and verifies possession of the
private key without looking up an old event. This solves binding recovery in the protocol, but
hosted signature verification and durable job state remain unproven.

A separate 256-bit per-installation secret with HMAC-SHA-256 is the selected ownership component,
pending the complete service gates. A registry-based variant would require a trusted store
independent of events and person records. The selected master-key derivation avoids that registry.
Hashing the secret into a public installation ID
does not let a server verify an HMAC without the secret. The fallback bearer candidate explicitly
changes the earlier credential boundary and avoids the HMAC enrollment dependency.

The native issuer must never issue keys for caller-selected existing IDs. Issuance, initial-response
loss and native verification passed the authentication PoC. Long-term master-key handling and
provider log exposure still need operational verification. An installation must not report until
it has durably saved its issued
credential. Abandoning an unanswered issuance is acceptable only before any data uses that ID.

For a registry-based candidate that must recover the same unanswered issuance, persist a private recovery credential before enrollment and
retry the same enrollment with proof of possession. A lost request must be retryable; a lost
response must recover the same committed result without replacing its owner. Confirm durable
enrollment and persist the resulting installation credential before sending analytics under that
ID. Public ID knowledge must never recover a secret or rebind an existing ID. The concrete native
protocol must demonstrate these properties before integration. This does not authorize automatic
claims over existing installations that lack an ownership credential.

Credential recovery and operation replay protection are separate requirements. The operation
ledger must also survive analytics expiry and person erasure, with a demonstrated retention rule
that prevents an old request from erasing a later reporting period. A permanent binding alone
does not establish that rule.

### Consequences

- Good, because no destructive handler exists without an authorization mechanism.
- Good, because maintainers can repeat a bounded capability check through existing infrastructure
  tooling, with target verification and a credential-free private result ledger.
- Good, because local attack cases expose parser and binding mistakes before any deployment.
- Bad, because automated erasure remains unavailable and the existing maintainer procedure remains.
- Bad, because the reference added a development dependency and a stricter encoding contract to
  maintain. Renovate owned `jose` until the reference and `jose` were removed after ADR 0082.
- Neutral, because the provider already supports incoming webhook functions, but a future workflow
  definition needs an API supplement unless the provider gains that resource.

### Confirmation

- Repeat the explicitly opted-in `hog-probe` command from the trial report. Check selected backend,
  managed test project, live project/environment, organization and token before invoking code.
- Run `pnpm run verify:scripts`. Probe tests cover wrong targets, opt-in, private ledgers and
  existing-ledger preservation. The removed local reference tests covered fixed vectors, signature
  and subject substitution, duplicate fields, caller targets, encodings, algorithm confusion and
  time; restore them from commit `15760c9f` to rerun them.
- Keep public ingress, concurrent admission, merge races, unattended completion, same-ID reuse
  and a second erasure marked `NOT_RUN` until actual hosted evidence exists.
- Require the applicable retention and enrollment-loss cases in the trial report before accepting
  an asymmetric, per-installation HMAC or hash-derived bearer implementation.
- Repeat the ownership PoC only with explicit TEST opt-in, after restoring its removed scripts as
  the [research log](../telemetry-erasure-research.md#ownership-proof-of-concept) describes. Its successful
  authentication checks and accepted concurrent replays are not evidence of safe deletion or
  durable job admission.
- Keep the Java CLI, state schema, heartbeat fields and OpenTofu deployed resources unchanged.
  SPEC Sections 17.10 and 19.6 continue to govern the shipped behavior.

## Pros and Cons of the Options

### Supported native ES256 and trusted job state

PostHog verifies a key-bound request and durably processes its single authorized target.

- Good, because it meets the intended hosting and ownership boundary.
- Bad, because no supported asymmetric verifier was found, and atomic admission is unproven.

### Another native asymmetric algorithm

A protocol version fixes another maintained asymmetric algorithm and preserves the same gates.

- Good, because ES256 itself is not essential to the security model.
- Bad, because the inspected runtime exposes no supported RSA or Ed25519 verifier either.

### Public event, shared secret or client-side verification

The handler trusts event membership, a distributed password, or the sender's validation result.

- Bad, because arbitrary capture clients can manufacture events and client-side assertions.
- Bad, because a shared password cannot limit each installation to its own target.

### Per-installation HMAC with trusted enrollment

Each installation holds a different secret; the service verifies an authenticated request scoped
to its installation ID, deployment, action, expiry and operation ID.

- Good, because compromising one installation secret does not authorize another installation.
- Good, because hosted Hog exposes HMAC helpers.
- Good, because public native issuance and verification now pass without a binding registry.
- Bad, because master-key lifecycle adds operational responsibility, and deletion scope and atomic
  operation state remain unproven. HMAC availability alone does not establish a usable native service.

### Local bearer secret with a hash-derived public ID

The service hashes the presented secret and limits erasure to the resulting installation ID.

- Good, because verification needs neither an asymmetric verifier nor stored enrollment data.
- Good, because routine analytics exposes only the public ID and retention cannot expire the binding.
- Bad, because request-log disclosure grants ongoing erasure authority for that installation.
- Bad, because safe provider deletion and durable job processing still require hosted evidence.

### Handwritten verification in Hog

Repository-owned cryptographic code implements asymmetric verification inside the language.

- Bad, because the task requires a maintained verifier and forbids handwritten cryptographic math.
- Bad, because this creates a security maintenance burden before addressing durable state.

### External verifier and worker

A separate service validates signatures, owns atomic operation state and schedules provider calls.

- Good, because those are explicit components with testable responsibilities.
- Bad, because separately hosted operation violates the requested architecture. Nothing is deployed.

### Stop at the gate with reusable evidence

Keep the current client and infrastructure while checking capabilities and testing the local protocol.

- Good, because a later trial starts with reproducible evidence and precise remaining gates.
- Bad, because it delivers no automated erasure service.

## More Information

- [Executed ownership proofs of concept](../telemetry-erasure-research.md#ownership-proof-options)
- [Deletion and native-state investigation](../telemetry-erasure-research.md#posthog-deletion-api-behavior)
- [PostHog event retention](https://posthog.com/docs/data/events-retention)
- [Trial report and resume commands](../telemetry-erasure-research.md#hog-runtime-probe)
- [Pinned source research](../telemetry-erasure-research.md#posthog-native-execution)
- [ADR 0079](0079-installation-telemetry.md)
- [ADR 0080](0080-posthog-infrastructure-as-code.md)
