# `RemoveMethodsOnlyCallSuper` Drops Locking and Deprecation Contracts

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/RemoveMethodsOnlyCallSuper.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/RemoveMethodsOnlyCallSuperTest.java`

The implementation and tests were byte-identical on upstream `main` at
`bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact duplicate or claimed PR
was found on 2026-07-19.

## Recipe and decision context

An override that only forwards identical arguments to `super` is redundant
only when the declaration itself adds no contract. The recipe protects
visibility, `final`, Javadoc, and most annotations, but explicitly permits
`synchronized` and `@Deprecated` declarations to be removed.

Symphony keeps it inactive because those declarations add locking and
source/API metadata even though their body is a direct super call.

## Reproduction

Both branches were executed against the pinned implementation.

## Failure cases

### 1. `synchronized` override

**Evidence status:** execution-proven pinned output; removing the override
removes monitor acquisition before the unsynchronized parent call.

**Before**

```java
class Parent {
    void work() {
    }
}

class Child extends Parent {
    @Override
    synchronized void work() {
        super.work();
    }
}
```

**Actual output**

```java
class Parent {
    void work() {
    }
}

class Child extends Parent {
}
```

**Expected output**

```java
class Parent {
    void work() {
    }
}

class Child extends Parent {
    @Override
    synchronized void work() {
        super.work();
    }
}
```

The removal of `Child.work()` also removes acquisition of the receiver
monitor before dispatch to the unsynchronized parent method.

### 2. `@Deprecated` override

**Evidence status:** execution-proven pinned output; removing the declaration
also removes its subclass-owned deprecation metadata and diagnostics.

**Before**

```java
class Parent {
    void work() {
    }
}

class Child extends Parent {
    @Override
    @Deprecated
    void work() {
        super.work();
    }
}
```

**Actual output**

```java
class Parent {
    void work() {
    }
}

class Child extends Parent {
}
```

**Expected output**

```java
class Parent {
    void work() {
    }
}

class Child extends Parent {
    @Override
    @Deprecated
    void work() {
        super.work();
    }
}
```

Removal erases the subclass declaration and its deprecation signal from
source, compiler diagnostics, and declared-method metadata.

## Root cause

The visitor rejects `final`, widened visibility, Javadoc, and annotations
other than `Override` or `Deprecated`. It never rejects
`Flag.Synchronized`, and it deliberately treats `Deprecated` as removable.
Body equivalence is therefore used as a substitute for whole-declaration
equivalence.

## Required fix contract

The fix MUST:

- retain any forwarding override with `synchronized`;
- retain any forwarding override carrying `@Deprecated`;
- conservatively retain native, strictfp, bridge-relevant, or otherwise
  contract-bearing declarations if encountered;
- continue removing plain forwarding overrides with identical ordered
  arguments;
- preserve existing guards for visibility, `final`, Javadoc, annotations,
  constructors, and non-identical arguments;
- handle void and returned super calls;
- leave missing or partial attribution unchanged; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

Only the two displayed failures are execution-proven. Treat the additional
matrix below as unexecuted regression and negative-control requirements until
run.

Add:

1. the exact synchronized and deprecated cases above;
2. combined `synchronized @Deprecated`;
3. parent synchronized/unsynchronized combinations;
4. runtime monitor assertions using two in-memory threads and latches, not
   timing-only sleeps;
5. compile-warning and declared-annotation assertions for deprecation;
6. plain void and returned forwarding positives;
7. zero-argument, multiple-argument, reordered, transformed, and varargs
   controls;
8. every modifier, visibility, Javadoc, and annotation guard;
9. compilation of all positive outputs; and
10. explicit second-cycle and byte-stable no-change assertions.

Run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.RemoveMethodsOnlyCallSuperTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

## Upstream contribution workflow

Search current issues and PRs for the recipe plus `synchronized`,
`Deprecated`, `monitor`, and `annotation`. If the Good OSS helper is
unavailable, perform equivalent current policy, template, duplicate,
issue/PR, comment, and claim searches through GitHub tooling. Submit the two
contract guards together and require human concurrency/API review.

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

Symphony can reconsider the recipe when declaration-level contracts are
included in redundancy analysis, plain forwarding methods still simplify,
and the released recipe passes runtime, metadata, compilation, and
second-cycle tests.

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
