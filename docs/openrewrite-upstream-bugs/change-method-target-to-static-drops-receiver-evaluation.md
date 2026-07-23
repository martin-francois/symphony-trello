# `ChangeMethodTargetToStatic` Drops Receiver Evaluation

## Upstream targets

### Core implementation

- Repository: <https://github.com/openrewrite/rewrite>
- Implementation module: `rewrite-java`
- Test module: `rewrite-java-test`
- Artifact: `org.openrewrite:rewrite-java:8.87.0`
- Audited commit: `2304703c678e9febf855d22adf18aeb32f44b7aa`
- Compared upstream `main`: `6fe43db184cb1e38777d8e0ec3c981e94cb0b6ce`
- Configurable recipe: `org.openrewrite.java.ChangeMethodTargetToStatic`
- Implementation:
  `rewrite-java/src/main/java/org/openrewrite/java/ChangeMethodTargetToStatic.java`
- Existing tests:
  `rewrite-java-test/src/test/java/org/openrewrite/java/ChangeMethodTargetToStaticTest.java`
- Configuration: no core YAML wrapper; callers configure the recipe's method
  pattern and target type.

The core implementation and existing test file were unchanged between the
audited and compared commits.

### Affected migration wrappers

- Repository: <https://github.com/openrewrite/rewrite-migrate-java>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-migrate-java:3.40.0`
- Audited commit: `658481254a6ee678f5f162e51d8d49ee01c75877`
- Compared upstream `main`: `a5b7ddcf37df2aaa9c71799cabe387eb7fb9dd20`
- Affected recipes:
  - `org.openrewrite.java.migrate.RemovedToolProviderConstructor`
  - `org.openrewrite.java.migrate.RemovedModifierAndConstantBootstrapsConstructors`
- Wrapper configuration:
  `src/main/resources/META-INF/rewrite/java-version-17.yml`
- Existing wrapper tests:
  - `src/test/java/org/openrewrite/java/migrate/RemovedToolProviderConstructorTest.java`
  - `src/test/java/org/openrewrite/java/migrate/RemovedModifierAndConstantBootstrapsConstructorsTest.java`

The wrapper configuration and tests were unchanged between the audited and
compared commits. A contributor MUST repeat current-main and duplicate-claim
checks in both repositories before publishing upstream work.

`org.openrewrite.java.testing.mockito.MockUtilsToStatic` in
`openrewrite/rewrite-testing-frameworks` is another direct consumer of the
core primitive. Its separate declaration-deletion defect is documented in
[`mock-utils-to-static-deletes-still-used-declarations.md`](mock-utils-to-static-deletes-still-used-declarations.md);
that recipe also requires a cross-regression against the core fix.

## Recipe and decision context

Java permits a static method to be invoked through an expression. The
expression is evaluated before the static method's arguments even though its
value does not select a receiver and a null result does not cause a receiver
null check.

Replacing that expression with a type name is a useful cleanup only when
discarding its evaluation is proven equivalent. The core recipe performs the
replacement unconditionally. Symphony keeps both migration wrappers inactive
because method-call receivers can mutate state, throw, synchronize, read
volatile state, or perform other observable work.

## Reproduction

Add the three migration-wrapper fixtures below to a temporary
`RemovedStaticTargetReceiverEffectsProbeTest` in
`rewrite-migrate-java`, then run:

```text
./gradlew test \
  --tests '*RemovedStaticTargetReceiverEffectsProbeTest' \
  --no-daemon --configure-on-demand
```

All three pinned-output assertions pass. Both the before and actual forms
compile. A JShell runtime control using the `Modifier` shape produced:

```text
before calls=1
after calls=0
```

The control used Java 25 and called each form once. The return value was the
same, so the counter isolates the removed receiver evaluation.

Add the bound-member-reference fixture below to a temporary
`ChangeMethodTargetToStaticMemberReferenceProbeTest` in the core
`rewrite-java-test` module, then run:

```text
./gradlew :rewrite-java-test:test \
  --tests '*ChangeMethodTargetToStaticMemberReferenceProbeTest' \
  --no-daemon --configure-on-demand
```

The pinned-output assertion passes. Both source trees compile. The exact
runtime outputs are:

```text
Before:
callsAfterReference=1
value=ok

Actual:
callsAfterReference=0
value=ok
```

The equal value isolates evaluation lost when the method-reference object is
created.

## Failure cases

### `RemovedToolProviderConstructor`

**Evidence status:** execution-proven pinned output. Java evaluation rules make
the counter increment once before the rewrite and zero times afterward.

**Before**

```java
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

class Test {
    int calls;

    ToolProvider provider() {
        calls++;
        return null;
    }

    JavaCompiler compiler() {
        return provider().getSystemJavaCompiler();
    }
}
```

**Actual output**

```java
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

class Test {
    int calls;

    ToolProvider provider() {
        calls++;
        return null;
    }

    JavaCompiler compiler() {
        return ToolProvider.getSystemJavaCompiler();
    }
}
```

**Expected output**

```java
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

class Test {
    int calls;

    ToolProvider provider() {
        calls++;
        return null;
    }

    JavaCompiler compiler() {
        provider();
        return ToolProvider.getSystemJavaCompiler();
    }
}
```

### `RemovedModifierAndConstantBootstrapsConstructors`: `Modifier`

**Evidence status:** execution-proven pinned output and runtime-proven counter
change from one to zero.

**Before**

```java
import java.lang.reflect.Modifier;

class Test {
    int calls;

    Modifier modifier() {
        calls++;
        return null;
    }

    boolean isPublic() {
        return modifier().isPublic(1);
    }
}
```

**Actual output**

```java
import java.lang.reflect.Modifier;

class Test {
    int calls;

    Modifier modifier() {
        calls++;
        return null;
    }

    boolean isPublic() {
        return Modifier.isPublic(1);
    }
}
```

**Expected output**

```java
import java.lang.reflect.Modifier;

class Test {
    int calls;

    Modifier modifier() {
        calls++;
        return null;
    }

    boolean isPublic() {
        modifier();
        return Modifier.isPublic(1);
    }
}
```

### `RemovedModifierAndConstantBootstrapsConstructors`: `ConstantBootstraps`

**Evidence status:** execution-proven pinned output. Java evaluation rules make
the counter increment before the static invocation in the original but not in
the actual output.

**Before**

```java
import java.lang.invoke.ConstantBootstraps;

class Test {
    int calls;

    ConstantBootstraps bootstraps() {
        calls++;
        return null;
    }

    Object nullConstant() {
        return bootstraps().nullConstant(null, null, null);
    }
}
```

**Actual output**

```java
import java.lang.invoke.ConstantBootstraps;

class Test {
    int calls;

    ConstantBootstraps bootstraps() {
        calls++;
        return null;
    }

    Object nullConstant() {
        return ConstantBootstraps.nullConstant(null, null, null);
    }
}
```

**Expected output**

```java
import java.lang.invoke.ConstantBootstraps;

class Test {
    int calls;

    ConstantBootstraps bootstraps() {
        calls++;
        return null;
    }

    Object nullConstant() {
        bootstraps();
        return ConstantBootstraps.nullConstant(null, null, null);
    }
}
```

The displayed expected forms show one legal statement-context solution. In an
expression-only or conditionally evaluated context, the recipe MAY instead
leave the invocation unchanged. It MUST NOT hoist evaluation across
short-circuit, branch, loop, exception, or argument-order boundaries.

### Bound member reference drops creation-time evaluation

**Evidence status:** execution-proven pinned output; both forms compile, and a
runtime counter changes from one to zero immediately after the member reference
is created.

The support types are unchanged:

```java
package a;

public class Legacy {
    public String value() {
        return "ok";
    }
}
```

```java
package b;

public class Modern {
    public static String value() {
        return "ok";
    }
}
```

**Before**

```java
import a.Legacy;

import java.util.function.Supplier;

class Test {
    int calls;

    Legacy receiver() {
        calls++;
        return new Legacy();
    }

    Supplier<String> reference() {
        return receiver()::value;
    }

    public static void main(String[] args) {
        Test test = new Test();
        Supplier<String> reference = test.reference();
        System.out.println("callsAfterReference=" + test.calls);
        System.out.println("value=" + reference.get());
    }
}
```

**Actual output**

