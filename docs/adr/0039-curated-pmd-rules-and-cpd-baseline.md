---
status: accepted
date: 2026-05-31
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #78](https://github.com/martin-francois/symphony-trello/issues/78)"
  - "[GitHub issue #87](https://github.com/martin-francois/symphony-trello/issues/87)"
  - "pom.xml"
  - "config/pmd/ruleset.xml"
  - "config/pmd/candidate-ruleset.xml"
informed: [Future maintainers, Contributors]
---

# Expand PMD With Curated Rules and Candidate Reports

## Context and Problem Statement

The default validation command already runs PMD, but the blocking ruleset was intentionally tiny. It
only checked inline fully qualified Java type names. Issue #78 asks for PMD to become a broader
curated source-level analyzer and asks maintainers to evaluate CPD duplication checks.

How should the project expand PMD without importing unclassified rule categories or making
duplication reports fail the build before the baseline is understood?

## Decision Drivers

* Keep the default local validation command as `./mvnw -q spotless:check verify`.
* Promote only PMD rules that are high-signal on the current codebase.
* Fix justified findings before making a rule blocking.
* Keep broad or unclassified rules available for measurement without failing normal verification.
* Treat "noisy" precisely: noisy means false positives, findings that are cleaner to leave as they
  are, or findings whose cleanup cost is higher than their value. A rule is not noisy only because it
  finds many justified problems.
* Evaluate CPD, but do not make it blocking while the current baseline still needs cleanup or a
  deliberate acceptance decision.
* Keep PMD configuration Maven-native and easy to run locally.

## Considered Options

* Curate blocking PMD rules and keep additional rules in a candidate profile.
* Import broad PMD categories directly into the blocking ruleset.
* Keep PMD permanently narrow.
* Add CPD `cpd-check` to `verify` immediately.
* Defer CPD while documenting the measured baseline.

## Decision Outcome

Chosen option: "Curate blocking PMD rules and keep additional rules in a candidate profile", because
it improves the default source-level feedback loop while keeping unclassified rules out of the
blocking gate.

Chosen option for CPD: "Defer CPD while documenting the measured baseline", because the default CPD
report currently finds 46 duplications, mostly in setup and live-test fixtures. That is useful
cleanup input, but it has not yet been cleaned up or deliberately accepted as an enforceable
baseline.

### Consequences

* Good, because `./mvnw -q spotless:check verify` now enforces a broader curated PMD ruleset.
* Good, because the promoted rules first ran on the repository and their findings were fixed.
* Good, because `-Ppmd-candidate` gives maintainers a report-only path for additional PMD rules.
* Good, because the PMD ruleset path is configurable through the Maven `pmd.ruleset` property.
* Good, because candidate rules such as `AvoidDuplicateLiterals` and `UseConcurrentHashMap` can stay
  visible until their findings are fixed, tuned, or deliberately left candidate-only.
* Good, because CPD was measured before deciding whether it should block the build.
* Bad, because future PMD updates may change rule behavior and require tuning.
* Bad, because CPD duplication is not yet enforced automatically.

### Confirmation

Run the default validation command:

```bash
./mvnw -q spotless:check verify
```

Run the PMD candidate report:

```bash
./mvnw -q -Ppmd-candidate pmd:pmd
```

Run the CPD report:

```bash
./mvnw -q pmd:cpd
```

Do not promote candidate rules or CPD to the blocking gate until current findings are fixed, tuned,
or accepted with a clear reason.

## Pros and Cons of the Options

### Curate Blocking PMD Rules and Keep Additional Rules in a Candidate Profile

Add selected standard PMD rules to `config/pmd/ruleset.xml`, add
`config/pmd/candidate-ruleset.xml`, and let Maven switch rulesets through `pmd.ruleset`.

* Good, because the normal validation command catches more source-level issues.
* Good, because broad or preference-heavy rules stay measurable without breaking normal work.
* Good, because contributors can run the same reports locally.
* Bad, because maintainers must periodically review candidate findings.

### Import Broad PMD Categories Directly Into the Blocking Ruleset

Reference complete PMD categories such as `errorprone`, `bestpractices`, or `performance`.

* Good, because it would maximize PMD coverage quickly.
* Bad, because the current candidate report shows hundreds of unclassified findings from broad rules.
* Bad, because unclassified gates teach contributors and agents to suppress instead of fix.

### Keep PMD Permanently Narrow

Leave only `UnnecessaryFullyQualifiedName` in the blocking PMD ruleset.

* Good, because it keeps PMD simple and fast.
* Bad, because it ignores high-signal standard rules that already found justified cleanup.
* Bad, because it conflicts with PMD's intended role as a curated source-level analyzer.

### Add CPD `cpd-check` to `verify` Immediately

Bind PMD CPD duplication checks to Maven `verify`.

* Good, because duplicated code would fail the build.
* Bad, because the current default CPD report finds 46 duplications.
* Bad, because most findings are test fixture repetition that needs a separate cleanup plan.

### Defer CPD While Documenting the Measured Baseline

Keep `./mvnw -q pmd:cpd` as the local report command and do not bind `cpd-check` yet.

* Good, because the project has measured CPD before deciding.
* Good, because maintainers can use the report to plan fixture cleanup.
* Bad, because new duplication is not automatically blocked yet.

## More Information

The first candidate PMD run after the promoted-rule fixes reported 556 remaining candidate findings:
426 `AvoidDuplicateLiterals`, 36 `UseConcurrentHashMap`, 24 `LiteralsFirstInComparisons`, 14
`CloseResource`, 14 `ConsecutiveAppendsShouldReuse`, 13 `GuardLogStatement`, 8
`PreserveStackTrace`, 7 `LambdaCanBeMethodReference`, 4 `ConsecutiveLiteralAppends`, 3
`IdenticalCatchBranches`, 3 `SimplifyBooleanReturns`, 2 `AvoidCatchingThrowable`, 1
`UnnecessaryConstructor`, and 1 `UseTryWithResources`.

The CPD report with PMD's default `minimumTokens` value found 46 duplications. It should be revisited
after setup and live-test fixture duplication has a focused cleanup issue or PR.
