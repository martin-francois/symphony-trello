---
status: accepted
date: 2026-06-25
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #33](https://github.com/martin-francois/symphony-trello/issues/33)"
  - "[SPEC.md](../../SPEC.md)"
informed: [Future maintainers, Contributors]
---

# Define Generated Workflow Repository Source Precedence

## Context and Problem Statement

Issue #33 asks Symphony for Trello to define how Trello card repository URLs, local checkout paths,
and workflow-level repository defaults interact.

The repository manager prototype showed that Java-owned checkout preparation is larger than source
precedence alone. A safe Java implementation also needs process lifetime ownership, filesystem and
repository identity continuity, direct-checkout transaction state, provider and credential rules,
and durable Trello blocker handoff. Keeping all of that in one first PR made the review surface too
large.

What should Phase 1 standardize now without claiming the Java runtime owns repository preparation?

## Decision Drivers

* Let one-board-per-repository workflows avoid repeating the same repository URL on every Trello
  card.
* Preserve explicit Trello card source overrides for multi-repository boards.
* Avoid guessing from previous cards, local checkouts, branch names, or workspace residue.
* Keep Java changes small enough to review and verify independently.
* Keep Java pre-run checkout preparation available as future work without shipping a partial unsafe
  manager.

## Considered Options

* Keep only card-authored repository sources and require every card to repeat the repository URL or
  path.
* Add workflow defaults and source precedence while keeping repository preparation workflow-owned.
* Ship the Java repository manager prototype as the first issue #33 PR.
* Add direct checkout as part of Phase 1.

## Decision Outcome

Chosen option: add workflow defaults and source precedence while keeping repository preparation
workflow-owned.

Phase 1 adds optional workflow config:

```yaml
repository:
  default_url: null
  default_path: null
```

Source precedence is:

1. explicit Trello card repository URL or local checkout path;
2. workflow `repository.default_url`;
3. workflow `repository.default_path`;
4. no selected repository.

Explicit Trello card sources use a labelled line such as `Repository URL: <url>`,
`Repository path: <path>`, `Local checkout: <path>`, or `Repository: <url-or-path>` in the title,
description, or a Trello comment. Ordinary unlabelled web links are not selected as repositories.
Each source declaration is read from one logical line. If multiple declarations are present, they
must all name the same source. URL labels and `repository.default_url` accept credential-free
HTTP(S), username-only `ssh://`, SCP-style SSH such as `git@example.com:team/project.git`, and
`file://` URLs. Path and checkout labels accept local checkout paths. Generic `Repository:` labels
accept either form. HTTP(S) source URLs must not include user info, query strings, or fragments. URI
paths may keep safe percent-encoding, but encoded or literal control characters are invalid.

`repository.default_url` and `repository.default_path` use the existing environment-reference
conventions. Missing or blank optional environment values resolve to absent. Relative
`repository.default_path` values resolve relative to `WORKFLOW.md`.

A valid selected source suppresses lower-priority fallbacks. An invalid explicit Trello card source
does not silently fall back to workflow defaults.

Generated workflow text tells Codex to prepare a writable checkout under the current per-card
workspace from the selected URL or local source path. It also tells Codex to use the configured
blocker destination, review fallback, or path-safe workpad/final-response guidance when no usable
repository source exists.

The existing generated workflow direct-work exception is preserved: a selected local checkout is
source context by default and should be cloned into the per-card workspace, but Codex may work
directly in that checkout when the Trello card explicitly requests direct work, the checkout is
writable, and deployment filesystem policy permits it, including Git metadata writes when the task
needs direct commits. `--add-path <checkout>` grants extra filesystem access but is not a
direct-checkout commit guarantee.

Phase 1 also preserves the existing agent-owned base-selection instruction for local sources: after
cloning from a local checkout, Codex should not inherit the source checkout's current branch as the
task base. New task work should start from the repository's default branch when it is discoverable
unless the Trello card clearly requests another base. Machine-parsed base selection and Java
enforcement are deferred.

Java does not run Git, inspect repositories, clone, prepare checkouts, enforce repository identity,
perform first-class direct checkout, supervise Git processes, or persist repository blocker handoff
state in this phase. A first-class direct-checkout runtime subsystem with ownership metadata,
locking, transaction state, and recovery is deferred.

Issue #33 remains open for later managed-checkout and live E2E work.

### Consequences

* Good, because workflow defaults and source precedence become explicit and testable.
* Good, because the first issue #33 PR stays small and mergeable.
* Good, because no partial Java checkout manager is shipped.
* Neutral, because generated workflow/agent behavior still performs repository preparation.
* Bad, because managed checkout safety, direct-checkout transactions, durable preflight handoff, and
  provider-aware publication remain follow-up work.

### Confirmation

Run focused tests for config resolution and generated workflow output:

```bash
./mvnw -q -Dtest=ConfigResolverTest,TrelloBoardSetupTest test
```

Also confirm the final PR does not add Java Git commands, a Java repository checkout preparer, direct
checkout support, a process runner, pre-Codex repository blockers, suppression maps, fork
publication, PR helper scripts, repo-sync scripts, runtime publication context, or repository-specific
skill installation.

Live Trello/Codex E2E is not claimed by this phase. It remains required before closing issue #33.

## Pros and Cons of the Options

### Keep Only Card-Authored Repository Sources

Require every Trello card to repeat the repository URL or local path.

* Good, because it preserves current behavior.
* Bad, because one-board-per-repository teams repeat noisy boilerplate on every card.
* Bad, because no precedence exists when a future default is added.

### Add Workflow Defaults and Source Precedence

Add optional workflow defaults and document how explicit card sources override them.

* Good, because it solves the source-precedence part of issue #33.
* Good, because the behavior can be tested without Git or network access.
* Bad, because checkout preparation still depends on generated workflow instructions.

### Ship the Java Repository Manager Prototype

Prepare repositories before Codex starts.

* Good, because it could eventually provide stronger determinism.
* Bad, because the prototype is too broad for one reviewable PR.
* Bad, because known safe behavior requires additional lifecycle, process, filesystem, direct
  checkout, and Trello handoff contracts.

### Add Direct Checkout in Phase 1

Allow Codex to work directly in a supplied local checkout when requested.

* Good, because it preserves the existing explicit agent-driven exception for operator workflows
  that deliberately request direct work.
* Bad, because direct checkout needs explicit ownership metadata, concurrency leases, branch
  ancestry policy, safe-directory handling, and cleanup rules.
* Bad, because a first-class Java direct-checkout runtime subsystem is not necessary to define
  repository source precedence.

## More Information

[GitHub issue #33](https://github.com/martin-francois/symphony-trello/issues/33) tracks the broader
managed repository checkout and worktree policy.
