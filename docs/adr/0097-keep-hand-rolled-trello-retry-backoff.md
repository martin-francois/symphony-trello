---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #403](https://github.com/martin-francois/symphony-trello/issues/403)"
  - "[GitHub issue #58](https://github.com/martin-francois/symphony-trello/issues/58)"
  - "[GitHub issue #378](https://github.com/martin-francois/symphony-trello/issues/378)"
  - "[ADR 0053](0053-sleep-based-waits-kept-as-polling-boundaries.md)"
  - "[Failsafe repository](https://github.com/failsafe-lib/failsafe)"
  - "[Resilience4j repository](https://github.com/resilience4j/resilience4j)"
  - "[SmallRye Fault Tolerance repository](https://github.com/smallrye/smallrye-fault-tolerance)"
informed: [Future maintainers, Contributors]
---

# Keep The Hand-Rolled Trello Retry Backoff

## Context and Problem Statement

[ADR 0053](0053-sleep-based-waits-kept-as-polling-boundaries.md) decided that the Trello retry wait
is an inherent poll: Trello sends no event when a rate-limit window or an outage ends. It left one
question open. Should the retry loop itself come from an established library instead of
repository-owned code?

`TrelloClient.requestBody` implements the retry contract from `SPEC.md` section 5.3.1
(`tracker.max_api_retries`, `tracker.api_retry_base_delay_ms`) and section 11.2 (backoff, jitter,
`Retry-After`, and no automatic retry of writes):

- Only reads (`GET`) retry. Writes never retry, because a lost response after Trello applied a
  write would duplicate the comment or move. This change adds that existing rule to section 11.2.
