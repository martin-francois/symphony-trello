---
status: accepted
date: 2026-07-05
decision-makers: [François Martin, Codex]
consulted:
  - "[ClusterFuzzLite running modes](https://google.github.io/clusterfuzzlite/running-clusterfuzzlite/)"
  - "[ClusterFuzzLite GitHub Actions](https://google.github.io/clusterfuzzlite/running-clusterfuzzlite/github-actions/)"
  - "[ClusterFuzzLite JVM integration](https://google.github.io/clusterfuzzlite/build-integration/jvm-lang/)"
  - "[ClusterFuzzLite parallel crash-reporting issue](https://github.com/google/clusterfuzzlite/issues/142)"
  - "[ClusterFuzzLite silent batch-failure issue](https://github.com/google/clusterfuzzlite/issues/149)"
  - "[ClusterFuzzLite coverage-output issue](https://github.com/google/clusterfuzzlite/issues/150)"
  - "[JaCoCo change history](https://www.jacoco.org/jacoco/trunk/doc/changes.html)"
  - "[GitHub Actions billing and usage](https://docs.github.com/en/actions/concepts/billing-and-usage)"
  - "[ADR 0061](0061-jazzer-and-oss-fuzz-readiness.md)"
informed: [Future maintainers, Contributors]
---

# Run ClusterFuzzLite in GitHub Actions

## Context and Problem Statement

ADR 0061 adds deterministic Jazzer regression tests and OSS-Fuzz-ready standalone fuzz targets.
Those checks protect pull requests and prepare the project for hosted OSS-Fuzz, but OSS-Fuzz
coverage does not start until the external project is accepted and enabled.

How should the project run coverage-guided fuzzing between public release and hosted OSS-Fuzz
acceptance without slowing required pull request CI, consuming limited Blacksmith capacity, or
creating a second target-packaging implementation?

## Decision Drivers

* Keep required pull request CI near the repository's five-minute target.
* Avoid using limited Blacksmith runner capacity for long-running fuzzing.
* Reuse the standalone JVM fuzz targets and packaging logic prepared for OSS-Fuzz.
* Carry useful corpus inputs into later runs through a dedicated public Git repository.
* Preserve crash reproducers and publish findings through native GitHub surfaces.
* Keep repository-specific fuzz orchestration small, versioned, and independently testable.
* Stay below the GitHub-hosted job execution limit.

## Considered Options

* Scheduled ClusterFuzzLite batch fuzzing.
* One combined batch action for all fuzz targets.
* One ClusterFuzzLite batch matrix job per fuzz target.
* Custom scheduled Jazzer JUnit workflow.
* Required pull request continuous fuzzing.
* Blacksmith-hosted scheduled fuzzing.
* Wait for OSS-Fuzz before running continuous fuzzing.
* Self-hosted runner.
* Derive the pinned ClusterFuzzLite runner with the project's JaCoCo version.
* Bind-mount replacement JaCoCo JARs into the official runner.
* Wait for ClusterFuzzLite to update its runner's JaCoCo version.
* Reimplement JVM coverage publication outside ClusterFuzzLite.

## Decision Outcome

Chosen option: ClusterFuzzLite code-change, continuous-build, batch, prune, and coverage operations.

The repository adds `.clusterfuzzlite/` build integration and
`.github/workflows/continuous-fuzzing.yml`. ClusterFuzzLite uses Jazzer for JVM projects and reuses
the OSS-Fuzz build model. The ClusterFuzzLite build script delegates to `oss-fuzz/build.sh`, so
scheduled and future hosted fuzzing package the same four standalone `fuzzerTestOneInput` targets.
Each target starts with a small checked-in seed corpus covering valid and malformed forms from its
input grammar. ClusterFuzzLite then persists newly discovered corpus inputs between runs.

Every fuzzing job uses `ubuntu-latest`, not a Blacksmith runner. Batch fuzzing runs on `main` every
six hours as a four-target matrix. Each non-midnight target receives a 4,950-second active budget,
for a 19,800-second aggregate budget, and each job has a 100-minute timeout. The midnight run gives
each target 4,500 seconds, for an 18,000-second aggregate budget, so prune and coverage jobs can
follow before the next batch. Each target's corpus is stored in the dedicated Git storage repository
for later runs.

Each matrix runner removes the other three standalone fuzzer wrappers before starting batch mode.
ClusterFuzzLite continues after a nonfinal batch target crashes but writes SARIF only for the final
target it ran. Selecting one target per action invocation makes every reportable crash the final
result for that invocation. It also gives each SARIF upload a target-specific category, so parallel
uploads do not replace one another in code scanning. A tested repository script performs only the
wrapper selection; ClusterFuzzLite still owns execution, minimization, corpus persistence, crash
artifacts, and SARIF generation.

