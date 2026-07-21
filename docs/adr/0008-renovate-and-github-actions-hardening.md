---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted:
  - "[Renovate minimum release age documentation](https://docs.renovatebot.com/key-concepts/minimum-release-age/)"
  - GitHub Actions hardening guidance
informed: [Future maintainers]
---

# Use Minimal Renovate Configuration with SHA-Pinned GitHub Actions

The major-update publication part of this decision is amended by
[ADR 0072](0072-create-renovate-prs-without-dashboard-approval.md). Major updates still require human
pull-request approval and manual merge, but Renovate now creates their pull requests without a
Dependency Dashboard approval step. The action-pinning and minimal-configuration decisions remain
accepted.

## Context and Problem Statement

The project should keep dependencies current with low maintenance overhead, but GitHub Actions tags
are mutable and Renovate configuration can become noisy if it repeats inherited defaults. The project
also uses pinned pnpm and commitlint versions in workflow commands without a `package.json`.

How should dependency automation stay secure, maintainable, and clear for a Java repository?

## Decision Drivers

* Keep Renovate configuration minimal and avoid restating inherited defaults.
* Pin GitHub Actions to immutable full commit SHAs.
* Let Renovate update pinned actions and the Corepack pnpm version.
* Apply one seven-day release-age cooldown to every ordinary dependency update.
* Prevent Renovate from creating a branch or pull request for a version whose cooldown is pending,
  so repository CI does not execute that version's code during the cooldown.
* Continue creating pull requests for eligible versions even when newer versions in `group:all`
  remain in cooldown.
* Require human approval for major updates.
* Avoid adding `package.json` solely to run a JavaScript CLI in a Java project.
* Enforce release-note-ready pull request titles and retained pull request commit messages without
  making Maven verification depend on Node tooling.

## Considered Options

* Minimal Renovate config with action SHA pinning and regex managers for pnpm and commitlint.
* Unpinned GitHub Actions tags.
* Commit a `package.json` only to pin pnpm.
* Verbose Renovate package rules for every inherited policy.

## Decision Outcome

Chosen option: "Minimal Renovate config with action SHA pinning and regex managers for pnpm and
commitlint",
because it hardens CI and keeps automation behavior explicit only where it differs from presets.

The repository-level `minimumReleaseAge` is seven days and is the only release-age setting.
`minimumReleaseAgeBehaviour: "timestamp-required"` treats a version without a release timestamp as
ineligible. `internalChecksFilter: "strict"` excludes ineligible versions before Renovate creates a
branch or pull request. With `group:all`, Renovate still creates or updates the grouped pull request
from versions that have passed the cooldown; newer ineligible versions join only after their
cooldown passes. Renovate security updates retain their documented cooldown bypass so a disclosed
vulnerability does not wait seven days for remediation.

### Consequences

* Good, because GitHub Actions references are immutable full SHAs.
* Good, because version comments next to action SHAs preserve Renovate tracking.
* Good, because the Corepack pnpm and commitlint versions remain updateable without a package
  manifest.
* Good, because CI checks the pull request title that usually becomes the squash commit title for
  release automation and checks pull request commit messages for rebase-merge or intentionally
  multi-commit paths. The commit-message check uses a stricter config so breaking commits need both
  the visible `!` marker and the `BREAKING CHANGE:` footer that feeds generated changelog guidance.
* Good, because major updates require human pull-request approval and do not automerge.
* Good, because ordinary dependency code does not enter repository CI through a Renovate branch
  before its seven-day cooldown passes.
* Good, because eligible updates continue without waiting for unrelated versions that remain in
  cooldown.
* Bad, because SHA-pinned actions are less readable than tag-only action references.
* Bad, because ordinary fixes remain unavailable to Renovate for seven days after publication.
* Bad, because the regex manager must stay aligned with the workflow command text.

### Confirmation

Run `pnpm dlx --package renovate renovate-config-validator renovate.json` and
`./mvnw -q spotless:check verify`. Repository tests confirm that the only release-age policy is the
repository-wide seven-day cooldown, timestamp-less releases remain ineligible, and strict internal
checks prevent early branch creation. Review should also confirm `.github/workflows/*.yml` uses full
action SHAs with version comments and commitlint package pins are Renovate-managed.

## Pros and Cons of the Options

### Minimal Renovate config with action SHA pinning and regex managers for pnpm and commitlint

Use Renovate recommended presets, `helpers:pinGitHubActionDigests`, a regex manager for
`corepack prepare pnpm@... --activate`, a regex manager for commitlint package pins,
and one package rule for major updates.

* Good, because security-sensitive action refs are immutable.
* Good, because Renovate still keeps the pins current.
* Good, because commitlint enforces the Conventional Commit title that release automation reads
  after squash merges and the retained commit messages release automation reads after rebase merges.
  The retained-commit path additionally enforces complete breaking-change notation.
* Good, because the config remains short enough to understand.
* Neutral, because Renovate needs custom regexes for workflow command pins.
* Bad, because readers must know that some behavior comes from presets and Renovate's documented
  strict-filter semantics.

### Unpinned GitHub Actions tags

Keep actions referenced as `actions/checkout@v5` or similar.

* Good, because workflow files are easier to read.
* Bad, because tags can be moved.
* Bad, because a compromised action repository or maintainer account could change what CI executes.

### Commit a `package.json` only to pin pnpm

Add a minimal Node package manifest with `packageManager`.

* Good, because Corepack can read pnpm's version from a common manifest field.
* Bad, because it makes a Java repository look like it has Node project metadata.
* Bad, because it adds a file whose only purpose is running a JavaScript CLI in CI.

### Verbose Renovate package rules for every inherited policy

Spell out automerge, release age, grouping, and dashboard behavior in multiple package rules.

* Good, because every behavior is visible in one file.
* Bad, because it repeats preset defaults and global config.
* Bad, because repeated policy becomes easier to accidentally contradict.

## More Information

The workflows use pnpm only for Renovate config validation and commitlint validation of PR titles
and PR commit ranges. PR title linting uses the base config because titles cannot contain footers;
commit-range linting uses the stricter message config so breaking commits include both `!` and a
`BREAKING CHANGE:` footer. Java dependencies and build verification remain Maven-based. The
seven-day cooldown is a supply-chain observation window, not an assertion that releases become safe
after seven days. The upstream Semgrep supply-chain rule requires a repeated release age inside every
package rule and does not account for Renovate's inherited repository-level setting. The Semgrep
wrapper excludes that rule; the repository test instead enforces the stronger single global policy
and rejects package-level overrides.
