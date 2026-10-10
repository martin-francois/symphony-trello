---
status: accepted
date: 2026-10-04
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #31](https://github.com/martin-francois/symphony-trello/issues/31)"
  - SPEC.md
  - generated workflow
  - repository-local Codex skills
informed: [Future maintainers]
---

# Branch-Only Pull Request Handoff

## Context and Problem Statement

Generated GitHub workflows treat `Human Review` as "a pull request is ready". Some cards should
still be implemented and pushed, but the user wants to read the branch, edit the proposed pull
request text, or decide later whether a pull request belongs upstream. Some boards want this for
every card.

[GitHub issue #31](https://github.com/martin-francois/symphony-trello/issues/31) asks for a
board-level branch-only mode and a configurable card label, default `No PR`. It leaves four choices
open: where the mode is resolved, whether a branch-only board accepts a per-card "create PR"
override, whether setup asks about the mode, and whether the default label is active without an
opt-in.

## Decision Drivers

* Keep repository work agent-owned. Java does not prepare checkouts, manage branches, or publish.
* Make the selected mode visible to Codex and to the user, so a card in `Human Review` without a
  pull request is not a surprise.
* Keep the shortest setup path unchanged.
* Keep existing workflows working as they do today.
* Keep the precedence between board default, card label, and a later request for a pull request
  easy to explain and to test.

## Considered Options

* Resolve the mode in Java from a `github` workflow section and the card labels, render it into the
  prompt, and let the generated workflow describe the git steps.
* Put the label name into the generated workflow text only and let Codex read `card.labels`.
* Let Java perform the branch-only push and merge.

For a branch-only board, the issue offered two policies:

* Strict board-level branch-only mode: no card creates a pull request until the workflow setting
  changes.
* Board default with a per-card "create PR" override signal.

## Decision Outcome

Chosen option: "Resolve the mode in Java from a `github` workflow section and the card labels, render
it into the prompt, and let the generated workflow describe the git steps", together with "Strict
board-level branch-only mode".

The workflow gets a `github` section with `pull_request_mode` (`create` or `branch_only`, default
`create`) and `no_pr_label` (default `No PR`; `""` or null turns it off). Java resolves a
`pull_request_handoff` prompt variable for every run:

1. A `branch_only` workflow is strict. No card on that board creates a pull request.
2. On a `create` workflow, a Trello comment that explicitly asks for a pull request, newer than the
   latest branch-only handoff, selects `create` for that run. Codex applies this rule because the
   comment is free text.
3. On a `create` workflow, the configured label selects `branch_only`.
4. Otherwise the run uses `create`.

Generated GitHub workflows write both keys explicitly and add a `Pull Request Handoff Mode` section.
The section tells Codex to run the local CI-equivalent checks, write the proposed title and
description to an untracked `PR.md` (or `PR-2.md`, `PR-3.md`, and so on), never stage it, push the
branch, and hand off with the checkout path, branch, branch link, and file path. The `push-pr` and
`land` skills describe the branch-only push and the branch merge from `Merging`.

Setup does not ask about the mode. The maintainer chose to activate the default `No PR` label in
newly generated GitHub workflows without an extra opt-in, and classified the change as compatible:
workflows that are not regenerated or migrated keep their current behavior because their bodies do
not reference `pull_request_handoff`. A board that already uses a `No PR` label for another purpose
gets branch-only handoff for those cards once its workflow is regenerated or migrated. Renaming the
Trello label, or setting `github.no_pr_label` to another name or `""`, keeps the old meaning.

### Consequences

* Good, because the prompt states the effective mode and its source, so the handoff can explain why
  no pull request exists.
* Good, because label matching uses the same normalization as other Trello labels and is unit-tested
  in Java instead of left to model judgment.
* Good, because the git steps stay in workflow text and skills, inside the existing repository
  boundary.
* Good, because a strict branch-only board has one rule that no card text can override, which suits
  boards that must never publish pull requests automatically.
* Bad, because a branch-only board cannot open a pull request for a single card. The user opens it
  by hand from `PR.md`, or changes the workflow setting.
* Bad, because the free-text "create a PR" comment override still depends on Codex reading the
  comments correctly.
* Bad, because the branch-only handoff names the local checkout path in Trello. Other handoff text
  avoids host paths, but without the path the user cannot find unmerged work that has no pull
  request.
* Bad, because a branch merge from `Merging` fails on repositories whose branch protection requires
  pull requests. The card then moves to `Blocked`.
* Bad, because the runtime now validates a top-level `github` key. A hand-written workflow that
  already used that key for something else fails configuration until the key is fixed.

### Confirmation

Run `./mvnw -q -Dtest=PullRequestHandoffTest,ConfigResolverTest,TrelloBoardSetupTest,WorkflowConfigPromptTest,SymphonyOrchestratorTest test`.
The tests cover the precedence table, the `github` settings, the generated front matter and
instructions, the rendered prompt for each mode, and the dispatched prompt.

The live checks for the branch-only flow are in the live E2E guide
(`docs/live-e2e.md`, "Regression Scenario: Branch-Only Handoff").

## Pros and Cons of the Options

### Resolve the mode in Java and render it into the prompt

`ConfigResolver` reads the `github` section, and the prompt renderer adds a `pull_request_handoff`
object computed from that section and the card labels. Workflow text and skills describe what
Codex does in each mode.

* Good, because the precedence is code with unit tests.
* Good, because hand-written workflows can use the same variable.
* Bad, because it adds a prompt variable and a workflow section to the Java contract.

### Put the label name into the generated workflow text only

The generator would write "if `card.labels` contains `no pr`" style instructions and let Codex
compare labels.

* Good, because no Java contract change is needed.
* Bad, because label normalization and precedence would depend on model judgment.
* Bad, because changing the label name would mean editing prose instead of one setting.

### Let Java perform the branch-only push and merge

Java would push branches, write `PR.md`, and merge from `Merging`.

* Good, because the steps would be enforced by code.
* Bad, because it reverses the repository boundary chosen when
  [GitHub issue #33](https://github.com/martin-francois/symphony-trello/issues/33) and the
  Java-managed checkout prototype were closed as not planned. Java does not own checkouts, branches,
  or publication.

### Strict board-level branch-only mode

A `branch_only` workflow never creates a pull request. A card or comment that asks for one gets a
branch-only handoff with a note on how to get a pull request.

* Good, because the rule is simple and safe for boards that must not publish pull requests.
* Bad, because one-off pull requests on such a board need a manual step or a workflow change.

### Board default with a per-card "create PR" override

A branch-only board would accept a label or comment that asks for a pull request on one card.

* Good, because it is more flexible.
* Bad, because it adds a second explicit card signal and another precedence rule.
* Bad, because free text on a card could publish a pull request on a board meant to never do so.

## More Information

[GitHub issue #34](https://github.com/martin-francois/symphony-trello/issues/34) plans optional
pre-push Codex review cycles. When that feature lands, it should apply to branch-only cards before
the push too, because the push is the last automated step before human review.

`SPEC.md` Section 19.6 holds the normative contract.
