# `SimplifyBooleanExpressionVisitor` Drops Required Evaluation

## Upstream targets

### Core implementation

- Repository: <https://github.com/openrewrite/rewrite>
- Module: `rewrite-java` (implementation)
- Test module: `rewrite-java-test`
- Artifact: `org.openrewrite:rewrite-java:8.87.0`
- Audited source: tag `v8.87.0`, commit
  `2304703c678e9febf855d22adf18aeb32f44b7aa`
- Implementation path relative to `rewrite-java`:
  `src/main/java/org/openrewrite/java/cleanup/SimplifyBooleanExpressionVisitor.java`
- Existing repository-relative test path:
  `rewrite-java-test/src/test/java/org/openrewrite/java/cleanup/SimplifyBooleanExpressionVisitorTest.java`

The root visitor was byte-identical on `openrewrite/rewrite` `main` at
`6fe43db184cb1e38777d8e0ec3c981e94cb0b6ce`.

### Static-analysis consumers

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementations:
  - `src/main/java/org/openrewrite/staticanalysis/SimplifyBooleanExpression.java`
  - `src/main/java/org/openrewrite/staticanalysis/SimplifyConstantIfBranchExecution.java`
- Tests:
  - `src/test/java/org/openrewrite/staticanalysis/SimplifyBooleanExpressionTest.java`
  - `src/test/java/org/openrewrite/staticanalysis/SimplifyConstantIfBranchExecutionTest.java`
- Exact recipe IDs:
  - `org.openrewrite.staticanalysis.SimplifyBooleanExpression`
  - `org.openrewrite.staticanalysis.SimplifyConstantIfBranchExecution`

### Java-migration consumer

- Repository: <https://github.com/openrewrite/rewrite-migrate-java>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-migrate-java:3.40.0`
- Audited source commit:
  `658481254a6ee678f5f162e51d8d49ee01c75877`
- Configuration:
  `src/main/resources/META-INF/rewrite/java-version-25.yml`
- Current broad test:
  `src/test/java/org/openrewrite/java/migrate/UpgradeToJava25Test.java`
- Proposed focused test:
  `src/test/java/org/openrewrite/java/migrate/SystemGetSecurityManagerToNullTest.java`
- Exact recipe ID:
  `org.openrewrite.java.migrate.SystemGetSecurityManagerToNull`

Those consumer definitions were byte-identical on their upstream `main`
commits `bc8d038cfd4d533758674d57876ce7a1561baf18` and
`a5b7ddcf37df2aaa9c71799cabe387eb7fb9dd20`. No exact duplicate or active
claim was found on 2026-07-19.

## Recipe and decision context

Boolean identities preserve only the final boolean value. Replacing
`effect() && false` with `false`, `effect() || true` with `true`, or two
structurally equal evaluations with one value also removes evaluation,
exceptions, volatile observations, or changing results.

This root visitor is exposed directly and through branch-removal and
Java-migration wrappers. Symphony keeps every affected consumer inactive
until the core visitor is evaluation-safe.

## Reproduction

The direct static recipe, constant-if wrapper, and Java 25 declarative
migration were each executed against their pinned artifacts. All displayed
outputs are execution-proven.

## Failure cases

### 1. Direct simplification branches

**Evidence status:** execution-proven pinned output for right-dominating
`&&`/`||`, structurally equal field access, and equal String method-call
branches.

**Before**

```java
class Test {
    static class State {
        boolean value;
    }

    boolean effect() {
        return true;
    }

    State next() {
        return new State();
    }

    String nextString() {
        return "";
    }

    boolean andFalse() {
        return effect() && false;
    }

    boolean orTrue() {
        return effect() || true;
    }

    boolean repeatedField() {
        return next().value && next().value;
    }

    boolean repeatedEquals() {
        return nextString().equals(nextString());
    }
}
```

**Actual output**

```java
class Test {
    static class State {
        boolean value;
    }

    boolean effect() {
        return true;
    }

    State next() {
        return new State();
    }

    String nextString() {
        return "";
    }

    boolean andFalse() {
        return false;
    }

    boolean orTrue() {
        return true;
    }

    boolean repeatedField() {
        return next().value;
    }

    boolean repeatedEquals() {
        return true;
    }
}
```

**Expected output**

```java
class Test {
    static class State {
        boolean value;
    }

    boolean effect() {
        return true;
    }

    State next() {
        return new State();
    }

    String nextString() {
        return "";
    }

    boolean andFalse() {
        return effect() && false;
    }

    boolean orTrue() {
        return effect() || true;
    }

    boolean repeatedField() {
        return next().value && next().value;
    }

    boolean repeatedEquals() {
        return nextString().equals(nextString());
    }
}
```

The source remains unchanged because each candidate contains
repeat-sensitive evaluation.

### 2. `SimplifyConstantIfBranchExecution`

**Evidence status:** execution-proven pinned wrapper output. Branch selection
is correct, but evaluating `effect()` is removed.

**Before**

```java
class Test {
    boolean effect() {
        return true;
    }

    void andFalse() {
        if (effect() && false) {
            System.out.println("then");
        } else {
            System.out.println("else");
        }
    }

    void orTrue() {
        if (effect() || true) {
            System.out.println("then");
        } else {
            System.out.println("else");
        }
    }
}
```

