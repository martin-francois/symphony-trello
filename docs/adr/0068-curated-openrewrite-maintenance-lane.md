---
status: accepted
date: 2026-07-18
decision-makers: [François Martin, Codex]
consulted:
  - "[Guarded OpenRewrite modernization lane issue](https://github.com/martin-francois/symphony-trello/issues/587)"
  - "[OpenRewrite Maven plugin reference](https://docs.openrewrite.org/reference/rewrite-maven-plugin)"
  - "[OpenRewrite licensing](https://docs.openrewrite.org/licensing/openrewrite-licensing)"
  - "[ADR 0059](0059-keep-palantir-java-format.md)"
  - "[Repository static-analysis policy](../agents/static-analysis.md)"
informed: [Future maintainers, Contributors]
---

# Use A Curated OpenRewrite-To-Spotless Maintenance Pipeline

## Context and Problem Statement

Symphony for Trello targets Java 25 and has blocking formatting, compiler, static-analysis, and test
gates. Those tools do not provide one repeatable semantic-transformation lane for newer Java APIs,
Maven cleanup, or test maintenance.

OpenRewrite provides semantic recipes, but broad composites combine independent opinions. They can
mix useful modernization with dependency upgrades, weakened test contracts, invalid source,
protocol changes, and formatting that differs from the repository's Spotless result. Recipe
releases can also change their generated diff while retaining the same recipe ID.

The repository needs a pinned and auditable local lane. It must allow justified large cleanups,
keep Spotless authoritative for final formatting, document each executed candidate leaf whether
or not it currently produces a result, and block drift without giving CI authority to persist
mutations.

## Decision Drivers

* Keep accepted recipe IDs, configurations, artifacts, and versions visible in the repository.
* Evaluate semantic value after OpenRewrite output has been normalized by Spotless.
* Parse every Maven main and test source with type attribution before promoting a recipe.
* Accept a large diff when the final code is meaningfully clearer or more maintainable.
* Preserve explicit ownership of dependency versions, Quarkus updates, analyzers, and exact
  external protocols.
* Record an evidence-based decision for every evaluated leaf; zero current results are evidence of
  baseline conformance, not a reason to exclude a generally applicable recurrence guard.
* Enforce the accepted ordered state in required CI with read-only repository permission and no
  hosted source upload.

## Considered Options

* Use the OpenRewrite Maven plugin with a curated repository composite and Spotless normalization.
* Require raw OpenRewrite dry-run cleanliness or raw OpenRewrite idempotence.
* Require the Moderne CLI or platform.
* Use the Quarkus update command as the only semantic transformation tool.
* Continue with Renovate, manual refactoring, and existing analyzers only.

## Decision Outcome

Chosen option: "Use the OpenRewrite Maven plugin with a curated repository composite and Spotless
normalization".

The root POM defines an inactive `openrewrite` profile. It pins the Maven plugin, engine catalogs,
and directly selected Picnic recipe classifier to exact versions. `rewrite.yml` owns one composite
whose fully qualified children and option values are the allowlist.

The repository treats OpenRewrite followed by Spotless as one ordered normalization pipeline. If
`R` is the reviewed OpenRewrite composite, `F` is Spotless, and `S` is the committed repository
state, CI accepts:

```text
F(R(S)) = S
```

Spotless owns final source and POM formatting, so OpenRewrite may emit noncanonical intermediate
formatting. `rewrite:dryRun` remains a preview and evidence command; it is not the clean-state gate.
This decision does not require raw OpenRewrite output to remain unchanged on a second run, does not
require raw OpenRewrite idempotence, and does not require OpenRewrite and Spotless to commute.

The selected leaves modernize Java documentation, type-obvious local declarations, file API use,
pattern dispatch, multiline strings, nullness contracts, utility classes, private cleanup, Maven
metadata, Mockito verification, JUnit sources, and AssertJ assertions. The
[recipe decision record](../openrewrite-recipe-decisions.md) and its linked zero-result appendix
give every evaluated candidate its own status, evidence, and rationale. A safe leaf that enforces a
general Java, Maven, or existing-ecosystem invariant remains active even when the audited baseline
already conforms. Broad parents remain inactive when their descendants have mixed decisions; the
parent disposition does not replace child decisions.

The Maven plugin and core Maven/Java recipes are Apache-2.0. The static-analysis, testing, and
migration catalogs use the Moderne Source Available license, whose end-user grant covers applying
recipes to this repository's own source. The selected Picnic generated leaves are loaded from a
directly pinned MIT-licensed `recipes` classifier. No source or lossless semantic tree is uploaded
to Moderne. This decision does not permit operating or redistributing the catalogs as a commercial
recipe service.

