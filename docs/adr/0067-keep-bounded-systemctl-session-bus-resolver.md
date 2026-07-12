---
status: accepted
date: 2026-07-11
decision-makers: [François Martin, Codex]
consulted:
  - README.md
  - SPEC.md
  - src/main/java/ch/fmartin/symphony/trello/setup/LocalWorkerManager.java
  - src/main/java/ch/fmartin/symphony/trello/setup/SystemdSessionBusResolver.java
  - "[GitHub PR #558](https://github.com/martin-francois/symphony-trello/pull/558)"
  - "[GitHub issue #564](https://github.com/martin-francois/symphony-trello/issues/564)"
  - "[dbus-java repository](https://github.com/hypfvieh/dbus-java)"
  - "[dbus-java 5.2.0 release](https://github.com/hypfvieh/dbus-java/releases/tag/dbus-java-parent-5.2.0)"
  - "[JNA repository](https://github.com/java-native-access/jna)"
informed: [Future maintainers, Contributors]
---

# Keep The Bounded Systemctl Session-Bus Resolver

## Context and Problem Statement

On Linux, `symphony-trello status` needs to distinguish an unavailable user systemd manager from an
inactive or missing service. It makes one bounded `systemctl --user show` query for the service state.
That query may run from a shell that has a valid session-bus address, only an XDG runtime directory,
invalid inherited connection hints, or neither hint.

The contract in `SPEC.md` defines which values are safe to pass to `systemctl`, their precedence, and
the standard `/run/user/<uid>` fallback. PR #558 originally implemented this logic inside
`LocalWorkerManager`. Review moved it into the package-private `SystemdSessionBusResolver`, but the
resolver still contains substantial D-Bus address validation. A maintained library could be easier to
read and maintain if it replaces enough of this boundary.

The dependency research gate requires a release within the previous 12 months, an unarchived and
non-deprecated project, a compatible open-source license, and at least 100 GitHub stars. On
2026-07-11, dbus-java met that gate: the repository was unarchived, had 236 stars, used the MIT
license, and its 5.2.0 release was published on 2025-12-21. JNA also met the activity and popularity
criteria and offers an Apache-2.0 license option, but it is a low-level native-access library rather
than a D-Bus or systemd client.

Should PR #558 keep its extracted resolver and `systemctl` command boundary, adopt dbus-java now, or
use JNA to call libsystemd?

## Decision Drivers

* Report user-systemd availability truthfully without changing worker state.
* Preserve the connection-hint precedence and validation contract in `SPEC.md`.
* Keep one bounded, timed command for service-state lookup.
* Prefer maintained dependencies when they materially reduce code or cognitive complexity.
* Avoid adding native transport and lifecycle work without evidence that it simplifies the system.
* Keep Linux-only behavior isolated from worker lifecycle code.
* Make failures deterministic and easy to cover without a live user systemd manager.
* Avoid delaying the status correctness fix for a larger architecture experiment.

## Considered Options

* Keep the extracted resolver and bounded `systemctl --user show` query.
* Replace the command boundary with dbus-java in PR #558.
* Add JNA bindings to libsystemd in PR #558.

## Decision Outcome

Chosen option: "Keep the extracted resolver and bounded `systemctl --user show` query", because it
preserves the tested contract without expanding a status correctness fix into a new D-Bus transport
and systemd-interface integration.

`LocalWorkerManager` owns worker lifecycle and interprets the three requested systemd properties.
`SystemdSessionBusResolver` owns only the environment selection and validation needed by the command.
`ProcessCommandRunner` owns the bounded process lifecycle. This separation keeps the Linux-specific
grammar out of worker lifecycle code while retaining a small internal boundary between components.

dbus-java is the only credible dedicated dependency found during this review. It provides connection,
proxy, and transport modules, but adopting it is not a drop-in replacement for the current resolver.
The project would still need to choose or derive the correct session address, manage connection and
transport lifecycles, represent the systemd manager interface, enforce timeouts, and prove the same
fallback behavior. Whether it removes enough validation and tests to be simpler is not yet known.

