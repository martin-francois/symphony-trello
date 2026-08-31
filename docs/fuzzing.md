# Fuzzing

Symphony for Trello uses Jazzer for parser and boundary fuzzing. Normal Maven verification runs the
Jazzer tests in regression mode, so fixed crash inputs stay part of the regular test suite.

Run the focused fuzzing and chaos tests:

```bash
./mvnw -q -Dtest=RepositorySourceResolverFuzzTest,TrelloCardReferenceParserFuzzTest,WorkflowLoaderFuzzTest,TrelloClientChaosTest test
```

Run one target in active fuzzing mode:

```bash
JAZZER_FUZZ=1 ./mvnw -q -Pfuzzing -Djacoco.skip=true -Dtest=RepositorySourceResolverFuzzTest#labelledRepositorySourceValueCannotBreakSelectionInvariants test
```

Jazzer executes only one fuzz test per Maven run when `JAZZER_FUZZ=1` is set. Use separate commands
for separate targets. The `fuzzing` profile disables JUnit parallel execution so the selected target
has a quiet process.

Pull request CI runs the Jazzer fuzz tests in deterministic regression mode through the normal
`verify` job. It does not run continuous coverage-guided fuzzing. That keeps pull request feedback
within the repository's fast CI target while still failing when fixed crash inputs regress or parser,
workflow, or Trello boundary changes break the fuzz tests.

The separate `Continuous Fuzzing` GitHub Actions workflow runs ClusterFuzzLite batch fuzzing from
`main` on GitHub-hosted runners. It is scheduled every six hours with a 330-minute aggregate fuzzing
budget except for the midnight run, which uses 300 aggregate minutes so daily maintenance can follow
it before the next batch. A four-target matrix assigns 82.5 minutes to each target, or 75 minutes at
midnight, and each matrix job has a 100-minute timeout. It intentionally uses `ubuntu-latest`, not a
Blacksmith runner. Each runner keeps one fuzzer wrapper before invoking ClusterFuzzLite. This works
around ClusterFuzzLite's combined-batch SARIF limitation and guarantees that a crash in any target has
its own report. The jobs carry each target's corpus into later runs through the storage repository and
prune redundant corpus inputs after the midnight batch. A reportable batch crash fails its matrix job,
uploads the reproducer as a crash artifact, publishes a SARIF result to code scanning, and creates or
updates a deduplicated `bug` + `fuzzed` issue. Jazzer failures without a source-located SARIF result,
including timeouts and out-of-memory exits, use the matrix target and crash artifact as the fallback
issue identity instead of skipping issue creation.

ClusterFuzzLite also runs five-minute code-change fuzzing on pull requests and retains a baseline
fuzzer build after each push to `main`. The baseline lets code-change mode distinguish crashes
introduced by a pull request from crashes already present on `main`. Daily coverage generation uses
the accumulated corpora both to publish HTML coverage and to identify which fuzzers a pull request
affects. Same-repository pull requests publish crash SARIF to code scanning. Fork pull requests do
not have the required code-scanning permission, so their failures remain visible through the failed
check and ClusterFuzzLite crash artifact. These jobs also use `ubuntu-latest`; continuous fuzzing
never consumes a Blacksmith runner.

The `.clusterfuzzlite/` build integration copies the checked-out source into the JVM builder and
delegates to `oss-fuzz/build.sh`. The shared build script keeps local OSS-Fuzz, future hosted
OSS-Fuzz, and scheduled ClusterFuzzLite runs on the same target packaging path. Each target also has
a checked-in seed corpus under `oss-fuzz/corpora/`. These seeds cover representative repository
declarations, Trello references and checklist forms, and valid and invalid workflow front matter so
coverage-guided mutation starts inside useful parser paths.

