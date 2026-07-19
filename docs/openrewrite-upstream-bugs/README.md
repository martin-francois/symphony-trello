# Reproduced OpenRewrite Upstream Bugs

This directory contains only defects that were reproduced against both the artifact pinned by
Symphony for Trello and the upstream default branch on 2026-07-20. Each packet contains a minimal
before/actual/expected case, the affected implementation, and a focused upstream regression-test
plan.

No dedicated tracking issue, upstream issue, or upstream pull request has been created for these
defects. Create or select an accurate tracker before starting upstream contribution work.

## Retention gate

A packet remains in this directory only when all of these checks pass:

1. The recipe produces the documented output in an executable fixture on the pinned source. Use a
   `RewriteTest` for source transformations and an isolated Maven project when the defect exists in
   Maven's effective model.
2. The same fixture produces the defect on current upstream `main`.
3. Compilation, Maven's effective model, Java evaluation rules, or a runtime probe demonstrates a
   concrete contract violation. A surprising or opinionated migration is not sufficient.
4. The case is not a duplicate of another retained root defect.
5. The packet contains no failure case that was skipped or only inferred from source inspection.

The correction removed 83 of the original 110 packets because they did not satisfy this complete
gate. Removal means the claim is not verified for upstream reporting; it does not assert that every
removed hypothesis is false. A future audit can restore a packet only with fresh executable evidence
against the pinned artifact and then-current upstream `main`.

## Tested revisions

| Component | Pinned revision | Current `main` tested |
| --- | --- | --- |
| OpenRewrite core, Java, and Maven | `2304703c678e9febf855d22adf18aeb32f44b7aa` | `b3008cc4a1f0c43f562da16e5933a2a56d9bc568` |
| Java migration recipes | `658481254a6ee678f5f162e51d8d49ee01c75877` | `9b7a874bfe860b22c9a103dadb1c55719fdfd39c` |
| Static-analysis recipes | `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12` | `bc8d038cfd4d533758674d57876ce7a1561baf18` |
| Testing recipes | `2b5d8526dc226ff4794716133b2d0780eb257530` | `f0f55abf1414ab1f3dd44ab5fa2b2ffffcd24d65` |

## Execution record

The audit ran the same retained transformation fixtures on the pinned and current revisions above:

| Upstream repository | Focused defect cases | Pinned result | Current-main result |
| --- | ---: | --- | --- |
| `rewrite` Java | 1 | 1 passed | 1 passed |
| `rewrite` Maven | 4 | 4 passed | 4 passed |
| `rewrite-migrate-java` | 18 | 18 passed | 18 passed |
| `rewrite-static-analysis` | 20 | 20 passed | 20 passed |
| `rewrite-testing-frameworks` | 8 | 8 passed | 8 passed |
| **Total** | **51** | **51 passed** | **51 passed** |

The source suites used temporary `RewriteTest` probe classes. The Maven cases also used isolated
Maven projects where an effective-POM comparison is the contract oracle. Additional `javac`, Java
runtime, and effective-POM checks establish the observable failures described in the packets. A
passing probe means the recipe produced the asserted defective output; it does not mean the output
is correct.

The audit deliberately excluded four invalid evidence paths: one recipe did not transform its
fixture, one sort case did not transform, one Maven fixture failed dependency resolution, and one
security-manager test was disabled because its removed JDK symbols were unavailable.

## Reproduced defects

### `openrewrite/rewrite`

- [`ChangeMethodTargetToStatic` drops receiver evaluation](change-method-target-to-static-drops-receiver-evaluation.md)
- [A versionless dependency-management entry can have Maven semantics](dependency-management-versionless-entry-has-semantics.md)
- [`RemoveDuplicateDependencies` keeps the Maven-losing declaration](remove-duplicate-dependencies-keeps-maven-losing-declaration.md)
- [`SimplifyBooleanExpressionVisitor` drops required evaluation](simplify-boolean-expression-visitor-drops-required-evaluation.md)

