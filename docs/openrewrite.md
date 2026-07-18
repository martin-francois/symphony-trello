# OpenRewrite Maintenance

OpenRewrite is an opt-in semantic-maintenance lane. It complements the repository's formatting,
compiler, static-analysis, and test gates; it does not replace them. The normal
`./mvnw -q spotless:check verify` command does not activate OpenRewrite and remains non-mutating.

The reviewed allowlist is the `ch.fmartin.symphony.trello.OpenRewriteMaintenance` composite in
[`rewrite.yml`](../rewrite.yml). CI and normal maintainer commands use that composite without
command-line recipe overrides.

## Commands

Run these commands from the repository root:

```shell
# List the recipes available from the pinned artifacts. This does not edit source.
./mvnw -Popenrewrite rewrite:discover

# Check the reviewed composite. This does not edit source and exits nonzero when it proposes a diff.
./mvnw clean
./mvnw -Popenrewrite rewrite:dryRun

# Apply the reviewed composite. This edits files and is a maintainer-only action.
./mvnw -Popenrewrite rewrite:run
```

`rewrite:dryRun` writes its patch to `target/rewrite/rewrite.patch`. With data-table export enabled,
the supporting CSV files are under a timestamped `target/rewrite/datatables/` directory. These
outputs contain source paths and excerpts, so scan them before copying or uploading them:

```shell
find target/rewrite -type f \( -name '*.patch' -o -name '*.csv' \) -print0 |
  xargs -0 -r -n 1 scripts/check-private-context --file
```

OpenRewrite does not delete output from an earlier run. Before producing a report in a reused
worktree, run `./mvnw clean` to remove all generated Maven output, then run the dry run. A successful
clean dry run does not prove that an older `target/rewrite/rewrite.patch` is current.

The required CI `test` job runs `rewrite:dryRun` whenever normal CI runs for a pull request or push
to `main`. Release Please retains its existing normal-CI skip policy. This is the only automated
OpenRewrite lane. It has read-only repository permission and neither applies nor commits changes.
CI does not upload generated patches or data tables. Maintainers who need an additional dry run use
the local command above and inspect the generated output in their own clean branch or disposable
worktree.

## Applying And Reviewing A Change

Use a clean branch or a disposable worktree. OpenRewrite does not distinguish its own edits from
unrelated uncommitted work during review or rollback.

1. Run `./mvnw -Popenrewrite rewrite:discover` and confirm that every configured recipe is present.
2. Run `./mvnw clean`, then `./mvnw -Popenrewrite rewrite:dryRun`, and inspect the newly generated
   `target/rewrite/rewrite.patch`.
3. Run `./mvnw -Popenrewrite rewrite:run`.
4. Review the complete `git diff`, including POM and test changes. Reject dependency, plugin, Java
   release, Quarkus BOM, analyzer, workflow, public API, suppression, concurrency, process,
   filesystem, retry, redaction, or security changes unless the current task explicitly owns them.
5. Run `./mvnw -q spotless:apply`, review formatter changes, and run
   `./mvnw -q spotless:check verify`.
6. Record the current `git diff`, run `./mvnw -Popenrewrite rewrite:run` a second time, and confirm
   that the second run adds no diff.
7. Run `./mvnw clean`, then `./mvnw -Popenrewrite rewrite:dryRun`; a clean accepted baseline exits
   successfully and has no stale patch from an earlier run.

For rollback, delete a disposable worktree, or use `git diff --name-only` and restore only the
explicit OpenRewrite-owned paths after reviewing that list. Do not use a broad restore in a
worktree that contains unrelated changes.

Recipe and engine updates are executable transformation changes. Renovate waits seven days,
requires dependency-dashboard approval, keeps each OpenRewrite update out of unrelated dependency
groups, and disables automerge for every update type. The update pull request must pass the
blocking dry run and the full Maven gate. A maintainer must also apply the recipe in a clean branch,
review the complete diff, and repeat the apply command to prove idempotence.

## Pinned Inventory

Versions were resolved from Maven Central metadata on 2026-07-17 and are exact Maven properties in
the root POM.

