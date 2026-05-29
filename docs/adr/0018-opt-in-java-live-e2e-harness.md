---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted:
  - SPEC.md
  - docs/live-e2e.md
  - "[GitHub issue #1](https://github.com/martin-francois/symphony-trello/issues/1)"
informed: [Future maintainers]
---

# Add an Opt-In Java Live E2E Harness

## Context and Problem Statement

The live Trello runbook is detailed enough to reproduce scheduler, Trello setup, handoff, import,
and concurrency behavior. It is still easy to run inconsistently because the operator must create
boards, patch workflows, start several service processes, poll status endpoints, inspect Trello
state, and clean up disposable boards by hand.

How should Symphony for Trello automate repeatable live coverage without making normal CI depend on
Trello credentials, disposable external state, or real Codex availability?

## Decision Drivers

* Keep normal `verify` deterministic when no live opt-in flag is set.
* Exercise the packaged Quarkus runner rather than only in-process unit wiring.
* Use real Trello for board setup, card ordering, comments, moves, imports, and cleanup.
* Run deterministic fake-Codex checks before any real-Codex checks.
* Keep reusable live test code in Java.
* Avoid logging or committing secrets, private board names, card IDs, account names, and host paths.
* Leave manual coverage for strict real Codex and deployment-specific troubleshooting.

## Considered Options

* Add an opt-in Java integration test that runs through Maven Failsafe.
* Keep only the manual markdown runbook.
* Run live Trello E2E automatically in CI.
* Add a shell-only or external-language live harness.

## Decision Outcome

Chosen option: "Add an opt-in Java integration test that runs through Maven Failsafe".

`LiveTrelloE2eIT` runs only when `SYMPHONY_RUN_LIVE_E2E=1` is set. It reads Trello credentials from
the same `.env` / environment contract as the application, creates disposable boards, patches
workflows to use the Java fake Codex app-server, starts packaged Symphony processes on local ports,
verifies card ordering and concurrency through `/api/v1/state`, verifies Trello comments and handoff
moves, imports a non-default existing board, and archives created boards in cleanup.

The markdown runbook remains the source for strict real-Codex checks, deployment troubleshooting,
and live scenarios that are not automated yet.

### Consequences

* Good, because the most repeatable fake-Codex live checks are executable.
* Good, because the test exercises real Trello behavior and the packaged service process.
* Good, because normal CI and local `verify` runs still work without Trello credentials.
* Good, because Java remains the maintained automation language for the repository.
* Neutral, because strict real-Codex and deployment checks are still manual.
* Bad, because opt-in tests can still fail for external Trello availability, token scope, rate
  limits, or cleanup permissions.

### Confirmation

Run normal verification:

```bash
./mvnw -q spotless:check verify
```

Run the automated live Trello fake-Codex harness:

```bash
SYMPHONY_RUN_LIVE_E2E=1 ./mvnw -q -Dit.test=LiveTrelloE2eIT verify
```

If the Trello token can access multiple Workspaces, set
`SYMPHONY_LIVE_E2E_TRELLO_WORKSPACE_ID` for the disposable board Workspace.

## Pros and Cons of the Options

### Add an Opt-In Java Integration Test That Runs Through Maven Failsafe

Create a JUnit integration test named `LiveTrelloE2eIT` and bind Failsafe to `verify`. The test
skips unless explicitly enabled by environment configuration.

* Good, because the command fits the existing Maven/JUnit workflow.
* Good, because Failsafe runs after `package`, so the test can launch `target/quarkus-app/quarkus-run.jar`.
* Good, because the same Java assertions can poll Trello and the service status API.
* Bad, because it adds slower code under `src/test/java` that must stay isolated from normal unit
  tests.

### Keep Only the Manual Markdown Runbook

Continue to rely on `docs/live-e2e.md` for all live verification.

* Good, because it is transparent and easy to adapt during exploratory debugging.
* Good, because there is no additional build configuration.
* Bad, because manual live checks are easier to skip or run differently each time.

### Run Live Trello E2E Automatically in CI

Configure CI to run live Trello E2E on every pull request or main build.

* Good, because it would catch live regressions continuously.
* Bad, because CI would need Trello secrets and disposable external cleanup.
* Bad, because external service availability and rate limits could block unrelated work.

### Add a Shell-Only or External-Language Live Harness

Automate the runbook with shell, Python, Node, or another helper language.

* Good, because shell can orchestrate existing commands quickly.
* Bad, because maintained live verification logic would drift away from the repository's Java-first
  toolchain.
* Bad, because robust JSON polling, cleanup, and assertions are easier to maintain in JUnit.

## More Information

The harness intentionally does not replace strict real-Codex checks. Real-Codex coverage remains in
`docs/live-e2e.md` until it can be made deterministic enough to automate without hiding important
failure details.
