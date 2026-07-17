---
status: accepted
date: 2026-07-18
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #590](https://github.com/martin-francois/symphony-trello/issues/590)"
  - "[GitHub PR #591](https://github.com/martin-francois/symphony-trello/pull/591)"
  - "[OpenClaw size-label workflow](https://github.com/openclaw/openclaw/blob/56791a4806997cf8b09956b437b9b55f0c53c641/.github/workflows/labeler.yml#L62-L151)"
  - "[CodelyTV Pull Request Size Labeler](https://github.com/CodelyTV/pr-size-labeler/tree/095a41fca88b8764fd9e008ad269bcdb82bb38b9)"
  - "[pascalgn Size Label Action](https://github.com/pascalgn/size-label-action/tree/56b489b027932ec0cf60438a1a5f1a19c8fc71ff)"
  - "[cbrgm PR Size Labeler Action](https://github.com/cbrgm/pr-size-labeler-action/tree/87f4b43f2988ba7b3d5a2b05eea7005c35920dcd)"
  - "[GitHub Labeler](https://github.com/actions/labeler/tree/b8dd2d9be0f68b860e7dae5dae7d772984eacd6d)"
  - "[GitHub Script](https://github.com/actions/github-script/tree/3a2844b7e9c422d3c10d287c895573f7108da1b3)"
  - "[GitHub pull_request_target documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#pull_request_target)"
informed: [Future maintainers, Contributors]
---

# Use Repository-Owned Metadata-Only Pull Request Size Labeling

## Context and Problem Statement

