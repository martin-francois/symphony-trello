---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [docs/deployment.md, docs/ansible-deployment.md, docs/adr/0013-systemd-template-deployment.md]
informed: [Future maintainers]
---

# Add Ansible Desired-State Deployment Beside Manual systemd Instructions

## Context and Problem Statement

The manual systemd deployment guide explains the production layout, but repeated server maintenance
is awkward when operators have several workflow files. Adding, changing, or removing workflows should
not require hand-editing systemd services and `/etc/symphony-trello` files every time.

How should the project make repeated deployments easier while keeping the manual systemd path
available?

## Decision Drivers

* Keep the manual deployment guide available for operators who want explicit commands.
* Make repeated deployments idempotent from declared desired state.
* Store production Trello secrets through Ansible Vault rather than plain local files.
* Manage workflow additions, changes, and removals without hand-maintained remote state.
* Avoid adding a custom installer before the systemd layout has more production use.

## Considered Options

* Add an Ansible playbook beside the manual systemd guide.
* Replace the manual guide with Ansible-only deployment.
* Add a Java installer command now.
* Leave workflow updates as manual systemd operations.

## Decision Outcome

Chosen option: "Add an Ansible playbook beside the manual systemd guide", because it makes normal
production maintenance repeatable without hiding the underlying layout or adding application
installer code.

### Consequences

* Good, because operators can keep hosts, workflows, and secrets as declared Ansible inputs.
* Good, because rerunning the playbook applies changed app files and workflow files.
* Good, because removing a workflow from desired state stops and disables the corresponding service.
* Good, because the manual guide remains useful for understanding and troubleshooting.
* Bad, because Ansible and `rsync` become optional deployment-tool dependencies.
* Bad, because workspace deletion still needs human review to avoid losing useful run output.

### Confirmation

Run `ansible-playbook --syntax-check` for the playbook, `pnpm dlx markdownlint-cli2`, and
`./mvnw -q spotless:check verify`.

## Pros and Cons of the Options

### Add an Ansible Playbook Beside the Manual systemd Guide

Provide `deploy/ansible/site.yml`, a role, ignored local inventory and vault paths, and a user guide
that deploys the same systemd template layout from declared workflow files.

* Good, because it preserves the current deployment contract.
* Good, because it handles app sync, workflow sync, service startup, and removed workflow cleanup.
* Good, because Ansible Vault is familiar for production secrets.
* Neutral, because operators still build the Quarkus package locally before deploying.
* Bad, because the playbook needs Ansible, the `ansible.posix` collection, and `rsync`.

### Replace the Manual Guide With Ansible-Only Deployment

Remove most hand-written systemd commands and make Ansible the only documented server path.

* Good, because the docs would be shorter.
* Bad, because troubleshooting is harder when the underlying layout is not explained.
* Bad, because users without Ansible would lose a simple deployment path.

### Add a Java Installer Command Now

Create an application command that installs users, directories, unit files, and workflow files.

* Good, because it would keep deployment automation inside the Java project.
* Bad, because privilege handling, rollback, distro differences, and cleanup policy need more design.
* Bad, because Ansible already handles desired-state server configuration well.

### Leave Workflow Updates as Manual systemd Operations

Keep only the manual deployment guide.

* Good, because it adds no new tooling.
* Bad, because repeated multi-workflow deployment stays cumbersome.
* Bad, because manual service cleanup is easy to forget when workflow files are removed.

## More Information

The Ansible guide is documented in [docs/ansible-deployment.md](../ansible-deployment.md). The
manual systemd guide remains in [docs/deployment.md](../deployment.md).
