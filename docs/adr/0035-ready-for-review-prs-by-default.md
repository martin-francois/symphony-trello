---
status: accepted
date: 2026-05-07
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, generated workflow, repository-local Codex skills, live Trello handoff]
informed: [Future maintainers]
---

# Create Ready-for-Review Pull Requests by Default

## Context and Problem Statement

Symphony for Trello moves repository-changing work to `Human Review` when a person can review the
result. A live run opened a draft pull request and then moved the card to `Human Review`. That was
surprising because draft pull requests suppress normal review signals in common GitHub tooling and
do not clearly mean the work is ready for a person.

What pull request state should generated workflows use by default?

## Decision Drivers

* Keep `Human Review` aligned with work that is ready for a person.
* Avoid hidden draft defaults from generic GitHub helper skills.
* Still allow draft pull requests when a card asks for that workflow.
* Keep the Java scheduler focused on Trello orchestration instead of GitHub PR state management.

## Considered Options

* Create ready-for-review pull requests by default and require explicit card text for draft PRs.
* Keep the helper default and allow Codex to create draft PRs unless told otherwise.
* Add Java service logic that converts draft pull requests to ready-for-review before handoff.

## Decision Outcome

Chosen option: "Create ready-for-review pull requests by default and require explicit card text for
draft PRs", because it matches the meaning of `Human Review` and keeps the behavior in generated
workflow and skill instructions where PR publication already lives.

Generated workflows now tell Codex to create ready-for-review, non-draft pull requests by default.
The `push-pr` skill says not to pass `--draft` unless the Trello card explicitly asks for a draft PR,
and to mark an existing draft PR ready for review before handoff when draft was not requested.

### Consequences

* Good, because cards in `Human Review` point to PRs that are ready for review by default.
* Good, because draft PRs remain available for cards that explicitly need them.
* Good, because the scheduler does not need repository-specific GitHub behavior.
* Bad, because this still depends on Codex following generated workflow and skill instructions.

### Confirmation

Run `./mvnw -q -Dtest=CodexSkillStructureTest,TrelloBoardSetupTest test` and confirm generated
workflows and repository-local skills require non-draft PRs by default.

For a live repository-changing card, confirm the PR is not draft before the card moves to
`Human Review`, unless the card explicitly requested a draft PR.

## Pros and Cons of the Options

### Create ready-for-review pull requests by default and require explicit card text for draft PRs

Codex publishes normal reviewable pull requests unless the Trello card asks for draft.

* Good, because this matches `Human Review`.
* Good, because it is easy for users to opt into draft when needed.
* Bad, because prompt/skill instructions are softer enforcement than service code.

### Keep the helper default and allow Codex to create draft PRs unless told otherwise

Codex follows whatever default the active GitHub helper uses.

* Good, because no new instruction is needed.
* Bad, because helper defaults can conflict with the Trello workflow.
* Bad, because a draft PR in `Human Review` is easy to misread as ready work.

### Add Java service logic that converts draft pull requests to ready-for-review before handoff

The scheduler would inspect GitHub PR state and update it.

* Good, because Java code can enforce the policy directly.
* Bad, because the scheduler does not own repository publication.
* Bad, because it would require more GitHub API scope and repository-specific logic.

## More Information

The repository-local `push-pr` skill owns PR publication. The generated workflow points Codex to
that skill and also states the non-draft default directly so external GitHub helper defaults do not
silently change the workflow behavior.
