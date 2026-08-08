# `NullableOnMethodReturnType` Moves Declaration-Only Annotations to an Array Type

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/NullableOnMethodReturnType.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/NullableOnMethodReturnTypeTest.java`

The implementation and relevant tests were byte-identical on upstream `main`
at `bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact open or closed issue
or pull request was found on 2026-07-19.

## Recipe and decision context

The recipe finds any annotation whose fully qualified name ends in
`Nullable`, removes it from a non-package-private method's leading
annotations, and inserts it into the return type. That is appropriate for
JSpecify's type-use `@Nullable`, but the name matcher does not prove that the
matched annotation supports `ElementType.TYPE_USE`.

Symphony keeps this recipe inactive because a legal method-only annotation is
moved onto an array dimension, producing invalid Java.

## Reproduction

The complete transformation below was executed in an isolated `RewriteTest`
against `2.39.0`. The annotation definition is a second source in the same
test and MUST be retained in the upstream regression.

## Evidence records

### Declaration-only `@Nullable` on an array-returning method

**Evidence status:** execution-proven pinned output; the annotation supports
`METHOD` but not `TYPE_USE`.

**Before**

```java
package example;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
public @interface Nullable {
}
```

```java
package example;

class Test {
    @Nullable
    public String[] value() {
        return null;
    }
}
```

**Actual output**

```java
package example;

class Test {
    public String @Nullable[] value() {
        return null;
    }
}
```

The output fails compilation because a method-only annotation is no longer in
a declaration-annotation position.

**Expected output**

```java
package example;

class Test {
    @Nullable
    public String[] value() {
        return null;
    }
}
```

## Root cause

`Annotated.Matcher("*..Nullable")` matches by name. The visitor checks only
the annotation's cursor location and method visibility. It never inspects the
annotation declaration's `@Target`, so the array-specific branch always
attaches the annotation to `J.ArrayType#getAnnotations`.

## Required fix contract

The fix MUST:

- move only annotations proven applicable to `TYPE_USE`;
- leave method-only and unknown-target `Nullable` annotations unchanged;
- handle `@Target` values expressed as one constant or an array;
- preserve the existing JSpecify scalar, array, primitive-array, nested-type,
  and multidimensional-array transformations;
- behave conservatively when annotation type attribution or metadata is
  missing;
- preserve imports, comments, prefixes, and method modifiers; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

Only the displayed failure is execution-proven. Treat the additional matrix
below as unexecuted regression and negative-control requirements until run.

Add tests for:

1. the exact two-source method-only array case above;
2. method-only scalar and multidimensional-array returns;
3. `TYPE_USE` only, `METHOD` only, both targets, no `@Target`, and unresolved
   annotation declarations;
4. JSpecify `Nullable` on scalar, nested, primitive-array, object-array, and
   multidimensional-array returns;
5. package-private, constructor, void, parameter, and field controls;
6. compilation of every changed output and the invalid-output regression;
7. a second recipe cycle for every positive case; and
8. byte stability across two cycles for every conservative no-change case.

## Upstream contribution workflow

Before coding, search current issues, pull requests, comments, and claims for
the recipe ID plus `Nullable`, `TYPE_USE`, `Target`, and `array`. Re-run the
fixture on current `main`, add the failing test first, and run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.NullableOnMethodReturnTypeTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

If Symphony's Good OSS helper is unavailable, perform the same current
policy, template, duplicate, issue/PR, comment, and claim searches through
the GitHub UI or equivalent tooling. A human MUST review the target-analysis
logic.

AI-assisted work MUST include:

> This change was prepared with AI assistance. I reviewed the implementation,
> tests, generated before/after examples, and contribution text.

### Mandatory upstream contribution checklist

Before preparing public upstream work, the contributor MUST:

1. Read the current contributing and automation instructions for the target repository.
2. Search current open and closed issues and pull requests for every exact recipe ID and every
   failure mechanism documented in this packet, and confirm that no contributor has claimed the
   same work.
3. Reproduce every listed failure case and negative control against the current upstream default
   branch. If that branch already fixes a case, record the fixing commit and remove the case from
   the proposed patch.
4. Add the smallest failing `RewriteTest` cases before changing the implementation.
5. Run the focused commands documented in this packet and the full gate for the owning repository.
6. Obtain human review of the implementation, tests, public issue, and pull-request text before
   publication.

## Symphony acceptance criteria

Symphony can reconsider the recipe after declaration-only and unknown-target
annotations stay unchanged, supported type-use annotations still move
correctly, all outputs compile, and a released version is clean on a second
ordered maintenance cycle.

### Mandatory Symphony reactivation checklist

After upstream releases the fix, Symphony MUST:

1. Update the pinned artifact through the normal dependency process for the repository.
2. Run the fixed leaf alone against every positive and negative control in this packet.
3. Run the complete ordered `OpenRewrite -> Spotless` maintenance lane.
4. Run the complete repository gate.
5. Run the complete ordered maintenance lane a second time and confirm that the worktree remains
   unchanged.
6. Update the recipe decision record and the selected defect tracker with the upstream issue, pull request, release,
   and local outcome.

Parent composites MUST remain independently reviewed. Fixing one leaf MUST NOT activate unreviewed
siblings.
