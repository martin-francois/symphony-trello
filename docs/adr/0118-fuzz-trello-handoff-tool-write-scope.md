---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #773](https://github.com/martin-francois/symphony-trello/issues/773)"
  - "[GitHub issue #690](https://github.com/martin-francois/symphony-trello/issues/690)"
  - "[GitHub issue #692](https://github.com/martin-francois/symphony-trello/issues/692)"
  - "[GitHub PR #836](https://github.com/martin-francois/symphony-trello/pull/836)"
  - "[GitHub PR #784](https://github.com/martin-francois/symphony-trello/pull/784)"
  - "[Jazzer FuzzedDataProvider](https://codeintelligencetesting.github.io/jazzer-docs/jazzer-api/com/code_intelligence/jazzer/api/FuzzedDataProvider.html)"
  - "[ADR 0003](0003-scoped-trello-handoff-tools.md)"
  - "[ADR 0061](0061-jazzer-and-oss-fuzz-readiness.md)"
  - "[ADR 0078](0078-github-actions-continuous-fuzzing.md)"
informed: [Future maintainers, Contributors]
---

# Fuzz the Trello handoff tools for write scope

## Context and Problem Statement

`TrelloHandoffToolHandler.handle` runs the Trello tool calls that the Codex model writes. Text on a
Trello card reaches the model through the prompt, so card content can steer the tool arguments. The
handler enforces the `trello_tools` settings from [ADR 0003](0003-scoped-trello-handoff-tools.md):
`enabled`, `allow_writes`, `allow_comments`, `allow_checklists`, `allow_url_attachments`,
`allow_destructive_operations`, and the two move allowlists.

The hosted coverage report of 2026-10-03 showed 0 of 438 branches covered in
`TrelloHandoffToolHandler` and 0 of 130 in `TrelloMarkdown`. A crash-only fuzz target would find
nothing there, because `handle` turns every runtime exception into a `trello_tool_failed` result.

How should a fuzz target reach the handler, what should it check, and where does its batch fuzzing
time come from?

## Decision Drivers

* The target must check a property: no tool call writes what the configuration does not allow.
* Each execution should be cheap, with no socket, thread, or file, like the other standalone targets.
* The target should exercise the real `TrelloClient` requests, because the client decides which
  checklist a checklist write lands on and which path each write uses.
