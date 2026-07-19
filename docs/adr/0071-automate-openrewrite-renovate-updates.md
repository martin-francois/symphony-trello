---
status: accepted
date: 2026-07-19
decision-makers: [François Martin, Codex]
consulted:
  - "[OpenRewrite maintenance lane PR #592](https://github.com/martin-francois/symphony-trello/pull/592)"
  - "[ADR 0070](0070-curated-openrewrite-maintenance-lane.md)"
  - "[Renovate branch update documentation](https://docs.renovatebot.com/updating-rebasing/)"
  - "[GitHub pull-request-target security guidance](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)"
  - "[GitHub workflow-trigger documentation](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)"
informed: [Future maintainers, Contributors]
---

# Automate OpenRewrite Renovate Updates Through Derived Pull Requests

## Context and Problem Statement

ADR 0070 requires a maintainer to approve every OpenRewrite update in Renovate's dependency
dashboard, run OpenRewrite and Spotless locally, commit generated changes, and prove the ordered
fixed point. This work is repeated even when an update produces no repository change.

Adding a generated commit directly to a Renovate branch is not a durable solution. Renovate stops
updating a branch after another author adds a commit. A later recipe release would therefore leave
the edited branch stale until a maintainer intervenes.

The repository needs an update flow that leaves Renovate in control of its source branch, merges
eligible non-major updates with no generated result automatically, and gives a maintainer a complete
generated pull request when review is required. Executing updated Maven plugins must remain separate
from the credential that publishes a branch.

## Decision Drivers

* Require no maintainer action when an eligible update produces no repository change.
* Require only generated-diff review when an update changes source or POM output.
* Leave Renovate's branch untouched so it continues to receive newer releases and base rebases.
* Bind every generated result to an exact Renovate pull-request head.
* Discard stale generation when the source pull request changes during a run.
* Keep Maven and updated recipe artifacts away from repository write credentials.
* Reconcile missed events without depending on a maintainer.
* Keep the seven-day minimum release age and exact Maven version pins.
* Retain the repository-wide manual guard for major dependency updates.
* Fail closed for unexpected authors, repositories, commits, paths, recipes, or non-fixed output.

## Considered Options

* Keep the Renovate source branch untouched and publish generated changes through a derived pull
  request with a short-lived GitHub App token.
* Add a generated commit directly to the Renovate source branch.
* Run the ordered pipeline through Renovate `postUpgradeTasks`.
* Publish a derived pull request with the workflow's default `GITHUB_TOKEN`.
* Keep the manual update procedure from ADR 0070.

## Decision Outcome

Chosen option: "Keep the Renovate source branch untouched and publish generated changes through a
derived pull request with a short-lived GitHub App token".

Renovate groups OpenRewrite engine, plugin, catalog, and selected Picnic recipe updates under the
`OpenRewrite toolchain` group. It creates the pull request without dependency-dashboard approval,
keeps it rebased on `main`, and enables Renovate-controlled automerge for non-major updates. Native
platform auto-merge is disabled for this group, so Renovate requires every branch status to pass
before merging rather than relying only on repository-required checks. The seven-day minimum release
age remains. Major updates retain dependency-dashboard approval and manual merge through the
repository-wide major-update guard. The repository retains the `openrewrite` label that Renovate
adds to this group and the workflow uses as its candidate discriminator.

The `OpenRewrite Renovate automation` workflow treats the Renovate pull request as an unmodified
source:

1. It records `OpenRewrite source provenance` only from an `opened` or `synchronize` webhook whose
   actual sender is `renovate[bot]`, and scheduled or manual work requires that
   GitHub-Actions-created status on the exact source SHA.
2. It accepts only a same-repository pull request authored by `renovate[bot]` on the explicit
   `renovate/openrewrite-toolchain` branch, based on `main`, labelled `openrewrite`, and containing
   one Renovate-authored commit directly on the current base that changes only `pom.xml`.
3. Trusted code from the base branch proves that the POM diff changes one or more exact
   OpenRewrite toolchain version properties and nothing else. The allowlist includes the
   compiler-side Picnic property because Renovate extracts its annotation-processor and Refaster
   runner declarations without a Maven dependency type.