| Artifact | Version | Upstream source | License | Use |
| --- | --- | --- | --- | --- |
| `org.openrewrite.maven:rewrite-maven-plugin` | `6.44.0` | [`openrewrite/rewrite-maven-plugin` tag `v6.44.0`](https://github.com/openrewrite/rewrite-maven-plugin/tree/v6.44.0) | [Apache-2.0](https://github.com/openrewrite/rewrite-maven-plugin/blob/v6.44.0/LICENSE) | Local Maven integration |
| `org.openrewrite:rewrite-java` | `8.87.0` | [`openrewrite/rewrite` commit `2304703`](https://github.com/openrewrite/rewrite/tree/2304703c678e9febf855d22adf18aeb32f44b7aa/rewrite-java) | [Apache-2.0](https://github.com/openrewrite/rewrite/blob/2304703c678e9febf855d22adf18aeb32f44b7aa/LICENSE) | Java parser and core recipes, pinned by the plugin BOM |
| `org.openrewrite:rewrite-maven` | `8.87.0` | [`openrewrite/rewrite` commit `2304703`](https://github.com/openrewrite/rewrite/tree/2304703c678e9febf855d22adf18aeb32f44b7aa/rewrite-maven) | [Apache-2.0](https://github.com/openrewrite/rewrite/blob/2304703c678e9febf855d22adf18aeb32f44b7aa/LICENSE) | Maven recipes |
| `org.openrewrite.recipe:rewrite-static-analysis` | `2.39.0` | [`openrewrite/rewrite-static-analysis` commit `e51c700`](https://github.com/openrewrite/rewrite-static-analysis/tree/e51c700117e6d1bbb4c8a6e32d5f590e457b8e12) | [Moderne Source Available](https://github.com/openrewrite/rewrite-static-analysis/blob/e51c700117e6d1bbb4c8a6e32d5f590e457b8e12/LICENSE) | Curated Java cleanups |
| `org.openrewrite.recipe:rewrite-testing-frameworks` | `3.42.0` | [`openrewrite/rewrite-testing-frameworks` commit `2b5d852`](https://github.com/openrewrite/rewrite-testing-frameworks/tree/2b5d8526dc226ff4794716133b2d0780eb257530) | [Moderne Source Available](https://github.com/openrewrite/rewrite-testing-frameworks/blob/2b5d8526dc226ff4794716133b2d0780eb257530/LICENSE) | Curated JUnit 5 cleanup |
| `org.openrewrite.recipe:rewrite-migrate-java` | `3.40.0` | [`openrewrite/rewrite-migrate-java` commit `6584812`](https://github.com/openrewrite/rewrite-migrate-java/tree/658481254a6ee678f5f162e51d8d49ee01c75877) | [Moderne Source Available](https://github.com/openrewrite/rewrite-migrate-java/blob/658481254a6ee678f5f162e51d8d49ee01c75877/LICENSE) | Curated Java 25 API cleanup |
| `io.quarkus:quarkus-update-recipes` | `1.12.0` | [`quarkusio/quarkus-update-recipes` tag `1.12.0`](https://github.com/quarkusio/quarkus-update-recipes/tree/1.12.0) | [Apache-2.0](https://github.com/quarkusio/quarkus-update-recipes/blob/1.12.0/LICENSE) | Reference only; not loaded by the general composite |

The source-available terms permit an end user to apply the selected recipes to its own code. This
repository uses the recipes locally for its own source and does not redistribute the recipe
artifacts as a service. No source or lossless semantic tree is uploaded to Moderne. A Moderne
platform or CLI integration requires a separate privacy, security, licensing, and operational
decision.

Maven Central published `rewrite-maven` `8.87.3` after the initial issue snapshot. The selected
plugin `6.44.0` imports the OpenRewrite `8.87.0` BOM and declares its engine dependencies at
`8.87.0`; the explicit plugin dependency therefore pins the newest plugin-tested compatible
version rather than overriding the plugin with a newer engine release.

Quarkus upgrades remain owned by `quarkus:update` in the dependency-update branch. Maintainers must
inspect its diff, read the target migration guide, and run the full Maven gate. The general
maintenance composite does not load `quarkus-update-recipes` and does not update the Quarkus BOM.

## Candidate Method

Each requested broad family was run separately in report-only mode against the same clean
`origin/main` baseline, with the pinned plugin and catalog artifacts. Command-line recipe selection
was used only for this one-time candidate measurement; it is not used by CI or the documented
maintenance commands.

The Maven parser reported 483 source documents for every family and emitted no
`SourcesFileErrors` rows. The intended Maven Java roots contained all 142 tracked
`src/main/java/**/*.java` files and all 113 tracked `src/test/java/**/*.java` files. The root
`pom.xml` was included. Three tracked Java helper programs are outside Maven's main and test source
roots and were therefore intentionally outside the candidate parser:
`scripts/FakeCodexAppServer.java`, `scripts/PatchLiveE2eWorkflow.java`, and
`scripts/WriteNarrowRealCodexWorkflow.java`. There were no other tracked Java omissions.

Counts below are `SourcesFileResults` leaf rows with a positive estimated time saving. Aggregate
parent rows are excluded. A leaf can perform more than one edit in its one file-result row.

| Candidate family | Leaf result rows | Unique changed files | Parse or recipe errors | Outcome |
| --- | ---: | ---: | ---: | --- |
| `org.openrewrite.staticanalysis.CommonStaticAnalysis` | 57 | 39 | 0 | Broad composite rejected; three children selected |
| `org.openrewrite.java.testing.junit5.JUnit5BestPractices` | 16 | 14 | 0 | Broad composite rejected; one child selected |
| `org.openrewrite.maven.BestPractices` | 4 | 1 | 0 | Broad composite rejected; one child selected |
| `org.openrewrite.java.migrate.UpgradeToJava25` | 51 | 45 | 0 | Broad composite rejected; one child selected |

### Common Static Analysis

| Recipe ID | Provider / license | Baseline | Invariant, representative diff, overlap, and risk | Decision |
| --- | --- | ---: | --- | --- |
| `org.openrewrite.java.OrderImports` | `rewrite-java:8.87.0` / Apache-2.0 | 21 rows / 21 files | Orders imports and changes wildcard static imports. Spotless already owns import order and unused imports; accepting this would duplicate the formatter and create style churn. | Rejected |
| `org.openrewrite.staticanalysis.EqualsAvoidsNull` | `rewrite-static-analysis:2.39.0` / Moderne Source Available | 20 / 20 | Moves known non-null literals or constants to the receiver of `equals`. JSpecify and existing invariants already make unexpected nulls visible; the reversed expressions hide invariant failures and reduce readability. | Rejected |
| `org.openrewrite.staticanalysis.ReplaceLambdaWithMethodReference` | `rewrite-static-analysis:2.39.0` / Moderne Source Available | 6 / 6 in the family; 2 files and 4 edits when run directly | Preserves the functional delegate while replacing forwarding lambdas with method references. Four family results depended on the rejected `EqualsAvoidsNull` rewrite, so only the two independently reproducible files were applied. Compiler checks cover target typing. | **Accepted** |
| `org.openrewrite.staticanalysis.UsePortableNewlines` | `rewrite-static-analysis:2.39.0` / Moderne Source Available | 4 / 4 | Replaces escaped `\\n` in shell and JSON fixtures with `%n`. Those strings model exact protocols and generated scripts, so host-dependent line endings would change fixture and process behavior. | Rejected |
| `org.openrewrite.staticanalysis.UnnecessaryExplicitTypeArguments` | `rewrite-static-analysis:2.39.0` / Moderne Source Available | 2 / 2, 3 edits | Removed explicit `Optional` type witnesses. Compilation then inferred sibling subtypes and failed with `Failed cannot be converted to Found` and `ItemClassification cannot be converted to Exact`. The explicit witnesses are required for the interface result type. | Rejected after compile validation |
| `org.openrewrite.staticanalysis.LambdaBlockToExpression` | `rewrite-static-analysis:2.39.0` / Moderne Source Available | 1 / 1 | Collapses a nested, side-effecting `Optional` lambda. The block exposes the sequencing better and Spotless already owns formatting. | Rejected |
| `org.openrewrite.staticanalysis.IndexOfReplaceableByContains` | `rewrite-static-analysis:2.39.0` / Moderne Source Available | 1 / 1, 2 edits | Replaces only nonnegative `indexOf` existence checks with `contains`; the adjacent `indexOf` calls that compare ordering remain intact. Tests cover the list-order invariant. | **Accepted** |
| `org.openrewrite.staticanalysis.FixStringFormatExpressions` | `rewrite-static-analysis:2.39.0` / Moderne Source Available | 1 / 1 | Changes `%s\\n` to `%s%n` inside generated shell/text-block content. The exact serialized text and process protocol are intentional; the platform substitution is a behavior risk. | Rejected |
| `org.openrewrite.staticanalysis.FinalizePrivateFields` | `rewrite-static-analysis:2.39.0` / Moderne Source Available | 1 / 1 | Marks one test builder's constructor-only `workflowPath` field `final`. It complements compiler checks by making the test-fixture lifecycle invariant explicit and has no runtime/public API effect. | **Accepted** |

### JUnit 5 Best Practices

| Recipe ID | Provider / license | Baseline | Invariant, representative diff, overlap, and risk | Decision |
| --- | --- | ---: | --- | --- |
| `org.openrewrite.java.testing.cleanup.SimplifyTestThrows` | `rewrite-testing-frameworks:3.42.0` / Moderne Source Available | 10 rows / 10 files | Broadens precise `throws IOException` declarations to `throws Exception`. Tests compile either way, but the broader contracts conceal the boundary each test exercises. | Rejected |
| `org.openrewrite.java.testing.junit5.CsvSourceToValueSource` | `rewrite-testing-frameworks:3.42.0` / Moderne Source Available | 4 / 4, 9 annotation conversions | Converts single-argument CSV inputs to typed string or integer value sources without changing values or parameterized-test names. Picnic's annotation-order check required `@ParameterizedTest` before `@ValueSource`; the generated order was adjusted accordingly. | **Accepted** |
| `org.openrewrite.java.testing.junit5.AddParameterizedTestAnnotation` | `rewrite-testing-frameworks:3.42.0` / Moderne Source Available | 2 / 2 | Adds `@ParameterizedTest` to methods already driven by `@FuzzTest`. Combining two test-template annotations risks duplicate or conflicting runtime discovery. | Rejected |

### Maven Best Practices

| Recipe ID | Provider / license | Baseline | Invariant, representative diff, overlap, and risk | Decision |
| --- | --- | ---: | --- | --- |
| `org.openrewrite.maven.cleanup.ExplicitPluginGroupId` | `rewrite-maven:8.87.0` / Apache-2.0 | 1 row / 1 file, 6 edits | Adds the canonical `org.apache.maven.plugins` group to six existing plugin declarations. Artifact identity and version ownership stay unchanged while resolution is explicit. | **Accepted** |
| `org.openrewrite.maven.cleanup.ExplicitPluginVersion` | `rewrite-maven:8.87.0` / Apache-2.0 | 1 / 1 | Adds a duplicated literal `3.28.0` in a profile where the plugin version is already inherited. The literal would bypass the property and Renovate ownership model. | Rejected |
| `org.openrewrite.maven.RemoveRedundantDependencyVersions` | `rewrite-maven:8.87.0` / Apache-2.0 | 1 / 1 | Removes explicit picocli, Guava, and JSpecify properties and versions because the BOM also manages them. The repository deliberately pins and exposes those versions to Renovate. | Rejected |
| `org.openrewrite.maven.SortDependencies` | `rewrite-maven:8.87.0` / Apache-2.0 | 1 / 1 | Reorders dependency blocks broadly. Spotless `sortPom` already owns POM ordering, including project comments and grouping. | Rejected |

### Java 25 Upgrade Audit

| Recipe ID | Provider / license | Baseline | Invariant, representative diff, overlap, and risk | Decision |
| --- | --- | ---: | --- | --- |
| `org.openrewrite.java.migrate.nio.file.RedundantUtf8Charset` | `rewrite-migrate-java:3.40.0` / Moderne Source Available | 39 rows / 39 files | Removes explicit `StandardCharsets.UTF_8` from file APIs now defaulting to UTF-8. Explicit encoding is repository policy and documents filesystem/protocol intent; removal adds no migration value. | Rejected |
| `org.openrewrite.java.migrate.util.ListFirstAndLast` | `rewrite-migrate-java:3.40.0` / Moderne Source Available | 4 / 4, 6 edits | Uses Java 25 `List.getFirst()` and `getLast()` in tests where an assertion or branch already proves non-emptiness. This clarifies intent without changing public API or production behavior. | **Accepted** |
| `org.openrewrite.java.migrate.lang.UseTextBlocks` | `rewrite-migrate-java:3.40.0` / Moderne Source Available | 3 / 3 | Converts exact prompt, installer, and diagnostic fixture strings to text blocks. Incidental indentation and line-ending changes create protocol and snapshot risk. | Rejected |
| `org.openrewrite.maven.UpgradePluginVersion` | `rewrite-maven:8.87.0` / Apache-2.0 | 2 rows / 1 file | Proposes JaCoCo `0.8.14` to `0.8.15` and SpotBugs `4.9.8.3` to `4.9.8.5`. Renovate owns plugin versions; silent version changes are outside the semantic lane. | Rejected |
| `org.openrewrite.maven.UseMavenCompilerPluginReleaseConfiguration` | `rewrite-maven:8.87.0` / Apache-2.0 | 1 / 1 | Replaces `${maven.compiler.release}` with `${java.version}`. The current property boundary is deliberate and already configures the release explicitly. | Rejected |
| `org.openrewrite.java.migrate.lang.IfElseIfConstructToSwitch` | `rewrite-migrate-java:3.40.0` / Moderne Source Available | 1 / 1 | Rewrites model-default resolution to a pattern switch. The style-only change expands the null/default semantic surface and adds no missing Java 25 behavior. | Rejected |
| `org.openrewrite.java.migrate.io.ReplaceSystemOutWithIOPrint` | `rewrite-migrate-java:3.40.0` / Moderne Source Available | 1 / 1 | Replaces `System.out` in a process helper with the newer compact-source `IO` API while the code separately flushes `System.out`. Mixing output lifecycles risks its parent-process protocol. | Rejected |

`org.openrewrite.java.migrate.JavaBestPractices` was rejected wholesale without adding it to the
candidate allowlist. Its broad `var`, dependency-replacement, JSpecify, text-block, and instance
`main` changes cross repository policy and public/runtime boundaries. A useful child requires its
own report-only measurement and review before selection.

### PR 585 Recurrence Audit

The [PR 585 recipe-coverage audit](https://github.com/martin-francois/symphony-trello/issues/588)
provided two additional measurements against immutable pre-refactor commit `82e8172`. These
measurements use the same pinned catalog artifacts and licenses listed above.

`org.openrewrite.java.migrate.lang.MigrateProcessWaitForDuration` from
`rewrite-migrate-java:3.40.0` changed 15 invocations in 9 files after parsing 450 source documents
with no parse, type-attribution, or recipe errors. Twelve calls produced the same call expression
later merged by PR 585. Three calls that first converted an existing duration to milliseconds or
nanoseconds produced the conservative
`Duration.of(convertedValue, unit.toChronoUnit())` form. That output preserves the original
`long` plus `TimeUnit` truncation, timeout result, and interruption behavior; replacing it with the
original duration would require a separate nonnegative-value and precision proof. The applied
recipe passed the full Maven gate and a second dry run was clean. Current `main` already contains
the PR 585 transformation, so the child has zero current findings and is accepted as a proven
recurrence guard.

The symbolic-link assertion hunk was reproduced exactly by an ordered composition of
`tech.picnic.errorprone.refasterrules.AssertJPathRulesRecipes$AssertThatIsSymbolicLinkRecipe` and
`org.openrewrite.java.testing.assertj.StaticImports`. In isolation, the Picnic leaf adds a regular
`org.assertj.core.api.Assertions` import and emits a qualified `Assertions.assertThat(...)` call.
PMD rejects that intermediate output with `UnnecessaryFullyQualifiedName`. `StaticImports` then
uses `org.openrewrite.java.UseStaticImport` to reuse the base file's existing static `assertThat`
import and reproduce the merged hunk. The leaf is an undocumented generated API supplied at
runtime by
`tech.picnic.error-prone-support:error-prone-contrib:0.30.0:recipes` under the
[MIT license](https://github.com/PicnicSupermarket/error-prone-support/blob/27235b89b057a3d6d86871a772182eae7bd2e9c7/LICENSE);
the `StaticImports` recipe is supplied by `rewrite-testing-frameworks:3.42.0` under the Moderne
Source Available license. The composition reproduced one hunk in one file and passed the full gate
and idempotence check. Relying on the transitive Picnic FQCN would make the allowlist compatibility
unstable. Loading the broad
`org.openrewrite.java.testing.assertj.Assertj` composite instead produced 79 positive leaf rows, 185
total result rows, and 227 zero-context hunks across 36 files. Only the one composed hunk
overlapped the merged PR 585 change; none of the 246 separately inventoried supplementary AssertJ
hunks overlapped. Both approaches are rejected for this lane. A future task can pin the Picnic
recipe classifier, review that additional dependency/license boundary, and evaluate the narrow
two-recipe composition.

## Accepted Baseline

The repository composite contains only these fully qualified children:

* `org.openrewrite.staticanalysis.ReplaceLambdaWithMethodReference`
* `org.openrewrite.staticanalysis.IndexOfReplaceableByContains`
* `org.openrewrite.staticanalysis.FinalizePrivateFields`
* `org.openrewrite.java.testing.junit5.CsvSourceToValueSource`
* `org.openrewrite.maven.cleanup.ExplicitPluginGroupId`
* `org.openrewrite.java.migrate.lang.MigrateProcessWaitForDuration`
* `org.openrewrite.java.migrate.util.ListFirstAndLast`

The accepted initial diff is limited to the six explicit Maven plugin groups and test-source
cleanups described above. It does not change production runtime code, public API, Java release,
Quarkus BOM, dependency or plugin versions, analyzer configuration, workflow behavior, or
suppressions. After applying and formatting, the full Maven gate passes, a second
`rewrite:run` produces no additional diff, and `rewrite:dryRun` exits successfully. These three
properties are the fixed clean baseline for the required CI gate.
