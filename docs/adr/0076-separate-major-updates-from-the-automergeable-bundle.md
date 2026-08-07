---
status: accepted
date: 2026-08-07
decision-makers: [François Martin, Claude]
consulted:
  - "[ADR 0008](0008-renovate-and-github-actions-hardening.md)"
  - "[ADR 0072](0072-create-renovate-prs-without-dashboard-approval.md)"
  - "[Renovate group:all preset documentation](https://docs.renovatebot.com/presets-group/#groupall)"
  - "[Renovate separateMajorMinor documentation](https://docs.renovatebot.com/configuration-options/#separatemajorminor)"
informed: [Future maintainers, Contributors]
---

# Separate Major Updates From The Automergeable Bundle

## Context and Problem Statement

The repository grouped every dependency update into one pull request through the `group:all` preset.
That preset sets `separateMajorMinor: false`, so major updates share a branch with minor, patch, and
digest updates.

Major updates require a human merge under [ADR 0008](0008-renovate-and-github-actions-hardening.md).
Combining both decisions means a single eligible major update places `automerge: false` on the one
branch that carries every other update. The safe patch and minor updates then wait for a human to
review an unrelated major, which is the outcome automatic merging was introduced to avoid. The
longer a major stays unreviewed, the more routine updates accumulate behind it.

The same failure mode reaches any package whose rule sets `automerge: false` while keeping the
shared group name. The `tessl-labs/good-oss-citizen` rule required vendored tile changes before
merge and therefore carried `automerge: false`, which suppressed automatic merging for the whole
group rather than for that package alone.

## Decision Drivers

* Keep routine updates flowing without a maintainer in the loop.
* Keep the human-review boundary on major updates exactly where ADR 0008 placed it.
* Keep packages that must move together in one pull request, even across a major boundary.
* Make a failed check attributable to the dependency that caused it.

## Considered Options

* Separate major updates from the automergeable bundle.
* Keep `group:all` and accept that a pending major blocks routine updates.
* Automerge major updates once required checks pass.

## Decision Outcome

Chosen option: "Separate major updates from the automergeable bundle", because it preserves both the
review boundary and the automatic flow that the previous configuration forced into conflict.

`group:all` is removed from `extends`. It cannot be retained in any form, because the preset itself
sets `separateMajorMinor: false`. The repository sets `separateMajorMinor: true` and
`separateMultipleMajor: true`, and one package rule groups the non-major update types into a single
automergeable pull request.

Major updates are therefore ungrouped by default: no rule assigns them a group name, so each major
arrives as its own pull request with `automerge: false`. `separateMultipleMajor: true` delivers a
multi-version jump as one pull request per major step, so each release's migration notes apply to a
diff that contains only that release's breaking changes.

The major-update rule deliberately sets no `groupName`. Setting one would override the group name of
a lockstep group whenever its update happened to be a major, splitting packages that must move
together. Because the bundle rule matches non-major update types only, majors are already ungrouped
and no override is needed.

A package that requires a manual merge leaves the bundle through `groupName: null` rather than
through `automerge: false` alone. Without that, its `automerge: false` applies to the shared branch
and suppresses automatic merging for every unrelated update travelling with it.

### Consequences

* Good, because a pending major update no longer holds back routine patch and minor updates.
* Good, because a failed check on a major update names exactly one dependency.
* Good, because each major migration is reviewed against a diff limited to that migration.
* Good, because lockstep groups continue to move together across major boundaries.
* Bad, because the repository opens more pull requests than it did under `group:all`, and each one
  consumes a CI run.
* Neutral, because the human-review boundary for major updates is unchanged.

### Confirmation

This decision remains implemented when:

* `group:all` is absent from `extends`;
* `separateMajorMinor` and `separateMultipleMajor` are `true`;
* one package rule groups the non-major update types with `automerge: true`;
* that rule does not match the `major` update type;
* no package rule both sets `automerge: true` and matches the `major` update type;
* every package rule with `automerge: false` that is not the major-update rule sets
  `groupName: null`;
* the major-update rule sets no `groupName`; and
* repository tests reject each of the preceding conditions.

## Pros and Cons of the Options

### Separate Major Updates From The Automergeable Bundle

Routine updates automerge while majors wait for review.

* Good, because the two policies stop competing for one branch.
* Good, because major migrations are reviewed one at a time.
* Bad, because more pull requests mean more CI runs.

### Keep `group:all` And Accept That A Pending Major Blocks Routine Updates

One pull request carries everything.

* Good, because it consumes the fewest CI runs.
* Bad, because automatic merging stops whenever any major is eligible.
* Bad, because one failed check does not identify which dependency caused it.

### Automerge Major Updates Once Required Checks Pass

Required checks become the only guard for breaking changes.

* Good, because no update waits for a maintainer.
* Bad, because a required check cannot confirm that a documented migration was performed.
* Bad, because it removes the review boundary ADR 0008 established.

## More Information

[ADR 0008](0008-renovate-and-github-actions-hardening.md) established the major-update review
boundary. [ADR 0072](0072-create-renovate-prs-without-dashboard-approval.md) moved the human gate
from dashboard approval to merge time; this decision removes the last configuration that prevented
that gate from applying to majors alone.
