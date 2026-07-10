---
status: accepted
date: 2026-07-10
decision-makers: [François Martin, Codex]
consulted:
  - SPEC.md
  - README.md
  - "[GitHub issue #536](https://github.com/martin-francois/symphony-trello/issues/536)"
  - "[ADR 0017](0017-workflow-configured-in-progress-pickup-list.md)"
  - "[ADR 0019](0019-trello-workpad-comment.md)"
  - "[ADR 0051](0051-orchestrator-operation-lock-and-state-monitor.md)"
informed: [Future maintainers, Operators]
---

# Pause Dispatch On Structured Codex Usage Limits

## Context and Problem Statement

When Codex is usage-limited, a fast failed attempt can release one Trello card from the configured
in-progress list and the next polling tick can immediately pick up another eligible card. A queue
then appears busy while every card encounters the same account-wide condition. Normal per-card
backoff does not prevent that board-wide churn.

Codex app-server reports a typed `usageLimitExceeded` error and can separately report rate-limit
window reset times. How should Symphony use that signal without mistaking unrelated model,
configuration, or transport failures for an account-wide limit?

## Decision Drivers

* Stop new pickup moves before another eligible card churns through `In Progress`.
* Use a structured Codex protocol signal instead of parsing localized or changing message text.
* Keep already-running workers alive.
* Preserve normal retry semantics for unrelated failures.
* Make the wait visible on Trello and through the existing status surfaces.
* Avoid exposing raw account, provider, protocol, or configured-command details in the new state.
* Keep status reads independent from Trello network latency.

## Considered Options

* Workflow-wide in-memory pause from the structured Codex usage-limit category.
* Detect usage limits by matching error-message text.
* Apply only per-card backoff.
* Pause dispatch for every fast agent failure, including invalid models and bad requests.

## Decision Outcome

Chosen option: "Workflow-wide in-memory pause from the structured Codex usage-limit category".

The app-server client classifies only the exact structured `codexErrorInfo` value
`usageLimitExceeded`. It carries a clean error message and an optional retry time to the
orchestrator. A message that merely mentions usage, or another structured error such as
`badRequest`, keeps the existing generic failure path.

Before the failed worker's Trello exit handling, the orchestrator installs a process-local dispatch
pause. Candidate dispatch and retry timers stop before Trello candidate or card-state I/O while the
pause is active. Existing workers are not cancelled. Existing retry entries are deferred without
incrementing their attempt, and later usage-limit reports can extend but never shorten the pause.
The retry time is the latest future reset among exhausted Codex rate-limit windows; when no valid
reset is available, `agent.max_retry_backoff_ms` supplies the wait. A non-positive configured value
uses a one-second safety floor so failed or unavailable probes cannot create a hot loop. Symphony
merges available primary and secondary window values from Codex's sparse rolling rate-limit updates;
nullable unavailable metadata does not erase the most recent observed value. The client atomically
shares that merge across concurrent app-server sessions launched with the same configured
`codex.command`, but commits an update only after the orchestrator accepts its worker identity as
current. Separate command scopes prevent a workflow reload from combining potentially different
Codex accounts. Available non-null top-level fields are retained generically so new Codex
metadata does not require a Symphony schema change. A null, omitted, or non-object snapshot update
publishes the prior canonical value; before any valid snapshot it remains observable as canonical
null and cannot fabricate runtime state.

A valid `codex.command` change is a dispatch-scope boundary. The prior command's pause and timer no
longer gate the new command; late rate-limit events from still-current prior-command workers stay in
their launch command's snapshot, while ignored worker events, typed usage results, or stale callbacks
cannot mutate the new command's pause. Normal card lifecycle may
create an ordinary retry under the new command when the physical tracker target is unchanged; an
affected usage retry becomes immediately eligible, while unrelated retries regain their natural
deadlines. Invalid reloads preserve the active scope. A physical target changes only when tracker
kind, transport-equivalent endpoint, or resolved board changes—not for credential rotation or a
selector resolving to the same board. Endpoint scheme/host case, trailing DNS dot, default port,
percent-escape hex case, and the Trello client's trailing-slash removal from the end of the whole raw
endpoint before URI parsing are equivalent. Slashes before a query/fragment, raw dot segments, other
URI component changes, and absent versus explicit-empty components are not equivalent.
Such a change never transplants card attempts:
old retries retire, in-flight workers keep their launch config, and workpad cleanup retains the old
target and owning config. With the command unchanged, the account pause remains but old card/probe
ownership detaches so the deadline probes the new target. A new section cancels old cleanup only when
it addresses that same physical target.

