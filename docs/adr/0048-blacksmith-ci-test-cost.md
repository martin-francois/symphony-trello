---
status: accepted
date: 2026-06-07
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #355](https://github.com/martin-francois/symphony-trello/issues/355)"
  - "[GitHub issue #357](https://github.com/martin-francois/symphony-trello/issues/357)"
  - "[GitHub issue #359](https://github.com/martin-francois/symphony-trello/issues/359)"
  - "[Benchmark run 27079999864](https://github.com/martin-francois/symphony-trello/actions/runs/27079999864)"
  - "[Benchmark run 27080073069](https://github.com/martin-francois/symphony-trello/actions/runs/27080073069)"
  - "[Fresh 2-vCPU baseline run 29863698502](https://github.com/martin-francois/symphony-trello/actions/runs/29863698502)"
  - "[Fresh 4-vCPU trial run 29867224338](https://github.com/martin-francois/symphony-trello/actions/runs/29867224338)"
  - "[Restored 2-vCPU confirmation run 29867643365](https://github.com/martin-francois/symphony-trello/actions/runs/29867643365)"
  - "[Blacksmith runner billing](https://docs.blacksmith.sh/blacksmith-runners/overview#is-there-a-free-tier)"
  - "[GitHub Actions billing](https://docs.github.com/en/billing/concepts/product-billing/github-actions)"
informed: [Future maintainers, Contributors]
---

# Reserve Blacksmith for 2-vCPU Critical-Path Verification

## Context and Problem Statement

The Linux verification job uses Blacksmith free minutes. Blacksmith minutes are weighted by runner
size, so lower wall-clock time is not automatically cheaper. For this repository, compare runner
choices with normalized seconds:

```text
normalized seconds = wall-clock seconds * runner vCPU / 2
```

A 4-vCPU runner must finish in less than half the 2-vCPU time to use fewer normalized Blacksmith
minutes. An 8-vCPU runner must finish in less than one quarter of the 2-vCPU time.

The repository is public. GitHub's 2026 Actions platform charge does not apply to public-repository
runner usage, so it does not add a fixed per-minute cost that would change these break-even ratios.
Blacksmith's free quota continues to charge larger x64 runners in proportion to their vCPU count.

Blacksmith provides 3,000 x64 2-vCPU minutes per organization each month. Standard GitHub-hosted
runners are free and unlimited for public repositories. Retained CI history through 2026-07-22
measured a 14-second p50 for both `lint` and `renovate`, an 11-second p50 for `script-tests`, a
121-second p50 for `test`, and a 125-second p50 for `windows-powershell`. Spending Blacksmith minutes
to shorten the already faster jobs does not reduce the workflow's critical path while the Linux and
Windows verification jobs are still running.

