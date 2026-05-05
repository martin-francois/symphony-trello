---
status: accepted
date: 2026-05-06
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, pom.xml, Quarkus 3.31 migration guide, JUnit 6 user guide, ArchUnit issue tracker]
informed: [Future maintainers]
---

# Use Quarkus-Managed JUnit 6 Test Stack

## Context and Problem Statement

Quarkus 3.31 upgraded its test stack to JUnit 6 and renamed its `-junit5` artifacts to `-junit`.
This project already uses Quarkus 3.35, so the Quarkus BOM resolves JUnit Platform and JUnit Jupiter
6.0.3 for tests. The POM still declared the relocated `quarkus-junit5` artifact, which worked only
because Quarkus provides a relocation to `quarkus-junit`.

How should the project make the JUnit 6 migration explicit without weakening Quarkus test behavior,
Mockito agent configuration, ArchUnit checks, JaCoCo coverage, or Maven Surefire/Failsafe support?

## Decision Drivers

* Follow the Quarkus-supported test stack instead of pinning unrelated JUnit artifacts by hand.
* Remove relocation warnings so dependency output reflects the intended build.
* Keep Maven Surefire and Failsafe compatible with JUnit 6 on Java 25.
* Keep the Mockito Java agent configured through Maven dependency properties and a direct
  same-version `mockito-core` dependency.
* Keep ArchUnit architecture checks running in `verify`.
* Avoid adding JUnit Vintage or JUnit 4 support because the project has no JUnit 4 tests.
* Avoid test rewrites when the Jupiter API imports remain correct for JUnit 6.

## Considered Options

* Replace `quarkus-junit5` with `quarkus-junit` and keep JUnit versions managed by the Quarkus BOM.
* Add an explicit JUnit BOM and direct `junit-jupiter` dependencies.
* Keep using the relocated `quarkus-junit5` artifact.
* Replace ArchUnit's JUnit Platform integration with plain JUnit tests.

## Decision Outcome

Chosen option: "Replace `quarkus-junit5` with `quarkus-junit` and keep JUnit versions managed by
the Quarkus BOM", because this is the path Quarkus documents for JUnit 6 while preserving the
project's existing test conventions and build gates.

The project keeps `archunit-junit5` for now. ArchUnit's current released JUnit Platform integration
still uses the `junit5` artifact name, and the upstream ArchUnit tracker has no completed JUnit
6-specific replacement module yet. The artifact runs on the JUnit Platform supplied by Quarkus in
this project, so replacing it would add custom code without improving compatibility.

### Consequences

* Good, because the build no longer relies on the Quarkus relocation from `quarkus-junit5` to
  `quarkus-junit`.
* Good, because the JUnit 6 version stays aligned with the Quarkus platform.
* Good, because Surefire/Failsafe continue to use versions above JUnit 6's minimum supported Maven
  plugin version.
* Good, because existing Jupiter tests and parameterized tests do not need source changes.
* Good, because Mockito's JUnit Jupiter extension and Java agent artifact now resolve to the same
  Mockito version.
* Good, because ArchUnit rules, Mockito mocks, PMD, Spotless, and JaCoCo remain in the normal
  `verify` path.
* Bad, because ArchUnit's artifact name still contains `junit5`, which can look stale even though it
  is the current released ArchUnit integration.

### Confirmation

Run `./mvnw dependency:tree` and confirm Quarkus resolves `io.quarkus:quarkus-junit` and JUnit
Platform/Jupiter 6 artifacts without a `quarkus-junit5` relocation warning.

Run `./mvnw -q spotless:check verify` and confirm unit tests, integration tests, PMD, ArchUnit, and
JaCoCo pass.

## Pros and Cons of the Options

### Replace `quarkus-junit5` with `quarkus-junit` and keep JUnit versions managed by the Quarkus BOM

Use the renamed Quarkus test extension and let the Quarkus platform manage the JUnit 6 artifacts.

* Good, because it follows the Quarkus 3.31 migration guidance.
* Good, because it avoids a second dependency management source for JUnit.
* Good, because it removes relocation warnings.
* Good, because it keeps Renovate updates focused on the Quarkus platform.
* Neutral, because JUnit Jupiter source imports still use `org.junit.jupiter`.

### Add an explicit JUnit BOM and direct `junit-jupiter` dependencies

Import `org.junit:junit-bom` and declare JUnit dependencies directly.

* Good, because it would make the JUnit version visible in this POM.
* Bad, because Quarkus already manages JUnit versions for its test framework.
* Bad, because a separately pinned JUnit BOM can drift from the Quarkus-tested combination.

### Keep using the relocated `quarkus-junit5` artifact

Leave the POM unchanged and rely on Quarkus relocation metadata.

* Good, because the build already resolves to `quarkus-junit`.
* Bad, because dependency output warns that the project build file should be updated.
* Bad, because the declared artifact suggests the project has not completed the JUnit 6 migration.

### Replace ArchUnit's JUnit Platform integration with plain JUnit tests

Remove `archunit-junit5` and execute ArchUnit rules from ordinary Jupiter test methods.

* Good, because no dependency name would mention JUnit 5.
* Bad, because it would replace a maintained integration with local boilerplate.
* Bad, because the upstream ArchUnit artifact still provides useful discovery and failure behavior.
* Bad, because it does not solve a real compatibility problem in the current build.

## More Information

Relevant upstream references:

* [Quarkus 3.31 release notes](https://quarkus.io/blog/quarkus-3-31-released/)
* [Quarkus 3.31 migration guide](https://github.com/quarkusio/quarkus/wiki/Migration-Guide-3.31)
* [JUnit 6 Maven build support](https://docs.junit.org/current/running-tests/build-support.html)
* [ArchUnit JUnit 6 tracking issue](https://github.com/TNG/ArchUnit/issues/1556)
