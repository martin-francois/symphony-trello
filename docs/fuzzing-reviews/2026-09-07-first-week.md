# Fuzzing review: first week

Review date: 2026-10-04. Issue: [#690](https://github.com/martin-francois/symphony-trello/issues/690).

[Fuzzing](../fuzzing.md) describes the continuous ClusterFuzzLite chain, and
[ADR 0078](../adr/0078-github-actions-continuous-fuzzing.md) explains its design. In short, batch
runs follow each other, every fourth batch (cycle 3) also prunes the corpus and publishes coverage,
and a watchdog restarts the chain at cycle 0 when it stops. Corpora and coverage reports live in the
public
[`symphony-trello-fuzzing-storage`](https://github.com/martin-francois/symphony-trello-fuzzing-storage)
repository.

The baseline period is the first seven days of that chain. It covers the 30 batch runs that started
between 2026-08-31 15:19 UTC, when the first cycle 0 started, and 2026-09-07 15:19 UTC. All 30
succeeded, and all seven cycle-3 runs finished their prune and coverage jobs. Each of the four targets
fuzzed for 145,380 seconds, about 40 hours. Cycles 0 to 2 give each target 82.5 minutes and cycle 3
gives it 75 minutes. The tables also show data up to 2026-10-03 to check whether the conclusions
still hold.

## Targets

| Target | Production entry point |
| --- | --- |
| `RepositorySourceFuzzer` | `RepositorySourceResolver` |
| `TrelloCardReferenceParserFuzzer` | `TrelloCardReferenceParser` |
| `TrelloChecklistClassifierFuzzer` | `TrelloChecklistClassifier` |
| `WorkflowLoaderFuzzer` | `WorkflowLoader` |

## Throughput

Values come from the libFuzzer `stat::` lines in the batch job logs.

| Target | Executions | Average exec/s | Lowest and highest batch exec/s | Peak RSS |
| --- | ---: | ---: | ---: | ---: |
| `RepositorySourceFuzzer` | 851,430,065 | 5,857 | 3,435 and 8,099 | 1,088 MB |
| `TrelloCardReferenceParserFuzzer` | 4,260,172,361 | 29,304 | 16,318 and 37,433 | 1,173 MB |
| `TrelloChecklistClassifierFuzzer` | 2,674,173,740 | 18,394 | 2,774 and 23,756 | 1,094 MB |
| `WorkflowLoaderFuzzer` | 331,257,511 | 2,279 | 756 and 2,918 | 1,106 MB |

`WorkflowLoaderFuzzer` is the slowest target. Each of its executions creates, writes, and deletes a
temporary file before parsing.

## Corpus

Stored corpus files after each daily prune, from the `main` branch of the storage repository. Each
target started from three checked-in seeds. The first column is the corpus the chain started from,
after the short manual test runs on 2026-08-31.

| Target | 2026-08-31 | 2026-09-01 | 2026-09-07 | 2026-10-03 |
| --- | ---: | ---: | ---: | ---: |
| `RepositorySourceFuzzer` | 233 | 342 | 377 | 392 |
| `TrelloCardReferenceParserFuzzer` | 91 | 89 | 85 | 85 |
| `TrelloChecklistClassifierFuzzer` | 132 | 140 | 135 | 135 |
| `WorkflowLoaderFuzzer` | 457 | 1,627 | 1,751 | 1,782 |

Coverage edges and features at the end of the first and the last batch in the period, from the
libFuzzer `DONE` lines. Edges include library code such as the YAML parser.

| Target | Edges | Features |
| --- | --- | --- |
| `RepositorySourceFuzzer` | 518 to 520 | 2,567 to 2,737 |
| `TrelloCardReferenceParserFuzzer` | 82 to 82 | 379 to 379 |
| `TrelloChecklistClassifierFuzzer` | 327 to 327 | 1,103 to 1,107 |
| `WorkflowLoaderFuzzer` | 3,933 to 3,962 | 14,418 to 15,322 |

## Coverage

JaCoCo branch and line coverage of each entry point, from the hosted reports on the storage
repository's `gh-pages` branch. The 2026-08-31 report used the corpus from before the chain started.

| Entry point | 2026-08-31 | 2026-09-01 | 2026-09-07 and 2026-10-03 |
| --- | --- | --- | --- |
| `RepositorySourceResolver` | 185/227 branches | 194/227 branches | 195/227 branches, 204/224 lines |
| `TrelloCardReferenceParser` | 37/40 branches | 37/40 branches | 37/40 branches, 61/62 lines |
| `TrelloChecklistClassifier` | 20/20 branches | 20/20 branches | 20/20 branches, 37/37 lines |
| `WorkflowLoader` | 21/24 branches | 21/24 branches | 21/24 branches, 39/39 lines |

Across all production code the fuzzers covered 430 of 14,232 lines and 328 of 6,969 branches on
2026-09-07. The 2026-10-03 report shows the same numbers.

Every target reached its entry point in the first report. Between the first report built from the
chain's corpus, on 2026-09-01, and the end of the period, the only gain was one
`RepositorySourceResolver` branch. Of the 32 branches it still misses, 8 cannot run with the current
harness. The harness writes `Repository: ` plus the input into every card description and always
passes an empty repository configuration, so a card without a declaration and the fallback to the
workflow default URL or path never occur. Another 7 missed branches check the port after an IPv6
host in brackets, and 9 validate the user and host of `user@host:path` remotes. At least 4 of those
9 can never run: `scpRemote` returns early when the host part has no `@`, so its later
`at >= 0` and `user != null` checks are always true.

## Findings

None. All 120 target runs in the period logged `finished with no crashes discovered`. No log
contains a libFuzzer timeout, out-of-memory, or deadly-signal report. Code scanning has no
ClusterFuzzLite alert, and the chain created no `fuzzed` issue. The only `fuzzed` issue,
[#508](https://github.com/martin-francois/symphony-trello/issues/508), predates continuous fuzzing.
No pull request code-change fuzzing run failed in the period.

After the period, three batches failed in `Build fuzzers` on 2026-09-10, 2026-09-22, and
2026-09-28 because Maven could not download artifacts. The watchdog restarted the chain each time.

## Weakly covered input boundaries

| Boundary | Input source | Fuzz-covered branches | Decision |
| --- | --- | ---: | --- |
| Workflow front matter to typed configuration: `ConfigResolver`, `CodexSandboxPolicy`, `WorkflowConfigIngestion`, and helpers | Operator YAML and environment references | 0 of 272 | New work, ranked first |
| Trello card payload mapping in `TrelloClient` | Trello API JSON written by board members | 0 of 72 | New work, ranked second |
| Trello handoff tool arguments: `TrelloHandoffToolHandler` and `TrelloMarkdown` | Arguments written by the Codex model, which reads card text | 0 of 568 | New work, ranked third |
| `.env` parsing in `LocalEnvironment` | Operator file | 0 of 94 | Deferred |
| `CodexAppServerClient` messages | Local Codex process output | 0 of 199 | Deferred |
| Prompt rendering in `PromptRenderer` | Operator template plus card data | 0 of 4 | Deferred |
| Status API in `StatusResource` | Local HTTP requests | 0 of 28 | Deferred |

The ranking weighs three things: the uncovered branches the input can reach, what a defect there
would cost, and how much harness the target needs. The handoff tools have the most branches and the
highest defect value, but they come last because they need a recording fake, a new seam in
`TrelloClient`, and a write-scope property before fuzzing finds anything.

1. Configuration resolution decides the Codex sandbox policy and secret references. It can extend
   `WorkflowLoaderFuzzer`, whose loader is saturated but whose corpus already holds valid front
   matter. Tracked in [#771](https://github.com/martin-francois/symphony-trello/issues/771).
2. Card payload mapping reads data that any board member can shape. `instant()` and `decimal()`
   throw unchecked parse exceptions where `integer()` throws `trello_unknown_payload`, and the
   per-card lookups catch only `TrelloException`. A fuzz target should confirm this before a fix.
   Tracked in [#772](https://github.com/martin-francois/symphony-trello/issues/772).
3. Handoff tools enforce Trello write permissions against model-written arguments. A crash-only
   harness finds nothing because `handle` turns every runtime exception into a failure result, so
   this target needs a write-scope property and a recording fake first. Tracked in
   [#773](https://github.com/martin-francois/symphony-trello/issues/773).

The deferred boundaries either take input only from the operator or the local machine, or would
mostly fuzz library code. `PromptRenderer` passes card values to Pebble as data, so fuzzing it would
mostly exercise Pebble's parser on operator templates. `CodexAppServerClient` needs a fake process
transport first. The quarterly review in
[#693](https://github.com/martin-francois/symphony-trello/issues/693) should look at them again.

The issue also named webhook payloads and configuration migration. Symphony for Trello polls Trello
and has no webhook endpoint, and production code has no configuration migration logic. URL,
identifier, and card-reference parsing is already reached by `RepositorySourceFuzzer` and
`TrelloCardReferenceParserFuzzer`, so another target there would repeat covered code.

## Follow-up for existing targets

- [#691](https://github.com/martin-francois/symphony-trello/issues/691): generate structured input
  for `RepositorySourceFuzzer`, so card fields vary, a workflow default repository is set, and
  remotes use bracketed IPv6 hosts and `user@host:path` forms. This can reach about 20 of the 24
  missed branches listed above. The rest cannot run with any input.
- [#692](https://github.com/martin-francois/symphony-trello/issues/692): give `WorkflowLoaderFuzzer`
  properties beyond its exception type, and measure whether parsing from memory instead of a
  temporary file raises its throughput.
- [#693](https://github.com/martin-francois/symphony-trello/issues/693): the two Trello parser targets
  gained no branch after the first day. They are the first place to take runtime from when a new
  target joins the matrix.

## How the numbers were collected

The corpus history is the `main` branch of
[`symphony-trello-fuzzing-storage`](https://github.com/martin-francois/symphony-trello-fuzzing-storage).
Each coverage upload is a commit on its `gh-pages` branch. Per-class coverage comes from
`coverage/latest/report/linux/jacoco.xml` in those commits, per-target summaries from
`coverage/latest/fuzzer_stats/`. Throughput comes from the batch job logs. Replace the dates with the
review period:

```bash
gh api --paginate "repos/martin-francois/symphony-trello/actions/workflows/continuous-fuzzing.yml/runs?created=2026-08-31..2026-09-07&per_page=100" \
  --jq '.workflow_runs[] | select(.display_title | startswith("Continuous batch cycle")) | .id'
gh api "repos/martin-francois/symphony-trello/actions/runs/<run-id>/logs" > run.zip
unzip -p run.zip '*_batch*.txt' | grep -E 'stat::|DONE|finished with no crashes'
```

GitHub keeps workflow logs for 90 days, so the next review must collect them within that window.