4. A read-only job checks out only the trusted base, materializes the exact validated Renovate
   `pom.xml`, and establishes that tree as a local baseline. It discovers recipes, applies
   OpenRewrite, applies Spotless, repeats the ordered pipeline, compares the two patches, and runs
   the full Maven gate without checking out source-branch code. Maven executes inside a pinned,
   unprivileged container that receives no runner environment, GitHub token, cache token, Docker
   socket, or repository write credential and mounts only the workspace plus fresh cache and output
   subdirectories that exclude runner command files. The host accepts only the expected regular
   output files after the container exits.
5. Regular pull-request CI routes Maven execution for the Renovate source and generated branches
   through the same pinned isolation model while retaining the required `test` job name. It
   disables checkout credentials, mounts `.git` read-only, skips native Windows Maven execution,
   and defers dependency submission until the update reaches `main`.
6. A separate job validates the reconciliation artifact and publishes statuses. Updated Maven code
   never receives its status-write token.
7. When an eligible non-major update has an empty generated patch, the workflow publishes an
   `OpenRewrite update validation` success on the exact Renovate head. Required CI stays green and
   Renovate automerges its untouched dependency-only pull request.
8. When the generated patch is nonempty, CI on the untouched Renovate pull request remains blocked
   by the existing fixed-point check and a failed update-validation status. A separate publication
   job applies the verified patch to a branch named for the source pull-request number and creates
   or refreshes a generated pull request.
9. The generated pull request contains the exact source SHA, version-property changes, generated
   paths, validation commands, compatibility decision, and commit-history decision. A maintainer
   reviews and merges this pull request. No local generation or follow-up commit is required.
10. A later Renovate source SHA cancels the older run and replaces the generated branch. A required
   `OpenRewrite source freshness` status turns pending for the old generated head until its parent
   matches the current source SHA. Repository rules dismiss stale approval when the generated diff
   changes.
11. A closed-source pull request or a newer update with no generated result closes the obsolete
   generated pull request and removes its branch.

The workflow has a scheduled reconciliation and a manual dispatch input. These paths repair missed
pull-request events and mark an existing stale generated head pending before starting regeneration.
Per-source concurrency cancels older work. Immediately before writing, the publication job
revalidates the source repository, author, branch, base, label, state, and SHA, and the push uses
`--force-with-lease`. When the existing generated commit already has the current source as its
single parent and the verified generated tree is identical, reconciliation performs no commit,
push, or pull-request edit. Scheduled repair therefore does not rerun CI or dismiss approval for
unchanged output.

Pre-merge Maven execution for both automation branches runs inside an isolated container with no
runner or repository credential. Dependency submission and native Windows Maven tests resume only
after the validated or reviewed update reaches `main`. The publication job receives a short-lived
installation token from a repository-scoped GitHub App and does not execute Maven or code from the
source branch. An eligible non-major no-result update with no prior generated state does not request
the token, so it still automerges when the App is unavailable. The App requires repository contents
and pull-request write permission. It must not bypass the rules for `main`. The publisher
independently validates artifact source identity, paths, file modes, and file manifest before using
artifact input with Git or GitHub.

New upstream recipe leaves remain inactive by default. Discovering them does not block a toolchain
update because they cannot execute until `rewrite.yml` selects them. Recipe curation remains a
separate maintenance decision. A removed or renamed active recipe still fails discovery or
execution.

This decision changes contributor automation only. It does not change a supported runtime or user
contract, so `SPEC.md` does not need an update.

### Consequences

* Good, because eligible non-major no-result OpenRewrite updates merge without maintainer work.
* Good, because generated changes arrive in a complete reviewable pull request.
* Good, because Renovate remains able to replace its source commit when a newer release arrives.
* Good, because stale generated output is canceled, merge-blocked by a source-freshness status,
  source-SHA checked, and force-with-lease protected.
* Good, because updated Maven code executes inside an isolated container without runner,
  repository, or cache credentials.
* Good, because inactive upstream recipe additions do not create mandatory catalog-review work.
* Good, because major updates retain explicit maintainer approval even when the current generated
  result is empty.
