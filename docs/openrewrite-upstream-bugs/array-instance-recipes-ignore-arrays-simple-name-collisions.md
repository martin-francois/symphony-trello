# Array Instance Recipes Ignore an In-Scope `Arrays` Type

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation (`hashCode`):
  `src/main/java/org/openrewrite/staticanalysis/RemoveHashCodeCallsFromArrayInstances.java`
- Implementation (`toString`):
  `src/main/java/org/openrewrite/staticanalysis/RemoveToStringCallsFromArrayInstances.java`
- Tests:
  - `src/test/java/org/openrewrite/staticanalysis/RemoveHashCodeCallsFromArrayInstancesTest.java`
  - `src/test/java/org/openrewrite/staticanalysis/RemoveToStringCallsFromArrayInstancesTest.java`

Both implementations and their tests were byte-identical on upstream `main`
at `bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact duplicate or active
claim was found on 2026-07-19.

## Recipe and decision context

Replacing identity-based array `hashCode()` and `toString()` operations with
content-based `java.util.Arrays` operations improves code. Both visitors,
however, emit a simple `Arrays` name. An enclosing or nested type named
`Arrays` shadows the imported JDK type, so the generated calls bind to the
wrong class or fail compilation.

This is one shared template/import root cause covering both recipes.

## Reproduction

Every displayed transformation below was execution-proven in pinned
`RewriteTest`. The `toString` fixture covers every visitor path that emits the
unsafe simple name: direct invocation, both static wrappers, implicit method
argument conversion, and binary string concatenation.

## Failure cases

### 1. `RemoveHashCodeCallsFromArrayInstances`

**Evidence status:** execution-proven pinned output; the generated simple name
binds to the nested `Test.Arrays`, so the actual output does not compile.

**Before**

```java
class Test {
    static class Arrays {
    }

    int hash(String[] values) {
        return values.hashCode();
    }
}
```

**Actual output**

```java
import java.util.Arrays;

class Test {
    static class Arrays {
    }

    int hash(String[] values) {
        return Arrays.hashCode(values);
    }
}
```

**Expected output**

```java
class Test {
    static class Arrays {
    }

    int hash(String[] values) {
        return java.util.Arrays.hashCode(values);
    }
}
```

### 2. `RemoveToStringCallsFromArrayInstances`, all emitting paths

**Evidence status:** execution-proven pinned output for direct invocation,
both static wrappers, implicit method-argument conversion, and binary
concatenation; every generated call is captured by `Test.Arrays`.

**Before**

```java
import java.util.Objects;

class Test {
    static class Arrays {
    }

    void render(String[] values) {
        String direct = values.toString();
        String valueOf = String.valueOf(values);
        String objects = Objects.toString(values);
        System.out.println(values);
        String concat = "values=" + values;
    }
}
```

**Actual output**

```java
import java.util.Arrays;

class Test {
    static class Arrays {
    }

    void render(String[] values) {
        String direct = Arrays.toString(values);
        String valueOf = Arrays.toString(values);
        String objects = Arrays.toString(values);
        System.out.println(Arrays.toString(values));
        String concat = "values=" + Arrays.toString(values);
    }
}
```

**Expected output**

```java
class Test {
    static class Arrays {
    }

    void render(String[] values) {
        String direct = java.util.Arrays.toString(values);
        String valueOf = java.util.Arrays.toString(values);
        String objects = java.util.Arrays.toString(values);
        System.out.println(java.util.Arrays.toString(values));
        String concat = "values=" + java.util.Arrays.toString(values);
    }
}
```

## Root cause

Each template is declared with `.imports("java.util.Arrays")` and spells the
call as `Arrays.hashCode(...)` or `Arrays.toString(...)`. `maybeAddImport`
does not make that simple name unambiguous when a member, enclosing, same-file,
or same-package type already owns `Arrays`. The generated tree's intended type
attribution does not change Java's source name-resolution rules.

## Required fix contract

The shared fix MUST:

- emit an unambiguous `java.util.Arrays` reference whenever the simple name is
  captured or conflicts;
- retain the simple imported form when it is unambiguous;
- cover member, enclosing, top-level same-file, same-package, explicit-import,
  and local type-name conflicts;
- fix direct calls, `String.valueOf`, `Objects.toString`, all supported
  implicit formatting/printing/appending calls, and binary concatenation;
- preserve primitive-array and object-array overload selection;
- preserve argument evaluation count, order, comments, and formatting;
- avoid an unused or conflicting import when a fully qualified name is used;
  and
- remain idempotent: a second cycle of either recipe MUST produce no change.

## Regression test plan

Only the displayed branches are execution-proven failures. Treat the
additional matrix below as unexecuted regression and negative-control
requirements until run.

Add the two exact fixtures above, then expand with:

1. every primitive array type and object/multidimensional arrays;
2. member, enclosing, same-file top-level, same-package, explicit-import, and
   local `Arrays` conflicts;
3. a conflicting `Arrays` class with same-named methods, proving the call
   cannot silently bind to unrelated behavior;
4. direct `toString`, `String.valueOf`, `Objects.toString`, `println`,
   `format`, writer, builder append/insert, and concatenation paths;
5. method-call and side-effecting array expressions;
6. compilation of every generated result;
7. ordinary no-conflict positive controls; and
8. an explicit second cycle for each recipe and each emitting path.

Run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.RemoveHashCodeCallsFromArrayInstancesTest \
  --tests org.openrewrite.staticanalysis.RemoveToStringCallsFromArrayInstancesTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

## Upstream contribution workflow

Treat this as one shared fix. Search current issues and PRs for both recipe IDs
plus `Arrays`, `shadow`, `simple name`, and `import conflict`. If the Good OSS
helper is unavailable, perform equivalent current policy, template,
duplicate, issue/PR, comment, and claim searches via GitHub tooling. A human
MUST review every emitting branch and overload.

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

Symphony can reconsider both recipes together when all generated references
are unambiguous, every branch compiles and preserves evaluation, and both
released recipes are clean on a second ordered cycle.

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
