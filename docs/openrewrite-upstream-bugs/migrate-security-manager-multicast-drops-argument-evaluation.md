# `MigrateSecurityManagerMulticast` Drops Argument Evaluation

## Upstream target

- Repository:
  [`openrewrite/rewrite-migrate-java`](https://github.com/openrewrite/rewrite-migrate-java)
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-migrate-java:3.40.0`
- Pinned tag and commit:
  [`v3.40.0` at `658481254a6ee678f5f162e51d8d49ee01c75877`](https://github.com/openrewrite/rewrite-migrate-java/tree/658481254a6ee678f5f162e51d8d49ee01c75877)
- Pinned implementation:
  [`MigrateSecurityManagerMulticast.java`](https://github.com/openrewrite/rewrite-migrate-java/blob/658481254a6ee678f5f162e51d8d49ee01c75877/src/main/java/org/openrewrite/java/migrate/lang/MigrateSecurityManagerMulticast.java)
- Pinned tests:
  [`MigrateSecurityManagerMulticastTest.java`](https://github.com/openrewrite/rewrite-migrate-java/blob/658481254a6ee678f5f162e51d8d49ee01c75877/src/test/java/org/openrewrite/java/migrate/lang/MigrateSecurityManagerMulticastTest.java)
- Current-main comparison: the implementation at
  [`openrewrite/rewrite-migrate-java@a5b7ddcf37df2aaa9c71799cabe387eb7fb9dd20`](https://github.com/openrewrite/rewrite-migrate-java/blob/a5b7ddcf37df2aaa9c71799cabe387eb7fb9dd20/src/main/java/org/openrewrite/java/migrate/lang/MigrateSecurityManagerMulticast.java)
  was byte-for-byte identical to the pinned implementation when inspected on
  2026-07-19.

## Recipe and decision context

Exact recipe:

`org.openrewrite.java.migrate.lang.MigrateSecurityManagerMulticast`

Symphony records it as `Rejected` with zero findings in
[`docs/openrewrite-zero-result-decisions.md`](../openrewrite-zero-result-decisions.md).
The target one-argument overload ignores the deprecated TTL value, but Java
still evaluates the two-argument call's TTL expression before invoking the
method. A safe migration must preserve that evaluation.

Any modernization composite containing this leaf MUST cross-reference this
report instead of receiving a duplicate.

## Reproduction

The pinned upstream positive test and a new pinned-tag failure probe ran
successfully. The failure probe contained all four calls below in one
compilation unit and asserted the actual pinned output.

The current Symphony application has no matching Security Manager call. The
evidence is isolated and execution-proven, not a Symphony source diff.

## Failure cases

### 1. A method call is never invoked

**Evidence status:** Executed with a pinned-tag `RewriteTest`.

#### Before

```java
import java.net.InetAddress;

class Example {
    void check(SecurityManager manager, InetAddress address) {
        manager.checkMulticast(address, nextTtl());
    }

    byte nextTtl() {
        throw new IllegalStateException("evaluated");
    }
}
```

#### Actual pinned output

```java
import java.net.InetAddress;

class Example {
    void check(SecurityManager manager, InetAddress address) {
        manager.checkMulticast(address);
    }

    byte nextTtl() {
        throw new IllegalStateException("evaluated");
    }
}
```

#### Expected corrected output

The conservative correct result is no change:

```java
import java.net.InetAddress;

class Example {
    void check(SecurityManager manager, InetAddress address) {
        manager.checkMulticast(address, nextTtl());
    }

    byte nextTtl() {
        throw new IllegalStateException("evaluated");
    }
}
```

### 2. A post-increment is removed

**Evidence status:** Executed with a pinned-tag `RewriteTest`.

#### Before

```java
import java.net.InetAddress;

class Example {
    byte ttl;

    void check(SecurityManager manager, InetAddress address) {
        manager.checkMulticast(address, ttl++);
    }
}
```

#### Actual pinned output

```java
import java.net.InetAddress;

class Example {
    byte ttl;

    void check(SecurityManager manager, InetAddress address) {
        manager.checkMulticast(address);
    }
}
```

#### Expected corrected output

```java
import java.net.InetAddress;

class Example {
    byte ttl;

    void check(SecurityManager manager, InetAddress address) {
        manager.checkMulticast(address, ttl++);
    }
}
```

### 3. Array access, index mutation, and exceptions disappear

**Evidence status:** Executed with a pinned-tag `RewriteTest`.

#### Before

```java
import java.net.InetAddress;

class Example {
    byte[] ttls = {1};
    int index;

    void check(SecurityManager manager, InetAddress address) {
        manager.checkMulticast(address, ttls[index++]);
    }
}
```

#### Actual pinned output

```java
import java.net.InetAddress;

class Example {
    byte[] ttls = {1};
    int index;

    void check(SecurityManager manager, InetAddress address) {
        manager.checkMulticast(address);
    }
}
```

#### Expected corrected output

```java
import java.net.InetAddress;

class Example {
    byte[] ttls = {1};
    int index;

    void check(SecurityManager manager, InetAddress address) {
        manager.checkMulticast(address, ttls[index++]);
    }
}
```

The actual output removes index mutation plus possible null, bounds, and array
access exceptions.

### 4. Boxed-byte unboxing is removed

**Evidence status:** Executed with a pinned-tag `RewriteTest`.

#### Before

```java
import java.net.InetAddress;

class Example {
    Byte boxedTtl;

    void check(SecurityManager manager, InetAddress address) {
        manager.checkMulticast(address, boxedTtl);
    }
}
```

#### Actual pinned output

```java
import java.net.InetAddress;

class Example {
    Byte boxedTtl;

    void check(SecurityManager manager, InetAddress address) {
        manager.checkMulticast(address);
    }
}
```

#### Expected corrected output

```java
import java.net.InetAddress;

class Example {
    Byte boxedTtl;

    void check(SecurityManager manager, InetAddress address) {
        manager.checkMulticast(address, boxedTtl);
    }
}
```

The original throws `NullPointerException` when `boxedTtl` is null. The actual
output does not.

## Root cause

For every matched two-argument invocation, the visitor replaces the argument
list with a singleton containing argument zero:

```java
m.withArguments(singletonList(m.getArguments().get(0)))
```

It also edits the method type but never classifies, moves, or evaluates
argument one. The pinned positive test uses a local primitive `byte b`, whose
read is not observably needed in that fixture, so it does not expose the root
family.

## Required fix contract

- The migration MUST preserve receiver, address, and TTL evaluation order.
- Every side effect and exception from the old TTL expression MUST remain.
- Volatile reads, unboxing, array access, casts, assignments, increments,
  method calls, and allocations MUST NOT be discarded.
- A literal, compile-time constant, or proven nonthrowing local read MAY be
  removed.
- Ambiguous expressions MUST remain unchanged.
- An implementation MAY introduce temporaries only if it preserves receiver,
  first-argument, and second-argument evaluation order exactly.
- Generated code MUST compile on the declared migration target.
- The fixed recipe MUST remain idempotent: a second cycle MUST produce no
  change for positive and conservative no-change cases.

## Regression test plan

Extend the existing test with:

1. Positive literal, constant, and simple local cases.
2. All four executed failures above as no-change tests.
3. Pre/post increment, assignment, compound assignment, method invocation,
   constructor invocation, and lambda invocation.
4. Array access, field access through a nullable receiver, cast, division, and
   boxed unboxing.
5. Volatile field reads.
6. Conditional and switch expressions.
7. Side-effecting receiver, address, and TTL together, proving left-to-right
   order.
8. Missing or malformed type attribution, with no change.
9. Compilation of every positive output.
10. Runtime counters/exceptions implemented entirely in memory, avoiding host
    network or multicast assumptions.
11. Run the recipe for a second cycle on every positive output and assert no
    second-cycle diff; run both cycles on every conservative no-change case
    and assert byte stability.
12. Java 8 input symbols and supported later-Java output.
13. The recipe has no options; test all values if a purity-policy option is
    added.

Targeted validation:

```shell
./gradlew test \
  --tests org.openrewrite.java.migrate.lang.MigrateSecurityManagerMulticastTest
```

Full validation:

```shell
./gradlew check
```

## Upstream contribution workflow

Submit only this leaf fix. From Symphony, use the Good OSS helper to inspect
repository policy, open and closed issues, comments, related PRs, templates,
and claims for `openrewrite/rewrite-migrate-java`. If unavailable, perform the
equivalent current searches through the available GitHub UI or tooling.

Search the exact recipe ID, class, `checkMulticast`, `ttl`, `side effect`, and
`argument evaluation`. Stop if claimed. If no issue exists, file the executed
cases and obtain maintainer buy-in before coding.

Open an early draft PR with the failing tests, per the
[OpenRewrite contribution guide](https://github.com/openrewrite/.github/blob/main/CONTRIBUTING.md).
Human review is mandatory.

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

When AI assistance was used, the public issue or pull request MUST include a normal-prose
AI-disclosure statement, for example:

> This change was prepared with AI assistance. I reviewed the implementation, tests, generated
> before/after examples, and contribution text.

## Symphony acceptance criteria

Symphony can activate the recipe after:

- all evaluation shapes above are protected upstream;
- a released `rewrite-migrate-java` artifact contains the fix;
- Symphony updates its pin;
- positive output compiles and no-change cases remain byte-stable;
- the ordered OpenRewrite-and-Spotless run is clean; and
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
