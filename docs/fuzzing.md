# Fuzzing

Symphony for Trello uses Jazzer for parser and boundary fuzzing. Normal Maven verification runs the
Jazzer tests in regression mode, so fixed crash inputs stay part of the regular test suite.

Run the focused fuzzing and chaos tests:

```bash
./mvnw -q -Dtest=RepositorySourceResolverFuzzTest,TrelloCardReferenceParserFuzzTest,TrelloCardPayloadFuzzTest,TrelloHandoffToolFuzzTest,WorkflowLoaderFuzzTest,StandaloneFuzzerIsolationTest,FuzzHarnessIsolationRulesTest,TrelloClientChaosTest test
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
`main` on GitHub-hosted runners. Each successful long batch dispatches its successor. A separate
watchdog queues its successor before checking the batch chain every 15 minutes for 4 hours and
15 minutes. Each queue and batch-state operation has a two-minute timeout, so slow API calls cannot
consume the job's 350-minute budget. The queued successor starts when the current watchdog finishes
or is cancelled. Changes
to the continuous chain and completion of any marked long batch also start or refresh this serialized
watchdog chain. Each check restarts cycle 0 only when the long workflow is idle. This design does not
depend on GitHub scheduled events, which GitHub can delay or drop, and overlapping trigger events do
not queue duplicate batch chains. A transient successor-dispatch or batch-state API failure is
retried at the next check instead of ending the watchdog. Three cycles use a 330-minute
aggregate fuzzing budget; every fourth cycle uses 300
aggregate minutes so daily maintenance can follow before the next batch. The matrix runs one job
per target and divides that budget by target weights. With six targets, full shares are 66 minutes,
or 60 minutes in the maintenance cycle; half shares are 33 or 30 minutes.
`scripts/select-clusterfuzzlite-target` gives each target a full or a half share, and
[ADR 0116](adr/0116-fuzz-trello-card-payload-mapping.md) records why some targets get half.
Each matrix job has a 100-minute timeout. Matrix jobs run one at a time because
ClusterFuzzLite pushes every corpus to the same Git branch; serialization prevents non-fast-forward
races without changing the aggregate fuzzing budget. The workflow intentionally uses `ubuntu-latest`,
not a Blacksmith runner. Each runner keeps one fuzzer wrapper before invoking ClusterFuzzLite. This
works around ClusterFuzzLite's combined-batch SARIF limitation and guarantees that a crash in any
target has its own report. The jobs carry each target's corpus into later runs through the storage
repository, verify that each successful run persisted its corpus, and prune redundant corpus inputs
after every fourth batch. A reportable batch crash fails its matrix job,
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
coverage-guided mutation starts inside useful parser paths. The Trello handoff tool seeds are the
smallest input for each tool and outcome from a local active run, named `<tool>--<outcome>`, plus the
inputs that found a removed permission check. They are `FuzzedDataProvider` inputs, not readable
payloads.

The card payload seeds are Trello card responses taken from the `TrelloClientTest` payload shapes. Each one ends with nine choice bytes that
`FuzzedDataProvider` reads from the end of the input: four priority label settings, then the closed
flags of four board lists and of the board. The JSON body before them is what the card mapping
parses, and Jackson ignores the trailing bytes.

ClusterFuzzLite's `v1` runner image bundles JaCoCo 0.8.7, which rejects Java 25 class files after
the fuzzers run and then uploads an incomplete coverage directory. The coverage job therefore uses
the same digest-pinned ClusterFuzzLite runner with its JaCoCo agent and CLI replaced by the version
declared in `pom.xml`. The repository-owned wrapper still delegates corpus download, coverage
execution, and publication to ClusterFuzzLite. Its post-run verifier requires the HTML report,
well-formed JaCoCo XML, aggregate summary, and every per-target summary. It downloads and
checks every published file, rejects empty aggregate coverage, and rejects any target report that
does not cover lines in that target's production resolver, parser, classifier, loader,
`TrelloClient` card mapping, or tool handler.

ClusterFuzzLite stores corpora on `main` and coverage on `gh-pages` in the dedicated public
[`symphony-trello-fuzzing-storage`](https://github.com/martin-francois/symphony-trello-fuzzing-storage)
repository. Pull-request jobs read this public data without receiving its write credential. Trusted
jobs on `main` receive the `CLUSTERFUZZLITE_STORAGE_TOKEN` secret only in storage-write and
storage-verification steps. Baseline builds and crash reproducers remain GitHub Actions artifacts.
Explicit post-run API checks fail the workflow if ClusterFuzzLite logs an upload error but returns
success.
After the first coverage run and GitHub Pages activation, the latest report is available at
`https://martin-francois.github.io/symphony-trello-fuzzing-storage/coverage/latest/report/linux/index.html`.

