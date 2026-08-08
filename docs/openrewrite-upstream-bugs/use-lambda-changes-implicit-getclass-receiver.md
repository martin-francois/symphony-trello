# `UseLambdaForFunctionalInterface` Changes the Receiver of Implicit `getClass()`

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/UseLambdaForFunctionalInterface.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/UseLambdaForFunctionalInterfaceTest.java`

The implementation and tests were byte-identical on upstream `main` at
`bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact duplicate or active
claim was found on 2026-07-19.

## Recipe and decision context

An anonymous class introduces a new `this`; a lambda uses the enclosing
lexical `this`. The recipe already rejects an explicit `this` identifier, but
an unqualified `getClass()` call has no explicit `this` token. Conversion
therefore changes which object's runtime class is returned.

This handoff is intentionally limited to the execution-proven implicit
`getClass()` defect. It does not claim broader anonymous-class identity or
serialization failures without separate executed fixtures.

## Reproduction

The transformation was executed in a pinned `RewriteTest`.

## Failure cases

### Implicit `getClass()` in the functional method

**Evidence status:** execution-proven pinned output. Before,
`supplier.get()` returns the anonymous implementation class; actual returns
`Test.class`.

**Before**

```java
import java.util.function.Supplier;

class Test {
    Supplier<Class<?>> supplier() {
        return new Supplier<Class<?>>() {
            @Override
            public Class<?> get() {
                return getClass();
            }
        };
    }
}
```

**Actual output**

```java
import java.util.function.Supplier;

class Test {
    Supplier<Class<?>> supplier() {
        return () -> getClass();
    }
}
```

**Expected output**

```java
import java.util.function.Supplier;

class Test {
    Supplier<Class<?>> supplier() {
        return new Supplier<Class<?>>() {
            @Override
            public Class<?> get() {
                return getClass();
            }
        };
    }
}
```

## Root cause

`shouldConvertToLambda` calls `usesThis`, whose nested visitor looks only for
a `J.Identifier` with simple name `this`. An implicit Object method call is a
`J.MethodInvocation` with no explicit receiver, so `getClass()` bypasses the
guard.

## Required fix contract

The fix MUST:

- skip anonymous-to-lambda conversion when the functional method contains an
  implicit call attributed to `java.lang.Object getClass()`;
- distinguish implicit `getClass()` from explicitly outer-qualified
  `Test.this.getClass()`, whose receiver already remains the outer instance;
- inspect nested expression positions, blocks, conditionals, arguments, and
  method references without crossing into unrelated nested class scopes;
- preserve the existing explicit-`this`, shadowing, statement-use,
  uninitialized-field, target-type, diamond, and overload guards;
- retain safe anonymous functional-interface conversions;
- leave missing method attribution unchanged; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

The displayed case is the only execution-proven failure in this handoff.
Treat the remaining items as unexecuted regression or negative-control
requirements until their tests are run:

1. the exact Supplier case with a runtime class assertion;
2. implicit `getClass()` in a block, return expression, conditional, lambda
   nested inside the method, and method argument;
3. explicit `this.getClass()` as a no-change control already covered by the
   broader `this` guard;
4. explicitly outer-qualified `Test.this.getClass()` as a safe-conversion
   control if attribution proves the receiver;
5. a different helper named `getClass` in malformed/unattributed input as
   conservative no change;
6. ordinary safe Runnable, Supplier, Consumer, and custom SAM positives;
7. compilation and in-memory runtime assertions; and
8. explicit first-cycle and second-cycle checks.

## Upstream contribution workflow

Search current issues and PRs for the recipe plus `getClass`, `implicit this`,
`anonymous class`, and `lambda receiver`. If the Good OSS helper is
unavailable, perform equivalent current policy, template, duplicate,
issue/PR, comment, and claim searches through GitHub tooling. Add the runtime
class test first and run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.UseLambdaForFunctionalInterfaceTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

A human MUST review scope boundaries and all existing lambda safety guards.

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

Symphony can reconsider the recipe when implicit `getClass()` retains the
anonymous receiver, safe conversions still apply, and the released recipe is
clean on a second ordered cycle.

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