* The target should keep working after [GitHub PR #784](https://github.com/martin-francois/symphony-trello/pull/784) validates tool arguments against their JSON
  Schema before dispatch.
* The continuous fuzzing chain in [ADR 0078](0078-github-actions-continuous-fuzzing.md) has a fixed aggregate budget per cycle.

## Considered Options

* Drive the handler and the real client through an in-memory transport.
* Drive the handler with a stubbed `TrelloClient`.
* Drive the handler and the real client against a local HTTP server.
* Fuzz the handler for crashes only.
* Give every target an equal part of the fixed aggregate batch budget.
* Give the new target a full share and grow the aggregate batch budget.

## Decision Outcome

Chosen options: drive the handler and the real client through an in-memory transport, and give every
target an equal part of the fixed aggregate batch budget.

`TrelloClient` gets a package-private constructor that takes a `Transport`, a one-method interface
that sends one `HttpRequest` and returns its `HttpResponse`. The public constructor passes a transport
backed by `HttpClient`, so production requests are unchanged. The test class `InMemoryTrelloBoard` in
the `tracker` package implements the transport. It holds board lists, the current card, and a second
card with a workpad, a blocker comment, and a checklist whose names match the current card's. It serves
the reads the tools send and records every write request, including a write it answers with an
injected error status. Each record names the card the write lands on: the card in the request path,
or the card that owned the comment, checklist, or check item when the request arrived.

`TrelloHandoffToolFuzzInvariants` in the `agent` package uses `FuzzedDataProvider` to choose the
`trello_tools` settings, the list names and archived flags, the current card's comments and checklists,
the session card id, the tool name, the JSON arguments, and optionally one request that Trello answers
with an error. It resolves the settings through `ConfigResolver`, so list names are normalized as in
production, and it runs one `handle` call. It then checks these properties:

* every write is a request one of the six tools sends, and lands on the session card or on a comment,
  checklist, or check item that card owns;
* every write needs `enabled` and `allow_writes`, and the setting for its kind: comments need
  `allow_comments`, deletes also need `allow_destructive_operations`, checklist writes need
  `allow_checklists`, and URL attachments need `allow_url_attachments`;
* an attached URL is http or https with a host and without credentials, query string, or fragment,
  and a created checklist or item name is one line without control characters;
* a tool writes only its own kinds, so a checklist call never posts a comment;
* the workpad and blocker recheck tools edit or delete only comments of their own managed family, and
  every comment they write stays in it: a workpad starts with the workpad heading, and a recheck
  status ends with the managed footer that links a comment on the current card;
* a move goes to an open list on the board whose id is allowed, or which is the only open list with an
  allowed name;
* a refused call returns a code from a known set and sends no write, and a failure code outside the
  known sets is a finding, so a new handler code must be added to the oracle on purpose;
* a call that fails with `trello_tool_failed` sends no write;
* a call whose arguments match the advertised schema with non-blank strings never fails with
  `trello_tool_failed`;
* a successful result names the session card;
* `TrelloMarkdown.escapeLeadingHashtags` only inserts backslashes before `#` plus a digit, so every
  other character and every line stays the same.

`TrelloHandoffToolFuzzer` is the standalone entry point and `TrelloHandoffToolFuzzTest` is the JUnit
`@FuzzTest`. Maven copies the seed corpus in `oss-fuzz/corpora/TrelloHandoffToolFuzzer` to the JUnit
test's regression inputs. The seeds are the smallest input for each tool and outcome from a local
active run, plus the inputs that found each permission check removed in the experiment below.

`scripts/select-clusterfuzzlite-target` gives each target a weight. A batch cycle fuzzes four full
shares in total, as ADR 0078 sets, and each target gets that aggregate in proportion to its weight.
All five targets have weight 1 today, so each gets 66 minutes in a normal cycle and 60 minutes in the
maintenance cycle.

### Consequences

* Good, because one execution costs a config resolution, a few in-memory requests, and the handler
  call. A local single-process run reached about 7,000 executions per second.
* Good, because the target checks the real request paths. A write that the client sends to the wrong
  card or to another card's comment fails the target even when the handler code looks correct.
* Good, because removing a permission check makes the target fail. In a local experiment, removing
  the `allow_writes`, `allow_comments`, `allow_checklists`, `allow_url_attachments`,
  `allow_destructive_operations`, or move allowlist check failed an active run within about 100 to
  110,000 executions. The inputs that found them are seeds, so each removal also fails the Maven
  regression replay.
* Good, because adding a target to the batch matrix only adds a weight. The cycle length and the
  daily prune and coverage stay as ADR 0078 sets them.
* Bad, because `TrelloClient` has a package-private constructor that only tests use.
* Bad, because the fake answers what Trello answers in the tested paths, not every Trello behavior.
  A Trello response shape the fake never sends is not covered.
* Bad, because the managed-family oracle accepts any comment that carries the family marker. A human
  comment that starts with the workpad heading or ends with the recheck footer counts as managed, as
  it does in the handler.
* Bad, because the other four targets lose 16.5 minutes each per normal cycle.

### Confirmation

```bash
./mvnw -q -Dtest=TrelloHandoffToolFuzzTest,ContinuousFuzzingWorkflowTest test
JAZZER_FUZZ=1 ./mvnw -q -Pfuzzing -Djacoco.skip=true -Djazzer.max_duration=10m \
  -Djazzer.max_executions=0 \
  '-Dtest=TrelloHandoffToolFuzzTest#handoffToolCallsWriteOnlyWhatTheConfigurationAllows' test
pnpm run verify:scripts
```

After the change reaches `main`, the next hosted coverage report must list `TrelloHandoffToolFuzzer`
with covered branches in `TrelloHandoffToolHandler` for each of the six tools.

## Pros and Cons of the Options

### Drive the handler and the real client through an in-memory transport

A package-private `TrelloClient` constructor takes a transport. A test class answers the requests from
memory and records every write.

* Good, because no socket, thread, or file is involved.
* Good, because the real client builds every request, so the property covers the client's part of the
  write scope too.
* Bad, because the fake board has to model the Trello reads and writes the tools use.

### Drive the handler with a stubbed `TrelloClient`

A subclass or a Mockito stub of `TrelloClient` records the calls the handler makes.

* Good, because production code stays unchanged.
* Bad, because `upsertChecklistItem` picks the checklist and the check item inside the client, so a
  stub would not see which checklist a write lands on.
* Bad, because Mockito is a test-runner jar that the OSS-Fuzz runtime classpath excludes.

### Drive the handler and the real client against a local HTTP server

`TrelloHandoffToolHandlerTest` uses `FakeTrelloServer` this way.

* Good, because production code stays unchanged.
* Bad, because every execution pays for HTTP round trips and a server thread.
* Bad, because a standalone target that opens sockets breaks the isolation the other targets keep,
  see [GitHub issue #692](https://github.com/martin-francois/symphony-trello/issues/692).

### Fuzz the handler for crashes only

Call `handle` with fuzzed arguments and report uncaught exceptions.

* Good, because it needs no oracle.
* Bad, because `handle` catches every runtime exception, so the target would report nothing.

### Give every target an equal part of the fixed aggregate batch budget

The `fuzz_seconds` input stays one full share. The selector divides four full shares among all
targets by weight, and all weights are 1.

* Good, because the aggregate budget and cycle length stay as ADR 0078 sets them.
* Good, because a later target only adds a weight, and a reviewer can give a plateaued target a lower
  weight without changing the total.
* Bad, because every existing target gets less time per cycle.

### Give the new target a full share and grow the aggregate batch budget

Add a fifth matrix job with the same per-target budget as the other four.

* Good, because no existing target loses time.
* Bad, because a normal cycle grows from 330 to 412.5 minutes of fuzzing, so prune and coverage no
  longer run about once a day.

## More Information

A 9-minute single-process active run on this change found no violation in 3.8 million executions.
Its corpus covered 363 of 438 branches in `TrelloHandoffToolHandler` and 127 of 130 in
`TrelloMarkdown`, including its nested `Fence` record. The committed seeds alone cover 296 and 83. Most
uncovered handler branches are in `toolSpecs` and `updateCodexUsageSection`, which no tool call
reaches.

[GitHub PR #836](https://github.com/martin-francois/symphony-trello/pull/836) adds a card payload target and gives the two reference-parsing targets half a share
each. When both changes meet, its targets keep their relative shares as weights in this selector.
[GitHub PR #784](https://github.com/martin-francois/symphony-trello/pull/784) moves argument validation in front of the tools and reports it as
`invalid_tool_arguments`. The refusal set already contains that code, so the target composes with
that change without an edit. `trello_tool_failed` is then no longer expected for any arguments.