The required CI `test` job performs these steps in order:

1. apply the reviewed OpenRewrite composite;
2. apply Spotless in a new Maven invocation;
3. fail when Git reports a tracked or nonignored untracked change; and
4. run `spotless:check verify`.

The separate formatter invocation reloads a POM changed by OpenRewrite. The checkout is writable
because both tools apply changes locally, but the job has only `contents: read` permission. It
never commits, pushes, comments, opens a pull request, or uploads generated output.

Renovate separates OpenRewrite and Picnic recipe updates from unrelated dependency groups, delays
them for seven days, requires dependency-dashboard approval, and disables automerge. Each update
must pass the same ordered fixed-point check and full Maven gate after complete diff review.

Quarkus migrations remain owned by `quarkus:update` in the specific dependency-update branch.
Generic dependency and plugin versions remain owned by POM properties and Renovate. The general
maintenance composite does not update the Quarkus BOM or other dependency versions.

This decision changes contributor tooling and the maintained source baseline. It does not change a
supported runtime or user contract, so `SPEC.md` does not need an update.

### Consequences

* Good, because the accepted repository state captures both semantic rewrites and canonical
  formatting in the order maintainers use them.
* Good, because formatting-only disagreement between OpenRewrite and Spotless no longer rejects an
  otherwise useful recipe.
* Good, because large justified modernizations are accepted after semantic and full-gate review.
* Good, because every evaluated rejection has a durable recipe-specific explanation.
* Good, because useful recurrence guards remain enforced even when the current baseline has no
  matching source.
* Good, because CI detects drift without receiving authority to persist a mutation.
* Good, because direct artifact pins make generated Picnic recipe IDs reproducible and make a
  removed or renamed ID fail validation.
* Bad, because CI performs two mutating tool invocations before it can prove the checkout was
  already canonical.
* Bad, because maintainers must repeat candidate measurement and complete diff review when a
  catalog release changes behavior.
* Neutral, because Quarkus upgrades and dependency versions continue through their existing owners.

### Confirmation

This decision remains implemented when:

* the `openrewrite` profile and repository composite use exact versions, fully qualified IDs, and
  reviewed option values;
* normal `./mvnw -q spotless:check verify` neither invokes OpenRewrite nor changes source;
* OpenRewrite followed by Spotless leaves clean committed `HEAD` unchanged;
* the required CI job applies those tools in that order before the full Maven gate;
* CI detects tracked and nonignored untracked changes and has no repository write permission;
* every newly evaluated leaf receives an individual decision, and zero results alone never decide
  against activation;
* Renovate cannot group or automerge a recipe update and requires its release-age delay and
  dashboard approval; and
* Quarkus and general dependency versions remain outside the recurring composite.

## Pros and Cons of the Options

### Curated OpenRewrite-To-Spotless Pipeline

* Good, because contributors need no new runtime or hosted account.
* Good, because semantic recipes and the established formatter have explicit, non-overlapping
  ownership.
* Good, because repository drift is detected through a normal Git worktree comparison.
* Bad, because the repository must inventory mixed catalog licenses and re-review generated diffs.

### Raw OpenRewrite Cleanliness Or Idempotence

* Good, because it is simple to express with `rewrite:dryRun` or a repeated raw apply.
* Bad, because it treats OpenRewrite's intermediate formatting as canonical and rejects recipes
  whose correct final form emerges only after Spotless.
* Bad, because raw idempotence does not prove that the committed OpenRewrite-to-Spotless state is
  current.

### Moderne CLI Or Platform

* Good, because it provides orchestration features for multi-repository migrations.
* Bad, because one repository does not justify another runtime, account, credential, or operational
  dependency.
* Bad, because hosted source or semantic-tree handling requires a separate privacy, security, and
  licensing decision.

### Quarkus Update Command Alone

* Good, because Quarkus owns migration knowledge for its own target versions.
* Bad, because it does not provide recurring Java, test, or POM maintenance outside Quarkus
  upgrades.

### Existing Tools Without OpenRewrite

* Good, because existing tools already provide strong formatting, defect, security, and version
  checks.
* Bad, because semantic migrations remain ad hoc and lack a shared recurring baseline.

## More Information

The [OpenRewrite maintenance guide](../openrewrite.md) defines commands, CI behavior, artifact
handling, pinned inventory, rollback, and update ownership. The
[recipe decision record](../openrewrite-recipe-decisions.md) is the durable per-leaf audit.