Parallel fuzzing is disabled. ClusterFuzzLite issue 142 documents that libFuzzer workers can find
crashes and then time out before ClusterFuzzLite reports them. Reliable failure reporting is more
valuable here than using every available core.

Code-change mode runs for five minutes on pull requests. A continuous build is retained after every
push to `main`, allowing code-change mode to suppress crashes that reproduce against the baseline.
Coverage mode runs daily, publishes HTML coverage to the storage repository, and provides the data ClusterFuzzLite
uses to select fuzzers affected by a change. These extra jobs are advisory fuzzing checks and do not
replace the repository's short required verification gate.

The ClusterFuzzLite `v1` runner image bundles JaCoCo 0.8.7. That version rejects the project's Java
25 class files, while ClusterFuzzLite ignores the internal coverage command's nonzero exit and
uploads the partial directory. Coverage therefore runs in a digest-pinned derivative of the same
ClusterFuzzLite runner image. The derivative replaces only the JaCoCo agent and CLI with the version
declared by `jacoco.version` in `pom.xml`; Maven resolves both artifacts before the image is built.
ClusterFuzzLite continues to own corpus retrieval, coverage execution, and storage publication. The
repository verifier requires the JVM HTML report, JaCoCo XML, aggregate summary, and every target's
summary. It downloads every published artifact, rejects empty files or malformed JaCoCo XML,
confirms that the aggregate contains covered source files, and confirms that each target covers
lines in its intended production resolver, parser, classifier, or loader. The wrapper preserves
the useful part of ClusterFuzzLite's low-disk cleanup without mounting the host's privileged
container-runtime socket. Coverage mode bypasses ClusterFuzzLite's per-target cleanup, and the
host-launched runner cannot delete host images. On GitHub Actions, the wrapper therefore enumerates
images by repository, digest, and ID. It removes only the digest-pinned JVM builder used by this
checkout and ClusterFuzzLite's per-build `external-cfl-project-*` image. It does not prune the generic
builder cache or remove other language images. Local Docker and Podman runs still build and tag the
derivative coverage image, but they do not delete existing images or prune cache. The wrapper honors
the repository-wide Docker or Podman runtime selection, so maintainers can run the same coverage
path locally.

ClusterFuzzLite requires corpus pruning when batch fuzzing is enabled. A separate job therefore runs
daily in `prune` mode with a ten-minute budget. Batch, prune, coverage, and baseline-build jobs can
be dispatched manually. Manual batch runs default to five minutes so maintainers can verify the
hosted integration without waiting for a scheduled run.

When a fuzzer finds a reportable crash, ClusterFuzzLite minimizes it, uploads the reproducer as a
GitHub Actions crash artifact, and returns a failing status. The workflow uploads the generated SARIF
result to GitHub code scanning. Scheduled batch failures also run the repository-owned reporting
script, which fingerprints the normalized SARIF result and creates or updates a `bug` + `fuzzed`
issue. If Jazzer fails without a source-located SARIF result, the script fingerprints the matrix
target and records the exact crash artifact so Java exceptions, timeouts, and out-of-memory exits
still create or update an issue. Pull-request failures remain checks: same-repository pull requests also publish SARIF, while
fork pull requests retain the failed check and crash artifact without attempting an unauthorized
code-scanning upload.

The ClusterFuzzLite actions are pinned to the commit behind the upstream `v1` tag. The action's own
container image remains selected by its upstream `v1` image tag; that indirection is part of the
upstream action implementation and cannot be pinned by callers.

The JVM builder copies Java 25 from a versioned, digest-pinned Eclipse Temurin image instead of
downloading the moving `latest/25/ga` archive. Dockerfile's built-in Renovate manager owns the image
tag and digest, so Java updates follow the repository's dependency cooldown and review rules.

ClusterFuzzLite persists corpora on `main` and coverage on `gh-pages` in the dedicated public
`martin-francois/symphony-trello-fuzzing-storage` repository. Pull-request jobs read that public data
without receiving its write credential. Trusted batch, prune, and coverage jobs receive the
`CLUSTERFUZZLITE_STORAGE_TOKEN` repository secret only in storage-write and storage-verification
steps. The batch matrix runs one writer at a time because ClusterFuzzLite pushes every target corpus
to one branch. Repository-owned verification steps check the expected corpus or report path after
each successful storage operation so an upload error cannot leave the workflow green. Baseline builds
and crash reproducers use GitHub Actions artifacts.

