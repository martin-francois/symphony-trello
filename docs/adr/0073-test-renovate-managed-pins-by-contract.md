---
status: accepted
date: 2026-07-21
decision-makers: [François Martin, Codex]
consulted: [Renovate-managed dependency update behavior, CI failures in GitHub pull request 609]
informed: [Future maintainers]
---

# Test Renovate-Managed Pins by Contract

## Context and Problem Statement

Renovate updates GitHub Action commit pins and container image versions in workflow and wrapper
files. Some tests copied the current commit SHA or image version into a second file. A valid
Renovate update therefore failed CI until a maintainer changed the duplicated test value, even
though the tested security and runtime behavior remained correct.

How should tests protect immutable action references and versioned container defaults without
requiring manual test edits for routine Renovate updates?

## Decision Drivers

* Renovate updates MUST remain reviewable without unrelated manual test maintenance.
* GitHub Actions MUST remain pinned to full commit SHAs with readable version comments.
* Container wrappers MUST execute the expected registry and repository with an explicit version.
* Tests MUST continue to reject mutable or malformed dependency references.
* The dependency declaration MUST remain the single source of truth for its current version.

## Considered Options

* Assert the dependency reference structure and runtime behavior without copying its current value.
* Copy every current SHA and version into the tests and update both files together.
* Teach Renovate to update duplicate values in test files.
* Remove the dependency-reference assertions.

## Decision Outcome

Chosen option: "Assert the dependency reference structure and runtime behavior without copying its
current value", because it verifies the durable contract while leaving Renovate-managed values in
one source location.

Workflow tests require the named action, a lowercase 40-character hexadecimal commit SHA, and the
expected version-comment shape. Container-wrapper tests execute each wrapper with a recording
runtime and require the expected registry and repository with an explicit version shape. Tests keep
checking the surrounding permissions, inputs, arguments, and failure behavior independently of the
current dependency release.

### Consequences

* Good, because routine Renovate pin updates do not require a maintainer to edit tests.
* Good, because mutable action tags, shortened action SHAs, missing version comments, mutable
  container tags, and unexpected image repositories still fail CI.
* Good, because wrapper tests inspect the actual container invocation rather than only reading a
  declaration.
* Neutral, because Renovate and code review remain responsible for confirming that a new SHA belongs
  to the documented action release.
* Bad, because a test failure no longer prints the previously accepted dependency version as a
  comparison value.

### Confirmation

Run `pnpm run verify:scripts` and `./mvnw -q spotless:check verify`. Apply a candidate Renovate
update in an isolated worktree and confirm the focused workflow and container-wrapper tests pass
without test-value changes. Review the tests to confirm they still require full action SHAs, version
comments, expected image repositories, and explicit image versions.

## Pros and Cons of the Options

### Assert Dependency Contracts Without Copying Current Values

Match the stable action and image identity plus the required pin shape, then exercise the surrounding
workflow or wrapper behavior.

* Good, because tests change only when the contract changes.
* Good, because invalid mutable references still fail.
* Good, because container assertions cover the command that the wrapper actually executes.
* Bad, because the test does not independently list the exact approved release.

### Copy Every Current Value Into Tests

Keep exact expected SHAs and versions in test source and update those expectations with each
dependency release.

* Good, because tests state the currently approved value explicitly.
* Bad, because valid automated updates fail until a maintainer synchronizes duplicate values.
* Bad, because the test restates a constant instead of testing behavior or policy.

### Teach Renovate To Update Test Copies

Add custom Renovate matching rules for every duplicated test value so dependency and expectation
changes arrive in the same commit.

* Good, because exact-value assertions can remain green after recognized updates.
* Bad, because each new assertion format requires another update rule.
* Bad, because an update-rule mismatch recreates the same maintenance failure.
* Bad, because matching test fixtures broadens the dependency manager's write surface.

### Remove Dependency-Reference Assertions

Delete the action-pin and container-image checks and rely on workflow execution or separate scanners.

* Good, because dependency version updates cannot break these tests.
* Bad, because regressions to mutable references or wrong image repositories lose a local guardrail.

## More Information

This decision refines the confirmation strategy in
[ADR 0008](0008-renovate-and-github-actions-hardening.md). It does not change a user-facing contract,
so `SPEC.md` does not need an update.