* Bad, because updates with generated output temporarily have a blocked Renovate source pull
  request and a separate generated pull request.
* Bad, because repository owners must create and install one narrowly scoped GitHub App and keep its
  private key in Actions secrets.
* Bad, because a grouped update can require separating one defective artifact before the remaining
  artifacts can merge.
* Neutral, because the existing CI fixed-point check remains the final merge guard.

### Confirmation

This decision remains implemented when:

* OpenRewrite dependencies retain exact POM properties, a seven-day minimum release age, and the
  repository-wide major-update guard;
* the repository retains the exact `openrewrite` label used by Renovate and reconciliation;
* Renovate creates grouped non-major OpenRewrite pull requests without dashboard approval and
  uses the explicit `renovate/openrewrite-toolchain` branch while permitting Renovate-controlled
  automerge with native platform auto-merge disabled;
* an eligible non-major no-result update passes required CI and merges without a generated pull
  request;
* a result-producing update leaves the Renovate source branch untouched and creates or refreshes one
  generated pull request;
* source validation rejects forks, non-Renovate authors, extra commits, and non-version POM changes;
* source provenance depends on the webhook sender and a GitHub-Actions-created status, not Git author
  metadata;
* generation discovers active recipes, reaches the ordered fixed point, and passes the full gate;
* pre-merge Maven runs for the source and generated branches execute in a pinned, unprivileged
  container without runner, repository, or cache credentials, while publication uses a separate
  short-lived App token;
* publication revalidates every source eligibility field immediately before writing and uses
  force-with-lease;
* `OpenRewrite update validation` succeeds on the source head only after the full no-result gate;
* `OpenRewrite source freshness` is a required status and reports success as not applicable for
  non-generated pull requests;
* unrelated Renovate pull requests receive not-applicable success for all custom contexts;
* scheduled reconciliation marks stale output pending before generation and repairs missed events;
* unchanged reconciliation preserves the generated commit, CI result, and maintainer approval;
* changed generated branches invalidate stale maintainer approval; and
* inactive newly discovered recipe leaves do not block a dependency update.

## Pros and Cons of the Options

### Untouched Source And Derived Generated Pull Request

This option keeps Renovate's dependency-only branch unchanged. A separate privileged job publishes
only a patch that a read-only job already generated and verified.

* Good, because Renovate continues to handle new releases and rebases.
* Good, because credential separation limits the impact of executing updated recipe artifacts.
* Good, because the maintainer interacts only with a generated pull request when code changes.
* Bad, because result-producing updates create two related pull requests.
* Bad, because the workflow needs a repository-scoped GitHub App.

### Generated Commit On The Renovate Branch

This option lets a workflow add OpenRewrite and Spotless output directly to Renovate's branch.

* Good, because the maintainer sees one pull request.
* Bad, because Renovate stops updating a branch after another author adds a commit.
* Bad, because another workflow must detect and recover every superseding release.

### Renovate Post-Upgrade Task

This option runs the repository's ordered pipeline before Renovate creates its own commit.

* Good, because Renovate keeps one managed commit containing dependency and generated changes.
* Bad, because hosted Renovate permits only administrator-approved commands whose availability is
  not a repository contract.
* Bad, because the updated Maven toolchain executes in Renovate's write-capable environment.

### Default GitHub Token Publication

This option uses the workflow's `GITHUB_TOKEN` to push the generated branch and create its pull
request.

* Good, because it needs no additional App or stored private key.
* Bad, because pushes made with `GITHUB_TOKEN` do not normally trigger a new workflow run.
* Bad, because reconstructing every required pull-request check through dispatch events adds a
  second CI orchestration contract.

### Manual Update Procedure

This option retains dashboard approval, local generation, manual commit, and complete review for
every toolchain version.

* Good, because it needs no branch-writing automation.
* Bad, because no-result updates consume the same maintainer steps as semantic changes.
* Bad, because an unattended update does not progress until a maintainer notices it.

## More Information

The [OpenRewrite maintenance guide](../openrewrite.md) documents the operational flow and one-time
GitHub App setup. [ADR 0070](0070-curated-openrewrite-maintenance-lane.md) continues to own the
curated recipes and ordered fixed-point contract.
