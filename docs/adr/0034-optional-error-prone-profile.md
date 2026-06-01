---
status: superseded by [ADR 0045](0045-enforce-error-prone-and-picnic-in-verify.md)
date: 2026-05-29
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #80](https://github.com/martin-francois/symphony-trello/issues/80)"
  - pom.xml
  - CONTRIBUTING.md
  - AGENTS.md
informed: [Future maintainers]
---

# Add Error Prone as an Optional Compiler Profile

This ADR records the historical decision to introduce Error Prone as an optional profile. It is
superseded by [ADR 0045](0045-enforce-error-prone-and-picnic-in-verify.md), which promotes the clean
production-source baseline into normal verification.

## Context and Problem Statement

The project already uses Spotless, JaCoCo, ArchUnit, PMD, and focused tests in the default local
validation command:

```bash
./mvnw -q spotless:check verify
```

[GitHub issue #80](https://github.com/martin-francois/symphony-trello/issues/80) asks for an Error
Prone evaluation path, especially for Optional misuse such as checking presence and then reading the
value unsafely. The project should get local Error Prone signal without making normal verification
slower or noisier before the baseline is understood.

## Decision Drivers

* Keep `./mvnw -q spotless:check verify` unchanged as the default local validation command.
* Let contributors and agents run Error Prone locally with one Maven command.
* Start with stable, explicit Error Prone rule flags so the profile is easy to audit.
* Surface Optional misuse with `OptionalNotPresent`.
* Avoid adding Picnic Error Prone Support or any other analyzer in this change.
* Avoid an automatic rewrite profile until generated fixes are proven safe and reviewable.

## Considered Options

* Add a warning-oriented optional `error-prone` Maven profile.
* Add Error Prone to the default Maven compiler configuration.
* Add an automatic Error Prone fix profile now.
* Defer Error Prone and rely only on PMD plus agent instructions.

## Decision Outcome

Chosen option: "Add a warning-oriented optional `error-prone` Maven profile", because it gives
maintainers local compiler-level feedback while keeping the default validation command stable.

The profile is run with:

```bash
./mvnw -Perror-prone clean compile
```

The profile uses Error Prone `2.49.0`, forks the compiler for Java 25 module access, demotes current
Error Prone errors to warnings while the baseline is evaluated, and explicitly enables
`OptionalNotPresent` as a warning. The command includes `clean` so Maven recompiles sources and does
not skip Error Prone when existing class files are up to date. It is intentionally not quiet because
Maven `-q` hides the warning output that this candidate profile is meant to show.

### Consequences

* Good, because the Error Prone integration is available without changing normal `verify`.
* Good, because Optional presence/value-read problems are surfaced by a standard analyzer.
* Good, because the command runs locally and does not depend on hosted dashboards.
* Neutral, because maintainers can promote specific checks to errors later after the baseline is
  clean and useful.
* Bad, because contributors must opt in to the profile when they want this extra feedback.
* Bad, because Java compiler internals require module export flags for the Error Prone plugin.

### Confirmation

Run:

```bash
./mvnw -Perror-prone clean compile
./mvnw -q spotless:check verify
```

Review the profile configuration when updating Java, Maven Compiler Plugin, or Error Prone versions.
Keep `OptionalNotPresent` configured explicitly so Optional misuse remains visible.

## Pros and Cons of the Options

### Add a warning-oriented optional `error-prone` Maven profile

Configure Error Prone under a Maven profile and keep it outside the default validation command.

* Good, because contributors can run one Maven command for extra source-level feedback.
* Good, because the profile can be tuned without breaking the normal build.
* Good, because this matches the repository policy for new static-analysis rules.
* Bad, because optional checks may be skipped unless a contributor or agent runs the profile.

### Add Error Prone to the default Maven compiler configuration

Make every normal compile and verify run through Error Prone.

* Good, because findings would be impossible to miss.
* Bad, because this changes the default verification behavior before baseline findings and Java 25
  compatibility are understood.
* Bad, because [GitHub issue #76](https://github.com/martin-francois/symphony-trello/issues/76)
  requires the default local verification command to remain stable.

### Add an automatic Error Prone fix profile now

Add a second profile that runs Error Prone patching or refactoring commands.

* Good, because mechanical fixes could be generated faster.
* Bad, because generated rewrite output has not been evaluated in this repository.
* Bad, because normal contributors could accidentally modify source files while trying to validate.

### Defer Error Prone and rely only on PMD plus agent instructions

Keep the existing tooling unchanged.

* Good, because there is no new compiler configuration to maintain.
* Bad, because Optional misuse would remain dependent on review discipline and custom agent rules.
* Bad, because the project would not get feedback from Error Prone's built-in checks.

## More Information

The Maven profile follows the official Error Prone Maven installation guidance for annotation
processor paths and JDK module export flags.
