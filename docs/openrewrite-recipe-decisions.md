# OpenRewrite Recipe Decision Record

This record classifies the result-producing recipes evaluated for the initial maintenance lane and
defines the decision boundary for every evaluated zero-result candidate. The
[zero-result decision appendix](openrewrite-zero-result-decisions.md) gives each such recipe ID an
individual status and reason. A parent composite does not replace a leaf decision.

The initial allowlist evidence was measured against the pre-application source that became this
PR's reviewed feature-commit baseline. Supplemental audits used clean worktrees at that same
baseline on 2026-07-18. Every applicable audit covered all 142 Maven main and 113 Maven test Java
files plus the root POM, with no `SourcesFileErrors`. Counts are positive `SourcesFileResults` rows
and unique files unless a row states another unit. OpenRewrite's broader `sourceFiles` run metric
includes non-Java and ignored tooling inputs, so it is not used as a coverage claim. Every accepted
source change was evaluated as `OpenRewrite -> Spotless`, not as raw OpenRewrite output.

## Statuses

- **Accepted**: selected in `rewrite.yml` and applied to the baseline.
- **Accepted recurrence guard**: selected after an invariant review despite zero current findings.
- **Breaking-release candidate**: preferable transformation that remains inactive because it stops
  previously supported, working use.
- **Rejected**: evaluated and not selected for the recipe-specific reason in its row.
- **Contingent**: not selected because its language, build tool, library, framework, capability, or
  required migration target is absent from the recurring lane.
- **Deferred**: evaluation or activation requires a separate target-version or toolchain decision.
- **Parent/configured primitive**: not selected as a bare recipe ID; use reviewed children or an
  exactly configured wrapper.

## Catalog Legend

| Short name | Artifact and version | License |
| --- | --- | --- |
| Core | `org.openrewrite:rewrite-java:8.87.0` | Apache-2.0 |
| XML | `org.openrewrite:rewrite-xml:8.87.0`, resolved with Rewrite Maven | Apache-2.0 |
| Maven | `org.openrewrite:rewrite-maven:8.87.0` | Apache-2.0 |
| Static | `org.openrewrite.recipe:rewrite-static-analysis:2.39.0` | Moderne Source Available |
| Testing | `org.openrewrite.recipe:rewrite-testing-frameworks:3.42.0` | Moderne Source Available |
| Migrate | `org.openrewrite.recipe:rewrite-migrate-java:3.40.0` | Moderne Source Available |
| Picnic | `tech.picnic.error-prone-support:error-prone-contrib:0.30.0:recipes` | MIT |

## Accepted Recipes

