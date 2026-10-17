---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #475](https://github.com/martin-francois/symphony-trello/issues/475)"
  - "[GitHub PR #474](https://github.com/martin-francois/symphony-trello/pull/474)"
  - "[SPEC.md](../../SPEC.md)"
  - "[Trello rate limits](https://developer.atlassian.com/cloud/trello/guides/rest-api/rate-limits/)"
  - "[Trello batch endpoint](https://developer.atlassian.com/cloud/trello/rest/api-group-batch/)"
informed: [Future maintainers, Contributors]
---

# Remember Prerequisite Status Lookups In Memory Per Comment Activity

## Context and Problem Statement

Symphony keeps one managed prerequisite status comment on a Trello card. The comment says that the
card waits for prerequisites, or that its prerequisites are resolved. To keep it truthful, every
candidate poll looks up that comment on each card in a blocker-enforced state. The lookup reads up
to 1,000 comment actions of the card in one request.

Before this decision, the poll repeated that lookup on every tick for every blocker-enforced card
that had comments or any checklist. Most of these lookups found nothing to change. A measurement
with the stateful fake board in the test suite used 66 cards: 40 queued cards with 30 comments each
(5 with a resolved status, 1 with a stale waiting status), 10 queued cards with an ordinary checklist
and no comments, 5 queued cards waiting on a prerequisite, the prerequisite card, and 10 cards in an
active list that is not blocker-enforced. Each tick sent 76 Trello requests, and 55 of them were
status lookups. A poll tick took about 1.8 seconds against the local fake, without network latency.
Trello allows 100 requests per 10 seconds for each token.

How can the poll stop repeating lookups whose answer cannot have changed, while stale waiting
status is still cleared and no comment spam appears?

## Decision Drivers

* Keep the managed status comment truthful when prerequisite checklist items are removed.
* Keep one managed status comment per card and update it in place.
* Spend fewer Trello requests per tick, and stay safe when Trello rate limits requests.
* Add no persistence and no new Trello-visible state.
* Keep the lookup limited to active candidate cards.

## Considered Options

* Remember each lookup in memory, keyed by the card's comment count and last-activity time.
* Skip the lookup when the card has no prerequisite checklist items.
* Group lookups through the Trello batch endpoint.
* Find status comments through Trello search or board-wide comment actions.
* Keep the lookup on every tick.

## Decision Outcome

Chosen option: "Remember each lookup in memory, keyed by the card's comment count and last-activity
time", because it removes the repeated lookups without persistence and without changing what board
users see.

`PrerequisiteStatusCommentMemo` remembers the managed status comment that the last lookup found on
each candidate card. The key is the comment badge count and `dateLastActivity` from the card list
that the candidate poll already reads. When both values are unchanged, the poll reuses the
remembered comment. The sync then compares the remembered text with the status it needs, as before:

* If the text already matches, no request is sent.
* If the text differs, Symphony updates the remembered comment in place. This is how a waiting
  status is cleared after someone removes the prerequisite checklist items.

Symphony forgets the remembered lookup before each of its own status writes, so the next poll reads
the comment again and confirms the write. Adding or deleting a comment changes the comment count, so
a status comment written by another run, or a deleted status comment, is found on the next poll.
The memo keeps entries only for the current active candidates. A restart starts with an empty memo.

Only the candidate poll uses the memo. The dispatch-time refresh reads only the 20 newest comments
and has no complete comment count, so it always looks up the status comment. It still forgets the
remembered lookup when it writes.

The same change stops the clearing lookup on cards without comments. Such a card cannot carry a
managed status comment. Before, any card with a checklist was looked up even without comments. A
waiting card without comments is still looked up before Symphony writes its first status comment.

With the same 66-card board, the first tick still sends 72 requests, including 45 lookups. The second
tick sends 27 requests and confirms the 6 status writes from the first tick. Every later tick sends 21
requests and no lookups, and takes about 60 milliseconds against the local fake.

### Consequences

* Good, because a poll of an unchanged board reads no comment actions for the status comment.
* Good, because a stale waiting status is still cleared on the first poll after the prerequisite
  items are removed, and no extra lookup is needed for that.
* Good, because nothing is persisted and the memo is bounded by the active candidates.
* Bad, because the first poll after a restart still pays one lookup per card with comments.
* Bad, because the memo cannot see a hand edit of a status comment if Trello leaves the card's comment
  count and `dateLastActivity` unchanged. The next status change on that card overwrites the edit, as
  it did before this decision.
* Neutral, because the memo is shared by every caller of `TrelloClient`. A lookup that runs while
  another thread writes the status does not replace the forgotten entry, so the next poll reads the
  comment again.

### Confirmation

This decision is still implemented when:

* `TrelloClientPrerequisiteStatusLookupTest` shows, across poll ticks against
  `FakeTrelloCommentBoard`, that a waiting status is written once, that an unchanged card is not
  looked up again, that removing prerequisite items clears the waiting status, that a new comment
  causes a new lookup, that a rate-limited status write is retried after a fresh lookup on the next
  poll, and that inactive, terminal, and commentless cards without prerequisite state are never
  looked up;
* `PrerequisiteStatusCommentMemoTest` shows that a lookup racing with a status write does not hide
  the written comment; and
* `./mvnw -q spotless:check verify` passes.

## Pros and Cons of the Options

### Remember Each Lookup In Memory, Keyed By The Card's Comment Count And Last-Activity Time

The poll stores the found status comment per card in memory, keyed by the comment badge count and
`dateLastActivity` from the card list. It reuses the stored answer while both values stay the same.

* Good, because steady-state polls send no status lookups.
* Good, because it needs no new Trello request and no persistence.
* Bad, because it adds in-memory state to `TrelloClient` that must stay correct under concurrent
  callers.

### Skip The Lookup When The Card Has No Prerequisite Checklist Items

The poll would look up the status comment only for cards that currently have prerequisite checklist
items.

* Good, because it is a one-line change with no state.
* Bad, because a card whose prerequisite items were removed keeps a stale waiting status. The issue
  and [GitHub PR #474](https://github.com/martin-francois/symphony-trello/pull/474) rule this out.

### Group Lookups Through The Trello Batch Endpoint

Trello's batch endpoint accepts up to 10 GET URLs in one call.

* Good, because it cuts HTTP round trips by up to 10 times.
* Bad, because the Trello documentation does not say whether a batch counts as one request or as
  several for rate limits, so the benefit cannot be confirmed.
* Bad, because it transfers the same comment payloads and adds per-URL error handling.

### Find Status Comments Through Trello Search Or Board-Wide Comment Actions

One board-wide request would find cards whose comments contain the status heading, either through
Trello search or through the newest comment actions of the board.

* Good, because it could replace many per-card lookups with one request.
* Bad, because board actions return only the newest actions, so an older status comment is missed.
* Bad, because Trello search depends on an index whose freshness and matching cannot be checked with
  the offline fake board.

### Keep The Lookup On Every Tick

The poll keeps reading up to 1,000 comment actions per blocker-enforced card on every tick.

* Good, because it has no state.
* Bad, because the measured board spends 55 of 76 requests per tick on lookups that change nothing.

## More Information

The measurement ran `TrelloClient.fetchCandidateCards` ten times against `FakeTrelloCommentBoard`
with `active_states` `Todo` and `In Progress`, `blocker_enforced_states` `Todo`, and
`terminal_states` `Done`. The timings are from a loopback fake. A real Trello request adds a network
round trip, so the saved requests matter more there.