At the deadline Symphony lets one card recheck Codex. It prefers the oldest due usage-limit retry,
then the oldest due deferred retry, and otherwise fetches and dispatches exactly one eligible
candidate as a controlled probe. A later unrelated retry keeps its own backoff deadline. Probe
ownership is bound to both the card and worker identity. A repeated typed limit extends the pause. A
completed or non-usage failure from that bound worker clears it and releases other deferred retries.
If the probe never starts or reconciliation cancels it before a result, Symphony re-arms the pause
and retains an authoritative usage retry. A missing, terminal, inactive, or out-of-scope probe is
retired and ownership transfers without clearing the pause. When there is no eligible probe, the
pause waits another fallback interval or until the next retained retry is due. Stale worker results
remain subject to the existing worker-identity check and cannot install or extend a pause.

When Trello comment writes are permitted, Symphony maintains a bounded managed section inside the
authoritative `## Codex Workpad` comment. It shows the clean Codex message, next attempt time, and
rechecking state. The agent workpad tool rejects either managed marker in its raw proposed text,
before cleanup-note stripping, normalization, or Trello I/O, so agents cannot create, replace, or
remove Symphony's section. Updating that section scans every fetched workpad duplicate before any
mutation and reuses the existing full-window safety rules. Unbalanced or nested markers and multiple
balanced sections in one workpad are malformed; sections in multiple workpads are ambiguous. Both
states fail closed without changing or deleting comments. If exactly one older duplicate owns the
section, destructive consolidation copies it to the authoritative workpad before deleting its former
owner. Disabled or failed deletion reports failure instead of claiming safe ownership. A bounded
per-card lock serializes the complete agent and orchestrator workpad read-modify-write sequence, and
repeated state changes keep a single duplicate-workpad cleanup notice. A workpad write failure is
visible in logs but does not defeat the dispatch pause.

The runtime snapshot and status page expose only the stable code `CODEX_USAGE_LIMIT`, first
detection time, and current deadline. Typed usage-limit event summaries keep only the clean error
message before they can become a running row's `last_message` or a card's recent event; raw account
and provider details are not exposed through those APIs. The pause and its timers are deliberately
not persisted.
Graceful stop and normal pause clearing remove every observed managed workpad section or report
failure so cleanup ownership retries. Failed cleanup ownership is capped, expires, and retries with
delay instead of generating unbounded per-poll I/O. After a crash or restart, the prompt refresh
exposes an older workpad and Symphony removes a stale managed section before dispatching that card.
The section also labels itself process-local so an operator can recognize staleness before cleanup
succeeds.

### Consequences

* Good, because one account-wide Codex limit no longer cycles every eligible Trello card through
  the pickup list.
* Good, because localized message wording and invalid-model failures cannot trigger the circuit
  breaker.
* Good, because humans can see the reason and retry time from the Trello workpad or status page.
* Good, because the operation lock installs the pause before subsequent Trello I/O while status
  readers still take only the short state monitor.
* Bad, because restarting Symphony forgets the pause and may allow one new probe before detecting
  the same limit again; stale workpad cleanup is opportunistic when the card next becomes eligible.
* Bad, because workpad visibility depends on configured Trello comment-write permissions.
* Neutral, because generic failures, backoff, and already-running workers retain their prior
  behavior.

### Confirmation

Unit tests must cover typed classification, malformed reset data, generic-message compatibility,
pause extension, deferred retries, failed and cancelled probes, stale and late workers, workpad
cleanup policy and ownership races, and status serialization. The deterministic fake app-server live
scenario must show that a second priority card is not picked up after the first reports the typed
limit. A real account limit is observed only when it occurs naturally; verification must not
deliberately consume quota or expose private account payloads.

## Pros and Cons of the Options

### Workflow-Wide In-Memory Pause From The Structured Category

* Good, because it matches the account-wide scope of the failure.
* Good, because it has an exact protocol boundary and a bounded fallback wait.
* Bad, because it adds a small orchestrator state machine and timer coordination.

### Detect Usage Limits By Matching Error-Message Text

* Good, because it could work with old clients that omit typed error information.
* Bad, because messages can be localized, reworded, or mention usage in unrelated contexts.
* Bad, because false positives would stop all dispatch.

### Apply Only Per-Card Backoff

* Good, because this is the existing simple retry model.
* Bad, because other eligible cards still churn through the visible pickup list.

### Pause Dispatch For Every Fast Agent Failure

* Good, because it also suppresses churn from an invalid configured model.
* Bad, because unrelated card-local or configuration failures are not evidence of an account-wide
  outage and need different recovery and operator guidance.

## More Information

This decision refines ADR 0017 only while a typed Codex usage limit is active: failed pickup cards
are still released from `In Progress`, but new pickup moves wait behind the workflow-wide pause. It
extends ADR 0019 with one Symphony-managed section rather than a second progress comment. It keeps
ADR 0051's lock split: long operations and Trello I/O use the operation lock, while snapshots copy
pause state under the short monitor.