| Recipe ID or configured instance | Catalog | Evidence | Individual rationale |
| --- | --- | --- | --- |
| `org.openrewrite.java.ShortenFullyQualifiedTypeReferences` | Core | 8 files | Replaces inline fully qualified types with imports, matching the repository Java style while retaining unambiguous type ownership. |
| `org.openrewrite.java.migrate.lang.JavadocToMarkdownDocComment` | Migrate | 43 / 43 | Converts eligible Javadocs to Java 25 Markdown documentation comments; code spans, links, and paragraph meaning remain represented while comment noise decreases. |
| `org.openrewrite.java.migrate.lang.var.UseVarForConstructors` | Migrate | 74 / 74 | Uses `var` only where a constructor on the right-hand side already states the concrete type, removing duplication without hiding it. |
| `org.openrewrite.java.migrate.lang.var.UseVarForTypeCast` | Migrate | 6 / 6 | Uses `var` where the right-hand cast already states the type, removing a repeated declaration type. |
| `org.openrewrite.java.migrate.lang.IfElseIfConstructToSwitch` | Migrate | 1 / 1 | Converts one closed type-dispatch chain to an exhaustive Java 25 pattern switch while preserving the existing null/default fallback. |
| `org.openrewrite.java.migrate.lang.UseTextBlocks` with `convertStringsWithoutNewlines=false` and `avoidLineContinuations=false` | Migrate | 3 rows / 3 files, 4 edits | Converts only genuinely multiline concatenations; single-line fixtures and continuation-sensitive shell text remain unchanged. |
| `org.openrewrite.java.migrate.nio.file.RedundantUtf8Charset` | Migrate | 39 rows / 39 files | Removes redundant charset arguments only from Java file APIs whose contract already specifies UTF-8, reducing call noise without changing bytes. Later AssertJ composition expands the combined UTF-8 diff to 42 files. |
| `org.openrewrite.staticanalysis.AnnotateNullableParameters` | Static | 4 / 4 | Adds `@Nullable` where the method body already accepts and guards null at reviewed configuration, hook, and workspace boundaries. |
| `org.openrewrite.staticanalysis.HideUtilityClassConstructor` | Static | 2 files | Adds private constructors to two all-static test classes so their non-instantiable role is explicit. |
| `org.openrewrite.staticanalysis.FinalClass` | Static | expanded ordered run: 1 / 1 | Marks `ArchitectureTest` final after the earlier utility-class recipe makes its constructor private, stating the resulting non-subclassable contract explicitly. |
| `org.openrewrite.staticanalysis.RemoveUnusedPrivateMethods` | Static | 1 file | Removes the unused `validateServerPort` forwarding wrapper; the owning validation method remains directly exercised. |
| `org.openrewrite.staticanalysis.ReplaceLambdaWithMethodReference` | Static | 2 files / 4 direct edits | Replaces forwarding lambdas with target-typed method references. Four additional broad-family rows depended on a rejected equals rewrite and were not applied. |
| `org.openrewrite.staticanalysis.IndexOfReplaceableByContains` | Static | 1 / 1, 2 edits | Replaces only existence checks; adjacent index comparisons that encode ordering remain intact. |
| `org.openrewrite.staticanalysis.FinalizePrivateFields` | Static | 1 / 1 | Marks a constructor-only test-builder field final, making its lifecycle invariant explicit. |
| `org.openrewrite.java.testing.junit5.CsvSourceToValueSource` | Testing | 4 / 4, 9 annotations | Replaces one-argument CSV sources with typed value sources while preserving arguments and parameterized-test names. |
| `org.openrewrite.java.testing.mockito.RemoveTimesZeroAndOne` | Testing | 1 edit | Removes redundant `times(1)` from Mockito verification; default `verify` already requires one invocation. The cleaner call exposed two token-identical restart tests, which were consolidated into one enum-parameterized test with two named scenarios so the PMD duplication gate also remains clean. |
| `org.openrewrite.java.testing.assertj.AssertJIntegerRulesRecipes$AbstractIntegerAssertIsOneRecipe` | Testing | 5 / 5 | Replaces integer `isEqualTo(1)` with the semantic `isOne()` assertion. |
| `org.openrewrite.java.testing.assertj.ReturnActual` | Testing | 1 / 1 | Returns a helper's already-asserted value through AssertJ 3.27's public `actual()` API and removes a redundant second return. |
| `tech.picnic.errorprone.refasterrules.AssertJNumberRulesRecipes$NumberAssertIsNotNegativeRecipe` | Picnic | 3 / 3 | Replaces `isGreaterThanOrEqualTo(0)` with `isNotNegative()` while retaining assertion descriptions. |
| `tech.picnic.errorprone.refasterrules.AssertJNumberRulesRecipes$NumberAssertIsPositiveRecipe` | Picnic | 2 / 2 | Replaces `isGreaterThan(0)` with `isPositive()` for counts and offsets. |
| `tech.picnic.errorprone.refasterrules.AssertJObjectRulesRecipes$AssertThatHasToStringRecipe` | Picnic | 2 rows / 2 files, 3 edits | Keeps each JSON node or `StringBuilder` as the assertion subject and expresses its exact string representation with `hasToString`. It leaves `ByteArrayOutputStream.toString(Charset)` calls unchanged, avoiding the overload error in the rejected generic wrapper. |
| `tech.picnic.errorprone.refasterrules.AssertJOptionalRulesRecipes$AbstractOptionalAssertHasValueRecipe` | Picnic | 13 / 13 | Replaces Optional `contains(value)` with `hasValue(value)`; AssertJ delegates `hasValue` to the same presence and equality check. |
| `tech.picnic.errorprone.refasterrules.AssertJPathRulesRecipes$AssertThatHasFileNameRecipe` | Picnic | 2 rows / 2 files, 4 edits | Keeps the path as the diagnostic subject instead of asserting on `getFileName().toString()`. It runs before the general object-string leaf so the more specific path assertion wins. |
| `tech.picnic.errorprone.refasterrules.AssertJPathRulesRecipes$AssertThatHasParentRawRecipe` | Picnic | expanded ordered run: 2 edits / 1 file | Keeps each path as the assertion subject and expresses its expected raw parent directly, improving failure diagnostics without filesystem access. |
| `tech.picnic.errorprone.refasterrules.AssertJRulesRecipes$AssertThatContainsExactlyElementsOfListRecipe` | Picnic | 1 / 1 | Uses element-oriented ordered list comparison and improves mismatch diagnostics. |
| `tech.picnic.errorprone.refasterrules.AssertJRulesRecipes$AssertThatHasSameElementsAsSetRecipe` | Picnic | 1 / 1 | Uses set-oriented comparison only where actual and expected values are sets, so duplicate semantics cannot differ. |
| `tech.picnic.errorprone.refasterrules.AssertJStringRulesRecipes$AssertThatContentRecipe` | Picnic | isolated audit: 5 rows / 5 files | Moves file reads with an explicit charset into path content assertions while preserving that charset and improving path diagnostics. The preceding redundant-UTF-8 cleanup removes its current UTF-8 inputs, but the independently reviewed leaf remains selected for non-default charset assertions. |
| `tech.picnic.errorprone.refasterrules.AssertJStringRulesRecipes$AssertThatContentUtf8Recipe` | Picnic | combined output: 9 files / 43 edits | Converts Java's UTF-8-default file reads to path assertions with explicit UTF-8; it does not use AssertJ's platform-default no-argument content reader. Running after redundant-UTF-8 cleanup gives this leaf the combined current UTF-8 output. |
| `tech.picnic.errorprone.refasterrules.AssertJPathRulesRecipes$AssertThatIsSymbolicLinkRecipe` | Picnic | historical 1 hunk / 1 file; zero current findings | **Accepted recurrence guard.** PR 585 already replaced a boolean `Files.isSymbolicLink` assertion with the path-specific AssertJ assertion. The directly pinned Picnic classifier now makes the leaf ID stable, and the following `StaticImports` recipe normalizes its generated AssertJ call before repository checks run. |
| `org.openrewrite.java.UseStaticImport` through configured `org.openrewrite.java.testing.assertj.StaticImports` | Core with Testing wrapper | 15 rows / 15 files | The wrapper supplies the leaf's required method pattern and keeps generated AssertJ calls consistent with the repository's static-import convention. |
| `org.openrewrite.maven.cleanup.ExplicitPluginGroupId` | Maven | 1 / 1, 6 edits | Keeps every standard Maven plugin declaration explicit. The repeated canonical value is centralized in one POM property. |
| `org.openrewrite.java.migrate.lang.MigrateProcessWaitForDuration` | Migrate | historical 15 calls / 9 files; zero current findings | **Accepted recurrence guard.** PR 585 already applied and verified the Duration overload while preserving timeout truncation and interruption behavior. |
| `org.openrewrite.java.migrate.util.ListFirstAndLast` | Migrate | 4 / 4, 6 edits | Uses sequenced-list access where an assertion or branch already proves non-emptiness. |

