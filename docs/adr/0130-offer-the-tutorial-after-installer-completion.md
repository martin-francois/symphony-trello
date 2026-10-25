---
status: accepted
date: 2026-10-25
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #824](https://github.com/martin-francois/symphony-trello/issues/824)"
  - "[ADR 0110](0110-guided-tutorial-on-a-temporary-board.md)"
  - "[SPEC.md](../../SPEC.md)"
informed: [Future maintainers, Contributors, Operators]
---

# Offer the tutorial after installer completion

## Context and Problem Statement

Interactive setup offers a temporary-board tutorial after its handoff. The installers defer that
handoff until managed workers and autostart are ready, so the setup invocation cannot offer the
tutorial itself. Asking before autostart would let tutorial cancellation interrupt installation.
Printing only the tutorial command leaves installer users without the optional question requested
in [GitHub issue #824](https://github.com/martin-francois/symphony-trello/issues/824).

## Decision Drivers

* Installation must finish before optional tutorial work starts.
* Declining the offer must not read credentials, inspect board configuration or contact Trello.
* Bash and PowerShell must use the same prompt, parsing and tutorial cleanup.
* Cancellation must leave installed files, connected boards and autostart intact.
* Existing direct tutorial commands must keep their behavior.

## Considered Options

* Call a Java `tutorial --offer` mode after the completed installer handoff.
* Ask within deferred setup before the installer configures autostart.
* Implement separate tutorial questions in Bash and PowerShell.
* Keep only the printed tutorial command.

## Decision Outcome

Choose `tutorial --offer` after the completed installer handoff. The option asks the existing
default-No question only when input and output are attached to a terminal. Blank input, EOF and
noninteractive execution decline before credential or manifest access. Acceptance uses the same
tutorial command and cleanup as a direct invocation.

The Java offer service owns prompting and optional-failure reporting for direct setup and the
installer entry point. The installers call it after setup, managed-worker handling, autostart and
the final handoff succeed. They clear the completed phase before optional work, so cancellation
does not report failed worker startup. An ordinary tutorial failure preserves installation
success. An interrupt still follows the platform's cancellation behavior; it does not undo the
completed installation. The tutorial shutdown hook retains ownership of its temporary board.

This changes the installer-offer placement decision in ADR 0110. Its remaining tutorial design
and cleanup decisions stay in force. SPEC Section 19.4 permits this optional question after the
final completion block. Dry-run and `--no-onboard` do not offer the tutorial.

### Consequences

* Good, because installer users receive the same optional introduction as direct setup users.
* Good, because credential lookup and tutorial failure handling remain in Java.
* Good, because cancellation happens after installation and autostart have completed.
* Bad, because an interactive installer session can continue after its completion output.
* Bad, because the terminal and process boundaries need lifecycle tests on both shell platforms.

### Confirmation

Command tests verify acceptance, decline, blank input, EOF and redirected input. A declined offer
does not create a board or read a missing configuration directory. Installer lifecycle tests
verify that the offer follows completed worker/autostart handling, that ordinary tutorial failure
preserves success, and that cancellation preserves installation artifacts. Real process tests
verify temporary-board cleanup on interruption. Existing direct tutorial and setup offer tests
continue to pass.

The POSIX installed-process test also observes the already-started managed worker before its
explicit cleanup. Native Windows cancellation uses a private console and an owned job: the test
never attaches to an inherited console or broadcasts a signal to the CI runner. Its synthetic
setup records completed handoff and autostart before a waiting tutorial child advertises its
owned PID. That child runs the test JDK with a shutdown hook installed before it atomically
publishes readiness, then waits without a timed or normal exit path. The hook atomically records
the same fresh token and JVM PID. The controller verifies console membership before sending
Ctrl+C and waits for both installer and child exit. The test requires the hook record as well as
the successful controller result. It then terminates remaining owned test processes. Native assertions
cover installed/config/autostart persistence; the existing Windows fake does not model a
persistent worker, so it does not establish live-worker survival.

This adds test infrastructure because redirected PowerShell input correctly skips the offer and
an ordinary exit code does not prove console cancellation. A private console also lets cleanup
remain bounded without signaling unrelated processes. The controller requires an existing visible
interactive window station and selects its desktop before starting the private console. A console
alone retains the service runner's noninteractive station and fails the real terminal guard.
The fixture changes no desktop permissions, process identity or session, and has no fallback that
bypasses that prerequisite. Controlled failure evidence records stages, terminal flags and native
result codes without exception text or test-owned paths.

A PowerShell `Read-Host` surrogate remained alive with its installer after a delivered console
event. Its readiness marker preceded the blocking input call and did not establish an active
cancellation boundary. The fixture therefore uses the actual JVM lifetime model instead of that
surrogate. This proves JVM shutdown and installer persistence, not native JLine interaction,
Trello archival or live-worker survival. The POSIX tutorial process tests retain archival coverage.

## Pros and Cons of the Options

### Call a Java offer mode after completion

Both installers invoke the installed tutorial command with an additive flag after the handoff.

* Good, because one Java implementation owns the question and cleanup.
* Good, because the option is compatible with existing direct tutorial invocations.
* Bad, because the installers need a separate optional-process boundary.

### Ask during deferred setup

The first setup invocation would ask before returning control to the installer.

* Good, because it reuses the existing setup offer without another command invocation.
* Bad, because interruption prevents the installer from finishing worker and autostart setup.

### Ask separately in each shell

Bash and PowerShell would parse an answer and launch the ordinary tutorial command on Yes.

* Good, because the ordinary Java command needs no new option.
* Bad, because prompt parsing and terminal behavior diverge across three implementations.

### Keep only the command hint

The final handoff would continue to print a command without asking the optional question.

* Good, because installation ends immediately after its handoff.
* Bad, because installer users still miss the requested guided introduction.


## More Information

* [Issue #824](https://github.com/martin-francois/symphony-trello/issues/824) owns the separate
  installer offer after the original tutorial implementation.
* [ADR 0110](0110-guided-tutorial-on-a-temporary-board.md) retains the tutorial board and cleanup
  decisions; this ADR changes only its installer-offer placement.
* [SPEC Sections 17.7 and 19.4](../../SPEC.md) define the validation inventory and setup contract.
