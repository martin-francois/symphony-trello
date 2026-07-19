# `ArrayStoreExceptionToTypeNotPresentException` Loses Catch Semantics

## Upstream target

- Repository:
  [`openrewrite/rewrite-migrate-java`](https://github.com/openrewrite/rewrite-migrate-java)
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-migrate-java:3.40.0`
- Pinned tag and commit:
  [`v3.40.0` at `658481254a6ee678f5f162e51d8d49ee01c75877`](https://github.com/openrewrite/rewrite-migrate-java/tree/658481254a6ee678f5f162e51d8d49ee01c75877)
- Pinned implementation:
  [`ArrayStoreExceptionToTypeNotPresentException.java`](https://github.com/openrewrite/rewrite-migrate-java/blob/658481254a6ee678f5f162e51d8d49ee01c75877/src/main/java/org/openrewrite/java/migrate/ArrayStoreExceptionToTypeNotPresentException.java)
- Pinned tests:
  [`ArrayStoreExceptionToTypeNotPresentExceptionTest.java`](https://github.com/openrewrite/rewrite-migrate-java/blob/658481254a6ee678f5f162e51d8d49ee01c75877/src/test/java/org/openrewrite/java/migrate/ArrayStoreExceptionToTypeNotPresentExceptionTest.java)
- Current-main comparison: the implementation at
  [`openrewrite/rewrite-migrate-java@a5b7ddcf37df2aaa9c71799cabe387eb7fb9dd20`](https://github.com/openrewrite/rewrite-migrate-java/blob/a5b7ddcf37df2aaa9c71799cabe387eb7fb9dd20/src/main/java/org/openrewrite/java/migrate/ArrayStoreExceptionToTypeNotPresentException.java)
  was byte-for-byte identical to the pinned implementation when inspected on
  2026-07-19.

## Recipe and decision context

Exact leaf:

`org.openrewrite.java.migrate.ArrayStoreExceptionToTypeNotPresentException`

The Java 11 migration composite includes this leaf. Do not duplicate the
report for that composite or later Java migration wrappers.

Symphony records the leaf as `Rejected` with zero findings in
[`docs/openrewrite-zero-result-decisions.md`](../openrewrite-zero-result-decisions.md).
The desired Java 11 catch update is valid, but replacing an existing exception
type is unsafe when the protected region can still throw
`ArrayStoreException`.

## Reproduction

Pinned-tag `RewriteTest` probes ran successfully for all five cases below.
The probes used the exact recipe class from tag `v3.40.0`. Case 5's generated
source was also compiled independently; `javac` exited `1` with:

```text
exception java.lang.TypeNotPresentException has already been caught
```

The defect has zero Symphony findings because the current application has no
matching old catch. The evidence is isolated, not a Symphony source diff.

## Failure cases

### 1. The protected body still throws `ArrayStoreException`

**Evidence status:** Executed with a pinned-tag `RewriteTest`.

#### Before

```java
import java.lang.annotation.Annotation;

class Example {
    void inspect(
            Class<?> type,
            Class<? extends Annotation> annotation,
            Object value) {
        try {
            type.getAnnotation(annotation);
            Object[] values = new String[1];
            values[0] = value;
        } catch (ArrayStoreException e) {
            recover(e);
        }
    }

    void recover(RuntimeException e) {
    }
}
```

#### Actual pinned output

```java
import java.lang.annotation.Annotation;

class Example {
    void inspect(
            Class<?> type,
            Class<? extends Annotation> annotation,
            Object value) {
        try {
            type.getAnnotation(annotation);
            Object[] values = new String[1];
            values[0] = value;
        } catch (TypeNotPresentException e) {
            recover(e);
        }
    }

    void recover(RuntimeException e) {
    }
}
```

The assignment can still throw `ArrayStoreException`, which is no longer
caught.

#### Expected corrected output

```java
import java.lang.annotation.Annotation;

class Example {
    void inspect(
            Class<?> type,
            Class<? extends Annotation> annotation,
            Object value) {
        try {
            type.getAnnotation(annotation);
            Object[] values = new String[1];
            values[0] = value;
        } catch (ArrayStoreException | TypeNotPresentException e) {
            recover(e);
        }
    }

    void recover(RuntimeException e) {
    }
}
```

Adding a multi-catch is one valid result for this shape. Leaving the original
catch and adding a separate `TypeNotPresentException` catch with the same body
is also valid if ordering and comments are preserved.

### 2. `getAnnotation` appears only in `finally`

**Evidence status:** Executed with a pinned-tag `RewriteTest`.

#### Before

```java
class Example {
    void inspect(Class<?> type, Object value) {
        try {
            Object[] values = new String[1];
            values[0] = value;
        } catch (ArrayStoreException e) {
            recover(e);
        } finally {
            type.getAnnotation(Override.class);
        }
    }

    void recover(RuntimeException e) {
    }
}
```

#### Actual pinned output

```java
class Example {
    void inspect(Class<?> type, Object value) {
        try {
            Object[] values = new String[1];
            values[0] = value;
        } catch (TypeNotPresentException e) {
            recover(e);
        } finally {
            type.getAnnotation(Override.class);
        }
    }

    void recover(RuntimeException e) {
    }
}
```

The catch does not protect the `finally` block.

#### Expected corrected output

```java
class Example {
    void inspect(Class<?> type, Object value) {
        try {
            Object[] values = new String[1];
            values[0] = value;
        } catch (ArrayStoreException e) {
            recover(e);
        } finally {
            type.getAnnotation(Override.class);
        }
    }

    void recover(RuntimeException e) {
    }
}
```

### 3. `getAnnotation` appears only in a sibling catch handler

**Evidence status:** Executed with a pinned-tag `RewriteTest`.

#### Before

```java
class Example {
    void inspect(Class<?> type, Object value) {
        try {
            Object[] values = new String[1];
            values[0] = value;
        } catch (ArrayStoreException e) {
            recover(e);
        } catch (IllegalArgumentException e) {
            type.getAnnotation(Override.class);
        }
    }

    void recover(RuntimeException e) {
    }
}
```

#### Actual pinned output

```java
class Example {
    void inspect(Class<?> type, Object value) {
        try {
            Object[] values = new String[1];
            values[0] = value;
        } catch (TypeNotPresentException e) {
            recover(e);
        } catch (IllegalArgumentException e) {
            type.getAnnotation(Override.class);
        }
    }

    void recover(RuntimeException e) {
    }
}
```

#### Expected corrected output

```java
class Example {
    void inspect(Class<?> type, Object value) {
        try {
            Object[] values = new String[1];
            values[0] = value;
        } catch (ArrayStoreException e) {
            recover(e);
        } catch (IllegalArgumentException e) {
            type.getAnnotation(Override.class);
        }
    }

    void recover(RuntimeException e) {
    }
}
```

### 4. A deferred lambda contains the only annotation lookup

**Evidence status:** Executed with a pinned-tag `RewriteTest`.

#### Before

```java
class Example {
    Runnable inspectLater(Class<?> type, Object value) {
        try {
            Object[] values = new String[1];
            values[0] = value;
            return () -> type.getAnnotation(Override.class);
        } catch (ArrayStoreException e) {
            recover(e);
            return () -> {
            };
        }
    }

    void recover(RuntimeException e) {
    }
}
```

#### Actual pinned output

```java
class Example {
    Runnable inspectLater(Class<?> type, Object value) {
        try {
            Object[] values = new String[1];
            values[0] = value;
            return () -> type.getAnnotation(Override.class);
        } catch (TypeNotPresentException e) {
            recover(e);
            return () -> {
            };
        }
    }

    void recover(RuntimeException e) {
    }
}
```

The lambda body runs after the `try` has returned and is not protected by the
catch.

#### Expected corrected output

```java
class Example {
    Runnable inspectLater(Class<?> type, Object value) {
        try {
            Object[] values = new String[1];
            values[0] = value;
            return () -> type.getAnnotation(Override.class);
        } catch (ArrayStoreException e) {
            recover(e);
            return () -> {
            };
        }
    }

    void recover(RuntimeException e) {
    }
}
```

### 5. A `TypeNotPresentException` catch already exists

**Evidence status:** The exact rewrite was executed with a pinned-tag
`RewriteTest`; the actual output was then compiled and rejected.

#### Before

```java
class Example {
    void inspect(Class<?> type) {
        try {
            type.getAnnotation(Override.class);
        } catch (ArrayStoreException e) {
            recover(e);
        } catch (TypeNotPresentException e) {
            recover(e);
        }
    }

    void recover(RuntimeException e) {
    }
}
```

#### Actual pinned output

```java
class Example {
    void inspect(Class<?> type) {
        try {
            type.getAnnotation(Override.class);
        } catch (TypeNotPresentException e) {
            recover(e);
        } catch (TypeNotPresentException e) {
            recover(e);
        }
    }

    void recover(RuntimeException e) {
    }
}
```

#### Expected corrected output

The existing second catch already handles the modern exception. The recipe
MUST leave the original `ArrayStoreException` catch intact:

```java
class Example {
    void inspect(Class<?> type) {
        try {
            type.getAnnotation(Override.class);
        } catch (ArrayStoreException e) {
            recover(e);
        } catch (TypeNotPresentException e) {
            recover(e);
        }
    }

    void recover(RuntimeException e) {
    }
}
```

## Root cause

For every `J.Try`, the visitor calls:

```java
FindMethods.find(try_, classGetAnnotationPattern)
```

The search covers the whole `J.Try` subtree, including catches, `finally`, and
deferred nested code. It does not restrict the match to expressions whose
exceptions flow through the selected catch. Once any match exists, every
single-type `ArrayStoreException` catch is changed with `ChangeType`. The
visitor neither preserves the old exception nor checks existing catches.

## Required fix contract

- Existing `ArrayStoreException` handling MUST be preserved whenever the
  protected resources or body can still throw it.
- A lookup in a catch body, `finally`, deferred lambda, local class, or other
  non-protected execution region MUST NOT justify changing the catch.
- Existing `TypeNotPresentException`, multi-catch, catch ordering, comments,
  and handler behavior MUST remain valid.
- Generated output MUST compile.
- The recipe MAY conservatively add `TypeNotPresentException` to a compatible
  handler instead of proving `ArrayStoreException` impossible.
- The recipe MAY leave ambiguous cases unchanged.
- A more precise control-flow implementation is allowed but not required.
- The fixed recipe MUST remain idempotent: a second cycle MUST produce no
  change for positive and conservative no-change cases.

## Regression test plan

Expand the existing test class with:

1. The five executed cases above.
2. The existing simple positive example.
3. Annotation lookup in a try-with-resources initializer, which is protected.
4. Annotation lookup in the body before and after another exception source.
5. Lookup only in a catch, `finally`, lambda, method reference, anonymous
   class, and local class.
6. Existing separate `TypeNotPresentException` catch.
7. Existing multi-catches containing either or both exception types.
8. Multiple catch handlers with distinct bodies.
9. Catch ordering relative to `RuntimeException` and `Exception`.
10. Side-effecting lookup receiver and annotation-class expression.
11. Missing and partially attributed method types, with conservative no
    change.
12. Compile every changed result.
13. Runtime-style assertions that a real array-store operation still reaches
    the original handler; do not depend on host files, time, or environment.
14. Run the recipe for a second cycle on every positive output and assert
    that the second cycle produces no change; run both cycles on conservative
    no-change cases and assert that they remain byte-stable.
15. The recipe has no options; test any new safety option exhaustively.
16. Parse on the Java versions supported by the migration, especially Java 8
    input and Java 11 output.

Targeted command:

```shell
./gradlew test \
  --tests org.openrewrite.java.migrate.ArrayStoreExceptionToTypeNotPresentExceptionTest
```

Full gate:

```shell
./gradlew check
```

## Upstream contribution workflow

Submit only this fix. From Symphony, use the Good OSS helper to run
`repo-scan`, `issues-open`, and `issues-closed` for
`openrewrite/rewrite-migrate-java`. If unavailable, perform equivalent current
policy, template, issue/PR, duplicate, and claim searches through available
GitHub tooling.

Search the exact recipe ID and class name plus `ArrayStoreException`,
`TypeNotPresentException`, `getAnnotation`, and `catch`. Inspect every comment
and related closed PR for a matching issue; stop if claimed. Otherwise file
the minimal executed reproduction before coding.

Open a draft PR when the failing test exists, per the
[OpenRewrite contribution guide](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md).
A human MUST review the implementation, tests, issue, and pull-request text
before submission. When AI assistance was used, the public issue or pull
request MUST include a provider-neutral normal-prose disclosure, for example:

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

Activation requires an upstream release where:

- all five failure cases are covered;
- changed output compiles;
- actual array-store handling remains intact;
- wrapper recipes inherit the fix without duplicate configuration;
- Symphony updates the pinned artifact;
- ordered OpenRewrite then Spotless leaves a clean tree; and
- the full Symphony gate passes.

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