## Rejected Recipes

Each row is an independent result-producing rejection. The complete re-audit retained every row
below: none of these concrete outputs became better merely because behavior changes are now
eligible. Parent-composite names do not stand in for these decisions.

| Recipe ID or configured instance | Catalog | Evidence | Individual rejection reason |
| --- | --- | --- | --- |
| `org.openrewrite.java.OrderImports` | Core | 21 / 21 | Collapses explicit AssertJ and Mockito static imports to wildcards. That obscures symbol ownership; Spotless already owns the accepted import order. |
| `org.openrewrite.java.RemoveUnusedImports` | Core | 6 / 6 | Its findings were secondary cleanup after other broad transformations. Spotless already removes unused imports in the final ordered state. |
| `org.openrewrite.java.format.MethodParamPad` | Core | 10 files | Changes whitespace that Spotless owns and produces no desired net semantic change after formatting. |
| `org.openrewrite.java.format.NoWhitespaceAfter` | Core | 5 files | Changes whitespace that Spotless owns and produces no desired net semantic change after formatting. |
| `org.openrewrite.staticanalysis.EqualsAvoidsNull` | Static | JavaBest: 20 rows / 20 files; Common Static: 19 / 19 | Reverses expressions whose current receivers are established non-null values. Literal-first equality hides violated nullness invariants and reads less naturally. |
| `org.openrewrite.staticanalysis.FixStringFormatExpressions` | Static | 1 / 1 | Replaces an exact newline inside generated shell content with `%n`, making the generated protocol host-dependent. |
| `org.openrewrite.staticanalysis.UsePortableNewlines` | Static | 4 / 4 | Rewrites exact shell and JSON fixture newlines and generated invalid Java (`\%n`) in one helper; the protocol bytes must remain platform-independent. |
| `org.openrewrite.staticanalysis.UnnecessaryExplicitTypeArguments` | Static | 2 / 2, 3 edits | Removing Optional type witnesses makes Java infer sibling subtypes and fails compilation (`Failed` versus `Found`, and `ItemClassification` versus `Exact`). |
| `org.openrewrite.staticanalysis.DeclarationSiteTypeVariance` | Static | 18 files | Adds `? super` and `? extends` wildcards to internal functional signatures without a subtype caller; the isolated ordered rerun then failed compilation in four production classes, so the output is neither valid nor clearer. |
| `org.openrewrite.staticanalysis.UnnecessaryThrows` | Static | 17 files | Removes declarations but also deletes intentional `catch (Exception)` behavior in installer helpers, changing runtime behavior. |
| `org.openrewrite.staticanalysis.UseCollectionInterfaces` | Static | 3 / 3 | Changes insertion-ordered `LinkedHashMap` declarations to `Map` even where oldest-entry eviction depends on insertion order, hiding the required data-structure invariant. |
| `org.openrewrite.java.migrate.io.ReplaceSystemOutWithIOPrint` | Migrate | 1 / 1 | Produces `IO.println(...)` followed by `System.out.flush()` in a subprocess handshake, splitting one exact protocol across two output APIs. |
| `org.openrewrite.java.migrate.lang.ExtractExplicitConstructorInvocationArguments` | Migrate | 11 / 11 | Generates undefined identifiers such as `clock1`, `workflowConfig1`, and `healthChecker1`; the result does not compile. |
| `org.openrewrite.java.migrate.lang.ReplaceUnusedVariablesWithUnderscore` | Migrate | 62 / 62 | The current Palantir formatter rejects the generated underscore output, so the ordered pipeline cannot normalize it. |
| `org.openrewrite.java.migrate.lang.UseTextBlocks` with its default `convertStringsWithoutNewlines=true` | Migrate | 11 rows / 9 files | Converts deliberately split marker strings and shell expressions into continuation-heavy text blocks that are harder to read. The allowlist instead uses the reviewed multiline-only configuration. |
| `org.openrewrite.java.migrate.lang.var.UseVarForGenericMethodInvocations` | Migrate | 63 / 63 | Hides Optional, collection, and map result contracts when the method name does not fully state the return type and can alter target-typing clarity. |
| `org.openrewrite.java.migrate.lang.var.UseVarForGenericsConstructors` | Migrate | 52 / 52 | Replaces interface-typed local declarations with variables inferred as concrete collection implementations, weakening the abstraction expressed at the declaration site. |
| `org.openrewrite.java.migrate.lang.var.UseVarForPrimitive` | Migrate | 94 / 94 | Hides primitive width and boolean/numeric type information where the initializer does not repeat it. |
| `org.openrewrite.java.migrate.util.UseEnumSetOf` | Migrate | 2 / 2 | Replaces immutable `Set.of` values, including a static constant, with mutable `EnumSet` instances. |
| `org.openrewrite.staticanalysis.AnnotateNullableMethods` | Static | 5 / 5 | Annotates implementations such as `StreamTerminal` and `RecordingTerminal` but leaves the owning `Terminal` contract unchanged, producing an incomplete nullness boundary. |
| `org.openrewrite.staticanalysis.AnnotateRequiredParameters` | Static | 1 / 1 | Removes the existing null guard in `WorkspaceManager.sanitize`, changing a domain-specific exception into a null-pointer exception. |
| `org.openrewrite.staticanalysis.LambdaBlockToExpression` | Static | 1 / 1 | Removes the outer block from a nested `Optional.ifPresent(... ifPresentOrElse(...))` chain in `WorkflowConfigEditor`. The denser indentation and closing delimiters make control flow harder to read, so the original block is retained. |
| `org.openrewrite.java.testing.assertj.CollapseConsecutiveAssertThatStatements` | Testing | 3 / 3 | Moves `// then` inside an already-started assertion chain in `TestConventionTest`, so the marker no longer introduces the test's then section. |
| `org.openrewrite.java.testing.assertj.SimplifyChainedAssertJAssertion` through `SimplifyChainedAssertJAssertions` | Testing | 5 rows / 4 files | Rewrites `ByteArrayOutputStream.toString(Charset)` as `hasToString(Charset, value)`, which does not compile because `hasToString` expects a string. The selected narrower Picnic object leaf independently owns the safe `StringBuilder` transformation, so that result does not justify the unsafe wrapper. |
| `org.openrewrite.java.testing.assertj.SimplifySequencedCollectionAssertions` | Testing | 2 / 2 | Emits `.first().endsWith(...)` and `.first().startsWith(...)` on `ObjectAssert<String>`; both call sites fail compilation. |
| `org.openrewrite.java.testing.cleanup.SimplifyTestThrows` | Testing | 10 / 10 | Broadens precise `throws IOException` declarations to `throws Exception`, hiding the checked boundary exercised by each test. |
| `org.openrewrite.java.testing.cleanup.TestsShouldIncludeAssertions` | Testing | 5 files | Treats custom domain assertion helpers as missing assertions and wraps tests in `assertDoesNotThrow`, obscuring existing diagnostics and given/when/then structure. |
| `org.openrewrite.java.testing.junit5.AddParameterizedTestAnnotation` | Testing | 2 / 2 | Adds `@ParameterizedTest` to methods already driven by Jazzer's parameterized `@FuzzTest`, creating redundant competing test-template declarations. |
| `tech.picnic.errorprone.refasterrules.AssertJThrowingCallableRulesRecipes$AssertThatThrownByIsInstanceOfIllegalArgumentExceptionClassHasMessageRecipe` | Picnic | 1 / 1 | Replaces the concise typed illegal-argument assertion with a longer generic throwable assertion and adds no diagnostic value. |
| `tech.picnic.errorprone.refasterrules.AssertJThrowingCallableRulesRecipes$AssertThatThrownByIsInstanceOfNullPointerExceptionClassRecipe` | Picnic | 1 / 1 | Replaces the typed null-pointer assertion with a longer generic assertion plus `isInstanceOf`, reducing expressiveness. |
| `org.openrewrite.maven.cleanup.ExplicitPluginVersion` | Maven | 1 / 1 | Duplicates the inherited PMD plugin version as a literal, bypassing the POM property and Renovate ownership boundary. |
| `org.openrewrite.maven.RemoveRedundantDependencyVersions` | Maven | 1 / 1 | Removes explicit Picocli, Guava, and JSpecify version properties that intentionally expose repository version ownership to Renovate. |
| `org.openrewrite.maven.SortDependencies` | Maven | 1 / 1 | Reorders BOM imports whose order affects dependency management; the OpenTelemetry security override intentionally precedes the Quarkus BOM. |
| `org.openrewrite.maven.UpgradePluginVersion` | Maven | 2 rows / 1 file | Changes JaCoCo and SpotBugs versions inside a semantic source lane; exact POM properties and Renovate own those upgrades. |
| `org.openrewrite.maven.UseMavenCompilerPluginReleaseConfiguration` | Maven | 1 / 1 | Replaces `${maven.compiler.release}` with `${java.version}`, bypassing the repository's explicit compiler-release boundary. |
| `org.openrewrite.maven.AddProperty` through configured `org.openrewrite.maven.cleanup.AddProjectBuildOutputTimestamp` | Maven | 1 row / 1 POM | Writes `1980-01-01T00:00:00Z`; `maven-pmd-plugin:3.28.0:pmd` rejects that value because its valid range starts at `1980-01-01T00:00:02Z`, so the repository verification gate fails. |

