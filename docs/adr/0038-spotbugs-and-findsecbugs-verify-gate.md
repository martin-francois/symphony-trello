---
status: accepted
date: 2026-05-30
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #77](https://github.com/martin-francois/symphony-trello/issues/77)"
  - "[GitHub issue #80](https://github.com/martin-francois/symphony-trello/issues/80)"
  - "pom.xml"
  - "CONTRIBUTING.md"
  - "AGENTS.md"
informed: [Future maintainers, Contributors]
---

# Add SpotBugs and FindSecBugs to Local Verify

## Context and Problem Statement

The project already uses Spotless, PMD, ArchUnit, deterministic tests, and JaCoCo in the default
local validation command. PMD covers source-level rules, but it does not inspect compiled bytecode
or FindSecBugs security detectors.

How should the project add a bytecode and security analysis layer without making contributors use a
hosted dashboard or a separate command?

## Decision Drivers

* Keep the default local validation command as `./mvnw -q spotless:check verify`.
* Give contributors and agents the same blocking analyzer feedback locally and in CI.
* Add security-focused checks that complement PMD and ArchUnit.
* Keep the analyzer configuration small and easy to audit.
* Fix real findings where practical and suppress only true false positives with narrow reasons.
* Keep analyzer versions in Maven properties so Renovate can update them.

## Considered Options

* Add SpotBugs and FindSecBugs to Maven `verify`.
* Add SpotBugs only and defer FindSecBugs.
* Add SpotBugs and FindSecBugs in an optional profile.
* Use a hosted scanner only.
* Defer bytecode and security analysis.

## Decision Outcome

Chosen option: "Add SpotBugs and FindSecBugs to Maven `verify`", because it gives maintainers,
contributors, agents, and CI the same deterministic feedback through the existing validation
command.

### Consequences

* Good, because bytecode and security findings now fail the same local command as tests and PMD.
* Good, because `config/spotbugs/exclude.xml` gives one reviewed place for project-level false
  positives.
* Good, because most baseline findings were fixed instead of suppressed.
* Good, because remaining suppressions are limited to reviewed product boundaries: dependency
  injection, intentional command execution, stable LF text generation, loopback HTTP probes, Unix
  sentinel files, and non-security retry jitter.
* Good, because the diagnostics redaction REDOS findings were fixed with linear scanner logic
  instead of suppressed. Guava `CharMatcher` is used only for scanner character classes so the
  redaction rules stay readable without returning to vulnerable regular expressions.
* Good, because the Maven version properties are Renovate-friendly.
* Bad, because Guava becomes a runtime dependency.
* Bad, because build time increases.
* Bad, because future Java or Quarkus upgrades may require SpotBugs compatibility checks.

### Confirmation

Run `./mvnw -q spotless:check verify`. Review any future SpotBugs or FindSecBugs finding before
adding a suppression. Prefer fixing the code. If the finding is a false positive, use the narrowest
entry in `config/spotbugs/exclude.xml` or a code-local `@SuppressFBWarnings` with a justification.

## Pros and Cons of the Options

### Add SpotBugs and FindSecBugs to Maven `verify`

Bind `spotbugs-maven-plugin` to `verify` and load `findsecbugs-plugin` through the plugin
configuration.

* Good, because all contributors can run the same command locally.
* Good, because CI and local behavior match.
* Good, because no hosted service is required.
* Neutral, because suppressions still need review discipline.
* Bad, because every `verify` run pays the analyzer cost.

### Add SpotBugs Only and Defer FindSecBugs

Add bytecode checks now, but postpone security detectors.

* Good, because it would be a smaller first step.
* Bad, because [GitHub issue #77](https://github.com/martin-francois/symphony-trello/issues/77)
  explicitly calls for FindSecBugs.
* Bad, because it leaves security detector feedback outside the default loop.

### Add SpotBugs and FindSecBugs in an Optional Profile

Create a profile such as `-Pspotbugs` and document it for maintainers.

* Good, because normal `verify` would stay faster.
* Bad, because optional checks are easier for agents and contributors to miss.
* Bad, because [GitHub issue #77](https://github.com/martin-francois/symphony-trello/issues/77)
  asks for the default local verification command to include the checks.

### Use a Hosted Scanner Only

Rely on a hosted code-scanning dashboard or GitHub security feature.

* Good, because hosted tools can aggregate findings and history.
* Bad, because contributors and agents would not get the same local fix-and-rerun loop.
* Bad, because private-repository hosted scanning may need extra setup or public availability.

### Defer Bytecode and Security Analysis

Keep PMD, tests, ArchUnit, and JaCoCo as the only blocking checks.

* Good, because no build cost is added.
* Bad, because bytecode and FindSecBugs security findings stay invisible until a later phase.

## More Information

[GitHub issue #77](https://github.com/martin-francois/symphony-trello/issues/77) tracks this static
analysis phase. [GitHub issue #80](https://github.com/martin-francois/symphony-trello/issues/80)
covered the PMD prerequisite.