After the workflow is present on `main`, maintainers can smoke-test the complete hosted path with a
short manual batch run:

```bash
gh workflow run continuous-fuzzing.yml --ref main \
  -f operation=batch \
  -f fuzz_seconds=60
```

The manual `fuzz_seconds` value is one full share. Full-share targets fuzz for that long and
half-share targets for half of it, rounded up. Because corpus writers are serialized, a one-minute
smoke run takes one build-and-fuzz cycle per target. `automatic_budget` defaults to `false`; the
continuation script sets it to `true` to keep automatic cycles at 19,800 or 18,000 total seconds.

Use `operation=prune`, `operation=coverage`, or `operation=build` to test the corpus-pruning,
coverage, or baseline-build paths. The `fuzz_seconds` input applies only to batch runs.

Bootstrap or restart the continuous chain with cycle 0:

```bash
gh workflow run continuous-fuzzing.yml --ref main \
  -f operation=batch \
  -f fuzz_seconds=4950 \
  -f automatic_budget=true \
  -f continue_fuzzing=true \
  -f cycle_index=0
```

The `continue_fuzzing` input defaults to `false` so a short manual smoke run remains one-off. Do not
dispatch a one-off batch while the continuous chain is active because all batch runs share one
concurrency group to protect corpus writes. Manually dispatched pruning uses that group as well. A
successful continuous cycle dispatches the next index; cycle 3 runs pruning and coverage before
dispatching cycle 0. A failed batch stops self-dispatch and lets the watchdog restart cycle 0 on its
next check, which prevents setup failures from causing a rapid retry loop. Completion of the marked
long run refreshes that recovery without waiting for a scheduled event.

