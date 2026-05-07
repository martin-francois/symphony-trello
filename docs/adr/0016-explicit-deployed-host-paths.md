---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [docs/deployment.md, docs/ansible-deployment.md, GitHub issue 13]
informed: [Future maintainers]
---

# Keep Deployed Host Paths Explicit

## Context and Problem Statement

A deployed workflow can receive a Trello card that asks Codex to work with a local file, folder, or
project checkout outside Symphony's managed workspace area. The service may be healthy, Codex may be
authenticated, and Trello handoff may work, while the requested host path is still inaccessible to
the service user or systemd sandbox.

How should deployed Symphony for Trello expose existing host paths without making broad host
filesystem access the default?

## Decision Drivers

* Keep the production default narrow and predictable.
* Let operators intentionally expose host paths that cards are allowed to modify.
* Support the manual systemd path and the Ansible path.
* Make filesystem blockers visible in Trello comments.
* Avoid changing the core Symphony workflow contract or requiring a project checkout manager now.

## Considered Options

* Keep only the managed workspace accessible by default and add explicit allowed host paths.
* Expose the host filesystem broadly by default.
* Copy every target project into the per-card workspace before each run.
* Leave deployment access handling to documentation only.

## Decision Outcome

Chosen option: "Keep only the managed workspace accessible by default and add explicit allowed host
paths", because it preserves the secure deployment posture while making intended file, folder, or
checkout access a deliberate operator choice.

The base systemd unit continues to protect home directories and keeps writable access limited to
Symphony state. Manual deployments can add a systemd drop-in with read-only temporary filesystems for
home locations, declared bind mounts, and matching Codex sandbox writable roots for specific paths.
The Ansible role manages those settings from `symphony_trello_allowed_host_paths`, which accepts a
list of concrete files or folders. The older `symphony_trello_allowed_project_roots` variable remains
a compatibility alias for existing deployments. For parent directories, operators can additionally
set `symphony_trello_codex_danger_full_access` so systemd still limits visible writable host paths
while Codex does not apply a narrower workspace-write list inside that namespace. The role also
exposes a broader `symphony_trello_allow_host_filesystem` switch for trusted single-user machines.

Generated workflow prompts now tell Codex that filesystem blocker comments must state the
inaccessible path, that deployed Symphony blocks undeclared host paths by default for security
reasons, where accessible workspace files are, and which deployment settings allow one or more host
paths.

Generated workflow prompts also tell Codex not to edit shared host checkouts directly when a writable
per-card checkout can be created. If a card names only a repository URL, Codex should create or reuse
a writable checkout under the current per-card workspace, preferring a readable matching local
checkout as the clone source when available. If a card names a read-only local checkout, Codex should
inspect it as source context and clone it into the current workspace before implementing the task.
The workflow also tells Codex to add only the source checkout to the current user's Git
`safe.directory` list when Git refuses a read-only local clone because the source checkout is owned
by another user. It tells Codex to start task branches from the repository's default branch when that
branch is discoverable so local clones do not inherit a shared checkout's current feature branch.
This keeps shared checkouts clean while avoiding blockers when the source is readable but not
writable.

The generated workflow does not treat missing push credentials as a blocker when a Trello card only
asks for local commits. It also allows handoff when broad validation has clearly unrelated failures
and card-specific validation passed, as long as the handoff records the failing command and the
reason it is unrelated.

### Consequences

* Good, because unrelated host paths stay unavailable unless the operator opts in.
* Good, because intended host paths can be managed idempotently by Ansible across systemd and
  Codex's own sandbox.
* Good, because blocked Trello comments should explain host access problems without requiring
  operators to inspect systemd units first.
* Neutral, because parent directories with several checkouts may need a less strict Codex sandbox
  inside the still-restricted systemd namespace.
* Neutral, because existing workflow files need their prompt text updated to get the improved
  blocker-comment wording.
* Neutral, because URL-only or read-only-checkout cards rely on the generated workflow prompt and
  Codex behavior rather than a built-in checkout manager.
* Bad, because operators still need to decide which host paths are safe to expose.

### Confirmation

Run `ansible-playbook --syntax-check`, `ansible-lint`, `systemd-analyze verify`, live deployment
checks with and without an allowed host path, and `./mvnw -q spotless:check verify`.

## Pros and Cons of the Options

### Keep Only The Managed Workspace Accessible By Default And Add Explicit Allowed Host Paths

Keep the base unit strict. Expose additional host paths only through a systemd drop-in plus Codex
sandbox writable roots, or through Ansible variables that manage both.

* Good, because the operator has to make each exposed path explicit.
* Good, because Ansible can converge the drop-in and restart services only when inputs changed.
* Good, because the manual deployment path remains transparent.
* Bad, because cards that mention unconfigured host paths still block until the operator adds access
  or changes the workflow.

### Expose The Host Filesystem Broadly By Default

Disable home protection and make the host filesystem writable to Codex sessions.

* Good, because existing checkouts work without extra deployment configuration.
* Bad, because a Trello card could cause Codex to inspect or modify unrelated host files.
* Bad, because the deployment would no longer be safe by default.

### Copy Every Target Project Into The Per-Card Workspace Before Each Run

Add first-class project checkout provisioning and run Codex only inside managed copies.

* Good, because it could keep host access narrow while giving each card a prepared project.
* Bad, because clone/update policy, credentials, branches, dirty worktrees, cleanup, and conflict
  handling need a separate design.
* Bad, because it is larger than the issue 13 fix.

### Leave Deployment Access Handling To Documentation Only

Document that cards should use accessible paths, but do not add deployment support.

* Good, because it requires no systemd or Ansible changes.
* Bad, because operators would still need to hand-roll drop-ins.
* Bad, because repeated deployments could drift from the documented security model.

## More Information

Issue 13 tracks the deployed host path access problem. Manual configuration is documented in
[docs/deployment.md](../deployment.md), and Ansible configuration is documented in
[docs/ansible-deployment.md](../ansible-deployment.md).
