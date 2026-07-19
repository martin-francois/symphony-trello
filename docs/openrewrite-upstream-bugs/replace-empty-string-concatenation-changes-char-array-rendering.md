# `ReplaceStringConcatenationWithStringValueOf` Changes `char[]` Rendering

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/ReplaceStringConcatenationWithStringValueOf.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/ReplaceStringConcatenationWithStringValueOfTest.java`

The implementation and tests were byte-identical on upstream `main` at
`bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact duplicate or active
claim was found on 2026-07-19.

## Recipe and decision context

For most values, `"" + value` and `String.valueOf(value)` are equivalent.
Overload resolution makes `char[]` an exception: concatenation performs
Object-style conversion, while `String.valueOf(char[])` returns the character
contents.

Symphony keeps the recipe inactive because that changes the returned String.

## Reproduction

The transformation was executed in the same focused `RewriteTest` on the pinned commit and current
upstream `main` at `bc8d038cfd4d533758674d57876ce7a1561baf18`.

## Failure cases

### `char[]` right operand

**Evidence status:** execution-proven pinned output. For `{'o', 'k'}`, before
returns an array identity string such as `[C@...`; actual returns `ok`.

**Before**

```java
class Test {
    String render(char[] chars) {
        return "" + chars;
    }
}
```

**Actual output**

```java
class Test {
    String render(char[] chars) {
        return String.valueOf(chars);
    }
}
```

**Expected output**

```java
class Test {
    String render(char[] chars) {
        return String.valueOf((Object) chars);
    }
}
```

Leaving the original concatenation unchanged is also correct.

## Root cause

The visitor excludes an already-String right operand, null literals, nested
binary chains, and several parent shapes. It then applies
`String.valueOf(#{any()})` using the right operand's attributed type.
Overload resolution selects `String.valueOf(char[])`, which is not the
conversion used by String concatenation.

## Required fix contract

The fix MUST:

- preserve the exact String for every operand type;
- treat primitive and multidimensional `char` arrays according to their
  actual static type;
- insert Object conversion for `char[]` or leave it unchanged;
- cover identifiers, fields, calls, casts, conditionals, and parentheses;
- preserve null behavior, evaluation count, order, and exceptions;
- retain safe primitive, String-ineligible, array, and Object positives;
- preserve parentheses cleanup and comments; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

Only the displayed `char[]` failure is execution-proven. Treat the additional
matrix below as unexecuted regression and negative-control requirements until
run.

Add:

1. the exact case with runtime result comparison;
2. null and non-null `char[]`;
3. `char[][]`, Object-typed arrays, casts, conditionals, fields, and calls;
4. all primitive arrays and ordinary object arrays as controls;
5. side-effecting right operands evaluated once;
6. nested concatenation and parenthesized branches;
7. compilation of all generated output; and
8. explicit second-cycle assertions.

## Upstream contribution workflow

Search current issues and PRs for the recipe plus `char[]`, `String.valueOf`,
`overload`, and `concatenation`. If the Good OSS helper is unavailable,
perform equivalent current policy, template, duplicate, issue/PR, comment,
and claim searches through GitHub tooling. Add runtime comparison first and
run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.ReplaceStringConcatenationWithStringValueOfTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

A human MUST review overload selection and result preservation across all
array types.

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

Symphony can reconsider the recipe when overload-aware conversion preserves
every result and a released recipe passes runtime and second-cycle checks.

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
