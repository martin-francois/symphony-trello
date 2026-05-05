---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [pom.xml, ArchUnit, PMD, TestConventionTest, AdrConformanceTest, CONTRIBUTING.md]
informed: [Future maintainers]
---

# Enforce Meaningful Tests, Clear Test Structure, and 80 Percent Coverage

## Context and Problem Statement

The service coordinates Trello state, local workspaces, Codex app-server sessions, retry behavior,
and dynamic Trello handoff tools. Regressions are often easier to prevent with focused tests than to
debug through live Trello and Codex runs after the fact.

How should the project keep test and source quality high without adding low-value tests, noisy
linters, or review-only conventions for basic rules?

## Decision Drivers

* Catch scheduler, Trello, Codex, workflow parsing, and policy regressions before live E2E.
* Keep CI strict enough that reviewers do not spend time on avoidable quality issues.
* Avoid tests that only restate POJO accessors, constants, or generated behavior.
* Make failing tests easy to understand from the failure output.
* Use ArchUnit for architecture rules that are naturally expressed against compiled classes.
* Use PMD only when a narrow source rule is clearer than custom source parsing and cannot be handled
  by Spotless or ArchUnit.
* Use Mockito for simple mocks while keeping protocol fakes where they better model stateful
  external behavior.
* Keep test conventions enforceable by the build.

## Considered Options

* Enforce JaCoCo, ArchUnit, narrow PMD source rules, and focused custom convention tests.
* Use custom JUnit source scans for all conventions.
* Add Checkstyle for Java source conventions.
* Rely on reviewer discipline without automated coverage or test-style checks.
* Require much higher coverage across the whole bundle.
* Use only broad integration tests and live E2E checks.

## Decision Outcome

Chosen option: "Enforce JaCoCo, ArchUnit, narrow PMD source rules, and focused custom convention
tests", because it uses purpose-built tools where they fit while keeping custom checks only for
project-specific conventions that standard tools cannot see cleanly.

### Consequences

* Good, because `./mvnw verify` and CI fail when line coverage drops below 80 percent.
* Good, because ArchUnit checks circular dependencies between production top-level packages and
  important dependency boundaries.
* Good, because PMD replaces a hand-written source regex for inline fully qualified Java type names.
* Good, because unit tests must use readable `// given`, `// when`, and `// then` sections.
* Good, because simple mocks use Mockito instead of hand-written one-off doubles.
* Good, because maintainers are still expected to avoid low-value POJO/getter/setter tests.
* Bad, because the build now has one more test dependency and one narrow PMD plugin configuration.
* Bad, because broad refactors may need test updates before behavior changes are complete.
* Bad, because a bundle-level coverage threshold can hide uneven coverage across packages.

### Confirmation

Run `./mvnw -q spotless:check verify`. Review new tests to ensure they cover behavior, edge cases,
policy enforcement, or external-boundary contracts instead of only increasing the coverage number.
Review new ArchUnit and PMD rules for likely false positives before adding them.

## Pros and Cons of the Options

### Enforce JaCoCo, ArchUnit, narrow PMD source rules, and focused custom convention tests

Use JaCoCo's Maven check in `verify`, ArchUnit for compiled architecture rules such as top-level
package cycle detection, PMD for the narrow `UnnecessaryFullyQualifiedName` source rule, and custom
JUnit tests for conventions that need source comments or Markdown parsing.

* Good, because the same command works locally and in CI.
* Good, because the coverage gate catches accidental test deletion or large untested additions.
* Good, because architecture checks are expressed with ArchUnit instead of ad hoc reflection or text
  scans.
* Good, because PMD handles the Java source rule without broad lint noise.
* Good, because the section structure makes tests easier to scan and failure causes easier to find.
* Neutral, because purpose-built fakes remain acceptable for stateful protocol and concurrency
  behavior.
* Bad, because tool configuration needs maintenance when the project intentionally changes
  conventions.

### Use custom JUnit source scans for all conventions

Keep every style and convention rule in one or more JUnit tests that read source files directly.

* Good, because no extra Maven plugins or test dependencies are needed.
* Bad, because source regexes are more fragile than purpose-built tools.
* Bad, because architecture rules are easier to express and explain with ArchUnit.

### Add Checkstyle for Java source conventions

Add Checkstyle and move Java source conventions into a Checkstyle configuration.

* Good, because Checkstyle can enforce many Java source formatting and naming conventions.
* Bad, because Spotless already owns formatting and import cleanup.
* Bad, because adding Checkstyle for one or two local preferences would add more process and
  configuration than value.

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
