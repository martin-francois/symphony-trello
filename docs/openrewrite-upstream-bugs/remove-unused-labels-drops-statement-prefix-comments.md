# `RemoveUnusedLabels` Drops Comments Between a Label and Its Statement

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/RemoveUnusedLabels.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/RemoveUnusedLabelsTest.java`

The implementation and tests were byte-identical on upstream `main` at
`bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact duplicate or active
claim was found on 2026-07-19.

## Recipe and decision context

Removing an unreferenced Java label is useful, but all comments attached to
the label/statement boundary must survive. The visitor overwrites the
statement's existing prefix with the label's prefix.

Symphony keeps the recipe inactive because that discards source comments.

## Reproduction

The transformation was executed against the pinned implementation.

## Failure cases

### Block comment after the colon

**Evidence status:** execution-proven pinned output; the comment is present in
the parsed before source and absent from the actual output.

**Before**

```java
class Test {
    void run() {
        unused: /* why this loop exists */
        while (condition()) {
            break;
        }
    }

    boolean condition() {
        return true;
    }
}
```

**Actual output**

```java
class Test {
    void run() {
        while (condition()) {
            break;
        }
    }

    boolean condition() {
        return true;
    }
}
```

**Expected output**

```java
class Test {
    void run() {
        /* why this loop exists */
        while (condition()) {
            break;
        }
    }

    boolean condition() {
        return true;
    }
}
```

## Root cause

For an unused label, `visitLabel` returns
`l.getStatement().withPrefix(l.getPrefix())`. That replaces, rather than
merges, the statement prefix. Comments after the label colon belong to the
statement's original prefix and are lost.

## Required fix contract

The fix MUST:

- remove only labels unused by `break` and `continue`;
- merge label and statement prefixes without dropping or duplicating comments;
- preserve comment order and the closest sensible source location;
- cover line, block, and Javadoc-like comments before the label, after the
  colon, and before the statement body;
- preserve used labels and nested same/different-name labels;
- support labeled loops, blocks, switches, and statements;
- leave malformed or uncertain trees unchanged; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

Only the displayed comment loss is execution-proven. Treat the additional
matrix below as unexecuted regression and negative-control requirements until
run.

Add:

1. the exact block-comment case;
2. line and multiple comments after the colon;
3. comments before the label and on the labeled statement;
4. for, while, do/while, block, switch, and expression-statement labels;
5. used `break` and `continue` controls;
6. nested labels with same and different names;
7. formatting and comment-order assertions;
8. parsing/compilation of every positive output; and
9. a second recipe cycle plus byte-stable no-change cases.

## Upstream contribution workflow

Search current issues and PRs for the recipe plus `comment`, `prefix`,
`label`, and `withPrefix`. If the Good OSS helper is unavailable, perform
equivalent current policy, template, duplicate, issue/PR, comment, and claim
searches through GitHub tooling. Add the loss reproduction first and run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.RemoveUnusedLabelsTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

A human MUST review comment ownership, ordering, and formatting across every
labeled-statement shape.

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

Symphony can reconsider the recipe when every comment survives in order,
label-use detection remains exhaustive, and a released version is clean on a
second ordered cycle.

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
