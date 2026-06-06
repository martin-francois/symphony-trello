---
status: accepted
date: 2026-06-07
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #355](https://github.com/martin-francois/symphony-trello/issues/355)"
  - "[GitHub PR #356](https://github.com/martin-francois/symphony-trello/pull/356)"
  - "[GitHub Actions run 27075094829](https://github.com/martin-francois/symphony-trello/actions/runs/27075094829)"
  - "[GitHub Actions run 27076767091](https://github.com/martin-francois/symphony-trello/actions/runs/27076767091)"
  - "[GitHub Actions run 27076839061](https://github.com/martin-francois/symphony-trello/actions/runs/27076839061)"
  - "[GitHub Actions run 27072391976](https://github.com/martin-francois/symphony-trello/actions/runs/27072391976)"
  - "[GitHub issue #357](https://github.com/martin-francois/symphony-trello/issues/357)"
  - "[Blacksmith instance types](https://docs.blacksmith.sh/blacksmith-runners)"
informed: [Future maintainers, Contributors]
---

# Keep Linux Test CI on 2-vCPU Blacksmith Runners

## Context and Problem Statement

The Linux `test` CI job runs the main Maven verification path:

```bash
./mvnw spotless:check
SYMPHONY_TRELLO_TEST_PWSH=./scripts/pwsh-docker.sh ./mvnw verify
```

Most successful runs finish quickly, but one PR run took much longer than normal. Issue #355 asked
whether increasing the Blacksmith runner size to 4 vCPU or higher would make the job faster in a way
that also maximizes the monthly Blacksmith free-minute allowance.

Blacksmith documents that the free allowance is based on 2-vCPU x64 minutes. Higher-vCPU x64 runners
consume that allowance proportionally. A 4-vCPU x64 job must therefore run more than twice as fast as
2 vCPU to improve the free-minute budget. An 8-vCPU x64 job must run more than four times as fast.

Which Linux runner size and CI shape should the project use for the `test` path?

## Decision Drivers

* Maximize useful Blacksmith free minutes, not only wall-clock speed.
* Keep required CI checks equivalent to the current release-quality verification.
* Prefer measured CI timings over assumptions about runner size.
* Treat `2-vCPU normalized minutes = wall seconds * vCPU / 2` as the comparison metric.
* Avoid splitting checks into extra jobs unless the latency gain is worth the additional runner
  startup, cache restore, compile, and vCPU-weighted minute cost.
* Keep installer lifecycle and PowerShell-backed tests deterministic.
* Keep the CI workflow easy to understand and maintain.

## Considered Options

* Keep the Linux `test` job on `blacksmith-2vcpu-ubuntu-2404`.
* Move the Linux `test` job to `blacksmith-4vcpu-ubuntu-2404`.
* Move the Linux `test` job to `blacksmith-8vcpu-ubuntu-2404`.
* Use Maven `-T 1C` for the Linux `test` job.
* Enable JUnit 5 parallel test execution.
* Split tests, coverage, PMD/CPD, SpotBugs/FindSecBugs, and installer lifecycle into separate CI
  jobs.
* Remove `spotless:check` from the Linux `test` job.
* Move or avoid the PowerShell test image pull.

## Decision Outcome

Chosen option: "Keep the Linux `test` job on `blacksmith-2vcpu-ubuntu-2404`", because measured
4-vCPU and 8-vCPU runners did not produce enough speedup to offset their higher free-minute cost.

PR #356 first ran a temporary benchmark matrix for the Linux `test` job on the same commit:

| Runner | Wall time | vCPU multiplier | Normalized 2-vCPU seconds |
| --- | ---: | ---: | ---: |
| `blacksmith-2vcpu-ubuntu-2404` | 147s | 1x | 147s |
| `blacksmith-4vcpu-ubuntu-2404` | 140s | 2x | 280s |
| `blacksmith-8vcpu-ubuntu-2404` | 144s | 4x | 576s |

The fastest wall-clock result was 4 vCPU, but only by seven seconds. It consumed almost twice the
normalized Blacksmith budget. The 8-vCPU result was slower than 4 vCPU and much more expensive in
normalized minutes.

