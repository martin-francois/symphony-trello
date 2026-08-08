# `AssertTrueInstanceofToAssertInstanceOf` Loses a Qualified Assertion Owner

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-testing-frameworks>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-testing-frameworks:3.42.0`
- Audited release: tag `v3.42.0`, commit
  `2b5d8526dc226ff4794716133b2d0780eb257530`
- Compared upstream `main`:
  `f0f55abf1414ab1f3dd44ab5fa2b2ffffcd24d65`
- Recipe:
  `org.openrewrite.java.testing.junit5.AssertTrueInstanceofToAssertInstanceOf`
- Implementation:
  `src/main/java/org/openrewrite/java/testing/junit5/AssertTrueInstanceofToAssertInstanceOf.java`
- Existing tests:
  `src/test/java/org/openrewrite/java/testing/junit5/AssertTrueInstanceofToAssertInstanceOfTest.java`

The implementation and existing test file were byte-identical at the audited
and compared commits. A contributor MUST repeat the current-main and
duplicate-claim checks before publishing upstream work.

## Recipe and decision context

Replacing `assertTrue(value instanceof Type)` with `assertInstanceOf` is a
useful JUnit migration. A qualified input establishes that JUnit owns the
assertion call. The replacement must not bind to an application method with
the same name.

The pinned recipe discards the qualified owner, emits an unqualified
`assertInstanceOf(...)` call, and adds a static import. Java resolves a
compatible same-class or inherited method before the static import, so the
generated test silently invokes application code instead of JUnit.

The previously alleged `Supplier<String>` failure is not a defect. JUnit has
a matching `assertInstanceOf(Class<T>, Object, Supplier<String>)` overload,
and the transformed supplier fixture compiles.

## Reproduction

Add the fixture below as a temporary
`AssertTrueInstanceofOwnerProbeTest` beside the existing upstream test. Run
the same command on tag `v3.42.0` and current upstream `main`:

```text
./gradlew test \
  --tests org.openrewrite.java.testing.junit5.AssertTrueInstanceofOwnerProbeTest \
  --no-daemon --configure-on-demand
```

The expected-output assertion passes on both revisions, proving that both
versions emit the wrong-owner call.

## Failure case

**Evidence status:** execution-proven on the pinned release and current
upstream `main` with normal Rewrite type validation. The actual output
compiles, but calling `test("value")` throws `AssertionError("wrong owner")`
instead of executing JUnit's assertion.

**Before**

```java
class Test {
    static void assertInstanceOf(Class<?> type, Object value) {
        throw new AssertionError("wrong owner");
    }

    void test(Object value) {
        org.junit.jupiter.api.Assertions.assertTrue(value instanceof String);
    }
}
```

**Actual output**

```java
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class Test {
    static void assertInstanceOf(Class<?> type, Object value) {
        throw new AssertionError("wrong owner");
    }

    void test(Object value) {
        assertInstanceOf(String.class, value);
    }
}
```

**Expected output**

```java
class Test {
    static void assertInstanceOf(Class<?> type, Object value) {
        throw new AssertionError("wrong owner");
    }

    void test(Object value) {
        org.junit.jupiter.api.Assertions.assertInstanceOf(String.class, value);
    }
}
```

## Root cause

The recipe's template contains only the unqualified call and schedules a
static import:

```java
JavaTemplate.builder("assertInstanceOf(...)")
    .staticImports("org.junit.jupiter.api.Assertions.assertInstanceOf");
```

It neither retains the original selector nor checks whether the generated
simple method name resolves to another member in the lexical scope.

## Required fix contract

For a qualified input, the fix MUST preserve an unambiguous reference to
`org.junit.jupiter.api.Assertions.assertInstanceOf`. It MUST:

- prevent compatible same-class, inherited, nested-type, and static-import
  declarations from capturing the generated call;
- preserve the no-message, `String`, and `Supplier<String>` JUnit 5 overloads;
- preserve the two supported JUnit 4-to-JUnit 5 forms;
- retain valid static-import behavior for an originally statically imported
  input;
- preserve generic and array `instanceof` target typing;
- emit correctly attributed, compiling source; and
- remain idempotent on a second cycle.

## Regression test plan

Move the exact fixture above into
`AssertTrueInstanceofToAssertInstanceOfTest`. Add:

1. compatible same-class and inherited methods that prove wrong binding;
2. incompatible same-named methods that prove the output still compiles;
3. qualified JUnit 5 no-message, `String`, and `Supplier<String>` inputs;
4. qualified JUnit 4 no-message and `String` inputs;
5. the corresponding static-import positive controls;
6. generic, nested, and array target types;
7. incomplete attribution as a no-change control; and
8. explicit second-cycle and byte-stability assertions.

Run:

```text
./gradlew test \
  --tests org.openrewrite.java.testing.junit5.AssertTrueInstanceofToAssertInstanceOfTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

## Upstream contribution workflow

Read the repository's current contribution guide and templates. Search open
and closed issues and pull requests for the exact recipe ID plus
`assertInstanceOf`, `qualified`, `static import`, and `shadow`. Check comments
and assignees for an existing claim.

Re-run the failure against current upstream `main`. Contribute one focused
fix with the failing regression committed before the implementation. A human
MUST review the implementation, test, public issue, pull-request text, and
generated output before publication.

AI-assisted work MUST disclose the assistance, for example:

> This change was prepared with AI assistance. I reviewed the implementation,
> test, generated before/after examples, and contribution text.

## Symphony acceptance criteria

Symphony can reactivate
`org.openrewrite.java.testing.junit5.AssertTrueInstanceofToAssertInstanceOf`
only after:

- a released artifact contains the owner-preservation fix;
- Symphony updates the affected artifact pin through its normal dependency
  process;
- the fixed leaf passes the exact failure fixture plus the positive and
  no-change controls;
- qualified generated calls cannot bind to application methods and every
  changed output compiles;
- the complete ordered `OpenRewrite -> Spotless` maintenance lane passes;
- the complete repository gate passes;
- a second complete ordered maintenance run leaves the worktree unchanged;
  and
- the decision record and selected defect tracker record the upstream issue,
  pull request, release, updated pin, and Symphony's result.
