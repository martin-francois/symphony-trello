---
status: accepted
date: 2026-10-04
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #772](https://github.com/martin-francois/symphony-trello/issues/772)"
  - "[GitHub issue #690](https://github.com/martin-francois/symphony-trello/issues/690)"
  - "[GitHub issue #693](https://github.com/martin-francois/symphony-trello/issues/693)"
  - "[Jazzer FuzzedDataProvider](https://codeintelligencetesting.github.io/jazzer-docs/jazzer-api/com/code_intelligence/jazzer/api/FuzzedDataProvider.html)"
  - "[ADR 0061](0061-jazzer-and-oss-fuzz-readiness.md)"
  - "[ADR 0078](0078-github-actions-continuous-fuzzing.md)"
informed: [Future maintainers, Contributors]
---

# Fuzz Trello card payload mapping without HTTP

## Context and Problem Statement

`TrelloClient` turns Trello card JSON into `Card` objects. Board members write the card text,
comments, and checklist items in that JSON, and Trello decides its structure and field types. The
2026-10-03 hosted coverage report showed 0 of 430 `TrelloClient` branches covered by any fuzz
target, including the 72 branches in the methods that map one card payload.

How should a fuzz target reach that mapping, and where does its batch fuzzing time come from?

## Decision Drivers

* Each execution should be cheap, so the time goes to new inputs rather than to transport.
* JSON typing is part of the input. A field can arrive as a string, number, boolean, object, array,
  or `null`, and the target should see the same Java types production sees.
* Standalone targets must not open sockets or touch files.
* The continuous fuzzing chain in ADR 0078 has a fixed aggregate budget per cycle.
* A finding has to name the input that caused it and stay replayable in Maven.

## Considered Options

* Call the card mapping directly with a parsed payload.
* Fuzz through `fetchCandidateCards` against a local HTTP server.
* Feed card JSON to the existing `TrelloCardReferenceParserFuzzer`.
* Give the new target a full share and grow the aggregate budget.
* Give the new target a full share and halve the two reference-parsing targets.

## Decision Outcome

Chosen options: call the card mapping directly, and halve the two reference-parsing targets.

