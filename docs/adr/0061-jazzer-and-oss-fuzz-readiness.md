---
status: accepted
date: 2026-07-04
decision-makers: [François Martin, Codex]
consulted:
  - "[Jazzer README](https://github.com/CodeIntelligenceTesting/jazzer)"
  - "[Jazzer FuzzTest Javadocs](https://codeintelligencetesting.github.io/jazzer-docs/jazzer-junit/com/code_intelligence/jazzer/junit/FuzzTest.html)"
  - "[OSS-Fuzz JVM Integration Guide](https://google.github.io/oss-fuzz/getting-started/new-project-guide/jvm-lang/)"
  - "[jqwik User Guide](https://jqwik.net/docs/current/user-guide.html)"
  - "[ADR 0011](0011-test-quality-and-coverage-policy.md)"
informed: [Future maintainers, Contributors]
---

# Add Jazzer and OSS-Fuzz Readiness

## Context and Problem Statement

Symphony for Trello parses untrusted Trello text, workflow Markdown, repository source declarations,
URLs, and external service payloads. Example-based tests cover the known paths, but they do not
explore malformed input space deeply. Live Trello bugbashes catch real integration bugs, but they are
slow, depend on credentials, and are not deterministic enough to be the first line of defense.

How should the project add fuzzing and chaos-style coverage while keeping normal Maven verification
deterministic?

## Decision Drivers

* Exercise parser and boundary invariants with many generated inputs.
* Keep normal `./mvnw -q spotless:check verify` deterministic and credential-free.
* Make crash regressions easy to keep in the ordinary test suite.
* Prepare the repository for an OSS-Fuzz project submission without forcing that external submission
  into this pull request.
* Avoid live Trello, GitHub, or Codex dependencies in the fuzzing gate.
* Keep the new tooling small enough for contributors to run locally.

## Considered Options

* Jazzer JUnit tests plus OSS-Fuzz wrapper files.
* Example-based chaos tests only.
* A Java property-testing library such as jqwik or QuickTheories.
* OSS-Fuzz-only standalone targets.
* Live Trello bugbash coverage only.

## Decision Outcome

Chosen option: Jazzer JUnit tests plus OSS-Fuzz wrapper files.

The project adds `com.code-intelligence:jazzer-junit` as a test dependency. Focused `@FuzzTest`
classes run in Jazzer regression mode during ordinary Maven tests. Their `@MethodSource` values are
deterministic regression seeds in normal test mode and seed inputs for active Jazzer mutation runs.
Repository-source and Trello-reference fuzz tests attach those providers directly to `@FuzzTest`.
The workflow-loader fuzz test uses `FuzzedDataProvider.consumeBytes(...)` instead because its
existing corpus is raw byte data and a mutation-framework wrapper would require changing that corpus
format; the curated workflow seeds stay as deterministic parameterized regression tests. CI also runs
those fuzz tests and the chaos tests through the normal `verify` job. Active fuzzing remains opt-in
through `JAZZER_FUZZ=1` and a Maven `fuzzing` profile that disables JUnit parallel execution because
Jazzer fuzzing mode runs one target per JVM.
Scheduled coverage-guided fuzzing from `main` is handled separately by
[ADR 0062](0062-github-actions-continuous-fuzzing.md) so long-running fuzzing does not become part of
required pull request CI.

OSS-Fuzz entry points live under `src/test/java/ch/fmartin/symphony/trello/fuzz` so they compile with
the same code and dependencies as the tests. These standalone `fuzzerTestOneInput` classes are the
targets wrapped by OSS-Fuzz; they cover repository-source selection, workflow loading, Trello card
reference parsing, and prerequisite checklist classification. The `oss-fuzz/` directory contains the
project metadata, builder Dockerfile, and wrapper script needed for an upstream `google/oss-fuzz`
project submission. The Dockerfile is shaped for the upstream OSS-Fuzz project directory: it clones
this repository, installs and selects a Java 25 JDK, and then runs the wrapper with Maven inside the
OSS-Fuzz JVM builder image. OSS-Fuzz builders are external to this repository's normal developer
workflow; contributors should still use `./mvnw` in this checkout.

