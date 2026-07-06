---
status: accepted
date: 2026-06-28
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #468](https://github.com/martin-francois/symphony-trello/issues/468)"
  - "[JSpecify](https://jspecify.dev/)"
  - "[Maven Central org.jspecify:jspecify metadata](https://repo1.maven.org/maven2/org/jspecify/jspecify/maven-metadata.xml)"
informed: [Future maintainers, Contributors]
---

# Add JSpecify Nullness Boundaries

## Context and Problem Statement

The Java codebase used tests, Optional usage, static analysis, and review discipline for null-safety.
That leaves important API and integration boundaries with implicit null contracts. Contributors
should be able to see where `null` is accepted or returned without reverse-engineering constructor
normalization and defensive checks.

How should this repository introduce nullness annotations without creating broad mechanical churn or
a noisy blocking checker?

## Decision Drivers

* Make nullness contracts explicit on reviewed Java boundaries.
* Avoid changing runtime validation behavior.
* Avoid broad annotation churn across packages that have not been audited.
* Keep local and CI verification deterministic.
* Leave room for a future checker once the baseline is understood.

## Considered Options

* Keep nullness contracts implicit.
* Add JSpecify annotations on reviewed boundaries without a blocking checker.
* Mark broad packages as null-marked immediately.
* Add a blocking nullness checker immediately.

## Decision Outcome

Chosen option: add JSpecify annotations on reviewed boundaries without a blocking checker.

The project depends on `org.jspecify:jspecify` so production source can use `@NullMarked` and
`@Nullable`. The dependency is a normal compile dependency because JSpecify annotations are present
in production class files. It adds annotation types, not runtime validation behavior.

Initial annotations cover representative boundaries: the repository source model and prompt context,
workflow repository defaults, parsed workflow data, tracker lookup results, and selected Trello
payload nulls such as blocker fields and optional card URLs.

Use `@NullMarked` at package level only after a whole package has been audited. Until then, use
type-level `@NullMarked` on focused model, parser, configuration, or payload boundaries. Use
`@Nullable` on each intentionally nullable type use. Do not add a blocking nullness checker until a
future issue and ADR define the baseline and acceptable noise level.

### Consequences

* Good, because reviewed nullness contracts are visible in source and class files.
* Good, because the first change stays reviewable and does not mechanically annotate the codebase.
* Good, because normal Maven verification remains the local and CI source of truth.
* Neutral, because the annotation jar is present at compile and runtime due annotation retention.
* Bad, because nullness mistakes are not yet enforced by a dedicated checker.

### Confirmation

Run:

```bash
./mvnw -q spotless:check verify
```

Confirm that representative annotated boundaries compile and the convention tests still find the
JSpecify dependency and annotations.

## Pros and Cons of the Options

### Keep Nullness Contracts Implicit

Do not add nullness annotations yet.

* Good, because there is no dependency or annotation work.
* Bad, because contributors must infer nullness from tests, validation code, and comments.
* Bad, because public API boundaries stay less precise than they can be.

### Add JSpecify on Reviewed Boundaries Without a Checker

Add the annotation dependency and annotate representative audited boundaries first.

* Good, because the change is small and reviewable.
* Good, because it documents real contracts without forcing baseline cleanup.
* Good, because it creates a convention future contributors can follow.
* Bad, because enforcement is compile-only until a checker is added.

### Mark Broad Packages as Null-Marked Immediately

Add package-level `@NullMarked` broadly across production packages.

* Good, because defaults would be consistent within each marked package.
* Bad, because many packages have not been audited for constructor normalization, integration
  payload nulls, framework injection, and nullable external data.
* Bad, because broad marking could imply contracts that are not yet true.

### Add a Blocking Nullness Checker Immediately

Add a checker to local and CI verification in the same change.

* Good, because mistakes would become build failures.
* Bad, because the baseline noise is unknown.
* Bad, because the issue scope explicitly avoids adding a noisy blocking checker before findings are
  understood.

## More Information

Future annotation expansion should prefer the narrowest reviewed boundary first. A later checker can
be introduced when the repository has a clear baseline and the expected false-positive or cleanup
cost is documented.
