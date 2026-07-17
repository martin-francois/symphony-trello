---
name: java-optionals-eval-capture
description: >
  Use in Symphony for Trello when a Java change or review presents a reusable
  Optional or absence/fallback strategy-selection lesson, including on an
  adjacent owning API such as ScopedValue even when no java.util.Optional
  appears. Capture the before/prompt/after code and create or update a
  self-contained eval issue in
  martinfrancois/java-optionals-skill.
---

# Java Optionals Eval Capture

## Goal

When a Symphony for Trello change provides a reusable Optional or adjacent
absence/fallback strategy-selection lesson, preserve the teaching example for
the Java Optionals skill. Capture the reusable absence-handling shape without
generalizing an unrelated container or context migration. Create or update an
issue in `martinfrancois/java-optionals-skill` that stands on its own: it must
include the relevant code before the prompt, the prompt or review request that
triggered the change, the intermediate or rejected implementation when one
exists, the final preferred code, and why the final code is better.

When the Optional improvement is prompted by a reviewer or user after Codex
introduced the weaker Optional shape, also capture the earlier prompt that
caused Codex to write that shape. The eval issue must make the trigger failure
explicit: the Optionals skill should have activated during the original
implementation prompt, not only during the later refactor request.

When a later request reveals only that activation or eval capture was missed,
even though the implementation was already preferred, capture both prompts and
state that no code correction was needed. The eval must reward recognizing and
capturing the reusable lesson without inventing a defect.

When an Optional refactor is later found to be non-equivalent, over-abstracted,
or worse than the original code, capture the full correction chain. Include the
original code, the prompt that produced the weaker Optional code, the weaker
code, the later correction/revert prompt, and the final code. The eval must
teach the skill to preserve behavior by default, and to call out any laziness,
exception, side-effect, nullability, prompt, or checked-boundary change
explicitly before recommending it.

## When To Use

Use this skill whenever you:

- replace `isPresent()` / `get()` / `orElseThrow()` control flow with clearer
  Optional APIs;
- replace `orElse(null)`, null checks, fake streams, or generic Optional helpers
  with a direct Optional shape;
- improve `map`, `flatMap`, `or`, `orElseGet`, `ifPresent`, or
  `ifPresentOrElse` usage;
- simplify nullable, absent, fallback, or default strategy selection on an
  adjacent owning API such as `ScopedValue.orElse(...)` when choosing and
  invoking the strategy is a reusable lesson, even when the final code contains
  no `java.util.Optional`;
- preserve checked-exception or side-effect boundaries while improving Optional
  readability;
- accept a user-provided Optional refactor;
- correct an Optional refactor after review feedback;
- revert or repair an Optional refactor because it changed behavior, laziness,
  exception handling, side effects, nullability, prompt ordering, or a checked
  boundary.

Do not use this skill for unrelated Java edits that merely touch `Optional`, a
null validation, a configuration default, or collection work inside
`Optional.map(...)` without changing the Optional design or absence/fallback
behavior. Collection-only mapping lessons belong to Streams capture instead.

## Workflow

For a broad Java, API, or refactoring audit, enumerate every implemented
nullable, absent, fallback, and default-selection change that presents a
reusable strategy or behavior lesson before capture. Create or update one issue
for each distinct reusable behavior boundary, including missed triggers on
adjacent owning APIs; do not stop at changes that spell `Optional` explicitly.

1. Capture the pre-refactor code from Git before editing when possible:

   ```bash
   git show HEAD:path/to/File.java
   ```

   If work is already in progress, use the nearest available commit, patch, or
   review comment that shows the code before the Optional change.

2. Capture the prompt or review instruction that caused the Optional refactor.
   Quote only the relevant requirements.

3. If the weaker Optional code was agent-produced earlier in the same transcript
   or PR, inspect the transcript, commit history, review thread, or issue prompt
   to recover:

   - the code before that earlier prompt ran;
   - the earlier prompt that should have triggered the Optionals skill;
   - the agent-produced Optional code that later needed correction.

   If the exact earlier prompt is unavailable, state that explicitly and use the
   nearest recoverable prompt or commit message. Do not stop at the later
   "please refactor/use the Optionals skill" prompt when the useful eval is a
   missed-trigger case.

4. Capture the intermediate implementation when there was one. This is the code
   that was produced before the better version, or the code the user/reviewer
   corrected.

5. If the Optional refactor changed behavior or was later judged worse, capture
   the correction prompt and explain the equivalence failure. Include both:

   - the behavior-preserving alternative the skill should have suggested first;
   - any behavior-changing alternative only when the available context proves
     the behavior change is safe or desirable.

   If the context is insufficient to prove that a behavior-changing refactor is
   safe, the desired eval behavior should reward asking for confirmation or
   choosing the behavior-preserving shape.

6. Capture the final preferred code.

