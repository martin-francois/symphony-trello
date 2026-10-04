---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude Code]
consulted: [SPEC.md, README, generated workflow prompt, land and trello-handoff skills, offline real-Codex replay]
informed: [Future maintainers]
---

# Close Merging Cards With Nothing to Merge

## Context and Problem Statement

GitHub-enabled boards place `Merging` between `Human Review` and `Done`. The README already says
that work without a pull request belongs in `Done` after human review, but `Merging` is the next
column, so people drop reviewed cards there.
[GitHub issue #667](https://github.com/martin-francois/symphony-trello/issues/667) reports that such
a card bounced back to `Human Review`.

A replay with real Codex against a local stand-in for the Trello API showed the mechanism. Symphony
dispatches the card from `Merging` because `Merging` is an active list. The generated prompt told
Codex to "start by determining the current Trello list", but it never rendered that list, and the
scoped Trello tools cannot read it. Codex treated the run as new work and called
`trello_move_current_card` with `Human Review`. In one replay it also posted its answer a second
time. Even with the list known, the merge rules
only covered pull requests: a missing PR meant `Blocked`, and the only path to `Done` was a
successful merge.

A card dropped straight on `Done` stayed there in the same replay. The scheduler never moves a card
out of a terminal list, and it stops a running agent once its card reaches one, so that path needed
no change.

How should a card with nothing to merge leave `Merging`, and how does Codex learn that the card is
in `Merging` at all?

## Decision Drivers

* A human moving reviewed work forward means the work is accepted. It should not come back.
* Cards that have a pull request must keep the existing merge flow, including `Blocked` for a
  missing or broken PR.
* `Merging` keeps its meaning as approval to merge a pull request.
* Prefer workflow and skill guidance where the merge flow already lives over new scheduler logic.

## Considered Options

* Render the current list in the generated prompt and close nothing-to-merge cards in `Done`.
* Return nothing-to-merge cards to `Human Review` with a comment that says to use `Done`.
* Let Symphony move cards out of `Merging` itself when no pull request is linked.
* Append the current list to the runtime context Symphony adds after every workflow prompt.

## Decision Outcome

Chosen option: "Render the current list in the generated prompt and close nothing-to-merge cards
in `Done`", because it fixes the reproduced mechanism with the smallest change and keeps merge
decisions in the generated workflow and the `land` skill.

Generated prompts now show `Current Trello list: {{ card.state }}` under the card title, matching
how upstream Symphony renders the issue state. The merge section and the `land` skill define
"nothing to merge": no PR for the card's work linked from the card description, comments, or
workpad, no open PR for the card's branch, and completed work that did not need one. Codex records that no merge was needed
and moves the card to the configured done list. It does not return the card to review or block it.
Work that needs a PR keeps the old rules. The review handoff tells the human that a card with
nothing to merge can go straight to `Done`.

### Consequences

* Good, because accepted work with nothing to merge reaches `Done` from either drop target.
* Good, because PR-backed cards in `Merging` still merge or block as before.
* Good, because every generated prompt now states the current list, which the routing text already
  assumed.
* Bad, because workflow files generated earlier keep their old prompt until someone adds the
  current-list line or regenerates them. The shipped skills update in every workspace on the next
  run, but without the list line Codex still cannot tell it is in `Merging`.
* Bad, because the decision relies on Codex following the prompt rather than on scheduler code.

### Confirmation

`TrelloBoardSetupTest` renders a generated workflow for a card in `Merging` and checks for the
current-list line, the nothing-to-merge rule, and the handoff hint. It also checks the shipped
`land` skill. To confirm the behavior end to end, run real Codex against a disposable board: hand a
question-only card to `Human Review`, move it to `Merging`, and check that it ends in `Done` with a
workpad note. A card whose PR link is broken must still end in `Blocked`.

## Pros and Cons of the Options

### Render the current list in the generated prompt and close nothing-to-merge cards in `Done`

The generated prompt renders `card.state`, and the merge rules gain a nothing-to-merge decision
that moves the card to the done list.

* Good, because it addresses both halves of the reproduced failure.
* Good, because the change stays in the prompt text and the skills that already own merging.
* Bad, because existing workflow files need a manual line or regeneration.

### Return nothing-to-merge cards to `Human Review` with a comment that says to use `Done`

Codex would explain the right drop target and leave the card for the human to move again.

* Good, because `Merging` would stay strictly about pull requests.
* Bad, because it keeps the bounce the issue asks to remove and costs the human another move.

### Let Symphony move cards out of `Merging` itself when no pull request is linked

The scheduler would inspect card text and comments for PR links and move the card without Codex.

* Good, because Java code enforces it without model judgement.
* Bad, because deciding whether work needed a PR takes judgement about the card and repository,
  which the scheduler does not have.
* Bad, because it adds GitHub-specific logic to the Trello scheduler.

### Append the current list to the runtime context Symphony adds after every workflow prompt

Symphony already appends a repository-source context after the persisted prompt. It could add the
current list there too.

* Good, because existing and hand-written workflow files would get the list without edits.
* Bad, because it mixes list routing into a context that exists for repository-source selection.
* Bad, because the list is ordinary card data that the template can already render.

## More Information

[GitHub issue #667](https://github.com/martin-francois/symphony-trello/issues/667) tracks the
reported bounce. [GitHub issue #31](https://github.com/martin-francois/symphony-trello/issues/31)
covers a separate branch-only handoff without a pull request and is not changed by this decision.
