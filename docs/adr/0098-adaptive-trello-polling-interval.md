---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #58](https://github.com/martin-francois/symphony-trello/issues/58)"
  - "[GitHub issue #403](https://github.com/martin-francois/symphony-trello/issues/403)"
  - "[GitHub PR #797](https://github.com/martin-francois/symphony-trello/pull/797)"
  - "[ADR 0042](0042-application-clock-boundaries.md)"
  - "[ADR 0051](0051-orchestrator-operation-lock-and-state-monitor.md)"
  - "[ADR 0052](0052-align-polling-defaults-with-upstream.md)"
  - "[ADR 0053](0053-sleep-based-waits-kept-as-polling-boundaries.md)"
informed: [Future maintainers, Contributors, Operators]
---

# Adapt The Trello Poll Interval To Rate Limits

## Context and Problem Statement

Each worker process polls one Trello board every `polling.interval_ms`. Generated workflows use
5000 ms ([ADR 0052](0052-align-polling-defaults-with-upstream.md)). Trello allows 100 requests per
10 seconds per token and 300 per 10 seconds per API key, so several boards that share a token can
run into `429` responses. Before this change a worker kept polling at the configured interval no
matter how often Trello answered `429`, and every worker polled on an exact grid, so workers that
started together stayed in step.

[GitHub issue #58](https://github.com/martin-francois/symphony-trello/issues/58) asks for jitter, a
slower effective interval after repeated `429` responses, a 30000 ms cap on that slowdown, gradual
recovery after a quiet period, respect for `Retry-After`, and status that shows the configured and
the effective interval.

The retry library question was settled first.
[GitHub PR #797](https://github.com/martin-francois/symphony-trello/pull/797) (ADR 0097 on that
branch, for [GitHub issue #403](https://github.com/martin-francois/symphony-trello/issues/403))
keeps the hand-rolled retry loop in `TrelloClient` and says this issue should build a small
repository-owned interval policy, not use Failsafe, Resilience4j, or SmallRye Fault Tolerance. It
left two questions for this issue:

1. `TrelloClient` already sleeps out `Retry-After` inside a read, up to `tracker.max_api_retries`
   times. How does the poll schedule avoid waiting out the same `Retry-After` a second time?
2. `Retry-After` is used without an upper bound. Does the 30000 ms cap also bound it?

## Decision Drivers

- Keep the retry contract that [GitHub PR #797](https://github.com/martin-francois/symphony-trello/pull/797) pins in `TrelloClientChaosTest` and `TrelloClientTest`:
  reads retry `429` and transport errors, writes never retry.
- Count every `429`, including one a read retried successfully, one from a write, and one from a
  Codex tool call that shares the client. All of them use the same token budget.
- Never wait out a `Retry-After` twice, and never make the delay shorter than the configured
  interval or than a `Retry-After` that is still ahead.
- Keep every Trello-caused wait bounded. The poll tick holds the orchestrator operation lock
  ([ADR 0051](0051-orchestrator-operation-lock-and-state-monitor.md)), so a long sleep inside a
  read also delays `stop()`.
- Keep `polling.interval_ms` the only user-facing setting. The issue fixes the 30000 ms cap.
- Read time through the application clock ([ADR 0042](0042-application-clock-boundaries.md)) and
  take the jitter random source as a constructor argument, so tests fix both.

## Considered Options

- Client-recorded rate-limit pressure, drained by a tick-level interval policy
- Slow down only when a tick fails with `trello_api_rate_limited`
- Move every `429` wait out of the read into the tick schedule
- Proactive local token buckets for Trello's documented limits

## Decision Outcome

Chosen option: "Client-recorded rate-limit pressure, drained by a tick-level interval policy",
because it is the only option that sees every `429` the process receives while keeping the read
retry contract unchanged.

How it works:

- `TrelloClient` records each `429` response in a `RateLimitPressureRecorder`: the time it arrived
  and, when the response has a `Retry-After` value, the instant that wait ends. `Retry-After` is
  read in both forms HTTP allows, seconds and an HTTP date. Before this change an HTTP date fell
  back to the exponential backoff. The value
  the client already parses is reused; nothing parses the header twice. `TrackerClient`
  exposes the batch through `drainRateLimitPressure()`, which returns nothing for trackers that do
  not record pressure.
- At the end of every tick, `SymphonyOrchestrator.finishTickAndScheduleNext` drains the batch and
  asks `AdaptivePollInterval` for the next delay.
- A tick with at least one `429` doubles the effective interval, up to 30000 ms. The effective
  interval is never shorter than `polling.interval_ms`. A configured interval of 30000 ms or more is
  therefore never slowed.
- After 60 seconds without a `429`, the effective interval halves. The check runs at the end of
  each tick, so a step lands on the first tick end after the 60 seconds. Every further quiet 60 seconds
  halves it again until it is back at `polling.interval_ms`. A `429` during recovery doubles it
  again and restarts the 60 seconds. From the cap, a 5000 ms workflow is back to 5000 ms about three
  minutes after the last `429`.
- The delay before the next tick is the larger of the effective interval and the part of a
  `Retry-After` wait that is still ahead. Jitter then adds between 0 and 10 percent of that delay.
- A manual refresh (`POST /api/v1/refresh`) still runs the next tick at once. It is an explicit
  operator action, and the read inside it still honors `Retry-After` if Trello answers `429`.
- `/api/v1/state` and the status page show the configured and the effective interval, a stable
  slowdown reason (`trello_rate_limited`), and when Trello last answered `429`. A log line names
  each change of the effective interval.

Answer to question 1: the read keeps its `Retry-After` wait, and the poll schedule only slows
later ticks. The recorder stores an absolute "not before" instant, not a duration. When the read
already slept until that instant, it has passed by the time the tick ends, so the schedule adds
nothing for it. When the read did not wait, because it was the last attempt or a write, the next
tick waits for the rest of it.

Answer to question 2: yes. `RateLimitPressure.MAX_WAIT` (30000 ms) bounds the slowed interval and
every `Retry-After` value, both the sleep inside a read and the wait before the next tick. Trello's
windows last 10 seconds, so a longer `Retry-After` most likely comes from a proxy in between. If
Trello really wants a longer pause, the next request gets another `429` and waits again, which
costs one request per 30 seconds. An unbounded value would instead block the tick, and with it
`stop()`, for as long as the header says. This makes `SPEC.md` section 11.2's "bounded" backoff
true for `Retry-After` too.

### Consequences

- Good, because every `429` slows the worker, also one a retry recovered from or one a Codex tool
  call received.
- Good, because the retry contract from [GitHub PR #797](https://github.com/martin-francois/symphony-trello/pull/797) stays as it is, and its tests stay green
  unchanged.
- Good, because no Trello-caused wait exceeds 30 seconds, so shutdown and the next tick are never
  stuck behind a long `Retry-After`.
- Good, because jitter only lengthens delays: the configured interval and a pending `Retry-After`
  stay minimums, and workers that share a token drift apart instead of polling in step.
- Good, because operators see the slowdown in `/api/v1/state`, on the status page, and in the
  logs, next to the `polling.interval_ms` they configured.
- Bad, because the 60-second quiet period, the factor 2, and the 10 percent jitter are constants.
  Changing them needs a code change. They are not configurable on purpose: the issue asked for no
  new setting, and there is no evidence yet that operators need to tune them.
- Bad, because a `429` from a Codex tool call between two ticks does not move the tick that is
  already scheduled. It slows the tick after that. The read inside the tool call still honors
  `Retry-After`.
- Bad, because with jitter the wait at the cap can reach 33 seconds. The cap bounds the effective
  interval and each `Retry-After`; jitter comes on top so that workers at the cap do not line up.

### Confirmation

- `AdaptivePollIntervalTest` covers jitter bounds, doubling per rate-limited tick, the 30000 ms cap,
  a `Retry-After` still ahead, a `Retry-After` already waited out, jitter on a `Retry-After`,
  stepwise recovery, a `429` during recovery, a configured interval at the cap, and a reloaded
  interval.
- `RateLimitPressureRecorderTest.drainBetweenReadingAndPublishingAMergeCountsEveryResponseInExactlyOneBatch`
  pins the only new concurrency mechanism: a drain that runs between reading and publishing a
  merge must neither lose nor repeat a `429`. A non-atomic `get` plus `set` makes it fail.
- `TrelloClientRateLimitPressureTest` checks that retried reads and writes report pressure and that
  a `Retry-After` above 30 seconds is bounded. `TrelloClientTest.retryAfterHttpDateWaitsUntilThatDate`
  covers the HTTP-date form. `TrelloRateLimitPollingIntegrationTest` runs the real
  client against a fake Trello server that answers `429`.
- `SPEC.md` sections 5.3.2, 8.1, 11.2, 13.3, 14.2, and 17 describe the behavior.
- Revisit this decision if Trello starts sending `Retry-After` values above 30 seconds for real
  limits, or if operators need to tune the quiet period or the factor.

## Pros and Cons of the Options

### Client-recorded rate-limit pressure, drained by a tick-level interval policy

`TrelloClient` records each `429` and its `Retry-After` deadline in a thread-safe batch. The
orchestrator drains the batch once per tick and feeds it to a small policy object that owns the
effective interval, the recovery timer, and the jitter.

- Good, because it sees every `429` in the process, whether the read recovered, a write failed, or
  a tool call ran into it.
- Good, because the absolute deadline answers the double-wait question without a "waited" flag.
- Good, because the policy is plain Java with an injected clock and random source, so tests pin
  every step.
- Bad, because it adds a method to `TrackerClient` and a lock-free accumulator that needs its own
  concurrency test.

### Slow down only when a tick fails with `trello_api_rate_limited`

The tick catches the `trello_api_rate_limited` exception, reads a `Retry-After` value carried on it,
and slows down.

- Good, because it needs no new state in the client.
- Bad, because a read that recovered after one retry throws nothing, so the most common `429`
  would not slow the worker at all.
- Bad, because the tick catches many tracker failures per card, and tool calls never reach the
  tick, so many `429` responses would stay invisible.

### Move every `429` wait out of the read into the tick schedule

Reads stop retrying `429`. The tick fails, and the next tick waits for `Retry-After` or the
backoff.

- Good, because only one place would ever wait for Trello.
- Bad, because it changes the retry contract that [GitHub PR #797](https://github.com/martin-francois/symphony-trello/pull/797) just pinned and that `SPEC.md` section
  5.3.1 describes for `tracker.max_api_retries`.
- Bad, because reads inside Codex tool calls have no tick to defer to, so they would fail instead
  of waiting a few seconds.
- Bad, because a single `429` would throw away the whole tick's work instead of one request.

### Proactive local token buckets for Trello's documented limits

The client counts its own requests and waits before it reaches 100 per 10 seconds per token or 300
per 10 seconds per key.

- Good, because it would avoid many `429` responses before they happen. `SPEC.md` section 11.2
  recommends it.
- Bad, because each board runs in its own worker process, so a bucket in one process cannot see
  the requests of other workers that share the token. It would not prevent the multi-board case
  this issue is about.
- Bad, because it does not give the recovery behavior or the status the issue asks for, so the
  interval policy would still be needed.

## More Information

[GitHub PR #797](https://github.com/martin-francois/symphony-trello/pull/797) adds ADR 0097 (keep the hand-rolled Trello retry backoff). This ADR builds on it and
should merge after it. The code lives in `AdaptivePollInterval`, `RateLimitPressure`,
`RateLimitPressureRecorder`, and `SymphonyOrchestrator.finishTickAndScheduleNext`.
