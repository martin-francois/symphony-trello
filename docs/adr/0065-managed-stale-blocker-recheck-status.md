---
status: accepted
date: 2026-07-10
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #547](https://github.com/martin-francois/symphony-trello/issues/547)"
  - "[SPEC.md](../../SPEC.md)"
  - "[ADR 0058](0058-generated-workflow-repository-source-precedence.md)"
informed: [Future maintainers, Contributors]
---

# Manage Stale Blocker Recheck Status Through A Two-Phase Tool

## Context and Problem Statement

A Codex run can add an ordinary `Blocked:` Trello comment and move a card to `Blocked`. After an
operator fixes the problem and requeues the card, Symphony moves it to `In Progress` and renders the
old comments for the next run. The old blocker comment can remain the newest visible status, so the
card gives conflicting signals while work has resumed.

The agent has the semantic context needed to decide whether a repository, permission, requirement,
or other blocker is actually resolved. Java can classify comments and maintain one status safely,
but it cannot decide that an arbitrary blocker no longer applies. How should the generated workflow
communicate the recheck without claiming success too early or creating retry comment spam?

## Decision Drivers

* Never say that work resumed before the new run confirms the old blocker no longer applies.
* Recognize explicit blocker handoffs without treating ordinary human discussion as status.
* Preserve a newer ordinary human comment instead of scanning past it to an older blocker.
* Keep retries and continuation runs idempotent.
* Keep mutation policy in the generated workflow and scoped Trello tool boundary.
* Avoid requiring external Trello or GitHub resources in the regression suite.
* Preserve human comments, including comments that use a similar visible heading.

## Considered Options

* Add generated workflow instructions that use the ordinary add-comment tool.
* Add a two-phase current-card tool controlled by generated workflow instructions.
* Make the Java pickup transition decide and publish resumed work automatically.

## Decision Outcome

Chosen option: "Add a two-phase current-card tool controlled by generated workflow instructions",
because the workflow owns the semantic recheck while Java owns exact classification and idempotent
comment mutation.

The typed `trello_update_blocker_recheck_status` tool accepts `checking` and `resumed`. It reads a
deep current-card comment window. After excluding the workpad, Symphony prerequisite statuses, and
exact Symphony-managed blocker-recheck statuses, only the newest ordinary comment is considered. It
qualifies when its first non-blank line begins with `Blocked:` or `Blocked by ...`, without case
sensitivity. The tool does not scan past a newer ordinary comment.

The newest qualifying ordinary blocker comment is the comment being rechecked and remains unchanged.
`checking` creates or updates a separate Symphony-managed status; the absence of that status triggers
its creation and must not suppress the recheck.

`checking` binds one managed status to the qualifying blocker comment action. The first `resumed`
transition is rejected unless that action-bound episode has entered managed checking state. A retry
for the same action when it is already resumed succeeds idempotently without regression. A new
blocker comment has a new action ID and therefore starts a new episode in checking. The action ID
binds the managed status to the exact blocker comment being rechecked. Trello retains the managed
comment's original creation timestamp when it is edited, so timestamps cannot distinguish episodes.

Managed comments require an exact canonical human-readable footer. It labels the comment as
`Managed by Symphony` and links to the qualifying blocker comment on the current Trello card. The
link carries the source action ID needed for episode binding and gives a reviewer direct access to
the explanation being rechecked. Similar visible wording, a malformed link, or a link to another
card remains ordinary and is never edited or deleted by the tool.

The newest addressable exact managed comment is authoritative if duplicates exist. Duplicate
deletion follows `trello_tools.allow_destructive_operations`. Without that opt-in, the authoritative
comment shows manual-cleanup guidance. With it, the authoritative state is updated before duplicate
deletion. A full comment window without a managed footer fails closed because an older managed
comment could be outside the fetched window.

The generated workflow always calls `checking` before testing for a blocker because the rendered
prompt contains only recent comments while the tool reads the deep comment window. A no-blocker
result creates no managed status. It calls `resumed` only after the semantic recheck succeeds. A
still-blocked, not-yet-resumed episode follows the normal blocker handoff and remains in checking. An
already-resumed retry for the same action retains its last-confirmed state until a newer blocker
action starts a new episode.

An initial `checking` tool failure stops the attempt before blocker testing or another Trello write.
The failure is reported in the final response, and the next dispatched retry begins with `checking`
again. The ordinary blocked handoff is not used after this failure because its comment, workpad, or
move would violate the required write ordering.

### Consequences

* Good, because a stale blocker gets an immediate visible recheck state and a truthful resumed state.
* Good, because the tool enforces ordering even if a caller tries to request resumed state directly.
* Good, because action binding remains correct when editing does not change Trello comment creation
  time.
* Good, because retries reuse one managed comment and duplicate cleanup follows existing destructive
  policy.
* Good, because deterministic fake-Trello tests cover the repository-mismatch lifecycle without
  mutating real cards or repositories.
* Bad, because the workflow must make two tool calls around a semantic recheck.
* Bad, because a comment window containing 1,000 comments can fail closed until an operator removes
  enough old comments or finds the existing managed status.

### Confirmation

This decision is still implemented when:

* the generated workflow and shipped Trello handoff skill describe the exact newest-comment
  classifier and the checking-before-resumed order;
* tool tests cover repository mismatch, successful resume, still blocked, normal pickup, newer human
  comment supersession, retry, a new blocker after prior resume, duplicate policy, marker collision,
  and a full comment window;
* the first `resumed` transition fails without checking state bound to the newest qualifying blocker
  action, while a same-action already-resumed retry stays resumed;
* title-derived resumed text is one safe line with a bounded task summary; and
* `./mvnw -q spotless:check verify` passes.

## Pros and Cons of the Options

### Add Workflow Instructions With Ordinary Comments

Tell Codex to add a checking comment and later add a resumed comment with `trello_add_comment`.

* Good, because no new Java tool is needed.
* Good, because the agent retains semantic control.
* Bad, because retries can create duplicate comments.
* Bad, because Java cannot enforce checking-before-resumed order or exact comment classification.
* Bad, because the workflow has no reliable way to update the same Trello comment action.

### Add A Two-Phase Current-Card Tool

Let generated workflow instructions choose the semantic transition while a typed Java tool
classifies comments, binds state to a blocker action, and updates one managed status.

* Good, because semantic and mutation responsibilities stay at their appropriate boundaries.
* Good, because direct or stale resumed requests fail safely.
* Good, because action binding makes retry and new-blocker behavior deterministic.
* Bad, because the typed tool and generated instructions add a small lifecycle contract.

### Publish Resumed Work From Java Pickup

Have the tracker or orchestrator create and complete the status as part of card dispatch.

* Good, because initial status publication would not depend on agent instructions.
* Bad, because Java cannot know whether an arbitrary repository, permission, or requirement blocker
  is resolved.
* Bad, because it moves semantic card-mutation policy into the scheduler and conflicts with the
  agent-owned handoff boundary in `SPEC.md`.

## More Information

[GitHub issue #547](https://github.com/martin-francois/symphony-trello/issues/547) contains the
repository-mismatch example and compatibility decision. This is a compatible additive change; it
does not change existing configuration keys or require migration.
