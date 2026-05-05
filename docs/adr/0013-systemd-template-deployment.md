---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [README.md, docs/deployment.md, docs/adr/0010-one-process-per-trello-board.md]
informed: [Future maintainers]
---

# Provide a systemd Template for Multi-Workflow Server Deployment

## Context and Problem Statement

Symphony for Trello runs one process per workflow file and Trello board. That model is clear, but it
becomes inconvenient on a server when an operator has several workflows and must keep them running
after logging out.

How should the project make production-style server deployment easier without adding a larger process
manager or changing the one-workflow-per-process contract?

## Decision Drivers

* Keep the runtime contract from ADR 0010: one process per workflow file and Trello board.
* Make several workflows easy to start, stop, inspect, and restart independently.
* Use standard Linux service management instead of a custom supervisor.
* Keep credentials outside workflow files.
* Keep installation understandable before adding more automation.
* Avoid pretending the project has a full installer before the deployment shape has been used more.

## Considered Options

* Document a systemd template service and production directory layout.
* Add a Java `install-systemd` command immediately.
* Add a multi-workflow supervisor mode inside the application.
* Leave production deployment as raw `java -jar` commands.

## Decision Outcome

Chosen option: "Document a systemd template service and production directory layout", because it
removes the biggest repeated setup burden while keeping the implementation simple and aligned with
the existing process model.

### Consequences

* Good, because one installed app can run many workflow service instances.
* Good, because each workflow keeps separate logs, restart state, HTTP port, and workspace root.
* Good, because systemd handles restart-on-failure and boot-time startup.
* Good, because secrets live in `/etc/symphony-trello/env` instead of workflow files.
* Bad, because installing users, directories, workflow files, and unit files is still manual.
* Bad, because non-systemd platforms still need their own deployment guide.

### Confirmation

Run `./mvnw -q spotless:check verify` and `pnpm dlx markdownlint-cli2`. Review
`docs/deployment.md` from the perspective of an operator deploying two workflow files on one Linux
server.

## Pros and Cons of the Options

### Document a systemd template service and production directory layout

Provide `deploy/systemd/symphony-trello@.service` and a deployment guide that maps
`symphony-trello@project-a` to `/etc/symphony-trello/workflows/project-a.WORKFLOW.md`.

* Good, because systemd template instances match the one-workflow-per-process runtime model.
* Good, because operators can use normal `systemctl` and `journalctl` commands.
* Good, because this is easy to review and adjust before automating installation.
* Neutral, because workflow files still carry per-board configuration such as `server.port`.
* Bad, because the first setup still has several copy and directory commands.

### Add a Java `install-systemd` command immediately

Create a setup command that installs directories, unit files, and workflow files automatically.

* Good, because it would be more convenient for operators.
* Bad, because installer behavior needs careful privilege, overwrite, distro, and rollback handling.
* Bad, because the manual layout should be validated before freezing it into an installer command.

### Add a multi-workflow supervisor mode inside the application

Let one Java process read several workflow files and manage all boards internally.

* Good, because operators would start one process.
* Bad, because it would break the current one-workflow-per-process model.
* Bad, because it introduces cross-workflow lifecycle and failure semantics not required by the spec.

### Leave production deployment as raw `java -jar` commands

Document only direct Java commands for every workflow.

* Good, because it adds no deployment artifacts.
* Bad, because operators must create their own restart and boot-time behavior.
* Bad, because several workflows become awkward to manage consistently.

## More Information

Future deployment automation work is tracked in
[GitHub issue #6](https://github.com/martinfrancois/symphony-trello/issues/6) instead of
user-facing setup documentation.
