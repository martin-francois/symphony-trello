---
status: accepted
date: 2026-06-17
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #426](https://github.com/martin-francois/symphony-trello/issues/426)"
informed: [Future maintainers, Contributors]
---

# Enable Network Access For Generated GitHub Workflows

## Context and Problem Statement

Generated GitHub workflows tell Codex to clone repositories, push branches, and create or update
pull requests. A live private-repository test showed that the workflow could reach the Trello card
and start Codex, but Codex blocked before creating a branch or pull request because shell GitHub
network access was unavailable.

The workflow still should not disable Codex's filesystem sandbox by default. Users can opt into
`dangerFullAccess`, but GitHub pull-request work should not require that broader mode only to reach
GitHub.

## Decision Drivers

* Keep GitHub pull-request workflows usable by default.
* Keep Codex's filesystem sandbox enabled by default.
* Avoid making generated GitHub workflows depend on a separate connector write fallback.
* Keep the behavior explicit in `WORKFLOW.md`.

## Considered Options

* Keep generated GitHub workflows unchanged and let cards block when Codex has no network access.
* Make generated GitHub workflows use `dangerFullAccess`.
* Make generated GitHub workflows use `workspaceWrite` with `networkAccess: true`.
* Try to route pull-request writes only through a GitHub connector instead of shell GitHub access.

## Decision Outcome

Chosen option: generated GitHub workflows use a Codex `workspaceWrite` turn sandbox policy with
`networkAccess: true`.

This gives Codex the network access needed for `git`, `gh`, and pull-request publication while
keeping the filesystem sandbox. Additional writable roots are still merged into the same
`workspaceWrite` policy. `dangerFullAccess` remains an explicit setup choice for trusted workflows
that need it.

### Consequences

* Good, because private-repository GitHub PR tasks can use the generated happy path.
* Good, because the default still limits filesystem writes to the workspace and configured roots.
* Good, because the requirement is visible in generated workflow front matter.
* Bad, because GitHub-enabled generated workflows allow network access for Codex turns.

### Confirmation

Generate a GitHub workflow and confirm it includes:

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

### Keep Generated GitHub Workflows Unchanged

Do not add Codex network access to generated GitHub workflows.

* Good, because the generated sandbox policy would stay narrower.
* Bad, because private-repository PR work can block before Codex creates a branch or pull request.
* Bad, because users would need to diagnose a generated workflow that cannot perform its documented
  GitHub task.

### Use `dangerFullAccess`

Generate GitHub workflows with `dangerFullAccess`.

* Good, because Codex would have network access.
* Bad, because it disables Codex's filesystem sandbox for the whole turn.
* Bad, because it makes a broad access mode the default for ordinary GitHub pull-request work.

### Use `workspaceWrite` With `networkAccess: true`

Generate GitHub workflows with a workspace-write sandbox and explicit network access.

* Good, because Codex can use `git`, `gh`, and networked repository operations.
* Good, because filesystem writes remain limited to the workspace and configured writable roots.
* Good, because the network requirement is visible in generated workflow front matter.
* Bad, because Codex turns for GitHub-enabled workflows can reach the network by default.

### Route Pull-Request Writes Only Through a Connector

Keep shell network access disabled and use only a GitHub connector for branch and PR writes.

* Good, because shell commands would not need network access for GitHub writes.
* Bad, because generated workflows and shipped skills currently use repository-local `git` and `gh`
  commands.
* Bad, because this would be a larger workflow redesign instead of a focused bug fix.

## More Information

[GitHub issue #426](https://github.com/martin-francois/symphony-trello/issues/426) describes the
live failure that showed generated private-repository workflows needed network access.
