# `ReplaceDeprecatedRuntimeExecMethods` Changes Command Tokenization

## Upstream target

- Repository: <https://github.com/openrewrite/rewrite-static-analysis>
- Module: repository root recipe module
- Artifact: `org.openrewrite.recipe:rewrite-static-analysis:2.39.0`
- Audited source: tag `v2.39.0`, commit
  `e51c700117e6d1bbb4c8a6e32d5f590e457b8e12`
- Implementation:
  `src/main/java/org/openrewrite/staticanalysis/ReplaceDeprecatedRuntimeExecMethods.java`
- Tests:
  `src/test/java/org/openrewrite/staticanalysis/ReplaceDeprecatedRuntimeExecMethodsTest.java`

The implementation and tests were byte-identical on upstream `main` at
`bc8d038cfd4d533758674d57876ce7a1561baf18`. No exact duplicate or active
claim was found on 2026-07-19.

## Recipe and decision context

The String overloads of `Runtime.exec` tokenize with `StringTokenizer`'s
default whitespace delimiters and collapse runs of delimiters. The recipe
uses `split(" ")` for both compile-time literals and runtime expressions.
That preserves neither repeated-space behavior nor tabs, newlines, carriage
returns, and form feeds.

Symphony keeps the recipe inactive because the generated argument vector can
launch a process with different arguments.

## Reproduction

All three matched overloads were executed for literal and dynamic commands
against the pinned implementation.

## Failure cases

### 1. Literal command with repeated spaces

**Evidence status:** execution-proven pinned output for `exec(String)`,
`exec(String,String[])`, and `exec(String,String[],File)`. The original
tokenizer emits `printf`, `'%s'`, `value`; the generated arrays include empty
arguments between repeated spaces.

**Before**

```java
import java.io.File;
import java.io.IOException;

class Test {
    void run(Runtime runtime, String[] environment, File directory) throws IOException {
        runtime.exec("printf  '%s'  value");
        runtime.exec("printf  '%s'  value", environment);
        runtime.exec("printf  '%s'  value", environment, directory);
    }
}
```

**Actual output**

```java
import java.io.File;
import java.io.IOException;

class Test {
    void run(Runtime runtime, String[] environment, File directory) throws IOException {
        runtime.exec(new String[]{"printf", "", "'%s'", "", "value"});
        runtime.exec(new String[]{"printf", "", "'%s'", "", "value"}, environment);
        runtime.exec(new String[]{"printf", "", "'%s'", "", "value"}, environment, directory);
    }
}
```

**Expected output**

```java
import java.io.File;
import java.io.IOException;

class Test {
    void run(Runtime runtime, String[] environment, File directory) throws IOException {
        runtime.exec(new String[]{"printf", "'%s'", "value"});
        runtime.exec(new String[]{"printf", "'%s'", "value"}, environment);
        runtime.exec(new String[]{"printf", "'%s'", "value"}, environment, directory);
    }
}
```

### 2. Dynamic command

**Evidence status:** execution-proven pinned output for all three overloads.
At runtime, `split(" ")` retains internal empty arguments and fails to split
the other delimiter characters recognized by the original overload.

**Before**

```java
import java.io.File;
import java.io.IOException;

class Test {
    void run(Runtime runtime, String command, String[] environment, File directory) throws IOException {
        runtime.exec(command);
        runtime.exec(command, environment);
        runtime.exec(command, environment, directory);
    }
}
```

**Actual output**

```java
import java.io.File;
import java.io.IOException;

class Test {
    void run(Runtime runtime, String command, String[] environment, File directory) throws IOException {
        runtime.exec(command.split(" "));
        runtime.exec(command.split(" "), environment);
        runtime.exec(command.split(" "), environment, directory);
    }
}
```

**Expected output**

The conservative correct result is unchanged:

```java
import java.io.File;
import java.io.IOException;

class Test {
    void run(Runtime runtime, String command, String[] environment, File directory) throws IOException {
        runtime.exec(command);
        runtime.exec(command, environment);
        runtime.exec(command, environment, directory);
    }
}
```

An upstream fix MAY generate a helper expression only if it exactly preserves
the original tokenization, empty-command errors, evaluation count, and
exception behavior.

## Root cause

The literal branch flattens constant String additions, calls
`sb.toString().split(" ")`, and embeds those tokens. The dynamic branch emits
`command.split(" ")`. Neither implements `Runtime.exec(String)`'s documented
`StringTokenizer` contract.

## Required fix contract

The fix MUST:

- preserve the exact argument vector for all original command strings;
- cover all three String overloads;
- collapse the same delimiters and handle leading, trailing, repeated, tab,
  newline, carriage-return, and form-feed input exactly;
- preserve empty-command and null-command failure behavior;
- evaluate dynamic command, environment, and directory expressions once and
  in the original order;
- retain safe literal modernization where exact tokenization is proven;
- leave dynamic expressions unchanged unless exact equivalence is generated;
- preserve comments and method attribution; and
- remain idempotent: a second cycle MUST produce no change.

## Regression test plan

Only the displayed repeated-space literal and dynamic branches are
execution-proven. Treat the additional matrix below as unexecuted regression
and negative-control requirements until run.

Add:

1. both complete fixtures above;
2. each delimiter alone and in mixed/repeated/leading/trailing forms;
3. empty, delimiter-only, null, and quoted-looking command strings;
4. literal concatenations and dynamic expressions for all overloads;
5. side-effecting command, environment, and directory expressions;
6. argument-vector assertions without launching host processes;
7. empty/error behavior assertions with an injectable tokenizer abstraction
   or direct helper tests;
8. compilation of every changed output; and
9. explicit second-cycle and no-change byte-stability checks.

## Upstream contribution workflow

Search current issues and PRs for the recipe plus `Runtime.exec`,
`StringTokenizer`, `split`, `whitespace`, and `empty argument`. If the Good
OSS helper is unavailable, perform equivalent current policy, template,
duplicate, issue/PR, comment, and claim searches through GitHub tooling.
Avoid host command assumptions in tests. Run:

```text
./gradlew test \
  --tests org.openrewrite.staticanalysis.ReplaceDeprecatedRuntimeExecMethodsTest \
  --no-daemon --configure-on-demand
./gradlew check --no-daemon --configure-on-demand
```

A human MUST review exact tokenizer and exception equivalence for every
overload.

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

Symphony can reconsider the recipe when every String overload preserves its
exact argument vector and errors, tests avoid host processes, and the released
recipe is clean on a second ordered cycle.

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
