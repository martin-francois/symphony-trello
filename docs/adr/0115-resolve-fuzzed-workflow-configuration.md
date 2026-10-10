---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude Code]
consulted:
  - "[GitHub issue #771](https://github.com/martin-francois/symphony-trello/issues/771)"
  - "[GitHub issue #690](https://github.com/martin-francois/symphony-trello/issues/690)"
  - "[GitHub PR #787](https://github.com/martin-francois/symphony-trello/pull/787)"
  - "[ADR 0061](0061-jazzer-and-oss-fuzz-readiness.md)"
  - "[ADR 0078](0078-github-actions-continuous-fuzzing.md)"
informed: [Future maintainers, Contributors]
---

# Resolve fuzzed workflow configuration in the workflow fuzz target

## Context and Problem Statement

`WorkflowLoaderFuzzer` stopped after `WorkflowLoader.load`. The hosted coverage report of 2026-10-03
showed 0 of 272 branches covered in the classes that turn front matter into typed configuration:
`ConfigResolver`, `CodexSandboxPolicy`, `WorkflowConfigIngestion`, `EnvironmentReferences`,
`TrelloListRoleValidator`, `TypedWorkflowConfig`, `WorkflowServerPortClassification`, and
`WholeNumbers`. The loader itself had 40 of 40 lines covered, so its fuzzing time only added edges
inside the YAML library.

These classes decide the Codex sandbox policy, secret references, the local server port, list
roles, and timeouts from YAML that the operator writes. How should fuzzing reach them without
reading the host environment or host files?

## Decision Drivers

* Reuse the stored corpus of about 1,800 workflow inputs.
* Keep the four-target ClusterFuzzLite matrix and its time budget unchanged.
* Exercise the YAML value types that `WorkflowConfigIngestion` depends on.
* No fuzz execution reads the process environment, a `.env` file, or a host file.
* Check properties of the result, not only the exception type.

## Considered Options

* Extend the workflow target to resolve every loaded workflow
* Add a fifth standalone `WorkflowConfigFuzzer`
* Generate the configuration map directly and skip YAML
* Keep `file:` secrets in a per-process temporary directory
* Read `file:` secrets through an in-memory seam

## Decision Outcome

Chosen options: "Extend the workflow target to resolve every loaded workflow" and "Read `file:`
secrets through an in-memory seam".

`WorkflowConfigInvariants` runs after every successful load in `WorkflowLoaderFuzzer` and
`WorkflowLoaderFuzzTest`. It resolves the definition with `ConfigResolver`, runs
`validateForDispatch`, builds the Codex sandbox policy with `CodexSandboxPolicy.effectivePolicy`, and
reads the server port through `TypedWorkflowConfig.localServerPortSetting` and
`serverPortClassification`. A `ConfigException` with a snake_case code is the expected rejection.
Any other exception is a finding, and so is a broken property:

* resolved paths are absolute and normalized,
* timeouts, delays, agent limits, priorities, and per-state limits are in range,
* only `SYMPHONY_CODEX_DANGER_FULL_ACCESS` forces full access, and then no additional roots apply,
* the effective sandbox policy builds and grants every additional writable root,
* setup diagnostics classify and probe the same server port that the service resolves.

The environment is a fixed map. Each input is resolved three times: with the fixed map, with
`SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS` added, and with `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true`
added. The two operator overrides change which sandbox rules apply, so a single environment would
leave those branches unreachable.

`ConfigResolver` gains a constructor that takes a `ConfigResolver.SecretFiles` implementation. The
default `SecretFiles.HOST` keeps the old `Files.size` and `Files.readString` calls. The fuzz harness
passes in-memory files keyed by file name, including one that reports a size over the 64 KiB limit
and fails the run if it is ever read.

The first local fuzzing run found a crash within seconds: a YAML list with an empty item made
`ConfigResolver` throw `NullPointerException`. A targeted input replayed through the same harness
found a second: a NUL character in a path setting made it throw `InvalidPathException`. Both now fail
as `ConfigException`, recorded in `SPEC.md` Section 6.4.

