---
status: accepted
date: 2026-06-01
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #130](https://github.com/martin-francois/symphony-trello/issues/130)"
  - "[ADR 0034](0034-optional-error-prone-profile.md)"
  - "[ADR 0041](0041-report-only-picnic-error-prone-profile.md)"
  - "[ADR 0043](0043-base-error-prone-baseline-cleanup.md)"
  - "[ADR 0044](0044-curated-picnic-refaster-profile.md)"
  - "pom.xml"
informed: [Future maintainers, Contributors]
---

# Enforce Error Prone and Picnic in Verify

## Context and Problem Statement

The static-analysis rollout first added Error Prone and Picnic Error Prone Support as optional
profiles. The baselines have now been cleaned and triaged:

* Base Error Prone warnings are clean.
* Useful Picnic bug-check findings are fixed or intentionally disabled.
* Useful Picnic Refaster rule families are applied and narrowed to an accepted set.
* Rejected Refaster families have documented reasons.

The remaining question is whether selected Error Prone and Picnic checks should stay opt-in or become
part of the normal local validation command.

## Decision Drivers

* Make `./mvnw -q spotless:check verify` enforce the accepted static-analysis checks.
* Keep selected checks blocking only after the relevant baseline is clean.
* Keep disabled style checks explicit.
* Keep Refaster constrained to reviewed rule families.
* Avoid retaining optional profiles that duplicate the default compiler configuration.
* Keep future rewrite or candidate exploration separate from normal verification.

## Considered Options

* Keep Error Prone and Picnic as optional profiles.
* Promote only base Error Prone to normal verification.
* Promote selected Error Prone, Picnic bug checks, and selected Refaster rules to normal
  verification.
* Add separate Maven executions that run optional profiles during `verify`.

## Decision Outcome

Chosen option: "Promote selected Error Prone, Picnic bug checks, and selected Refaster rules to
normal verification", because their baselines are clean and they now represent accepted project
policy rather than exploratory reports.

The default Maven compiler configuration now runs Error Prone with Picnic support and Refaster. It
uses the same Java 25 module export flags that were proven in the optional profiles.

The blocking compiler configuration:

* enables Error Prone during normal production-source compilation;
* enables `OptionalNotPresent` as an error;
* keeps `StaticImport` disabled because it is a style preference for this project;
* keeps `LexicographicalAnnotationAttributeListing` disabled because picocli attribute order can be
  user-facing;
* loads Picnic bug checks from `error-prone-contrib`;
* loads Refaster from `refaster-runner`;
* limits Refaster with `Refaster:NamePattern` to the accepted rule families from
  [ADR 0044](0044-curated-picnic-refaster-profile.md);
* removes `-XepAllErrorsAsWarnings` so accepted checks fail the build.

The old `error-prone`, `picnic-error-prone`, and `picnic-refaster` profiles are removed because they
would duplicate the default compiler path. PMD candidate and jPinpoint remain separate report-only
profiles because their remaining rule sets are not accepted as blocking checks.

Applying the same selected Picnic configuration to test compilation exposed a separate test-source
baseline. [GitHub issue #145](https://github.com/martin-francois/symphony-trello/issues/145) tracks
that work so justified test-source findings are not hidden or broadly suppressed.

### Consequences

* Good, because normal validation now enforces the selected production-source compiler-level
  static-analysis checks.
* Good, because agents and contributors do not need to remember extra profile commands for accepted
  checks.
* Good, because future production-source violations fail at compile time.
* Good, because selected Refaster suggestions are constrained to reviewed rule families.
* Bad, because every normal compile pays the Error Prone and Picnic compiler-plugin cost.
* Bad, because future Java, Maven Compiler Plugin, Error Prone, or Picnic upgrades may require
  maintenance of compiler module flags.

### Confirmation

Run:

```bash
./mvnw clean compile
./mvnw -q spotless:check verify
```

Both commands should pass with Error Prone, Picnic bug checks, and selected Refaster rules enabled
for production sources.

## Pros and Cons of the Options

### Keep Error Prone and Picnic as Optional Profiles

Leave the optional profiles available and keep normal validation unchanged.

* Good, because normal compile stays faster.
* Bad, because accepted checks can be skipped.
* Bad, because the final static-analysis goal says useful checks should be enforced by normal
  verification.

### Promote Only Base Error Prone

Run base Error Prone during normal production-source compile and keep Picnic optional.

* Good, because it enforces the base compiler checks with less plugin surface.
* Bad, because cleaned and accepted Picnic rule families would remain optional.
* Bad, because agents would still need extra commands to catch accepted Picnic findings.

### Promote Selected Error Prone, Picnic, and Refaster Checks

Run the cleaned selected checks in normal production-source compiler configuration.

* Good, because `./mvnw -q spotless:check verify` enforces the accepted production-source Java
  static-analysis gate.
* Good, because the configuration uses already-triaged rule flags and Refaster patterns.
* Bad, because normal compile becomes heavier.

### Add Separate Maven Executions During Verify

Keep the optional profiles and call them through additional `verify` executions.

* Good, because the exploratory profile names would remain available.
* Bad, because Maven would run extra compiler passes for checks that can run in the normal compiler
  path.
* Bad, because duplicated compiler configuration is harder to maintain.

## More Information

This decision does not make every possible Error Prone, Picnic, or Refaster rule accepted. Future
rule families must still be measured, fixed, tuned, suppressed, or rejected before they become
blocking.

Test-source Picnic findings, PMD candidate, and jPinpoint remain tracked separately because their
remaining rule sets are not yet accepted as blocking checks. Semgrep and CodeQL remain separate
later work.
