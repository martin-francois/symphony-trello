---
status: accepted
date: 2026-05-31
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #79](https://github.com/martin-francois/symphony-trello/issues/79)"
  - "[GitHub issue #130](https://github.com/martin-francois/symphony-trello/issues/130)"
  - "[PMD Maven plugin documentation](https://pmd.github.io/pmd/pmd_userdocs_tools_maven.html)"
  - "[Apache Maven PMD plugin multi-module configuration](https://maven.apache.org/plugins/maven-pmd-plugin/examples/multi-module-config.html)"
  - "[PMD-jPinpoint-rules](https://github.com/jborgers/PMD-jPinpoint-rules)"
  - "pom.xml"
  - "config/pmd/jpinpoint-ruleset.xml"
  - "config/pmd/vendor/jpinpoint-java-rules.xml"
informed: [Future maintainers, Contributors]
---

# Add a Report-Only jPinpoint PMD Profile

## Context and Problem Statement

The project already uses a curated PMD ruleset in the default validation command and a separate
candidate PMD profile for standard PMD rules.
[GitHub issue #79](https://github.com/martin-francois/symphony-trello/issues/79) asks whether
`jborgers/PMD-jPinpoint-rules` can add useful third-party PMD checks for performance, logging,
concurrency, data-mixup, sustainability, and related source-level risks.

jPinpoint is narrower than PMD itself and has a different release channel. The upstream
`PMD-jPinpoint-rules` repository publishes source releases, but the rules repository does not publish
a directly named `PMD-jPinpoint-rules` Maven Central artifact. The related
`com.jpinpoint.sonar:sonar-pmd-jpinpoint` Maven Central artifact contains a PMD ruleset resource,
but the latest stable artifact is older than the current `PMD-jPinpoint-rules` `pmd7` branch. The
branch has recent false-positive fixes and new rule work.

The copied upstream snapshot includes fixes such as false-positive corrections for
`UnconditionalCreatedLogArguments`, `ImplementEqualsHashCodeOnValueObjects`, and
`AvoidTimeUnitConfusion`, plus expanded `AvoidLoadingAllFromFile` coverage.

How should Symphony for Trello evaluate jPinpoint without changing the default `verify` gate or the
standard PMD candidate profile?

## Decision Drivers

* Keep `./mvnw -q spotless:check verify` unchanged.
* Keep rule execution reproducible and reviewable.
* Use the recent PMD 7 ruleset state that includes upstream false-positive fixes.
* Keep third-party jPinpoint configuration separate from standard PMD candidate rules.
* Measure all initial findings before promoting any individual rule.
* Avoid broad suppressions for the first baseline.

## Considered Options

* Vendor the generated jPinpoint ruleset XML into this repository.
* Add a report-only `jpinpoint` Maven profile that loads the Maven Central Sonar plugin artifact as
  a PMD plugin dependency.
* Defer jPinpoint until the rules project publishes a dedicated PMD rules artifact.
* Add selected jPinpoint rules directly to the blocking PMD ruleset.

## Decision Outcome

Chosen option: "Vendor the generated jPinpoint ruleset XML into this repository", because it gives
the project a reproducible snapshot of the current PMD 7 Java ruleset while keeping jPinpoint
separate from the blocking and standard candidate PMD rules.

The profile uses `config/pmd/vendor/jpinpoint-java-rules.xml`, copied from
`jborgers/PMD-jPinpoint-rules` branch `pmd7` at commit
`306079521fd97152ada849c7bc4c53ccee6993c8`. Every rule in that generated ruleset uses PMD's
built-in XPath rule class, so no third-party rule implementation JAR is needed. The upstream
Apache-2.0 license is copied into `config/pmd/vendor/LICENSE-PMD-jPinpoint-rules`. The local
`config/pmd/jpinpoint-ruleset.xml` wrapper keeps the project command stable:

```bash
./mvnw -q -Pjpinpoint test-compile pmd:pmd@jpinpoint-report
```

Do not add jPinpoint to `verify` until selected rules have been triaged and every remaining finding
is either fixed, tuned out by rule configuration, or narrowly suppressed with a reason. A rule is not
noisy only because it reports many justified findings. A finding is justified when fixing it would
make the code meaningfully better, cleaner, safer, faster, or more maintainable; compiling
successfully is not enough to dismiss a supplementary static-analysis finding.

The jPinpoint profile adds a separate `jpinpoint-report` PMD execution. It writes its direct report
to `target/jpinpoint-pmd/pmd.xml` and does not change the profile-wide PMD ruleset properties. This
keeps the blocking PMD gate active even when a maintainer runs a lifecycle command such as
`./mvnw -Pjpinpoint verify`.

The local wrapper keeps jPinpoint's `UnresolvedType` rule enabled, with a narrow ruleset-level
suppression for one known false-positive location. A raw jPinpoint run after `test-compile` reported
23 `UnresolvedType` findings, all in
`EffectiveConfig.withResolvedBoardId(...)`. Those findings point at valid nested-record accessor
calls such as `tracker.kind()` and `polling`. For this rule, successful `test-compile` and the
presence of the relevant `EffectiveConfig` nested record classes under `target/classes` are evidence
that the type-resolution finding itself is false, not a general reason to ignore static-analysis
findings. The wrapper suppresses only violations under that method instead of disabling the whole
rule.

### Consequences

* Good, because the profile is report-only and does not affect normal verification.
* Good, because the upstream snapshot is pinned to a commit and committed for review.
* Good, because the rules are loaded from a reviewed local file instead of a manual download at
  execution time.
* Good, because initial findings can be classified from the normal PMD XML report.
* Good, because the false-positive `UnresolvedType` location is tuned out of the wrapper instead of
  disabling the whole rule or forcing broad suppressions in source.
* Bad, because vendoring a generated third-party ruleset creates update and license-review work.
* Bad, because Renovate will not automatically discover upstream ruleset changes.

### Confirmation

Run the jPinpoint report:

```bash
./mvnw -q -Pjpinpoint test-compile pmd:pmd@jpinpoint-report
```

Review `target/jpinpoint-pmd/pmd.xml`. Keep findings report-only until fixed, tuned, or deliberately
accepted.

Run the default validation command:

```bash
./mvnw -q spotless:check verify
```

## Pros and Cons of the Options

### Vendor the Generated jPinpoint Ruleset XML

Commit the upstream generated XML into `config/pmd/vendor`.

* Good, because the project can use the newer PMD 7 ruleset state with recent false-positive fixes.
* Good, because the ruleset is reproducible and reviewed like normal source.
* Good, because the profile does not depend on a Sonar-named artifact layout.
* Bad, because updates must be done manually by copying a newer upstream snapshot.
* Bad, because the committed XML is large.

### Add a Report-Only Maven Profile with the Sonar Plugin Artifact

Load `com.jpinpoint.sonar:sonar-pmd-jpinpoint` as a PMD plugin dependency only when the
`jpinpoint` profile is active, and point PMD at a local wrapper ruleset.

* Good, because it uses Maven Central and avoids manual downloads.
* Good, because it keeps jPinpoint separate from standard PMD candidate rules.
* Good, because the command is easy for maintainers and agents to rerun.
* Bad, because the project depends on a Sonar plugin artifact containing a PMD ruleset resource.
* Bad, because the latest stable Sonar plugin artifact is older than the upstream PMD 7 ruleset
  branch.

### Defer Until a Dedicated PMD Rules Artifact Exists

Document that jPinpoint cannot be evaluated cleanly yet.

* Good, because it avoids relying on an artifact intended for SonarQube.
* Bad, because a Maven-native artifact with the PMD ruleset is already available.
* Bad, because it would leave useful local measurement undone.

### Add Selected jPinpoint Rules to the Blocking PMD Ruleset

Promote individual jPinpoint checks immediately.

* Good, because high-signal third-party rules would fail the default build.
* Bad, because the initial baseline has not been cleaned or classified enough for a blocking gate.
* Bad, because third-party rule behavior and false-positive rates need measurement first.

## More Information

After adding the narrow `UnresolvedType` suppression, the first jPinpoint run on this branch reported
322 findings across 17 rules:

| Count | Rule | Classification |
| --- | --- | --- |
| 79 | `AvoidLoadingAllFromFile` | Likely useful for unbounded production reads; config and fixture hits need size/context triage before fixing or suppressing. |
| 67 | `AvoidInMemoryStreamingDefaultConstructor` | Many findings; triage production paths separately from tests and intentionally small in-memory buffers. |
| 33 | `ObjectMapperCreatedForEachMethodCall` | Likely useful for production paths; tests and CLI setup helpers need targeted review before deciding fix or suppression. |
| 20 | `AvoidObjectMapperAsField` | Context-dependent; conflicts with the common Quarkus and Jackson pattern of injected or shared mappers. |
| 20 | `AvoidImplicitlyRecompilingRegex` | Likely useful; should be grouped with regex constant cleanup. |
| 18 | `AvoidUnguardedMutableFieldsInObjectsUsingSynchronized` | Potentially useful for orchestrator concurrency review; needs focused concurrency triage before promotion. |
| 17 | `AvoidForEachInStreams` | Mostly style or readability preference; decide whether this project wants the style before fixing. |
| 17 | `AvoidUnguardedAssignmentToNonFinalFieldsInObjectsUsingSynchronized` | Potentially useful for orchestrator concurrency review; needs focused concurrency triage before promotion. |
| 15 | `AvoidExposingMutableRecordState` | Useful where records expose mutable collections; needs focused API review before fixing. |
| 11 | `LimitStatementsInLambdas` | Mostly style or readability preference; decide whether this project wants the style before fixing. |
| 6 | `AvoidRecompilingPatterns` | Useful and likely low-risk in production code; good follow-up candidate. |
| 6 | `InitializeComparatorOnlyOnce` | Useful where comparators are hot, otherwise minor allocation cleanup. |
| 5 | `UsingSuppressWarnings` | Duplicates the repository's existing PMD suppression policy; not a promotion candidate. |
| 5 | `AvoidMutableLists` | Context-dependent because picocli mutates option lists during parsing. |
| 1 | `AvoidInfiniteRecursion` | False positive for guarded recursive directory creation. |
| 1 | `ImproperVariableName` | Style-only naming preference; not a promotion candidate. |
| 1 | `NonComparableMapKeys` | False positive for a `LinkedHashMap` test fake that does not require comparable keys. |

No source fixes were made in this spike. The remaining findings are cross-cutting and overlap the
parallel static-analysis cleanup track; fixing them belongs in focused follow-up issues or PRs after
maintainers choose the useful jPinpoint rule families. No source suppressions were added.
