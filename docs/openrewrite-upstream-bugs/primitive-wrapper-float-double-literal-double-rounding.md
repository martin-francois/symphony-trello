# `PrimitiveWrapperClassConstructorToValueOf` Can Change `Float` Bits

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/PrimitiveWrapperClassConstructorToValueOf.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/PrimitiveWrapperClassConstructorToValueOfTest.java`

The implementation and tests were byte-identical on upstream `main` at
`bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact issue or active PR was
found on 2026-07-19. Related closed issue
[openrewrite/rewrite-static-analysis#476](https://github.com/openrewrite/rewrite-static-analysis/issues/476)
added compilable handling for `Float` constructors whose argument has type
`double`; it does not cover the double-rounding defect for literals documented
here.

## Recipe and decision context

Changing deprecated wrapper constructors to `valueOf` is desirable. The
known identity change from wrapper caching is documented by the recipe.
However, the `Float(double literal)` branch performs a different numeric
conversion: it turns the parsed double literal into a decimal string and
calls `Float.valueOf(String)`.

That can avoid the constructor's required binary64-to-binary32 rounding and
produce different float bits. This is a value defect, not the documented
identity trade-off.

## Reproduction

The exact transformation was executed against the pinned recipe. Independent
runtime evaluation produced:

- `(float) Double.parseDouble("1.0000000596046448")`:
  bits `0x3f800000` (`1.0f`);
- `Float.parseFloat("1.0000000596046448")`:
  bits `0x3f800001` (the next float).

## Failure cases

### Decimal that crosses a binary32 rounding boundary

**Evidence status:** execution-proven pinned output and runtime-proven raw-bit
difference.

**Before**

```java
class Test {
    Float value = new Float(1.0000000596046448);
}
```

**Actual output**

```java
class Test {
    Float value = Float.valueOf("1.0000000596046448");
}
```

**Expected output**

```java
class Test {
    Float value = Float.valueOf((float) 1.0000000596046448);
}
```

Leaving this one constructor unchanged is also correct, but the explicit cast
retains the modernization while preserving the constructor's conversion.

## Root cause

For a `Float` constructor whose argument is a primitive double literal, the
visitor changes the `J.Literal` type to String and builds a quoted decimal
from `literal.getValue()`. The emitted string expression selects
`Float.valueOf(String)`, which rounds the source decimal directly to float
instead of first rounding to double.

## Required fix contract

The fix MUST:

- preserve `Float.floatToRawIntBits` for every finite and non-finite legal
  constructor input;
- use `Float.valueOf((float) expression)` for primitive-double inputs;
- use `.floatValue()` exactly once for boxed `Double` inputs;
- preserve evaluation count and order for side-effecting expressions;
- retain safe Boolean, Byte, Character, Double, Integer, Long, Short, and
  primitive-float transformations;
- preserve comments, suffixes, hexadecimal floating literals, and
  parentheses;
- leave missing type attribution unchanged; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

Only the displayed failure is execution-proven. Treat the additional numeric
matrix below as unexecuted regression and negative-control requirements until
run.

Add tests for:

1. the exact halfway-adjacent decimal above with raw-bit assertions;
2. positive and negative double-rounding boundary values;
3. decimal, hexadecimal, subnormal, minimum, maximum, infinity-producing,
   and signed-zero inputs;
4. primitive double variables, boxed `Double`, method calls, casts, and
   parenthesized expressions;
5. side-effecting arguments evaluated once;
6. every other wrapper-constructor branch as a positive control;
7. compilation and runtime bit equality for every changed output; and
8. a second recipe cycle with no diff.

Run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.PrimitiveWrapperClassConstructorToValueOfTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

## Upstream contribution workflow

Search current issues and PRs for the recipe plus `Float`, `double rounding`,
`valueOf(String)`, and the exact literal. If the Good OSS helper is
unavailable, perform equivalent current policy, template, duplicate,
issue/PR, comment, and claim searches through GitHub tooling. Add the raw-bit
failure first and obtain human review of all numeric boundaries.

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

Symphony can reconsider the recipe when the modernized call preserves the
constructor's numeric value for all inputs and a released version passes
compile, runtime-bit, and second-cycle checks.

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
