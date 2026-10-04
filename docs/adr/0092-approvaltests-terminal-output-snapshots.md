---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude Code]
consulted:
  - "[GitHub issue #602](https://github.com/martin-francois/symphony-trello/issues/602)"
  - "[GitHub issue #776](https://github.com/martin-francois/symphony-trello/issues/776)"
  - "[ApprovalTests.Java](https://github.com/approvals/ApprovalTests.Java)"
  - "[java-snapshot-testing](https://github.com/codedabble-dev/java-snapshot-testing)"
  - "[Selfie](https://github.com/diffplug/selfie)"
  - "[tui-test](https://github.com/microsoft/tui-test)"
  - "[VHS](https://github.com/charmbracelet/vhs)"
  - "[insta](https://github.com/mitsuhiko/insta)"
  - "[ADR 0020](0020-junit-6-test-stack.md)"
  - "[ADR 0055](0055-test-deduplication-layer-boundaries.md)"
informed: [Future maintainers, Contributors]
---

# Use ApprovalTests for Terminal-Output Snapshots

## Context and Problem Statement

Command-line and installer tests assert selected lines and fragments. A change can alter output
that no assertion selects, such as line order, spacing, headings, quoting, ANSI sequences, or which
stream a message goes to, and the tests still pass.

[GitHub issue #602](https://github.com/martin-francois/symphony-trello/issues/602) asks for
committed, reviewable snapshots of the complete transcript: one happy path per public
`symphony-trello` command, the `install.sh` dry run, one guided installation in a real
pseudo-terminal, and two commands through the installed wrapper. The issue requires the project
to evaluate existing snapshot libraries first and to build its own snapshot engine only when no
maintained library fits.

Which tool should store, compare, report, and update these snapshots?

## Decision Drivers

* Run inside the existing Java 25, JUnit 6, and Maven test JVM, in the required Linux
  `./mvnw ... verify` CI step, without another language runtime.
* Stay correct under the repository's concurrent JUnit class and method execution.
* Keep one reviewable file or clearly separated entry per scenario.
* Never write or change a committed baseline during a normal local or CI run. Updates need an
  explicit developer flag.
* Show a useful line-level diff when a transcript changes.
* Meet the dependency policy in [Java style](../agents/java-style.md): an upstream release in the
  previous 12 months, an unarchived repository, no deprecation notice, a compatible open-source
  license, and at least 100 GitHub stars. Respect the seven-day release-age cooldown from
  [Dependency updates](../agents/dependency-updates.md).
* Prefer a terminal-aware tool when it gives a material advantage, and keep the transcript
  representation honest: separate streams for normal processes, one merged transcript for a
  pseudo-terminal.

## Considered Options

* ApprovalTests.Java with repository-owned transcript rendering and normalization
* java-snapshot-testing
* Selfie
* tui-test
* VHS
* insta
* A repository-owned snapshot engine

Screened out by the dependency policy, with the failed criterion, checked on 2026-10-04 from the
GitHub API, Maven Central, and PyPI:

| Candidate | Kind | Failed criterion |
| --- | --- | --- |
| skuzzle/snapshot-tests | Java snapshot library | 19 stars; last release January 2024 |
| mkutz/ApproveJ | Java approval library | 33 stars |
| json-snapshot | Java JSON snapshots | 38 stars |
| Zenika/java-snapshot-matcher | Java snapshot matcher | 20 stars |
| slowli/term-transcript | Rust terminal transcripts | 32 stars |
| mitsuhiko/insta-cmd | Rust command snapshots for insta | 52 stars |
| Textualize/pytest-textual-snapshot | Python TUI snapshots | 52 stars |
| pexpect | Python pseudo-terminal driver | last release 4.9.0 on 2023-11-25 |

JetBrains JediTerm (864 stars, LGPL-3.0) is a Java terminal emulator, not a snapshot library. It
would only help with final-screen rendering, which the issue leaves out of scope because the
installer does not move the cursor or draw a full-screen interface.

## Decision Outcome

Chosen option: "ApprovalTests.Java with repository-owned transcript rendering and normalization",
because it is the only eligible candidate that runs in the existing JVM with no new runtime, stores
one file per scenario, never writes a baseline unless asked, and adds about 1 MB of test-only
dependencies. None of the terminal-aware tools runs inside the Java test JVM, and their main
terminal feature, rendering the final screen, is out of scope for this issue.

The project keeps three small pieces of its own, because they define this repository's transcript
format rather than snapshot infrastructure:

* `TerminalTranscript` renders the result. A normal process records `exitCode`, `--- stdout ---`,
  and `--- stderr ---`. A pseudo-terminal records `exitCode` and `--- terminal ---`, because the
  terminal merges both streams and the echo of typed input. Control characters become visible
  markers such as `<ESC>` and `<CR>`; a stream without a final newline is marked.
* `TranscriptNormalizer` replaces only exact values that a scenario registers: its temporary root,
  a dynamically allocated port written as `127.0.0.1:<port>`, or the installer's release version.
  It has no pattern rules. It fails when part of a temporary path survives, which catches output
  that wrapped or folded a path, the problem behind
  [GitHub issue #776](https://github.com/martin-francois/symphony-trello/issues/776).
* `CredentialSentinels` holds fixed credential values. Every snapshot fails before comparison if a
  sentinel appears in any captured stream. Sentinels are never normalized.

ApprovalTests does the storage, naming, comparison, candidate files, and approval. The test
support configures it as follows:

* Baselines live in `src/test/snapshots/<suite>/<scenario>.approved.txt`. Candidates go to
  `target/snapshot-candidates`, so a failing run never writes into `src/`.
* In a normal run, a failure reporter uses AssertJ's file comparison to throw a line-level diff
  that names the baseline, the candidate, and the update command.
* `-Dsymphony.snapshots.update=true` switches to ApprovalTests' `AutoApproveReporter`, which copies
  the candidate over the baseline. Surefire passes the property only when a developer sets it, and
  the test support refuses it when the `CI` environment variable is set.
* Surefire and Failsafe set `APPROVALTESTS_DISABLE_SCRIPT_DOWNLOADS=1`. Without it, ApprovalTests
  downloads two helper scripts from GitHub during the first verification.
* Surefire and Failsafe set `picocli.ansi=false` for every test. Picocli otherwise colors help text
  when a developer exports `CLICOLOR_FORCE`, and the in-process help snapshot would change. No
  other test depends on colored help.
* ApprovalTests records verified baseline paths in a static, unsynchronized set. The test support
  holds one lock while it calls ApprovalTests. Commands still run and capture output in parallel;
  only the file comparison is serialized.

The process boundary uses the existing installer fixture. Snapshot children start from an empty
environment and a PATH that exposes only the commands the scenario needs, so locale, terminal type,
color settings, shell, and optional host tools such as `curl` or `node` cannot change a transcript.
The pseudo-terminal scenario types each answer only after its prompt appears, so the terminal echo
follows the prompt instead of depending on scheduling. The installed-wrapper scenarios replace the
fake launcher jar with a manifest-only jar that points at the test classpath, so the wrapper starts
the real command-line application.

### Consequences

* Good, because every scenario has its own baseline file, so a review shows exactly which command's
  output changed.
* Good, because a normal run cannot create or change a baseline, and CI cannot enable updates.
* Good, because the dependency is small, has no runtime outside the JVM, and is test-scoped.
* Good, because the transcript format shows stream placement, control characters, and missing final
  newlines.
* Bad, because ApprovalTests has no JUnit extension, so the repository supplies what an extension
  would: explicit scenario names instead of test-method names, a reporter that throws the line
  diff, the update switch, and the lock. The ApprovalTests types in use are `Approvals.verify`,
  `Options`, `ApprovalNamer`, `ApprovalFailureReporter`, and `AutoApproveReporter`.
* Bad, because the installed-wrapper scenarios do not test release packaging. They run the wrapper
  that `install.sh` writes against the test classpath; `ReleasePackagingScriptTest` and
  `PackagedAppSmokeIT` keep covering the packaged application.
* Bad, because the guided-installation baseline shows the setup prompts of the installer fixture's
  fake `setup-local`, which hides secret input the way `Console.readPassword` does. The real
  prompts are covered by the in-process `setup-local` scenario.
* Bad, because ApprovalTests writes run logs to `.approval_tests_temp/` in the project root. The
  directory ignores itself and `.gitignore` lists it too.
* Bad, because there is no IDE plugin or diff-tool integration in the chosen setup. A developer
  reads the AssertJ diff or compares the candidate file under `target/`.
* Bad, because baselines record `install.sh` and Git messages, so a Git release that rewords
  `Cloning into ...` would require a baseline update.

### Confirmation

* `TerminalSnapshotsTest` proves that an unexpected line, an ordering change, a whitespace change,
  and a stream-placement change fail with a diff, that verification leaves the baseline untouched,
  that a missing baseline is not created, that only the explicit property updates baselines, that
  CI refuses updates, and that a leaked sentinel fails before comparison.
* `TrelloBoardSetupMainSnapshotTest` and `InstallerScriptSnapshotTest` run in the normal
  `./mvnw -q spotless:check verify` gate.
* The dependency is listed in
  [Dependency upgrade confidence](../testing/dependency-upgrade-confidence.md).

## Pros and Cons of the Options

### ApprovalTests.Java with Repository-Owned Transcript Rendering and Normalization

ApprovalTests.Java compares a received text with an approved file and lets a reporter decide what
happens on a mismatch. The repository supplies the transcript text, the file locations, the
failure reporter, and the update switch.

Evidence: 387 stars, Apache-2.0, repository active and not archived, release 31.0.0 on 2026-06-18
(older than the seven-day cooldown), Java 8 bytecode, compiled against `junit-jupiter-api` 6.1.0.
Runtime dependencies are `approvaltests-util` and `commons-lang3`, which the test classpath already
has through REST Assured. OSV and the GitHub advisory database list no vulnerabilities.

Spike: 40 snapshot tests in four classes with the repository's Java 25, JUnit 6.1.3, Surefire
3.6.0, and concurrent class and method execution. Three verification runs passed. A missing
baseline failed without creating it. A changed baseline failed. Received files were written next to
the baseline by default, which the custom namer moves under `target/`.

* Good, because it runs in the existing JVM and adds about 1 MB of test-only jars.
* Good, because one scenario is one file.
* Good, because it never creates an approved file unless a reporter approves it.
* Good, because the namer, reporter, and writer are plain interfaces.
* Bad, because it has no JUnit extension. The repository names scenarios and reports failures
  itself, as the Consequences section lists.
* Bad, because its default reporter opens desktop diff tools and its JUnit reporter prints only the
  full expected and actual text, so the repository adds its own reporter.
* Bad, because its duplicate-verification tracker is not thread-safe and needs a lock.
* Bad, because it downloads helper scripts at test time unless an environment variable disables it.

### java-snapshot-testing

java-snapshot-testing stores the snapshots of one test class in one `.snap` file with delimited
entries and compares them through a JUnit 5 extension.

Evidence: 129 stars, MIT, not archived, release 4.2.0 on 2026-06-30 under the new group
`io.github.codedabble-dev`. Open issues report wrong snapshot names with parallel execution and
nested tests, and duplicated shaded classes.

Spike: the same 40 tests. Verification passed under concurrent execution with `CI=true`, and the
diff output was clear.

* Good, because the diff output is line-level and readable.
* Good, because the JUnit extension handles naming.
* Bad, because without a CI marker a normal local run silently wrote all 40 missing snapshots into
  `src/test/java`, which the issue forbids.
* Bad, because a failure writes `.snap.debug` files next to the test sources.
* Bad, because the published POMs declare no dependencies, and each jar is about 4.2 MB with shaded
  AssertJ and opentest4j inside.
* Bad, because one file holds every scenario of a class.

### Selfie

Selfie stores the snapshots of one test class in one `.ss` file next to the test source and
selects read-only, interactive, or overwrite mode through a system property or environment
variable.

Evidence: 101 stars, Apache-2.0, not archived, release 3.1.1 on 2026-05-26. It is written in Kotlin
and depends on `kotlin-stdlib` 2.2.21. A race when writing snapshots under parallel JUnit execution
was fixed upstream.

Spike: the same 40 tests. With `selfie=readonly`, a missing snapshot failed without writing, and
verification passed under concurrent execution. The failure message named the first changed line
and column.

* Good, because read-only and overwrite modes are built in.
* Good, because the failure message is short and precise.
* Bad, because it adds the Kotlin standard library and registers a JUnit listener for the whole test
  run.
* Bad, because its default local mode writes new snapshots, so the build must force read-only mode.
* Bad, because snapshot files live in `src/test/java`, and one file holds every scenario of a
  class.
* Bad, because it barely meets the 100-star minimum.

### tui-test

tui-test drives a real terminal emulator such as Alacritty or xterm.js, controls its size, and
asserts screen snapshots with optional styles. Its core is Rust, with Rust, Python, and JavaScript
bindings and a command-line tool.

Evidence: 277 stars, MIT, not archived. Release 0.1.0 appeared on 2026-10-03, inside the
seven-day cooldown; earlier releases are betas.

* Good, because it is the most terminal-aware option: terminal size, styles, and screen state.
* Bad, because it has no Java binding and cannot run inside the Maven test JVM or reuse the
  in-process `TrelloBoardSetupMain.run(...)` boundary.
* Bad, because it snapshots the final screen, which loses prompt order and transient output; the
  issue keeps final-screen snapshots out of scope.
* Bad, because the current release is pre-1.0 and too new for the cooldown.

### VHS

VHS runs scripted terminal sessions from a tape file and writes GIF, video, or text output that can
serve as golden files.

Evidence: about 21,000 stars, MIT, release v0.12.1 on 2026-09-24. It requires `ttyd` and `ffmpeg`
on PATH.

* Good, because it is popular and well maintained.
* Bad, because it needs a Go binary, `ttyd`, and `ffmpeg` in CI and on every contributor machine.
* Bad, because it records the rendered screen, not separate streams or a prompt-ordered transcript.
* Bad, because it has no JUnit integration.

### insta

insta is the standard Rust snapshot library, with review tooling through `cargo insta`.

Evidence: about 2,970 stars, Apache-2.0, release 1.49.0 on 2026-10-03.

* Good, because its review workflow is mature.
* Bad, because it needs the Rust toolchain and Rust test code, which this Java project does not
  have.
* Bad, because its command-snapshot companion, insta-cmd, fails the star requirement.

### A Repository-Owned Snapshot Engine

The repository would implement baseline files, comparison, candidate files, diffs, and updates
itself.

* Good, because it has no new dependency.
* Bad, because the issue allows it only when no maintained library meets the requirements, and
  ApprovalTests does.
* Bad, because the project would maintain storage, comparison, and update code that a library
  already provides.

## More Information

[GitHub issue #602](https://github.com/martin-francois/symphony-trello/issues/602) defines the
scenarios and requirements. [ADR 0055](0055-test-deduplication-layer-boundaries.md) keeps detailed
failure and validation matrices in lower-layer tests; the snapshots cover one happy path per
command. `CONTRIBUTING.md` documents the verification, update, Docker, and Podman commands.
