# `RenameUnderscoreIdentifier` Creates Collisions and Broken Type References

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-migrate-java>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-migrate-java:3.40.0`
- Audited commit: `658481254a6ee678f5f162e51d8d49ee01c75877`
- Compared upstream `main`: `a5b7ddcf37df2aaa9c71799cabe387eb7fb9dd20`
- Recipe:
  `org.openrewrite.java.migrate.lang.RenameUnderscoreIdentifier`
- Implementation:
  `src/main/java/org/openrewrite/java/migrate/lang/RenameUnderscoreIdentifier.java`
- Existing tests:
  `src/test/java/org/openrewrite/java/migrate/lang/RenameUnderscoreIdentifierTest.java`
- Composite configuration:
  `src/main/resources/META-INF/rewrite/java-version-11.yml`

The implementation and existing test file were unchanged between the audited
commit and the compared `main` commit. A contributor MUST repeat the
current-main and duplicate-claim checks immediately before opening an upstream
issue or pull request.

## Recipe and decision context

Java 8 permits a single underscore as an identifier. Java 9 reserves it.
Renaming a legacy `_` declaration is therefore a useful migration, but the
current recipe always selects `__` without checking the declaration's scope.
Valid Java 8 source can already contain both names.

Symphony keeps this recipe inactive because all five proven declaration
branches can produce duplicate declarations and the separately proven class
path leaves bound type references as the reserved `_` token. These are recipe
defects, not reasons to reject the modernization goal.

## Reproduction

The modern OpenRewrite parser cannot parse `_` as an identifier directly. The
upstream test harness first used the recipe's package-visible rename visitor to
turn `UNDERSCORE` into `_`, matching the technique in the existing upstream
test. It then ran the public recipe against that Java 8-shaped AST.

Add a temporary probe beside the existing test and run:

```text
./gradlew test \
  --tests '*RenameUnderscoreIdentifierCollisionProbeTest' \
  --no-daemon --configure-on-demand
```

All five pinned-output assertions pass. Save the generated aggregate output as
`RenameCollisionActual.java`, then run:

```text
javac --release 11 RenameCollisionActual.java
```

`javac` reports duplicate declarations for the parameter, local variable,
field, method, and nested class shown below.

For the class-reference branch, a second temporary setup visitor changed every
`UNDERSCORE` identifier token to `_` so the AST contained the declaration,
explicit constructor, field type, return type, parameter type, and `new` class
that valid Java 8 source would contain. Run:

```text
./gradlew test \
  --tests org.openrewrite.java.migrate.lang.RenameUnderscoreTypePathProbeTest \
  --no-daemon --configure-on-demand
```

The pinned-output and post-recipe type assertions pass. The public recipe
renames the class and explicit constructor but leaves every bound type and
`new`-class token as `_`.

## Failure cases

### Parameter collision

**Evidence status:** execution-proven pinned output; `javac` reports that
variable `__` is already defined in `sum`.

**Before**

```java
class Test {
    int sum(int _, int __) {
        return _ + __;
    }
}
```

**Actual output**

```java
class Test {
    int sum(int __, int __) {
        return __ + __;
    }
}
```

**Expected output**

```java
class Test {
    int sum(int ___, int __) {
        return ___ + __;
    }
}
```

### Local-variable collision

**Evidence status:** execution-proven pinned output; `javac` reports that
variable `__` is already defined in `sum`.

**Before**

```java
class Test {
    int sum() {
        int _ = 1;
        int __ = 2;
        return _ + __;
    }
}
```

**Actual output**

```java
class Test {
    int sum() {
        int __ = 1;
        int __ = 2;
        return __ + __;
    }
}
```

**Expected output**

```java
class Test {
    int sum() {
        int ___ = 1;
        int __ = 2;
        return ___ + __;
    }
}
```

### Field collision

**Evidence status:** execution-proven pinned output; `javac` reports a
duplicate field named `__`.

**Before**

```java
class Test {
    int _ = 1;
    int __ = 2;

    int sum() {
        return _ + __;
    }
}
```

**Actual output**

```java
class Test {
    int __ = 1;
    int __ = 2;

    int sum() {
        return __ + __;
    }
}
```

**Expected output**

```java
class Test {
    int ___ = 1;
    int __ = 2;

    int sum() {
        return ___ + __;
    }
}
```

### Method collision

**Evidence status:** execution-proven pinned output; `javac` reports a
duplicate method named `__`.

**Before**

```java
class Test {
    int _() {
        return 1;
    }

    int __() {
        return 2;
    }

    int sum() {
        return _() + __();
    }
}
```

**Actual output**

```java
class Test {
    int __() {
        return 1;
    }

    int __() {
        return 2;
    }

    int sum() {
        return __() + __();
    }
}
```

**Expected output**

```java
class Test {
    int ___() {
        return 1;
    }

    int __() {
        return 2;
    }

    int sum() {
        return ___() + __();
    }
}
```

### Nested-class collision

**Evidence status:** execution-proven pinned output; `javac` reports a
duplicate nested class named `__`.

**Before**

```java
class Test {
    class _ {
    }

    class __ {
    }
}
```

**Actual output**

```java
class Test {
    class __ {
    }

    class __ {
    }
}
```

**Expected output**

```java
class Test {
    class ___ {
    }

