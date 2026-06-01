---
status: superseded by [ADR 0045](0045-enforce-error-prone-and-picnic-in-verify.md)
date: 2026-05-31
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #81](https://github.com/martin-francois/symphony-trello/issues/81)"
  - "[Error Prone Support installation guide](https://error-prone.picnic.tech/)"
  - "[Error Prone Support compatibility matrix](https://error-prone.picnic.tech/compatibility/)"
  - "[Maven Central: error-prone-contrib](https://central.sonatype.com/artifact/tech.picnic.error-prone-support/error-prone-contrib)"
  - "pom.xml"
informed: [Future maintainers, Contributors]
---

# Add Report-Only Picnic Error Prone Profiles

This ADR records the historical decision to introduce Picnic checks as report-only profiles. It is
superseded by [ADR 0045](0045-enforce-error-prone-and-picnic-in-verify.md), which promotes the
selected clean production-source rule families into normal verification.

## Context and Problem Statement

[GitHub issue #81](https://github.com/martin-francois/symphony-trello/issues/81) asks whether
Picnic Error Prone Support is useful after the base Error Prone profile from
[ADR 0034](0034-optional-error-prone-profile.md) proved compatible with this Java 25 Maven build.

Picnic Error Prone Support provides extra Error Prone bug checkers and Refaster rewrite rules. It is
opinionated and can report many style or modernization findings. The project needs the signal
without changing the normal validation command before the baseline is understood.

## Decision Drivers

* Keep `./mvnw -q spotless:check verify` unchanged.
* Keep Picnic separate from the base `error-prone` profile.
* Use Maven Central artifacts, pinned through Maven properties.
* Evaluate bug checkers separately from Refaster rewrite suggestions.
* Keep all findings as warnings until focused cleanup PRs handle each useful rule family.
* Do not add an automatic rewrite profile.

## Considered Options

* Add a warning-oriented `picnic-error-prone` profile for bug checkers and a separate
  `picnic-refaster` profile for rewrite suggestions.
* Add Picnic modules directly to the base `error-prone` profile.
* Add only Picnic bug checkers and defer Refaster.
* Make selected Picnic checks blocking immediately.

## Decision Outcome

Chosen option: "Add a warning-oriented `picnic-error-prone` profile for bug checkers and a separate
`picnic-refaster` profile for rewrite suggestions", because it gives maintainers local signal while
keeping rewrite-style suggestions explicit.

Run the Picnic bug-check profile with:

```bash
./mvnw -Ppicnic-error-prone clean compile
```

Run the Refaster measurement profile with:

```bash
./mvnw -Ppicnic-refaster clean compile
```

Both profiles include the same Java 25 Error Prone compiler flags as the base `error-prone` profile.
`picnic-error-prone` adds `tech.picnic.error-prone-support:error-prone-contrib:0.29.0`.
`picnic-refaster` also adds `tech.picnic.error-prone-support:refaster-runner:0.29.0`.
Both profiles disable `StaticImport` and `LexicographicalAnnotationAttributeListing`. `StaticImport`
is a broad style preference that does not improve correctness and would make the code less
consistent with the current style.
`LexicographicalAnnotationAttributeListing` conflicts with keeping picocli subcommand order aligned
with user-facing help output.

The `clean` phase is part of both commands so Maven recompiles sources and does not skip analysis
when classes are already up to date. Do not run these commands with Maven `-q`; the profiles report
findings as warnings.

No Picnic rule is added to the default `verify` gate in this decision.

### Consequences

* Good, because Picnic can be run locally without changing normal validation.
* Good, because Renovate can update the pinned Maven property.
* Good, because bug checks and Refaster suggestions can be triaged separately.
* Good, because warning output is visible and can guide focused follow-up PRs.
* Bad, because findings are still optional until selected rules are promoted.
* Bad, because the profile duplicates some compiler flags from the base Error Prone profile.

### Confirmation

Run:

```bash
./mvnw -Ppicnic-error-prone clean compile
./mvnw -Ppicnic-refaster clean compile
./mvnw -q spotless:check verify
```

## Pros and Cons of the Options

### Separate Picnic Bug-Check and Refaster Profiles

Add two optional Maven profiles.

* Good, because bug checks and rewrite suggestions have different review workflows.
* Good, because maintainers can run only the profile they need.
* Bad, because the POM has one more optional profile to maintain.

### Add Picnic Modules Directly to the Base Error Prone Profile

Make `-Perror-prone` also run Picnic.

* Good, because there is one fewer profile.
* Bad, because it changes the meaning of the existing base profile.
* Bad, because base Error Prone and Picnic baselines become harder to compare.

### Add Only Picnic Bug Checkers and Defer Refaster

Skip Refaster integration for now.

* Good, because the first profile is smaller.
* Bad, because #81 explicitly asks to evaluate Refaster separately from bug checkers.

### Make Selected Picnic Checks Blocking Immediately

Promote selected checks into normal `verify`.

* Good, because chosen findings would be impossible to miss.
* Bad, because the initial baseline has not been cleaned or classified enough for a blocking gate.

## More Information

The first `picnic-error-prone` run on this branch completed successfully and reported 143 warnings
across 15 rule names:

| Count | Rule | Classification |
| ---: | --- | --- |
| 94 | `StaticImport` | Style-only; disabled because the project is not adopting a broad static-import convention. |
| 16 | `OptionalOrElseGet` | Useful and handled by [GitHub issue #136](https://github.com/martin-francois/symphony-trello/issues/136) follow-up work. |
| 13 | `TimeZoneUsage` | Potentially useful; needs a focused clock-injection design before source changes. |
| 6 | `CollectorMutability` | Useful in some locations; needs mutability-by-contract review before promotion. |
| 2 | `UnusedVariable` | Existing base Error Prone warning; not Picnic-specific. |
| 2 | `UnusedNestedClass` | Existing base Error Prone warning; not Picnic-specific. |
| 2 | `SystemConsoleNull` | Existing base Error Prone warning; not Picnic-specific. |
| 1 | `SelfAssignment` | Existing base Error Prone warning; not Picnic-specific. |
| 1 | `NestedOptionals` | Useful and handled by [GitHub issue #136](https://github.com/martin-francois/symphony-trello/issues/136) follow-up work. |
| 1 | `LexicographicalPermitsListing` | Style-only; low priority. |
| 1 | `LexicographicalAnnotationAttributeListing` | Disabled because picocli subcommand order is user-facing. |
| 1 | `IdentityConversion` | Useful and low-risk; good cleanup candidate. |
| 1 | `FutureReturnValueIgnored` | Existing base Error Prone warning; not Picnic-specific. |
| 1 | `DirectReturn` | Useful and low-risk; good cleanup candidate. |
| 1 | `AddressSelection` | Existing base Error Prone warning; not Picnic-specific. |

The first `picnic-refaster` run completed successfully and reported 198 rewrite suggestions. The
largest groups were immutable collection factory rewrites, precondition helper rewrites,
Optional-related rewrites, file-reading rewrites, and small stream/string-builder rewrites. The
Refaster profile was later narrowed by
[ADR 0044](0044-curated-picnic-refaster-profile.md) after those families were triaged.

These findings are real cleanup input, not noise only because there are many of them. They remain
outside `verify` until focused follow-up issues fix, tune, suppress, or reject each useful rule
family.