The prepared OSS-Fuzz project metadata uses `file_github_issue: true` so OSS-Fuzz findings are
mirrored to GitHub issues for project visibility. Those GitHub issues are not the source of the full
private crash report; maintainers still need OSS-Fuzz tracker access for details. The configured
primary contact, `oss-fuzz@fmartin.ch`, must be a real Google account rather than only a mail alias
because ClusterFuzz and the OSS-Fuzz tracker require Google-account authentication.

The OSS-Fuzz wrapper follows the standalone JVM target pattern from the OSS-Fuzz JVM guide: generated
launchers call OSS-Fuzz's `jazzer_driver` with `jazzer_agent_deploy.jar`, the computed classpath, and
`LD_LIBRARY_PATH` including `JVM_LD_LIBRARY_PATH`. This matches the current pure Java wrapper pattern
used by OSS-Fuzz JVM projects such as apache-commons-codec, apache-commons-compress, antlr4-java,
json-sanitizer, jsoup, spring-retry, and spring-data-jpa. The script copies Maven runtime-scope
dependencies only and keeps Maven's Jazzer, JUnit, Surefire, Mockito, and AssertJ jars out of
`$OUT/lib`. The standalone fuzzer classes are compiled by Maven with the repository's test-scoped
Jazzer dependency, which provides stable API types such as `FuzzedDataProvider` at compile time. The
generated wrappers do not ship Maven's Jazzer or JUnit runtime stack; they launch OSS-Fuzz's
`jazzer_driver` and pass OSS-Fuzz's supplied `jazzer_agent_deploy.jar` through `--agent_path` at
runtime. The wrapper also copies the Java 25 runtime into `$OUT` and sets `JAVA_HOME` and `PATH` for
the launcher because Symphony for Trello is compiled with Java 25 while the runner image does not
promise that runtime. A direct `docker build` of `oss-fuzz/Dockerfile` is not sufficient validation;
it only checks the builder image and clone step. The real validation path uses `google/oss-fuzz`
helper commands to build the image, build fuzzers, run `check_build`, and smoke-run the generated
fuzzers in the base-runner environment.

Deterministic chaos tests complement Jazzer by simulating malformed Trello responses, rate limits,
and write-response parsing failures with the in-repository fake Trello server. These tests avoid
network access and assert that writes are not retried or performed when validation has failed.

### Consequences

* Good, because normal Maven verification and CI now include deterministic fuzz regression coverage.
* Good, because active local fuzzing has documented 15- to 30-minute and longer-run commands without
  making continuous fuzzing a contributor requirement.
* Good, because [ADR 0062](0062-github-actions-continuous-fuzzing.md) defines the interim
  GitHub-hosted scheduled fuzzing loop until OSS-Fuzz is active.
* Good, because OSS-Fuzz readiness is reviewable before an external `google/oss-fuzz` pull request.
* Good, because OSS-Fuzz validation uses the same helper path that upstream reviewers use, not only a
  direct Docker build.
* Good, because external-boundary chaos cases do not need live credentials.
* Neutral, because active fuzzing remains opt-in and runs one target per Maven invocation.
* Neutral, because local validation of unpushed OSS-Fuzz changes may need a temporary local-source
  override.
* Bad, because the project has another test dependency and a small amount of OSS-Fuzz-specific
  wrapper code to keep current.
* Bad, because the OSS-Fuzz files are not proof of hosted coverage until the external OSS-Fuzz
  project exists and runs successfully.

### Confirmation

Run:

```bash
./mvnw -q -Dtest=RepositorySourceResolverFuzzTest,TrelloCardReferenceParserFuzzTest,WorkflowLoaderFuzzTest,TrelloClientChaosTest test
JAZZER_FUZZ=1 ./mvnw -q -Pfuzzing -Djacoco.skip=true -Dtest=RepositorySourceResolverFuzzTest#labelledRepositorySourceValueCannotBreakSelectionInvariants test
./mvnw -q spotless:check verify
bash -n oss-fuzz/build.sh
```