    class __ {
    }
}
```

### Class rename leaves bound type and `new`-class references

**Evidence status:** execution-proven pinned output. The class declaration and
explicit constructor become `__`, while the field, return, parameter, and
`new`-class type tokens remain `_`, which is not legal Java 9-or-newer source.
The class type retains its old attributed name, and the constructor method
type is changed from `<constructor>` to `__`.

**Before**

```java
class _ {
    _() {
    }

    _ field;

    _ copy(_ input) {
        return new _();
    }
}
```

**Actual output**

```java
class __ {
    __() {
    }

    _ field;

    _ copy(_ input) {
        return new _();
    }
}
```

**Expected output**

```java
class __ {
    __() {
    }

    __ field;

    __ copy(__ input) {
        return new __();
    }
}
```

## Root cause

The pinned implementation fixes the replacement spelling when it constructs
the visitor:

```java
new RenameIdentifierVisitor("_", "__")
```

The visitor has separate paths for variables, methods, and classes, but every
path hard-codes `_` to `__`. The variable path delegates to core
`RenameVariable`, whose contract permits renaming even when the target name is
already declared in the same scope. The method and class paths rename directly
and perform no target-name collision search.

The variable and method fixtures therefore collapse two declarations and their
uses onto one spelling. The pinned class branch shown below changes only the
class declaration's name:

```java
classDecl = classDecl.withName(classDecl.getName().withSimpleName(newName));
```

The separate executed class-reference probe proves that the method path reaches
and renames an explicit constructor, but the visitor has no corresponding type
reference or `J.NewClass` path. It leaves the printed type tokens at `_`, keeps
the class type's old attributed name, and changes the constructor method type's
name away from `<constructor>`.

## Required fix contract

The fix MUST:

- choose a deterministic legal target that is unused in the declaration's
  effective scope, such as `__`, then `___`, or skip the declaration safely;
- cover the five proven collision branches plus the proven class declaration,
  explicit-constructor, type-reference, and `new`-class path;
- update only references bound to the renamed variable, method, or type;
- preserve constructor identity and consistent class, method, and constructor
  type attribution;
- account for shadowing, capture, inherited members, overloads, erased method
  signatures, method references, and cross-compilation-unit uses;
- preserve different-scope uses of `__` when they do not conflict;
- leave source unchanged when attribution is insufficient to prove a safe
  rename;
- produce compilable output for every changed source; and
- remain idempotent: a second recipe cycle MUST produce no change.

The fix MUST NOT merge declarations, redirect references to another symbol, or
rename an existing `__` declaration merely to make room.

## Regression test plan

Move the six exact fixtures above into
`RenameUnderscoreIdentifierTest`. Add:

1. one compile assertion for every displayed output;
2. positive no-collision cases for parameters, locals, fields, methods, and a
   nested or top-level type with explicit and implicit constructors, field
   types, return types, parameter types, class literals, casts, `instanceof`,
   and `new` expressions;
3. same-name declarations in nested but nonconflicting scopes;
4. field hiding, local shadowing, overload, inheritance, generic-erasure,
   interface, enum, anonymous-class, and lambda cases;
5. several pre-existing underscore-only candidates so selection advances past
   `__` and `___` deterministically;
6. attributed method invocations, method references, and
   cross-compilation-unit calls that prove only the selected method's callers
   change;
7. incomplete or missing type attribution as an explicit no-change case;
8. Java 9-or-newer source markers and identifiers other than exactly `_` as
   no-change controls; and
9. second-cycle and byte-stability assertions.

Run:

```text
./gradlew test \
  --tests org.openrewrite.java.migrate.lang.RenameUnderscoreIdentifierTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

The tests MUST verify symbol binding, not only printed spelling. Every changed
fixture MUST compile under the intended target release.

## Upstream contribution workflow

Before editing, use the repository's current contribution guide and templates.
Search current open and closed issues and pull requests for the exact recipe ID
plus `underscore`, `collision`, `duplicate declaration`, and
`RenameVariable`. Check all comments and assignees for an existing claim. If
the Good OSS helper is unavailable, perform equivalent policy, template,
duplicate, open/closed issue and pull-request, comment, and claim searches
through GitHub tooling.

Re-run every listed failure against the current upstream default branch. If
current `main` already fixes a case, record the fixing commit and remove that
case from the proposed patch.

Contribute one focused fix with failing tests first. Do not open a duplicate
issue or parallel pull request. A human MUST review the implementation, tests,
public issue, pull-request text, scope lookup, symbol binding, erasure, caller
updates, and all generated examples before publication.

AI-assisted work MUST disclose the assistance, for example:

> This change was prepared with AI assistance. I reviewed the implementation,
> tests, generated before/after examples, and contribution text.

## Symphony acceptance criteria

Symphony can reactivate
`org.openrewrite.java.migrate.lang.RenameUnderscoreIdentifier` only after:

- a released artifact handles all five proven collision branches without
  duplicate declarations, consistently renames the proven class-reference
  path, preserves bindings and callers, emits compilable output, and leaves
  uncertain cases unchanged;
- Symphony updates the artifact pin through its normal dependency process;
- the fixed leaf alone passes the exact failure fixtures plus positive and
  negative controls;
- the complete ordered `OpenRewrite -> Spotless` maintenance lane passes;
- the complete repository gate passes;
- a second complete ordered maintenance run leaves the worktree unchanged; and
- the decision record and selected defect tracker record the upstream issue, pull request,
  release, updated pin, and Symphony result.
