---
status: accepted
date: 2026-10-31
decision-makers: [François Martin]
consulted:
  - "[ADR 0071](0071-automate-openrewrite-renovate-updates.md)"
  - "[ADR 0140](0140-keep-caches-and-pull-request-code-out-of-privileged-jobs.md)"
  - "[GitHub Actions security](../agents/github-actions-security.md)"
  - "[GitHub issue #1176](https://github.com/martin-francois/symphony-trello/issues/1176)"
  - "[maven-dependency-submission-action v6.0.1](https://github.com/advanced-security/maven-dependency-submission-action/tree/a64327a7329c9939cf675e458452febe1894a70c)"
  - "[GitHub REST API: create a dependency snapshot](https://docs.github.com/en/rest/dependency-graph/dependency-submission#create-a-snapshot-of-dependencies-for-a-repository)"
informed: [Future maintainers, Contributors]
---

# Submit Maven dependencies only from main

## Context and Problem Statement

`.github/workflows/dependency-submission.yml` ran on pushes to `main` and on pull requests that
changed `pom.xml`, `.mvn/jvm.config` or the workflow itself. Its one job held `contents: write` and
ran `advanced-security/maven-dependency-submission-action`, which runs Maven on the checked-out
`pom.xml` and posts the resulting dependency snapshot to GitHub.

On a fork pull request GitHub drops the write permission, so the POST fails with 403 and the check
turns red for an outside contributor who touched `pom.xml`. On a same-repository pull request the
job ran pull request code with a write token. The audit in
[ADR 0140](0140-keep-caches-and-pull-request-code-out-of-privileged-jobs.md) accepted that, since
such an author already holds write access, and left the choice to the owner in
[GitHub issue #1176](https://github.com/martin-francois/symphony-trello/issues/1176).

Should dependency submission run on pull requests at all?

## Decision Drivers

* No pull request code runs with a write token.
* An outside pull request that changes `pom.xml` does not get a red check it cannot fix.
* Every action stays pinned by full SHA, and no step code moves into the workflow file.
* The dependency graph of `main` stays complete, with transitive Maven dependencies.

## Considered Options

* Drop the `pull_request` trigger and submit only on pushes to `main`.
* Split the work: a `contents: read` pull request job builds the snapshot and uploads it as an
  artifact, and a push-to-`main` job submits.
* Keep the workflow as it was.

## Decision Outcome

Chosen option: drop the `pull_request` trigger and submit only on pushes to `main`.

The owner first chose the split. Reading the pinned action showed it cannot work: v6.0.1, the
latest release, has no input to build a snapshot without submitting it. `src/index.ts` calls
`submitSnapshot` as soon as Maven succeeds, and the snapshot never reaches a file; it exists only as
the `snapshot` step output, set inside `submitSnapshot` right before the POST. A `contents: read`
job therefore fails with 403 like a fork run does. Even with a working snapshot-only step, an
artifact never reaches GitHub's dependency graph, and nothing in this repository reads pull request
snapshots: no workflow uses `dependency-review-action`.

The workflow now has only the `push` trigger on `main`. The job keeps `contents: write`, the
pinned checkout, Java setup and action. Its `if` guard, which skipped Release Please and OpenRewrite
pull request branches by `github.head_ref`, went with the trigger: `github.head_ref` is empty on a
push, so the guard could no longer skip anything.

### Consequences

* Good, because no pull request code runs in a job with a write token.
* Good, because fork pull requests that change `pom.xml` no longer show a failing check.
* Good, because the OpenRewrite and Release Please branches need no special case here.
* Bad, because a pull request no longer shows whether the action can build the snapshot. A `pom.xml`
  change that breaks the depgraph plugin surfaces on `main` after the merge. The required `test`
  job still runs Maven on every such pull request.
* Neutral, because GitHub's own parsing of `pom.xml` still adds the direct dependencies to the
  dependency graph. The transitive ones arrive with the next submission from `main`.

### Confirmation

`scripts/github-actions-security.test.ts` asserts that the workflow's only trigger is a push to
`main`. Run:

```bash
pnpm run verify:scripts
```

The audit table in [GitHub Actions security](../agents/github-actions-security.md#audit-of-2026-10-30)
carries the workflow's current row.

## Pros and Cons of the Options

### Submit only on pushes to main

Remove the `pull_request` trigger. The existing job runs only after a push to `main`, builds the
snapshot with the pinned action and submits it with `contents: write`.

* Good, because the workflow gets smaller and has no pull request path to guard.
* Good, because it needs no new action, artifact or permission.
* Bad, because pull requests lose the early warning that the snapshot fails to build.

### Build on pull requests, submit on main

Split the workflow into two jobs. On pull requests a job with `contents: read` builds the snapshot
and uploads it as an artifact; on pushes to `main` a second job builds and submits it.

* Good, because a pull request would still run the depgraph plugin.
* Bad, because the pinned action always submits, so a read-only job fails with 403. Making it pass
  would need `continue-on-error` and a check on the step output, which sends a doomed POST and logs
  an error annotation on every run, or running `com.github.ferstl:depgraph-maven-plugin` by hand,
  which duplicates what the action does.
* Bad, because the uploaded artifact has no reader. Submitting it for the pull request head would
  need a third job on `workflow_run` with `contents: write` that consumes pull request output, a
  privileged run that [GitHub Actions security](../agents/github-actions-security.md) would then
  have to cover.

### Keep the workflow as it was

Leave one job with `contents: write` that builds and submits the snapshot both on pushes to `main`
and on pull requests that change `pom.xml`, `.mvn/jvm.config` or the workflow.

* Good, because nothing changes, and GitHub drops the write token for fork runs.
* Bad, because fork pull requests that change `pom.xml` keep a red check.
* Bad, because same-repository pull request code keeps running with `contents: write`.

## More Information

[ADR 0140](0140-keep-caches-and-pull-request-code-out-of-privileged-jobs.md) left this choice open
in its audit. [ADR 0071](0071-automate-openrewrite-renovate-updates.md) kept dependency submission
off the OpenRewrite Renovate branches; with no pull request trigger, that exception is no longer
needed. [ADR 0007](0007-java-maven-toolchain-and-warning-handling.md) explains why the
repository owns this workflow instead of GitHub's generated Automatic Dependency Submission.