PR #356 then ran a second temporary hosted matrix that combined runner size with Maven `-T 1C`.
Maven `-T` is reactor and plugin parallelism. It does not by itself mean that JUnit test classes or
test methods run concurrently. The project currently has one Maven module, so `-T` has limited room
to help.

| Runner | Maven mode | Wall time | vCPU multiplier | Normalized 2-vCPU seconds |
| --- | --- | ---: | ---: | ---: |
| `blacksmith-2vcpu-ubuntu-2404` | default | 168s | 1x | 168s |
| `blacksmith-2vcpu-ubuntu-2404` | `-T 1C` | 171s | 1x | 171s |
| `blacksmith-4vcpu-ubuntu-2404` | default | 167s | 2x | 334s |
| `blacksmith-4vcpu-ubuntu-2404` | `-T 1C` | 168s | 2x | 336s |
| `blacksmith-8vcpu-ubuntu-2404` | default | 142s | 4x | 568s |
| `blacksmith-8vcpu-ubuntu-2404` | `-T 1C` | 141s | 4x | 564s |

That run again showed no normalized-cost win for larger runners. Maven `-T 1C` made the 2-vCPU and
4-vCPU jobs slightly slower and made the 8-vCPU job only one second faster, while the 8-vCPU job
still consumed almost four times the normalized 2-vCPU budget.

PR #356 also ran a third temporary hosted matrix with JUnit 5 parallel execution enabled. It used
fixed JUnit parallelism equal to the runner vCPU count and tested both JUnit parallel execution
alone and JUnit parallel execution combined with Maven `-T 1C`.

| Runner | Mode | Result | Wall time before failure | Normalized 2-vCPU seconds before failure |
| --- | --- | --- | ---: | ---: |
| `blacksmith-2vcpu-ubuntu-2404` | JUnit parallel | failed | 83s | 83s |
| `blacksmith-2vcpu-ubuntu-2404` | `-T 1C` + JUnit parallel | failed | 68s | 68s |
| `blacksmith-4vcpu-ubuntu-2404` | JUnit parallel | failed | 51s | 102s |
| `blacksmith-4vcpu-ubuntu-2404` | `-T 1C` + JUnit parallel | failed | 59s | 118s |
| `blacksmith-8vcpu-ubuntu-2404` | JUnit parallel | failed | 41s | 164s |
| `blacksmith-8vcpu-ubuntu-2404` | `-T 1C` + JUnit parallel | failed | 44s | 176s |

Every JUnit-parallel variant failed, including 2 vCPU. The failures were not timing-only evidence
because failed jobs are not acceptable release-quality verification. The common failure family was
test interference: setup and rerun tests failed with `java.net.BindException: Address already in
use`, and some main-process tests showed cross-test process/JDK command state contamination. Issue
#357 tracks making those tests safe before JUnit parallel execution is reconsidered for required CI.

The same third run repeated the successful non-JUnit-parallel cases:

| Runner | Maven mode | Wall time | vCPU multiplier | Normalized 2-vCPU seconds |
| --- | --- | ---: | ---: | ---: |
| `blacksmith-2vcpu-ubuntu-2404` | default | 150s | 1x | 150s |
| `blacksmith-2vcpu-ubuntu-2404` | `-T 1C` | 149s | 1x | 149s |
| `blacksmith-4vcpu-ubuntu-2404` | default | 161s | 2x | 322s |
| `blacksmith-4vcpu-ubuntu-2404` | `-T 1C` | 159s | 2x | 318s |
| `blacksmith-8vcpu-ubuntu-2404` | default | 157s | 4x | 628s |
| `blacksmith-8vcpu-ubuntu-2404` | `-T 1C` | 145s | 4x | 580s |

The best successful wall-clock result in that run was 8 vCPU with Maven `-T 1C`, at 145 seconds.
Its normalized cost was 580 2-vCPU seconds. The 2-vCPU default and Maven-threaded jobs were 150 and
149 normalized seconds, so larger runners plus Maven parallelism were still materially worse for the
Blacksmith free-minute budget.

