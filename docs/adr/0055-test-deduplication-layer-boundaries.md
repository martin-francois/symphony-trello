---
status: accepted
date: 2026-06-16
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #416](https://github.com/martin-francois/symphony-trello/issues/416)"
  - "[ADR 0011](0011-test-quality-and-coverage-policy.md)"
  - "TrelloBoardSetupMainTest"
  - "LocalSetupTest"
informed: [Future maintainers, Contributors]
---

# Deduplicate Tests Along Layer Boundaries

## Context and Problem Statement

[GitHub issue #416](https://github.com/martin-francois/symphony-trello/issues/416) asks to reduce
test-suite duplication without removing behavior coverage. Most of the work is mechanical:
parameterized tests, shared fakes, shared builders, and fluent assertions.

One area needs an explicit rule. Some setup validation behavior is tested both at the lower
`LocalSetup` layer and at the top-level `TrelloBoardSetupMain` command layer. Removing all
top-level checks would make the suite smaller, but it could lose coverage for process-boundary and
main-wrapper behavior that only exists at the command boundary.

How should setup tests be deduplicated without losing the regression coverage that belongs at the
top-level command boundary?

## Decision Drivers

* Preserve every distinct setup behavior and regression scenario.
* Keep the testing pyramid from [ADR 0011](0011-test-quality-and-coverage-policy.md): lower layers
  should own detailed validation matrices.
* Keep real process-boundary tests that need a separate JVM, process environment, file type, FIFO,
  symlink, or exit-code path.
* Keep main-wrapper tests for dispatch, help/version output, expected versus unexpected failure
  handling, troubleshooting report creation or suppression, and exit-code mapping.
* Avoid repeated in-JVM command tests that only reassert a validation rule already covered at the
  owning lower layer.
* Make future test placement clear so the suite does not grow the same duplication again.

## Considered Options

* Own validation matrices at the lower layer and keep only command-boundary behavior above it.
* Keep duplicate validation checks at every setup layer.
* Move all setup validation checks to the top-level command tests.
* Delete top-level setup command tests whenever a lower-layer test exists.

## Decision Outcome

Chosen option: own validation matrices at the lower layer and keep only command-boundary behavior
above it.

Detailed parser and setup-service validation belongs in `LocalSetupTest` or the more specific
service test that owns the behavior. `TrelloBoardSetupMainTest` should keep tests for behavior that
the lower layer cannot prove:

* command dispatch and help/version output;
* process-boundary behavior using `runMainProcess(...)`;
* top-level exit-code mapping;
* expected failure handling that must not write a troubleshooting report;
* unexpected failure handling that must write a troubleshooting report;
* command-specific behavior for commands not owned by `LocalSetup`;
* redaction and error-format behavior that only appears at the command boundary.

An in-JVM `runCli(...)` test in `TrelloBoardSetupMainTest` may be removed only when a lower-layer
test covers the same command option, input value, error code, and user-facing message, and the
top-level test does not assert one of the command-boundary responsibilities above.

### Consequences

* Good, because validation rules have one clear owning layer.
* Good, because process-boundary and main-wrapper regressions remain covered.
* Good, because future tests have a clear placement rule.
* Good, because duplicated command tests can be removed in small, auditable steps.
* Bad, because some behavior remains covered at more than one layer when the top-level test checks a
  command-boundary concern in addition to the validation rule.
* Bad, because removal still needs a manual audit rather than a purely mechanical grep.

### Confirmation

When removing a higher-layer validation test, confirm that the lower-layer test still fails if the
validation behavior is broken. At minimum, inspect the matching lower-layer scenario for the same
option, input, error code, and message. For riskier deletions, temporarily break or revert the
production validation and verify that the lower-layer test fails for the right reason.

Run:

```bash
./mvnw -q -Dtest=TrelloBoardSetupMainTest,LocalSetupTest test
./mvnw -q spotless:check verify
```

## Pros and Cons of the Options

### Own Validation Matrices at the Lower Layer and Keep Only Command-Boundary Behavior Above It

Place detailed parser and setup validation at `LocalSetup` or the owning service. Keep top-level
command tests only when the top level adds behavior.

* Good, because tests follow the testing pyramid.
* Good, because duplicated setup validation rows can be removed without losing process-boundary
  coverage.
* Good, because failures usually point to the layer that owns the behavior.
* Bad, because maintainers must decide whether a top-level test checks wrapper behavior or only a
  duplicated validation rule.

### Keep Duplicate Validation Checks at Every Setup Layer

Leave the same validation matrix at the lower setup layer and at the top-level command layer.

* Good, because it is conservative.
* Good, because it avoids deciding ownership for old regression tests.
* Bad, because the suite stays larger than needed.
* Bad, because future validation changes require updates at multiple layers.

### Move All Setup Validation Checks to the Top-Level Command Tests

Use `TrelloBoardSetupMainTest` as the main owner for setup validation.

* Good, because it tests through the user-visible command object.
* Bad, because failures are farther from the validation code.
* Bad, because it makes lower-level setup behavior harder to test directly.
* Bad, because it encourages large command fixtures for simple validation rules.

### Delete Top-Level Setup Command Tests Whenever a Lower-Layer Test Exists

Remove top-level tests whenever there is any related lower-layer coverage.

* Good, because it removes the most lines.
* Bad, because it can delete process-boundary coverage.
* Bad, because it can delete top-level error handling, redaction, troubleshooting-report, or
  exit-code behavior.
* Bad, because it treats similar tests as equivalent without checking what each layer owns.

## More Information

[GitHub issue #416](https://github.com/martin-francois/symphony-trello/issues/416) contains the
test deduplication plan. [ADR 0011](0011-test-quality-and-coverage-policy.md) defines the general
test quality policy.
