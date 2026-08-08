# `MockUtilsToStatic` Deletes Still-Used Declarations

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-testing-frameworks>
- Module: repository root recipe module
- Artifact:
  `org.openrewrite.recipe:rewrite-testing-frameworks:3.42.0`
- Audited commit: `2b5d8526dc226ff4794716133b2d0780eb257530`
- Compared upstream `main`: `224c5ebb42ea181c2579f7047e4cd3130a29669f`
- Recipe: `org.openrewrite.java.testing.mockito.MockUtilsToStatic`
- Implementation:
  `src/main/java/org/openrewrite/java/testing/mockito/MockUtilsToStatic.java`
- Existing tests:
  `src/test/java/org/openrewrite/java/testing/mockito/MockUtilsToStaticTest.java`
- Composite configuration:
  `src/main/resources/META-INF/rewrite/mockito.yml`

The implementation and existing test file were byte-identical at the audited
and compared commits. A contributor MUST repeat current-main and
duplicate-claim checks before publishing upstream work.

## Recipe and decision context

Converting supported Mockito `MockUtil` instance calls to their static form is
a useful migration. Deleting the instance declaration is safe only after every
use of that declaration has either been migrated or proven unnecessary.

The pinned recipe deletes the enclosing declaration as soon as it finds
`new MockUtil()`. It neither proves that the variable has no other uses nor
limits deletion to one declarator. Symphony keeps the recipe inactive because
the proven outputs contain undefined names and can suppress sibling
initializer evaluation.

## Reproduction

Add the three fixtures below to a temporary
`MockUtilsToStaticUseProbeTest` beside the existing upstream test. Run:

```text
./gradlew test \
  --tests '*MockUtilsToStaticUseProbeTest' \
  --no-daemon --configure-on-demand
```

All three pinned-output assertions pass. Compile their aggregate actual output
against a minimal `org.mockito.internal.util.MockUtil` stub:

```text
javac --release 11 \
  org/mockito/internal/util/MockUtil.java \
  MockUtilsActual.java
```

`javac` reports unresolved `util` in the local and field cases and unresolved
`observed` in the multi-declarator case.

## Failure cases

### Local variable remains an argument

**Evidence status:** execution-proven pinned output; the actual output does not
compile because `util` remains an argument after its declaration is deleted.

**Before**

```java
import org.mockito.internal.util.MockUtil;

class Test {
    boolean test(Object value) {
        MockUtil util = new MockUtil();
        observe(util);
        return util.isMock(value);
    }

    void observe(Object value) {
    }
}
```

**Actual output**

```java
import org.mockito.internal.util.MockUtil;

class Test {
    boolean test(Object value) {
        observe(util);
        return MockUtil.isMock(value);
    }

    void observe(Object value) {
    }
}
```

**Expected output**

```java
import org.mockito.internal.util.MockUtil;

class Test {
    boolean test(Object value) {
        MockUtil util = new MockUtil();
        observe(util);
        return MockUtil.isMock(value);
    }

    void observe(Object value) {
    }
}
```

The declaration MUST remain because the argument use has no static
`MockUtil` equivalent.

### Field remains a return value

**Evidence status:** execution-proven pinned output; the actual output does not
compile because `expose` still returns the deleted field.

**Before**

```java
import org.mockito.internal.util.MockUtil;

class Test {
    private MockUtil util = new MockUtil();

    MockUtil expose() {
        return util;
    }

    boolean test(Object value) {
        return util.isMock(value);
    }
}
```

**Actual output**

```java
import org.mockito.internal.util.MockUtil;

class Test {

    MockUtil expose() {
        return util;
    }

    boolean test(Object value) {
        return MockUtil.isMock(value);
    }
}
```

**Expected output**

```java
import org.mockito.internal.util.MockUtil;

class Test {
    private MockUtil util = new MockUtil();

    MockUtil expose() {
        return util;
    }

    boolean test(Object value) {
        return MockUtil.isMock(value);
    }
}
```

The supported method invocation can still become static, but that does not
make the field's independent return use obsolete.

### Multi-declarator deletion removes a sibling and its evaluation

**Evidence status:** execution-proven pinned output; the actual output does not
compile because `observed` is undefined. It also removes the call to
`createObserved()`.

**Before**

```java
import org.mockito.internal.util.MockUtil;

class Test {
    boolean test(Object value) {
        MockUtil util = new MockUtil(), observed = createObserved();
        observe(observed);
        return util.isMock(value);
    }

    MockUtil createObserved() {
        return new MockUtil();
    }

    void observe(Object value) {
    }
}
```

**Actual output**

```java
import org.mockito.internal.util.MockUtil;

class Test {
    boolean test(Object value) {
        observe(observed);
        return MockUtil.isMock(value);
    }

    MockUtil createObserved() {
        return new MockUtil();
    }

    void observe(Object value) {
    }
}
```

**Expected output**

```java
import org.mockito.internal.util.MockUtil;

class Test {
    boolean test(Object value) {
        MockUtil observed = createObserved();
        observe(observed);
        return MockUtil.isMock(value);
    }

    MockUtil createObserved() {
        return new MockUtil();
    }

    void observe(Object value) {
    }
}
```

