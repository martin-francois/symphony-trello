---
status: accepted
date: 2026-06-01
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #139](https://github.com/martin-francois/symphony-trello/issues/139)"
  - "[GitHub issue #136](https://github.com/martin-francois/symphony-trello/issues/136)"
  - "[ADR 0041](0041-report-only-picnic-error-prone-profile.md)"
  - "src/main/java/ch/fmartin/symphony/trello/time/ApplicationClock.java"
informed: [Future maintainers, Contributors]
---

# Use Application Clock Boundaries

## Context and Problem Statement

[GitHub issue #139](https://github.com/martin-francois/symphony-trello/issues/139) cleans up the
remaining Picnic `TimeZoneUsage` findings from [GitHub issue #136](https://github.com/martin-francois/symphony-trello/issues/136).

The findings were in runtime state, retry timing, Codex event timestamps, HTTP refresh responses,
and setup diagnostics. These places all need the current time, but direct calls such as
`Instant.now()` make time-dependent behavior harder to test.

## Decision Drivers

* Keep production behavior unchanged.
* Use UTC as the application clock.
* Make time-dependent code deterministic in tests where useful.
* Avoid passing `Clock` through unrelated domain objects.
* Avoid broad suppressions for `TimeZoneUsage`.
* Keep `./mvnw -q spotless:check verify` behavior unchanged.

## Considered Options

* Suppress all `TimeZoneUsage` findings.
* Inject a `Clock` at application component boundaries.
* Keep static `Instant.now()` calls and add tests with timing tolerances.
* Add a project-specific time service instead of using `java.time.Clock`.

## Decision Outcome

Chosen option: "Inject a `Clock` at application component boundaries", because the code already uses
Java time types and does not need a custom time abstraction.

Production code gets a UTC `Clock` from `ApplicationClock`. Tests can pass a fixed clock to affected
components. Domain state such as `RunningEntry` receives concrete `Instant` values from the
orchestrator instead of creating time internally.

The only suppression is the single `Clock.systemUTC()` factory in `ApplicationClock`, because that
is the intended production clock source.

### Consequences

* Good, because current-time behavior is explicit and testable.
* Good, because the Picnic `TimeZoneUsage` baseline can be cleaned without changing runtime output.
* Good, because the suppression is local and has one reason.
* Bad, because constructors for time-producing components now carry one more dependency.

### Confirmation

Run:

```bash
./mvnw clean compile
./mvnw -q spotless:check verify
```

Normal compile should pass with `TimeZoneUsage` enforced by the default Error Prone/Picnic compiler
configuration.

## Pros and Cons of the Options

### Suppress All `TimeZoneUsage` Findings

Add suppressions at each warning site.

* Good, because it is the smallest source diff.
* Bad, because most findings are useful and would remain less testable.
* Bad, because repeated suppressions would hide future direct-time calls.

### Inject a `Clock` at Application Component Boundaries

Add a UTC application clock and pass it to components that produce timestamps.

* Good, because production still uses the same effective current time.
* Good, because tests can use fixed times without sleeps or timing tolerances.
* Bad, because component constructors become slightly longer.

### Keep Static `Instant.now()` Calls and Add Timing-Tolerance Tests

Leave production code as-is and only test ranges around the current time.

* Good, because production code stays short.
* Bad, because tests remain less deterministic.
* Bad, because the Picnic findings remain unresolved.

### Add a Project-Specific Time Service

Wrap `Clock` in a custom service such as `ApplicationTime`.

* Good, because the project could add helper methods later.
* Bad, because the current need is only `Instant` timestamps.
* Bad, because a custom abstraction would be more code than `Clock` for this problem.

## More Information

The accepted implementation adds `ApplicationClock` as the production UTC clock source. Quarkus
beans receive a `Clock` through constructor injection. Non-CDI setup helpers use the same
`ApplicationClock.systemUtc()` factory when they need a default clock.

The `Clock.systemUTC()` call is intentionally isolated in `ApplicationClock` with a narrow
`TimeZoneUsage` suppression. That keeps direct current-time calls out of the rest of production
code while preserving the existing UTC behavior.
