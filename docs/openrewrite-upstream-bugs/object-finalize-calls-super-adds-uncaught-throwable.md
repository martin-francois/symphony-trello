# `ObjectFinalizeCallsSuper` Can Add an Unhandled `Throwable`

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/ObjectFinalizeCallsSuper.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/ObjectFinalizeCallsSuperTest.java`

The implementation and tests were byte-identical on upstream `main` at
`bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact duplicate or active
claim was found on 2026-07-19.

## Recipe and decision context

An override may legally narrow `Object.finalize()` by omitting
`throws Throwable` when it does not call the superclass implementation. The
recipe appends `super.finalize()` but does not update or otherwise satisfy the
checked-exception contract.

Symphony keeps the recipe inactive because it can turn compiling input into
uncompilable output.

## Reproduction

The transformation was executed in an isolated pinned `RewriteTest`.

## Failure cases

### Override legally omits `throws Throwable`

**Evidence status:** execution-proven pinned output; compilation of the actual
source requires the added call's checked `Throwable` to be caught or declared.

**Before**

```java
class Test {
    @Override
    protected void finalize() {
        cleanup();
    }

    void cleanup() {
    }
}
```

**Actual output**

```java
class Test {
    @Override
    protected void finalize() {
        cleanup();
        super.finalize();
    }

    void cleanup() {
    }
}
```

Compilation reports that the unhandled `Throwable` from `super.finalize()`
must be caught or declared.

**Expected output**

One acceptable correction is:

```java
class Test {
    @Override
    protected void finalize() throws Throwable {
        cleanup();
        super.finalize();
    }

    void cleanup() {
    }
}
```

If adding `throws Throwable` is not legal for the attributed override chain,
the recipe MUST leave the method unchanged rather than invent exception
handling.

## Root cause

The visitor verifies only that a declaration matches
`java.lang.Object finalize()` and lacks a matching invocation. It applies a
literal `super.finalize()` template at the last-statement coordinate. It does
not inspect the declaration's throws list or validate whether the generated
call compiles.

## Required fix contract

The fix MUST:

- produce compilable output for a valid override with no throws clause;
- preserve existing `throws Throwable` and compatible broader declarations;
- avoid duplicating throws types or the superclass call;
- retain the original cleanup statements and their order;
- handle comments, empty bodies, nested calls, and already-present explicit
  superclass calls;
- leave missing or unreliable method attribution unchanged;
- parse and compile on the Java versions supported by this recipe; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

Only the displayed failure is execution-proven. Treat the additional matrix
below as unexecuted regression and negative-control requirements until run.

Add tests for:

1. the exact no-throws reproduction;
2. existing `throws Throwable`, `throws Exception`, and narrower declarations;
3. a parent override chain that constrains the legal throws clause;
4. empty and non-empty bodies, comments, `try`/`finally`, and an existing
   `super.finalize()` call;
5. unresolved and partial method attribution;
6. compilation of every positive output;
7. first-cycle and second-cycle assertions; and
8. no-change byte stability across two cycles.

Run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.ObjectFinalizeCallsSuperTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

## Upstream contribution workflow

Search current issues and PRs for the recipe, `finalize`, `Throwable`, and
`unreported exception`. Inspect comments and claims before filing. If the Good
OSS helper is unavailable, perform equivalent policy, template, duplicate,
issue/PR, comment, and claim checks with GitHub tooling. Submit this fix alone
with a human review of exception compatibility.

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

Symphony can reconsider the recipe when all valid narrowed overrides remain
compilable, existing safe cases still receive the superclass call, and a
released recipe is idempotent in the ordered maintenance lane.

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
