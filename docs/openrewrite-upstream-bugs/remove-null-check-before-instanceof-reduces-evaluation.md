# `RemoveRedundantNullCheckBeforeInstanceof` Reduces Evaluation of Impure Expressions

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/RemoveRedundantNullCheckBeforeInstanceof.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/RemoveRedundantNullCheckBeforeInstanceofTest.java`

The implementation and tests were byte-identical on upstream `main` at
`bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact duplicate or active
claim was found on 2026-07-19.

## Recipe and decision context

For one stable value, `value != null && value instanceof Type` is equivalent
to `value instanceof Type`. Syntactic or semantic tree equality does not prove
that two evaluations yield the same value or have no effects.

Symphony keeps the recipe inactive because both its direct and chained
branches can reduce two method invocations to one.

## Reproduction

The direct and chained transformations were executed together in a pinned
`RewriteTest`.

## Failure cases

### Direct and chained repeated method invocation

**Evidence status:** execution-proven pinned output for both visitor branches.
The before expression invokes `next()` twice when its first result is non-null;
the actual expression invokes it once.

**Before**

```java
class Test {
    Object next() {
        return "";
    }

    boolean direct() {
        return next() != null && next() instanceof String;
    }

    boolean chained(boolean enabled) {
        return enabled && next() != null && next() instanceof String;
    }
}
```

**Actual output**

```java
class Test {
    Object next() {
        return "";
    }

    boolean direct() {
        return next() instanceof String;
    }

    boolean chained(boolean enabled) {
        return enabled && next() instanceof String;
    }
}
```

**Expected output**

```java
class Test {
    Object next() {
        return "";
    }

    boolean direct() {
        return next() != null && next() instanceof String;
    }

    boolean chained(boolean enabled) {
        return enabled && next() != null && next() instanceof String;
    }
}
```

## Root cause

Both branches call `SemanticallyEqual.areEqual` on the null-check expression
and the `instanceof` expression, then delete the null-check tree.
`SemanticallyEqual` proves structural meaning for matching, not repeatable,
side-effect-free evaluation.

## Required fix contract

The fix MUST:

- simplify only expressions proven stable and repeat-safe;
- preserve evaluation count, order, thrown exceptions, volatile reads, and
  returned-value changes;
- cover both direct and chained `&&` shapes;
- handle null on either side of `!=`;
- retain safe local-variable and parameter positives;
- conservatively retain method calls, constructors, array access, mutable or
  volatile field access, unboxing, division, casts, and assignments unless a
  shared purity abstraction proves safety;
- preserve comments and parentheses; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

Only the displayed direct and chained failures are execution-proven. Treat the
additional matrix below as unexecuted regression and negative-control
requirements until run.

Add:

1. the direct and chained cases above;
2. counters and alternating return values proving call count and result;
3. throwing calls, array access, field access, volatile reads, unboxing,
   division, casts, assignments, and increments;
4. local variables and parameters as positive controls;
5. null on both sides of `!=`;
6. nested/chained conditions with comments and parentheses;
7. runtime tests using only in-memory state;
8. missing attribution as a no-change case; and
9. explicit second-cycle and byte-stability assertions.

## Upstream contribution workflow

Search current issues and PRs for the recipe plus `side effect`,
`SemanticallyEqual`, `evaluation count`, and `instanceof`. If Symphony's Good
OSS helper is unavailable, perform equivalent current policy, template,
duplicate, issue/PR, comment, and claim searches through GitHub tooling.
Add the failing cases before changing the shared safety predicate, then run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.RemoveRedundantNullCheckBeforeInstanceofTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

A human MUST review the repeat-safety predicate and complete evaluation-order
matrix.

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

Symphony can reconsider the recipe when only repeat-safe expressions
simplify, every impure family remains behaviorally identical, and a released
version is clean on a second ordered cycle.

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