[GitHub issue #564](https://github.com/martin-francois/symphony-trello/issues/564) owns a disposable
dbus-java spike. A future implementation may replace this decision only after the spike compares code
size, cognitive complexity, runtime dependencies, platform support, lifecycle behavior, and failure
modes. If adoption is recommended, it must update or supersede this ADR.

### Consequences

* Good, because PR #558 stays focused on truthful status reporting.
* Good, because the current implementation remains deterministic and fully testable without a live
  D-Bus connection.
* Good, because Linux session-bus mechanics are isolated in one package-private class.
* Good, because a maintained dependency remains preferred if the spike proves it is genuinely
  simpler.
* Bad, because the repository temporarily owns D-Bus address validation code and its regression
  tests.
* Bad, because maintainers must track systemd-compatible address behavior until this decision changes.
* Neutral, because issue #564 may confirm the current approach instead of producing a dependency
  migration.

### Confirmation

This decision remains implemented when:

* `LocalWorkerManager` delegates session-bus environment resolution to `SystemdSessionBusResolver`;
* Linux status uses one bounded `systemctl --user show` query for `LoadState`, `UnitFileState`, and
  `ActiveState`;
* resolver and status tests cover caller-bus precedence, caller-runtime precedence, standard-runtime
  fallback, invalid inherited values, unavailable manager state, timeout, interruption, and cleanup;
* no D-Bus or libsystemd runtime dependency is added without evidence from issue #564; and
* `./mvnw -q spotless:check verify` passes.

## Pros and Cons of the Options

### Keep The Extracted Resolver And Bounded Systemctl Query

Keep using the system-provided `systemctl` client. Resolve only the environment needed for the
subprocess and interpret its three structured output properties.

* Good, because it uses the same systemd client operators already have.
* Good, because command timeout, interruption, output capture, and cleanup already have one tested
  implementation.
* Good, because it adds no transport or native runtime dependency.
* Good, because the extracted resolver keeps this complexity out of worker lifecycle code.
* Bad, because the project owns strict validation for inherited D-Bus addresses.
* Bad, because the validation code is larger than the command that consumes it.

### Replace The Command Boundary With dbus-java In PR #558

Add dbus-java core and a Unix-socket transport, connect to the user bus, represent the systemd manager
interface, and query the unit properties through D-Bus proxies.

* Good, because a maintained library would own D-Bus message framing, proxy calls, and transport
  mechanics.
* Good, because it could remove custom address validation if its discovery and validation behavior
  satisfies the full contract.
* Bad, because its session discovery does not by itself prove the required caller/XDG/UID precedence.
* Bad, because the PR would gain connection lifecycle, transport selection, interface representation,
  timeout, and cleanup work.
* Bad, because no prototype yet shows that the resulting production and test code is smaller or
  clearer.
* Bad, because it would delay a focused correctness fix with an architecture migration.

### Add JNA Bindings To libsystemd In PR #558

Use JNA to maintain local Java bindings for the native libsystemd APIs needed to open the user bus and
read unit properties.

* Good, because libsystemd implements the native systemd semantics directly.
* Good, because JNA is popular, active, and available under Apache-2.0.
* Bad, because JNA does not provide the required libsystemd bindings or higher-level lifecycle.
* Bad, because the repository would own native signatures, library discovery, memory ownership, and
  platform compatibility.
* Bad, because it increases native complexity instead of demonstrating a reduction in code or
  maintenance cost.

## More Information

The initial implementation omitted this ADR even though the review compared multiple meaningful
dependency and architecture choices. That contradicted the repository's ADR discipline. The agent
guidance is strengthened separately on `main` so a review reply that recommends keeping custom code
after dependency research cannot be posted before the ADR and any required spike issue exist.
