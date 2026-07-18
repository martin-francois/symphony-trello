---
status: accepted
date: 2026-07-17
decision-makers: [François Martin, Codex]
consulted:
  - "[Guarded OpenRewrite modernization lane issue](https://github.com/martin-francois/symphony-trello/issues/587)"
  - "[OpenRewrite Maven plugin reference](https://docs.openrewrite.org/reference/rewrite-maven-plugin)"
  - "[OpenRewrite licensing](https://docs.openrewrite.org/licensing/openrewrite-licensing)"
  - "[Quarkus update guide](https://quarkus.io/guides/update-quarkus)"
  - "[Repository static-analysis policy](../agents/static-analysis.md)"
informed: [Future maintainers, Contributors]
---

# Use A Curated Local OpenRewrite Maintenance Lane

## Context and Problem Statement

Symphony for Trello targets Java 25 and has blocking formatting, compiler, static-analysis, and test
gates. Those tools report defects and enforce repository policy, but they do not provide a
repeatable semantic-transformation lane for newer Java APIs, Maven cleanup, or test maintenance.
Renovate updates versions without performing the related source migration, and the Quarkus updater
is scoped to Quarkus version changes.

OpenRewrite provides semantic recipes and a Maven plugin, but its broad composites combine
independent opinions. Uncurated use would change dependency or plugin versions, duplicate Spotless
or analyzer behavior, rewrite exact protocol fixtures, and change source beyond the task that
selected the tool. Recipe releases can also change their generated diff even when the recipe ID
stays the same.

The repository needs a local, pinned, auditable lane that exposes only measured transformations,
keeps source mutation an explicit maintainer action, and makes a clean baseline blocking without
changing normal verification or runtime behavior.

## Decision Drivers

* Keep the accepted recipe IDs and versions visible in the repository.
* Parse every Maven main and test source with type attribution before promoting a recipe.
* Preserve existing ownership of formatting, dependency versions, Quarkus updates, and analyzers.
* Keep `./mvnw -q spotless:check verify` non-mutating and independent of OpenRewrite.
* Require complete diff review, full verification, and idempotence for applied transformations.
* Enforce the accepted composite through the existing required CI job with read-only repository
  permission and no hosted source upload.
* Apply licenses that permit this repository to transform its own source without operating or
  redistributing a recipe service.
* Avoid public API, runtime, process, filesystem, retry, redaction, security, and dependency changes
  that are not explicitly owned by a maintenance task.

## Considered Options

The considered boundaries range from a repository-local allowlist to hosted orchestration and the
existing manual process:

* Use the OpenRewrite Maven plugin with a curated repository composite.
* Require the Moderne CLI or platform.
* Use the Quarkus update command as the only semantic transformation tool.
* Continue with Renovate, manual refactoring, and existing analyzers only.

## Decision Outcome

Chosen option: "Use the OpenRewrite Maven plugin with a curated repository composite".

The root POM defines an inactive `openrewrite` profile. It pins the Maven plugin and every loaded
recipe artifact to an exact version and reads one repository-owned composite from `rewrite.yml`.
Fully qualified child recipe IDs are the allowlist. The first allowlist contains only:

* forwarding-lambda to method-reference cleanup;
* `indexOf` existence checks to `contains`;
* constructor-only private fields to `final`;
* single-argument JUnit CSV sources to typed value sources;
* explicit standard Maven plugin group IDs;
* `Process.waitFor(long, TimeUnit)` to its Java 25 duration overload; and
* Java 25 `List.getFirst()` and `List.getLast()` use where tests prove non-emptiness.

The requested Common Static Analysis, JUnit 5 Best Practices, Maven Best Practices, and Java 25
Upgrade composites were measured but rejected wholesale. Their other result-producing children
overlap repository checks, weaken precise test contracts, modify exact fixtures, hide established
version properties, change dependency or plugin versions, or introduce unjustified semantic risk.
The complete artifact inventory, counts, representative diffs, and child decisions are recorded in
the [OpenRewrite maintenance guide](../openrewrite.md).

The duration-overload child currently has no findings because PR 585 already made those changes.
The linked recipe-coverage audit proved it against the immutable pre-refactor source, including
type attribution, the complete generated diff, full verification, and a clean second run. It
remains selected as a recurrence guard. A separately investigated symbolic-link assertion leaf is
not exact in isolation: it requires the existing AssertJ `StaticImports` recipe to reproduce the
merged hunk, so the audit classifies it as `EXISTING_COMPOSABLE`. The ordered composition is not
selected because the Picnic FQCN is an undocumented generated transitive recipe API; selecting it
requires directly pinning and reviewing that additional artifact.

The Maven plugin and `rewrite-maven` engine recipes are Apache-2.0. The selected static-analysis,
testing-framework, and Java-migration catalogs use the Moderne Source Available license. Its
end-user grant covers applying recipes to this repository's own code. This decision does not permit
operating or redistributing the catalogs as a commercial recipe service. No source or lossless
semantic tree is uploaded to Moderne.

