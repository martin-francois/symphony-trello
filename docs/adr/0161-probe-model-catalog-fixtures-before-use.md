---
status: accepted
date: 2026-11-02
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #1227](https://github.com/martin-francois/symphony-trello/issues/1227)"
  - "[Pull request #1234](https://github.com/martin-francois/symphony-trello/pull/1234)"
  - CodexModelAppServerFixture.java
informed: [Future maintainers, Contributors]
---

# Probe model-catalog fixtures before use

## Context and Problem Statement

Parallel model-catalog tests write executable shell fixtures and immediately start them. On Linux,
execution fails with ETXTBSY while another forked process retains a writable descriptor for that
file. The resolver correctly returns fallback defaults when launch fails, so the test reports a
catalog mismatch without identifying the launch failure.

A controlled run with Temurin 25.0.4+7, two CPUs, and 1,000 repetitions reproduced that launch error.
The fixture must finish executable preparation before a test uses it.

## Decision Drivers

* Preserve real process-boundary catalog tests.
* Keep fixture preparation separate from production launch behavior.
* Bound preparation and terminate any probe that times out or is interrupted.
* Retry only the observed transient executable-busy condition.

## Considered Options

* Probe the fixture with a dedicated no-op environment variable before returning it.
* Retry catalog requests in production or rerun failed tests.
* Replace the process fixture with an in-memory catalog fake.

## Decision Outcome

Probe the fixture before returning it. Its shell prefix exits immediately when the probe variable
is set, before executing scenario-specific code. Normal catalog calls do not receive that variable.
Preparation retries ETXTBSY within one ten-second monotonic deadline. Other launch errors and
nonzero exits fail immediately. A one-millisecond pause separates busy-file retries.

The same deadline bounds the started probe. A finally block terminates it after completion,
timeout, or interruption. Interruption remains set on the calling thread. Test-specific deadline
injection permits lifecycle checks without waiting ten seconds.

Do not add raw exception logging to the production resolver for this fixture failure. The failure
was reproduced through temporary instrumentation, and production fallback behavior does not need
to change. This is a test-fixture correction with no user-facing contract or SPEC change.

### Consequences

* Good, because catalog tests retain real process and JSON-RPC boundaries.
* Good, because the fixture becomes executable before assertions begin.
* Good, because preparation cannot leave a stuck probe after timeout or interruption.
* Bad, because each fixture requires one extra short-lived process.
* Bad, because ETXTBSY detection relies on the JDK's launch diagnostic text on Linux.

### Confirmation

Repeat the original catalog case under two-CPU Temurin execution and confirm all 1,000 repetitions
pass. Keep the temporary repetition and instrumentation out of the committed tests.
`CodexModelAppServerFixtureTest` holds a writable descriptor until the probe reports ETXTBSY,
then releases it and requires success. Removing the retry fails that deterministic regression.
It also checks that preparation never runs the scenario body, nonzero exits, bounded completion,
and interruption cleanup. PID readiness markers are published by atomic rename.
Removing probe termination must fail the two lifecycle checks. The full Maven gate remains required.

## Pros and Cons of the Options

### Probe fixture executability

* Good, because preparation owns the race and returns a ready fixture.
* Good, because the no-op branch never runs the test's catalog scenario.
* Bad, because it adds a process and a platform-specific retry condition.

### Retry production requests or failed tests

* Good, because it hides the immediate failure.
* Bad, because it changes product behavior or masks failures after fixture preparation.
* Bad, because it does not establish the fixture's executable state before use.

### Replace the process fixture

* Good, because it avoids executable-file races.
* Bad, because it removes coverage of launch, pipes, and JSON-RPC interaction.

## More Information

[ADR 0055](0055-test-deduplication-layer-boundaries.md) preserves process-boundary coverage while
assigning fixture preparation and protocol assertions to their owning tests.