This workflow does not replace hosted OSS-Fuzz. Hosted OSS-Fuzz remains the long-term service because
it provides dedicated infrastructure, triage, and broader continuous operation. ClusterFuzzLite is
the repository-owned bridge and continues to use the same fuzz targets.

### Consequences

* Good, because long fuzzing runs do not slow required pull request CI.
* Good, because Blacksmith minutes remain reserved for short verification jobs.
* Good, because corpora persist across runs and improve later fuzzing coverage.
* Good, because daily pruning prevents redundant corpus inputs from growing without bound.
* Good, because pull requests receive code-change fuzzing backed by baseline builds and coverage
  data.
* Good, because daily HTML coverage makes fuzz target reach visible without a separate service.
* Good, because crash reproducers and SARIF findings use native GitHub artifacts and code scanning.
* Good, because every target gets complete crash reporting despite ClusterFuzzLite's combined-batch
  SARIF limitation.
* Good, because repository-owned shell logic is limited to tested target selection,
  Java-compatible coverage runner preparation, storage verification, and SARIF-to-issue reporting.
* Neutral, because GitHub scheduled workflows do not guarantee an exact start time.
* Good, because scheduled crash findings create or update deduplicated GitHub issues.
* Neutral, because fork pull requests cannot publish SARIF and rely on their failed check and crash
  artifact.
* Good, because the separate storage repository retains corpus history and serves the latest
  coverage report through GitHub Pages.
* Bad, because the external storage write depends on a long-lived cross-repository token. The token
  should be replaced with a repository-scoped credential when GitHub provides one that the upstream
  Docker action can use non-interactively.
* Neutral, because the action's upstream container image uses the mutable `v1` tag internally.
* Bad, because the matrix builds the JVM fuzzers four times per batch instead of once.
* Bad, because serializing storage writers increases batch wall time. It prevents lost corpora until
  ClusterFuzzLite provides atomic concurrent corpus updates or the workflow gains an aggregation job.
* Bad, because ClusterFuzzLite issue 149 reports that a batch target can time out or exhaust memory
  without failing the overall job. Post-merge checks must inspect each target's log instead of
  treating a green job as sufficient evidence.
* Bad, because ClusterFuzzLite issue 150 reports regressions where coverage mode does not retain the
  generated HTML. Post-merge checks must confirm that `gh-pages` contains the expected report and
  that GitHub Pages serves it.
* Bad, because coverage builds a small derivative runner image before each run. Resolving two JARs
  and adding one image layer costs time but keeps the runtime contents explicit and reproducible.

### Confirmation

Run the repository checks:

```bash
scripts/check-shell-scripts syntax
scripts/check-shell-scripts shellcheck
pnpm run verify:scripts
./mvnw -q -Dtest=ContinuousFuzzingWorkflowTest test
./mvnw -q spotless:check verify
```

After the workflow reaches `main`, dispatch a short hosted batch run and confirm that all four
standalone fuzz targets build and run:

```bash
gh workflow run continuous-fuzzing.yml --ref main \
  -f operation=batch \
  -f fuzz_seconds=60
```

Then dispatch coverage and inspect the published report. The initial acceptance criterion is that
all four fuzzer reports exist and each reaches the production parser, classifier, resolver, or
loader it targets. Record the first report as the baseline. A target that has zero or visibly
shallow reach into its intended entry point needs better seeds or another target before the hosted
setup counts as healthy. Whole-application line coverage is not the target because these fuzzers
deliberately cover untrusted parsing boundaries, not network and orchestration code.

Inspect the per-target batch logs for timeouts and out-of-memory exits even when the batch job is
green. Confirm that the storage repository's `gh-pages` branch contains
`coverage/latest/report/linux/index.html` and that GitHub Pages serves that file.

## Pros and Cons of the Options

### Scheduled ClusterFuzzLite Batch Fuzzing

Use the official ClusterFuzzLite actions and the repository's standalone OSS-Fuzz targets for
code-change, continuous-build, batch, prune, and coverage operations.

* Good, because the implementation includes corpus persistence, pruning, crash minimization,
  artifacts, and SARIF reporting.
* Good, because it exercises the same standalone target boundary intended for hosted OSS-Fuzz.
* Good, because the separate storage repository retains corpora and makes coverage browsable.
* Bad, because cross-repository writes require a separately managed credential.
* Bad, because the repository depends on ClusterFuzzLite's action containers and artifact protocol.

### One Combined Batch Action for All Fuzz Targets

Build the four targets once and let one ClusterFuzzLite batch invocation divide the budget among
them.