For OSS-Fuzz compatibility, copy the `oss-fuzz/` project files into a current `google/oss-fuzz`
checkout and run:

```bash
python3 infra/helper.py build_image --no-pull symphony-trello
python3 infra/helper.py build_fuzzers --sanitizer address symphony-trello
python3 infra/helper.py check_build symphony-trello
mkdir -p /tmp/symphony-trello-{RepositorySourceFuzzer,WorkflowLoaderFuzzer,TrelloCardReferenceParserFuzzer,TrelloChecklistClassifierFuzzer}
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-RepositorySourceFuzzer symphony-trello RepositorySourceFuzzer -- -runs=100
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-WorkflowLoaderFuzzer symphony-trello WorkflowLoaderFuzzer -- -runs=100
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-TrelloCardReferenceParserFuzzer symphony-trello TrelloCardReferenceParserFuzzer -- -runs=100
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-TrelloChecklistClassifierFuzzer symphony-trello TrelloChecklistClassifierFuzzer -- -runs=100
```

Also run markdownlint for the changed Markdown files, private-context scanning for changed files, and
`git diff --check`.

## Pros and Cons of the Options

### Jazzer JUnit Tests Plus OSS-Fuzz Wrapper Files

Use Jazzer's JUnit integration for deterministic regression-mode fuzz tests and keep OSS-Fuzz
entry-point classes and builder files in the repository.

* Good, because tests can run in the existing Maven/JUnit flow.
* Good, because crash inputs can be promoted to test resources and run without special tooling.
* Good, because OSS-Fuzz target code compiles with the repository.
* Good, because the OSS-Fuzz wrapper follows the current standalone JVM target pattern and can be
  tested with `infra/helper.py`.
* Bad, because active fuzzing still needs a separate `JAZZER_FUZZ=1` command for each target.
* Bad, because OSS-Fuzz wrapper scripts add external-platform conventions to the repository.

### Example-Based Chaos Tests Only

Add deterministic tests for hand-picked malformed payloads and retry edge cases without a fuzzing
engine.

* Good, because this is simple and stays entirely inside existing JUnit patterns.
* Good, because each test has a named product scenario.
* Bad, because it only checks inputs maintainers thought of in advance.
* Bad, because parser and URL edge cases remain easy to miss.

### Java Property-Testing Library

Use a library such as jqwik or QuickTheories to generate inputs and assert invariants.

* Good, because property tests can be expressive and deterministic.
* Good, because they integrate with Java test suites.
* Bad, because jqwik was considered but excluded: its user guide states that jqwik is not meant to be
  used by AI coding agents at all, while this repository explicitly uses AI-assisted development.
* Bad, because jqwik property tests do not fit the OSS-Fuzz JVM integration path: OSS-Fuzz JVM
  fuzzing depends on Jazzer and libFuzzer-compatible fuzz targets, not JUnit property-test engines.
* Bad, because it adds a different generator model while Jazzer already targets JVM fuzzing and
  OSS-Fuzz integration.

### OSS-Fuzz-Only Standalone Targets

Add only `fuzzerTestOneInput` classes and OSS-Fuzz project files, without JUnit fuzz tests.

* Good, because hosted fuzzing targets can stay small.
* Bad, because normal Maven verification would not exercise the fuzz invariants.
* Bad, because crash regressions would be easier to leave outside the ordinary test suite.

### Live Trello Bugbash Coverage Only

Rely on periodic real Trello/GitHub/Codex bugbashes to find integration and parser problems.

* Good, because live runs catch issues fake tests cannot reproduce.
* Bad, because live runs are slow, expensive, credential-dependent, and not deterministic.
* Bad, because they do not give contributors fast local feedback for malformed input handling.

## More Information

The first fuzz targets cover repository source selection, Trello card reference parsing, checklist
classification, and workflow loading. Future targets should focus on parsers and public boundaries
where arbitrary input can cross into prompt text, Trello writes, filesystem paths, or generated
configuration. Hosted OSS-Fuzz setup still requires a separate upstream project submission.