Recent 2-vCPU history also showed the long run was an outlier, not the normal cost profile:

| Run | Runner detail | Test job wall time | Maven verify time |
| --- | --- | ---: | ---: |
| `27072391976` | gen 1 Intel 2-vCPU runner | 12m16s | 10m56s |
| `27072211836` | gen 2 AMD 2-vCPU runner | 2m29s | 2m12s |
| `27071991905` | gen 2 AMD 2-vCPU runner | 2m27s | 2m10s |
| `27071784673` | gen 2 AMD 2-vCPU runner | 2m24s | 2m09s |
| `27069676663` | gen 2 AMD 2-vCPU runner | 2m24s | 2m07s |

The longer run had a Maven cache hit, so dependency download was not the cause. The main difference
visible in the log was the older Blacksmith host generation. Blacksmith does not document a
workflow label that pins only the faster generation, so changing the runner size would be the wrong
fix for that outlier.

Local command timings on the same branch were:

| Command | Wall time | Decision |
| --- | ---: | --- |
| `./mvnw -q spotless:check verify` | 104s | Baseline local check. |
| `./mvnw -q -T 1C spotless:check verify` | 96s | Small local gain, not enough evidence to change CI. |
| `./mvnw -q -Dspotbugs.skip=true -Dpmd.skip=true -Dcpd.skip=true spotless:check verify` | 88s | Static analysis is not the main bottleneck. |
| `./mvnw -q -DskipTests -Djacoco.skip=true test-compile pmd:check pmd:cpd-check spotbugs:check` | 10s | Splitting static analysis would add another runner and duplicate setup/compile work. |

Do not split PMD/CPD and SpotBugs into a separate required job now. The potential critical-path
saving is small on normal runs, and the extra job would consume additional normalized minutes.

Do not enable JUnit parallel execution now. The suite is not currently parallel-safe, and making it
safe is separate work tracked by issue #357.

Do not split test execution or coverage now. JaCoCo aggregation, Quarkus packaging, the packaged app
smoke test, installer lifecycle tests, and PowerShell-backed tests make a split more complex than
the current measured benefit justifies.

Keep `spotless:check` in the Linux `test` job. It is the Java formatter gate, not a duplicate of the
Markdown and shell lint job.

Keep the PowerShell test image pull in the Linux `test` job. The Linux Maven verification uses
`SYMPHONY_TRELLO_TEST_PWSH=./scripts/pwsh-docker.sh` for PowerShell-backed Java tests. Moving that
setup into a separate required job would duplicate checkout, Java setup, Maven cache restore, and
part of compilation for little measured gain.

If the 2-vCPU runner starts regularly landing on slower host generations, create a new focused issue
to ask Blacksmith whether there is a supported label, account setting, or support path for avoiding
that class of host. Do not move to larger x64 runners or parallel test modes unless new successful
measurements show they improve the normalized 2-vCPU minute cost.

### Consequences

* Good, because the default test job preserves the best measured free-minute efficiency.
* Good, because CI remains simple and close to the local validation command.
* Good, because a larger runner is not adopted for a seven-second improvement that doubles cost.
* Good, because the outlier is documented as host-generation-related evidence, not normal baseline.
* Good, because JUnit parallelism was measured instead of assumed safe.
* Bad, because occasional slow 2-vCPU host generations can still make individual runs take longer.
* Bad, because the fastest wall-clock benchmark result is not the selected configuration.
* Bad, because static analysis and tests still share one required Linux job.
* Bad, because JUnit parallel execution needs test-isolation work before it can be reconsidered.

### Confirmation

Run the local verification command:

```bash
./mvnw -q spotless:check verify
```

For future runner-size changes, first repeat the temporary PR matrix measurement for the same commit
and compare normalized 2-vCPU minutes:

```text
normalized_2vcpu_seconds = wall_seconds * vcpu / 2
```

