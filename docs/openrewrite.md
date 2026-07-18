# OpenRewrite Maintenance

OpenRewrite is an opt-in semantic-maintenance lane. It complements the repository's formatting,
compiler, static-analysis, and test gates; it does not replace them. The normal
`./mvnw -q spotless:check verify` command remains non-mutating and does not activate OpenRewrite.

The reviewed allowlist is the `ch.fmartin.symphony.trello.OpenRewriteMaintenance` composite in
[`rewrite.yml`](../rewrite.yml). The recipe-specific positive and zero-result decisions are in the
[recipe decision record](openrewrite-recipe-decisions.md) and its linked audit appendix. The
composite contains 405 exact entries: the previous 401-entry reviewed state plus four compatible
improvements accepted by the complete rejected-inventory re-audit.

## Accepted Ordered State

OpenRewrite and Spotless form one ordered normalization pipeline. If `R` is the reviewed
OpenRewrite composite, `F` is Spotless, and `S` is the committed repository state, CI accepts
exactly this condition:

```text
F(R(S)) = S
```

Spotless owns final Java, POM, JSON, YAML, and Markdown formatting. OpenRewrite may therefore emit
noncanonical intermediate formatting. This decision does not require:

- raw OpenRewrite output to equal the committed state;
- a second unformatted OpenRewrite run to produce no changes;
- raw OpenRewrite output to be idempotent; or
- OpenRewrite and Spotless to commute.

## Commands

Run discovery when editing the allowlist or updating a recipe catalog:

```shell
./mvnw -Popenrewrite rewrite:discover
```

Apply and verify the accepted pipeline in this exact order:

```shell
./mvnw -Popenrewrite rewrite:run
./mvnw -q spotless:apply
git diff
./mvnw -q spotless:check verify
```

Review the complete combined diff. After committing it, rerun the first two commands from clean
`HEAD` and require this command to print nothing:

```shell
git status --porcelain=v1 --untracked-files=all
```

Use `rewrite:dryRun` only as an optional raw preview:

```shell
./mvnw clean
./mvnw -Popenrewrite -Drewrite.exportDatatables=true rewrite:dryRun
```

The preview writes `target/rewrite/rewrite.patch` and, when requested, timestamped data tables
under `target/rewrite/datatables/`. OpenRewrite does not remove stale generated output, so run
`./mvnw clean` before collecting evidence. These files contain source paths and excerpts. Scan
them before copying or uploading:

```shell
find target/rewrite -type f \( -name '*.patch' -o -name '*.csv' \) -print0 |
  xargs -0 -r -n 1 scripts/check-private-context --file
```

## CI

The required Linux `test` job:

1. applies the curated OpenRewrite composite;
2. starts a separate Maven invocation and applies Spotless;
3. fails when `git status --porcelain=v1 --untracked-files=all` reports a tracked or nonignored
   untracked change; and
4. runs `spotless:check verify`.

Separate Maven invocations are intentional. When OpenRewrite changes `pom.xml`, Spotless must load
the rewritten Maven model before applying Java formatting and `sortPom`.

The checkout is writable, but the job has only `contents: read` repository permission. It does not
commit, push, comment, open a pull request, or upload source, patches, data tables, or semantic
trees. Maven may write ignored `target/` content; the gate checks the Git worktree, not every
generated filesystem path.

## Applying And Reviewing A Change

Use a clean branch or disposable worktree because OpenRewrite does not distinguish its own edits
from unrelated uncommitted work.

1. Confirm that every configured recipe is discoverable.
2. Apply OpenRewrite and then Spotless.
3. Review every source, POM, test, workflow, and documentation change in the combined diff.
4. Run the focused tests for affected behavior, then the full Maven gate.
5. Commit the reviewed result.
6. From clean `HEAD`, apply OpenRewrite and Spotless again and confirm that Git remains clean.

For rollback, delete a disposable worktree or restore only the explicitly reviewed paths. Do not
use a broad restore in a worktree that contains unrelated changes.

## Pinned Inventory

Versions are exact Maven properties in the root POM.

| Artifact | Version | License | Use |
| --- | --- | --- | --- |
| `org.openrewrite.maven:rewrite-maven-plugin` | `6.44.0` | Apache-2.0 | Local Maven integration |
| `org.openrewrite:rewrite-java` | `8.87.0` | Apache-2.0 | Java parser and core recipes, pinned by the plugin BOM |
| `org.openrewrite:rewrite-maven` | `8.87.0` | Apache-2.0 | Maven recipes |
| `org.openrewrite.recipe:rewrite-static-analysis` | `2.39.0` | Moderne Source Available | Curated Java analysis recipes |
| `org.openrewrite.recipe:rewrite-testing-frameworks` | `3.42.0` | Moderne Source Available | Curated JUnit, Mockito, and AssertJ recipes |
| `org.openrewrite.recipe:rewrite-migrate-java` | `3.40.0` | Moderne Source Available | Curated Java 25 migrations |
| `tech.picnic.error-prone-support:error-prone-contrib:recipes` | `0.30.0` | MIT | Directly pinned AssertJ Refaster recipe leaves |
| `io.quarkus:quarkus-update-recipes` | `1.12.0` | Apache-2.0 | Reference only; not loaded by the general composite |

The source-available terms permit an end user to apply the selected recipes to its own code. This
repository uses the recipes locally and does not redistribute them as a service. No source or
lossless semantic tree is uploaded to Moderne. A Moderne platform or CLI integration requires a
separate privacy, security, licensing, and operational decision.

The direct Picnic `recipes` classifier makes the selected generated leaf IDs an explicit,
versioned dependency. A catalog update that renames or removes one of those IDs fails active-recipe
validation and requires the same candidate review as any other executable transformation update.

## Updates And Ownership

Recipe and engine updates change executable transformations. Renovate waits seven days, requires
dependency-dashboard approval, keeps them out of unrelated dependency groups, and disables
automerge. Before merging an update, a maintainer applies the ordered pipeline, reviews the
combined diff, runs the full gate, and proves that the committed state remains an ordered fixed
point.

A catalog update also requires a fresh applicability review of every newly discovered zero-result
leaf. Zero current results do not justify exclusion. Select a compatible leaf when it makes code
meaningfully better or enforces a generally useful invariant for Java, Maven, or an existing
repository ecosystem. Compatible means that no previously supported, working use stops working;
correcting invalid or already-broken behavior is compatible when the generated behavior is
genuinely better. Record preferable transformations that stop working use as inactive
breaking-release candidates. Reject unsafe, context-dependent, defective, or worse output. Do not
load a recipe solely for an absent language, build system, library, framework, or capability. Keep
target-version migrations with the workflow that selects that target.

Quarkus migrations remain owned by `quarkus:update` in the dependency-update branch. Generic
dependency and plugin version changes remain owned by their POM properties and Renovate. The
general maintenance composite does not update the Quarkus BOM or dependency versions.

## Candidate Evidence

The initial broad families and supplementary candidates were measured independently against
pre-application source and clean worktrees at the same rebased PR baseline on 2026-07-18. Every
applicable audit covered all 142 Maven main and 113 Maven test Java files plus the root POM without
`SourcesFileErrors`. Three Java helper programs under `scripts/` remain outside Maven source roots
and therefore outside this lane.

Counts, provider and license ownership, exact decisions, compilation failures, formatting failures,
compatible bug corrections, breaking-release candidates, and recurrence guards are recorded per
candidate in the
[recipe decision record](openrewrite-recipe-decisions.md) and its linked zero-result appendix. A
parent composite is not treated as an individual rejection when its descendants have mixed
decisions.