```java
import a.Legacy;
import b.Modern;

import java.util.function.Supplier;

class Test {
    int calls;

    Legacy receiver() {
        calls++;
        return new Legacy();
    }

    Supplier<String> reference() {
        return Modern::value;
    }

    public static void main(String[] args) {
        Test test = new Test();
        Supplier<String> reference = test.reference();
        System.out.println("callsAfterReference=" + test.calls);
        System.out.println("value=" + reference.get());
    }
}
```

**Expected output**

```java
import a.Legacy;

import java.util.function.Supplier;

class Test {
    int calls;

    Legacy receiver() {
        calls++;
        return new Legacy();
    }

    Supplier<String> reference() {
        return receiver()::value;
    }

    public static void main(String[] args) {
        Test test = new Test();
        Supplier<String> reference = test.reference();
        System.out.println("callsAfterReference=" + test.calls);
        System.out.println("value=" + reference.get());
    }
}
```

Leaving the expression-qualified reference unchanged is the conservative
contract. A fix MAY instead evaluate and null-check `receiver()` exactly once
at member-reference creation, before producing `Modern::value`, when it can do
so without changing scope, order, conditionality, or exception behavior.

This core-only branch does not change a Symphony decision row. The configurable
core primitive remains `Parent/configured primitive`, and the two rejected
migration wrappers already record the shared receiver-evaluation defect.

## Root cause

The pinned invocation visitor replaces the original select with a synthesized
type identifier:

```java
m = method.withSelect(
        new J.Identifier(randomId(),
                select == null ?
                        Space.EMPTY :
                        select.getPrefix(),
                Markers.EMPTY,
                emptyList(),
                classType.getClassName(),
                classType,
                null
        )
);
```

`ChangeMethodTargetToStatic` replaces the method invocation's select expression
with an identifier for the configured target type. It never classifies or
retains the original select evaluation.

The member-reference visitor performs the same unconditional replacement:

```java
m = memberRef.withContaining(
        new J.Identifier(randomId(),
                containing.getPrefix(),
                Markers.EMPTY,
                emptyList(),
                classType.getClassName(),
                classType,
                null
        )
);
```

The two migration wrappers configure that core primitive for
`ToolProvider`, `Modifier`, and `ConstantBootstraps`. Their wrapper tests cover
the common `new Type().staticMethod()` migration, but they do not cover an
arbitrary side-effectful or throwing expression as the apparent receiver.

The pinned wrapper configuration contains these exact core-recipe entries:

```yaml
- org.openrewrite.java.ChangeMethodTargetToStatic:
    methodPattern: javax.tools.ToolProvider *()
    fullyQualifiedTargetTypeName: javax.tools.ToolProvider
- org.openrewrite.java.ChangeMethodTargetToStatic:
    methodPattern: java.lang.reflect.Modifier *(..)
    fullyQualifiedTargetTypeName: java.lang.reflect.Modifier
- org.openrewrite.java.ChangeMethodTargetToStatic:
    methodPattern: java.lang.invoke.ConstantBootstraps *(..)
    fullyQualifiedTargetTypeName: java.lang.invoke.ConstantBootstraps
```

## Required fix contract

The core fix MUST:

- preserve receiver-expression evaluation, ordering, cardinality, exceptions,
  synchronization, and volatile reads whenever they are observable;
- replace a receiver only when a complete analysis proves that discarding it
  is safe, or emit an equivalent evaluation at the original point;
- treat method calls, object creation, array access, dereference, unboxing,
  assignments, increments, volatile fields, and unknown expressions
  conservatively;
- preserve argument evaluation after receiver evaluation;
- preserve conditionality in `&&`, `||`, ternaries, switches, loops, lambdas,
  and nested expression contexts;
- preserve expression-qualified member-reference evaluation exactly once,
  including its immediate null check, at member-reference creation, or leave
  the reference unchanged;
- handle expression statements, returns, initializers, arguments, selectors,
  and chained calls without illegal statement insertion;
- leave source unchanged when attribution or control-flow placement is
  insufficient;
- leave an already type-qualified call on the configured static owner
  unchanged, and directly rewrite a type-qualified call when only its static
  owner changes;
