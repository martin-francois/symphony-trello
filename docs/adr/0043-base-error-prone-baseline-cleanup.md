---
status: accepted
date: 2026-06-01
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #140](https://github.com/martin-francois/symphony-trello/issues/140)"
  - "[GitHub issue #130](https://github.com/martin-francois/symphony-trello/issues/130)"
  - "[ADR 0034](0034-optional-error-prone-profile.md)"
  - "pom.xml"
informed: [Future maintainers, Contributors]
---

# Clean the Base Error Prone Baseline

## Context and Problem Statement

[GitHub issue #140](https://github.com/martin-francois/symphony-trello/issues/140) cleans up the
remaining base Error Prone warnings that still appeared in the optional Error Prone profiles.

The warnings covered unused fields, unused nested setup types, a record constructor self-assignment,
a local loopback connection helper, an ignored executor return value, and `System.console()` null
checks.

## Decision Drivers

* Fix useful findings instead of hiding them.
* Keep behavior, CLI output, and setup prompts unchanged.
* Keep suppressions narrow and local.
* Keep `./mvnw -q spotless:check verify` behavior unchanged in this cleanup PR.
* Leave broader promotion of optional profiles to the static-analysis rollout plan.

## Considered Options

* Fix all findings with code changes.
* Suppress all remaining base Error Prone warnings.
* Fix useful findings and isolate the one console-boundary suppression.
* Promote the `error-prone` profile into the default `verify` command now.

## Decision Outcome

Chosen option: "Fix useful findings and isolate the one console-boundary suppression", because most
warnings pointed to straightforward cleanup and one warning protects intentional runtime behavior.

The unused fields and nested types are removed. The record constructor now validates the default
value without assigning the parameter to itself. The local port probe uses the same explicit IPv4
loopback address as the health URLs and Quarkus bind address.
Worker dispatch stores the submitted `Future` on the running entry so stop and termination paths can
cancel it instead of ignoring the return value.

`System.console()` stays behind a small `SystemConsole.current()` helper with a narrow
`SystemConsoleNull` suppression. The project still needs to handle runtimes where `System.console()`
returns `null`, and both console-sensitive call sites use the same boundary.

### Consequences

* Good, because the base Error Prone warning baseline becomes clean.
* Good, because the console suppression has one place and one reason.
* Good, because normal validation behavior does not change before the remaining rollout work is
  done.
* Bad, because the optional Error Prone profile is still not part of normal `verify`.

### Confirmation

Run:

```bash
./mvnw -Perror-prone clean compile
./mvnw -Ppicnic-error-prone clean compile
./mvnw -q spotless:check verify
```

The two Error Prone profile commands should complete without warnings.

## Pros and Cons of the Options

### Fix All Findings With Code Changes

Change every warning site without suppressions.

* Good, because no warnings remain.
* Bad, because removing the `System.console()` null check would change behavior on runtimes where
  the console is unavailable.

### Suppress All Remaining Base Error Prone Warnings

Add suppressions at each warning site.

* Good, because it is the smallest source diff.
* Bad, because most findings are real cleanup.
* Bad, because repeated suppressions would hide future issues.

### Fix Useful Findings and Isolate the One Console-Boundary Suppression

Fix the normal source findings and centralize the console null check.

* Good, because useful findings are fixed.
* Good, because the one suppression is explicit and narrow.
* Bad, because future console call sites must use the helper to keep the policy consistent.

### Promote the `error-prone` Profile Into Default `verify` Now

Make normal Maven verification run the base Error Prone profile.

* Good, because new base Error Prone warnings would be blocked immediately.
* Bad, because the static-analysis rollout still has open Picnic Refaster work in
  [GitHub issue #137](https://github.com/martin-francois/symphony-trello/issues/137).
* Bad, because changing the default compiler path should be a separate final rollout decision.

## More Information

This decision does not reject making Error Prone blocking. It only keeps that promotion out of the
#140 cleanup PR. The final static-analysis rollout remains tracked by
[GitHub issue #130](https://github.com/martin-francois/symphony-trello/issues/130).
