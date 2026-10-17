---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #692](https://github.com/martin-francois/symphony-trello/issues/692)"
  - "[GitHub issue #690](https://github.com/martin-francois/symphony-trello/issues/690)"
  - "[GitHub PR #774](https://github.com/martin-francois/symphony-trello/pull/774)"
  - "[Jazzer bug detectors](https://github.com/CodeIntelligenceTesting/jazzer/blob/main/docs/bug-detectors.md)"
  - "[JDK Flight Recorder API](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jfr/module-summary.html)"
  - "[JEP 486: Permanently disable the Security Manager](https://openjdk.org/jeps/486)"
  - "[ADR 0061](0061-jazzer-and-oss-fuzz-readiness.md)"
  - "[ADR 0078](0078-github-actions-continuous-fuzzing.md)"
informed: [Future maintainers, Contributors]
---

# Fuzz harness properties and isolation

## Context and Problem Statement

The first-week fuzzing review in
[GitHub issue #690](https://github.com/martin-francois/symphony-trello/issues/690) found that
`WorkflowLoaderFuzzer` only checked that `WorkflowLoader` threw nothing other than
`WorkflowException`. It also wrote every input to a temporary file, deleted it, and was the slowest
target at 2,279 executions per second on GitHub runners. A crash-only target misses deterministic
wrong results, and a target that writes files can leave state behind.

What should the fuzz targets assert, how should `WorkflowLoaderFuzzer` avoid the file, and how does
the project prove that fuzzing touches no files, network, or user configuration?

## Decision Drivers

* Find wrong results, not only uncaught exceptions.
* Keep the standalone OSS-Fuzz runtime classpath free of test libraries, as ADR 0061 requires.
* Keep production changes narrow.
* Verify isolation in the normal Maven build, not only by reading the code.
* Keep throughput changes measured, not assumed.

## Considered Options

For parsing workflow input without a file:

* A public `WorkflowLoader.parse(Path, byte[])` that `load(Path)` delegates to.
* Keep the temporary file.
* An in-memory file system such as Jimfs.

For verifying isolation:

* Replay the seed corpora in Jazzer regression mode and record file and socket I/O with Java Flight
  Recorder.
* Rely on Jazzer's `ServerSideRequestForgery` detector alone.
* A Security Manager that denies file and socket access.
* Replay the corpora with the standalone Jazzer driver in a child JVM with a fresh home directory.

For workflow properties:

* Assert the `SPEC.md` section 5.2 rules, determinism, a prompt round trip, and a size bound.
* Also round-trip the config through a YAML writer.

## Decision Outcome

Chosen options: `WorkflowLoader.parse(Path, byte[])`, a Flight Recorder replay test, and the
`SPEC.md` rules without a YAML writer round trip, because they run the production parsing code
without a file, check isolation in every Maven build, and find wrong results without false findings
from a YAML writer.

`load(Path)` reads the bytes and calls `parse`, which decodes strict UTF-8 and splits lines the same
way `Files.readAllLines` did. Undecodable input still fails as `missing_workflow_file` with the same
message, so the change has no user-facing effect. `WorkflowLoaderFuzzTest` checks that a file load
and an in-memory parse of every seed give the same result.

`StandaloneFuzzerIsolationTest` replays each `oss-fuzz/corpora/<Fuzzer>` directory through the
target's `fuzzerTestOneInput`. Each execution is wrapped in a custom Flight Recorder event, and the
test fails on any `jdk.FileRead`, `jdk.FileWrite`, `jdk.FileForce`, `jdk.SocketRead`, or
`jdk.SocketWrite` event on the same thread inside it. It ignores class and jar reads from class
loading and file events without a path, which are writes to standard output or error. Production
code that logs a warning for a malformed input writes to stderr, which leaves no state behind.
Against the old `WorkflowLoaderFuzzer` it reported the temporary file writes and reads. During
ClusterFuzzLite and OSS-Fuzz runs, Jazzer's network detector reports any connection attempt, and the
test fails if the fuzzing build files disable it.

The properties live in shared helpers, `WorkflowLoaderInvariants` and
`TrelloReferenceFuzzInvariants`, so the standalone target and the JUnit fuzz test check the same
rules. They throw `AssertionError` because AssertJ is not on the OSS-Fuzz runtime classpath.
[Fuzzing](../fuzzing.md) lists the properties of each harness.

### Consequences

* Good, because a wrong config, prompt, or error code now fails fuzzing, not only an exception.
* Good, because the property check found a lenient UTF-8 decoding mutation within three executions.
* Good, because the build fails if any standalone target starts touching files or sockets.
* Good, because `WorkflowLoaderFuzzer` no longer writes files.
* Neutral, because `WorkflowLoader` has one more public method that production code does not call
  directly.
* Bad, because adding a standalone target now also needs a `pom.xml` test resource mapping and a
  `@FuzzTest` method in `StandaloneFuzzerIsolationTest`. A test fails when either is missing.
* Bad, because the Trello targets run about a third as many executions per second.
* Bad, because the replay test sees socket reads and writes but not a refused connection attempt.
  The Jazzer network detector covers that case during real fuzzing only.
* Bad, because the replay test covers the checked-in seeds, not every input a fuzzing run generates,
  and only I/O on the thread that runs the target. No target starts threads today.
* Bad, because the JUnit `@FuzzTest` harnesses are not replayed under Flight Recorder. They call the
  same invariant helpers as the standalone targets, and only `fileLoadMatchesInMemoryParse` touches a
  file, inside a JUnit temporary directory.
* Bad, because the replay test runs isolated from other test classes for a few seconds. A JVM-wide
  recording would otherwise collect every other test's file events.
* Neutral, because an isolation failure names the target and the file or host, not the seed. The
  seeds are checked in, so rerunning the test reproduces the failure.

### Confirmation

```bash
./mvnw -q -Dtest=StandaloneFuzzerIsolationTest,WorkflowLoaderFuzzTest,TrelloCardReferenceParserFuzzTest test
```

To confirm the isolation test still detects file access, make a standalone target write a temporary
file and run `StandaloneFuzzerIsolationTest`. It must fail and name the file.

## Pros and Cons of the Options

### Public `WorkflowLoader.parse(Path, byte[])`

`load(Path)` reads the file and delegates to a public method that parses bytes as if they came from
that path. The fuzzer calls the method directly.

* Good, because the fuzzer exercises the same parsing code as production.
* Good, because a file load and an in-memory parse are compared on every seed.
* Bad, because the method is public only for the fuzz package.

### Keep the temporary file

Each execution keeps creating, writing, and deleting a file before `load(Path)` reads it.

* Good, because production code stays unchanged.
* Bad, because every execution writes to disk, and an interrupted run can leave files behind.
* Bad, because the isolation test would need an exception for the target that most needs it.

### In-memory file system

The fuzzer writes each input to a Jimfs file system and calls `load(Path)` with that path.

* Good, because production code stays unchanged.
* Bad, because Jimfs is a test dependency, and the OSS-Fuzz build copies only runtime dependencies.
  The build script would have to ship one more jar.
* Bad, because it keeps the file I/O cost that the in-memory parse removes.

### Flight Recorder replay test

A JUnit class replays the seed corpora through each standalone entry point in Jazzer regression mode
and checks recorded file and socket events per execution.

* Good, because it runs in every Maven build and in pull request CI.
* Good, because it covers file reads and writes anywhere, not only in known directories.
* Bad, because Flight Recorder has no event for a refused connection or a DNS lookup.

### Jazzer network detector alone

Jazzer's `ServerSideRequestForgery` hook reports every connection attempt.

* Good, because it already runs during ClusterFuzzLite and OSS-Fuzz fuzzing.
* Bad, because in JUnit regression mode the hook loads but a connection attempt does not fail the
  test, as a probe test showed.
* Bad, because it does not cover file access.

### Security Manager

A Security Manager would deny file and socket permissions while a target runs.

* Bad, because JEP 486 permanently disabled the Security Manager in Java 24, and the project uses
  Java 25.

### Child JVM replay with the standalone driver

A test starts the Jazzer driver in a child JVM with `-runs=0` over each corpus, a fresh home
directory, and a fresh working directory, then checks that they stay empty.

* Good, because it runs the same driver as ClusterFuzzLite, including the network detector.
* Bad, because it adds several seconds of JVM and native driver startup per target.
* Bad, because the driver extracts native libraries into the temporary directory, so an empty
  directory check needs exceptions.

### Config round trip through a YAML writer

The property would serialize the parsed config with Jackson's YAML writer, parse it again, and
compare.

* Good, because it is a classic parse and serialize property.
* Bad, because it tests the writer, not the loader. Jackson reads `1e400` as `Infinity` but writes it
  back as a string, and reads `!!binary` as a byte array. Each writer quirk would become a false
  fuzzing finding.

## More Information

### Throughput measurements

All runs used one process on a shared 24-core host, the 1,982-input `WorkflowLoaderFuzzer` corpus
from the storage repository on 2026-10-04, and the 2026-10-04 `main` branch as the baseline.

| Variant | libFuzzer exec/s, 60 s, seed 1 | Seed 2 | Replay without instrumentation, inputs/s |
| --- | ---: | ---: | ---: |
| Temporary file, crash-only (before) | 2,373 | 2,140 | 17,000 to 20,100 |
| In memory, crash-only | 2,552 | 2,391 | 36,700 to 40,900 |
| In memory with the new properties (after) | 5,167 | 4,893 | 18,600 to 20,400 |

Without Jazzer's instrumentation, the temporary file halves throughput. Under instrumentation it
costs about 8%, because instrumented YAML parsing dominates each execution. The gap to the other
targets comes from the YAML parser, not from the file. The properties parse each input three
times, which halves uninstrumented replay speed. Under libFuzzer the run with properties still
executed twice as many inputs per second. The cause was not identified; the new properties add
coverage features, which changes which inputs libFuzzer schedules. A five-minute run of the final
target executed 1,325,627 inputs at 4,404 per second and found nothing.

The Trello targets got round-trip and order properties, which cost throughput. Same host, seed 1,
60 seconds each, stored corpora:

| Target | Before, exec/s | After, exec/s |
| --- | ---: | ---: |
| `TrelloCardReferenceParserFuzzer` | 50,338 | 15,331 |
| `TrelloChecklistClassifierFuzzer` | 23,636 | 8,193 |

Both targets have covered every reachable branch of their entry points since the first day of
continuous fuzzing, so more executions of the same code find little. A property that checks the
result is worth more there than raw speed.

### Front matter behaviors left open

While checking the properties, three YAML front matter behaviors turned out to be accepted or
rejected without a clear contract: an alias becomes its anchor name instead of the anchored value,
a duplicate key keeps the last value, and comment-only front matter is a parse error while `null`
front matter is an empty config. The properties do not pin these behaviors.
[GitHub issue #786](https://github.com/martin-francois/symphony-trello/issues/786) asks the
maintainer to decide each one.
