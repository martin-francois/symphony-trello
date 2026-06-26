---
name: java-streams-eval-capture
description: >
  Use in Symphony for Trello whenever Java code is refactored to make better
  use of Java Streams, whether the refactor is self-initiated, review-driven,
  or requested by the user. Capture the before/prompt/after code and create or
  update a self-contained eval issue in martinfrancois/java-streams-skill.
---

# Java Streams Eval Capture

## Goal

When a Symphony for Trello change improves Java Streams usage, preserve the
teaching example for the Java Streams skill. Create or update an issue in
`martinfrancois/java-streams-skill` that stands on its own: it must include the
relevant code before the refactor, the prompt or review request that triggered
the change, the intermediate or rejected implementation when one exists, the
final preferred code, and why the final code is better.

## When To Use

Use this skill whenever you:

- refactor Java code to use streams more clearly, lazily, or safely;
- replace a loop with a stream or a stream with a clearer stream shape;
- fix stream short-circuiting, ordering, allocation, collector, Optional, or
  pipeline readability problems;
- accept a user-provided streams refactor;
- correct a streams refactor after review feedback.

Do not use this skill for unrelated Java edits that merely happen to touch a
line containing `.stream()` without changing the stream design.

## Workflow

1. Capture the pre-refactor code from Git before editing when possible:

   ```bash
   git show HEAD:path/to/File.java
   ```

   If work is already in progress, use the nearest available commit, patch, or
   review comment that shows the code before the streams change.

2. Capture the prompt or review instruction that caused the streams refactor.
   Quote only the relevant requirements.

3. Capture the intermediate implementation when there was one. This is the code
   that was produced before the better version, or the code the user/reviewer
   corrected.

4. Capture the final preferred code.

5. Search for an existing Java Streams skill issue for the same pattern:

   ```bash
   gh issue list --repo martinfrancois/java-streams-skill --state open --search '<pattern keywords>'
   ```

   Update an existing matching issue instead of creating a duplicate.

6. Create or update the issue with a self-contained body. Do not include a
   "Repository context" section, and do not require the reader to open the
   source repository or pull request to understand the eval.

   ```bash
   gh issue create \
     --repo martinfrancois/java-streams-skill \
     --title 'Add eval for <stream pattern>' \
     --body-file /tmp/java-streams-eval-issue.md
   ```

## Issue Body Shape

Use this structure and adapt the wording to the stream pattern:

~~~markdown
## Problem

Describe the Java Streams lesson the skill should learn. State why this is
eval-worthy and not just generic style.

## Code before the prompt was executed

Explain the old behavior, then include the smallest relevant code block.

```java
// pre-prompt code
```

## Prompt that caused the implementation

List the relevant user, review, or product requirements that triggered the
streams refactor.

## Prompt-produced code before maintainer correction

Use this section when an intermediate or rejected implementation exists.
Explain that it may be correct but has a streams/readability/performance issue.

```java
// intermediate code
```

## Why the prompt-produced code is bad

Explain the concrete defect. For short-circuiting cases, state whether work
happens inside or before the lazy stream pipeline.

## Maintainer-preferred code

```java
// final preferred code
```

## Why the replacement is better

Tie the final code shape to the concrete stream behavior: laziness,
short-circuiting, encounter order, allocation, collector choice, or readability.

## Desired eval behavior

- Reward the intended stream shape.
- Reward the explanation that distinguishes correctness from maintainability.
- Reward preservation of the product behavior.

## Anti-patterns the eval should reject

- List misleading, over-eager, over-abstracted, or behavior-changing shapes.

## Suggested eval name

`short-kebab-case-name`
~~~

## Quality Bar

- Keep the issue self-contained. Include enough code and prompt context that a
  maintainer can build the eval without knowing Symphony for Trello.
- Explain why the weaker code is weaker even when it is functionally correct.
- Prefer concrete stream mechanics over vague style claims.
- Do not paste secrets, credentials, private host paths, Trello card ids, or
  deployment details.
- Keep large unrelated class bodies out of the issue. Include the smallest code
  needed to show the before/intermediate/final contrast.
- After creating or updating the issue, mention the issue URL in the work
  summary for the Symphony for Trello change.
