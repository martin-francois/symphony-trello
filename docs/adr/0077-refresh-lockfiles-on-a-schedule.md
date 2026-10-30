---
status: accepted
date: 2026-08-08
decision-makers: [François Martin, Claude]
consulted:
  - "[ADR 0008](0008-renovate-and-github-actions-hardening.md)"
  - "[ADR 0076](0076-separate-major-updates-from-the-automergeable-bundle.md)"
  - "[Renovate lockFileMaintenance documentation](https://docs.renovatebot.com/configuration-options/#lockfilemaintenance)"
  - "[Renovate vulnerabilityAlerts documentation](https://docs.renovatebot.com/configuration-options/#vulnerabilityalerts)"
  - "[Renovate minimum release age documentation](https://docs.renovatebot.com/key-concepts/minimum-release-age/)"
  - "[pnpm minimumReleaseAge setting](https://pnpm.io/settings#minimumreleaseage)"
  - "[pnpm issue 13805](https://github.com/pnpm/pnpm/issues/13805)"
  - "[dependabot-core issue 15904](https://github.com/dependabot/dependabot-core/issues/15904)"
  - "[GitHub issue #1118](https://github.com/martin-francois/symphony-trello/issues/1118)"
  - "[pnpm/action-setup](https://github.com/pnpm/action-setup)"
informed: [Future maintainers, Contributors]
---

# Refresh Lockfiles On A Schedule

## Context and Problem Statement

Renovate proposes an update for a declaration it can find. `package.json` names three development
dependencies; `pnpm-lock.yaml` pins those packages and every package they depend on. Renovate owns
the three named packages. Nothing owns the rest.

That gap is invisible while it is empty and permanent once it is not. A published advisory against a
package that appears only inside the lockfile produces no Renovate update, because no manifest
declares a version to raise. `vulnerabilityAlerts` does not close the gap either: it changes how
Renovate treats an update it would already create, so it also reaches only the declarations Renovate
manages. The advisory then stays open until something regenerates the lockfile, and nothing in this
repository does.

Renovate has a rule for exactly this, `lockFileMaintenance`, which regenerates the lockfile in place
and lets every transitive pin move to the newest version its existing ranges already allow. It is
disabled by default, so its absence produces no warning, no dashboard entry, and no failing check.

Enabling it opens a second problem, and it is the harder one. Renovate does not resolve a lock file
maintenance branch itself: it runs the package manager and commits whatever the package manager
produces. `minimumReleaseAge` in `renovate.json` filters the updates Renovate computes, so it never
reaches that resolution. The repository's seven-day release-age rule is therefore silently absent
from exactly the path that moves the most versions at once, and with automatic merge on that path a
package published minutes ago could reach the default branch without a human ever seeing it.

pnpm 12 creates another security problem when it manages the version from `packageManager`: it
prepends a package-manager environment document to `pnpm-lock.yaml`. GitHub's static dependency-graph
parser currently reads that first document as the complete graph. It then sees pnpm's own binaries
and none of the project dependencies, which can silently close or suppress vulnerability alerts.
The dependabot-core grapher learned to read the final document, but the static parser still reproduced
the defect on 2026-09-23. pnpm documents `pmOnFail: ignore` as the supported way to keep a
single-document lockfile, at the cost of disabling pnpm's own package-manager version switch.

