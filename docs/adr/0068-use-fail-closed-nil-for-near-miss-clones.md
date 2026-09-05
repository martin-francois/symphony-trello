---
status: accepted
date: 2026-07-14
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #568](https://github.com/martin-francois/symphony-trello/issues/568)"
  - "[ADR 0039](0039-curated-pmd-rules-and-cpd-baseline.md)"
  - "[StoneDetector](https://github.com/StoneDetector/StoneDetector)"
  - "[NIL](https://github.com/kusumotolab/NIL)"
informed: [Future maintainers, Contributors]
---

# Use Fail-Closed NIL for Near-Miss Clone Detection

## Context and Problem Statement

PMD CPD blocks exact and token-level Java duplication, but it does not target every renamed or more
heavily modified clone. Issue #568 asks for a complementary detector only when it can analyze the
complete Java 25 tree reproducibly, add useful signal, and fail rather than silently omit inputs.

StoneDetector was evaluated first from published image digest
`sha256:6de535e8f96438cfbc06496135d4889cd56f5f1bf102611863e85fb9afa0dfae`, corresponding to upstream
revision `de8a5ebb0fd84fc21d99a517d86971f2935b1008`. NIL was then evaluated from release `v2.0.0`, commit
`967bb983890bf2c4145d2155dfe0e88c02480ad6`.

## Decision Drivers

* Analyze every tracked production and test Java source, including Java 25 syntax.
* Fail on parser or detector omissions instead of presenting a partial report as clean.
* Add reviewed near-miss signal beyond CPD without maintaining two overlapping new gates.
* Keep local and hosted execution deterministic, isolated, and supply-chain pinned.
* Make every retained clone pair explain why separation is clearer than abstraction.

## Considered Options

* Integrate StoneDetector and retain CPD.
* Apply a narrow fail-closed compatibility patch to NIL and retain CPD.
* Integrate both StoneDetector and NIL.
* Retain CPD alone.

## Decision Outcome

Chosen option: "Apply a narrow fail-closed compatibility patch to NIL and retain CPD".

The repository builds NIL from the pinned upstream commit in `config/nil/Dockerfile`. The source
archive SHA-256, Gradle distribution checksum, strict Gradle dependency verification metadata,
Eclipse JDT and GradleUp Shadow versions, builder and runtime image digests, compatibility patch, and
resulting JAR SHA-256 are versioned. The patch moves Java parsing from JLS 21 to JLS 25, rejects JDT
parser errors, emits one machine-readable parsed-file record per input, makes the archive
reproducible, and replaces NIL's abandoned Shadow plugin coordinate with its maintained GradleUp
successor so the pinned Gradle build emits no deprecation warning.

`node scripts/nil-clone-detection.mjs` copies only paths returned by `git ls-files` under the two Java
source trees. Analysis runs as a non-root caller with a read-only root and source mount, no network,
no capabilities, no-new-privileges, bounded PIDs and file descriptors, a 1 GiB JVM heap, two active
processors, and a ten-minute host timeout. The wrapper verifies exact parsed-file accounting,
rejects malformed or out-of-tree output, normalizes pair direction and repository-relative paths,
and compares the result with `config/nil/baseline.json`. The normalized report is written beneath
`target/nil-clones/` and uploaded by CI.

The selected threshold is at least 25 lines, at least 200 tokens, and 90 percent LCS verification.
It reduced the initial patched-NIL result from 6,751 broad pairs to 19 high-confidence pairs. Review
then removed one production pair by centralizing the workpad-update safety target and one test pair
by parameterizing equivalent invalid-selector cases. The remaining 17 pairs have location-specific
review rationales in the baseline. A new or stale pair makes the gate fail.

PMD CPD stays blocking through Maven. CPD provides a fast exact/token-level boundary; NIL provides a
slower near-miss boundary for relevant Java changes.

### Consequences

* Good, because parser recovery can no longer make a malformed or unsupported input look clean.
* Good, because the detector found and helped remove production and test duplication beyond CPD's
  clean report.
* Good, because a single wrapper owns local and CI inputs, limits, normalization, and failure modes.
* Bad, because the first uncached container build downloads the pinned NIL and Gradle dependency
  graph and takes roughly one minute; cached analysis is approximately two seconds and uses about
  500 MiB peak RSS in the measured environment.
* Bad, because the repository owns a small compatibility patch until upstream supports Java 25 and
  parser accounting directly.
* Neutral, because line-range changes can require a reviewed baseline update even when the clone
  bodies did not change.

### Confirmation

Run:

```bash
node scripts/nil-clone-detection.mjs
./mvnw -q pmd:cpd
./mvnw -q spotless:check verify
```

The wrapper's script tests cover detector failure, timeout, parser omission, malformed output,
normalization, and baseline comparison. The workflow runs only for Java or detector-boundary changes
and uploads `target/nil-clones/report.json` whenever the wrapper produced one, including when a
complete analysis finds an unreviewed or stale baseline pair.

## Pros and Cons of the Options

### Integrate StoneDetector

StoneDetector's structural control-flow representation produced useful pairs, including families
not reported by NIL. However, every tested configuration produced only 251 of 255 ASTs and encoded
about 1,606 of 1,976 methods. The stable failures included 370 unsupported-operator records across
63 files, partial source positions, inconsistent stacks, and Java 25 unnamed-variable parsing.
Single-threading made its shared error report deterministic but did not repair the omissions.
Integrating those partial findings would make the baseline untrustworthy.

### Patch and Integrate NIL

NIL's algorithm completed once its JDT boundary was updated and made fail-closed. Two clean builds
produced the same JAR, valid inputs produced identical normalized findings, and a deliberately
malformed Java file changed the result from silent success to a nonzero parser failure.

### Integrate Both New Detectors

The tools are algorithmically complementary. At an intermediate NIL threshold, 29 normalized file
pairs overlapped, 21 were Stone-only, and 24 were NIL-only. At the selected strict NIL threshold,
seven of ten file pairs overlapped, three were NIL-only, and Stone reported 43 additional file
pairs. Those Stone-only results cannot form a gate because they came from incomplete analysis.
Running both would add cost and conflicting baselines without a second trustworthy result.

### Retain CPD Alone

This would avoid the compatibility patch and container build, but would discard repeatable near-miss
findings that already identified independently useful production and test cleanup.

## More Information

StoneDetector has no built-in fail-on-analysis-error switch. Its `--error-file` records caught
per-file and per-method failures while the process still exits successfully. A wrapper can reject
that file, but it cannot reconstruct omitted ASTs or method encodings. NIL's original omission was
different: JDT parser problems were never inspected. The compatibility patch closes that boundary
and the wrapper independently checks that every intended file produced exactly one parser-success
record.