The obsolete `util` declarator can be removed after its only invocation is
migrated. The sibling declarator and its initializer MUST remain in their
original evaluation position.

## Root cause

The pinned visitor schedules deletion for the enclosing declaration as soon as
it finds a matching initializer:

```java
if (namedVar instanceof J.VariableDeclarations) {
    doAfterVisit(new DeleteStatement<>((J.VariableDeclarations) namedVar));
}
```

When the visitor finds `new MockUtil()` as a named-variable initializer, it
walks to the enclosing `J.VariableDeclarations` and schedules
`DeleteStatement` for that entire declaration. It performs no reference
analysis. The later method-target visitor rewrites recognized `MockUtil`
method calls, but arguments, returns, assignments, comparisons, aliases, and
other sinks remain. For a declaration containing multiple variables, deleting
the statement also deletes every sibling and initializer.

## Required fix contract

The fix MUST:

- build a complete use plan for each `MockUtil` declaration before deletion;
- rewrite supported method invocations without assuming that all other uses
  disappear;
- retain the declaration when any unsupported use remains;
- remove only the proven-obsolete declarator from a multi-variable
  declaration and preserve every sibling's type, comments, order, and
  initializer evaluation;
- handle fields and local variables, including static and instance scopes,
  separately;
- preserve assignments, aliases, arguments, returns, comparisons, casts,
  member references, synchronization, and other non-call sinks;
- leave source unchanged when type or symbol attribution is incomplete;
- emit compilable output; and
- remain idempotent on a second cycle.

The fix MUST NOT delete an entire `J.VariableDeclarations` merely because one
initializer constructs `MockUtil`.

`MockUtilsToStatic` delegates call rewriting to
`org.openrewrite.java.ChangeMethodTargetToStatic`. The receiver-evaluation
repair in
[`change-method-target-to-static-drops-receiver-evaluation.md`](change-method-target-to-static-drops-receiver-evaluation.md)
is therefore a prerequisite for reactivating this recipe, independently of
the declaration-deletion fix.

## Regression test plan

Move the three exact fixtures above into `MockUtilsToStaticTest`. Add:

1. arguments, returns, field reads, assignments, comparisons, casts, arrays,
   lambdas, method references, and aliases as retained-use cases;
2. local, instance-field, and static-field declarations;
3. first, middle, and last `MockUtil` declarators with evaluated siblings;
4. comments and annotations attached to both removed and retained declarators;
5. a variable used exclusively by supported instance calls as a positive
   deletion case;
6. direct `new MockUtil().isMock(value)` and already-static calls as positive
   and no-change controls;
7. unrelated constructors, similarly named types, subclasses, and incomplete
   attribution as no-change controls;
8. counter-based or throwing sibling initializers proving evaluation is
   retained;
9. cross-regressions for side-effectful and throwing call qualifiers against
   the fixed `ChangeMethodTargetToStatic`;
10. compilation of every changed output; and
11. explicit second-cycle and byte-stability assertions.

Run:

```text
./gradlew test \
  --tests org.openrewrite.java.testing.mockito.MockUtilsToStaticTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

The tests MUST assert forbidden deletion of declarations with live uses and
forbidden suppression of sibling initializer evaluation.

## Upstream contribution workflow

Read the repository's current contribution guide and templates. Search current
open and closed issues and pull requests for the exact recipe ID plus
`DeleteStatement`, `multi declarator`, `undefined variable`, and `MockUtil`.
Check all comments and assignees for an existing claim. If the Good OSS helper
is unavailable, perform equivalent current policy, template, duplicate,
open/closed issue and pull-request, comment, and claim searches through GitHub
tooling.

Re-run every listed failure against the current upstream default branch. If
current `main` already fixes a case, record the fixing commit and remove that
case from the proposed patch.

Contribute one focused fix with the failing cases committed before the
implementation. Do not open duplicate or parallel work. A human MUST review
the implementation, tests, public issue, pull-request text, use analysis,
declarator splitting, evaluation order, comments, and all generated examples
before publication.

AI-assisted work MUST disclose the assistance, for example:

> This change was prepared with AI assistance. I reviewed the implementation,
> tests, generated before/after examples, and contribution text.

## Symphony acceptance criteria

Symphony can reactivate
`org.openrewrite.java.testing.mockito.MockUtilsToStatic` only after:

- released artifacts contain both the declaration-deletion fix and the
  `ChangeMethodTargetToStatic` receiver-evaluation fix;
- Symphony updates the affected artifact pins through its normal dependency
  process;
- the fixed leaf alone passes the exact failure fixtures plus positive and
  negative controls, retains live declarations, and removes only individually
  proven-obsolete declarators;
- sibling declarations and evaluation remain intact and every changed output
  compiles;
- the complete ordered `OpenRewrite -> Spotless` maintenance lane passes;
- the complete repository gate passes;
- a second complete ordered maintenance run leaves the worktree unchanged; and
- the decision record and selected defect tracker record both upstream issues and pull
  requests, their releases, the updated pins, and Symphony's result.