- A read retries after a transport `IOException` or an HTTP 429 response, up to
  `tracker.max_api_retries` extra attempts (default 3). Every other non-2xx status fails at once.
  [GitHub issue #403](https://github.com/martin-francois/symphony-trello/issues/403) mentions
  429/5xx, but the specification and the code retry 429 and transport errors only. This ADR does
  not change that.
- The wait before a retry is the `Retry-After` header when it is a non-negative number of seconds.
  Otherwise it is `base * 2^(attempt - 1)` plus a random jitter in `[0, base)`, with
  `base = tracker.api_retry_base_delay_ms` (default 1000 ms) and the exponent capped at 8.
- An interrupt during the request or the wait ends the call with `trello_api_request` and keeps the
  thread's interrupt flag set.

[GitHub issue #58](https://github.com/martin-francois/symphony-trello/issues/58) (adaptive polling
backoff and jitter) will follow this decision, so the choice also has to say what that issue
should build on.

## Decision Drivers

- Keep the current retry contract: an exact `Retry-After` wait, jitter only on the computed backoff,
  no retry for writes or for non-429 statuses, and a kept interrupt flag.
- Adopt a library only when it removes logic the repository would otherwise own, not when it only
  moves the same logic into builder calls.
- A new runtime dependency needs Renovate ownership, a row in
  [Dependency upgrade confidence](../testing/dependency-upgrade-confidence.md), the seven-day release
  age, and an upstream that still ships fixes.
- Unit tests build `TrelloClient` with `new TrelloClient(json)` and run without a CDI container. A
  choice must keep that working or pay for changing every such test.
- The choice should give
  [GitHub issue #58](https://github.com/martin-francois/symphony-trello/issues/58) a clear basis.

## Considered Options

- Keep the hand-rolled retry loop
- Failsafe (`dev.failsafe:failsafe` 3.3.2)
- Resilience4j Retry (`io.github.resilience4j:resilience4j-retry` 2.4.0)
- SmallRye Fault Tolerance through `quarkus-smallrye-fault-tolerance` (version 6.11.4 from the
  Quarkus 3.39.5 platform BOM)

## Decision Outcome

Chosen option: "Keep the hand-rolled retry loop", because no library removed the only logic with
real content, the delay function. The best candidate saved 4 lines of loop code. That is not worth
a runtime dependency. Each library also needed extra code or a test change to keep the current
contract, as the findings below show.

### Spike

Each option was implemented against the same `TrelloClient` on 2026-10-04. Every variant shared
one extraction: a single-attempt method that throws a retryable failure for HTTP 429 and transport
errors, plus the existing delay function (`Retry-After` or exponential backoff with jitter, 18
non-comment lines). Only the retry loop differed. Each variant ran the 51 `TrelloClientTest` cases,
the 9 `TrelloClientChaosTest` cases that existed then, and 6 spike contract cases: exact
`Retry-After` wait, 429 retries exhausted, HTTP 500 not retried, transport failure retried, interrupt
during the wait, and writes not retried.

| Option             | Loop lines | New runtime jars | Added jar bytes | Tests passing (of 66)                                   |
| ------------------ | ---------- | ---------------- | --------------- | ------------------------------------------------------- |
| Hand-rolled        | 24         | 0                | 0               | 66                                                      |
| Failsafe           | 20         | 1                | 143,998         | 66                                                      |
| Resilience4j Retry | 24         | 2                | 149,748         | 66                                                      |
| SmallRye FT        | 32         | 9                | 620,614         | 9 in plain JUnit; the 6 contract cases pass in Quarkus |

"Loop lines" counts non-blank, non-comment lines of the retry loop, its exception handling, and its
sleep helper where it has one. The current code has 54 such lines (45 in `requestBody`, which also
builds the `HttpRequest`, and 9 in `sleep`). The shared extraction, not a library, is what brings
the hand-rolled loop to 24.

Findings per library:

- Failsafe applies its jitter to every delay, including a delay from `withDelayFnOn`. With
  `withJitter(0.5)` and a 200 ms `Retry-After`, 11 of 20 measured waits were shorter than 200 ms
  (range 109 to 294 ms). The jitter therefore has to stay in repository code, and Failsafe only
  replaces the `for` loop and the sleep. Checked exceptions come back wrapped in
  `FailsafeException`, so the interrupt path needs unwrapping. The last release is 3.3.2 from June
  2023; the newest commits (December 2025) fix CI and a flaky test.
- Resilience4j passes the attempt number and the outcome to `intervalBiFunction`, so `Retry-After`
  fits. `executeCheckedSupplier` declares `throws Throwable`, which forces a catch-all. An
  interrupt during the wait rethrows the last failure, so the caller has to check the interrupt flag
  to tell an interrupt from exhausted retries. Its test seam for the wait,
  `RetryImpl.setSleepFunction`, is static and JVM-global, which conflicts with the parallel test
  rules in [Testing](../agents/testing.md). Version 2.4.0 is from March 2026 and the project is
  active.
- SmallRye Fault Tolerance needs no version of its own because the Quarkus BOM manages it. Its
  programmatic API (`TypedGuard`, `CustomBackoffStrategy`) is marked `@Experimental` in 6.11.4.
  `TypedGuard.create` looks up the CDI container (`CdiSpi` calls `CDI.current()`), so 57 of the 66
  plain JUnit tests failed with `NoClassDefFoundError: SpiAccess$Holder`. Two defaults change
  behavior unless overridden. The built-in backoffs add up to 200 ms of jitter in either direction.
  A 180 s maximum retry duration ends the call without another attempt once that much time has
  passed, even right after waiting out a long `Retry-After`. A custom backoff receives only the
  exception, not the attempt number, so the strategy has to count attempts itself. The extension also adds 9 jars and turns on
  MicroProfile Fault Tolerance annotation processing for the whole application.

### Guidance for adaptive polling

[GitHub issue #58](https://github.com/martin-francois/symphony-trello/issues/58) should not add a
retry or resilience library either, and should not reuse a retry policy for the poll interval:

- Its behavior is an effective poll interval per worker that grows on Trello rate-limit pressure,
  is capped at 30000 ms, recovers after a quiet period, and is visible in status. None of the three
  libraries models an interval with recovery. Their rate limiters hand out permits inside one JVM,
  while each board runs in its own worker process, so they cannot spread load across workers
  either.
- Build it as a small repository-owned policy next to the tick scheduling in
  `SymphonyOrchestrator.finishTickAndScheduleNext`. Read time through the application clock
  boundary from [ADR 0042](0042-application-clock-boundaries.md) and take the random source for
  jitter as a dependency, so tests can fix both.
- When the orchestrator needs Trello's `Retry-After` value, carry the value `TrelloClient` already
  parses (for example on the `trello_api_rate_limited` exception) instead of parsing the header a
  second time.
- `TrelloClient` already waits out `Retry-After` inside a read, up to `tracker.max_api_retries`
  times. The interval policy must not wait out the same `Retry-After` again at the next tick. Either
  the read keeps that wait and the policy only slows later ticks, or the wait moves into the tick
  scheduling and the read stops retrying 429 responses. That issue chooses one and records it.

### Consequences

- Good, because the retry contract stays in about 70 lines of plain Java in one class, with no new
  runtime dependency, Renovate rule, or upgrade-confidence row.
- Good, because none of these library behaviors reaches production: Failsafe jitter on
  `Retry-After` waits, the SmallRye CDI container requirement, and the SmallRye default jitter and
  maximum duration.
- Good, because [GitHub issue #58](https://github.com/martin-francois/symphony-trello/issues/58) has a stated basis and does not have to repeat
  this evaluation.
- Bad, because the repository keeps owning the loop, the interrupt handling, and the jitter
  formula. The new retry-classification and interrupt tests pin that code.
- Bad, because `Retry-After` is still used without an upper bound and only in its seconds form; an
  HTTP-date value falls back to exponential backoff. `SPEC.md` section 11.2 asks for "bounded
  exponential backoff with jitter", and an unbounded `Retry-After` wait does not meet the bounded
  part. This decision does not change either behavior, because it concerns where the loop comes
  from, not its limits. [GitHub issue #58](https://github.com/martin-francois/symphony-trello/issues/58) decides whether its 30000 ms cap also
  bounds `Retry-After`.

### Confirmation

- `pom.xml` declares none of `dev.failsafe:failsafe`, `io.github.resilience4j:*`, or
  `io.quarkus:quarkus-smallrye-fault-tolerance`.
- The comment on `TrelloClient.sleep` links this ADR.
- These tests pin the retry contract, and any later switch to a library must keep them green:
  `TrelloClientChaosTest.readRetriesOnlyRateLimitsAndTransportFailuresUpToConfiguredLimit`,
  `TrelloClientChaosTest.interruptDuringRetryBackoffStopsWaitingAndKeepsInterruptFlag`,
  `TrelloClientChaosTest.readRateLimitRetriesButWriteRateLimitDoesNotRetry`,
  `TrelloClientTest.nonNegativeRetryAfterRemainsAuthoritative`, and
  `TrelloClientTest.negativeRetryAfterFallsBackToExponentialDelay`.
- Revisit this decision when the Trello client needs a circuit breaker, a retry budget shared
  across requests, or asynchronous retries, which are features these libraries do provide. Also
  revisit it when the SmallRye programmatic API leaves `@Experimental` and works in unit tests
  without a CDI container.

## Pros and Cons of the Options

### Keep the hand-rolled retry loop

`TrelloClient.requestBody` keeps its `for` loop over attempts. It sleeps for the `Retry-After` value
or the exponential backoff with jitter, and it restores the interrupt flag when the wait is
interrupted.

- Good, because the code states the contract directly: which outcomes retry, how long to wait, and
  what an interrupt does.
- Good, because the plain unit tests and the fake Trello server exercise it without a container.
- Neutral, because the loop and its sleep helper are 54 lines today and 24 after the
  single-attempt extraction the spike used. That extraction is an optional cleanup, independent of this decision.
- Bad, because a future feature such as a circuit breaker would also have to be written here.

### Failsafe

Failsafe builds a `RetryPolicy` with `handle`, `withMaxRetries`, and `withDelayFn`, and runs the
single attempt through `Failsafe.with(policy).get(...)`.

- Good, because it has no transitive dependencies and the shortest loop in the spike (20 lines).
- Bad, because its jitter also changes `Retry-After` waits, so the delay function stays
  repository code and the library replaces only the loop and the sleep.
- Bad, because it wraps checked exceptions in `FailsafeException`, which adds unwrapping for the
  interrupt path.
- Bad, because the last release is from June 2023, so a defect found now may never get a fixed
  release.

### Resilience4j Retry

Resilience4j builds a `RetryConfig` with `maxAttempts`, `retryExceptions`, and an
`intervalBiFunction` that returns the delay, and runs the attempt through
`Retry.executeCheckedSupplier`.

- Good, because the interval function receives the attempt number and the outcome, which fits
  `Retry-After`. `IntervalFunction.ofExponentialRandomBackoff` could replace the 3-line exponential
  formula, with symmetric instead of additive jitter.
- Good, because the project is active and released in March 2026.
- Bad, because the loop is as long as the hand-rolled one (24 lines) and adds two jars.
- Bad, because `throws Throwable` forces a catch-all, and an interrupt during the wait looks like
  exhausted retries unless the caller checks the interrupt flag.
- Bad, because its sleep test seam is a JVM-global static.

### SmallRye Fault Tolerance

The Quarkus extension provides a `TypedGuard` built with `withRetry()`, `maxRetries`,
`whenException`, and a `CustomBackoffStrategy` that returns the delay; the attempt runs through
`guard.call(...)`.

- Good, because the Quarkus platform BOM manages its version, so it adds no version to track.
- Good, because it is the fault-tolerance implementation Quarkus documents.
- Bad, because the programmatic API is `@Experimental` and needs a running CDI container; the
  existing plain unit tests fail until each is moved to `@QuarkusTest` or a test-only standalone
  implementation is added.
- Bad, because its defaults (200 ms jitter on the built-in backoffs, 180 s maximum duration)
  change the contract unless overridden, and its custom backoff does not receive the attempt
  number.
- Bad, because it has the longest loop in the spike (32 lines) and adds 9 jars (620,614 bytes).

## More Information

The spike kept every variant's code outside the repository; this ADR records the measurements.
To repeat it, replace the loop in `TrelloClient.requestBody` with each library's retry call, keep
the delay function, add the dependency, and run `TrelloClientTest` and `TrelloClientChaosTest`.
Run the SmallRye variant a second time with the test class annotated `@QuarkusTest` to separate the
container requirement from the retry behavior.
