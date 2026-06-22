---
status: accepted
date: 2026-06-22
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #33](https://github.com/martin-francois/symphony-trello/issues/33)"
  - "[ADR 0028](0028-specification-authority.md)"
  - "[ADR 0056](0056-github-workflow-network-sandbox.md)"
informed: [Future maintainers, Contributors]
---

# Keep Repository Checkout Policy In Generated Workflows

## Context and Problem Statement

[GitHub issue #33](https://github.com/martin-francois/symphony-trello/issues/33) asks Symphony for
Trello to make repository selection and workspace preparation deterministic. The immediate product
need is clear:

* cards that name a repository URL should work in a safe writable checkout;
* cards that name a local checkout path should treat it as source context by default, not mutate it
  accidentally;
* one-board-per-repository workflows should not require every Trello card to repeat the same
  repository URL;
* if no repository source is available, Symphony should block with Trello-visible guidance instead
  of guessing.

The harder decision is the owning layer. The Java service already owns per-card workspace creation,
hooks, Codex launch, Trello state transitions, and generated starter workflows. It does not own a
general Git repository manager, provider permission probe, fork manager, or shared base-clone cache.

The checked upstream Symphony reference implementation is informative but not binding. Upstream
creates deterministic per-issue workspaces and launches Codex there. Its spec says checkout and sync
are implementation-defined and typically handled by hooks. Its concrete workflow uses
`hooks.after_create` to clone the upstream repository into each workspace and does not ship a
general repository manager.

Which layer should own repository checkout and worktree policy for the first public release?

## Decision Drivers

* Keep the Java scheduler boundary small unless Java ownership materially improves reliability or
  safety.
* Support the common one-board-per-repository shape without repetitive Trello card URLs.
* Keep repository write safety visible in `WORKFLOW.md`, where operators can review and customize it.
* Avoid editing host checkouts by default, even when they are writable.
* Avoid guessing a repository from previous cards, unrelated local checkouts, or leftover workspace
  contents.
* Preserve the pre-public clean-break policy: implement the current contract only, without migration
  or historical workflow detection.

## Considered Options

* Strengthen generated workflow text and shipped skills.
* Add a scoped Codex-callable repository preparation tool.
* Add a Java pre-run repository manager before Codex starts.
* Add a workflow or connected-board repository-default config field.

## Decision Outcome

Chosen option: strengthen generated workflow text and shipped skills. Repository preparation remains
in generated workflows, hooks, and the agent's repository skills for the first public release.

The Java runtime continues to create isolated per-card workspaces and run configured hooks. It does
not parse Trello card text for repository references, create worktrees before Codex starts, manage
shared base clones, create forks, or probe provider push permissions.

Generated workflows define the current repository source precedence:

1. An explicit Trello card repository URL or local checkout path.
2. An existing Git checkout prepared in the per-card workspace by workflow hooks.
3. No selected repository.

The workflow must tell Codex not to infer a repository from previous Trello cards, unrelated host
checkouts, branch names, or leftover workspace contents. If neither a card-level source nor a
workflow-prepared checkout exists, Codex must move the Trello card to `Blocked` with path-safe
guidance instead of guessing.

### One-Board-Per-Repository Workflows

This decision supports a workflow-level default repository through `hooks.after_create`. Operators
who use one Trello board for one repository can configure the hook to clone that repository into
each per-card workspace. Cards on that board can then omit the repository URL.

This is intentionally not a connected-board manifest field for the first public release. The hook
keeps the behavior in the workflow contract, matches the upstream reference shape, works with any
Git host the operator can script, and avoids adding another Java configuration surface before the
repository policy is proven in live use.

Card-level repository URLs and local checkout paths take precedence over the workflow-prepared
checkout. This keeps multi-repository boards possible and makes overrides explicit.

### URL-Only Cards

When a Trello card names only a repository URL, generated workflows tell Codex to create or reuse a
writable task checkout in a stable repository-named subdirectory of the current per-card workspace.
Codex should prefer cloning from a readable matching local checkout under an allowed host path and
otherwise clone the repository URL directly.

The first release does not maintain a long-lived shared base clone/cache. Per-card workspace clones
are less efficient, but they avoid shared mutable Git state, cleanup races, fork-cache ownership
problems, and provider-specific Java code. A future issue can introduce a scoped repository tool or
Java manager if live E2E shows the prompt/skills layer is not reliable enough.

### Local Checkout Paths

When a Trello card names a local checkout path, generated workflows tell Codex to inspect it as
source context. By default, Codex should clone from that readable checkout into the per-card
workspace and work in the clone. It may work directly in the provided checkout only when the card
explicitly asks for that, the checkout is writable, and deployment filesystem policy allows it.

Task branches should start from the repository default branch when discoverable, usually
`origin/main`, unless the card explicitly asks for a different base. Codex must not silently inherit
the source checkout's current branch.

### Push And Fork Handling

Generated GitHub workflows already grant Codex `workspaceWrite` with `networkAccess: true` per
[ADR 0056](0056-github-workflow-network-sandbox.md). Publishing remains agent/skill-owned:

* direct branch push is used when the authenticated GitHub identity may push;
* the PR publishing skill handles auth, stale branches, branch-protection failures, and a GitHub fork
  fallback when the authenticated user cannot push branches to the target repository;
* when a required PR cannot be created or updated because push, fork, auth, or repository policy is
  unavailable, Codex blocks the Trello card with the exact path-safe blocker.

This does not implement fork creation in Java. If prompt/skill-owned fork handling proves too
unreliable in live use, it should move into a scoped tool or Java repository manager with focused
provider tests.

### Cleanup

Terminal cleanup remains workspace cleanup. `hooks.before_remove` can do repository-specific cleanup
such as closing disposable pull requests or deleting external resources. Because this decision does
not create shared base clones, Java cleanup must not delete or mutate operator-managed Git caches.

### Consequences

* Good, because the repository policy is visible in generated `WORKFLOW.md`.
* Good, because one-board-per-repository users can use the same workflow-level hook pattern as the
  upstream reference implementation.
* Good, because Java runtime code stays provider-neutral and does not need to parse arbitrary Trello
  prose for repository URLs.
* Good, because per-card workspace checkouts avoid cross-card worktree interference.
* Bad, because prompt/skill compliance is weaker than a deterministic Java repository manager.
* Bad, because per-card clones are less efficient than a shared base clone/cache.
* Bad, because direct-push and fork fallback are not enforced before Codex starts.

### Confirmation

Generate a workflow and confirm the repository checkout policy says:

* explicit Trello card repository URLs or local checkout paths take precedence;
* a `hooks.after_create` checkout can be used as the workflow-level default repository;
* missing card-level and workflow-level repository sources block instead of guessing;
* local host checkouts are source context by default and direct edits require explicit card intent.

Run:

```bash
./mvnw -q -Dtest=TrelloBoardSetupTest test
```

When changing the policy later, use the opt-in live E2E repository scenario with disposable
repositories. It covers a URL-only card, a workflow-default card, a read-only local-checkout card,
and a no-source blocker card.

## Pros and Cons of the Options

### Strengthen Generated Workflow Text And Shipped Skills

Keep repository preparation in the workflow contract and agent instructions.

* Good, because it matches the current spec boundary and upstream hook pattern.
* Good, because operators can customize repository setup with normal shell hooks.
* Good, because it works across Git hosts without Java provider-specific code.
* Bad, because correctness depends on Codex following the workflow.
* Bad, because automated tests can prove generated instructions but not every live Git path.

### Add A Scoped Repository Preparation Tool

Expose a narrow tool that Codex calls to prepare a repository workspace.

* Good, because clone/worktree/fork behavior could become deterministic while remaining
  agent-initiated.
* Good, because it could centralize URL parsing and provider permission checks.
* Bad, because it adds a new tool protocol, provider-specific behavior, and more test surface.
* Bad, because it is premature before the prompt/skills layer is proven insufficient.

### Add A Java Pre-Run Repository Manager

Have Java parse repository references and prepare checkouts before Codex starts.

* Good, because the service could fail before launching Codex when repository preparation is
  impossible.
* Good, because Java could enforce worktree isolation and shared base-clone cleanliness.
* Bad, because arbitrary Trello prose repository parsing belongs poorly in the scheduler.
* Bad, because GitHub fork and push permission behavior would move into runtime code.
* Bad, because it increases coupling between Trello card text, Git hosting, and worker scheduling.

### Add A Workflow Or Connected-Board Repository-Default Field

Create a first-class config field for a default repository.

* Good, because cards could omit repository URLs without a custom hook.
* Good, because Java could render the default repository into the prompt explicitly.
* Bad, because the existing hook mechanism already supports this shape without another config
  surface.
* Bad, because it would still need a checkout implementation layer decision.
* Bad, because connected-board manifest defaults would be harder for operators to version with the
  workflow.

## More Information

[GitHub issue #33](https://github.com/martin-francois/symphony-trello/issues/33) contains the
repository checkout/worktree policy request and upstream reference notes.
