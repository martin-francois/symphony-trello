---
name: java-optionals-eval-capture
description: >
  Use in Symphony for Trello whenever Java Optional code is written, reviewed,
  or refactored to improve Optional usage, whether the change is self-initiated,
  review-driven, or requested by the user. Capture the before/prompt/after code
  and create or update a self-contained eval issue in
  martinfrancois/java-optionals-skill.
---

# Java Optionals Eval Capture

## Goal

When a Symphony for Trello change improves Java Optional usage, preserve the
teaching example for the Java Optionals skill. Create or update an issue in
`martinfrancois/java-optionals-skill` that stands on its own: it must include
the relevant code before the prompt, the prompt or review request that triggered
the change, the intermediate or rejected implementation when one exists, the
final preferred code, and why the final code is better.

## When To Use

Use this skill whenever you:

- replace `isPresent()` / `get()` / `orElseThrow()` control flow with clearer
  Optional APIs;
- replace `orElse(null)`, null checks, fake streams, or generic Optional helpers
  with a direct Optional shape;
- improve `map`, `flatMap`, `or`, `orElseGet`, `ifPresent`, or
  `ifPresentOrElse` usage;
- preserve checked-exception or side-effect boundaries while improving Optional
  readability;
- accept a user-provided Optional refactor;
- correct an Optional refactor after review feedback.

Do not use this skill for unrelated Java edits that merely touch a line
containing `Optional` without changing the Optional design.

## Workflow

1. Capture the pre-refactor code from Git before editing when possible:

   ```bash
   git show HEAD:path/to/File.java
   ```

   If work is already in progress, use the nearest available commit, patch, or
   review comment that shows the code before the Optional change.

2. Capture the prompt or review instruction that caused the Optional refactor.
   Quote only the relevant requirements.

3. Capture the intermediate implementation when there was one. This is the code
   that was produced before the better version, or the code the user/reviewer
   corrected.

4. Capture the final preferred code.

5. Search for an existing Java Optionals skill issue for the same pattern:

   ```bash
   gh issue list --repo martinfrancois/java-optionals-skill --state open --search '<pattern keywords>'
   ```

   Update an existing matching issue instead of creating a duplicate.

6. Create or update the issue with a self-contained body. Do not include a
   "Repository context" section, and do not require the reader to open the
   source repository or pull request to understand the eval.

   ```bash
   gh issue create \
     --repo martinfrancois/java-optionals-skill \
     --title 'feat: add eval for <optional pattern>' \
     --body-file /tmp/java-optionals-eval-issue.md
   ```

## Issue Body Shape

Use this structure and adapt the wording to the Optional pattern:

~~~markdown
## Problem

Describe the Java Optional lesson the skill should learn. State why this is
eval-worthy and not just generic style.

## Code before the prompt was executed

Explain the old behavior, then include the smallest relevant code block.

```java
// pre-prompt code
```

## Prompt that caused the implementation

List the relevant user, review, or product requirements that triggered the
Optional refactor.

## Prompt-produced code before maintainer correction

Use this section when an intermediate or rejected implementation exists. If the
reviewed code is the pre-prompt code, say so explicitly. Explain that it may be
correct but has an Optional/readability/maintainability issue.

```java
// intermediate or reviewed code
```

## Why the prompt-produced code is weak

Explain the concrete weakness. Distinguish functional correctness from the
Optional API, readability, laziness, side-effect, or exception-boundary problem.

## Maintainer-preferred code

```java
// final preferred code
```

## Why the replacement is better

Tie the final code shape to the concrete Optional behavior: single consumption
of the value, explicit present/empty handling, lazy fallback, clearer
transformation, preserved side effects, or checked-exception boundaries.

## Desired eval behavior

- Reward the intended Optional shape.
- Reward the explanation that distinguishes correctness from maintainability.
- Reward preservation of the product behavior.

## Anti-patterns the eval should reject

- List misleading, over-abstracted, null-style, fake-stream, or behavior-changing
  shapes.

## Suggested eval name

`short-kebab-case-name`
~~~

## Quality Bar

- Keep the issue self-contained. Include enough code and prompt context that a
  maintainer can build the eval without knowing Symphony for Trello.
- Explain why the weaker code is weaker even when it is functionally correct.
- Prefer concrete Optional mechanics over vague style claims.
- Include the relevant Java baseline when it determines which Optional APIs are
  available.
- Do not paste secrets, credentials, private host paths, Trello card ids, or
  deployment details.
- Keep large unrelated class bodies out of the issue. Include the smallest code
  needed to show the before/intermediate/final contrast.
- After creating or updating the issue, mention the issue URL in the work
  summary for the Symphony for Trello change.
