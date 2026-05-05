---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [pom.xml, JavaStyleTest, CONTRIBUTING.md]
informed: [Future maintainers]
---

# Enforce Meaningful Tests, Clear Test Structure, and 80 Percent Coverage

## Context and Problem Statement

The service coordinates Trello state, local workspaces, Codex app-server sessions, retry behavior,
and dynamic Trello handoff tools. Regressions are often easier to prevent with focused tests than to
debug through live Trello and Codex runs after the fact.

How should the project keep test quality high without adding low-value tests or relying on manual
review for basic conventions?

## Decision Drivers

* Catch scheduler, Trello, Codex, workflow parsing, and policy regressions before live E2E.
* Keep CI strict enough that reviewers do not spend time on avoidable quality issues.
* Avoid tests that only restate POJO accessors, constants, or generated behavior.
* Make failing tests easy to understand from the failure output.
* Use Mockito for simple mocks while keeping protocol fakes where they better model stateful
  external behavior.
* Keep test conventions enforceable by the build.

## Considered Options

* Enforce JaCoCo 80 percent line coverage plus Java style tests for test structure and mocking.
* Rely on reviewer discipline without automated coverage or test-style checks.
* Require much higher coverage across the whole bundle.
* Use only broad integration tests and live E2E checks.

## Decision Outcome

Chosen option: "Enforce JaCoCo 80 percent line coverage plus Java style tests for test structure and
mocking", because it raises the quality floor while still leaving room for judgment about which tests
are meaningful.

### Consequences

* Good, because `./mvnw verify` and CI fail when line coverage drops below 80 percent.
* Good, because unit tests must use readable `// given`, `// when`, and `// then` sections.
* Good, because simple mocks use Mockito instead of hand-written one-off doubles.
* Good, because maintainers are still expected to avoid low-value POJO/getter/setter tests.
* Bad, because broad refactors may need test updates before behavior changes are complete.
* Bad, because a bundle-level coverage threshold can hide uneven coverage across packages.

### Confirmation

Run `./mvnw -q spotless:check verify`. Review new tests to ensure they cover behavior, edge cases,
policy enforcement, or external-boundary contracts instead of only increasing the coverage number.

## Pros and Cons of the Options

### Enforce JaCoCo 80 percent line coverage plus Java style tests for test structure and mocking

Use JaCoCo's Maven check in `verify` and keep convention checks in `JavaStyleTest`.

* Good, because the same command works locally and in CI.
* Good, because the coverage gate catches accidental test deletion or large untested additions.
* Good, because the section structure makes tests easier to scan and failure causes easier to find.
* Neutral, because purpose-built fakes remain acceptable for stateful protocol and concurrency
  behavior.
* Bad, because style tests need maintenance when the project intentionally changes conventions.

### Rely on reviewer discipline without automated coverage or test-style checks

Ask maintainers to notice coverage drops, unclear tests, and unnecessary hand-written mocks during
review.

* Good, because it adds no build-time checks.
* Bad, because review attention is better spent on behavior and design.
* Bad, because conventions drift between contributors and sessions.

### Require much higher coverage across the whole bundle

Set a substantially higher global coverage gate.

* Good, because it would pressure contributors to test more code.
* Bad, because it could encourage tests that only exercise accessors or constants.
* Bad, because coverage percentage alone does not prove behavior is meaningfully tested.

### Use only broad integration tests and live E2E checks

Rely on Quarkus integration tests and live Trello/Codex runbooks instead of focused unit tests.

* Good, because broad tests are close to real use.
* Bad, because they are slower and failures are harder to diagnose.
* Bad, because live E2E is intentionally not required on every CI run.

## More Information

The coverage gate is a floor, not the goal. When adding tests, prefer examples where a removed branch,
changed policy, or small manual mutation would make the test fail with an actionable message.
