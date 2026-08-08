# `ReplaceStringBuilderWithString` Changes Later `char[]` Semantics

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/ReplaceStringBuilderWithString.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/ReplaceStringBuilderWithStringTest.java`

The implementation and tests were byte-identical on upstream `main` at
`bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact duplicate or active
claim was found on 2026-07-19.

## Recipe and decision context

Simple String concatenation can be clearer than a short builder chain.
However, `StringBuilder.append(char[])` appends a non-null array's characters
and throws `NullPointerException` for a null array. A `char[]` used as a later
String-concatenation operand instead receives Object-style string conversion:
a non-null array renders as its identity string and a null array renders as
`"null"`.

Symphony keeps the recipe inactive because the generated text and null
behavior change.

## Reproduction

The transformation was executed against the pinned implementation.

## Failure cases

### `char[]` follows an initial String

**Evidence status:** execution-proven pinned output. With
`new char[]{'o', 'k'}`, before returns `prefix:ok`, while actual returns
`prefix:[C@...`. With a null `char[]`, before throws
`NullPointerException`, while actual returns `prefix:null`.

**Before**

```java
class Test {
    String render(char[] chars) {
        return new StringBuilder()
                .append("prefix:")
                .append(chars)
                .toString();
    }
}
```

**Actual output**

```java
class Test {
    String render(char[] chars) {
        return "prefix:" +
                chars;
    }
}
```

**Expected output**

```java
class Test {
    String render(char[] chars) {
        return "prefix:" + String.valueOf(chars);
    }
}
```

The expected output returns `prefix:ok` for `new char[]{'o', 'k'}` and throws
`NullPointerException` for a null `char[]`, exactly like the source. Java
selects `String.valueOf(char[])`, not `String.valueOf(Object)`, so an explicit
null guard is neither necessary nor correct. Leaving the original builder
chain unchanged is also correct.

The following compiled runtime controls establish the relevant Java
semantics:

```java
char[] nullChars = null;

new StringBuilder().append((char[]) null).toString(); // throws NullPointerException
String.valueOf((char[]) null);                        // throws NullPointerException
"prefix:" + nullChars;                               // returns "prefix:null"
```

## Root cause

The visitor makes only the first non-String expression explicit with
`String.valueOf`. Later expressions are inserted into a `J.Binary` addition
without checking which removed `append` overload was selected. For
`append(char[])`, ordinary String-concatenation conversion differs for both a
non-null array and a null array. Wrapping the expression in
`String.valueOf(char[])` preserves both behaviors.

## Required fix contract

The fix MUST:

- preserve exact rendered text for every append overload;
- rewrite an argument whose attributed invocation selects `append(char[])` as
  `String.valueOf(charArrayExpression)`, regardless of chain position, or
  leave the chain unchanged;
- leave the chain unchanged when attribution does not establish the selected
  overload;
- continue leaving `append(char[], int, int)` chains unchanged unless a
  separately tested transformation preserves their range checks and
  exceptions;
- handle `char[]` in first, middle, and final positions;
- cover identifiers, fields, calls, casts, conditionals, and null arrays;
- preserve `NullPointerException` for null arguments to `append(char[])` and
  MUST NOT replace null with `"null"`, an empty String, or another fallback;
- preserve Object-style rendering when the selected overload is
  `append(Object)`, including when the runtime value happens to be a
  `char[]`;
- preserve left-to-right evaluation and exactly-once evaluation;
- retain safe String, char, primitive, Object, and nullable transformations;
- preserve comments and multiline formatting; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

The displayed fixture and all three null-semantics controls are
compiler- and runtime-proven. Treat the additional position and expression
matrix below as unexecuted regression and negative-control requirements until
run.

Add:

1. the exact fixture above with expected generated source
   `"prefix:" + String.valueOf(chars)`;
2. runtime comparison for both `new char[]{'o', 'k'}` and a null `char[]`,
   asserting `prefix:ok` and `NullPointerException`, respectively;
3. `char[]` in every chain position and multiple arrays;
4. null arrays, casts, conditionals, fields, and method-call arrays;
5. a statically Object-typed `char[]` negative control that retains the
   selected `append(Object)` semantics;
6. `append(char[], int, int)` negative controls, including null, invalid
   offset, and invalid length;
7. unattributed-source negative controls that remain unchanged;
8. side-effecting append arguments and order assertions;
9. safe String, char, primitive, and ordinary Object positives;
10. chained result used as a select or nested expression;
11. compilation of every output; and
12. an explicit second recipe cycle.

## Upstream contribution workflow

Search current issues and PRs for the recipe plus `char[]`, `append overload`,
and `String concatenation`. If the Good OSS helper is unavailable, perform
equivalent current policy, template, duplicate, issue/PR, comment, and claim
searches through GitHub tooling. Add output-comparison tests first and run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.ReplaceStringBuilderWithStringTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

A human MUST review overload selection and evaluation order for the full
append matrix.

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

Symphony can reconsider the recipe when every builder overload retains its
rendered value and evaluation order and the released recipe is clean on a
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