`TrelloClient.normalize` is package-private and static, and `TrelloClient.BoardContext.of` builds
the board context from a list of board lists. The test helper `TrelloCardPayloadFuzzInvariants` in
the same package reads the board state and priority labels from the end of the fuzz input, parses
the rest as a JSON object, and maps it. Its Jackson mapper copies the parsing settings of the mapper
Quarkus injects into `TrelloClient`, so the parsed map has the same types as in production.
`TrelloCardPayloadFuzzJsonParityTest` compares the two mappers' settings and fails if they drift. The
property is that mapping returns no card, a card with a non-blank id, name, and board id, or a
`TrelloException` with code `trello_unknown_payload`. Any other exception is a finding. Input that
Jackson cannot read as an object ends the execution, because the request layer reports it as
`trello_api_request` before mapping starts. A JSON `null` body also ends it. The request layer passes
that `null` on today, which [GitHub issue #833](https://github.com/martin-francois/symphony-trello/issues/833) tracks as a separate request-layer bug.

`TrelloCardPayloadFuzzer` is the standalone entry point and `TrelloCardPayloadFuzzTest` is the
JUnit `@FuzzTest`. Both call the helper. Its configuration resolver receives an empty environment
resolver so process variables and local dotenv files cannot influence an execution. The focused
`mappingDoesNotReadTheLocalEnvironment` test rejects any call to `LocalEnvironment`, and the JFR
isolation guard also rejects dotenv reads during seed replay. Maven copies the seed corpus in
`oss-fuzz/corpora/TrelloCardPayloadFuzzer` to the JUnit test's regression inputs, so normal test
runs replay every seed and crash input. `StandaloneFuzzerIsolationTest.trelloCardPayloadFuzzer`
also replays the corpus through the standalone entry point. The payload fuzz test uses
`FuzzIsolationExtension` and `@Isolated` to enforce the same file and socket isolation as the other
targets.

The batch matrix has six targets after the handoff-tool target lands.
`RepositorySourceFuzzer`, `TrelloCardPayloadFuzzer`, `TrelloHandoffToolFuzzer`, and
`WorkflowLoaderFuzzer` get a full share. `TrelloCardReferenceParserFuzzer` and
`TrelloChecklistClassifierFuzzer` get half a share each, because the review in [GitHub issue #690](https://github.com/martin-francois/symphony-trello/issues/690)
measured 37 of 40 and 20 of 20 branches on their first day and no new branch since.
The weights total ten half shares.

Automatic cycles retain ADR 0078's aggregate of 19,800 seconds normally and 18,000 seconds during
maintenance. The selector divides that aggregate by the total weight. Normal full and half shares
are 3,960 and 1,980 seconds; maintenance shares are 3,600 and 1,800 seconds. The continuation script
sets `automatic_budget=true`, including when the watchdog starts the chain.

A manually supplied `fuzz_seconds` remains one full target share. Half-share targets get half that
value, rounded up. For example, a manual value of 4,950 gives full targets 4,950 seconds and half
targets 2,475 seconds, totaling 24,750 seconds. This replaces the handoff-tool change's even split
with the accepted unequal allocation. Manual dispatch defaults to `automatic_budget=false`, even
when it requests a successor with `continue_fuzzing=true`; the successor uses the automatic budget.
The documented chain-bootstrap command sets `automatic_budget=true` for its first batch too.

### Consequences

* Good, because one execution costs a JSON parse, a config resolution, and the mapping. A local
  single-process run reached about 19,000 executions per second.
* Good, because the target found the defect it was written to look for within about 120
  executions: a malformed comment `date` threw `DateTimeParseException` out of the mapping. Per-card
  lookups caught only `TrelloException`, so one such card failed the whole lookup.
* Good, because the coverage verifier fails if the new target's report does not reach
  `TrelloClient.java`.
* Bad, because `normalize` and `BoardContext` are visible to the whole `tracker` package to give
  the fuzz helper an entry point.
* Bad, because the target does not cover the request layer: HTTP status handling, retries, and how
  a response body becomes a map.
* Bad, because each batch cycle builds the fuzzers six times instead of five, which adds one build
  to the cycle's wall-clock time without adding fuzzing time.
* Neutral, because the plateaued targets still run every cycle. [GitHub issue #693](https://github.com/martin-francois/symphony-trello/issues/693) reviews the allocation
  every quarter.

### Confirmation

```bash
./mvnw -q -Dtest=TrelloCardPayloadFuzzTest,TrelloCardPayloadFuzzJsonParityTest,TrelloClientTest,ContinuousFuzzingWorkflowTest test
JAZZER_FUZZ=1 ./mvnw -q -Pfuzzing -Djacoco.skip=true -Djazzer.max_duration=10m \
  -Djazzer.max_executions=0 \
  '-Dtest=TrelloCardPayloadFuzzTest#cardPayloadMappingReturnsACardOrRejectsTheUnknownPayload' test
pnpm run verify:scripts
```

After the change reaches `main`, the next hosted coverage report must list
`TrelloCardPayloadFuzzer` with covered branches in each card mapping method named in
[GitHub issue #772](https://github.com/martin-francois/symphony-trello/issues/772).

## Pros and Cons of the Options

### Call the card mapping directly with a parsed payload

A test helper in the `tracker` package parses the fuzz input as a card response and passes the map to
the package-private `TrelloClient.normalize`, with a board context and configuration built in memory.

* Good, because no socket, thread, or file is involved.
* Good, because a crash input is the JSON body plus a few trailing choice bytes, which is easy to read
  and to turn into a `TrelloClientTest` case.
* Bad, because it needs a package-private entry point in production code.

### Fuzz through `fetchCandidateCards` against a local HTTP server

`TrelloClientChaosTest` already serves fixed malformed responses this way.

* Good, because production code stays unchanged and the request layer is covered too.
* Bad, because every execution pays for HTTP round trips, and the useful input is the JSON body
  anyway.
* Bad, because a standalone target that opens sockets breaks the isolation the other targets keep.

### Feed card JSON to the existing `TrelloCardReferenceParserFuzzer`

Add card JSON seeds to the existing target and let it map them as well as parse references.

* Good, because no new matrix job or corpus is needed.
* Bad, because that target feeds plain text to the reference parser. Mixing JSON payloads into its
  corpus slows both input grammars.

### Give the new target a full share and grow the aggregate budget

Add a matrix job with the existing per-target budget and leave every other allocation unchanged.

* Good, because no existing target loses time.
* Bad, because every new target lengthens the automatic cycle. Pruning and coverage then drift
  beyond the daily interval in ADR 0078.

### Give the new target a full share and halve the two reference-parsing targets

Give the payload target a full share and `TrelloCardReferenceParserFuzzer` and
`TrelloChecklistClassifierFuzzer` half a share each. Divide the fixed automatic aggregate by the
weights of all six targets. Keep manually supplied seconds as a full target share.

* Good, because the aggregate budget and cycle length stay as ADR 0078 sets them.
* Good, because the time moves from targets that stopped finding branches to one that starts at
  zero.
* Bad, because a later regression in the reference parser gets half the fuzzing time it had before.

## More Information

The first finding was fixed by applying the existing malformed-payload contract: `instant` and
`decimal` now throw `TrelloException("trello_unknown_payload")` like `integer` already did for
`idShort`. `SPEC.md` Section 11.3 states that rule for every parsed field. Per-card lookups now report
such a card as failed. Candidate and terminal fetches still fail as a whole when one card is
malformed; [GitHub issue #835](https://github.com/martin-francois/symphony-trello/issues/835) tracks skipping that card instead.