## Zero-Result Candidate Decisions

Zero current results are evidence that the frozen baseline already conforms; they are not a reason
to omit a useful rule. The audit therefore activates compatible improvements and generally
applicable guards for Java, Maven, JUnit, Mockito, AssertJ, Picnic, Guava, and JSpecify where those
ecosystems already exist. Compatibility means that no previously supported, working use stops
working. A correction to invalid or already-broken behavior remains compatible when the generated
behavior is genuinely better.

A candidate remains inactive only for its own safety, context, readability, ownership,
prerequisite, target-version, or compatibility reason. Preferable transformations that stop
supported, working use are recorded separately as breaking-release candidates instead of being
mislabelled as unsafe or valueless.

The [zero-result decision appendix](openrewrite-zero-result-decisions.md) records every known
zero-result recipe ID individually, including the former 14-row sample, mixed parents,
parameterized implementation recipes, and recipes that are contingent because their ecosystem is
absent. Quarkus updater declarations are listed separately there because no target migration was
executed; they are target-specific recipes, not zero-result evidence.

## Preferable Breaking-Release Candidates

The complete rejected-inventory re-audit identified 37 zero-result recipes whose default result is
preferable but whose transformation can stop a previously supported, working use. They remain
inactive in this compatible pull request. The
[dedicated breaking-release section](openrewrite-zero-result-decisions.md#preferable-breaking-release-candidates)
lists every recipe and its exact compatibility boundary.

No result-producing rejection moved into this section. Every measured positive output remains
rejected for its individual invalid-source, failed-gate, readability, diagnostics, protocol,
mutability, or ownership reason in the table above.

## Deferred Migrations

These migrations require a target-version or toolchain decision outside the recurring maintenance
lane. Their deferral is not a blanket rejection of unrelated children.

| Recipe ID or family | Catalog | Evidence | Individual deferral reason |
| --- | --- | --- | --- |
| `org.openrewrite.xml.ChangeTagValue` through configured `org.openrewrite.maven.UpgradeToModelVersion410` and `org.openrewrite.maven.MigrateToMaven4` | XML through Maven | 1 / 1 POM | Changes the POM model version to 4.1.0 while the repository wrapper remains Maven 3.9.16, so the rewritten project is not runnable with its committed toolchain. Evaluate it only with an explicit Maven 4 wrapper and CI migration. |
| `org.openrewrite.xml.ChangeTagAttribute` through configured `org.openrewrite.maven.UpgradeToModelVersion410` and `org.openrewrite.maven.MigrateToMaven4` | XML through Maven | 2 rows / 1 POM | Changes the POM namespace and schema location to the Maven 4.1 model while the repository wrapper remains Maven 3.9.16. Evaluate it only with an explicit Maven 4 wrapper and CI migration. |
| Quarkus target migration recipes | Quarkus updater, not loaded by this lane | 141 declarations discovered; no target migration executed | Every discovered declaration belongs to a named Quarkus, Camel Quarkus, or Minio version/extension migration. The appendix records each declaration. Evaluate it in the dependency-update branch only after selecting the target BOM and reviewing its migration guide. |

## Parent Composite Disposition

The following measured parents are not active because their descendants have mixed decisions or
require individually stable leaf IDs. This is an activation decision, not a blanket rejection, and
it does not replace any leaf row in this record or its appendix.

| Parent composite | Disposition |
| --- | --- |
| `org.openrewrite.staticanalysis.CommonStaticAnalysis` | Not active; individually selected general Java guards coexist with semantic changes, formatter-owned rules, and absent-capability leaves. |
| `org.openrewrite.java.testing.junit5.JUnit5BestPractices` through `org.openrewrite.java.testing.junit.JupiterBestPractices` | Not active; accepted JUnit recurrence guards coexist with precise-throws, raw-boolean, and fuzz-template regressions. |
| `org.openrewrite.java.testing.junit.JUnit6BestPractices` | Not active; accepted recurrence guards coexist with rejected assertion/test-removal behavior and target-specific JUnit migration children. |
| `org.openrewrite.java.testing.mockito.MockitoBestPractices` | Not active; accepted call-shape cleanup guards coexist with unsafe resource rewrites, rejected strictness changes, and absent legacy migrations. |
| `org.openrewrite.maven.BestPractices` | Not active; accepted POM recurrence guards coexist with Spotless-owned order, Maven 4 migration, and version-ownership changes. |
| `org.openrewrite.java.migrate.UpgradeToJava25` | Not active; individually selected Java 25 guards coexist with target-version, compiler-boundary, removed-subsystem, and dependency changes owned elsewhere. |
| `org.openrewrite.java.migrate.JavaBestPractices` | Not active; individually selected Java, Guava, JSpecify, and JDK guards coexist with source-breaking, target-specific, and absent-capability leaves. |
| `org.openrewrite.java.testing.assertj.Assertj` | Not active; individually selected assertion guards coexist with descendants that fail compilation or conflict with the repository's diagnostic conventions. |
| `org.openrewrite.staticanalysis.CodeCleanup` | Not active; individually selected semantic guards coexist with Spotless-owned whitespace and import recipes. |
| `org.openrewrite.staticanalysis.CommonDeclarationSiteTypeVariances` | Not active; its sole positive child, `DeclarationSiteTypeVariance`, is individually rejected. |
| `org.openrewrite.staticanalysis.JavaApiBestPractices` | Not active; `UseMapContainsKey` is selected as a recurrence guard while its parent does not provide a stable leaf-only boundary. |
| `org.openrewrite.java.testing.cleanup.BestPractices` | Not active; `TestsShouldIncludeAssertions` misclassifies repository domain assertions. |
| `org.openrewrite.maven.ReproducibleBuilds` | Not active; the timestamp child fails the PMD gate and the plugin-version child violates version ownership. |
