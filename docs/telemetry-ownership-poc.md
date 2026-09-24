# PostHog ownership proofs of concept

Verdict on 2026-09-22: choose server-derived HMAC credentials for the ownership component.
Both HMAC and hash-derived bearer authentication passed real public webhook tests in the managed
EU TEST project. Automated erasure remains blocked on safe deletion scope and unverified job
processing. Authentication success does not grant either prototype deletion authority.

The [merge mitigation follow-up](telemetry-merge-mitigations.md) distinguishes blind guessing
from attacks that already know another installation's analytics ID. The merge finding does not
establish that random public requests can erase everyone. Keeping analytics IDs private inside
native PostHog ingestion is another candidate to test before requiring a new provider deletion API.

The later [alternatives comparison](telemetry-erasure-alternatives.md) tested merge filtering and
private-token ingestion. It keeps HMAC for ownership and recommends a merge filter only as
additional protection. Neither hiding identifiers nor hiding the capture token establishes a
strict installation-only deletion boundary.

## Comparison and decision

| Property | Server-derived HMAC | Hash-derived bearer |
| --- | --- | --- |
| Public hosted verification | Passed | Passed |
| First binding event required | No | No |
| Binding database | No | No |
| Periodic key uploads | No | No |
| Secret sent on each erasure | No | Yes |
| Additional operational secret | Server master key | None |
| Issuance | Server assigns a fresh ID and key once | Client generates its secret locally |
| Lost issuance response | Start fresh before any reporting uses the unanswered ID | No issuance request |
| One-year event expiry | Irrelevant to verification | Irrelevant to verification |
| Exact replay rejected by authentication alone | No | No |

HMAC is the preferred path because its extra machinery is one small native issuance function and
a protected master key. It needs no database, external backend, stored binding event or recurring
registration. Its authenticated request exposes a short-lived proof for one operation. Copying
that proof does not disclose the key needed to authorize another operation. The bearer fallback
is adequate against random guessing but exposes ongoing authority when an erasure request leaks.

This choice requires retaining the server master key independently of analytics. Losing or replacing
it without preserving the old version invalidates existing installations. Backups, versioned key
rotation and client persistence still need implementation before deployment. The master stays in
PostHog secret configuration; it is never shipped to installations. A master-key compromise affects
all derived credentials, while a single installation-key compromise affects that installation.

## What ran

The runner checked the selected OpenTofu backend and managed TEST role against live organization,
project, environment and capture-token metadata before creating resources. It checked the account's
existing default data-pipeline allowance. It changed no billing plan or feature setting. This was
a bounded authentication experiment, not a capacity or pricing test.

Two runs created three temporary source functions each: issuer, HMAC verifier and bearer verifier.
They contained no `fetch`, capture call or provider deletion credential. Public requests reached
`webhooks.eu.posthog.com`. The final run recorded 49 successful assertions, including management
and cleanup checks and two local simulated-age cases. It is not a count of 49 end-to-end deletion
tests. The local reference separately covers malformed input, target binding and retention-independent
credential reuse in the ordinary script test suite.

The final authentication run lasted from 15:01:11 to 15:01:16 UTC on 2026-09-22. The
maintainer's private run ledger records the actual TEST project binding. Production was not
invoked.

The final live run demonstrated:

- Server issuance returns a fresh ID and a key matching Node's standard HMAC implementation.
- An unanswered issuance can be abandoned and retried with a fresh ID before reporting starts.
  Supplying an existing ID to issuance is rejected.
- Both native verifiers derive the expected target, with no enrollment record or analytics history.
- Public IDs alone, duplicate JSON members, extra targets, wrong audience, expired/future requests,
  excess lifetime, malformed data, oversized bodies and GET requests are rejected.
- Altering the HMAC target or operation invalidates the request.
- A well-formed random bearer secret derives its own unrelated ID. It does not authenticate as the
  victim. The prototype performs no data lookup or deletion for that ID.
- Restoring a serialized credential works without repeating enrollment. Verification after 400 days
  passed with a simulated local clock. No claim is made that hosted storage was aged by 400 days.
- Repeated and concurrent valid proofs are accepted by these stateless verifiers. This deliberately
  demonstrates the missing job-admission layer rather than claiming replay protection.

Management responses omitted the configured master key. Immediate function-log reads returned no
entries, so they prove no useful retention or redaction guarantee. The code does not print requests
or secrets. Provider request traces, issuance responses and serialized execution state still need
their own exposure assessment. The maintainer's threat model accepts a bearer fallback if stronger
verification becomes disproportionately complex; this experiment did not need that fallback.

The management test-invocation API rejected `source_webhook` with `Invalid function type`. Compiling
the same code as an unsaved destination succeeded, but its result field was null. Neither observation
was counted as proof of public verification; the saved public source endpoints supplied that evidence.

Direct function DELETE returned 403 for personal API key access. Cleanup used the supported update
path: disable, then set `deleted=true`. All six run-owned functions subsequently returned 404 and
were absent from the source-function listing. No captured events or erasure jobs were created.
PostHog's internal soft-deleted configuration and logs are not claimed physically purged.

## Harmless native workflow proof

A separate workflow contained only a schedule trigger, a five-second delay and an exit. Readback
confirmed `schedules: []` and `billable_action_types: []` before activation. No recurring schedule,
person, analytics capture, message or deletion action was created.

Three concurrent `POST hog_flows/<id>/run/` requests with `{"variables":{}}` and one
`Idempotency-Key` produced one queued invocation and two 409 responses for the in-progress key.
A retry after completion returned the same invocation ID. PostHog reported `status: succeeded`,
an empty `person_id`, and a finished timestamp after the delay. Local calls only observed status;
they did not advance the workflow.

