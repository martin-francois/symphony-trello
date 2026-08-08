# `UnwrapElseAfterReturn` Can Create Local Variable Collisions

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/UnwrapElseAfterReturn.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/UnwrapElseAfterReturnTest.java`

The implementation and tests were byte-identical on upstream `main` at
`bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact duplicate or active
claim was found on 2026-07-19.

## Recipe and decision context

Removing `else` after a terminating branch normally reduces nesting. Variables
inside the old else block have a smaller scope than variables later in the
enclosing block. Flattening those statements can make two previously
non-overlapping declarations collide.

Symphony keeps the recipe inactive because both its plain-else and innermost
else-if branches can emit uncompilable Java.

## Reproduction

Both flattening branches were executed in one pinned `RewriteTest`.

## Failure cases

### Plain else and else-if chain

**Evidence status:** execution-proven pinned output. Both actual methods
contain two `int value` declarations in one block and fail compilation.

**Before**

```java
class Test {
    void plain(boolean stop) {
        if (stop) {
            return;
        } else {
            int value = 1;
            System.out.println(value);
        }
        int value = 2;
        System.out.println(value);
    }

    void chain(boolean first, boolean second) {
        if (first) {
            return;
        } else if (second) {
            return;
        } else {
            int value = 1;
            System.out.println(value);
        }
        int value = 2;
        System.out.println(value);
    }
}
```

**Actual output**

```java
class Test {
    void plain(boolean stop) {
        if (stop) {
            return;
        }
        int value = 1;
        System.out.println(value);
        int value = 2;
        System.out.println(value);
    }

    void chain(boolean first, boolean second) {
        if (first) {
            return;
        } else if (second) {
            return;
        }
        int value = 1;
        System.out.println(value);
        int value = 2;
        System.out.println(value);
    }
}
```

**Expected output**

The before source MAY remain unchanged. A scope-preserving alternative is to
remove `else` but retain a standalone block:

```java
class Test {
    void plain(boolean stop) {
        if (stop) {
            return;
        }
        {
            int value = 1;
            System.out.println(value);
        }
        int value = 2;
        System.out.println(value);
    }
}
```

The else-if case needs the same scope preservation after its chain.

## Root cause

`flatten` concatenates the old else block's statements directly into the
parent block. The visitor preserves comments and end whitespace but never
compares declarations from the removed scope with later declarations,
pattern variables, catch variables, or other names in the destination scope.

## Required fix contract

The fix MUST:

- preserve Java name scopes for every hoisted statement;
- cover plain else and innermost else-if-chain branches;
- detect collisions with later locals, pattern variables, resource names,
  catch variables, lambda parameters, and local types where relevant;
- retain a standalone block or skip transformation when flattening is unsafe;
- preserve execution order, termination behavior, comments, and whitespace;
- retain safe no-collision flattening;
- leave incomplete attribution or scope analysis unchanged; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

Only the displayed plain and chained failures are execution-proven. Treat the
additional matrix below as unexecuted regression and negative-control
requirements until run.

Add:

1. both exact methods above;
2. collisions with locals, local classes, patterns, resources, and nested
   scopes;
3. multiple declarations and same names with different types;
4. plain return, throw, block return/throw, and else-if chains;
5. safe no-collision positive cases;
6. comments on `else`, block boundaries, and first/last statements;
7. compilation of every positive output;
8. runtime selected/unselected branch assertions using in-memory counters;
   and
9. explicit second-cycle and no-change byte-stability tests.

## Upstream contribution workflow

Search current issues and PRs for the recipe plus `duplicate variable`,
`scope`, `else if`, and `flatten`. If the Good OSS helper is unavailable,
perform equivalent current policy, template, duplicate, issue/PR, comment,
and claim searches through GitHub tooling. Add both branch failures first and
run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.UnwrapElseAfterReturnTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

A human MUST review Java scope analysis and every flattening branch.

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

Symphony can reconsider the recipe when scope preservation is exhaustive,
safe nesting reductions remain, every output compiles, and the released
recipe is clean on a second ordered cycle.

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