Only move the required Linux `test` job to a larger x64 runner or parallel test mode when the
normalized value is lower than the current 2-vCPU value and the job still passes, or when
maintainers explicitly choose latency over free-minute efficiency.

## Pros and Cons of the Options

### Keep the Linux `test` Job on `blacksmith-2vcpu-ubuntu-2404`

Continue using the current 2-vCPU x64 Ubuntu 24.04 runner for the Maven verification job.

* Good, because it had the lowest normalized cost in the benchmark.
* Good, because it keeps the full 3000 x64 2-vCPU-minute allowance as useful as possible.
* Good, because no CI topology change is needed.
* Bad, because it does not avoid rare slow host-generation outliers.

### Move the Linux `test` Job to `blacksmith-4vcpu-ubuntu-2404`

Run the same job on a 4-vCPU x64 runner.

* Good, because it was seven seconds faster in the temporary benchmark.
* Bad, because its normalized cost was almost double the 2-vCPU job.
* Bad, because it did not provide anything close to the required 2x speedup.

### Move the Linux `test` Job to `blacksmith-8vcpu-ubuntu-2404`

Run the same job on an 8-vCPU x64 runner.

* Good, because it tests whether high parallel capacity helps this build.
* Bad, because it was slower than the 4-vCPU result and close to the 2-vCPU result.
* Bad, because its normalized cost was almost four times the 2-vCPU job.

### Use Maven `-T 1C` for the Linux `test` Job

Run Maven with one thread per available core.

* Good, because the local measurement was slightly faster.
* Good, because one hosted 2-vCPU rerun was one second faster.
* Bad, because this is a single-module Maven build, so Maven reactor parallelism has limited room to
  help.
* Bad, because hosted measurements showed no material improvement and no normalized-cost win.
* Bad, because it is not the same as executing JUnit tests in parallel.

### Enable JUnit 5 Parallel Test Execution

Run JUnit test classes and methods concurrently with fixed parallelism based on runner vCPU count.

* Good, because it tests the form of parallelization most likely to shorten this single-module
  project's test phase.
* Bad, because every hosted JUnit-parallel benchmark variant failed.
* Bad, because the failure pattern shows shared port and process-state coupling in setup and
  lifecycle tests.
* Bad, because enabling it would weaken required CI until issue #357 makes the suite parallel-safe.

### Split Tests, Coverage, Static Analysis, and Installer Lifecycle Into Separate Jobs

Run tests, coverage, PMD/CPD, SpotBugs/FindSecBugs, and installer lifecycle checks in parallel jobs.

* Good, because it could shorten the critical path if one check grows much slower later.
* Good, because failures might become more localized.
* Bad, because every split job has its own runner startup, checkout, Java setup, Maven cache restore,
  and likely compilation cost.
* Bad, because the local static-only check was only about 10 seconds, so splitting it now would
  increase total normalized cost for little latency improvement.
* Bad, because splitting coverage and packaged-app checks would require more build-artifact and
  JaCoCo handling.

### Remove `spotless:check` From the Linux `test` Job

Rely on other jobs for formatting and skip Spotless in `test`.

* Good, because it would remove one short step from the Linux test path.
* Bad, because the current `lint` job covers Markdown, shell, and PowerShell checks, not Java
  Spotless formatting.
* Bad, because it would weaken the release-quality Maven path.

### Move or Avoid the PowerShell Test Image Pull

Avoid pulling the PowerShell Docker image in the Linux `test` job or move PowerShell-backed Java
tests elsewhere.

* Good, because image pulls can add latency when cache behavior changes.
* Bad, because the Java tests intentionally use the Docker-backed PowerShell command when local
  `pwsh` is unavailable.
* Bad, because a separate job would duplicate more setup and compilation than it saves today.

## More Information

Blacksmith documents available Ubuntu runner labels from 2 to 32 vCPU and says x64 free minutes are
consumed proportionally to vCPU count. It also documents free upgrades when spare capacity is
available, but not a label for selecting a specific host generation.

The temporary benchmark matrix was removed before the final PR commit. It existed only to collect
runner-size data on PR #356.
