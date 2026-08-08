# `AddMissingNested` Breaks Pre-Java-16 Static Members

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-testing-frameworks>
- Module: repository root recipe module
- Artifact:
  `org.openrewrite.recipe:rewrite-testing-frameworks:3.42.0`
- Audited commit: `2b5d8526dc226ff4794716133b2d0780eb257530`
- Compared upstream `main`: `224c5ebb42ea181c2579f7047e4cd3130a29669f`
- Recipe: `org.openrewrite.java.testing.junit5.AddMissingNested`
- Implementation:
  `src/main/java/org/openrewrite/java/testing/junit5/AddMissingNested.java`
- Existing tests:
  `src/test/java/org/openrewrite/java/testing/junit5/AddMissingNestedTest.java`
- Composite configuration:
  `src/main/resources/META-INF/rewrite/junit5.yml`

The implementation and existing test file were byte-identical at the audited
and compared commits. A contributor MUST repeat current-main and
duplicate-claim checks before publishing upstream work.

## Recipe and decision context

JUnit Jupiter `@Nested` test classes are non-static inner classes. Converting a
static nested test class can be useful when the class was intended to
participate in the enclosing test instance.

Before Java 16, however, an inner class cannot declare a nonconstant static
field, static initializer, static method, or static member type. The recipe
always removes `static` from the selected class without checking the source
level or its members. Symphony keeps the recipe inactive because the four
proven outputs turn Java-11-valid source into uncompilable source.

## Reproduction

Add the four fixtures below to a temporary
`AddMissingNestedStaticMemberProbeTest`. Configure the parser with JUnit
Jupiter API types, a Java 11 source marker, and the pinned recipe. Run:

```text
./gradlew test \
  --tests '*AddMissingNestedStaticMemberProbeTest' \
  --no-daemon --configure-on-demand
```

All four pinned-output assertions pass. The four actual outputs deliberately
use the same `RootTest` name, so they MUST be compiled as isolated fixtures,
not concatenated into one source file. Use this exact layout:

```text
probe/
├── api/org/junit/jupiter/api/Nested.java
├── api/org/junit/jupiter/api/Test.java
├── nonconstant-field/RootTest.java
├── static-initializer/RootTest.java
├── static-member-type/RootTest.java
└── static-method/RootTest.java
```

Save each **Actual output** block below as the `RootTest.java` in its matching
directory. The two shared annotation stubs are:

```java
// probe/api/org/junit/jupiter/api/Nested.java
package org.junit.jupiter.api;

public @interface Nested {
}
```

```java
// probe/api/org/junit/jupiter/api/Test.java
package org.junit.jupiter.api;

public @interface Test {
}
```

Compile each fixture separately:

```text
mkdir -p probe/nonconstant-field/out
javac --release 11 -d probe/nonconstant-field/out \
  probe/api/org/junit/jupiter/api/Nested.java \
  probe/api/org/junit/jupiter/api/Test.java \
  probe/nonconstant-field/RootTest.java

mkdir -p probe/static-initializer/out
javac --release 11 -d probe/static-initializer/out \
  probe/api/org/junit/jupiter/api/Nested.java \
  probe/api/org/junit/jupiter/api/Test.java \
  probe/static-initializer/RootTest.java

mkdir -p probe/static-method/out
javac --release 11 -d probe/static-method/out \
  probe/api/org/junit/jupiter/api/Nested.java \
  probe/api/org/junit/jupiter/api/Test.java \
  probe/static-method/RootTest.java

mkdir -p probe/static-member-type/out
javac --release 11 -d probe/static-member-type/out \
  probe/api/org/junit/jupiter/api/Nested.java \
  probe/api/org/junit/jupiter/api/Test.java \
  probe/static-member-type/RootTest.java
```

