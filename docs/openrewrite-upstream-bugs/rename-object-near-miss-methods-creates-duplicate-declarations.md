# `RenameMethodsNamedHashcodeEqualOrToString` Can Create Duplicate Methods

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/RenameMethodsNamedHashcodeEqualOrToString.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/RenameMethodsNamedHashcodeEqualOrToStringTest.java`

The implementation and tests were byte-identical on upstream `main` at
`bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact duplicate or active
claim was found on 2026-07-19.

## Recipe and decision context

Renaming near-miss Object methods normally repairs an accidental failure to
override. A class can legally declare both the near-miss method and the
correctly named method because Java names are case-sensitive. Renaming in that
state creates a duplicate signature.

Symphony keeps the recipe inactive until all three rename branches guard
collisions.

## Reproduction

The `hashcode`, `equal`, and `tostring` branches were executed together against
the pinned implementation.

## Failure cases

### All three targets already exist

**Evidence status:** execution-proven pinned output for all rename branches;
the actual source contains three duplicate declarations and does not compile.

**Before**

```java
class Test {
    int hashcode() {
        return 1;
    }

    public int hashCode() {
        return 2;
    }

    boolean equal(Object value) {
        return false;
    }

    public boolean equals(Object value) {
        return true;
    }

    String tostring() {
        return "near";
    }

    public String toString() {
        return "proper";
    }
}
```

**Actual output**

```java
class Test {
    int hashCode() {
        return 1;
    }

    public int hashCode() {
        return 2;
    }

    boolean equals(Object value) {
        return false;
    }

    public boolean equals(Object value) {
        return true;
    }

    String toString() {
        return "near";
    }

    public String toString() {
        return "proper";
    }
}
```

**Expected output**

```java
class Test {
    int hashcode() {
        return 1;
    }

    public int hashCode() {
        return 2;
    }

    boolean equal(Object value) {
        return false;
    }

    public boolean equals(Object value) {
        return true;
    }

    String tostring() {
        return "near";
    }

    public String toString() {
        return "proper";
    }
}
```

The recipe MUST remain conservative rather than delete or merge either method,
because their bodies and callers can represent distinct behavior.

## Root cause

The visitor validates each near-miss name, parameters, and return type, then
schedules `ChangeMethodName`. It never searches the declaring type or
inheritance-visible declarations for a method with the target name and same
erased signature.

## Required fix contract

The fix MUST:

- skip a rename when the target signature already exists in the declaring
  type;
- cover `hashCode()`, `equals(Object)`, and `toString()` independently;
- account for generic erasure, bridge-relevant signatures, static methods,
  interfaces, records, enums, and inherited final methods;
- continue renaming unambiguous near-miss declarations and their call sites;
- avoid deleting or merging distinct implementations;
- preserve overloads with different parameters and return-type guards;
- leave incomplete attribution unchanged; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

Only the three displayed collision branches are execution-proven. Treat the
additional matrix below as unexecuted regression and negative-control
requirements until run.

Add:

1. the exact combined fixture above;
2. one collision test for each target in classes and interfaces;
3. inherited, final, static, generic-erasure, overload, record, and enum cases;
4. no-collision positives with local, cross-class, and method-reference call
   sites;
5. separate bodies and side effects proving no method is merged or deleted;
6. compilation of every changed output and compile-failure reproduction of
   the old actual;
7. incomplete attribution as no change; and
8. explicit second-cycle and byte-stability assertions.

## Upstream contribution workflow

Search current issues and PRs for the recipe plus `duplicate method`,
`hashcode`, `equal`, `tostring`, and `collision`. If the Good OSS helper is
unavailable, perform equivalent current policy, template, duplicate,
issue/PR, comment, and claim searches through GitHub tooling. Add all three
failing tests before implementing the shared collision guard, then run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.RenameMethodsNamedHashcodeEqualOrToStringTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

A human MUST review signature, erasure, inheritance, and caller-update
coverage.

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

Symphony can reconsider the recipe when every target signature is
collision-safe, valid near-miss repairs still update callers, all outputs
compile, and a released version is clean on a second cycle.

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
