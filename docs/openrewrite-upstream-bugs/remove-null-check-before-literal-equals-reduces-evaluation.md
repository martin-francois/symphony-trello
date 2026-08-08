# `RemoveRedundantNullCheckBeforeLiteralEquals` Reduces Evaluation of Impure Expressions

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/RemoveRedundantNullCheckBeforeLiteralEquals.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/RemoveRedundantNullCheckBeforeLiteralEqualsTest.java`

The implementation and tests were byte-identical on upstream `main` at
`bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact duplicate or active
claim was found on 2026-07-19.

## Recipe and decision context

`value != null && "literal".equals(value)` has a redundant null guard only
when both appearances of `value` are stable, repeat-safe evaluations.
Matching two trees with `SemanticallyEqual` does not establish that property.

Symphony keeps the recipe inactive because both direct and chained branches
can remove one invocation and change the compared value.

## Reproduction

Both visitor branches were executed in one pinned `RewriteTest`.

## Failure cases

### Direct and chained repeated method invocation

**Evidence status:** execution-proven pinned output. The before expression
invokes `next()` twice after a non-null first result; the actual invokes it
once.

**Before**

```java
class Test {
    String next() {
        return "";
    }

    boolean direct() {
        return next() != null && "ok".equals(next());
    }

    boolean chained(boolean enabled) {
        return enabled && next() != null && "ok".equals(next());
    }
}
```

**Actual output**

```java
class Test {
    String next() {
        return "";
    }

    boolean direct() {
        return "ok".equals(next());
    }

    boolean chained(boolean enabled) {
        return enabled && "ok".equals(next());
    }
}
```

**Expected output**

```java
class Test {
    String next() {
        return "";
    }

    boolean direct() {
        return next() != null && "ok".equals(next());
    }

    boolean chained(boolean enabled) {
        return enabled && next() != null && "ok".equals(next());
    }
}
```

## Root cause

The visitor correctly requires a String literal receiver and a matching
`String.equals(Object)` invocation. It then uses
`SemanticallyEqual.areEqual` to associate the null-checked tree with the
equals argument and removes the former. No repeatability or purity check is
performed.

## Required fix contract

The fix MUST:

- simplify only expressions proven stable and repeat-safe;
- preserve evaluation count, order, exceptions, volatile observations, and
  changing return values;
- cover direct and chained `&&` branches and null on either side;
- retain safe local-variable and parameter cases;
- conservatively retain calls, constructors, array access, mutable or
  volatile fields, casts, unboxing, division, assignment, and increments;
- keep the String-literal and method-type guards;
- preserve comments and parentheses; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

Only the displayed direct and chained failures are execution-proven. Treat the
additional matrix below as unexecuted regression and negative-control
requirements until run.

Add:

1. both exact cases above;
2. call counters, alternating values, and throwing invocations;
3. volatile fields, array access, unboxing, division, casts, assignments, and
   increments;
4. local and parameter positives;
5. null on either side and direct/chained nesting;
6. nonliteral receivers and non-String `equals` controls;
7. runtime assertions using only in-memory state;
8. missing attribution as no change; and
9. first-cycle and second-cycle assertions.

## Upstream contribution workflow

Search current issues and PRs for the recipe plus `side effect`,
`SemanticallyEqual`, `literal equals`, and `evaluation count`. If the Good OSS
helper is unavailable, perform equivalent current policy, template,
duplicate, issue/PR, comment, and claim searches through GitHub tooling.
Add failing tests first and run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.RemoveRedundantNullCheckBeforeLiteralEqualsTest \
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

Symphony can reconsider the recipe when only repeat-safe values simplify and
the released recipe preserves all impure evaluation and reaches a clean
second ordered cycle.

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