ClusterFuzzLite stores corpora and coverage in the public
[`symphony-trello-fuzzing-storage`](https://github.com/martin-francois/symphony-trello-fuzzing-storage)
repository. Pull-request jobs use unauthenticated read access. Jobs on `main` use the
`CLUSTERFUZZLITE_STORAGE_TOKEN` repository secret to update the `main` corpus branch and the
`gh-pages` coverage branch. Baseline builds and crash reproducers remain GitHub Actions artifacts in
this repository. The secret MUST retain write access to the storage repository. Replace the current
token with a repository-scoped GitHub App or fine-grained token when one is available.
After the first coverage run and GitHub Pages activation, the latest report is available at
`https://martin-francois.github.io/symphony-trello-fuzzing-storage/coverage/latest/report/linux/report.html`.

After the workflow is present on `main`, maintainers can smoke-test the complete hosted path with a
short manual batch run:

```bash
gh workflow run continuous-fuzzing.yml --ref main \
  -f operation=batch \
  -f fuzz_seconds=60
```

The manual `fuzz_seconds` value applies to each of the four batch matrix jobs.

Use `operation=prune`, `operation=coverage`, or `operation=build` to test the corpus-pruning,
coverage, or baseline-build paths. The `fuzz_seconds` input applies only to batch runs.

The first hosted coverage report establishes the baseline. A healthy report contains all four
fuzzers and shows each one reaching its intended production entry point. If a target has zero or
visibly shallow reach, add a representative seed or a focused standalone target before accepting
the hosted setup. Do not use whole-application line coverage as the threshold: these fuzzers cover
untrusted parsing boundaries, while network and orchestration behavior belongs in deterministic
tests. During post-merge verification, inspect every target's batch log for timeout or out-of-memory
exits even when the job is green, and confirm that the `gh-pages` branch contains and serves the HTML
report. These checks cover open ClusterFuzzLite issues
[#149](https://github.com/google/clusterfuzzlite/issues/149) and
[#150](https://github.com/google/clusterfuzzlite/issues/150).

For a 15- to 30-minute active fuzzing pass, run the public parser targets one at a time with an
explicit duration:

```bash
set -euo pipefail

targets=(
  'RepositorySourceResolverFuzzTest#labelledRepositorySourceValueCannotBreakSelectionInvariants'
  'RepositorySourceResolverFuzzTest#cardTextDeclarationScanCannotBreakSelectionInvariants'
  'TrelloCardReferenceParserFuzzTest#trelloReferenceParsingKeepsLookupIdsAndUrlsStable'
  'TrelloCardReferenceParserFuzzTest#checklistClassificationNeverEmitsPrerequisitesWithProblems'
  'WorkflowLoaderFuzzTest#workflowLoaderHandlesArbitraryWorkflowBytes'
)

for target in "${targets[@]}"; do
  JAZZER_FUZZ=1 ./mvnw -q -Pfuzzing -Djacoco.skip=true -Djazzer.max_duration=4m -Djazzer.max_executions=0 "-Dtest=$target" test
done
```

The `-Djazzer.max_duration=4m` value applies to one selected target in one Maven process. The loop
above runs five separate targets, so it can take roughly 20 minutes plus Maven startup time; it is
not a four-minute total suite limit.

For a longer or continuous agent-requested run, choose one target and a longer duration such as
`-Djazzer.max_duration=5h -Djazzer.max_executions=0`. The execution override lets the duration,
rather than the method-level regression cap, decide when the fuzzing process stops. Stop the command
when the requested window ends. Contributors are not expected to run this before every pull request;
use it when touching parser, prompt-line safety, workflow loading, or Trello reference/checklist
parsing logic.

Committed fuzz tests should stay deterministic in regression mode. If Jazzer finds a crash, keep the
generated input only after checking that it contains no private context and after moving it into the
matching `src/test/resources/<package-path>/<TestClassName>Inputs/<method>` directory. For example,
`RepositorySourceResolverFuzzTest` inputs live under
`src/test/resources/ch/fmartin/symphony/trello/repository/RepositorySourceResolverFuzzTestInputs/<method>`.
File fuzz-found bugs with the `bug` and `fuzzed` GitHub labels, include the minimized input or
reproduction bytes, and state which fuzz target found the issue.

The `oss-fuzz/` directory contains the files needed for an OSS-Fuzz project submission. They package
the compiled Maven test fuzzers from `src/test/java/ch/fmartin/symphony/trello/fuzz` into OSS-Fuzz
wrappers. The files are kept here so the project can review and test them before they are copied into
the upstream `google/oss-fuzz` repository.

The prepared `project.yaml` uses `primary_contact: "oss-fuzz@fmartin.ch"` and
`file_github_issue: true`. The contact address must be a real Google account, not only a forwarding
alias, so the maintainer can access ClusterFuzz and OSS-Fuzz issue details. GitHub issue mirroring is
enabled for project visibility, but detailed crash reports still live in the OSS-Fuzz tracker and may
require OSS-Fuzz access.

The JUnit fuzz tests and standalone OSS-Fuzz targets are related but separate:

- `RepositorySourceResolverFuzzTest`, `TrelloCardReferenceParserFuzzTest`, and
  `WorkflowLoaderFuzzTest` run in Maven. Their `@MethodSource` values are deterministic regression
  seeds in normal test mode. Repository-source and Trello-reference seed providers are attached
  directly to their `@FuzzTest` methods and become seed inputs for active local Jazzer mutation runs.
  `WorkflowLoaderFuzzTest` uses `FuzzedDataProvider.consumeBytes(...)` for active fuzzing so the
  byte-size cap is enforced while the existing raw byte crash corpus stays valid; its curated
  workflow seeds remain deterministic parameterized regression tests.
- `RepositorySourceFuzzer`, `WorkflowLoaderFuzzer`, `TrelloCardReferenceParserFuzzer`, and
  `TrelloChecklistClassifierFuzzer` are standalone `fuzzerTestOneInput` entry points. OSS-Fuzz wraps
  and runs these classes from compiled test output.

The OSS-Fuzz runtime classpath is deliberately narrower than Maven's test classpath. The build
script copies production classes to `$OUT/classes`, only the standalone fuzzer classes and their
shared invariant helper to `$OUT/test-classes`, and Maven runtime-scope dependency jars to
`$OUT/lib`. It fails if `$OUT/lib` contains Jazzer, JUnit, Surefire, Mockito, AssertJ, or similar
test-runner-only jars.

The standalone fuzzer classes are compiled by Maven with the repository's local test-scoped Jazzer
dependency. That gives the source code stable Jazzer API types such as `FuzzedDataProvider` at
compile time. Maven's Jazzer and JUnit jars are intentionally not copied into `$OUT/lib`. At runtime,
the generated wrappers launch OSS-Fuzz's `jazzer_driver`, pass OSS-Fuzz's
`jazzer_agent_deploy.jar` through `--agent_path`, and use only project classes, standalone fuzzer
classes, OSS-Fuzz's agent jar, and the curated runtime dependency jars on the wrapper classpath.

The Dockerfile is written for the upstream OSS-Fuzz project context, where `Dockerfile` and
`build.sh` are copied into `google/oss-fuzz/projects/symphony-trello/`. It clones this repository and
installs a Java 25 JDK before compiling the Maven project. A direct Docker build is only a quick
preflight for the Dockerfile and clone step. It does not prove that OSS-Fuzz can produce `$OUT`
targets, run the base-runner checks, or execute the generated wrappers:

```bash
docker build -f oss-fuzz/Dockerfile oss-fuzz --build-arg SYMPHONY_TRELLO_REF=<branch-name>
```

Use a current `google/oss-fuzz` checkout for real OSS-Fuzz validation:

```bash
git clone https://github.com/google/oss-fuzz.git
cd oss-fuzz
mkdir -p projects/symphony-trello
cp /path/to/symphony-trello/oss-fuzz/Dockerfile projects/symphony-trello/
cp /path/to/symphony-trello/oss-fuzz/build.sh projects/symphony-trello/
cp /path/to/symphony-trello/oss-fuzz/project.yaml projects/symphony-trello/

python3 infra/helper.py build_image --no-pull symphony-trello
python3 infra/helper.py build_fuzzers --sanitizer address symphony-trello
python3 infra/helper.py check_build symphony-trello
mkdir -p /tmp/symphony-trello-{RepositorySourceFuzzer,WorkflowLoaderFuzzer,TrelloCardReferenceParserFuzzer,TrelloChecklistClassifierFuzzer}
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-RepositorySourceFuzzer symphony-trello RepositorySourceFuzzer -- -runs=100
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-WorkflowLoaderFuzzer symphony-trello WorkflowLoaderFuzzer -- -runs=100
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-TrelloCardReferenceParserFuzzer symphony-trello TrelloCardReferenceParserFuzzer -- -runs=100
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-TrelloChecklistClassifierFuzzer symphony-trello TrelloChecklistClassifierFuzzer -- -runs=100
```

`--no-pull` keeps the helper non-interactive. Use `--pull` instead when intentionally refreshing
base images. The current helper expects the `--corpus-dir` path to exist. The `--` before
`-runs=100` separates OSS-Fuzz helper options from libFuzzer options.

Do not put private credentials in the Dockerfile or Docker build context. When validating local-only
or unpushed changes, copy the `oss-fuzz/` files into a local OSS-Fuzz checkout and use a temporary
local-source override there, then run the same helper commands. Remove the local override before
copying the project files to upstream OSS-Fuzz.
