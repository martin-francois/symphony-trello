---
status: accepted
date: 2026-05-04
decision-makers: [François Martin, Codex]
consulted: [SPEC.md]
informed: [Future maintainers]
---

# Expose Scoped Trello Handoff Tools Instead of Generic Trello Writes

## Context and Problem Statement

The Symphony specification keeps the orchestrator as the scheduling authority and describes Trello as
the work tracker. That boundary is easy to misread as a fully read-only workflow where operators must
watch logs and manually move cards after each agent run.

The coding agent is the component that knows whether requested work is reviewable, blocked, or unsafe
to hand off. How should the service let the agent report that state to Trello without exposing broad
write access?

## Decision Drivers

* Keep the orchestrator responsible for scheduling, not subjective handoff policy.
* Avoid forcing operators to watch logs and move cards manually.
* Limit Trello write access to the current card and configured destination lists.
* Keep dynamic tools easy to explain, test, and audit.
* Preserve a clear path for future Trello helper tools without opening a generic REST bridge.

## Considered Options

* Scoped typed tools for current-card comments and moves.
* Generic Trello REST dynamic tool.
* Read-only Trello workflow with manual operator handoff.
* Automatic orchestrator-driven card movement after every successful run.

## Decision Outcome

Chosen option: "Scoped typed tools for current-card comments and moves", because it gives the agent
enough authority to perform the common handoff while keeping writes narrow and workflow-configured.

### Consequences

* Good, because the agent can add review context and move the current card when the prompt policy
  says the work is ready.
* Good, because a worker cannot pass an arbitrary card id.
* Good, because allowed destination lists are explicit workflow configuration.
* Bad, because additional handoff actions, such as checklist updates or URL attachments, require new
  typed tools.
* Bad, because workflow prompts must clearly instruct agents when to use the handoff tools.

### Confirmation

Run `./mvnw -q spotless:check verify`. Tests should cover tool advertisement, current-card scoping,
comment permission checks, and allowed destination-list validation.

## Pros and Cons of the Options

### Scoped typed tools for current-card comments and moves

Expose `trello_add_comment` and `trello_move_current_card` only when write-capable Trello tools are
enabled in `WORKFLOW.md`.

* Good, because each tool has one clear purpose.
* Good, because scoping rules are simple enough to test exhaustively.
* Good, because operators can disable writes, comments, or moves independently.
* Neutral, because the tool set starts small.
* Bad, because future write use cases need explicit additions.

### Generic Trello REST dynamic tool

Expose a dynamic tool that lets the agent call arbitrary Trello REST endpoints.

* Good, because it would cover many Trello use cases immediately.
* Bad, because it is much harder to audit and safely constrain.
* Bad, because prompt mistakes could affect unrelated cards, lists, or boards.

### Read-only Trello workflow with manual operator handoff

Let Symphony read Trello cards and run Codex, but require humans to read logs and move cards.

* Good, because the Trello integration has no write risk.
* Bad, because it creates an inconvenient manual loop.
* Bad, because review context can be lost outside Trello.

### Automatic orchestrator-driven card movement after every successful run

Move cards from active to review automatically when the worker process exits successfully.

* Good, because it requires no agent tool call.
* Bad, because process success does not prove the work is ready for human review.
* Bad, because the orchestrator would own handoff policy that belongs in the workflow prompt.

## More Information

The initial typed tools are:

* `trello_add_comment`
* `trello_move_current_card`

The tools are advertised only when `trello_tools.enabled=true` and
`trello_tools.allow_writes=true`. Comment writes also require
`trello_tools.allow_comments=true`. Card moves require configured allowed destination list ids or
names, and the destination must resolve to an open list on the configured board.