Verified with OpenJDK `javac 25.0.3` and `--release 11`, every invocation exits
with status 1 and emits exactly one error. The field, initializer, and method
fixtures report diagnostic code `compiler.err.icls.cant.have.static.decl`:
`Illegal static declaration in inner class RootTest.InnerTest`, followed by
`modifier 'static' is only allowed in constant variable declarations`. The
member-type fixture reports `compiler.err.mod.not.allowed.here`:
`modifier static not allowed here`.

## Failure cases

### Nonconstant static field

**Evidence status:** execution-proven pinned output; the actual output fails
Java 11 compilation with an illegal static declaration.

**Before**

```java
import org.junit.jupiter.api.Test;

class RootTest {
    static class InnerTest {
        static Object state = new Object();

        @Test
        void test() {
        }
    }
}
```

**Actual output**

```java
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RootTest {
    @Nested
    class InnerTest {
        static Object state = new Object();

        @Test
        void test() {
        }
    }
}
```

**Expected output**

```java
import org.junit.jupiter.api.Test;

class RootTest {
    static class InnerTest {
        static Object state = new Object();

        @Test
        void test() {
        }
    }
}
```

### Static initializer

**Evidence status:** execution-proven pinned output; the actual output fails
Java 11 compilation with an illegal static initializer.

**Before**

```java
import org.junit.jupiter.api.Test;

class RootTest {
    static class InnerTest {
        static {
            System.out.println("initialize");
        }

        @Test
        void test() {
        }
    }
}
```

**Actual output**

```java
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RootTest {
    @Nested
    class InnerTest {
        static {
            System.out.println("initialize");
        }

        @Test
        void test() {
        }
    }
}
```

**Expected output**

```java
import org.junit.jupiter.api.Test;

class RootTest {
    static class InnerTest {
        static {
            System.out.println("initialize");
        }

        @Test
        void test() {
        }
    }
}
```

### Static method

**Evidence status:** execution-proven pinned output; the actual output fails
Java 11 compilation with an illegal static method.

**Before**

```java
import org.junit.jupiter.api.Test;

class RootTest {
    static class InnerTest {
        static void helper() {
        }

        @Test
        void test() {
        }
    }
}
```

**Actual output**

```java
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RootTest {
    @Nested
    class InnerTest {
        static void helper() {
        }

        @Test
        void test() {
        }
    }
}
```

**Expected output**

```java
import org.junit.jupiter.api.Test;

class RootTest {
    static class InnerTest {
        static void helper() {
        }

        @Test
        void test() {
        }
    }
}
```

### Static member type

**Evidence status:** execution-proven pinned output; the actual output fails
Java 11 compilation because a pre-Java-16 inner class cannot declare a static
member class.

**Before**

```java
import org.junit.jupiter.api.Test;

class RootTest {
    static class InnerTest {
        static class Helper {
        }

        @Test
        void test() {
        }
    }
}
```

**Actual output**

```java
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RootTest {
    @Nested
    class InnerTest {
        static class Helper {
        }

        @Test
        void test() {
        }
    }
}
```

**Expected output**

```java
import org.junit.jupiter.api.Test;

class RootTest {
    static class InnerTest {
        static class Helper {
        }

        @Test
        void test() {
        }
    }
}
```

## Root cause

The pinned implementation adds the annotation and then removes `static`
without a source-version or member-legality guard:

```java
cd = JavaTemplate.builder("@Nested")
        .javaParser(JavaParser.fromJavaVersion()
                .classpathFromResources(ctx, "junit-jupiter-api-5"))
        .imports(NESTED)
        .build()
        .apply(getCursor(), cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
cd.getModifiers().removeIf(modifier -> modifier.getType() == J.Modifier.Type.Static);
```

After adding `@Nested`, the visitor directly removes the `STATIC` modifier from
the class declaration. It does not inspect the Java version marker and does not
classify the class's static members. Java 16 relaxed this inner-class
restriction; Java 8 through 15 permit only constant variables in this
position.

The four fixtures are valid before the recipe and invalid afterward. Automatic
member extraction or conversion would require separate semantic decisions, so
the safe pre-Java-16 result is no change.

