---
status: accepted
date: 2026-07-06
decision-makers: [François Martin, Codex]
consulted:
  - README.md
  - SPEC.md
  - docs/deployment.md
  - deploy/systemd/symphony-trello@.service
  - "[ADR 0013](0013-systemd-template-deployment.md)"
  - "[ADR 0016](0016-explicit-deployed-host-paths.md)"
  - "[ADR 0036](0036-remove-ansible-deployment-before-oss-release.md)"
  - "[OpenClaw README](https://github.com/openclaw/openclaw)"
  - "[OpenClaw install docs](https://docs.openclaw.ai/install)"
  - "[OpenClaw setup docs](https://docs.openclaw.ai/start/setup)"
informed: [Future maintainers, Contributors]
---

# Remove Manual systemd Deployment Path

## Context and Problem Statement

Symphony for Trello already has an installer-managed local worker and lifecycle commands. The README
also had a separate "Production Deployment" section that pointed users to a manual systemd guide.
That wording suggested the installer-managed path was less suitable for real use, even though the
installer path is the supported public path for normal operation.

The manual systemd guide and unit file also created a second setup surface to keep current. They
duplicated host-path, secret-file, lifecycle, and service-management guidance that now mostly belongs
to the installer, generated workflow, and local lifecycle commands.

OpenClaw was checked as a comparable agent application. Its README and install docs put the
installer/onboarding flow first. OpenClaw documents daemon startup through onboarding and mentions
Linux systemd user services as part of that managed path; it does not make a hand-written systemd
template the main README production path.

Should Symphony for Trello keep a manual systemd deployment path as public documentation and a
checked-in unit file?

## Decision Drivers

* Keep the public setup surface small.
* Avoid implying that installer-managed runs are not production-suitable.
* Avoid maintaining a second service-manager path without current user demand.
* Preserve OpenClaw-style logout and reboot reliability as an installer-managed lifecycle goal, not
  as a manually maintained runbook.
* Keep host-path access documented through setup and workflow settings.
* Keep direct Java CLI execution possible for advanced users without promising a full manual
  deployment runbook.

## Considered Options

* Remove the manual systemd deployment path.
* Rename the README section but keep the manual systemd guide.
* Keep manual systemd as the advanced production path.
* Add a new automated server deployment path.

## Decision Outcome

Chosen option: "Remove the manual systemd deployment path", because the installer-managed local
worker is the public operation path and the manual systemd guide is a separate support surface that
is easy to let drift.

This is not a decision to give up service-manager supervision. OpenClaw-style managed autostart
across logout and reboot remains part of the desired installer/onboarding model and is tracked by
[issue #523](https://github.com/martin-francois/symphony-trello/issues/523).

The repository removes `docs/deployment.md` and `deploy/systemd/symphony-trello@.service`. README no
longer has a separate "Production Deployment" section. `SPEC.md` no longer defines manual systemd as
a Java implementation extension. Generated workflow filesystem blocker guidance now points to setup
and workflow settings such as `--add-path`, `codex.additional_writable_roots`, and
`SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS`.

### Consequences

* Good, because users see one primary setup and operation path.
* Good, because docs no longer imply the installer-managed worker is only for non-production use.
* Good, because future docs and tests do not need to keep a manual systemd unit current.
* Neutral, because advanced operators can still run the Java application under their own service
  manager if they accept ownership of that service configuration.
* Bad, because the repository no longer provides copy-paste systemd commands for multi-workflow
  server hosts.
* Bad, because installer-managed logout and reboot autostart remains a product gap until
  [issue #523](https://github.com/martin-francois/symphony-trello/issues/523) is implemented.

### Confirmation

This decision is still implemented when:

* `docs/deployment.md` is absent.
* `deploy/systemd/symphony-trello@.service` is absent.
* README has no manual systemd or "Production Deployment" path.
* `SPEC.md` does not list manual systemd as a shipped Java extension profile.
* generated workflow blocker guidance names setup and workflow host-path settings instead of
  deleted systemd guide links.

## Pros and Cons of the Options

### Remove The Manual systemd Deployment Path

Delete the manual guide and checked-in unit file. Keep the installer and managed local worker as the
public setup and operation path.

* Good, because the supported path is clearer.
* Good, because there is no second deployment runbook to maintain.
* Good, because host-path access guidance can stay tied to setup and workflow config.
* Bad, because advanced Linux operators need to write their own service-manager config.

### Rename The README Section But Keep The Manual systemd Guide

Change "Production Deployment" to a less misleading heading such as "Manual systemd Deployment", but
keep the guide and unit file.

* Good, because it would fix the immediate wording problem.
* Bad, because the repository would still maintain the second deployment path.
* Bad, because readers could still infer that manual systemd is the more serious path.

### Keep Manual systemd As The Advanced Production Path

Keep the current guide and unit file, and keep documenting it as the way to run server deployments.

* Good, because existing operators who copied the guide would still find it familiar.
* Bad, because it preserves the misleading production split.
* Bad, because every runtime, host-path, secret, and diagnostics change must consider both setup
  paths.

### Add A New Automated Server Deployment Path

Replace the manual guide with a new supported automation layer such as a Java installer command,
container deployment, Nix module, or configuration-management recipe.

* Good, because a future server path could be easier to run and test.
* Bad, because no current issue defines that product need.
* Bad, because it would add a larger subsystem instead of reducing support surface.

## More Information

This decision supersedes [ADR 0013](0013-systemd-template-deployment.md). It also updates the
deployment assumption preserved by
[ADR 0036](0036-remove-ansible-deployment-before-oss-release.md): Ansible remains removed, and manual
systemd is no longer kept as the advanced public path.