7. Search for an existing Java Optionals skill issue for the same pattern:

   ```bash
   gh issue list --repo martinfrancois/java-optionals-skill --state open --search '<pattern keywords>'
   ```

   Update an existing matching issue instead of creating a duplicate. If the
   same pattern appears in a different code location, add a comment to the
   existing issue with that occurrence's relevant before code, triggering
   prompt, intermediate code when available, final code, and why the same eval
   should cover it. Do not skip the capture merely because the issue already
   exists for another file.

8. Create or update the issue with a self-contained body. Do not include a
   "Repository context" section, and do not require the reader to open the
   source repository or pull request to understand the eval.

   ```bash
   gh issue create \
     --repo martinfrancois/java-optionals-skill \
     --title 'feat: add eval for <optional pattern>' \
     --body-file /tmp/java-optionals-eval-issue.md
   ```

9. Add or update a temporary override in `docs/agents/java-style.md` or the
   narrowest relevant agent-doc page for every eval issue that captures bad,
   missing, or unreliable Optional-skill behavior observed in Symphony for
   Trello. Link the issue from the override. The override must say to check the
   linked issue state at most once per turn before applying it, and to remove
   the override when the linked issue is closed. Do not leave agents relying
   only on the future upstream skill fix when the current repo has already
   observed the bad behavior. If an existing temporary override already covers
   the issue, update that override or record in the eval comment which override
   covers it instead of adding a duplicate.

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

List the relevant user, review, or product requirements that caused the
absence- or fallback-relevant pattern to be implemented. If this is a
missed-trigger case, this is the original implementation prompt, not the later
capture request. Do not call already-preferred code weak merely because
activation or capture was missed.

## Later prompt that exposed the issue

Use this section when a reviewer or user later asked to use the Optionals skill
or corrected the implementation. Explain that the later prompt is useful context
but should not be the only eval trigger.

## Prompt-produced or reviewed code

Show the implementation that should have triggered the skill. If the reviewed
code is the pre-prompt code, say so explicitly. When an intermediate or rejected
implementation exists, explain its concrete Optional, readability,
maintainability, or behavior-equivalence problem. When the code was already
preferred and only activation or capture failed, say so explicitly.

```java
// prompt-produced or reviewed code
```

## What the skill missed

State whether the miss was the code choice, behavior analysis, skill activation,
eval capture, or some combination. Explain a code weakness only when one exists.
When it does, distinguish functional correctness from the Optional API,
readability, laziness, side-effect, or exception-boundary problem.

## Behavior-equivalence analysis

Use this section when the refactor changes or might change laziness, exception
behavior, side-effect ordering, nullability, prompt ordering, checked-boundary
behavior, or return shape. State whether the behavior change is safe, unsafe,
or not provable from local context. Include the behavior-preserving alternative
when the final preferred code intentionally changes behavior.

## Maintainer-preferred code

When no code correction was needed, repeat the already-preferred code and say
that the desired change is activation/capture behavior, not a different
implementation.

```java
// final preferred code
```

## Why the replacement is better

Tie the final code shape to the concrete Optional behavior: single consumption
of the value, explicit present/empty handling, lazy fallback, clearer
transformation, preserved side effects, or checked-exception boundaries.

## Desired eval behavior

- Reward the intended Optional shape.
- Reward triggering the Optionals skill during the original implementation
  prompt when it asks for Optional-relevant code, even if the prompt does not
  explicitly name the skill.
- Reward the explanation that distinguishes correctness from maintainability.
- Reward preservation of the product behavior.
- Reward identifying behavior-changing Optional suggestions and either choosing
  the behavior-preserving implementation or explicitly justifying why the change
  is safe.

## Anti-patterns the eval should reject

- List misleading, over-abstracted, null-style, fake-stream, or unjustified
  behavior-changing shapes.

## Suggested eval name

`short-kebab-case-name`
~~~

## Quality Bar

- Keep the issue self-contained. Include enough code and prompt context that a
  maintainer can build the eval without knowing Symphony for Trello.
- For missed-trigger cases, include both prompts: the original implementation
  prompt and the later refactor, review, or capture prompt that exposed the
  miss.
- For activation/capture-only misses, explicitly state that the implementation
  was already preferred, identify the missing trigger or capture behavior, and
  do not invent a code defect, intermediate implementation, or maintainer
  correction.
- For non-equivalent refactors, include the full correction chain: original
  code, prompt-produced weaker code, later correction/revert prompt, and final
  code.
- When the implementation is actually weaker, explain why even when it is
  functionally correct.
- Explain behavior-equivalence explicitly. Do not treat fallback laziness,
  exception behavior, side-effect order, nullability, prompt order, or checked
  boundaries as incidental unless the surrounding code proves the change is
  harmless.
- Prefer concrete Optional mechanics over vague style claims.
- Include the relevant Java baseline when it determines which Optional APIs are
  available.
- Do not paste secrets, credentials, private host paths, Trello card ids, or
  deployment details.
- Keep large unrelated class bodies out of the issue. Include the smallest code
  needed to show the before/intermediate/final contrast.
- After creating or updating the issue, mention the issue URL in the work
  summary for the Symphony for Trello change.