**Actual output**

```java
class Test {
    boolean effect() {
        return true;
    }

    void andFalse() {
        System.out.println("else");
    }

    void orTrue() {
        System.out.println("then");
    }
}
```

**Expected output**

```java
class Test {
    boolean effect() {
        return true;
    }

    void andFalse() {
        if (effect() && false) {
            System.out.println("then");
        } else {
            System.out.println("else");
        }
    }

    void orTrue() {
        if (effect() || true) {
            System.out.println("then");
        } else {
            System.out.println("else");
        }
    }
}
```

The source remains unchanged until the condition visitor proves that
discarded evaluation is unobservable.

### 3. `SystemGetSecurityManagerToNull`

**Evidence status:** execution-proven pinned `rewrite-migrate-java` composite
output with a Java 25 marker. Replacing the removed API with null makes the
condition constant, after which the shared visitor also deletes `effect()`.

**Before**

```java
class Test {
    boolean effect() {
        System.out.println("effect");
        return true;
    }

    void run() {
        if (effect() && System.getSecurityManager() != null) {
            System.out.println("manager");
        } else {
            System.out.println("none");
        }
    }
}
```

**Actual output**

```java
class Test {
    boolean effect() {
        System.out.println("effect");
        return true;
    }

    void run() {
        System.out.println("none");
    }
}
```

**Expected output**

One behavior-preserving migration is:

```java
class Test {
    boolean effect() {
        System.out.println("effect");
        return true;
    }

    void run() {
        effect();
        System.out.println("none");
    }
}
```

Leaving the simplified condition as `effect() && false` until a separate
side-effect extraction is available is also correct.

## Root cause

The core visitor applies:

- `left && false -> false`;
- `left || true -> true`;
- `left && left -> left` and `left || left -> left` when the left tree is not
  itself a `MethodCall`; and
- `stringExpression.equals(sameExpression) -> true`.

The direct checks distinguish tree shapes but do not prove purity,
repeatability, stability, or no-throw behavior. Static-analysis wrappers reuse
the visitor directly; the migration wrapper reaches it through
`SimplifyConstantIfBranchExecution`.

## Required fix contract

The core fix MUST:

- preserve every evaluation that can mutate, throw, block, synchronize,
  perform I/O, read volatile state, or return a changing value;
- distinguish left- and right-dominating short-circuit identities;
- collapse repeated expressions only when both stability and removal of the
  second evaluation are proven safe;
- treat method calls nested in field access, array access, casts, and
  parentheses as impure;
- preserve safe literal and local primitive identities;
- expose or reuse one conservative repeat/purity predicate rather than
  wrapper-specific allowlists;
- preserve comments and prefixes when retaining/extracting evaluation;
- make every direct and wrapper recipe idempotent: a second cycle MUST
  produce no change; and
- add propagation tests in every known consumer repository.

## Regression test plan

Only the displayed direct and wrapper branches are execution-proven failures.
Treat the additional matrices below as unexecuted regression and
negative-control requirements until run.

In `openrewrite/rewrite`, cover:

1. `effect() && false` and `effect() || true`;
2. left literals whose right sides are short-circuited and therefore safely
   removable;
3. method calls nested under fields, arrays, casts, unboxing, division, and
   parentheses;
4. volatile and mutable fields;
5. repeated locals versus repeated calls and changing values;
6. String `equals` with method-call receiver/argument;
7. exceptions, synchronization, and evaluation order;
8. comments and incomplete attribution; and
9. a second visitor cycle.

In `rewrite-static-analysis`, add both complete direct fixtures and both
constant-if branches. In `rewrite-migrate-java`, add the Java 25 wrapper case.
Runtime tests MUST avoid external services, files, network access, and
nondeterministic host state. They MAY use deterministic in-memory side
effects, counters, latches, captured output, and exception assertions to prove
evaluation count, ordering, synchronization, and failure behavior.

Targeted commands:

```text
./gradlew :rewrite-java:test \
  --tests org.openrewrite.java.cleanup.SimplifyBooleanExpressionVisitorTest
./gradlew test \
  --tests org.openrewrite.staticanalysis.SimplifyBooleanExpressionTest \
  --tests org.openrewrite.staticanalysis.SimplifyConstantIfBranchExecutionTest
./gradlew test \
  --tests org.openrewrite.java.migrate.SystemGetSecurityManagerToNullTest
```

Run each repository's full `./gradlew check` after its focused tests.

## Upstream contribution workflow

Start in `openrewrite/rewrite`, because wrapper-only guards leave other
consumers unsafe. Search current issues and PRs across all three repositories
for `SimplifyBooleanExpressionVisitor`, `side effect`, `evaluation`, and each
wrapper ID. Inspect comments and claims. If the Good OSS helper is unavailable,
perform equivalent current policy, template, duplicate, issue/PR, comment,
and claim searches through GitHub tooling.

Coordinate the core release before opening propagation PRs. A human MUST
review the full operator matrix and consumer version alignment.

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

Symphony can reconsider the three consumers only after the core fix is
released, wrapper tests prove evaluation preservation, no vulnerable bundled
core remains, and every recipe reaches a clean second ordered cycle.

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
