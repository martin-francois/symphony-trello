---
status: accepted
date: 2026-06-17
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #426](https://github.com/martin-francois/symphony-trello/issues/426)"
  - "[GitHub issue #496](https://github.com/martin-francois/symphony-trello/issues/496)"
informed: [Future maintainers, Contributors]
---

# Enable Network Access For Generated Workflows

## Context and Problem Statement

Generated GitHub workflows tell Codex to clone repositories, push branches, and create or update
pull requests. A live private-repository test showed that the workflow could reach the Trello card
and start Codex, but Codex blocked before creating a branch or pull request because shell GitHub
network access was unavailable.

The workflow still should not disable Codex's filesystem sandbox by default. Users can opt into
`dangerFullAccess`, but GitHub pull-request work should not require that broader mode only to reach
GitHub.

Later live testing found the same network requirement for generated Trello-only workflows when a
workflow configured an HTTPS `repository.default_url`. Codex selected the default URL correctly, but
the generated Trello-only sandbox did not allow DNS/network access, so the clone could not run even
though the host could reach the public repository.

## Decision Drivers

* Keep generated workflows usable with selected repository URLs by default.
* Keep Codex's filesystem sandbox enabled by default.
* Avoid making generated GitHub workflows depend on a separate connector write fallback.
* Keep the behavior explicit in `WORKFLOW.md`.

## Considered Options

* Keep generated workflows unchanged and let cards block when Codex has no network access.
* Make generated workflows use `dangerFullAccess`.
* Make generated workflows use `workspaceWrite` with `networkAccess: true`.
* Try to route pull-request writes only through a GitHub connector instead of shell GitHub access.

## Decision Outcome

Chosen option: generated workflows use a Codex `workspaceWrite` turn sandbox policy with
`networkAccess: true`.

This gives Codex the network access needed for selected remote repository URLs, `git`, `gh`, and
pull-request publication while keeping the filesystem sandbox. Additional writable roots are still merged into the same
`workspaceWrite` policy. `dangerFullAccess` remains an explicit setup choice for trusted workflows
that need it.

### Consequences

* Good, because remote repository defaults and private-repository GitHub PR tasks can use the
  generated happy path.
* Good, because the default still limits filesystem writes to the workspace and configured roots.
* Good, because the requirement is visible in generated workflow front matter.
* Bad, because generated workflows allow network access for Codex turns.

### Confirmation

Generate a workflow and confirm it includes:

```yaml
codex:
  turn_sandbox_policy:
    type: workspaceWrite
    networkAccess: true
```

The project had no public users when this behavior changed, so Symphony does not carry a runtime
migration path or historical generated-template detector. Existing private workflows were updated
manually once. Runtime code parses the workflow it is given and validates the current schema; it does
not infer generated ownership or backfill network access for older private files.

Run the focused tests:

```bash
./mvnw -q -Dtest=CodexAppServerClientTest,TrelloBoardSetupTest,LocalSetupTest test
```

## Pros and Cons of the Options

### Keep Generated Workflows Unchanged

Do not add Codex network access to generated workflows.

* Good, because the generated sandbox policy would stay narrower.
* Bad, because HTTPS repository defaults can block before Codex can clone the selected source.
* Bad, because private-repository PR work can block before Codex creates a branch or pull request.
* Bad, because users would need to diagnose a generated workflow that cannot perform its documented
  repository or GitHub task.

### Use `dangerFullAccess`

Generate workflows with `dangerFullAccess`.

* Good, because Codex would have network access.
* Bad, because it disables Codex's filesystem sandbox for the whole turn.
* Bad, because it makes a broad access mode the default for ordinary repository work.

### Use `workspaceWrite` With `networkAccess: true`

Generate workflows with a workspace-write sandbox and explicit network access.

* Good, because Codex can use `git`, `gh`, and networked repository operations.
* Good, because filesystem writes remain limited to the workspace and configured writable roots.
* Good, because the network requirement is visible in generated workflow front matter.
* Bad, because Codex turns for generated workflows can reach the network by default.

### Route Pull-Request Writes Only Through a Connector

Keep shell network access disabled and use only a GitHub connector for branch and PR writes.

* Good, because shell commands would not need network access for GitHub writes.
* Bad, because generated workflows and shipped skills currently use repository-local `git` and `gh`
  commands.
* Bad, because this would be a larger workflow redesign instead of a focused bug fix.

## More Information

[GitHub issue #426](https://github.com/martin-francois/symphony-trello/issues/426) describes the
live failure that showed generated private-repository workflows needed network access.
[GitHub issue #496](https://github.com/martin-francois/symphony-trello/issues/496) describes the
live failure that showed generated Trello-only workflows also need network access for selected HTTPS
repository defaults.