### `openrewrite/rewrite-migrate-java`

- [`ArrayStoreExceptionToTypeNotPresentException` loses catch semantics](array-store-exception-replacement-loses-catch-semantics.md)
- [Micrometer-to-JSpecify migration uses the wrong dependency guard](migrate-from-micrometer-annotations-wrong-dependency-guard.md)
- [`MigrateSecurityManagerMulticast` drops argument evaluation](migrate-security-manager-multicast-drops-argument-evaluation.md)
- [`RenameUnderscoreIdentifier` creates declaration collisions](rename-underscore-identifier-creates-declaration-collisions.md)
- [`UseJavaUtilBase64` leaves calls that do not compile](use-java-util-base64-breaks-legacy-contracts.md)

### `openrewrite/rewrite-static-analysis`

- [Array instance recipes ignore an in-scope `Arrays` type](array-instance-recipes-ignore-arrays-simple-name-collisions.md)
- [`NullableOnMethodReturnType` moves declaration-only annotations](nullable-on-method-return-type-moves-declaration-only-annotation.md)
- [`ObjectFinalizeCallsSuper` adds an uncaught `Throwable`](object-finalize-calls-super-adds-uncaught-throwable.md)
- [`PrimitiveWrapperClassConstructorToValueOf` changes `Float` bits](primitive-wrapper-float-double-literal-double-rounding.md)
- [`RemoveMethodsOnlyCallSuper` drops method contracts](remove-methods-only-call-super-drops-method-contracts.md)
- [`RemoveRedundantNullCheckBeforeInstanceof` reduces evaluation](remove-null-check-before-instanceof-reduces-evaluation.md)
- [`RemoveRedundantNullCheckBeforeLiteralEquals` reduces evaluation](remove-null-check-before-literal-equals-reduces-evaluation.md)
- [`RemoveUnusedLabels` drops a statement-prefix comment](remove-unused-labels-drops-statement-prefix-comments.md)
- [`RenameMethodsNamedHashcodeEqualOrToString` creates duplicate declarations](rename-object-near-miss-methods-creates-duplicate-declarations.md)
- [`ReplaceDeprecatedRuntimeExecMethods` changes tokenization](replace-runtime-exec-string-changes-whitespace-tokenization.md)
- [`ReplaceStringConcatenationWithStringValueOf` changes `char[]` rendering](replace-empty-string-concatenation-changes-char-array-rendering.md)
- [`ReplaceStringBuilderWithString` changes `char[]` rendering](replace-string-builder-with-string-changes-char-array-rendering.md)
- [`UnnecessaryCloseInTryWithResources` changes resource lifetime](unnecessary-close-in-try-with-resources-changes-resource-lifetime.md)
- [`UnwrapElseAfterReturn` creates local-variable collisions](unwrap-else-after-return-creates-local-variable-collisions.md)
- [`UseLambdaForFunctionalInterface` changes the receiver of implicit `getClass()`](use-lambda-changes-implicit-getclass-receiver.md)

### `openrewrite/rewrite-testing-frameworks`

- [`AddMissingNested` breaks pre-Java-16 static members](add-missing-nested-breaks-pre-java16-static-members.md)
- [`AssertTrueInstanceofToAssertInstanceOf` loses a qualified assertion owner](assert-true-instanceof-loses-qualified-owner.md)
- [`MockUtilsToStatic` deletes still-used declarations](mock-utils-to-static-deletes-still-used-declarations.md)

## Contribution protocol

Before preparing an upstream fix, a contributor MUST rerun the packet against the latest default
branch and search current issues and pull requests for duplicates. The fix MUST add the smallest
failing upstream test first, preserve each stated no-change boundary, compile generated source when
applicable, and pass the owning module's focused and full test gates.

After an upstream fix is released, Symphony for Trello MUST rerun the fixed leaf, the complete
`OpenRewrite -> Spotless` maintenance lane, the repository verification gate, and a second
idempotence run before activating the recipe.
