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

The workflow gets a `github` section. `pull_request_mode` accepts `create` or `branch_only`.
Omitting it defaults to `create`; an explicit `null` fails configuration. `no_pr_label` defaults to
`No PR`; `""` or `null` disables the label. Java resolves a `pull_request_handoff` prompt variable
for every run:

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

The handoff uses labeled fields for the absolute checkout and proposed description paths, mode
source, branch, pushed commit, branch link and validation. The two paths are permitted in that
successful handoff and its workpad entry; filesystem blocker text keeps its redaction rules.
Before the review transition, Codex updates the current workpad status, replaces resolved blockers
with completed work and checks, and confirms that both evidence writes succeeded. This makes the
required evidence explicit while keeping checkout and publication work agent-owned.
The informational path field is `Task checkout:`. Bare `Checkout:` is reserved for explicit
repository-source selection; using it in a handoff would override a remote source with a disposable
worktree that terminal cleanup later removes.

A card in `Merging` that runs as `create` but still has no pull request after a branch-only
handoff, for example because a human removed the label and moved the card straight to `Merging`,
gets its pull request from the proposed description first and then takes the pull request merge
flow. On a `create` board, merging keeps going through a pull request, so branch protection and CI
still apply. Later PR creation extracts the title heading and writes the remaining description to a
separate temporary body file. Codex compares the published title and body with those intended values
before handoff, preserving repository-template reconciliation and the original uncommitted proposal.

