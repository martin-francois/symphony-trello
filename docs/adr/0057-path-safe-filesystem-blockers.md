---
status: accepted
date: 2026-06-17
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #431](https://github.com/martin-francois/symphony-trello/issues/431)"
  - "[ADR 0016](0016-explicit-deployed-host-paths.md)"
informed: [Future maintainers, Contributors]
---

# Keep Filesystem Blocker Text Path-Safe

## Context and Problem Statement

Generated workflows ask Codex to move a Trello card to `Blocked` when the card requests a host file
or folder that deployed Symphony cannot access. Earlier prompt wording asked Codex to include exact
local path details.

Trello comments and workpad updates are visible to users and may be copied into GitHub issues. They
must not expose private host paths, per-card workspace locations, account names, or deployment-specific
paths.

How should filesystem access blockers stay useful without leaking private local context?

## Decision Drivers

* Explain the blocker well enough for an operator to fix deployment access.
* Keep Trello-visible comments and workpads safe to copy into issue reports.
* Preserve the explicit allowed-host-path deployment model from ADR 0016.
* Avoid adding a runtime sanitizer for every Codex-written Trello comment in this bug fix.

## Considered Options

* Keep exact local path details in Trello blocker comments.
* Ask Codex to use path-safe labels in Trello-visible filesystem blocker comments and workpads.
* Add a runtime sanitizer for all Codex-written Trello comments and workpad updates.
* Only document the risk without changing generated prompts or shipped skills.

## Decision Outcome

Chosen option: ask Codex to use path-safe labels in Trello-visible filesystem blocker comments and
workpads.

Generated workflows and shipped skills tell Codex to explain that the requested file or folder is
inaccessible because undeclared host paths are blocked by default. They also tell Codex to point the
operator to the allowed-host-path setup or workflow settings. They must not copy absolute host paths,
per-card workspace locations, account names, or deployment-specific paths into Trello-visible text. Use
labels such as "the requested path" and "the per-card workspace" instead.

### Consequences

* Good, because Trello-visible blocker text no longer needs to expose private host paths.
* Good, because operators still learn which deployment setting to change.
* Neutral, because the exact path may need to be recovered from the original Trello card, local
  diagnostics with private context, or local logs.
* Bad, because this depends on generated prompt and skill instructions. A future runtime sanitizer
  may still be useful if Codex repeatedly ignores the rule.

### Confirmation

Generate a workflow and confirm the filesystem blocker guidance says not to copy absolute host
paths, per-card workspace locations, account names, or deployment-specific paths into Trello-visible
text.

The project had no public users when this wording changed, so Symphony does not carry a runtime
migration path, historical prompt detector, or generated-workflow ownership marker. Existing private
workflows were updated manually once. Runtime code parses the workflow it is given and validates the
current schema; it does not rewrite older private prompt text.

Run the focused tests:

```bash
./mvnw -q -Dtest=TrelloBoardSetupTest test
```

## Pros and Cons of the Options

### Keep Exact Host and Workspace Paths in Trello Blocker Comments

Leave generated prompts and shipped skills as they were.

* Good, because operators would see the exact path that failed.
* Bad, because Trello-visible comments could expose private host paths, account names, or deployment
  layout.
* Bad, because users might copy those comments into public issue reports.

### Use Path-Safe Labels in Trello-Visible Text

Ask Codex to describe the failed access without copying private paths into Trello comments or
workpads.

* Good, because Trello-visible text stays safer to share.
* Good, because the comment still points operators to the allowed-host-path setting they need to
  change.
* Bad, because operators may need local diagnostics or logs to recover the exact path.

### Add a Runtime Sanitizer for All Codex-Written Trello Text

Sanitize every Trello comment and workpad update before sending it to Trello.

* Good, because it could protect text even when generated prompts are missed.
* Bad, because it is broader than this bug fix.
* Bad, because a sanitizer can hide useful card-specific context or miss new forms of private
  context if it is incomplete.

### Only Document the Risk

Leave generated output unchanged and document that users should avoid sharing private paths.

* Good, because it has the smallest implementation cost.
* Bad, because it does not prevent the generated blocker comments from containing private paths.
* Bad, because users may not see the documentation before copying a Trello comment into an issue.

## More Information

[GitHub issue #431](https://github.com/martin-francois/symphony-trello/issues/431) describes the
path exposure found in filesystem blocker comments.
[ADR 0016](0016-explicit-deployed-host-paths.md) defines the allowed-host-path access model that
these blocker comments reference.
