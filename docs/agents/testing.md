# Testing

## Scope

How to write unit and integration tests in this repository, including frameworks, structure, and
parallel safety. Live end-to-end and deployed-verification rules live in
[Deployment & live verification](deployment-and-live-verification.md). How to run the build is in
[Default workflow](default-workflow.md).

## Test level and structure

- Follow the testing pyramid: fast focused unit tests first, broader integration tests where shared
  contracts or external boundaries are involved.
- Run standalone script tests in required CI through a project command that discovers every
  supported `scripts/**/*.test.*` file. Do not list only the test files that exist today. Strictly
  type-check TypeScript scripts before running their tests.
- Structure unit tests with `// given`, `// when`, and `// then` sections separated by blank lines.
- Test names and assertion descriptions should make failures actionable without requiring a debug
  session.

## Frameworks

- Use JUnit 6 and AssertJ.
- Design assertions in this priority order: actionable failure output, readable intent, then fewer
  chains and less duplication. Prefer one fluent AssertJ chain when it keeps the same subject and
  every failure still identifies the violated property or element. Keep independent chains when
  combining them would make a failure less specific.
- Before retaining repeated `assertThat(...)` roots or repeated element or property traversal, inspect
  the complete assertion block, resolve the AssertJ version from `pom.xml`, and inspect that version's
  Javadocs. For example, reconsider multiple assertions that begin from
  `ctSwitch.getCases().get(0)`. Choose the native assertion that matches the contract:
  `element`/`elements` for indexed navigation, `singleElement` for exact cardinality,
  `extracting`/`flatExtracting` for projections, `satisfiesExactly` for ordered per-element
  requirements, and `zipSatisfy` for paired collections. A new or changed test **MUST NOT** add a
  custom assertion helper or wrapper solely to shorten or merge chains when AssertJ expresses the
  expectation directly.
- Prefer AssertJ's type-specific assertions over asserting raw booleans from helper APIs. For
  example, use `assertThat(path).isSymbolicLink().isDirectory()` instead of
  `assertThat(Files.isSymbolicLink(path)).isTrue()` followed by
  `assertThat(Files.isDirectory(path)).isTrue()`. When AssertJ has no more specific assertion and
  `isTrue()` or `isFalse()` is necessary, the assertion **MUST** include an actionable invariant or
  timing description through `as(...)`, `describedAs(...)`, `withFailMessage(...)`, or
  `overridingErrorMessage(...)`. A domain-boolean variable or helper name does not replace that
  description; state what must hold or what must happen before the relevant deadline. This applies
  to attributed AssertJ boolean entry points including standard and soft `assertThat(...)`, BDD
  `then(...)`, and assumption `assumeThat(...)`/`given(...)` forms; changing the factory spelling
  does not bypass the convention.
- Prefer AssertJ's collection, map, path, optional, string, throwable, and type-specific assertions
  over assertion loops, assertion streams, boolean reducers, or collected intermediate values.
  New or changed Java tests **MUST NOT** introduce Stream pipelines, including pipelines used only
  to filter, map, or project assertion data. Use features such as `extracting`, `flatExtracting`,
  `filteredOn`, `containsExactly`, `containsExactlyInAnyOrder`, `containsEntry`, `allSatisfy`,
  `anySatisfy`, `noneSatisfy`, `zipSatisfy`, and `singleElement` for assertions. Use an explicit loop
  when fixture construction, fake protocol behavior, concurrency orchestration, or another
  non-assertion purpose requires iteration.
- Use Mockito for mocks. Keep purpose-built fakes only when they model an external protocol, stateful
  fixture, or concurrency behavior more clearly than Mockito stubbing.
- Prefer a parameterized test when cases share the same setup, action, and assertion contract, differ
  only in data, and parameterization removes duplication without obscuring scenario intent. Select
  the narrowest suitable JUnit source, such as `@ValueSource`, `@CsvSource`, `@EnumSource`, or
  `@MethodSource`. Give cases meaningful display names when their arguments do not identify the
  scenario. Keep separate tests when the scenarios represent distinct behavior or need independent
  failure narratives.
- Statically import test framework methods everywhere in tests: JUnit assertions and assumptions,
  AssertJ assertions, and Mockito methods such as `assertThat`, `assertThatThrownBy`, `assumeTrue`,
  `abort`, `mock`, `when`, and `verify`. This repository convention takes precedence over examples
  that qualify factory methods. Do not call them through the class name like
  `Assumptions.assumeTrue(...)`.

## Parallel safety

- Write new tests so they can run under JUnit class and method parallel execution by default. Avoid
  JVM-global mutation such as system properties, environment hacks, fixed ports, process-wide
  singleton state, shared files, or shared external service fixtures unless the test owns and
  restores the state in a way that remains safe when other tests run concurrently. Prefer dependency
  injection, per-test temporary directories, dynamic ports, scoped fakes, and unique fixture names.
  If a test truly must coordinate access to a shared resource, keep the lock narrow and explain why
  the resource cannot be isolated; do not make broad test classes or whole finding families serial
  only to hide parallel-safety problems.
