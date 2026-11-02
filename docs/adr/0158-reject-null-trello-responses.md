---
status: accepted
date: 2026-11-01
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #833](https://github.com/martin-francois/symphony-trello/issues/833)"
  - "[GitHub issue #835](https://github.com/martin-francois/symphony-trello/issues/835)"
informed: [Future maintainers, Contributors]
---

# Reject null Trello response containers at the request boundary

## Context and Problem Statement

Jackson parses a JSON `null` response into a null map or list. Trello response arrays can also
contain null elements. Downstream consumers assume non-null containers and maps, so these shapes
throw unchecked exceptions, including during lookups that otherwise isolate failures per card.

## Decision Drivers

* Report unknown Trello payloads through the existing typed error contract.
* Preserve successful results when another card lookup fails.
* Protect every map and list consumer through the shared response boundary.
* Keep null response validation separate from per-card normalization policy.

## Considered Options

* Reject null response containers and null list elements at the response boundary.
* Skip null elements and treat null response containers as empty data.
* Guard each mapping method separately.

## Decision Outcome

Chosen option: reject null response containers and null list elements at the response boundary.

The shared typed request method rejects a null decoded result with `trello_unknown_payload`.
The list reader rejects the entire response when any element is null, before consumers receive it.
Per-card state and workpad lookups retain their existing typed failure handling. Candidate and
terminal fetches fail as a whole for these invalid lists. This does not change successful responses,
request retries, transport errors, or malformed non-null card normalization.

This is a compatible correction of unsupported payload handling. There is no migration, CLI,
workflow, deployment, credential, or companion-repository change.

### Consequences

* Good, because all map reads and writes share the root-null guard.
* Good, because board lists, card lists, and other list readers share element validation.
* Good, because one failed card lookup leaves other lookup results available.
* Bad, because a single null element prevents valid elements in that response from being used.

### Confirmation

`TrelloClientTest` exercises root-null card, workpad, board, candidate, and board-list responses,
plus null-only and mixed valid/null candidate and board lists. The focused tests must reproduce
unchecked failures before the fix and typed failures after it. Run the normal Maven verification
suite before publication.

## Pros and Cons of the Options

### Reject null response containers and null list elements at the response boundary

Validate decoded results once in the shared request method and validate list elements in the list
reader. Reject invalid payloads with `trello_unknown_payload`.

* Good, because consumers receive the containers and maps their contracts require.
* Good, because it does not duplicate checks in individual mapping methods.
* Bad, because valid entries in a malformed list are unavailable for that fetch.

### Skip null elements and treat null response containers as empty data

Filter null entries out of lists and replace null containers with empty maps or lists.

* Good, because remaining list entries could still be processed.
* Bad, because empty data hides an unknown response and could be mistaken for a successful fetch.
* Bad, because skipping cards selects a broader partial-success policy covered separately by
  [GitHub issue #835](https://github.com/martin-francois/symphony-trello/issues/835).

### Guard each mapping method separately

Add null checks to card normalization, board mapping, list mapping, and every other response consumer.

* Good, because each consumer could select its own fallback.
* Bad, because the response contract would be duplicated and new consumers could miss validation.

## More Information

[ADR 0116](0116-fuzz-trello-card-payload-mapping.md) keeps HTTP response validation
outside the card payload fuzzer. The fake-server regression tests cover that boundary without
requiring real Trello access.