[GitHub issue #355](https://github.com/martin-francois/symphony-trello/issues/355) asked whether
increasing the runner size, enabling Maven parallelism, enabling JUnit parallelism, or splitting CI
jobs would make the pipeline faster without increasing normalized Blacksmith cost.
[GitHub issue #357](https://github.com/martin-francois/symphony-trello/issues/357) and
[GitHub PR #358](https://github.com/martin-francois/symphony-trello/pull/358) made local JUnit
parallelism viable enough to measure it on hosted Blacksmith runners.

## Decision Drivers

* Keep the CI gate equivalent to the existing release-quality verification.
* Minimize normalized Blacksmith minute usage, not only wall-clock time.
* Reserve the limited Blacksmith quota for jobs that determine end-to-end CI latency.
* Use free standard GitHub-hosted runners when a slower supporting job does not extend the critical
  path.
* Prefer reliable hosted CI over a faster setting that flakes or hangs.
* Keep local developer feedback fast where it has already been proven stable.
* Avoid splitting work into jobs that hide failures or duplicate setup cost without a measured
  normalized-cost benefit.

## Considered Options

* Keep the Linux verification job on `blacksmith-2vcpu-ubuntu-2404` with hosted JUnit parallelism
  disabled.
* Increase the Linux verification job to 4 or 8 vCPU.
* Enable hosted JUnit parallelism on the 2-vCPU runner.
* Combine hosted JUnit parallelism with larger 4-vCPU or 8-vCPU runners.
* Add Maven `-T 1C` to the single-module build.
* Split formatting, static analysis, and tests into separate CI jobs.
* Keep only Linux and Windows verification on Blacksmith and move every other job to standard
  GitHub-hosted runners.

## Decision Outcome

Chosen option: keep the Linux verification job on `blacksmith-2vcpu-ubuntu-2404` and run hosted
JUnit parallelism with `-Djunit.parallel.config.fixed.parallelism=2`. The Surefire and Failsafe
configuration caps JUnit's fixed executor pool at the configured parallelism. This prevents
blocking tests from creating compensating workers beyond the runner's measured capacity.

Runner allocation is separate from runner sizing. The `test` job remains on
`blacksmith-2vcpu-ubuntu-2404`, and `windows-powershell` remains on
`blacksmith-2vcpu-windows-2025`, because they dominate pull-request completion time and benefit from
Blacksmith's faster execution and caching. Every other repository job uses a standard
GitHub-hosted runner. Linux supporting jobs use `ubuntu-24.04`, matching the operating-system
version of the Blacksmith runner they replace. Release Please also uses GitHub-hosted Ubuntu because
it does not gate pull-request completion.

The previous stable hosted test shape completed in 146 seconds on 2 vCPU in both benchmark runs, for
146 normalized seconds. Successful 2-vCPU JUnit-parallel cells measured 97-103 normalized seconds,
which is both faster and cheaper under the Blacksmith free-minute model.

The fastest successful wall-clock cell was 8 vCPU with JUnit parallelism 2 at 73 seconds, but it
cost 292 normalized seconds. That is materially more expensive than the 2-vCPU JUnit-parallel cells.

A 2026-07-21 re-evaluation measured the complete Linux verification job after the suite and curated
OpenRewrite lane had grown. The successful 2-vCPU/parallelism-2 baseline took 414 seconds, or 414
normalized seconds. One successful 4-vCPU/parallelism-4 trial took 247 seconds, or 494 normalized
seconds. The larger runner reduced wall-clock time by about 40% but increased normalized usage by
about 19%, so 2 vCPU remains the more economical choice. An 8-vCPU trial was not justified: it would
have needed to finish the complete job in less than 104 seconds, while the 4-vCPU trial spent 102
seconds on OpenRewrite and another 123 seconds on verification alone. A restored
2-vCPU/parallelism-2 confirmation then completed in 286 seconds, or 286 normalized seconds. The
variation between the two 2-vCPU runs does not change the decision: the confirmation makes the
4-vCPU trial about 14% faster but about 73% more expensive.

The earlier 2-vCPU JUnit-parallel runs were held back because one repeated hosted JUnit 2 cell
failed with a setup server port conflict, JUnit 8 failed, and JUnit 6 hung long enough that the
benchmark run was cancelled. After
[GitHub PR #358](https://github.com/martin-francois/symphony-trello/pull/358) made the test suite
parallel-safe locally,
[GitHub PR #360](https://github.com/martin-francois/symphony-trello/pull/360) enabled the 2-vCPU
JUnit 2 CI shape so the normal hosted PR test job validates the exact candidate before merge.

The Maven build is a single module. Earlier
[GitHub issue #355](https://github.com/martin-francois/symphony-trello/issues/355) measurement
showed `-T 1C` had no meaningful hosted benefit for the full verification job because Maven module
parallelism has no independent modules to schedule.

Splitting the Java verification into additional Blacksmith jobs was not adopted. Local measurement
showed static-analysis-only work is much smaller than the full lifecycle, but splitting it into a
separate hosted job would duplicate checkout, Java setup, Maven cache restore, and dependency
resolution. The existing supporting jobs already separate non-Java checks where that separation
makes failures clearer; those jobs use free GitHub-hosted runners instead of consuming Blacksmith's
limited quota.

The local Maven default remains JUnit parallelism 4 from
[GitHub PR #358](https://github.com/martin-francois/symphony-trello/pull/358). CI intentionally
lowers that default to 2 on the 2-vCPU hosted runner to preserve the best measured normalized-cost
cell.

### Consequences

* Good, because CI uses the lowest normalized-cost runner shape measured for
  [GitHub issue #355](https://github.com/martin-francois/symphony-trello/issues/355).
* Good, because the fastest wall-clock runner shape is rejected when it costs more normalized
  Blacksmith minutes.
* Good, because only the two critical-path verification jobs consume the 3,000 monthly Blacksmith
  minutes.
* Good, because supporting jobs use unlimited standard GitHub-hosted runner time for this public
  repository without delaying completion past the longer verification jobs.
* Good, because developers still get local parallel test execution by default.
* Good, because the fixed executor's maximum pool size matches the requested parallelism instead of
  JUnit's default allowance of 256 compensating workers.
* Bad, because hosted CI now depends on parallel-test isolation remaining durable.
* Bad, because any future hosted flake needs to fix the isolated test or shared state rather than
  silently returning to serial execution.

### Confirmation

Run:

```bash
./mvnw -q -Djunit.parallel.enabled=true -Djunit.parallel.config.fixed.parallelism=2 spotless:check verify
```

That command should pass with CI's hosted JUnit-parallel setting.

The Surefire and Failsafe system properties MUST set
`junit.jupiter.execution.parallel.config.fixed.max-pool-size` to the same Maven property as
`junit.jupiter.execution.parallel.config.fixed.parallelism`. A fixed parallelism value without the
matching maximum pool size does not bound JUnit's compensating worker threads.

The hosted Linux test job should run on `blacksmith-2vcpu-ubuntu-2404` and pass:

```bash
./mvnw -Djunit.parallel.enabled=true -Djunit.parallel.config.fixed.parallelism=2 verify
```

Maintainers can still temporarily pass `-Djunit.parallel.enabled=false` when they need to reproduce
the old serial shape during test isolation debugging.

The only Blacksmith runner labels in `.github/workflows` MUST be:

* `ci.yml:test:blacksmith-2vcpu-ubuntu-2404`; and
* `ci.yml:windows-powershell:blacksmith-2vcpu-windows-2025`.

Repository script tests enforce this allocation across every workflow. All Linux jobs outside those
two critical-path verification jobs MUST use standard GitHub-hosted runners.

## Pros and Cons of the Options

### Keep 2-vCPU Linux Verification with Hosted JUnit Parallelism Disabled

Keep the existing Linux verification runner size and explicitly override the local JUnit-parallel
default in CI.

* Good, because this was the lowest reliable normalized-cost option before hosted parallel
  verification was rechecked.
* Good, because the current CI gate remains equivalent to the existing release-quality
  verification.
* Bad, because it leaves the successful 97-103 second JUnit-parallel measurements unavailable to CI
  even when hosted parallel verification is stable.

### Increase the Linux Verification Runner to 4 or 8 vCPU

Move the full Linux verification job to a larger Blacksmith runner.

* Good, because larger runners can reduce wall-clock time when combined with JUnit parallelism.
* Bad, because no measured larger-runner cell beat the 2-vCPU JUnit-parallel job's normalized cost.
* Bad, because the fastest successful wall-clock cell, 8 vCPU with JUnit parallelism 2, cost 292
  normalized seconds.

### Enable Hosted JUnit Parallelism on the 2-vCPU Runner

Use the same JUnit-parallel direction as the local Maven default in hosted Linux CI.

* Good, because successful 2-vCPU JUnit-parallel cells were faster and cheaper than the previous
  serial CI shape.
* Good, because it keeps the runner size at the best normalized-cost Blacksmith tier.
* Bad, because it relies on the test suite staying parallel-safe on hosted runners.

### Add Maven `-T 1C`

Run Maven with module-level parallelism.

* Good, because it is a standard Maven switch and easy to test.
* Bad, because this is a single-module build, so there are no independent modules for Maven to
  schedule.
* Bad, because measured hosted runs showed no meaningful benefit for the full verification job.

### Split Formatting, Static Analysis, and Tests

Run smaller parts of the Java verification in separate hosted jobs.

* Good, because some failures could report earlier and with a narrower job label.
* Bad, because the required full gate would still need equivalent checks.
* Bad, because separate hosted jobs duplicate setup and cache work without measured normalized-cost
  savings.

## More Information

### Hosted Measurements

The table below uses:

```text
normalized seconds = wall-clock seconds * runner vCPU / 2
```

#### Runner Size and JUnit Matrix

Measured in
[benchmark run 27079999864](https://github.com/martin-francois/symphony-trello/actions/runs/27079999864).

| Runner | JUnit parallelism | Result | Wall seconds | Normalized seconds |
| --- | --- | --- | ---: | ---: |
| 2 vCPU | off | pass | 146 | 146 |
| 2 vCPU | 2 | pass | 98 | 98 |
| 2 vCPU | 4 | pass | 112 | 112 |
| 2 vCPU | 8 | fail | 63 | n/a |
| 4 vCPU | off | pass | 146 | 292 |
| 4 vCPU | 2 | pass | 80 | 160 |
| 4 vCPU | 4 | pass | 81 | 162 |
| 4 vCPU | 8 | pass | 94 | 188 |
| 8 vCPU | off | pass | 140 | 560 |
| 8 vCPU | 2 | pass | 73 | 292 |
| 8 vCPU | 4 | pass | 91 | 364 |
| 8 vCPU | 8 | pass | 87 | 348 |

#### 2-vCPU JUnit Refinement

Measured in
[benchmark run 27080073069](https://github.com/martin-francois/symphony-trello/actions/runs/27080073069).
The JUnit 6 cell was cancelled after it hung long enough to waste benchmark minutes.

| Runner | JUnit parallelism | Repeat | Result | Wall seconds | Normalized seconds |
| --- | --- | ---: | --- | ---: | ---: |
| 2 vCPU | off | 1 | pass | 146 | 146 |
| 2 vCPU | 1 | 1 | pass | 99 | 99 |
| 2 vCPU | 2 | 1 | pass | 97 | 97 |
| 2 vCPU | 2 | 2 | fail | 69 | n/a |
| 2 vCPU | 2 | 3 | pass | 100 | 100 |
| 2 vCPU | 3 | 1 | pass | 99 | 99 |
| 2 vCPU | 3 | 2 | pass | 99 | 99 |
| 2 vCPU | 3 | 3 | pass | 103 | 103 |
| 2 vCPU | 4 | 1 | pass | 173 | 173 |
| 2 vCPU | 4 | 2 | pass | 104 | 104 |
| 2 vCPU | 4 | 3 | pass | 101 | 101 |
| 2 vCPU | 5 | 1 | pass | 104 | 104 |
| 2 vCPU | 6 | 1 | cancelled | 347 | n/a |
| 2 vCPU | 8 | 1 | fail | 66 | n/a |

Future CI runner-size changes should update this ADR with hosted measurements and normalized
seconds, not only wall-clock time.
