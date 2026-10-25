---
status: accepted
date: 2026-10-04
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #25](https://github.com/martin-francois/symphony-trello/issues/25)"
  - "[GitHub issue #73](https://github.com/martin-francois/symphony-trello/issues/73)"
  - "[GitHub issue #454](https://github.com/martin-francois/symphony-trello/issues/454)"
  - "[ADR 0003](0003-scoped-trello-handoff-tools.md)"
  - "[ADR 0015](0015-blocked-trello-handoff-list.md)"
  - "[SPEC.md](../../SPEC.md)"
informed: [Future maintainers, Contributors, Board users]
---

# Create Out-Of-Scope Follow-Up Cards Through One Opt-In Scoped Tool

## Context and Problem Statement

Codex sometimes finds useful work that is outside the current Trello card's acceptance criteria.
If it does that work anyway, the card grows without anyone deciding it should. If it only mentions
the work in the workpad, the note is easy to lose. The board needs a separate card that a person
can prioritize.

Creating a card is a wider Trello write than the existing handoff tools, which only change the
current card. Trello Free also has no typed card relationships: an attached card link does not say
whether one card blocks the other or is only related. The prerequisite checklist convention from
[GitHub issue #73](https://github.com/martin-francois/symphony-trello/issues/73) covers true
prerequisites, but nothing records a follow-up that has no required order.

How should Codex create follow-up cards, where do they go, how is the relationship recorded, and
what keeps a confused run from flooding the board?

## Decision Drivers

* Keep the current card's scope fixed while still making follow-up work visible.
* Keep the write opt-in, on the configured board, and bounded in count and rate.
* Never let Codex choose a card ID, list ID, board, or label.
* Work on a Trello Free Workspace without Custom Fields, mirror cards, or Advanced Checklists.
* Record relationship meaning explicitly on both cards and treat attachments as plain links.
* Reuse the prerequisite checklist convention for true prerequisites instead of inventing a second
  blocker format.
* Let a person decide when follow-up work starts.
* Recover from a failure part way through without deleting anything.

## Considered Options

* Codex writes follow-ups through generic `trello_rest` or several existing tools.
* One scoped `trello_create_follow_up_card` tool with Java-owned policy.
* Codex lists follow-ups only in the workpad and a person creates the cards.

For the relationship record, these options were considered:

* A Symphony metadata footer at the end of the follow-up card description.
* Trello Custom Fields.
* Relationship labels as the source of truth.
* The attachment between the two cards, with a special attachment name.

## Decision Outcome

Chosen option: "One scoped `trello_create_follow_up_card` tool with Java-owned policy", with the
relationship recorded in a Symphony metadata footer and prerequisites recorded with the existing
checklist convention.

The tool is advertised only when `trello_tools.enabled`, `trello_tools.allow_writes`,
`trello_tools.allow_url_attachments`, `trello_tools.allow_comments`, and
`trello_tools.follow_up_cards.enabled` are true. The config lives under `trello_tools` because the
tool is part of the same permission model: it also needs `allow_url_attachments`, prerequisite
relationships need `allow_checklists`, the optional blocked move uses the move allowlist, and the
blocked comment needs `allow_comments`. The tool never deletes anything, so
`allow_destructive_operations` does not apply.

Guided setup asks for an explicit choice during board creation, import, and reconfiguration. A new
workflow defaults to No; an existing workflow keeps its choice on Enter. Yes enables the feature
and all permissions needed for every relationship. No disables only follow-up creation. This avoids
silently revoking permissions used by another feature. Non-interactive setup preserves existing
settings and never opts a new workflow in. GitHub upgrades preserve custom move allowlists too;
creating the human approval list does not grant an agent permission to move cards there.
Destination, limits, and move lists remain separate
configuration choices.

The agent classifies findings against the actual target branch before filing them. Regressions
remain part of the current card, including the additional damage from worsening an existing
problem. A follow-up records the remaining pre-existing work with enough evidence and commands for
another agent to reproduce it without this session or machine. Missing baseline proof stays a
workpad verification gap. The workflow and handoff skills carry this policy; the tool schema explains
the required description. The server cannot verify a Git comparison from prose, so it validates the
argument structure without claiming to attest that evidence.

Codex supplies only a title, a description, acceptance criteria, and one of three relationships:
`related`, `follow_up_waits_for_current`, or `current_waits_for_follow_up`. Java chooses the rest:

* The card goes to the bottom of `Inbox` by default, or the configured `list_name` or `list_id`.
  Java refuses an active or terminal destination, so the follow-up waits until a person moves it.
  This is the human approval step; no separate approval flow is needed.
* The card gets the `follow-up` label by default, plus an optional label per relationship. A
  missing label is created on the board with the color `sky`, because Trello needs a color when it
  creates a label. Labels are only visual; Java never reads meaning from them.
* Both cards get a URL attachment to the other card.
* Symphony writes a managed `Follow-ups` workpad section on the source card, including the title,
  follow-up link, and reciprocal Symphony footer with the relationship worded from the source side.
  The three source-side sentences are defined in SPEC section 11.7. Agent workpad updates and usage
  notices preserve this section. The workpad tool rejects agent-supplied managed markers. A failed
  workpad write fails the tool call, and a retry repairs it without making another card.
  Malformed markers, duplicate workpads, and edited source-side relationship footers require manual
  repair; the follow-up tool never deletes
  a comment to resolve them.
* The last paragraph of the follow-up description is
  `_Managed by Symphony · Follow-up of [the source card](https://trello.com/c/<shortLink>) · <relationship text>_`.
  This exact line is the accepted relationship convention. Any other link stays a plain reference.
* `follow_up_waits_for_current` adds the source card to a `Must finish first` checklist on the new
  card. `current_waits_for_follow_up` adds the new card to a `Must finish first` checklist on the
  source card. Both use the existing prerequisite convention, so the scheduler already enforces
  them.
* With `move_current_card_to_blocked: true`, `current_waits_for_follow_up` also writes a
  `Blocked by` comment and moves the source card to `tracker.blocked_state`. Both happen only in
  the call that adds the prerequisite item, so a retry does not repeat the comment or move a card
  a person has unblocked again. The comment uses the
  exact blocker handoff format, so the stale-blocker recheck from
  [ADR 0065](0065-managed-stale-blocker-recheck-status.md) picks it up when the card comes back.

Bounds: the tool finds earlier follow-ups by reading open cards on the board and parsing their
footers. A matching title for the same source card returns the existing card and adds any missing
link, label, prerequisite item, or workpad entry. A matching title plus an attachment to the source
in either direction also prevents duplicate creation. If its exact relationship footer is missing
or changed, the tool returns `unknown` and leaves both cards unchanged. It does not reconstruct a
relationship from the attachment. A source card can have at most
`max_cards_per_source_card` unfinished follow-ups (default 3), and the process creates at most
`max_cards_per_hour` (default 10). One process lock serializes the duplicate check and the create.

### Consequences

* Good, because the current card keeps its scope and the follow-up has its own card, label, and
  acceptance criteria.
* Good, because Codex cannot pick a card, list, board, or label, and tool arguments are checked
  before any Trello request.
* Good, because true prerequisites reuse the scheduler's existing checklist convention.
* Good, because the duplicate, per-card, and hourly bounds come from board state and a small
  in-memory window, so a retry does not create a second card.
* Good, because a failed write part way is repaired by calling the tool again; nothing is deleted.
* Bad, because editing a footer removes its machine-readable relationship. A matching title and
  attachment still prevent a duplicate, but Symphony requires a person to review the relationship.
  Only exact footers count toward the per-source limit.
* Bad, because duplicate detection reads open cards only. If a person archives a follow-up, the same
  source card can create it again. [GitHub issue #857](https://github.com/martin-francois/symphony-trello/issues/857)
  tracks closing this gap.
* Bad, because the hourly limit resets when the process restarts.
* Bad, because creating a card, labels, links, and checklists takes several Trello requests, so a
  failure can leave a partly linked card until the next call repairs it.

### Confirmation

* `TrelloFollowUpCardToolTest` drives the tool through `TrelloHandoffToolHandler` against the
  stateful `FakeTrelloBoard` and checks the resulting board: list, labels, both attachments, the
  reciprocal footer and managed workpad, preservation during agent and usage updates, prerequisite
  checklists, the blocked move, duplicate and retry handling after edited footers, the per-card
  and hourly limits, two concurrent calls for the same title, and that disabled settings, bad
  destinations, and bad arguments make no write.
* `ConfigResolverTest` checks the `trello_tools.follow_up_cards` defaults and rejected values.
* `SPEC.md` Section 11.7 is the contract. Section 5.3.7 lists the config fields.
* The generated workflow and `WORKFLOW.example.md` contain the `Out-Of-Scope Follow-Up Work`
  section, checked by `TrelloBoardSetupTest` and `WorkflowConfigPromptTest`.

## Pros and Cons of the Options

### Codex writes follow-ups through generic `trello_rest` or several existing tools

Codex would create the card with raw Trello REST calls, or Symphony would add a generic
create-card tool and Codex would link and label the cards with separate tool calls.

* Good, because it needs less Java code.
* Bad, because a raw create-card call can target any list or board the token can reach.
* Bad, because the relationship format, labels, and limits would depend on Codex following prompt
  text on every run.
* Bad, because a retry in the middle of the sequence has no reliable way to find the card it
  already made.

### One scoped `trello_create_follow_up_card` tool with Java-owned policy

One tool call creates and links the card. Java owns the destination, labels, relationship records,
limits, and duplicate handling.

* Good, because the policy is in one place and tested against a fake board.
* Good, because it matches the typed-tool approach of
  [ADR 0003](0003-scoped-trello-handoff-tools.md).
* Bad, because it is the first typed tool that writes outside the current card, so it needs its own
  opt-in and bounds.

### Codex lists follow-ups only in the workpad and a person creates the cards

The workflow tells Codex to list out-of-scope work under a heading, and a person turns it into
cards.

* Good, because Symphony creates nothing on its own.
* Bad, because notes in a long workpad are easy to miss, which is the problem the issue describes.
* Neutral, because this remains the fallback when the tool is disabled or fails.

### A Symphony metadata footer at the end of the follow-up card description

The last description paragraph names the source card and the relationship in plain words with the
`Managed by Symphony` attribution.

* Good, because board users can read it and it works on Trello Free.
* Good, because it has the same shape as the other `Managed by Symphony` lines.
* Bad, because anyone who can edit the card can change it; a changed footer then means "no
  relationship", which is the safe reading.

### Trello Custom Fields

A custom field on each card would store the source card and relationship.

* Good, because the value is structured.
* Bad, because Custom Fields need a paid Trello workspace, and the default must work on Trello Free.

### Relationship labels as the source of truth

Labels such as `Related` or `Must finish first` would carry the relationship.

* Good, because labels show on the card front.
* Bad, because labels can be renamed or deleted, and a label cannot say which card is the source.

### The attachment between the two cards, with a special attachment name

The attachment name, such as `Follow-up of: <title>`, would carry the relationship.

* Good, because the attachment is already there.
* Bad, because people attach card links for many reasons, and the issue requires that manually
  linked cards never get relationship meaning.

## More Information

The prerequisite checklist convention is described in `SPEC.md` Section 11.4. Richer prerequisite
formats are tracked in
[GitHub issue #454](https://github.com/martin-francois/symphony-trello/issues/454); this tool uses
only the convention that is implemented today.

## Argument validation integration

The follow-up tool uses the shared definition registry from ADR 0088. Its advertised schema also
validates runtime arguments. Feature, URL attachment, and comment permissions are checked before
the schema, so a disabled tool keeps its policy error even for malformed input. Schema violations
return `invalid_tool_arguments`; domain checks such as forged metadata still return
`invalid_follow_up_card`. The refusal tests assert that neither path sends a Trello request.