`rewrite:dryRun` is the normal maintenance check and fails when a selected recipe proposes a
change. `rewrite:run` is never bound to a Maven lifecycle. A maintainer applies it only in a clean
branch or disposable worktree, reviews the complete diff, runs Spotless and the full Maven gate,
then repeats the apply command and confirms that it adds no diff.

The existing required CI `test` job performs the blocking dry run whenever normal CI runs for a pull
request or push to `main`. Release Please retains its existing normal-CI skip policy. The job has
read-only repository permission and never applies, commits, pushes, comments, opens a pull request,
or uploads generated output or source to a hosted analysis service. A separate path-filtered,
manually dispatched, or scheduled workflow is intentionally absent. With exact recipe pins, changes
to source, configuration, or recipe versions pass through the same required dry run in the normal
contribution path; a second workflow would duplicate that check while adding another workflow,
artifact, and maintenance boundary. Maintainers run the documented local dry-run command when they
need an additional on-demand check.

Renovate updates remain available but OpenRewrite engine and catalog updates are separated from
unrelated dependency groups, delayed for seven days, dependency-dashboard approved, and never
automerged. Each update must reproduce a clean dry run and full Maven verification. A maintainer
also reviews an applied diff and proves a second apply is idempotent.

Quarkus version migrations remain owned by `quarkus:update` in the specific dependency-update
branch. That workflow requires target migration-guide review, complete diff review, and the full
Maven gate. Quarkus update recipes are not loaded into the recurring general composite.

This decision changes contributor tooling only. It does not change a supported runtime or user
contract, so `SPEC.md` does not need an update.

### Consequences

* Good, because a pinned allowlist makes semantic maintenance reproducible and reviewable.
* Good, because the clean blocking baseline prevents accepted modernization debt from accumulating.
* Good, because candidate measurements and explicit rejections preserve the ownership boundaries of
  existing checks and dependency automation.
* Good, because normal verification, application runtime, and public API remain unchanged.
* Good, because the single automated lane has no mutation credentials and does not upload source,
  generated patches, data tables, or semantic trees.
* Good, because OpenRewrite enforcement reuses an existing required job instead of maintaining a
  duplicate scheduled and manually dispatched workflow.
* Bad, because maintainers must repeat candidate measurement and diff review when a recipe release
  changes behavior.
* Bad, because useful recipes outside the allowlist require a separate report-only evaluation before
  use.
* Neutral, because Quarkus upgrades and dependency versions continue through their existing owners.

### Confirmation

This decision remains implemented when:

* the `openrewrite` profile and repository composite use exact versions and fully qualified IDs;
* normal `./mvnw -q spotless:check verify` neither invokes OpenRewrite nor changes source;
* the active composite has no dry-run findings or recipe errors after its accepted baseline is
  applied;
* every applied recipe passes Spotless, full Maven verification, and a no-diff second apply;
* the required CI `test` status invokes only the dry-run goal with read-only permission and does not
  upload generated output;
* Renovate cannot group or automerge an OpenRewrite update and requires dashboard approval plus its
  release-age delay;
* Quarkus and general dependency versions remain outside the recurring composite; and
* a proposed new child or catalog version is measured and documented before it becomes blocking.

## Pros and Cons of the Options

### Curated Maven Plugin And Repository Recipe

This option keeps discovery and execution inside the existing Maven toolchain while the repository
owns the exact allowlist.

* Good, because contributors need no new runtime or hosted account.
* Good, because dry run, explicit apply, data tables, and invalid-recipe validation are available.
* Good, because CI can be read-only and deterministic.
* Bad, because the repository must inventory mixed catalog licenses and re-review generated diffs.
* Bad, because broad composites cannot be adopted without per-child classification.

### Moderne CLI Or Platform

This option would centralize recipe execution and multi-repository reporting in Moderne tooling.

* Good, because it provides orchestration features when many repositories share migrations.
* Bad, because one repository does not justify another runtime, account, credential, or operational
  dependency.
* Bad, because hosted source or semantic-tree handling needs a separate privacy and security review.
* Bad, because catalog commercialization terms and service boundaries need a separate licensing
  decision.

### Quarkus Update Command Alone

This option would use Quarkus's OpenRewrite-backed updater only during framework upgrades.

* Good, because Quarkus owns the migration knowledge for its own target versions.
* Good, because the updater already requires migration-guide and diff review.
* Bad, because it does not provide recurring Java, JUnit, or POM maintenance outside Quarkus
  upgrades.
* Bad, because continuously loading Quarkus update recipes would blur dependency-update ownership.

### Renovate, Manual Refactoring, And Existing Analyzers

This option preserves the tools and contributor workflow that existed before this decision.

* Good, because existing tools already provide strong formatting, defect, security, and version
  checks.
* Good, because it introduces no catalog license or transformation-version maintenance.
* Bad, because semantic migrations remain ad hoc and do not have a shared, recurring clean baseline.
* Bad, because Renovate changes versions but cannot perform the corresponding source migration.

## More Information

The [guarded OpenRewrite modernization lane issue](https://github.com/martin-francois/symphony-trello/issues/587)
defines the rollout and acceptance criteria. The [OpenRewrite maintenance guide](../openrewrite.md)
is the operational record for the initial candidate baseline and future maintainer workflow.