## Required fix contract

The fix MUST:

- inspect the effective Java source version before removing `static`;
- on Java 8 through 15, leave the entire candidate class unchanged when it
  contains a prohibited static field, initializer, method, explicitly static
  member class, member interface, enum, or annotation type;
- treat a member record as a pre-Java-16 case only for Java 14 or 15 source
  compiled with that release's preview features enabled;
- distinguish a Java Language Specification constant variable from a
  nonconstant static field;
- inspect nested descendants whose legality changes with the enclosing class;
- add `@Nested` and remove `static` only when the complete resulting class is
  legal for the target source version;
- preserve simple eligible conversions and already non-static `@Nested`
  candidates;
- leave source unchanged when the Java version marker or required attribution
  is unavailable;
- emit compilable output; and
- remain idempotent on a second cycle.

The fix MUST NOT silently make a static member non-static or extract it merely
to force the annotation.

## Regression test plan

Move the four exact execution-proven fixtures above into
`AddMissingNestedTest`. Add:

1. Java 8, 11, and 15 markers for nonconstant static fields, static
   initializers, static methods, explicitly static member classes, member
   interfaces, enums, and annotation types;
2. a separate Java 15 record case only when the test toolchain can compile it
   with `--enable-preview --release 15`; do not put records in the Java 8 or
   Java 11 matrix;
3. Java 16, 17, and current-source controls establishing the intended
   post-relaxation behavior;
4. constant primitive and `String` fields as legal pre-Java-16 controls, plus
   boxed, array, object, and nonconstant initializer counterexamples;
5. inherited members and deeper nested descendants;
6. an eligible static nested test with no prohibited member as a positive
   conversion;
7. an already non-static inner test and an already annotated class as
   no-change controls;
8. missing Java-version markers and incomplete attribution as conservative
   no-change controls;
9. compilation of every changed output at its declared release, with preview
   enabled only for the optional Java 15 record case; and
10. explicit second-cycle and byte-stability assertions.

Run:

```text
./gradlew test \
  --tests org.openrewrite.java.testing.junit5.AddMissingNestedTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

Tests MUST assert that skipped pre-Java-16 classes do not gain the annotation
or lose `static`; partial edits would still leave a misleading or invalid
state.

## Upstream contribution workflow

Read the repository's current contribution guide and templates. Search current
open and closed issues and pull requests for the exact recipe ID plus
`static member`, `Java 15`, `inner class`, and `@Nested`. Check all comments
and assignees for an existing claim. If the Good OSS helper is unavailable,
perform equivalent current policy, template, duplicate, open/closed issue and
pull-request, comment, and claim searches through GitHub tooling.

Re-run every listed failure against the current upstream default branch. If
current `main` already fixes a case, record the fixing commit and remove that
case from the proposed patch.

Contribute one focused source-version guard with failing tests first. Do not
open duplicate or parallel work. A human MUST review the implementation,
tests, public issue, pull-request text, source-level matrix, constant-variable
classification, nested-type coverage, and all generated examples before
publication.

AI-assisted work MUST disclose the assistance, for example:

> This change was prepared with AI assistance. I reviewed the implementation,
> tests, generated before/after examples, and contribution text.

## Symphony acceptance criteria

Symphony can reactivate
`org.openrewrite.java.testing.junit5.AddMissingNested` only after:

- a released artifact preserves valid Java 8-through-15 source for all four
  proven member forms, still converts eligible nested tests, emits compilable
  output, and leaves uncertain candidates unchanged;
- Symphony updates the artifact pin through its normal dependency process;
- the fixed leaf alone passes the exact failure fixtures plus positive and
  negative controls at their declared source releases;
- the complete ordered `OpenRewrite -> Spotless` maintenance lane passes;
- the complete repository gate passes;
- a second complete ordered maintenance run leaves the worktree unchanged; and
- the decision record and selected defect tracker record the upstream issue, pull request,
  release, updated pin, and Symphony result.
