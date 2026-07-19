# `UnnecessaryCloseInTryWithResources` Changes Resource Lifetime

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/UnnecessaryCloseInTryWithResources.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/UnnecessaryCloseInTryWithResourcesTest.java`

The implementation and tests were byte-identical on upstream `main` at
`bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact duplicate or active
claim was found on 2026-07-19.

## Recipe and decision context

Try-with-resources guarantees close at scope exit. It does not make an
explicit earlier close behaviorally redundant: code after that statement can
observe the closed state, and a second implicit close can affect counts,
exceptions, and suppression.

Symphony keeps the recipe inactive because it removes explicit close
statements at any top-level position in the try body.

## Reproduction

Both resource-declaration forms supported by the visitor were executed
together against the pinned implementation.

## Failure cases

### Declared and Java 9 referenced resources

**Evidence status:** execution-proven pinned output for
`J.VariableDeclarations` and `J.Identifier` resource branches. Before, the
assertions see `closed == true`; actual reaches them with `closed == false`.

**Before**

```java
class Test {
    static class Resource implements AutoCloseable {
        boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    void declaration() {
        try (Resource resource = new Resource()) {
            resource.close();
            if (!resource.closed) {
                throw new AssertionError();
            }
        }
    }

    void reference(Resource resource) {
        try (resource) {
            resource.close();
            if (!resource.closed) {
                throw new AssertionError();
            }
        }
    }
}
```

**Actual output**

```java
class Test {
    static class Resource implements AutoCloseable {
        boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    void declaration() {
        try (Resource resource = new Resource()) {
            if (!resource.closed) {
                throw new AssertionError();
            }
        }
    }

    void reference(Resource resource) {
        try (resource) {
            if (!resource.closed) {
                throw new AssertionError();
            }
        }
    }
}
```

**Expected output**

```java
class Test {
    static class Resource implements AutoCloseable {
        boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }

    void declaration() {
        try (Resource resource = new Resource()) {
            resource.close();
            if (!resource.closed) {
                throw new AssertionError();
            }
        }
    }

    void reference(Resource resource) {
        try (resource) {
            resource.close();
            if (!resource.closed) {
                throw new AssertionError();
            }
        }
    }
}
```

## Root cause

The visitor gathers resource names and removes any top-level body statement
matching `AutoCloseable close()` whose select is an identifier with that name.
It does not require the close to be last, inspect later uses, prove
idempotence, or model primary/suppressed exceptions.

## Required fix contract

The fix MUST:

- preserve intentional early lifetime termination;
- preserve close invocation count, order, primary exceptions, and suppressed
  exceptions;
- cover declared and referenced resources and multiple-resource reverse-close
  order;
- never infer close idempotence from `AutoCloseable`;
- remove a call only when equivalence is proven for that concrete resource
  type and position, otherwise leave it unchanged;
- account for later reads, calls, aliases, returns, catches, and `finally`;
- preserve comments attached to removed/retained statements; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

Only the two displayed resource-form failures are execution-proven. Treat the
additional matrix below as unexecuted regression and negative-control
requirements until run.

Add:

1. the exact two-form fixture above;
2. close first, middle, and last;
3. later direct and aliased resource use;
4. non-idempotent close counters;
5. first and second close throwing in combinations with a body exception,
   asserting primary and suppressed exceptions;
6. multiple resources and reverse order;
7. nested blocks, catches, finally, comments, and incomplete attribution;
8. any concrete idempotent positive supported by the chosen fix;
9. in-memory resources only, with no host files or sockets; and
10. explicit second-cycle and byte-stability assertions.

## Upstream contribution workflow

Search current issues and PRs for the recipe plus `early close`,
`suppressed exception`, `AutoCloseable`, and `idempotent`. If the Good OSS
helper is unavailable, perform equivalent current policy, template,
duplicate, issue/PR, comment, and claim searches through GitHub tooling.
Add lifetime and exception tests first and run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.UnnecessaryCloseInTryWithResourcesTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

A human MUST review resource lifetime and primary/suppressed exception
semantics.

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

Symphony can reconsider the recipe only after lifetime, count, and exception
contracts are preserved for both resource forms and the released recipe is
clean on a second cycle.

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
