---
status: accepted
date: 2026-07-13
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #535](https://github.com/martin-francois/symphony-trello/issues/535)"
  - "[GitHub pull request #558](https://github.com/martin-francois/symphony-trello/pull/558)"
  - "[GitHub issue #564](https://github.com/martin-francois/symphony-trello/issues/564)"
  - "[GitHub issue #571](https://github.com/martin-francois/symphony-trello/issues/571)"
  - "[SPEC.md](../../SPEC.md)"
informed: [Future maintainers, Contributors, Operators]
---

# Status Reports Workflow Runtime Health Separately From Autostart Configuration

## Context and Problem Statement

[Issue #535](https://github.com/martin-francois/symphony-trello/issues/535) showed that combining a
platform autostart row with workflow runtime rows can mislead operators. A workflow may be running
and responding while the adjacent service-manager query is unavailable, producing two apparently
contradictory answers to different questions.

[Pull request #558](https://github.com/martin-francois/symphony-trello/pull/558) explored making the
Linux autostart answer exact. The prototype grew into detailed sd-bus compatibility work, including
address transports, ordered fallbacks, percent decoding, Unix-socket limits, C-string behavior,
numeric parsing, and platform ABI behavior. That complexity belongs to the platform and does not
provide proportional value in every normal `status` call.

Symphony already has a portable runtime identity signal: the loopback `/api/v1/local-status`
endpoint, combined with managed PID ownership and the expected workflow and board identity. The
question normal `status` should answer is whether each selected workflow is responding as the
expected local Symphony worker now. Whether it will start after login or reboot is a separate setup
and deployment question.

## Decision Drivers

* Report the runtime state that operators most often need.
* Keep workflow identity and managed-process ownership checks authoritative.
* Give every selected workflow an independent result.
* Keep normal status read-only and portable across Linux, macOS, WSL2, and native Windows.
* Preserve installer-managed autostart without coupling runtime status to service managers.
* Avoid custom D-Bus parsing, native bindings, and a second health subsystem.
* Avoid overstating downstream Trello, GitHub, Codex, or network health.

## Considered Options

* Report workflow runtime state through the existing local endpoint and managed PID identity.
* Expand the custom systemd and sd-bus parser from pull request #558.
* Replace the custom resolver with dbus-java or native libsystemd integration.
* Hide platform differences behind another service-manager status abstraction.
* Use external Trello or GitHub availability as the local liveness signal.

## Decision Outcome

Chosen option: "Report workflow runtime state through the existing local endpoint and managed PID
identity".

Normal `status` resolves workflows through the existing selector behavior and evaluates each one
independently. It uses `/api/v1/local-status` to verify the expected workflow and board identity and
combines that response with the existing managed PID ownership model. Existing runtime distinctions,
including running, stopped, stale, untracked, invalid, wrong-workflow, and foreign-port outcomes,
remain available where the underlying evidence supports them.

Each workflow owns its complete evidence boundary. An unexpected runtime failure while validating
its workflow, reading its PID, probing its endpoint, checking process ownership, or classifying its
result produces one sanitized invalid row; it does not suppress later workflow rows or expose the
exception details.

Normal `status` makes no systemd, launchd, or Windows Task Scheduler call and prints no platform
autostart row. It does not claim whether a workflow will start after login or reboot. Installer and
lifecycle autostart behavior remains supported and owned by installation, setup, start, stop,
restart, uninstall, service registration, installer verification, logs, and native platform tools.
No replacement autostart diagnostic is introduced by this decision.

A responding local identity endpoint proves only that the expected Symphony worker is answering at
that moment. It does not prove that every downstream service or future operation is healthy.

### Consequences

* Good, because runtime output is portable and directly answers whether each workflow is responding.
* Good, because one invalid or unavailable workflow does not suppress sibling workflow rows.
* Good, because normal status has no service-manager availability or D-Bus dependency.
* Good, because installer-managed autostart behavior remains unchanged.
* Bad, because normal status no longer tells users whether a worker is configured to start after a
  login or reboot.
* Neutral, because autostart troubleshooting remains possible through setup checks, installer
  output, logs, and native platform tools.
* Neutral, because removing the misleading autostart row corrects broken human-readable output;
  supported workflow runtime reporting and installer-managed autostart remain available.

### Confirmation

This decision remains implemented when:

* the normal status caller graph has no `CommandRunner` or platform service-manager dependency;
* Linux, macOS, and Windows status tests produce workflow rows without autostart output;
* public tests cover matching and nonmatching `/api/v1/local-status` identity responses, managed and
  untracked workers, stale PID ownership, invalid workflows, isolated evidence failures, and mixed
  multi-workflow output;
* no custom D-Bus parser or direct D-Bus dependency exists in the implementation;
* installer and lifecycle autostart tests remain green; and
* README, CLI help, and `SPEC.md` describe the same runtime-only status contract.

## Pros and Cons of the Options

### Existing Local Runtime Identity

* Good, because the endpoint and PID ownership model already exist and are used by lifecycle flows.
* Good, because workflow and board identity prevent a foreign process from looking healthy.
* Bad, because it cannot predict post-login or post-reboot startup.

### Expand Custom Systemd And sd-bus Parsing

* Good, because Linux autostart details could remain in normal status.
* Bad, because Symphony would own a large compatibility surface for a secondary operator question.
* Bad, because the approach is Linux-specific and was abandoned unmerged in pull request #558.

### Use dbus-java Or Native libsystemd

* Good, because a maintained integration could avoid some hand-written wire compatibility.
* Bad, because it adds a dependency and platform boundary that runtime status no longer needs.
* Bad, because [issue #564](https://github.com/martin-francois/symphony-trello/issues/564) existed only
  to investigate replacing the rejected custom resolver.

### Add Another Service-Manager Abstraction

* Good, because platform queries could share a Java interface.
* Bad, because it preserves the mixed runtime-versus-autostart contract instead of correcting it.
* Bad, because it adds architecture without improving the selected runtime signal.

### Use External Service Availability

* Good, because Trello or GitHub failures might explain some worker problems.
* Bad, because remote availability is not equivalent to local worker identity or liveness.
* Bad, because normal status would gain network side effects and credentials it does not need.

## More Information

[Issue #571](https://github.com/martin-francois/symphony-trello/issues/571) owns the replacement
implementation. Issues #535 and #564 are closed as not planned, and pull request #558 is closed
without merging. Its complete prototype remains available on the archive branch recorded in issue
#571 for historical reference only.
