---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, docs/live-e2e.md]
informed: [Future maintainers]
---

# Keep Live Trello and Codex E2E Verification as a Reproducible Manual Procedure

## Context and Problem Statement

The service integrates with real Trello boards and real Codex worker sessions. Unit and integration
tests cover most behavior with local fakes, but they cannot prove that Trello authorization, board
creation, card movement, comments, multi-board runs, and real Codex execution work together.

How should live E2E coverage be documented without making every CI run depend on Trello credentials
and real Codex sessions?

## Decision Drivers

* Keep normal CI deterministic and safe for contributors.
* Preserve a reproducible path for testing real Trello and real Codex behavior.
* Test fake-Codex paths first to catch local bugs before spending live Codex time.
* Include multi-card and multi-board concurrency scenarios.
* Avoid committing secrets or requiring live credentials for every developer.

## Considered Options

* Document live E2E checks in `docs/live-e2e.md` and run them manually when needed.
* Add Java live E2E tests that run automatically in CI.
* Skip live E2E and rely only on fakes.

## Decision Outcome

Chosen option: "Document live E2E checks in `docs/live-e2e.md` and run them manually when needed",
because it keeps CI stable while preserving a detailed, repeatable live verification procedure.

### Consequences

* Good, because maintainers can reproduce real Trello/Codex checks before risky releases.
* Good, because CI remains usable without external secrets or live account side effects.
* Good, because fake-Codex checks can be run before real-Codex checks.
* Bad, because live E2E coverage is not enforced on every pull request.
* Bad, because manual execution can drift unless the document is kept current.

### Confirmation

Run `./mvnw -q spotless:check verify` for normal verification. For live integration changes, follow
`docs/live-e2e.md` and record which real systems were used and which parts used fakes.

## Pros and Cons of the Options

### Document live E2E checks in `docs/live-e2e.md` and run them manually when needed

Keep a markdown runbook for live Trello and Codex verification, including setup, fake-Codex phase,
real-Codex phase, card ordering, and multi-board scenarios.

* Good, because it is transparent and easy for an agent or maintainer to follow.
* Good, because it avoids secret-dependent CI failures.
* Neutral, because execution frequency depends on maintainer judgment.
* Bad, because it does not provide automatic regression protection for live-only failures.

### Add Java live E2E tests that run automatically in CI

Create automated tests that create Trello boards and run real Codex sessions in CI.

* Good, because it would enforce live behavior continuously.
* Bad, because it needs secrets, cleanup policy, rate-limit handling, and external service
  availability.
* Bad, because real Codex sessions can be slow, expensive, or nondeterministic.

### Skip live E2E and rely only on fakes

Use fake Trello/Codex or mocked clients for all tests.

* Good, because tests stay fast and deterministic.
* Bad, because Trello authorization, board setup, real card ordering, and Codex handoff can break
  without tests noticing.
* Bad, because confidence in multi-board and real-agent flows would be weaker.

## More Information

The live runbook should include at least one non-default existing board import case, single-board
parallelism checks, and multiple-board concurrency checks.