[GitHub issue #590](https://github.com/martin-francois/symphony-trello/issues/590)
requires pull requests to receive one size label from the complete GitHub additions-plus-deletions
count. The label must be recalculated when the pull request opens, reopens, changes base, or receives
commits. The automation must preserve unrelated labels, handle forks without running pull-request
code in a privileged context, and repair labels after suppressed events, base-diff changes, or
transient failures. Release Please pull requests must retain their no-check contract.

The repository already uses a full-SHA-pinned
[GitHub Script](https://github.com/actions/github-script/tree/3a2844b7e9c422d3c10d287c895573f7108da1b3)
action. The design research also examined OpenClaw's repository-owned GitHub Script implementation
and maintained dedicated size-label actions from CodelyTV, pascalgn, and cbrgm. GitHub's official
path-based Labeler action was checked as another premade option.

A dedicated size action handles the basic threshold calculation with less repository code. It does
not by itself satisfy this repository's trusted-event routing, Release Please, reconciliation,
failure-ordering, and concurrent-label-preservation requirements. Which implementation should own
pull-request size labeling and its recovery paths?

## Decision Drivers

* Count the complete GitHub diff as additions plus deletions, including tests and documentation.
* Apply the repository's inclusive boundaries and exact space-prefixed label names.
* Preserve unrelated labels and avoid removing the previous size label before the replacement
  succeeds.
* Recalculate promptly for ordinary pull-request changes and eventually repair missed or stale
  labels.
* Label fork pull requests without checking out or executing pull-request code in a privileged job.
* Keep actual Release Please pull requests free of a new required or visible size-labeler check.
* Resolve direct, missing, and multiple workflow associations from trusted metadata.
* Use least-privilege permissions and immutable full commit pins.
* Validate identifiers and API data before label mutations.
* Execute the exact deployed classification and routing scripts in local tests.
* Avoid a new dedicated action or container dependency when it does not remove the repository-owned
  orchestration.

## Considered Options

* Run repository-owned JavaScript through the existing pinned GitHub Script action and combine
  immediate events with trusted and scheduled reconciliation.
* Use CodelyTV's Pull Request Size Labeler.
* Use pascalgn's Size Label Action.
* Use cbrgm's PR Size Labeler Action.
* Use GitHub's official path-based Labeler.
* Keep pull-request size labels manual.

## Decision Outcome

Chosen option: run repository-owned classification and reconciliation JavaScript through the
existing full-SHA-pinned GitHub Script action.

The implementation adapts OpenClaw's metadata-only pattern and threshold bands. It deliberately
counts every file, uses the repository's existing `size XS` through `size XL` labels, and reads
aggregate additions and deletions from the current pull request. It does not copy the linked issue's
estimated size.

The workflow uses three trusted routes:

* `pull_request_target` handles open, reopen, synchronize, and base-edit events, including forks,
  merge conflicts, and skip directives. The job reads event metadata and never checks out or
  executes pull-request code.
* A `workflow_run` route follows successful or failed Commitlint completion for ordinary pull
  requests whose paths are excluded from the immediate route. It verifies direct associations or
  resolves the exact open pull request from trusted repository, branch, and head-SHA data.
* A schedule reads all open pull requests from the default branch. It repairs suppressed events,
  base-branch diff drift, transient API failures, pre-existing pull requests, and the residual case
  where every changed path is excluded from both immediate workflows.

The resolver deduplicates pull-request numbers, rejects invalid identities, and limits a scheduled
matrix to GitHub's 256-job maximum. Empty results do not start label jobs. Label jobs use
`fail-fast: false` and a non-cancelling per-pull-request concurrency group, so one pull request does
not suppress another and an older in-flight mutation is allowed to finish.

The label step validates the pull-request number and non-negative safe-integer change counts before
mutation. It paginates existing labels, adds the target label first, and then removes only stale
managed size labels. An add failure therefore leaves the previous labels intact. Unrelated labels
are never replaced from a stale snapshot. A later event or scheduled run repairs a stale managed
label whose removal failed.

CodelyTV's action is maintained and can express the complete-diff count and exact boundaries with
configuration. It was not chosen because its current entry point obtains the pull-request number
only from a pull-request event file, while this repository also needs explicit trusted resolution
for workflow and schedule events. Its current label update reads all labels and then replaces the
complete label set with one `PUT`; a concurrent unrelated-label update can be overwritten. The
action also introduces a Docker build and runtime dependency while leaving the routing and
reconciliation logic in this repository.

pascalgn's action was not chosen because its current implementation hardcodes `size/` label names,
reads a single unpaginated changed-file page, and does not handle a base-changing edit event. cbrgm's
action accepts an explicit pull-request number and configurable label names, but it reads a checked
out configuration file, reads a single unpaginated changed-file page, and its pinned action metadata
starts an executable container through a mutable `v1` tag. GitHub's official Labeler was not chosen
because it classifies changed paths rather than changed-line counts.

This decision does not prohibit a dedicated size action permanently. Supersede this ADR when a
maintained action satisfies the exact identity, counting, label-mutation, trigger, reconciliation,
pinning, and local-test contracts with less repository-owned glue.

### Consequences

* Good, because every size-label contract row and failure order is visible and executable in local
  tests.
* Good, because privileged jobs use trusted metadata and do not check out or execute pull-request
  code.
* Good, because unrelated labels are preserved without replacing a stale full-label snapshot.
* Good, because prompt events and scheduled reconciliation provide both fast feedback and eventual
  repair.
* Good, because the repository reuses one already-approved, immutable-SHA-pinned official action
  instead of adding a dedicated action or container.
* Good, because Release Please pull requests remain free of the size-labeler check while scheduled
  reconciliation still labels them.
* Bad, because the repository owns substantial GitHub-event, API, and failure-handling JavaScript.
* Bad, because exact-script tests must remain synchronized with both embedded workflow scripts.
* Bad, because reconciliation consumes scheduled GitHub Actions capacity and repairs residual cases
  only after the next schedule.
* Bad, because the Release Please exclusion list and trusted Commitlint workflow name are coupled to
  other repository workflows.
* Bad, because the default-branch event model prevents a live execution of a proposed workflow
  version before merge.

### Confirmation

Run the exact-script and repository workflow tests:

```bash
corepack pnpm run verify:scripts
./mvnw -q -Dtest=AdrConformanceTest,ReleaseWorkflowTest test
```

Run the full project and documentation checks:

```bash
./mvnw -q clean spotless:check verify
corepack pnpm dlx markdownlint-cli2 "**/*.md" "#node_modules"
```

Run `actionlint` on `.github/workflows/size-labeler.yml`. Inspect that every `uses:` reference is a
full commit SHA, job permissions are limited to the required pull-request access, and the workflow
does not contain a checkout step or execute head-branch content.

The script tests must extract and execute both exact JavaScript blocks from the workflow. They must
cover every size boundary, additions plus deletions, direct and fallback identity resolution, forks,
empty and multiple associations, scheduled reconciliation, the 256-job boundary, validation before
mutation, add-before-remove ordering, idempotence, unrelated-label preservation, pagination,
concurrency, and API failures.

Hosted checks validate the proposed workflow as repository content. The first live
`pull_request_target`, `workflow_run`, or scheduled execution of that exact version occurs only
after the workflow reaches the default branch.

## Pros and Cons of the Options

### Run Repository-Owned JavaScript Through GitHub Script

Keep the size-label and reconciliation logic in the workflow and execute it with the existing
full-SHA-pinned official GitHub Script action.

* Good, because the exact repository contract is implemented without adapting a dedicated action's
  event or label model.
* Good, because the deployed scripts run unchanged in local tests.
* Good, because the implementation uses aggregate pull-request counts and avoids changed-file
  pagination limits.
* Good, because validation and add-before-remove ordering are under repository control.
* Bad, because maintainers own the scripts, tests, and three-route event model.
* Bad, because inline workflow JavaScript is larger than a configured dedicated action step.

### Use CodelyTV Pull Request Size Labeler

Configure CodelyTV's maintained Docker action with this repository's labels and threshold values,
then add repository-owned workflow routes around it.

* Good, because the action already supports configurable names, thresholds, deletion counting, and
  `pull_request_target`.
* Good, because its aggregate mode calculates additions plus deletions without listing changed
  files.
* Bad, because it expects pull-request event data and does not remove the need for trusted fallback
  and schedule resolution.
* Bad, because replacing the complete label snapshot can overwrite an unrelated concurrent update.
* Bad, because it adds a dedicated Docker action and runtime supply-chain surface.

### Use pascalgn Size Label Action

Configure pascalgn's compiled JavaScript action for changed-line thresholds and run it from a
pull-request-target workflow.

* Good, because its action source is fully pinnable and it adds a new label before removing old
  size labels.
* Good, because it has configurable numeric thresholds and ignored-file patterns.
* Bad, because it emits hardcoded slash-prefixed names instead of the accepted labels.
* Bad, because one unpaginated file request does not classify the complete diff for larger pull
  requests.
* Bad, because its event handling does not cover base-changing edits or the required reconciliation
  routes.

### Use cbrgm PR Size Labeler Action

Check out a configuration file and invoke cbrgm's container action with an explicit pull-request
number.

* Good, because it accepts explicit pull-request identity and configurable labels.
* Good, because it supports both changed-line and changed-file thresholds.
* Bad, because this repository does not want file count to change the line-based classification.
* Bad, because one unpaginated file request does not classify the complete diff for larger pull
  requests.
* Bad, because the pinned action metadata launches a mutable `v1` container image.
* Bad, because reading repository configuration requires a trusted checkout and still leaves event
  resolution and reconciliation outside the action.

### Use GitHub Official Labeler

Configure GitHub's official Labeler action and keep size labels in its normal path-based rules.

* Good, because it is official, maintained, and fully pinnable.
* Good, because it already fits metadata-only pull-request label workflows.
* Bad, because it labels by changed paths rather than additions plus deletions.
* Bad, because it has no numeric changed-line threshold contract.

### Keep Pull Request Size Labels Manual

Let maintainers add and update pull-request size labels during review without automation.

* Good, because no workflow code, action dependency, or scheduled capacity is needed.
* Bad, because labels become stale as commits and the base branch change.
* Bad, because manual classification does not consistently enforce exact boundaries.
* Bad, because there is no repair path for missing or conflicting managed labels.

## More Information

[GitHub issue #590](https://github.com/martin-francois/symphony-trello/issues/590)
owns the pull-request size-label contract.
[GitHub PR #591](https://github.com/martin-francois/symphony-trello/pull/591)
implements this decision.

The
[OpenClaw workflow](https://github.com/openclaw/openclaw/blob/56791a4806997cf8b09956b437b9b55f0c53c641/.github/workflows/labeler.yml#L62-L151)
is design evidence, not a runtime dependency. Symphony for Trello retains its threshold bands but
does not copy OpenClaw's documentation and lockfile exclusions or stale-label-first mutation order.

This automation changes repository-maintenance metadata only. It does not change `SPEC.md`, the
application runtime, or a supported product compatibility contract.