The first hosted coverage report establishes the baseline. A healthy report contains every
fuzzer and shows each one reaching its intended production entry point. If a target has zero or
visibly shallow reach, add a representative seed or a focused standalone target before accepting
the hosted setup. Do not use whole-application line coverage as the threshold: these fuzzers cover
untrusted parsing boundaries, while network and orchestration behavior belongs in deterministic
tests. During post-merge verification, inspect every target's batch log for timeout or out-of-memory
exits even when the job is green, and confirm that the storage repository's `gh-pages` branch
contains and serves the HTML report. These checks cover open ClusterFuzzLite issues
[#149](https://github.com/google/clusterfuzzlite/issues/149) and
[#150](https://github.com/google/clusterfuzzlite/issues/150).

Each coverage review is a file in `docs/fuzzing-reviews/` named after the last day of the period it
covers. [ADR 0078](adr/0078-github-actions-continuous-fuzzing.md#decision-outcome) says how a review
ranks new targets. The [first-week review](fuzzing-reviews/2026-09-07-first-week.md)
is the first one. A later review compares its numbers with the previous file and collects them with
the commands listed there.

For a 15- to 30-minute active fuzzing pass, run the public parser targets one at a time with an
explicit duration:

```bash
set -euo pipefail

targets=(
  'RepositorySourceResolverFuzzTest#labelledRepositorySourceValueCannotBreakSelectionInvariants'
  'RepositorySourceResolverFuzzTest#cardTextDeclarationScanCannotBreakSelectionInvariants'
  'TrelloCardReferenceParserFuzzTest#trelloReferenceParsingKeepsLookupIdsAndUrlsStable'
  'TrelloCardReferenceParserFuzzTest#checklistClassificationNeverEmitsPrerequisitesWithProblems'
  'TrelloHandoffToolFuzzTest#handoffToolCallsWriteOnlyWhatTheConfigurationAllows'
  'TrelloCardPayloadFuzzTest#cardPayloadMappingReturnsACardOrRejectsTheUnknownPayload'
  'WorkflowLoaderFuzzTest#workflowLoaderHandlesArbitraryWorkflowBytes'
)

for target in "${targets[@]}"; do
  JAZZER_FUZZ=1 ./mvnw -q -Pfuzzing -Djacoco.skip=true -Djazzer.max_duration=4m -Djazzer.max_executions=0 "-Dtest=$target" test
done
```

The `-Djazzer.max_duration=4m` value applies to one selected target in one Maven process. The loop
above runs each target in its own Maven process, so it takes roughly four minutes per target plus
Maven startup time; it is not a four-minute total suite limit.

For a longer or continuous agent-requested run, choose one target and a longer duration such as
`-Djazzer.max_duration=5h -Djazzer.max_executions=0`. The execution override lets the duration,
rather than the method-level regression cap, decide when the fuzzing process stops. Stop the command
when the requested window ends. Contributors are not expected to run this before every pull request;
use it when touching parser, prompt-line safety, workflow loading, or Trello reference/checklist
parsing logic. Run the Trello handoff tool target when touching `TrelloHandoffToolHandler`,
`TrelloMarkdown`, or the `TrelloClient` writes those tools send.
Run the card payload target when touching how `TrelloClient` maps a card payload.

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
- `TrelloCardPayloadFuzzTest` also runs in Maven. It takes a `FuzzedDataProvider` and has no
  `@MethodSource`. Maven copies the `oss-fuzz/corpora/TrelloCardPayloadFuzzer` seeds to its Jazzer
  regression inputs, so normal test runs replay them next to its crash inputs.
- `TrelloHandoffToolFuzzTest` also runs in Maven. It takes a `FuzzedDataProvider` and has no
  `@MethodSource`. Maven copies the `oss-fuzz/corpora/TrelloHandoffToolFuzzer` seeds to its Jazzer
  regression inputs, so normal test runs replay them next to its crash inputs.
- `RepositorySourceFuzzer`, `WorkflowLoaderFuzzer`, `TrelloCardReferenceParserFuzzer`,
  `TrelloChecklistClassifierFuzzer`, `TrelloCardPayloadFuzzer`, and `TrelloHandoffToolFuzzer` are standalone
  `fuzzerTestOneInput` entry points. OSS-Fuzz wraps and runs these classes from compiled test output.
  `TrelloHandoffToolFuzzer` and `TrelloHandoffToolFuzzTest` share `TrelloHandoffToolFuzzInvariants`.
  It runs one fuzzed tool call through the real `TrelloClient` against `InMemoryTrelloBoard`, which
  answers requests from memory and records every write, and checks that each write is one the chosen
  `trello_tools` settings allow on the current card. [ADR 0118](adr/0118-fuzz-trello-handoff-tool-write-scope.md)
  lists the properties.
  `TrelloCardPayloadFuzzer` and `TrelloCardPayloadFuzzTest` share `TrelloCardPayloadFuzzInvariants`,
  which maps one card payload through the package-private `TrelloClient.normalize` without HTTP.
- `StandaloneFuzzerIsolationTest` replays each standalone target's `oss-fuzz/corpora/` seeds through
  its real entry point in Jazzer regression mode. `pom.xml` maps each corpus directory to that class's
  regression inputs, so a new target needs a corpus, a mapping, and a `@FuzzTest` method named after it.

Both kinds of target check the same properties. A target that only checks for uncaught exceptions
misses wrong results, so each harness asserts what the parser must produce:

| Harness | Properties | Why |
| --- | --- | --- |
| `WorkflowLoaderFuzzer`, `WorkflowLoaderFuzzTest` | Same bytes give the same result. Invalid UTF-8, and nothing else, fails as `missing_workflow_file`. Every other failure uses a specified error code and a one-line message. Markdown without front matter has an empty config and the whole trimmed text as prompt. Unclosed front matter is a parse error. The prompt is trimmed and survives a second parse after it is wrapped in empty front matter. The config has at most four keys and values per input byte. | `SPEC.md` section 5.2 defines these rules. The size bound fails if the YAML parser starts expanding aliases, which can turn a few kilobytes into an exponential config. |
| `TrelloCardReferenceParserFuzzer`, `trelloReferenceParsingKeepsLookupIdsAndUrlsStable` | Lookup ids are alphanumeric, URLs are normalized, and `containsTrelloCardUrl` agrees with the parsed references. The normalized URLs parse back to the same references, each URL is an exact reference to itself, and the number of references fits the text length. | Prerequisite lookups and prompts use the normalized URL, so it must identify the same card when parsed again. |
| `TrelloChecklistClassifierFuzzer`, `checklistClassificationNeverEmitsPrerequisitesWithProblems` | A checklist with a problem has no prerequisites and only known problem codes. Reversing the items reverses the prerequisites and keeps the problems. A checklist rebuilt from the prerequisite URLs classifies to the same result. | The scheduler must not depend on item order, and a prerequisite it reports must survive being written back as a URL. |
| `RepositorySourceFuzzer`, `RepositorySourceResolverFuzzTest` | Selected sources, identities, paths, and problems contain no control or line-separator characters. A selected URI has a usable port. | These values become prompt lines and clone targets. Their input generation is changing in [#691](https://github.com/martin-francois/symphony-trello/issues/691), so their properties stay as they are here. |

Byte and text inputs are capped at 8 KiB and 2,048 characters. The YAML parser rejects nesting
deeper than 1,000 levels, and `WorkflowLoaderFuzzTest` keeps a 4,000-level seed that must fail as
`workflow_parse_error` instead of overflowing the stack.

No target may touch files, the network, or user configuration. `WorkflowLoaderFuzzer` calls
`WorkflowLoader.parse(Path, byte[])` instead of writing each input to a temporary file.
Every class with a `@FuzzTest` method uses `FuzzIsolationExtension` and runs `@Isolated`. The
extension records file and socket reads and writes with Java Flight Recorder while the seeds replay
and fails the class on any that happen inside a fuzz test execution. It ignores class loading and
writes to standard output or error, such as log lines. Flight Recorder has no event for creating,
deleting, or moving a file, or for a connection attempt or DNS lookup, so the replay does not see
those. `FuzzHarnessIsolationRulesTest` fails when a class with a `@FuzzTest` method does not use the
extension.

The handoff replay constructs request metadata before the guarded executions. The JDK reads
`net.properties` during its first header validation, even with the in-memory transport. This
bootstrap does not send a request. File and socket checks remain active for every target execution.

During ClusterFuzzLite and OSS-Fuzz runs, Jazzer's `ServerSideRequestForgery` detector reports every
network connection attempt as a finding. `FuzzHarnessIsolationRulesTest` also fails if `pom.xml` or
any file under `oss-fuzz/` (seed corpora excluded), `.clusterfuzzlite/`, or `.github/workflows/`
sets Jazzer's `disabled_hooks` option or turns `hooks` off, in any flag, property, environment
variable, or `.options` spelling. In JUnit regression mode the detector loads but does not fail on a
connection, which is why the replay records socket I/O itself.
[ADR 0091](adr/0091-fuzz-harness-properties-and-isolation.md) records these choices and the
throughput measurements behind them.

The OSS-Fuzz runtime classpath is deliberately narrower than Maven's test classpath. The build
script copies production classes to `$OUT/classes`, only the standalone fuzzer classes and the test
helpers they call to `$OUT/test-classes`, and Maven runtime-scope dependency jars to
`$OUT/lib`. It fails if `$OUT/lib` contains Jazzer, JUnit, Surefire, Mockito, AssertJ, or similar
test-runner-only jars.

The standalone fuzzer classes are compiled by Maven with the repository's local test-scoped Jazzer
dependency. That gives the source code stable Jazzer API types such as `FuzzedDataProvider` at
compile time. Maven's Jazzer and JUnit jars are intentionally not copied into `$OUT/lib`. At runtime,
the generated wrappers launch OSS-Fuzz's `jazzer_driver`, pass OSS-Fuzz's
`jazzer_agent_deploy.jar` through `--agent_path`, and use only project classes, standalone fuzzer
classes, OSS-Fuzz's agent jar, and the curated runtime dependency jars on the wrapper classpath.

The wrappers start the fuzzing JVM with three options beyond memory settings:
`--enable-native-access=ALL-UNNAMED` because Jazzer loads its JNI library from the class path,
`-Xshare:off` because Jazzer appends its runtime to the boot class path, where CDS can share only part
of the classes and warns about it, and `--sun-misc-unsafe-memory-access=allow`. The last one is a
workaround that only silences the JDK warning Jazzer's own runtime triggers through
`sun.misc.Unsafe`. `.clusterfuzzlite/build.sh` runs `.clusterfuzzlite/check-unsafe-memory-access-workaround`
after packaging; it runs one fuzzer with the warning on and fails the ClusterFuzzLite build once the
OSS-Fuzz base image's Jazzer no longer triggers it. Issue
[#913](https://github.com/martin-francois/symphony-trello/issues/913) tracks the removal steps.

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
mkdir -p /tmp/symphony-trello-{RepositorySourceFuzzer,WorkflowLoaderFuzzer,TrelloCardReferenceParserFuzzer,TrelloChecklistClassifierFuzzer,TrelloHandoffToolFuzzer,TrelloCardPayloadFuzzer}
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-RepositorySourceFuzzer symphony-trello RepositorySourceFuzzer -- -runs=100
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-WorkflowLoaderFuzzer symphony-trello WorkflowLoaderFuzzer -- -runs=100
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-TrelloCardReferenceParserFuzzer symphony-trello TrelloCardReferenceParserFuzzer -- -runs=100
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-TrelloChecklistClassifierFuzzer symphony-trello TrelloChecklistClassifierFuzzer -- -runs=100
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-TrelloHandoffToolFuzzer symphony-trello TrelloHandoffToolFuzzer -- -runs=100
python3 infra/helper.py run_fuzzer --corpus-dir=/tmp/symphony-trello-TrelloCardPayloadFuzzer symphony-trello TrelloCardPayloadFuzzer -- -runs=100
```

`--no-pull` keeps the helper non-interactive. Use `--pull` instead when intentionally refreshing
base images. The current helper expects the `--corpus-dir` path to exist. The `--` before
`-runs=100` separates OSS-Fuzz helper options from libFuzzer options.

Do not put private credentials in the Dockerfile or Docker build context. When validating local-only
or unpushed changes, copy the `oss-fuzz/` files into a local OSS-Fuzz checkout and use a temporary
local-source override there, then run the same helper commands. Remove the local override before
copying the project files to upstream OSS-Fuzz.
