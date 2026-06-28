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

When the stream improvement is prompted by a reviewer or user after Codex
introduced the weaker stream shape, also capture the earlier prompt that caused
Codex to write that shape. The eval issue must make the trigger failure
explicit: the Streams skill should have activated during the original
implementation prompt, not only during the later stream-refactor request.

When a stream refactor is later found to be non-equivalent, over-allocated,
over-abstracted, or worse than the original code, capture the full correction
chain. Include the original code, the prompt that produced the weaker stream
code, the weaker code, the later correction/revert prompt, and the final code.
The eval must teach the skill to preserve behavior by default, and to call out
any mutability, ordering, laziness, parser, exception, allocation, or
side-effect change explicitly before recommending it.

## When To Use

Use this skill whenever you:

- refactor Java code to use streams more clearly, lazily, or safely;
- replace a loop with a stream or a stream with a clearer stream shape;
- fix stream short-circuiting, ordering, allocation, collector, Optional, or
  pipeline readability problems;
- accept a user-provided streams refactor;
- correct a streams refactor after review feedback.
- revert or repair a stream refactor because it changed behavior, allocation,
  mutability, ordering, laziness, parser semantics, or side-effect boundaries.

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

3. If the weaker stream code was agent-produced earlier in the same transcript
   or PR, inspect the transcript, commit history, review thread, or issue prompt
   to recover:

   - the code before that earlier prompt ran;
   - the earlier prompt that should have triggered the Streams skill;
   - the agent-produced stream code that later needed correction.

   If the exact earlier prompt is unavailable, state that explicitly and use the
   nearest recoverable prompt or commit message. Do not stop at the later
   "please refactor/use streams" prompt when the useful eval is a missed-trigger
   case.

4. Capture the intermediate implementation when there was one. This is the code
   that was produced before the better version, or the code the user/reviewer
   corrected.

5. If the stream refactor changed behavior or was later judged worse, capture
   the correction prompt and explain the equivalence failure. Include both:

   - the behavior-preserving alternative the skill should have suggested first;
   - any behavior-changing alternative only when the available context proves
     the behavior change is safe or desirable.

   If the context is insufficient to prove that a behavior-changing refactor is
   safe, the desired eval behavior should reward asking for confirmation or
   choosing the behavior-preserving shape.

6. Capture the final preferred code.

7. Search for an existing Java Streams skill issue for the same pattern:

   ```bash
   gh issue list --repo martinfrancois/java-streams-skill --state open --search '<pattern keywords>'
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
     --repo martinfrancois/java-streams-skill \
     --title 'Add eval for <stream pattern>' \
     --body-file /tmp/java-streams-eval-issue.md
   ```

9. Add or update a temporary override in `docs/agents/java-style.md` or the
   narrowest relevant agent-doc page for every eval issue that captures bad,
   missing, or unreliable Streams-skill behavior observed in Symphony for
   Trello. Link the issue from the override. The override must say to check the
   linked issue state at most once per turn before applying it, and to remove
   the override when the linked issue is closed. Do not leave agents relying
   only on the future upstream skill fix when the current repo has already
   observed the bad behavior. If an existing temporary override already covers
   the issue, update that override or record in the eval comment which override
   covers it instead of adding a duplicate.

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

List the relevant user, review, or product requirements that caused the weaker
stream code to be written. If this is a missed-trigger case, this is the
original implementation prompt, not the later refactor request.

## Later prompt that exposed the issue

Use this section when a reviewer or user later asked to use the Streams skill or
corrected the implementation. Explain that the later prompt is useful context
but should not be the only eval trigger.

## Prompt-produced code before maintainer correction

Use this section when an intermediate or rejected implementation exists.
Explain that it may be correct but has a streams/readability/performance issue,
or that it is not behavior-equivalent.

```java
// intermediate code
```

## Why the prompt-produced code is bad

Explain the concrete defect. For short-circuiting cases, state whether work
happens inside or before the lazy stream pipeline.

## Behavior-equivalence analysis

Use this section when the refactor changes or might change mutability, ordering,
allocation, laziness, parser semantics, exception behavior, or side-effect
boundaries. State whether the behavior change is safe, unsafe, or not provable
from local context. Include the behavior-preserving alternative when the final
preferred code intentionally changes behavior.

## Maintainer-preferred code

```java
// final preferred code
```

## Why the replacement is better

Tie the final code shape to the concrete stream behavior: laziness,
short-circuiting, encounter order, allocation, collector choice, or readability.

## Desired eval behavior

- Reward the intended stream shape.
- Reward triggering the Streams skill during the original implementation prompt
  when it asks for stream-relevant collection processing, even if the prompt
  does not explicitly name the skill.
- Reward the explanation that distinguishes correctness from maintainability.
- Reward preservation of the product behavior.
- Reward identifying behavior-changing stream suggestions and either choosing
  the behavior-preserving implementation or explicitly justifying why the change
  is safe.

## Anti-patterns the eval should reject

- List misleading, over-eager, over-abstracted, or unjustified
  behavior-changing shapes.

## Suggested eval name

`short-kebab-case-name`
~~~

## Quality Bar

- Keep the issue self-contained. Include enough code and prompt context that a
  maintainer can build the eval without knowing Symphony for Trello.
- For missed-trigger cases, include both prompts: the original prompt that
  produced the weak stream code and the later refactor/review prompt that
  exposed it.
- For non-equivalent refactors, include the full correction chain: original
  code, prompt-produced weaker code, later correction/revert prompt, and final
  code.
- Explain why the weaker code is weaker even when it is functionally correct.
- Explain behavior-equivalence explicitly. Do not treat `.toList()` mutability,
  encounter order, duplicate handling, parser splitting, laziness, exception
  behavior, or side effects as incidental unless the surrounding code proves the
  change is harmless.
- Prefer concrete stream mechanics over vague style claims.
- Do not paste secrets, credentials, private host paths, Trello card ids, or
  deployment details.
- Keep large unrelated class bodies out of the issue. Include the smallest code
  needed to show the before/intermediate/final contrast.
- After creating or updating the issue, mention the issue URL in the work
  summary for the Symphony for Trello change.
