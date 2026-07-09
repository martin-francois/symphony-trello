# Testing

## Scope

How to write unit and integration tests in this repository, including frameworks, structure, and
parallel safety. Live end-to-end and deployed-verification rules live in
[Deployment & live verification](deployment-and-live-verification.md). How to run the build is in
[Default workflow](default-workflow.md).

## Test level and structure

- Follow the testing pyramid: fast focused unit tests first, broader integration tests where shared
  contracts or external boundaries are involved.
- Structure unit tests with `// given`, `// when`, and `// then` sections separated by blank lines.
- Test names and assertion descriptions should make failures actionable without requiring a debug
  session.

## Frameworks

- Use JUnit 6 and AssertJ. Prefer readable AssertJ chains when they improve the failure message.
- Prefer AssertJ's type-specific assertions over asserting raw booleans from helper APIs when the
  feature exists. For example, use `assertThat(path).isSymbolicLink().isDirectory()` instead of
  `assertThat(Files.isSymbolicLink(path)).isTrue()` followed by
  `assertThat(Files.isDirectory(path)).isTrue()`. Reserve `isTrue()` and `isFalse()` for values that
  are already domain booleans or for cases where AssertJ has no clearer assertion.
- Prefer AssertJ's collection, map, path, optional, string, throwable, and type-specific assertions
  over assertion loops, assertion streams, boolean reducers, or collected intermediate values.
  Reaching for `for`, `.forEach(...)`, `.stream()`, `.allMatch(...)`, `.anyMatch(...)`, or
  `.map(...).toList()` in a test assertion is a code smell when AssertJ can express the expectation
  directly with better failure output. Use features such as `extracting`, `flatExtracting`,
  `filteredOn`, `containsExactly`, `containsExactlyInAnyOrder`, `containsEntry`, `allSatisfy`,
  `anySatisfy`, `noneSatisfy`, `zipSatisfy`, and `singleElement` where they fit. Keep loops or
  streams when they are clearer for fixture construction, fake protocol behavior, concurrency
  orchestration, or another non-assertion purpose; add a short explanation when that intent is not
  obvious.
- Use Mockito for mocks. Keep purpose-built fakes only when they model an external protocol, stateful
  fixture, or concurrency behavior more clearly than Mockito stubbing.
- Prefer parameterized tests with `@MethodSource` for data-driven behavior.
- Statically import test framework methods everywhere in tests: JUnit assertions and assumptions,
  AssertJ assertions, and Mockito methods such as `assertThat`, `assertThatThrownBy`, `assumeTrue`,
  `abort`, `mock`, `when`, and `verify`. Do not call them through the class name like
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
- If you catch yourself making the same setup, command-construction, or assertion edit in more than
  one test, stop and decide whether a parameterized test, scenario record, enum, or helper would make
  the coupling explicit. Apply that refactor before committing unless the cases truly represent
  different concepts that should evolve independently.
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