Something else must then install the exact pnpm version in CI. Until 2026-10-30 every pnpm job ran
`corepack enable` and `corepack prepare pnpm@<version> --activate`. The official Node.js 26 builds no
longer ship Corepack: the Linux tarball for v26.13.0 has only `node`, `npm`, and `npx`, and the
`node:26` container has no `corepack` command. The jobs passed only because the `ubuntu-26.04`
runner image also carries Node.js 24.22.1, so `corepack` came from that install even after
`actions/setup-node` put Node.js 26 first on `PATH`. Nothing in the repository declared or tested
that. A runner image without Node.js 24, a container job, or a self-hosted runner would have broken
every pnpm entry point ([GitHub issue #1118](https://github.com/martin-francois/symphony-trello/issues/1118)).

## Decision Drivers

* Close the gap between what Renovate manages and what the lockfile pins.
* Keep the refresh preventive rather than a response to an advisory that is already open.
* Keep dependency work inside the scheduled window the maintainer set aside for it.
* Keep the seven-day release age in force on every path that can move a version, including the ones
  Renovate does not resolve itself.
* Keep the human-review boundary from [ADR 0008](0008-renovate-and-github-actions-hardening.md)
  unchanged.
* Install the exact pnpm version in CI from a pinned, Renovate-owned source that works on a runner
  with no Node.js other than the one the job sets up, and state that version once.

## Considered Options

* Enable `lockFileMaintenance` on a weekly schedule with automatic merge, and enforce the seven-day
  release age in the package manager.
* Enable `lockFileMaintenance` on a weekly schedule with automatic merge and no package-manager
  gate.
* Enable `lockFileMaintenance` on a weekly schedule and require a manual merge.
* Leave lockfile refreshes manual and act on advisories when they are reported.

Installing the exact pnpm version in CI had these options:

* Install pnpm with `pnpm/action-setup`, which reads the version from `packageManager`.
* Install Corepack at an exact version with npm, then run `corepack enable`.
* Keep `corepack enable` and rely on the runner image for Corepack.

## Decision Outcome

Chosen option: "Enable `lockFileMaintenance` on a weekly schedule with automatic merge, and enforce
the seven-day release age in the package manager", because it is the only option that keeps
transitive pins current without a maintainer having to notice that they are not, and the only one
that does so without suspending the release-age rule on the refresh path.

The rule sets `schedule: ["* * * * 5"]`, which restricts the refresh to Friday, 00:00 to 23:59 UTC;
the configuration sets no `timezone`. Renovate's own default window for this rule is Monday before
04:00. Friday is chosen instead because it is when scheduled dependency work happens in this
repository. Until 2026-10-12 the rule used `* 0-4 * * 5`, Friday 00:00 to 04:59, so that a refresh
landing overnight would not compete for CI capacity with a working maintainer. The hosted Renovate
app often did not run at all inside those five hours on a quiet repository, and every missed window
delayed the refresh by a week. On that date the maintainer widened the window to the whole day
here and in the other repositories that used it. Actions minutes are unlimited for a public
repository, so a daytime refresh takes no capacity that matters.

The rule sets `automerge: true`, matching the non-major bundle policy in
[ADR 0076](0076-separate-major-updates-from-the-automergeable-bundle.md). Automatic merge still
happens only through a pull request whose required checks passed, so this grants the refresh no
privilege that a routine non-major update does not already have. A refresh that breaks a check stays
open for review.

Automatic merge is only defensible here because the cooldown is enforced where the resolution
actually happens. `pnpm-workspace.yaml` sets `minimumReleaseAge: 10080`, the same seven days
expressed in the minutes pnpm expects, so pnpm refuses to select any version published inside the
cutoff no matter who invoked it. The gate therefore covers the refresh Renovate delegates, a
maintainer's own `pnpm install`, and any future automation, rather than only the code path someone
remembered to configure. The alternative reading, that `renovate.json`'s `minimumReleaseAge` already
covers the refresh, is wrong: that setting filters the updates Renovate computes, and a lock file
maintenance branch is not one of them. The two values are kept in step by a script test that derives
the pnpm minutes from the Renovate days rather than asserting them independently.

`pnpm-workspace.yaml` also sets `pmOnFail: ignore`. This keeps the project dependency graph in one
YAML document until GitHub's static parser supports pnpm's stream format. The repository does not
accept an unpinned package manager in exchange: `package.json` keeps an exact `packageManager` value,
Corepack can select it for local use, and every CI job that runs pnpm installs that same exact
version first. `pmOnFail: download`, `warn`, and `error` were rejected because they retain the
two-document lockfile; leaving `pmOnFail` unset has the same result.

For CI, chosen option: "Install pnpm with `pnpm/action-setup`, which reads the version from
`packageManager`", because it removes Corepack from CI and keeps the pnpm version in one place.
Each job that runs pnpm has a `pnpm/action-setup` step pinned by commit SHA with a version comment,
like every other action. The step has no `version` input, so the action reads `packageManager` from
`package.json`. Renovate's npm manager already updates `packageManager`, and the github-actions
manager updates the action, so the regex manager that used to update the `corepack prepare` pins is
gone. For pnpm 12 the action installs pnpm's native executable, which does not need Node.js to run.

The step comes after `actions/setup-node`. The action bootstraps pnpm with `npm ci`, using the npm
on `PATH`. Before `actions/setup-node` runs, that npm belongs to the runner image's Node.js, which
is the hidden runner dependency this change removes. The jobs do not use `actions/setup-node`'s
`cache: pnpm`, which would need pnpm before `actions/setup-node`. A job that wants a pnpm store
cache should use the action's own `cache: true` input, which keeps this order.

A script test checks the setting, the lockfile shape, the manifest pin, and every workflow: no
workflow mentions Corepack, every `pnpm/action-setup` reference is SHA-pinned with a version
comment, and every job that runs pnpm, directly or through a repository script, sets up Node.js,
then installs pnpm with no `version` input, before its first pnpm command.

`minimumReleaseAgeExclude` is deliberately left unset. It exists for the case where a specific
version must be admitted before its cooldown expires, typically a security fix, and it should be
added with the version pinned and a comment naming the advisory rather than kept open as a standing
list.

The refresh does not join that bundle. Renovate defaults `lockFileMaintenance.groupName` to `null`
and gives the rule its own `branchTopic`, so the regenerated lockfile arrives as its own pull
request and a failure there cannot hold back an unrelated dependency update.

A manual merge was rejected for the reason the rule exists. A refresh nobody merges leaves the
transitive pins exactly where they were, and the queue of unreviewed refreshes is itself the failure
this decision removes.

The immediate effect here is small and is meant to be. The repository currently reports no open
Dependabot alerts, and all 34 alerts it has ever received were Maven runtime dependencies declared
directly in `pom.xml`, where ordinary Renovate updates already reach them. Maven resolves versions
from the POM and has no lockfile, so `lockFileMaintenance` applies only to `pnpm-lock.yaml`. The
value of the rule is that it is in place before the first transitive advisory, not after it.

### Consequences

* Good, because a transitive package that no manifest names can now receive a fix.
* Good, because the fix arrives on a schedule rather than after someone reads an alert.
* Good, because the refresh consumes no maintainer attention while every check passes.
* Good, because the seven-day release age now also covers a plain `pnpm install` and any future
  automation, not only the updates Renovate computes.
* Good, because GitHub's dependency graph continues to see project dependencies and can report
  vulnerable transitive packages.
* Bad, because a recurring refresh adds pull requests and CI runs that nobody asked for.
* Bad, because the cooldown is now stated twice, in two files and two units. The script test that
  derives one from the other is what keeps that from drifting into a lie.
* Bad, because the gate belongs to pnpm. A different package manager would need its own gate, and
  one that has none, such as npm or bun, would leave `automerge: false` as the only safe setting.
* Bad, because pnpm no longer switches versions itself. The `pnpm/action-setup` step now owns that
  enforcement in CI, so every new job that runs pnpm needs that step, and the script test fails
  until it has one.
* Good, because CI no longer depends on a Node.js install that the runner image ships beside the
  one `actions/setup-node` selects.
* Bad, because a maintainer who runs `corepack pnpm` locally on Node.js 26 must install Corepack
  first. CI no longer exercises that path.
* Neutral, because a refresh can resolve a slightly older version than the newest published one.
  That is the cooldown working, not the refresh failing; the newer version arrives the following
  week.
* Bad, because a refresh moves several transitive pins at once, so a failed check does not name its
  cause. Attribute such a failure by bisecting the regenerated lockfile locally, which is free,
  rather than by re-running the pull request. This is the same tradeoff ADR 0076 accepted for the
  non-major bundle.
* Neutral, because direct dependencies keep the update path, cooldown, and review boundary they
  already had.

### Confirmation

This decision remains implemented when:

* `lockFileMaintenance.enabled` is `true`;
* `lockFileMaintenance.schedule` restricts the refresh to one day a week, Friday;
* `lockFileMaintenance.automerge` is `true`;
* `vulnerabilityAlerts.enabled` is `true`, since the two rules cover different halves of the
  dependency graph and neither substitutes for the other;
* the script test `Renovate refreshes lockfiles on a schedule so transitive advisories are fixed`
  rejects each of the preceding conditions;
* `pnpm-workspace.yaml` sets `minimumReleaseAge` to the repository cooldown in minutes, currently
  `10080`, and `package.json` keeps `packageManager` on a pnpm that enforces it, verified against
  the current exact pnpm release;
* `pnpm-workspace.yaml` sets `pmOnFail: ignore`, `pnpm-lock.yaml` has one YAML document, and every CI
  job that runs pnpm installs the exact `packageManager` version with a SHA-pinned
  `pnpm/action-setup` step after `actions/setup-node`, which the script test `pnpm keeps GitHub's
  dependency graph readable while every CI job installs its exact pin` checks; and
* the script test `The lockfile refresh path enforces the same seven-day cooldown` derives that
  value from `renovate.json` and fails if either side moves alone.

## Pros and Cons of the Options

### Enable `lockFileMaintenance` On A Weekly Schedule With Automatic Merge, And Enforce The Seven-Day Release Age In The Package Manager

Renovate regenerates the lockfile every Friday and merges the result once required checks pass. pnpm
refuses to resolve a version published inside the seven-day cutoff, so the refresh cannot introduce
one.

* Good, because transitive pins stay current without anyone tracking them.
* Good, because the refresh reuses the merge gate that already governs non-major updates.
* Good, because the release-age rule is enforced by whatever resolves the lockfile, so it cannot be
  skipped by reaching the lockfile through a different caller.
* Bad, because a recurring pull request consumes CI capacity nobody asked for.
* Bad, because the cooldown now lives in two files that must be kept in agreement.

### Enable `lockFileMaintenance` On A Weekly Schedule With Automatic Merge And No Package-Manager Gate

The same refresh, resolved by pnpm with no cooldown of its own.

* Good, because it is the smallest configuration change.
* Bad, because it quietly suspends the seven-day release age on the refresh path.
  `minimumReleaseAge` in `renovate.json` filters updates Renovate computes, and Renovate computes no
  update here; it runs pnpm and commits the result.
* Bad, because `automerge: true` then merges that result without review, so a package published
  hours earlier can reach the default branch through the one path nobody is watching. This is the
  combination the review of this decision rejected.

### Enable `lockFileMaintenance` On A Weekly Schedule And Require A Manual Merge

Renovate opens the same refresh and waits for a maintainer.

* Good, because a maintainer sees every transitive change before it lands.
* Good, because it removes the automatic-merge exposure without needing a package-manager gate,
  which is the only remedy available under a package manager that has no release-age setting.
* Bad, because the review carries no information a required check does not already give: a
  regenerated lockfile is machine output, and reading it does not reveal what a passing test suite
  would have missed.
* Bad, because an unmerged refresh leaves the advisories open, which is the state this decision
  exists to end.

### Leave Lockfile Refreshes Manual And Act On Advisories When They Are Reported

Nothing changes until an advisory is reported against a transitive package.

* Good, because it opens no pull requests.
* Bad, because the response starts only after a vulnerability is public and matched to this
  repository.
* Bad, because the work then lands as an urgent unplanned refresh rather than a routine one.

### Install pnpm With `pnpm/action-setup`, Which Reads The Version From `packageManager`

Each job that runs pnpm adds a SHA-pinned `pnpm/action-setup` step with no `version` input after
`actions/setup-node`. The action reads `packageManager` and installs that exact pnpm.

* Good, because the pnpm version is stated once, in `package.json`, and Renovate's npm manager
  already updates it.
* Good, because the github-actions manager updates the action, so no regex manager is needed.
* Good, because it works on any runner that has npm from `actions/setup-node`, with no Corepack.
* Neutral, because it adds a third-party action, pinned by SHA and maintained by the pnpm project.
* Bad, because CI and local use now install pnpm in different ways.

### Install Corepack At An Exact Version With npm, Then Run `corepack enable`

One shared script runs `npm install --global corepack@<x.y.z>` before `corepack enable` in every
pnpm job. Corepack 0.36.0 with pnpm 12.15.0 ran the Renovate validator and the
`pnpm run verify:scripts` type check in a `node:26` container.

* Good, because CI and local use keep the same Corepack path.
* Bad, because the Corepack version is a new nonstandard declaration that needs a Renovate regex
  manager and regression coverage, as [dependency updates](../agents/dependency-updates.md)
  requires.
* Bad, because it adds a repository script that does what a maintained action already does.

### Keep `corepack enable` And Rely On The Runner Image For Corepack

The workflows stay as they were before
[GitHub issue #1118](https://github.com/martin-francois/symphony-trello/issues/1118).

* Good, because nothing changes.
* Bad, because the jobs work only while the runner image ships an older Node.js that still has
  Corepack, and nothing in the repository declares, pins, or tests that.

## More Information

[ADR 0008](0008-renovate-and-github-actions-hardening.md) established the Renovate ownership and
merge policy this decision extends to the lockfile.
[ADR 0076](0076-separate-major-updates-from-the-automergeable-bundle.md) set the automatic-merge
policy for non-major updates that `lockFileMaintenance` follows.
