---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted:
  - docs/deployment.md
  - "[GitHub issue #13](https://github.com/martin-francois/symphony-trello/issues/13)"
  - "[GitHub issue #115](https://github.com/martin-francois/symphony-trello/issues/115)"
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
* Support the manual systemd path.
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
For parent directories, operators can additionally set `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true` so
systemd still limits visible writable host paths while Codex does not apply a narrower
workspace-write list inside that namespace.

Generated workflow prompts now tell Codex that filesystem blocker comments must explain the access
problem without copying absolute host paths or per-card workspace locations, as refined by
[ADR 0057](0057-path-safe-filesystem-blockers.md).

Generated workflow prompts also tell Codex to choose repository source context in this order:
explicit Trello card repository URL or local checkout path, workflow `repository.default_url`,
workflow `repository.default_path`, and no selected repository. Explicit Trello card sources use
labelled lines such as `Repository URL: <url>`, `Repository path: <path>`, `Local checkout: <path>`,
or `Repository: <url-or-path>`; ordinary unlabelled web links are not selected as repositories. Each
source declaration is read from one logical line, and duplicate declarations must name the same
source. URL-labelled sources accept supported remotes and `file://` URLs; path-labelled sources
accept local paths; generic `Repository:` labels accept either form. HTTP(S) source URLs must not
include user info, query strings, or fragments. A selected local
checkout remains source context by default; Codex should clone from it into the current per-card
workspace before implementation rather than editing that shared checkout directly. After cloning
from a local checkout, Codex should not inherit the source checkout's current branch as the task
base; new task work should start from the repository's default branch when it is discoverable unless
the Trello card clearly requests another base. The existing generated workflow exception remains:
Codex may work directly in the selected checkout only when the Trello card explicitly requests
direct work, the checkout is writable, and deployment filesystem policy permits it. Cross-owner Git
trust, direct-checkout ownership metadata, locking, transaction state, recovery, and managed
checkout enforcement are future issue #33 phases, not part of this ADR's filesystem access decision.

The generated workflow does not treat missing push credentials as a blocker when a Trello card only
asks for local commits. It also allows handoff when broad validation has clearly unrelated failures
and card-specific validation passed, as long as the handoff records the failing command and the
reason it is unrelated.

### Consequences

* Good, because unrelated host paths stay unavailable unless the operator opts in.
* Good, because intended host paths are explicit in systemd and Codex's own sandbox.
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

Run `systemd-analyze verify`, live deployment checks with and without an allowed host path, and
`./mvnw -q spotless:check verify`.

## Pros and Cons of the Options

### Keep Only The Managed Workspace Accessible By Default And Add Explicit Allowed Host Paths

Keep the base unit strict. Expose additional host paths only through a systemd drop-in plus Codex
sandbox writable roots.

* Good, because the operator has to make each exposed path explicit.
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
* Bad, because it is larger than the
  [GitHub issue #13](https://github.com/martin-francois/symphony-trello/issues/13) fix.

### Leave Deployment Access Handling To Documentation Only

Document that cards should use accessible paths, but do not add deployment support.

* Good, because it requires no systemd changes.
* Bad, because operators would still need to hand-roll drop-ins.
* Bad, because repeated deployments could drift from the documented security model.

## More Information

[GitHub issue #13](https://github.com/martin-francois/symphony-trello/issues/13) tracks the deployed
host path access problem. Manual configuration is documented in
[docs/deployment.md](../deployment.md). Ansible support was removed by
[GitHub issue #115](https://github.com/martin-francois/symphony-trello/issues/115).
