---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, GitHub issue #22]
informed: [Future maintainers]
---

# Store the Codex Workpad in One Trello Comment

## Context and Problem Statement

Upstream Symphony keeps one durable workpad comment on the tracker item. Symphony for Trello needs
the same durable place for plans, acceptance criteria, validation evidence, blockers, and handoff
notes without creating a new progress comment on every run.

Where should the Trello workpad live so it is visible, reusable across retries and rework, and not
surprising to Trello users?

## Decision Drivers

* Stay close to the upstream Symphony workpad model.
* Avoid editing user-authored card descriptions by default.
* Avoid duplicate progress comments.
* Keep Trello writes scoped to the current card.
* Keep private host paths and local internals out of shared Trello text by default.

## Considered Options

* Single marker comment starting with `## Codex Workpad`.
* `## Codex Workpad` section in the card description.
* Trello checklists for plan, acceptance criteria, and validation.

## Decision Outcome

Chosen option: "Single marker comment starting with `## Codex Workpad`", because it matches the
upstream workpad behavior most closely while leaving the user-authored card description alone.

The Java implementation exposes `trello_upsert_workpad`. The tool uses a deeper current-card
comment lookup than the normal prompt refresh, includes Trello action ids in that lookup, updates
the existing marker comment when present, and creates it when missing. Generated workflows instruct
Codex to keep the workpad current and to avoid private host paths.
Normal card prompts still use a small recent-comment window, but include an older workpad marker
comment when it has fallen outside that window.

### Consequences

* Good, because humans and later Codex sessions have one place to inspect current progress.
* Good, because normal handoff and blocker comments can stay short and visible.
* Good, because normal card prompts still use a small recent-comment window while preserving the
  durable workpad context.
* Good, because the tool is scoped to the current card instead of exposing broad Trello writes.
* Bad, because updating a Trello comment depends on the token being allowed to edit that comment.
* Bad, because Symphony must fail visibly instead of creating a duplicate when the fetched comment
  window is full and an older workpad may exist outside that window.

### Confirmation

Run `./mvnw -q spotless:check verify`. Tests should prove the workpad tool creates a marker comment
when missing and updates the existing marker comment instead of creating a duplicate.

## Pros and Cons of the Options

### Single Marker Comment Starting with `## Codex Workpad`

Codex creates or updates one Trello comment identified by a stable Markdown heading.

* Good, because it mirrors upstream Symphony's persistent tracker comment.
* Good, because it does not modify the card description.
* Good, because the marker is simple to detect from Trello comment actions.
* Bad, because Trello comment update permissions can fail if the comment was created by a different
  account or token.

### `## Codex Workpad` Section in the Card Description

Codex updates a dedicated section in the Trello card description.

* Good, because the workpad is very visible.
* Bad, because it edits user-authored task text and can create surprising merge-style conflicts with
  human card edits.

### Trello Checklists for Plan, Acceptance Criteria, and Validation

Codex stores structured progress in Trello checklists.

* Good, because checklists are Trello-native and visible on the card.
* Bad, because they are weaker for nuanced notes, blockers, evidence, and handoff details.
* Bad, because they are better reserved for user task structure unless a workflow explicitly chooses
  checklist automation.

## More Information

`trello_upsert_workpad` requires `trello_tools.enabled`, `trello_tools.allow_writes`, and
`trello_tools.allow_comments`.
