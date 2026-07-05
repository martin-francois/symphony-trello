---
status: accepted
date: 2026-07-05
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub Actions billing and usage](https://docs.github.com/en/actions/concepts/billing-and-usage)"
  - "[GitHub Actions workflow syntax: schedule](https://docs.github.com/actions/using-workflows/workflow-syntax-for-github-actions#onschedule)"
  - "[GitHub Actions limits](https://docs.github.com/actions/reference/limits)"
  - "[ADR 0061](0061-jazzer-and-oss-fuzz-readiness.md)"
informed: [Future maintainers, Contributors]
---

# Run Scheduled GitHub-Hosted Continuous Fuzzing

## Context and Problem Statement

ADR 0061 adds deterministic Jazzer regression tests and OSS-Fuzz-ready standalone fuzz targets. Those
checks protect pull requests and prepare the project for hosted OSS-Fuzz, but OSS-Fuzz coverage may
not start immediately after the repository becomes public.

How should the project run coverage-guided fuzzing between public release and hosted OSS-Fuzz
acceptance without slowing required pull request CI or consuming limited Blacksmith resources?

## Decision Drivers

* Keep required pull request CI near the repository's five-minute target.
* Avoid using limited Blacksmith runner capacity for long-running fuzzing.
* Use a runner class that is free for standard public-repository GitHub Actions usage.
* Stay below the GitHub-hosted job execution limit.
* Run only from `main` so fuzz failures match released repository history.
* Create actionable GitHub issues when scheduled fuzzing finds a failure.
* Avoid duplicate issue spam for the same fuzz failure.

## Considered Options

* Scheduled GitHub-hosted continuous Jazzer workflow.
* Required pull request continuous fuzzing.
* Blacksmith-hosted scheduled fuzzing.
* Wait for OSS-Fuzz before running continuous fuzzing.
* Self-hosted runner.

## Decision Outcome

Chosen option: scheduled GitHub-hosted continuous Jazzer workflow.

The repository adds `.github/workflows/continuous-fuzzing.yml`. The workflow runs on `main` only,
using `ubuntu-latest` rather than a Blacksmith runner. GitHub documents standard GitHub-hosted
runners as free in public repositories, scheduled workflows as running from the default branch, and
GitHub-hosted jobs as capped at six hours. The workflow therefore schedules one run every six hours,
uses a 330-minute active fuzzing budget, and sets a 350-minute job timeout. That leaves room for
checkout, Maven startup, artifact upload, and issue creation before the six-hour cap.

Each run divides the active fuzzing budget across the current public Jazzer JUnit fuzz targets:

* `RepositorySourceResolverFuzzTest#labelledRepositorySourceValueCannotBreakSelectionInvariants`
* `RepositorySourceResolverFuzzTest#cardTextDeclarationScanCannotBreakSelectionInvariants`
* `TrelloCardReferenceParserFuzzTest#trelloReferenceParsingKeepsLookupIdsAndUrlsStable`
* `TrelloCardReferenceParserFuzzTest#checklistClassificationNeverEmitsPrerequisitesWithProblems`
* `WorkflowLoaderFuzzTest#workflowLoaderHandlesArbitraryWorkflowBytes`

If a target fails, the workflow uploads the collected logs and any generated Jazzer crash, timeout,
OOM, or leak artifacts. It creates a GitHub issue with the `bug` and `fuzzed` labels, the workflow
run URL, commit SHA, failed target, reproduction command, log tail, artifact name, and a SHA3-256
fingerprint. If an open `fuzzed` issue already contains the same fingerprint, the workflow comments
on that issue instead of creating a duplicate.

This scheduled workflow is not a replacement for OSS-Fuzz. It is an interim hosted fuzzing loop that
uses the repository's JUnit Jazzer targets until the external OSS-Fuzz project runs the standalone
`fuzzerTestOneInput` targets.

### Consequences

* Good, because long fuzzing runs do not slow required pull request CI.
* Good, because Blacksmith minutes remain reserved for the short deterministic CI gate.
* Good, because fuzz failures become GitHub issues with enough detail to reproduce locally.
* Good, because repeated failures use a fingerprint to avoid creating the same issue every run.
* Neutral, because GitHub scheduled workflows can be delayed or dropped under platform load.
* Neutral, because only the latest default-branch state is continuously fuzzed.
* Bad, because a scheduled workflow can still consume significant hosted-runner time after the
  repository becomes public.
* Bad, because issue creation depends on repository issues and `GITHUB_TOKEN` issue permissions.

### Confirmation

Run:

```bash
./mvnw -q -Dtest=ContinuousFuzzingWorkflowTest test
./mvnw -q spotless:check verify
```

Review should confirm `.github/workflows/continuous-fuzzing.yml` uses `ubuntu-latest`, not
Blacksmith; runs only on `main`; grants `issues: write`; runs every six hours; uses a 330-minute
active fuzzing budget with a 350-minute job timeout; and creates or updates `bug` + `fuzzed` issues
when a fuzz target fails.

## Pros and Cons of the Options

### Scheduled GitHub-Hosted Continuous Jazzer Workflow

Run active Jazzer fuzzing from a scheduled GitHub Actions workflow on the default branch.

* Good, because it starts once the repository is public and the workflow is on `main`.
* Good, because it avoids Blacksmith for long-running work.
* Good, because it can file issues directly with failure details.
* Bad, because scheduled workflows are not guaranteed to start exactly on time.
* Bad, because it only fuzzes committed `main`, not each pull request.

### Required Pull Request Continuous Fuzzing

Run long coverage-guided fuzzing as part of every pull request.

* Good, because pull requests would get strong fuzzing feedback before merge.
* Bad, because it contradicts the five-minute required CI target.
* Bad, because long fuzzing runs would make ordinary contributions slow and expensive to review.

### Blacksmith-Hosted Scheduled Fuzzing

Use the same Blacksmith runner class as the ordinary CI jobs for scheduled fuzzing.

* Good, because it would keep runner behavior close to normal CI.
* Bad, because Blacksmith capacity is intentionally limited and should stay focused on short gates.
* Bad, because long fuzzing is a poor fit for a cost-optimized PR verification runner.

### Wait for OSS-Fuzz

Do not add a scheduled workflow and rely on hosted OSS-Fuzz after the project is accepted.

* Good, because OSS-Fuzz is the best long-term continuous fuzzing platform.
* Good, because it avoids adding another GitHub Actions workflow.
* Bad, because coverage may be absent until the external project is accepted and running.

### Self-Hosted Runner

Run fuzzing continuously on a maintainer-owned host.

* Good, because it can run longer than the GitHub-hosted job limit.
* Bad, because it adds operational ownership, credentials, patching, and host-security work.
* Bad, because it is less transparent to external contributors than GitHub-hosted workflow runs.

## More Information

Keep the required CI gate deterministic and short. If scheduled continuous fuzzing becomes noisy,
reduce the schedule, improve de-duplication, or move long-running coverage to OSS-Fuzz rather than
making pull request CI slower.
