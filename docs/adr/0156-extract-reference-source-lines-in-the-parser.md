---
status: accepted
date: 2026-10-31
decision-makers: [Contributors]
consulted:
  - "[GitHub issue #805](https://github.com/martin-francois/symphony-trello/issues/805)"
informed: [Maintainers, Contributors]
---

# Extract reference source lines in the parser

## Context and Problem Statement

`TrelloClient.ReferenceAccumulator` stores the whole input text for every card reference it finds.
A multiline description therefore repeats inside each reference bullet in generated prompts.
The same accumulator handles titles, comments, checklist items and attachments. Exact checklist
references are inserted before general URL references, so a newline-wrapped exact reference can
also retain multiple lines even when the general URL path is corrected.

## Decision Drivers

* Keep one reference bullet per line without losing the line that explains the reference.
* Preserve existing URL recognition, canonical URLs, lookup ordering and first-occurrence deduplication.
* Keep URL parsing in its owning parser.
* Avoid changing the canonical generated workflow and triggering unrelated board migrations.

## Considered Options

* Return the matching source line with each reference from `TrelloCardReferenceParser`.
* Split text or locate URLs again inside `TrelloClient`.
* Flatten or escape whole descriptions in the generated workflow template.

## Decision Outcome

Chosen option: return the matching source line from the parser, following the implementation
boundary requested in the issue.

The parser uses its existing URL ranges to locate the line containing each matched URL. Its
exact-reference path uses the same line extraction for bare checklist IDs and URLs surrounded by
whitespace. Line boundaries use Java's `\R` logical line separators, including CRLF as one
separator. Text within a line, including indentation and tabs, is preserved.

The client keeps its existing insertion order and first-occurrence deduplication. It stores the
parser's source line instead of the complete input. Existing reference-only parser APIs and the
canonical generated workflow remain unchanged. SPEC Section 4 records the source-line contract.

### Consequences

* Good, because all reference-bearing contexts share the correction.
* Good, because the source text uses the same match that supplies the lookup ID.
* Good, because already-generated workflows receive corrected context without migration.
* Bad, because source-line extraction adds work for each reference in a multiline input.

### Confirmation

`TrelloCardReferenceParserTest` verifies logical line separators, whitespace, multiple references on
one line and repeated occurrences. `TrelloClientTest` covers every source context, first-occurrence
deduplication and newline-wrapped exact checklist IDs and URLs. The existing
`relationship-context-deduplication-and-rendering` harness checks the generated prompt with fake
Trello and fake Codex, including each reference's exact source line.

## Pros and Cons of the Options

### Return source lines from the parser

The parser enriches a reference with the line containing its already-recognized match.

* Good, because URL recognition and source-line selection cannot disagree about a match.
* Good, because callers do not duplicate parsing rules.
* Bad, because there is an additional result shape for callers needing source context.

### Split or parse again in the client

The client divides each input into lines before looking for references or locates parsed URLs again.

* Good, because line handling stays next to prompt accumulation.
* Bad, because the client must preserve URL recognition and exact-reference semantics itself.

### Change the workflow template

The template flattens or escapes the complete source text when rendering each bullet.

* Good, because it can keep bullets on individual lines.
* Bad, because every reference still repeats unrelated source text.
* Bad, because changing the canonical workflow requires migration for existing boards.

## More Information

The reference-record contract is in [SPEC Section 4](../../SPEC.md#4-core-domain-model).
[The live bug-bash harness](../live-bugbash.md) documents the source-install rehearsal and its
fake-service isolation.
