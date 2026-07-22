---
status: accepted
date: 2026-07-21
decision-makers: [François Martin, Codex]
consulted:
  - "[ADR 0008](0008-renovate-and-github-actions-hardening.md)"
  - "[ADR 0071](0071-automate-openrewrite-renovate-updates.md)"
  - "[Renovate Dependency Dashboard approval documentation](https://docs.renovatebot.com/configuration-options/#dependencydashboardapproval)"
  - "[Renovate minimum release age documentation](https://docs.renovatebot.com/key-concepts/minimum-release-age/)"
informed: [Future maintainers, Contributors]
---

# Create Renovate Pull Requests Without Dashboard Approval

## Context and Problem Statement

The repository originally used Dependency Dashboard approval as the implementation of human
approval for major dependency updates. This combined two separate decisions: whether Renovate may
create a pull request and whether Renovate may merge it. The intended safeguard was human review and
manual merge after the pull request exists, not a manual checkbox before Renovate creates it.

Package-specific dashboard approval rules also interact poorly with `group:all`. One matched update
can keep the complete grouped branch under `Pending Approval`, including routine updates that do not
need that gate.

How should Renovate create dependency pull requests while preserving manual merge requirements?

## Decision Drivers

* Create every eligible dependency pull request without a maintainer first checking the Dependency
  Dashboard.
* Keep major dependency updates from automerging.
* Preserve manual merge for updates that require extra generated-output review.
* Keep the policy consistent across every package rule and dependency group.
* Prevent one approval-gated update from blocking unrelated members of `group:all`.
* Make the distinction between pull-request creation and merge authorization explicit.

## Considered Options

* Disable Dependency Dashboard approval globally and use `automerge` rules to control merging.
* Require Dependency Dashboard approval for major and special-case updates.
* Allow every dependency update to automerge after CI.

## Decision Outcome

Chosen option: "Disable Dependency Dashboard approval globally and use `automerge` rules to control
merging", because the Dependency Dashboard is not the intended human-review boundary.

`dependencyDashboardApproval` is `false` at the repository level. No package rule overrides it.
Renovate therefore creates every eligible dependency pull request automatically after the
repository-wide seven-day minimum release age passes. `internalChecksFilter: "strict"` prevents a
version whose cooldown is pending from creating a branch or pull request. In `group:all`, that
pending version does not prevent eligible versions from creating or updating the grouped pull
request. Renovate's security-update cooldown bypass remains available for disclosed
vulnerabilities.

Pure `digest` updates remain disabled because Renovate does not apply the minimum release age to
them even though they change executed code. `pin` and `pinDigest` remain enabled because they make
the already selected version or commit immutable without selecting a newer release.
`statusCheckWhen.minimumReleaseAge: "never"` omits the branch-level status; strict filtering decides
version eligibility before branch creation, while the disabled digest rule prevents timestamp-less
code changes from bypassing that filter.

The repository-wide major-update rule keeps `automerge: false`, so any pull request containing a
major update requires human pull-request approval and manual merge. Package-specific rules for
Quarkus update recipes and vendored Tessl guidance also retain `automerge: false` because their
generated or vendored output still requires review. Those manual-merge rules do not delay pull-request
creation.

The Dependency Dashboard remains useful for update visibility and manual retry controls, but it is
not an approval gate. This decision changes contributor automation only and does not change the
runtime contract, so `SPEC.md` does not need an update.

### Consequences

* Good, because maintainers see dependency pull requests without first monitoring and approving a
  dashboard checkbox.
* Good, because major updates still cannot merge without human review.
* Good, because the same dashboard-approval policy applies to all packages and groups.
* Good, because a special-case update cannot hold the complete `group:all` branch in `Pending
  Approval`.
* Bad, because Renovate can open major and special-case pull requests that a maintainer may choose to
  postpone or close.
* Good, because CI does not execute an ordinary dependency version from a Renovate branch until its
  seven-day cooldown passes.
* Neutral, because minimum release age, strict eligibility filtering, CI, branch protection, labels,
  and manual-merge rules remain independent controls.

### Confirmation

This decision remains implemented when:

* repository-level `dependencyDashboardApproval` is `false`;
* no package rule overrides `dependencyDashboardApproval`;
* repository-level `minimumReleaseAge` is `7 days`, `minimumReleaseAgeBehaviour` is
  `timestamp-required`, and `internalChecksFilter` is `strict`;
* the minimum-release-age status is disabled because strict filtering enforces the cooldown before
  branch creation;
* pure `digest` updates are disabled because Renovate cannot age-gate them;
* `pin` and `pinDigest` remain enabled because they freeze already selected code;
* no package rule overrides the repository-wide minimum release age;
* the major-update package rule has `automerge: false`;
* Quarkus update recipe and Tessl tile updates retain their existing manual-merge policy; and
* repository tests reject any package-specific dashboard-approval setting.

## Pros and Cons of the Options

### Disable Dashboard Approval And Control Merging Separately

Renovate creates every eligible pull request. Package rules use `automerge: false` when the pull
request needs human review and manual merge.

* Good, because pull-request visibility does not depend on dashboard maintenance.
* Good, because branch protection and CI run before the maintainer decides whether to merge.
* Good, because creation and merge controls express separate concerns.
* Bad, because more unapproved pull requests can remain open.

### Require Dashboard Approval For Selected Updates

Renovate waits for a dashboard checkbox before creating major or special-case pull requests.

* Good, because postponed updates do not create pull requests.
* Bad, because maintainers must monitor and operate a second approval interface.
* Bad, because one matched update can block an otherwise routine grouped branch.
* Bad, because the dashboard gate is stricter than the intended human merge review.

### Automerge Every Update After CI

Renovate creates and automerges every update when its required checks pass.

* Good, because this requires the least routine maintainer work.
* Bad, because major updates and updates with generated output can merge without the intended human
  review.

## More Information

The original dashboard gate was introduced in commit `e87e38c4` as part of the initial Renovate
configuration. ADR 0008 described the intent as human approval for major updates. ADR 0071 inherited
the same dashboard mechanism for major OpenRewrite updates. The Tessl and Quarkus rules added
dashboard gates for workflows that require generated or vendored output, but their manual-merge
requirements remain enforceable without delaying pull-request creation.