The first attempt stopped too early on an initial results-API 404 and was cancelled and removed.
The second treated 404 as not yet observable within a bounded poll. Its result appeared after
about twelve seconds and reported completion at 15:09:01 UTC, as recorded in the maintainer's
private run ledger. Both run-owned workflows were deactivated,
cancellation was requested, and removal was verified with GET 404. Cancellation alone does not
prove every parked job has stopped; the second run's terminal result proves that run completed.

To repeat, use the same managed TEST binding checks and create a uniquely named draft flow with
`exit_condition: exit_only_at_end`. Its actions are `trigger` with config `{"type":"schedule"}`,
`delay` with config `{"delay_duration":"5s"}`, and `exit` with empty config. Connect them with
`continue` edges in that order, and declare no variables. Verify the empty schedules and billable
actions, then activate with PATCH and run the duplicate group above. Read
`hog_flows/<id>/invocation_results/<invocation_id>/` until its explicit status is terminal within
a bounded deadline. Cleanup sets status to `draft`, calls `invocations/cancel/` with `{"all":true}`,
deletes only the owned flow and verifies GET 404. All routes use the checked TEST project prefix.

This proves ordinary duplicate handling and native delay/continuation through the management API.
It does not prove cache-loss recovery, body-conflict rejection, or atomic public admission and
deletion scheduling. Workflow variables held no secret. The source's 24-hour idempotency expiry
was not waited out. See the [pinned lifecycle sources](telemetry-native-erasure-lifecycle-research.md).

## Prototype protocol

This is a development protocol, not an application wire contract. Existing installation IDs and
Java persistence are unchanged. The source is
[`erasure-ownership-poc.ts`](../scripts/erasure-ownership-poc.ts).

The HMAC issuer uses a fresh UUIDv4 and prefixes it with `h1-k1-`. Master and installation secrets
are 32-byte values represented as 64 lowercase hexadecimal characters. HMAC keys in this prototype
use the UTF-8 bytes of that hexadecimal representation consistently on both runtimes.

```text
installationKey = HMAC-SHA256(masterHex,
  "symphony-trello/owner/v1|" + audience + "|" + installationId)

unsigned = "h1|erase|" + audience + "|" + installationId
  + "|" + operationId + "|" + issuedAt + "|" + expiresAt

payload = unsigned + "|" + HMAC-SHA256(installationKeyHex, unsigned)
```

The bearer ID is `b1-` followed by the full lowercase SHA-256 digest of
`symphony-trello/bearer/v1|<audience>|<secretHex>`. Its payload has the same fields except that
the version is `b1`, the subject field carries the secret, and there is no appended MAC.

Transport is the exact compact JSON object `{"payload":"<payload>"}` over HTTPS POST. Unknown
members, duplicate members, escaping and extra whitespace fail the raw-body equality check. The
fixed fields permit no delimiters inside values. Operation IDs are UUIDv4; timestamps are ten-digit
Unix seconds, with a maximum 900-second lifetime and 60-second clock tolerance. The prototype
limits bodies to 1,200 characters. Native HMAC verification compares keyed hashes of both MACs to
avoid exposing the expected MAC prefix through ordinary string equality. This is not a claim
about the runtime's constant-time behavior.

The client must durably save an issued credential before its first heartbeat. If it loses the
response before saving, it starts a new issuance and abandons an ID that has never carried its
analytics. Once saved, that credential is reused without re-enrollment. This deliberate bootstrap
rule avoids adding a recovery database. It does not migrate existing random-UUID installations.

## Remaining blocker and next path

[Lifecycle research](telemetry-native-erasure-lifecycle-research.md) found that the public bulk-delete
API resolves IDs to whole persons and deletes by person UUID. Identity merges therefore widen
the deletion scope. There is no demonstrated atomic expected-ID check or customer-accessible
exact-ID, fixed-cutoff deletion API. A preflight ID-list check has a race with later merges.
An alias-dropping transformation also fails open on execution errors in the inspected source.
Changing HMAC to bearer does not repair any of those properties.

There is a better native admission candidate than the earlier metrics-only duplicate observer:
schedule-triggered workflow runs accept an idempotency header with a 24-hour reservation. The
harmless test demonstrated ordinary concurrency and continuation. Short-lived
requests can bound replay retention without an annual ledger, but cache loss, response loss,
public admission, deletion completion and same-ID reuse still need proof.

The next decisive requirement is a supported exact-installation deletion operation, or atomic
provider isolation against merges. Until that exists, keep the existing maintainer erasure flow.
No extra hosted tool is proposed, and no production verifier is activated.

## Reproduce

Offline reference tests:

```bash
node --test scripts/erasure-ownership-poc.test.ts
```

Explicit, bounded live TEST trial using existing protected credentials and selected state:

```bash
SYMPHONY_TRELLO_OWNERSHIP_POC=1 node scripts/erasure-ownership-live-poc.mjs
```

The runner writes a credential-free JSON ledger to its default private directory. The ledger
records the run, resource IDs, assertion results and cleanup readbacks. Set
`SYMPHONY_TRELLO_OWNERSHIP_POC_DIR` to use another private directory. Both functions and
requests are authorization-only. Ordinary CI has no live opt-in.

The proof work itself changed only development proofs, SPEC's evidence gate and ADR 0081. It
added no dependency, application behavior, production resource, release artifact or
companion-repository requirement. [ADR 0082](adr/0082-authenticated-erasure-with-reporting-periods.md)
later added the application behavior and deployment resources described in the
[implementation and activation gate](telemetry-erasure-implementation.md).