Setup does not ask about the mode. The maintainer chose to activate the default `No PR` label in
newly generated GitHub workflows without an extra opt-in. This is a breaking change, as recorded in
[GitHub issue #31](https://github.com/martin-francois/symphony-trello/issues/31).
Workflows that are not regenerated or migrated and have no top-level `github` key keep their
handoff behavior because their bodies do not reference `pull_request_handoff`. An invalid `github`
value still fails configuration after upgrade, even without regeneration or migration.
A board that already uses a `No PR` label for another purpose
gets branch-only handoff for those cards once its workflow is regenerated or migrated. Renaming the
Trello label, or setting `github.no_pr_label` to another name or `""`, keeps the old meaning.

The top-level `github` key now requires an object with valid fields. A hand-written workflow that
used this key for a different value fails configuration. Remove or rename that unrelated key, or
replace it with the documented `github` settings before upgrading.

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
* Bad, because the branch-only handoff names the local checkout and proposal paths in Trello. Other
  handoff text avoids host paths, but these paths let the user find unmerged work that has no pull
  request.
* Bad, because a branch merge from `Merging` fails on repositories whose branch protection requires
  pull requests. The card then moves to `Blocked`.
* Bad, because the runtime now validates a top-level `github` key. A hand-written workflow that
  already used that key for something else fails configuration until the key is fixed.

Branch-only merge routing requires the latest handoff for the current work after any reopening
to record a pushed repository-changing branch, and checks linked and historical pull requests. This preserves the completed no-work path from ADR 0087 and
prevents a closed, unmerged pull request from becoming a direct branch merge.
Newer human feedback also stops a branch-only merge. Codex replies, refreshes the branch-only
handoff and returns the card to review, then waits for renewed approval with no newer feedback
before merging.
The refresh uses the complete handoff procedure. With comment tools available, a fresh ordinary
comment contains all labeled fields, so the reply to feedback does not replace the review evidence.
Enabled workpad updates and disabled-tool behavior follow the same procedure.
When both evidence tools are disabled, a person copies the complete final-response handoff onto the
Trello card before merge approval. Subsequent runs cannot use that earlier final response as evidence.
This final-response fallback satisfies the general workpad and comment prerequisites for human review.
Codex uses an available review move and ends the run with the complete handoff and copy request,
instead of waiting for disabled tools or repeating completed implementation. Durable card evidence
remains a prerequisite to merging. After a verified merge, disabled evidence tools permit reporting
the result in the final response and using the configured completion move. An enabled evidence write
that fails still blocks handoff. This distinction keeps disabled permissions usable without treating
an unsuccessful attempted write as delivered evidence.
Disabling comment writes also withholds the blocker-recheck tool. Generated recheck prerequisites
therefore apply only when that tool is advertised, matching the shipped handoff skill. A failed
advertised call still stops the attempt before any later Trello write. An unavailable tool does not
establish that no blocker exists or clear a known blocker.
GitHub issue assignment follows the same availability condition before implementation. If assignment
fails, record it in the workpad when available or in the final response otherwise. This preserves
best-effort assignment without requiring unavailable Trello tools.

An agent's own successful handoff move must not cancel its unfinished final response. The runtime
registers an authorized move's worker, turn, board and resolved list before sending the Trello request.
For that exact open destination, reconciliation allows the existing turn at most 60 seconds from the
first registration. Neither another move nor continuing events renew this window. The normal turn
timeout and stall policy remain in force. A failed move removes protection, and a successful handoff
prevents another turn even when a person reactivates the card before completion. An independent
deadline scheduler verifies the captured worker under the short state monitor and interrupts that
worker before any tracker lookup. It does not wait for the reconciliation operation lock. Runner
cancellation and cleanup continue outside the monitor; ordinary serialized worker exit or
reconciliation decides cleanup after cancellation. This keeps slow tracker requests from extending
agent execution without making concurrent cleanup decisions or canceling a replacement worker.

Failed or absent cleanup lookups move the canceled attempt into separate pending cleanup bookkeeping
and retain its card claim. Keeping it in `running` would misreport a live session, accrue runtime and
occupy unrelated dispatch capacity. Ordinary ticks retry pending lookups without restarting the
worker. Successful lookups establish whether to remove the original workspace or release only the
claim. Equivalent current tracker targets supply refreshed credentials; the original workspace
configuration still controls cleanup.

The backlog pauses new dispatch at 128 entries. Workers already running may finish and add entries,
so the maximum backlog is the threshold plus in-flight capacity. We retain unresolved cleanup rather
than evicting obligations. Below the threshold, normal concurrency remains available to unrelated
cards; a log message explains dispatch backpressure at the threshold. This bounded in-memory queue
requires no new operator setting or status-API field.

We reject a grace period for every inactive card because it delays externally requested stops.
Registering only after the HTTP response also leaves a race when Trello applies the move before the
response arrives. The chosen bounded window keeps the current turn's existing permissions; it does
not create a final-answer-only execution mode. Missing or archived cards, board changes and different
inactive destinations still stop the worker immediately. The tradeoff is up to 60 seconds of continued
turn execution after an owned handoff so its final evidence can finish. Cleanup bookkeeping remains
pending when a tracker request is blocked after cancellation. Deterministic protocol tests
force both move-response and final-message races; the disabled-evidence live check requires the full
completed message and successful turn completion.

Completing the protocol turn alone does not deliver its evidence: the Codex thread is ephemeral,
and ordinary card details no longer select a worker after it exits. For branch-only runs without
comment/workpad tools, retain the completed final response in a private JSON record below
`<workspace.root>/.symphony+final-responses/`, partitioned by hashes of the workflow/board and card.
The record includes card, worker, thread and turn identity and survives service restart and task
checkout cleanup. A new eligible attempt replaces the current record with a running state before
work begins. Attempts that no longer qualify for recording still invalidate the prior response. Only
an accepted non-empty completed final item and successful matching turn seal a completed response;
duplicate start events for that same turn do not discard it. Failures and interruptions cannot expose
the previous attempt as current.

The output namespace and tracker dispatch share the existing endpoint fingerprint implementation.
Equivalent URL spellings therefore identify the same current answer, including when the next attempt
disables local recording. This prevents a spelling change from leaving a stale completed answer at
the previously logged path. The extraction preserves the established tracker equality rules and
keeps raw canonical URLs private; distinct endpoint identities remain separate.

This local file gives the owner the exact text to copy onto Trello without adding a CLI command,
dashboard feature or new configuration. It remains outside the merge evidence contract. Atomic
replacement, POSIX private modes where supported, symlink and ancestor checks, a 1 MiB response limit
and retention of at most 128 completed records per namespace bound storage and disclosure. Active
attempts are not evicted. Persistence errors fail the attempt visibly without logging the response.
These checks do not defend against concurrent hostile access by the same operating-system user.
Private output permissions alone do not stop another user from replacing a writable ancestor.
POSIX ancestry therefore requires ownership by the process user or system root and verified sticky
protection for group-writable or other-writable directories. This accepts protected shared parents
such as `/tmp` while rejecting replaceable paths. Public NIO named attributes supply Unix owner and
mode metadata when needed; storage fails if the required metadata cannot be verified. Filesystems
without POSIX permissions retain the documented inherited-permission policy.
The retained local record introduces private data on disk; operators receive its location and cleanup
instructions in the operations guide.

The fetched card branch tip must match the handoff's recorded commit before either ancestry or merge
decisions. Changed tips require a complete refreshed handoff and renewed approval. Both the ancestry
check and merge use the recorded commit, so a later branch update cannot substitute unreviewed work.
A missing remote branch permits completion only when the recorded commit is verified on the target.

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
