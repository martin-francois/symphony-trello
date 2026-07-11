---
status: accepted
date: 2026-06-25
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #33](https://github.com/martin-francois/symphony-trello/issues/33)"
  - "[GitHub issue #465](https://github.com/martin-francois/symphony-trello/issues/465)"
  - "[GitHub issue #540](https://github.com/martin-francois/symphony-trello/issues/540)"
  - "[GitHub issue #545](https://github.com/martin-francois/symphony-trello/issues/545)"
  - "[SPEC.md](../../SPEC.md)"
informed: [Future maintainers, Contributors]
---

# Define Generated Workflow Repository Source Precedence

## Context and Problem Statement

[GitHub issue #33](https://github.com/martin-francois/symphony-trello/issues/33) historically asked
Symphony for Trello to define how Trello card repository URLs, local checkout paths, and
workflow-level repository defaults interact. It and the related live-verification
[GitHub issue #465](https://github.com/martin-francois/symphony-trello/issues/465) are now closed as
not planned; both remain archival design context rather than active implementation ownership.

The repository manager prototype showed that Java-owned checkout preparation is larger than source
precedence alone. A safe Java implementation also needs process lifetime ownership, filesystem and
repository identity continuity, direct-checkout transaction state, provider and credential rules,
and durable Trello blocker handoff. Keeping all of that in one first PR made the review surface too
large.

What should Phase 1 standardize now without claiming the Java runtime owns repository preparation?

[GitHub issue #545](https://github.com/martin-francois/symphony-trello/issues/545) later showed that
the generated workflow treated an absent source as an unconditional blocker. That prevented a card
from acting on a fully qualified GitHub issue URL even though the task needed neither repository
identity nor a checkout. It also exposed a second distinction: an absent source may be valid, but a
configured selected source that is broken must still block.

## Decision Drivers

* Let one-board-per-repository workflows avoid repeating the same repository URL on every Trello
  card.
* Preserve card-supplied repository overrides for multi-repository boards without requiring special
  labels when ordinary task context is already unambiguous.
* Avoid guessing from previous cards, local checkouts, branch names, or workspace residue.
* Let repository-independent work and fully qualified remote API targets run without a checkout.
* Keep configured broken sources visible as blockers instead of silently treating them as absent.
* Keep Java changes small enough to review and verify independently.
* Avoid implying that archived Java pre-run checkout work is an active follow-up while still recording
  why a partial manager was rejected.

## Considered Options

* Keep only card-authored repository sources and require every card to repeat the repository URL or
  path.
* Add workflow defaults and source precedence while keeping repository preparation workflow-owned.
* Ship the Java repository manager prototype discussed in archived
  [GitHub issue #33](https://github.com/martin-francois/symphony-trello/issues/33).
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
2. one unambiguous repository identity in ordinary Trello card task context;
3. workflow `repository.default_url`;
4. workflow `repository.default_path`;
5. no repository.

### Repository-Need Classification Correction

The source-precedence decision remains unchanged. The blocker and checkout consequences were later
narrowed by [GitHub issue #545](https://github.com/martin-francois/symphony-trello/issues/545).
Repository need is now classified before the absence of a selected source is treated as a blocker:

1. Repository-independent work without repository-relative references can run with no source.
2. A fully qualified GitHub issue or pull request URL is its own direct external action target and
   does not require a checkout when the task is only an API action.
3. Repository-relative references need repository identity. A full repository, issue, pull-request,
   or file URL, `owner/repository`, or equivalent ordinary card context supplies identity when it
   clearly identifies exactly one repository. A selected remote URL also supplies identity without
   requiring a checkout. A selected local path or `file://` source supplies identity only
   when read-only inspection finds exactly one explicit, unambiguous compatible remote. Otherwise,
   Codex blocks and requests a fully qualified repository URL together with the issue or pull request
   number instead of deriving identity from the local path, directory name, or branch.
4. Repository-changing work needs a usable identity and checkout. When ordinary card context supplies
   the identity, Codex derives its normal credential-free clone URL and prepares a writable per-card
   checkout.

Generated workflow files are persisted and are not automatically rewritten during an upgrade. The
runtime therefore appends a final repository-source context after the persisted workflow prompt and
marks it authoritative for source selection, task classification, and source blocker decisions. A
no-source runtime context explicitly supersedes conflicting older workflow text: Codex ignores an
earlier unconditional missing-source blocker and classifies the current task before deciding whether
the missing source blocks it. This fixes existing boards without requiring workflow regeneration or
migration.

Every explicit Trello card source that wins selection is validated with a read-only probe, even when
the task is otherwise repository-independent. A workflow default is only a fallback and is not
validated or prepared when ordinary card context clearly identifies another repository. Validation does not create a
checkout or write to the selected source. A malformed, unavailable, unreadable, or uncheckoutable
selected source blocks instead of being treated as absent. A valid selected source supplies context;
it does not turn repository-independent work into repository-changing work. Repository identity is
not inferred from unrelated checkouts, prior cards, or leftover workspace contents.

Established `repository.default_url` values keep compatibility normalization for outer token
wrapping and trailing prose punctuation. Runtime selection removes that punctuation before
validation. A default URL that is valid after normalization is a selected source and is not
"malformed" for the fail-closed rule. New setup input can use stricter validation without changing
how existing workflow files run.

Explicit Trello card sources use a labelled line such as `Repository URL: <url>`,
`Repository path: <path>`, `Local checkout: <path>`, or `Repository: <url-or-path>` in the title,
description, or a Trello comment. Ordinary unlabelled web links are not explicit source declarations,
but clear task context can still supply repository identity.
Each source declaration is read from one logical line. If multiple declarations are present, they
must all name the same source. URL labels and `repository.default_url` accept credential-free
HTTP(S), username-only `ssh://`, SCP-style SSH such as `git@example.com:team/project.git`, and
`file://` URLs. Path and checkout labels accept local checkout paths. Generic `Repository:` labels
accept either form. HTTP(S) source URLs must not include user info, query strings, or fragments. URI
paths may keep safe percent-encoding, but encoded or literal control characters are invalid.

`repository.default_url` and `repository.default_path` use the existing environment-reference
conventions. Missing or blank optional environment values resolve to absent. Relative
`repository.default_path` values resolve relative to `WORKFLOW.md`.

The 2026-07-10 onboarding amendment makes the common workflow URL default available during setup.
Guided `setup-local` asks one optional clone-URL question; a blank answer keeps the generated
workflow repository-general. Regular `setup-local`, `new-board`, and `import-board` also accept
`--repository-url`. They reuse the repository source parser's transport, credential, query,
fragment, malformed-input, and control-character rules without contacting the repository. Setup
validates the value before external setup effects and never echoes it. A later `configure-github`
regeneration preserves both raw repository defaults, including environment references.

A valid selected source suppresses lower-priority fallbacks. An invalid explicit Trello card source
does not silently fall back to workflow defaults.

When a workflow `repository.default_url` remains malformed after compatibility normalization,
exactly one unambiguous card repository identity overrides that unused fallback. A full issue or
pull-request URL remains direct and checkout-free for API-only work. A full repository, issue,
pull-request, or file URL, `owner/repository`, or equivalent single identity can override it for
repository-changing work. With no card identity, the malformed workflow URL blocks unconditionally,
including for repository-independent work; conflicting identities also block. A lower-priority
`repository.default_path` never establishes identity or replaces the invalid selected URL. It may be
considered only after one card identity overrides the URL and read-only Git remote inspection proves
the path matches that identity. An invalid lower-priority path does not affect a higher-priority valid
URL.

Generated workflow text tells Codex to prepare a writable checkout under the current per-card
workspace only when the classified task needs repository files. After repository selection, Codex
keeps a configured `repository.default_path` as a non-authoritative candidate for a selected remote,
including an explicit card remote, and uses it first only when its Git remotes match the already
selected identity. The path does not establish identity merely because it is configured, and an
explicitly selected local path does not receive a second candidate. Without such a matching configured
path, Codex searches accessible local checkouts by Git remote identity and reuses one
unambiguous match without cloning. It clones only when no match exists. From either the reused or new
repository, it fetches the remote default branch before creating a separate per-card worktree from
that fresh branch. An explicit card branch, ref, base, or checkout arrangement overrides this default.
Directory names, current branches, nearby paths, previous cards, and workspace residue do not satisfy
identity matching. Unreadable repositories, ambiguous matches, fetch failures, and worktree failures
block with path-safe guidance rather than causing a second clone or an arbitrary checkout edit.

The existing generated workflow direct-work exception remains explicit: Codex may edit a shared
checkout worktree only when the Trello card requests that arrangement, the checkout is writable, and
deployment filesystem policy permits it, including Git metadata writes when the task needs direct
commits. `--add-path <checkout>` grants extra filesystem access but is not a direct-checkout commit
guarantee. Machine-parsed base selection and Java enforcement remain deferred.

Java does not run Git, inspect repositories, clone, prepare checkouts, enforce repository identity,
perform first-class direct checkout, supervise Git processes, or persist repository blocker handoff
state. Archived [GitHub issue #33](https://github.com/martin-francois/symphony-trello/issues/33) and
[GitHub issue #465](https://github.com/martin-francois/symphony-trello/issues/465) do not own future
work. A Java-managed Git or direct-checkout architecture would require a new explicit product
decision and an independently scoped issue.

### Consequences

* Good, because workflow defaults and source precedence become explicit and testable.
* Good, because the prompt-contract change stays bounded without absorbing the archived Java-managed
  checkout prototype.
* Good, because no partial Java checkout manager is shipped.
* Good, because common one-repository workflows can configure the default during onboarding while a
  blank answer preserves the existing repository-general behavior.
* Good, because cards that need no repository context no longer block only because no source exists.
* Good, because repository-changing cards can identify their repository naturally without repeating
  a labelled clone source or requiring a workflow default.
* Good, because fully qualified GitHub issue and pull request URLs remain direct action targets.
* Good, because remote URL defaults can resolve repository-relative references without forcing a
  clone, while local sources fail closed when their remote identity is absent or ambiguous.
* Neutral, because generated workflow/agent behavior still performs repository preparation.
* Neutral, because managed checkout safety, direct-checkout transactions, durable preflight handoff,
  and provider-aware publication are not planned by this decision; adopting them later would require
  a new product decision and issue.
* Bad, because selected-source usability still depends on an agent-run read-only probe instead of a
  Java-owned preflight.

### Confirmation

Run focused tests for config resolution and generated workflow output:

```bash
./mvnw -q -Dtest=ConfigResolverTest,TrelloBoardSetupTest,LocalSetupTest,TrelloBoardSetupMainTest test
```

Also confirm the final PR does not add Java Git commands, a Java repository checkout preparer, direct
checkout support, a process runner, pre-Codex repository blockers, suppression maps, fork
publication, PR helper scripts, repo-sync scripts, runtime publication context, or repository-specific
skill installation.

The repository-source prompt and generated-workflow tests must cover every repository-need row from
[GitHub issue #545](https://github.com/martin-francois/symphony-trello/issues/545), including the
fully qualified issue-URL agent scenario. They also cover the complete checkout order: configured
path precedence, remote-based local discovery and reuse without cloning, clone-only-when-absent,
default-branch fetch before worktree creation, and explicit card ref overrides.

Live Trello/Codex verification remains an opt-in confirmation of this prompt-owned behavior. It is
not a closure gate for archived issue #33 or #465.

## Pros and Cons of the Options

### Keep Only Card-Authored Repository Sources

Require every Trello card to repeat the repository URL or local path.

* Good, because it preserves current behavior.
* Bad, because one-board-per-repository teams repeat noisy boilerplate on every card.
* Bad, because no precedence exists when a future default is added.

### Add Workflow Defaults and Source Precedence

Add optional workflow defaults and document how explicit card sources override them.

* Good, because it preserves the useful source-precedence result from the archived
  [GitHub issue #33](https://github.com/martin-francois/symphony-trello/issues/33) discussion.
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

[GitHub issue #33](https://github.com/martin-francois/symphony-trello/issues/33) and
[GitHub issue #465](https://github.com/martin-francois/symphony-trello/issues/465) are archived,
closed-as-not-planned context. They do not own future managed checkout or live-verification work.
Any Java-managed Git architecture requires a new explicit product decision and independently scoped
issue.

[GitHub issue #545](https://github.com/martin-francois/symphony-trello/issues/545) defines the
repository-need classification and direct external-target guardrail.