* Good, because one build leaves more runner time for fuzzing.
* Bad, because ClusterFuzzLite writes SARIF only for the final target, so a crash in an earlier target
  does not reliably reach issue reporting or code scanning.

### One ClusterFuzzLite Batch Matrix Job per Fuzz Target

Build the same output in four GitHub-hosted jobs, keep one target wrapper in each job, and run the
official batch action once per target.

* Good, because every target produces an independent status, crash artifact, and SARIF result.
* Good, because separate target categories preserve all four code-scanning analyses.
* Good, because the jobs can run concurrently without enabling libFuzzer worker parallelism.
* Bad, because repeated image and Maven builds consume more runner minutes.

### Custom Scheduled Jazzer JUnit Workflow

Invoke each JUnit fuzz method from repository-owned shell code and create deduplicated issues.

* Good, because Maven commands are easy to reproduce without the OSS-Fuzz container toolchain.
* Good, because repository-owned code can choose a custom issue format.
* Bad, because it duplicates target discovery, scheduling, failure capture, minimization, corpus
  storage, and reporting behavior already implemented by ClusterFuzzLite.
* Bad, because its JUnit targets differ from the standalone targets prepared for hosted OSS-Fuzz.
* Bad, because without persistent corpora each scheduled run discards useful discovered inputs.

### Required Pull Request Continuous Fuzzing

Run coverage-guided fuzzing as part of every pull request.

* Good, because pull requests receive fuzzing feedback before merge.
* Bad, because long fuzzing contradicts the five-minute required CI target.
* Bad, because it makes ordinary contributions slower and more expensive to review.

### Blacksmith-Hosted Scheduled Fuzzing

Use the same Blacksmith runner class as ordinary CI for scheduled fuzzing.

* Good, because runner behavior stays close to normal CI.
* Bad, because Blacksmith capacity is limited and reserved for short verification jobs.

### Wait for OSS-Fuzz

Do not add a scheduled workflow and rely on hosted OSS-Fuzz after acceptance.

* Good, because OSS-Fuzz is the preferred long-term platform.
* Bad, because active coverage remains absent until the external project is accepted and running.

### Self-Hosted Runner

Run fuzzing continuously on a maintainer-owned host.

* Good, because it can run beyond GitHub-hosted job limits.
* Bad, because it adds host security, credentials, patching, and operational ownership.

### Derive the Pinned ClusterFuzzLite Runner With the Project's JaCoCo Version

Build a local image from the pinned official runner digest and replace only `/opt/jacoco-agent.jar`
and `/opt/jacoco-cli.jar` with Maven-resolved artifacts at the version declared in `pom.xml`.

* Good, because ClusterFuzzLite still owns corpus retrieval, coverage execution, and publication.
* Good, because the image build validates the JaCoCo version before coverage starts.
* Good, because the project and fuzzing coverage use one JaCoCo version selected by Renovate.
* Good, because GitHub-hosted cleanup frees this JVM build's images without exposing the privileged
  runtime socket or changing a local developer's image cache.
* Bad, because the workflow must reproduce the official action's container environment.
* Bad, because each coverage run spends time resolving the artifacts and building one image layer.

### Bind-Mount Replacement JaCoCo JARs Into the Official Runner

Run the pinned official image directly and mount the current JaCoCo agent and CLI over its bundled
files.

* Good, because it avoids building a derivative image.
* Bad, because individual file mounts add host-path and Podman labeling differences to the coverage
  runtime.
* Bad, because the effective image contents are less obvious from an image inspection or build log.

### Wait for ClusterFuzzLite to Update Its Runner's JaCoCo Version

Keep the official action unchanged and accept missing Java coverage until upstream publishes a
runner that supports Java 25.

* Good, because the repository would not own a coverage wrapper.
* Bad, because the current workflow remains green while publishing an unusable partial report.
* Bad, because the project has no release date or compatibility commitment from upstream.

### Reimplement JVM Coverage Publication Outside ClusterFuzzLite

Run Jazzer and JaCoCo directly in repository scripts, generate the report, and push it to the storage
repository without the ClusterFuzzLite runner.

* Good, because the repository controls every coverage command and failure status.
* Bad, because it duplicates ClusterFuzzLite's corpus download, target execution, report generation,
  and storage protocol.
* Bad, because a second coverage path can drift from code-change target selection and hosted
  OSS-Fuzz packaging.

## More Information

Keep required CI deterministic and short. If hosted OSS-Fuzz becomes active and provides equivalent
coverage, reassess whether the ClusterFuzzLite schedule still justifies its runner use. Prefer
reducing or removing duplicate scheduled work over adding continuous fuzzing to pull request CI.
