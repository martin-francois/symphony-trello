---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #691](https://github.com/martin-francois/symphony-trello/issues/691)"
  - "[GitHub issue #690](https://github.com/martin-francois/symphony-trello/issues/690)"
  - "[GitHub PR #774](https://github.com/martin-francois/symphony-trello/pull/774)"
  - "[libFuzzer dictionaries](https://llvm.org/docs/LibFuzzer.html#dictionaries)"
  - "[Jazzer FuzzedDataProvider](https://codeintelligencetesting.github.io/jazzer-docs/jazzer-api/com/code_intelligence/jazzer/api/FuzzedDataProvider.html)"
  - "[ADR 0061](0061-jazzer-and-oss-fuzz-readiness.md)"
  - "[ADR 0078](0078-github-actions-continuous-fuzzing.md)"
informed: [Future maintainers, Contributors]
---

# Structured input for the repository source fuzz target

## Context and Problem Statement

The first-week fuzzing review for
[GitHub issue #690](https://github.com/martin-francois/symphony-trello/issues/690) measured
`RepositorySourceResolver` at 195 of 227 JaCoCo branches from 2026-09-07 to 2026-10-03 while
`RepositorySourceFuzzer` averaged 5,857 executions per second on GitHub-hosted runners. The harness wrote `Repository: `
plus the whole input into the card description, copied the input into the title and one comment,
and always passed an empty workflow repository configuration. A card without a declaration and the
fallback to `repository.default_url` or `repository.default_path` could therefore never run.
Random byte mutation also never produced a parseable bracketed IPv6 host without a port.

How should the target generate input so that it reaches these branches, without losing the stored
corpus and without adding generation machinery that does not pay for itself?

## Decision Drivers

* Reach the measured missed branches that some input can reach.
* Keep the stored corpus of about 400 inputs useful after the change.
* Keep the standalone target runnable by the OSS-Fuzz and ClusterFuzzLite JVM wrappers.
* Keep seeds reviewable and reproducible.
* Add a dictionary or custom mutator only when it measurably improves coverage over seeds.

## Considered Options

* `FuzzedDataProvider` choices with the repository value read first.
* `FuzzedDataProvider` choices read before every text.
* Keep the byte-string harness and add seeds and a dictionary.
* A custom libFuzzer mutator for a repository declaration grammar.
* The Jazzer mutation framework through typed `@FuzzTest` parameters only.

## Decision Outcome

Chosen option: `FuzzedDataProvider` choices with the repository value read first.

`RepositorySourceFuzzer` reads the repository value as the first text. It then reads one choice for
each card text field: the title, the description, and up to two comments. Each field carries a
declaration of the shared value, a declaration of its own value, free text, or nothing. A last
choice selects no workflow default, `repository.default_url`, or `repository.default_path`. The
other texts follow. `FuzzedDataProvider` ends a text at a backslash followed by another character
and takes choices from the end of the input. An input stored before this change has no such
separator in most cases, so it keeps its whole text as the value and gets the first option of every
choice: the value declared in the title and the description, no comments, and no workflow default.

The harness builds `repository.default_path` the way `ConfigResolver` does and drops a path that
`ConfigResolver` rejects. `RepositorySourceResolverFuzzTest` gets the matching structure through
typed `@FuzzTest` parameters, which the Jazzer mutation framework mutates field by field.

The project adds no dictionary. On 2026-10-04, two single-process five-minute runs on the same host
started from the stored corpus plus the new seeds. Without a dictionary, the run ended at 578
libFuzzer edges and 205 of 219 `RepositorySourceResolver` branches. With a dictionary of 31
declaration labels, URL schemes, IPv6 host forms, ports, and the text separator, it ended at the
same 578 edges and the same 205 branches. The seeds already reach every branch that some input can
reach, so a dictionary has nothing left to find in this class.

Throughput rose with the change. On the same host, a three-minute run of the old harness from the
stored corpus averaged 7,104 executions per second, and the five-minute run of the new harness
averaged 10,300. The old harness parsed up to three declarations of the same value on every
execution; the new one often parses fewer. The hosted figure will differ, because GitHub-hosted
runners were slower than this host for the old harness too.

### Consequences

* Good, because replaying the stored corpus and the new seeds through the new harness covers 205 of
  the 219 branches left after removing the dead SCP-style checks. Before the change, the same
  corpus covered 195 of 227.
* Good, because the new seeds found a false positive in the harness helper
  `TestRepositoryUris`, which reported a port for every bracketed IPv6 host without one.
* Good, because the workflow default path became reachable and exposed that `ConfigResolver`
  accepted a `repository.default_path` with a line break, which made repository source selection
  throw for every card without a declaration. `ConfigResolver` now rejects such a path.
* Good, because a seed is plain text in the common case and needs choice bytes only for other card
  fields or a workflow default.
* Bad, because stored inputs that contain a backslash followed by another character change meaning.
  Replaying the stored corpus alone through the new harness covers 187 of 219 branches. The
  checked-in seeds cover the difference, and fuzzing adds new inputs from there.
* Bad, because a seed that needs choices is a binary file. The harness Javadoc describes its layout.
* Neutral, because 14 branches stay uncovered. No input can reach them: ten repeat a check that
  `parse` or the calling helper already made on the same value, two compare identity and path after
  the normalized values already matched, and two check bracketed IPv6 hosts for shapes that
  `java.net.URI` already rejects. The four SCP-style checks that
  [GitHub issue #691](https://github.com/martin-francois/symphony-trello/issues/691) named as dead
  were removed instead, because they repeated a test two lines earlier and protected nothing.

### Confirmation

`ContinuousFuzzingWorkflowTest` checks that every standalone target keeps a seed corpus. The target
name and its storage path did not change, so the batch job's existing corpus verification step
checks persistence on the first batch after merge.
`RepositorySourceResolverFuzzTest` replays the structured seeds in every Maven verify. After merge,
the hosted coverage report published by a maintenance cycle should show
`RepositorySourceResolver` at 205 of 219 branches or more. [Fuzzing](../fuzzing.md) documents how to
replay a corpus under JaCoCo locally.

## Pros and Cons of the Options

### `FuzzedDataProvider` choices with the repository value read first

The harness reads the repository value first, then one byte per choice from the end of the input,
then the remaining texts.

* Good, because most stored inputs keep their whole value and still reach the parser through a
  `Repository:` declaration in the description, as before.
* Good, because it needs no new build or packaging step.
* Bad, because inputs that need choices are binary.

### `FuzzedDataProvider` choices read before every text

The harness reads every choice before the first text.

* Good, because the code reads in the same order as the byte layout.
* Bad, because the last bytes of every stored input become choices instead of value text. A local
  replay of the stored corpus through a harness with this layout covered 143 of 227 branches.

### Keep the byte-string harness and add seeds and a dictionary

The harness keeps one text input and the project adds seeds and a libFuzzer dictionary.

* Good, because it changes no code.
* Bad, because the harness always writes a declaration and never sets a workflow default, so eight
  of the measured missed branches stay out of reach for any input.

### A custom libFuzzer mutator for a repository declaration grammar

A `LLVMFuzzerCustomMutator`-style mutator would generate declaration lines from a grammar.

* Good, because it could keep every input valid text.
* Bad, because the project would write and maintain a grammar mutator that the JVM wrappers must
  load, while structured choices and seeds already reach every branch that some input can reach.

### The Jazzer mutation framework through typed `@FuzzTest` parameters only

The JUnit fuzz test takes the card fields and the workflow default as typed parameters.

* Good, because the mutation framework mutates each field separately and keeps seeds readable.
* Bad, because the OSS-Fuzz and ClusterFuzzLite wrappers run the standalone `fuzzerTestOneInput`
  targets, not JUnit fuzz tests, so hosted fuzzing would not change. The project uses it for the JUnit
  counterpart in addition to the chosen option.

## More Information

[GitHub PR #774](https://github.com/martin-francois/symphony-trello/pull/774) holds the first-week
review that measured the baseline. Workflow front matter is the next candidate format. Measure it
only after
[GitHub issue #771](https://github.com/martin-francois/symphony-trello/issues/771) makes
configuration resolution reachable, so its baseline is real.
