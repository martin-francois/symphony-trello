---
status: accepted
date: 2026-06-16
decision-makers: ["martinfrancois"]
consulted:
  - "[GitHub issue #416](https://github.com/martin-francois/symphony-trello/issues/416)"
informed: ["repository contributors"]
---

# Cross-Layer CLI Validation Tests Kept As Per-Command Regression Coverage

## Context and Problem Statement

The test-deduplication effort in
[GitHub issue #416](https://github.com/martin-francois/symphony-trello/issues/416) proposed a
Phase 3 "testing-pyramid restructure": collapse the in-JVM argument-validation tests in
`TrelloBoardSetupMainTest` that appear to duplicate validation already covered at the `LocalSetup`
layer in `LocalSetupTest`, and "own each validation rule at the lower layer" with only thin wiring
tested above. The apparent duplication is large by grep count — the error code
`setup_invalid_arguments` is asserted 56 times in the Main test and 24 times in the Local test, and
families such as control-character, blank-value, and codex-model rejections appear in both. Before
removing any of those tests, the issue itself required proof, per test, that the lower layer covers
the same production code and asserts the same rule. Should the cross-layer validation tests be
collapsed, and if so, which?

## Decision Drivers

- The testing pyramid favors owning each rule once at the lowest layer that can express it, with
  thin wiring tests above, so duplicated coverage of the *same* production code is waste.
- A validation test may only be removed if a kept lower-layer test exercises the *same* production
  validation code path *and* pins the *same* rule and message — a shared error *vocabulary* is not
  shared *code*.
- Per-command regression coverage must be preserved: a test proving that `new-board` (or
  `import-board`, `status`, `diagnostics`, `list-workspaces`) wires its inputs to the validator is
  not redundant with a test of a different command, even when both reject "blank" with the same
  message.
- Coverage must not drop: removing a test that is the only cover for a production branch is a
  regression even when another test asserts a similar-looking message elsewhere.

## Considered Options

- Keep every cross-layer CLI validation test (own each command's validation at its own command path)
- Collapse the Main validation tests into the `LocalSetup` layer and delete the duplicates
- Remove only the subset proven redundant by a per-test guardian check

## Decision Outcome

Chosen option: "Keep every cross-layer CLI validation test", because a per-test audit showed the
apparent cross-layer overlap is **not redundant**. Only the `setup-local` command delegates to
`LocalSetup.run(...)`; the `new-board` and `import-board` commands validate their arguments through
`BoardSetupOptions` and `CliInputValidation`, `status` validates through its own selector check, and
`diagnostics` validates through `DiagnosticsOptions`. These are distinct production code paths that
merely share the same error vocabulary. Collapsing them would delete the only regression coverage of
each command's own validation wiring while leaving the shared `setup_invalid_arguments` *string*
asserted at the other layer — trading real coverage for a cosmetic line saving.

The audit found exactly one Main test whose underlying production code (`TrelloApiEndpoint.normalize`)
is also exercised at the lower layer (`list-workspaces` endpoint validation vs `LocalSetupTest`'s
dry-run endpoint validation). It is **kept** as well, because it is the only test that proves the
`list-workspaces` command path itself reaches that validation; removing it would drop coverage of
that command's wiring even though the shared normalizer stays covered. No tests were removed in
Phase 3.

The process-boundary regression tests (the `runMainProcess` family) and the Main-wrapper tests
(troubleshooting-report suppression/creation, exit-code mapping, version, dispatch, help output) were
never in scope for removal and remain.

### Consequences

- Good, because each CLI command keeps a focused regression test proving it rejects bad input at its
  own boundary; a refactor that broke one command's validation wiring without touching the shared
  vocabulary would still fail a test.
- Good, because no coverage is lost: the JaCoCo per-class `*_COVERED` equivalence check used
  throughout issue #416 stays unchanged, which it would not if a sole-cover test were deleted.
- Good, because the question is now settled with evidence rather than re-litigated from a grep count,
  matching the issue's own caveat that "cross-layer overlap cannot be assumed to be lazy duplication
  from a grep count."
- Bad, because the suite retains visible repetition of the same error-code and message strings across
  the per-command tests; this is accepted as intentional per-command coverage, not duplication to
  remove.

### Confirmation

The per-command validation paths are distinct in production code: `NewBoardCommand`/`ImportBoardCommand`
validate via `BoardSetupOptions`/`CliInputValidation`, `SetupLocalCommand` delegates to
`LocalSetup.run`, `StatusCommand` uses its own selector validation, and the diagnostics command uses
`DiagnosticsOptions`. The coverage-equivalence guardian confirms the decision operationally: removing
any of these Main validation tests drops the corresponding command class's `*_COVERED` count in
`target/site/jacoco/jacoco.csv` relative to the issue #416 baseline, because no lower-layer test
exercises that command's path. Phase 3 therefore made no removals; the safe, behavior-preserving
deduplication for this suite is fully captured by the earlier phases of issue #416 (shared CLI
builder, fluent result assertions, parameterized tables, shared test data and content builders).

## Pros and Cons of the Options

### Keep every cross-layer CLI validation test

Leave the per-command validation tests where they are, accepting that several commands assert the
same error vocabulary because each command validates on its own code path.

- Good, because every command keeps direct regression coverage of its own input validation.
- Good, because no production branch loses its only cover, so coverage is provably unchanged.
- Neutral, because the repeated error-code strings are data the tests assert, not duplicated logic.
- Bad, because a reader skimming grep counts may mistake the shared vocabulary for removable
  duplication; this ADR exists to answer that.

### Collapse the Main validation tests into the LocalSetup layer

Delete the Main in-JVM validation tests and rely on `LocalSetupTest` to own each rule, keeping only
thin wiring tests in the Main file.

- Good, because it would remove the surface-level repetition and shrink the Main test file.
- Bad, because `LocalSetupTest` exercises only the `setup-local` path, so it does not cover the
  `new-board`, `import-board`, `status`, or `diagnostics` validation code at all; the deletion would
  lose real coverage, which the JaCoCo equivalence check would flag as a drop.
- Bad, because it assumes a shared validation layer that does not exist for these commands.

### Remove only the subset proven redundant by a per-test guardian check

Audit each Main validation test, revert the production hunk it guards, and remove it only if a kept
lower-layer test then fails for the same reason.

- Good, because it is the safe, principled way to act on genuine cross-layer redundancy when it
  exists.
- Neutral, because it is exactly the audit that was performed; its result was that the redundant
  subset is empty (one near-candidate, still command-specific), so the principled outcome is to
  remove nothing.
- Bad, because running the full guardian protocol to delete zero tests is cost without benefit once
  the architectural distinction above is established.

## More Information

The validation-test inventory and per-command path analysis were produced while implementing
[GitHub issue #416](https://github.com/martin-francois/symphony-trello/issues/416); the earlier,
coverage-neutral phases of that issue (a fluent `SetupCommandBuilder`, adoption of
`runCli`/`CliRunResult`, parameterized `@MethodSource` validation tables, a shared
`ConnectedBoardBuilder`/`SetupTestData`, and `TestWorkflows`/`TestEnv` content builders) captured the
safe deduplication. The repository's broader test policy is recorded in
[ADR 0011](0011-test-quality-and-coverage-policy.md) and
[ADR 0020](0020-junit-6-test-stack.md).