### Consequences

* Good, because hosted fuzzing reaches the configuration classes with the existing corpus.
* Good, because no execution reads the process environment, a `.env` file, or a host file.
* Good, because `scripts/verify-clusterfuzzlite-coverage` now fails when the workflow target stops
  reaching `ConfigResolver.java`.
* Bad, because each workflow input now costs three resolutions. A 4-minute local Jazzer JUnit run
  dropped from 36,493 to 10,611 executions per second. The hosted target spends most of its time in
  the instrumented YAML library, so the hosted drop should be smaller. The first hosted batch after
  the merge shows the real figure.
* Bad, because `ConfigResolver` carries a public constructor whose only caller outside production is
  the fuzz harness.
* Neutral, because the in-memory seam means the harness never reaches device paths. `Files.size`
  returns 0 for `/dev/zero`, so a `file:` secret on a device path still reads without the size
  limit. [GitHub issue #834](https://github.com/martin-francois/symphony-trello/issues/834) tracks
  that production question.

### Confirmation

* `WorkflowLoaderFuzzTest` replays `oss-fuzz/corpora/WorkflowLoaderFuzzer` through `pom.xml` and
  calls `WorkflowConfigInvariants` for every loaded workflow.
* `scripts/verify-clusterfuzzlite-coverage` requires `ConfigResolver.java` lines in the
  `WorkflowLoaderFuzzer` report.
* `WorkflowConfigInvariants` passes only fixed environment maps and in-memory `SecretFiles` to
  `ConfigResolver`. A search for `LocalEnvironment` or `new ConfigResolver()` in
  `src/test/java/ch/fmartin/symphony/trello/fuzz` finds nothing.

## Pros and Cons of the Options

### Extend the workflow target to resolve every loaded workflow

After a successful load, the existing target also resolves and validates the configuration.

* Good, because the stored corpus and the matrix stay as they are.
* Good, because YAML still types the values, as it does in production.
* Bad, because the target does more work per input.

### Add a fifth standalone `WorkflowConfigFuzzer`

A new target parses YAML and resolves configuration on its own.

* Good, because the loader target keeps its execution rate.
* Bad, because it starts from seeds instead of the stored corpus.
* Bad, because it parses YAML again in a second target and needs a fifth matrix job and a new budget
  split.

### Generate the configuration map directly and skip YAML

A structured generator builds the `Map<String, Object>` that `ConfigResolver` reads.

* Good, because each execution reaches resolution without YAML parsing.
* Bad, because it misses how YAML types values, which `WorkflowConfigIngestion` depends on.
* Bad, because it needs a measured baseline first. It can follow under
  [GitHub issue #691](https://github.com/martin-francois/symphony-trello/issues/691).

### Keep `file:` secrets in a per-process temporary directory

The harness writes fixed secret files into a temporary directory it owns and points `file:`
references there.

* Good, because production code needs no change.
* Bad, because every execution with a `file:` reference reads the disk, which the file I/O check of
  [GitHub PR #787](https://github.com/martin-francois/symphony-trello/pull/787) reports.
* Bad, because a fuzzed absolute path such as `file:/etc/passwd` still reads a host file.

### Read `file:` secrets through an in-memory seam

`ConfigResolver` reads secret files through a small interface, and the harness passes an in-memory
implementation.

* Good, because no fuzzed path reaches the host file system.
* Good, because the size limit branch stays reachable through a fake size.
* Bad, because it adds a production interface for a test need.

## More Information

[GitHub PR #787](https://github.com/martin-francois/symphony-trello/pull/787) moves the workflow
target to in-memory parsing and adds `StandaloneFuzzerIsolationTest`. After both changes merge,
`WorkflowLoaderFuzzer` calls `WorkflowConfigInvariants` after `WorkflowLoaderInvariants`, and the
isolation test replays the seeds added here.
