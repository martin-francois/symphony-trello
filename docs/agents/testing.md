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

- When a bug is found, explicitly ask why the current suite missed it and add the smallest regression
  test that would have failed before the fix. Prefer writing that test before the fix when practical.
  If the fix already exists, temporarily revert or otherwise disable the fix when feasible to confirm
  the regression test fails for the original bug, then restore the fix and make the test pass.

## Reducing duplication

- When two or more tests repeat the same given-scaffolding or differ only in input data, extract a
  shared fixture helper or use a parameterized test instead of copying the block. Keep each test's
  distinctive inputs and assertions visible at the call site; share only the genuinely common setup.

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