- discard a local or parameter identifier qualifier only after attribution
  proves it is a simple, unobservable read;
- preserve every other expression qualifier at its original evaluation point
  or leave the complete invocation unchanged;
- emit compilable output; and
- remain idempotent on a second cycle.

A wrapper-only guard would protect these three APIs but leave every other user
of the public core primitive vulnerable. The primary fix belongs in
`rewrite-java`; the migration repository MUST add wrapper regressions and
consume the fixed core release. `rewrite-testing-frameworks` MUST also add a
`MockUtilsToStatic` cross-regression when it consumes the fixed core release.

## Regression test plan

### Core repository

Add to `ChangeMethodTargetToStaticTest`:

1. receiver method calls that mutate, throw, return null, or return a value;
2. field dereference, volatile read, array access, object creation, assignment,
   increment, cast, and unboxing receivers;
3. receiver and argument counters proving receiver-before-arguments ordering;
4. statement, return, initializer, argument, chained-select, lambda,
   short-circuit, ternary, switch, and loop positions;
5. bound expression-qualified member references that mutate, throw, or evaluate
   to null when the reference is created, including null-check timing, plus
   type-qualified static references as safe controls;
6. already-target-type calls as no-change controls, different type-qualified
   static owners as positive rewrites, and proven-unobservable local or
   parameter identifiers as positive rewrites;
7. missing attribution as a no-change control;
8. compilation and runtime assertions for every changed semantic branch; and
9. second-cycle and byte-stability assertions.

Run:

```text
./gradlew :rewrite-java-test:test \
  --tests org.openrewrite.java.ChangeMethodTargetToStaticTest \
  --no-daemon --configure-on-demand
./gradlew :rewrite-java:check :rewrite-java-test:check \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

### Migration repository

Add the exact three fixtures above to their owning wrapper tests. Assert the
counter and a throwing receiver, not only the printed output. Run:

```text
./gradlew test \
  --tests org.openrewrite.java.migrate.RemovedToolProviderConstructorTest \
  --tests org.openrewrite.java.migrate.RemovedModifierAndConstantBootstrapsConstructorsTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

## Upstream contribution workflow

Start in `openrewrite/rewrite`. Read its current contribution guide and
templates. Search current open and closed issues and pull requests for the
exact core recipe ID plus `receiver evaluation`, `side effect`, and
`static target`. Check all comments and assignees for an existing claim. If
the Good OSS helper is unavailable, perform equivalent policy, template,
duplicate, open/closed issue and pull-request, comment, and claim searches
through GitHub tooling.

Re-run every listed failure against the current upstream default branch. If
current `main` already fixes a case, record the fixing commit and remove that
case from the proposed patch.

After a core fix is merged and released, repeat the policy and duplicate search
in `rewrite-migrate-java`, update its core dependency through the repository's
normal version-management process, and add the wrapper regressions. Repeat the
same current-main and open/closed duplicate checks in
`rewrite-testing-frameworks` before adding the `MockUtilsToStatic`
cross-regression. Do not open parallel speculative fixes or duplicate issues.
A human MUST review the implementation, tests, public issue, pull-request
text, control flow, evaluation order, exception behavior, consumer coverage,
and every generated example before publication.

AI-assisted work MUST disclose the assistance, for example:

> This change was prepared with AI assistance. I reviewed the implementation,
> tests, generated before/after examples, and contribution text.

## Symphony acceptance criteria

Symphony can reactivate the two migration recipes only after:

- released core and migration artifacts preserve receiver evaluation for all
  three proven migration API branches, the proven core bound-member-reference
  branch, and every expression context covered by the core matrix; preserve
  receiver-before-argument ordering and thrown exceptions; and emit compilable
  output or conservatively make no change;
- Symphony updates the affected artifact pins through its normal dependency
  process;
- the fixed core primitive and each migration leaf alone pass their exact
  failure fixtures plus positive and negative controls;
- the complete ordered `OpenRewrite -> Spotless` maintenance lane passes;
- the complete repository gate passes;
- a second complete ordered maintenance run leaves the worktree unchanged; and
- the decision record and selected defect tracker record the upstream issues and pull
  requests, released artifacts, updated pins, and Symphony result.