- In tests, prefer waiting for an observable condition with a bounded helper such as `waitUntil` or
  Awaitility instead of using a fixed `Thread.sleep`. A sleep may remain only as the poll interval
  inside a bounded wait or retry helper, or when sleeping is the behavior under test. For
  intentionally blocking fakes, prefer an interruptible blocking primitive such as
  `CountDownLatch.await(...)` with a timeout, and make the helper name or test setup explain why a
  condition wait would not express the test better.
- Every added or changed concurrency mechanism—including `synchronized`, locks, atomics, concurrent
  collections, volatile state, task scheduling, and stale-callback guards—must have a realistic
  automated unit test, or an integration test when the race cannot be represented at unit level.
  The test must run in the normal CI suite and reproduce the race or visibility failure that the
  mechanism prevents. Prefer assertions on the domain invariant so replacing the implementation
  with another correct concurrency mechanism keeps the test green.
- Before finalizing concurrency code, run the owning test against a temporary mutation that removes
  or disables the protection and confirm it fails for the intended race-specific reason, then
  restore the implementation and confirm the same test passes. Use deterministic latches, barriers,
  controlled executors, or callbacks to force the interleaving; do not depend on repeated execution
  or timing luck. Map each concurrency mechanism changed by the pull request to its owning test in
  the pull request validation notes.
- When a test is confirmed flaky, search open and closed issues for that test before creating a
  focused issue. Create one only when no issue already owns it, and ensure the owning issue has the
  `flaky` label. Then add `// TODO Flaky: #123` immediately above the test method using the real
  issue number. Every time the flake is observed, add a new comment to its issue with the date, run
  or local command, environment, and the relevant failed test output so recurrence frequency and
  cost remain visible. Do not quarantine, disable, retry, or weaken the test merely to restore a
  green build. Remove the marker only in the commit that fixes the root cause and proves the test
  deterministic under its normal CI execution mode.

## Regression tests

- After reproducing a bug and before changing product code, explicitly ask why the current suite
  missed it and add the smallest regression test that represents the reproduced failure. Run the
  test against the buggy code and confirm that it fails in the expected bug-specific way rather than
  because of fixture setup, an unavailable dependency, or another incidental error. Do not implement
  the fix until this red test is established. If no suitable regression test can be made to fail for
  the reproduced bug, stop, explain the testability blocker, and ask the requester how to proceed.
- After establishing the red test, fix the identified mechanism and run the focused test until it is
  green. Keep the regression test in the normal durable test suite at the narrowest useful level.
- Fuzz tests are regression tests in normal Maven runs. When changing parser, prompt-line safety,
  workflow loading, or Trello reference/checklist parsing logic, run the focused fuzzing and chaos
  regression command from [Fuzzing](../fuzzing.md). If the user asks for active or continuous
  fuzzing, run one Jazzer target per Maven process with `JAZZER_FUZZ=1`, the `fuzzing` Maven profile,
  `-Djacoco.skip=true`, an explicit `-Djazzer.max_duration=...` window, and
  `-Djazzer.max_executions=0` so the requested duration is not cut short by a method-level
  regression cap. Use the 15- to 30-minute loop in [Fuzzing](../fuzzing.md) for a short pass, or a
  longer duration such as `-Djazzer.max_duration=6h` when the user asks for a continuous run. Do not
  treat continuous fuzzing as a contributor requirement unless the issue or user asks for it.

## Reducing duplication

- When two or more tests repeat the same given-scaffolding or differ only in input data, extract a
  shared fixture helper or use a parameterized test instead of copying the block. Keep each test's
  distinctive inputs and assertions visible at the call site; share only the genuinely common setup.
- When repetition is in assertion navigation rather than setup, apply the native AssertJ guidance
  above before extracting a helper. Use an assertion helper only when AssertJ cannot express the
  expectation directly and the helper names a reusable domain contract; do not extract one solely to
  hide repeated indexing or property traversal.
- If you catch yourself making the same setup, command-construction, or assertion edit in more than
  one test, stop and decide whether a native AssertJ assertion, parameterized test, scenario record,
  enum, or helper would make the coupling explicit. Apply that refactor before committing unless the
  cases truly represent different concepts that should evolve independently.
- Before adding a new fake server, CLI command array, workflow/env text block, manifest assertion,
  workflow assertion, or terminal transcript assertion, check
  `src/test/java/ch/fmartin/symphony/trello/testsupport` and existing package-local fixtures for a
  suitable helper.
- Use [ADR 0055](../adr/0055-test-deduplication-layer-boundaries.md) when deciding whether a
  top-level command test duplicates a lower-layer setup test. Keep process-boundary and main-wrapper
  coverage; remove only higher-layer validation rows that the owning lower-layer test covers with
  the same option, input, error code, and message.

## What to test and what not to test

- Do not write low-value tests that only restate a constant. Do test parsing, policy enforcement,
  edge cases, failure modes, and cross-component contracts.
- Do not add tests whose only purpose is to exercise POJOs, records, getters, setters, or generated
  accessors without logic. Coverage should come from meaningful behavior.
- When reporting test coverage gaps, do not list a redundant missing check if an existing check is
  already close enough for the risk the user asked about. Explain what the existing check covers.

## References

- [Deployment & live verification](deployment-and-live-verification.md)
- [Default workflow](default-workflow.md)
- [Java style & design preferences](java-style.md)
