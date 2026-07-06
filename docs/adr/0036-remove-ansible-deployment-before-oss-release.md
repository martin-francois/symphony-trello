---
status: accepted
date: 2026-05-29
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #115](https://github.com/martin-francois/symphony-trello/issues/115)"
  - "[GitHub issue #10](https://github.com/martin-francois/symphony-trello/issues/10)"
  - docs/deployment.md
  - docs/adr/0014-ansible-desired-state-deployment.md
informed: [Future maintainers]
---

# Remove Ansible Deployment Before OSS Release

ADR 0062 later removed the manual systemd deployment path that this ADR preserved. The Ansible
removal decision remains accepted.

## Context and Problem Statement

The repository had two public server setup paths: manual systemd instructions and an Ansible
desired-state playbook. The installer and managed local worker became the main public setup path,
with direct CLI and manual systemd commands initially available for advanced use.

Keeping Ansible in the OSS release would add documentation, dependency, CI, lint, Renovate, and
support work for a deployment model that has no current user-demand signal.

## Decision Drivers

* Keep the OSS setup surface small and supportable.
* Make the installer the primary public setup path.
* Preserve direct CLI setup for advanced users.
* Remove CI and dependency maintenance that exists only for Ansible.
* Close Ansible smoke-test work instead of building coverage for a removed path.

## Considered Options

* Remove Ansible before OSS release.
* Keep Ansible as a supported public deployment path.
* Keep Ansible as an undocumented internal artifact.
* Replace manual systemd documentation with Ansible-only deployment.

## Decision Outcome

Chosen option: "Remove Ansible before OSS release", because the repository could support installer
and direct CLI paths without carrying another operational model before users ask for managed
deployment automation. ADR 0062 later removed the manual systemd path as well.

### Consequences

* Good, because public docs point users to the installer first.
* Good, because CI no longer installs or lints Ansible-only dependencies.
* Good, because Renovate no longer needs Ansible-only dependency inputs.
* Neutral, because repeatable multi-workflow server deployment may be reconsidered later from a
  fresh user-demand issue.
* Bad, because operators who already used the playbook had to migrate away from Ansible.

### Confirmation

This decision is still implemented when the repository has no `deploy/ansible` tree, no
`docs/ansible-deployment.md`, no Ansible CI or Renovate inputs, and no user-facing setup path that
presents Ansible as supported. README should point users to the installer and direct CLI paths.
Generated workflow blocker guidance should mention setup or workflow host-path settings, not Ansible
variables.

## Pros and Cons of the Options

### Remove Ansible Before OSS Release

Delete the playbook, Ansible guide, Ansible CI/lint steps, Ansible dependency files, and public
references that present Ansible as supported.

* Good, because the supported setup paths are clear.
* Good, because the repository carries less deployment-specific maintenance before release.
* Bad, because repeatable server convergence is no longer provided by this repository.

### Keep Ansible As A Supported Public Deployment Path

Keep the guide, playbook, role, linting, dependencies, Renovate inputs, and future smoke coverage.

* Good, because operators who prefer Ansible would have a ready path.
* Bad, because every release would need to keep that path documented, tested, and supported.
* Bad, because [GitHub issue #10](https://github.com/martin-francois/symphony-trello/issues/10)
  would need more CI smoke work for a path not yet proven useful to OSS users.

### Keep Ansible As An Undocumented Internal Artifact

Remove public documentation but keep the playbook files in the repository.

* Good, because maintainers could still use the existing automation privately.
* Bad, because checked-in files still create dependency, security, review, and drift obligations.
* Bad, because hidden support creates confusion when contributors find the files.

### Replace Manual systemd Documentation With Ansible-Only Deployment

Make Ansible the only server deployment path.

* Good, because repeatable deployment would be the default server story.
* Bad, because it conflicts with the goal of reducing setup surface before OSS release.
* Bad, because users without Ansible would lose the transparent manual path.

## More Information

[GitHub issue #115](https://github.com/martin-francois/symphony-trello/issues/115) requests the
removal, implemented by [GitHub PR #119](https://github.com/martin-francois/symphony-trello/pull/119).
[GitHub issue #10](https://github.com/martin-francois/symphony-trello/issues/10) tracked
Ansible/systemd smoke coverage before those deployment paths were removed. This decision supersedes
[ADR 0014](0014-ansible-desired-state-deployment.md).
