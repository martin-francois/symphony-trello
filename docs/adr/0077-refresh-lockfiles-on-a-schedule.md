---
status: accepted
date: 2026-08-08
decision-makers: [François Martin, Claude]
consulted:
  - "[ADR 0008](0008-renovate-and-github-actions-hardening.md)"
  - "[ADR 0076](0076-separate-major-updates-from-the-automergeable-bundle.md)"
  - "[Renovate lockFileMaintenance documentation](https://docs.renovatebot.com/configuration-options/#lockfilemaintenance)"
  - "[Renovate vulnerabilityAlerts documentation](https://docs.renovatebot.com/configuration-options/#vulnerabilityalerts)"
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

## Decision Drivers

* Close the gap between what Renovate manages and what the lockfile pins.
* Keep the refresh preventive rather than a response to an advisory that is already open.
* Keep dependency work inside the scheduled window the maintainer set aside for it.
* Keep the human-review boundary from [ADR 0008](0008-renovate-and-github-actions-hardening.md)
  unchanged.

## Considered Options

* Enable `lockFileMaintenance` on a weekly schedule with automatic merge.
* Enable `lockFileMaintenance` on a weekly schedule and require a manual merge.
* Leave lockfile refreshes manual and act on advisories when they are reported.

## Decision Outcome

Chosen option: "Enable `lockFileMaintenance` on a weekly schedule with automatic merge", because it
is the only option that keeps transitive pins current without a maintainer having to notice that
they are not.

The rule sets `schedule: ["* 0-4 * * 5"]`, which restricts the refresh to Friday between 00:00 and
04:59 in the bot's timezone. Renovate's own default window for this rule is Monday before 04:00.
Friday is chosen instead because it is when scheduled dependency work happens in this repository;
the off-hours part of the window is kept because a refresh that lands overnight does not compete for
CI capacity with a working maintainer.

The rule sets `automerge: true`, matching the non-major bundle policy in
[ADR 0076](0076-separate-major-updates-from-the-automergeable-bundle.md). Automatic merge still
happens only through a pull request whose required checks passed, so this grants the refresh no
privilege that a routine non-major update does not already have. A refresh that breaks a check stays
open for review.

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
* Bad, because a recurring refresh adds pull requests and CI runs that nobody asked for.
* Bad, because a refresh moves several transitive pins at once, so a failed check does not name its
  cause. Attribute such a failure by bisecting the regenerated lockfile locally, which is free,
  rather than by re-running the pull request. This is the same tradeoff ADR 0076 accepted for the
  non-major bundle.
* Neutral, because direct dependencies keep the update path, cooldown, and review boundary they
  already had.

### Confirmation

This decision remains implemented when:

* `lockFileMaintenance.enabled` is `true`;
* `lockFileMaintenance.schedule` restricts the refresh to a weekly off-hours window;
* `lockFileMaintenance.automerge` is `true`;
* `vulnerabilityAlerts.enabled` is `true`, since the two rules cover different halves of the
  dependency graph and neither substitutes for the other; and
* the script test `Renovate refreshes lockfiles on a schedule so transitive advisories are fixed`
  rejects each of the preceding conditions.

## Pros and Cons of the Options

### Enable `lockFileMaintenance` On A Weekly Schedule With Automatic Merge

Renovate regenerates the lockfile every Friday and merges the result once required checks pass.

* Good, because transitive pins stay current without anyone tracking them.
* Good, because the refresh reuses the merge gate that already governs non-major updates.
* Bad, because a recurring pull request consumes CI capacity nobody asked for.

### Enable `lockFileMaintenance` On A Weekly Schedule And Require A Manual Merge

Renovate opens the same refresh and waits for a maintainer.

* Good, because a maintainer sees every transitive change before it lands.
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

## More Information

[ADR 0008](0008-renovate-and-github-actions-hardening.md) established the Renovate ownership and
merge policy this decision extends to the lockfile.
[ADR 0076](0076-separate-major-updates-from-the-automergeable-bundle.md) set the automatic-merge
policy for non-major updates that `lockFileMaintenance` follows.
