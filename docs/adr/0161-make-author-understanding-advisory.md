---
status: accepted
date: 2026-11-02
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #1259](https://github.com/martin-francois/symphony-trello/issues/1259)"
  - "[ADR 0144](0144-require-the-understanding-box-on-every-pull-request.md)"
informed: [Contributors, Reviewers, Future maintainers]
---

# Make author understanding advisory

## Context and Problem Statement

The mandatory understanding tick prevents maintainers from reviewing and finishing useful outside
contributions. On 2026-11-02 the maintainer authorized agents to assess those contributions, fix
them, and merge ready changes even when the author leaves the tick empty. The declaration must
still describe the author's own understanding, not the maintainer's review.

## Decision Drivers

- Useful contributions need a path through maintainer review and verification.
- An agent must not make another contributor's personal declaration.
- Automated metadata checks and agent instructions must enforce the same rule.
- Existing safety, project-value, compatibility, and validation requirements remain in force.

## Considered Options

- Keep the author's understanding declaration advisory, with maintainer review deciding readiness.
- Keep the author tick mandatory before review and merge.
- Add a second maintainer declaration that unlocks an unchecked author declaration.

## Decision Outcome

Chosen option: "Keep the author's understanding declaration advisory, with maintainer review deciding
readiness". This supersedes ADR 0144's mandatory PR tick and its prohibition on processing unticked
outside PRs. Its issue-form confirmation requirements remain unchanged.

The Review Checklist and its exact understanding line remain required template structure. A checked
or unchecked declaration passes this part of metadata validation. Exactly one AI assistance choice
remains mandatory. The maintainer and agents leave an outside author's declaration unchanged.
Owner declarations continue to follow the owner's standing authorization.

Maintainer agents assess whether the change benefits the project, inspect changed files before
executing contributor code, repair defects, review the final head, and run the required checks.
The existing confidence threshold and merge authorization rules still decide readiness. An empty
author tick alone does not prevent review, ready status, or merge.

Disclosure automation no longer flags an empty tick. Its existing reconciliation clears flags
whose only problem was that tick and reopens PRs it closed for that reason, subject to the existing
close-event and branch checks. A human closure is never reversed. Missing template structure and
invalid AI disclosure still follow the existing correction and closure workflow.

### Consequences

- Good, because useful fixes proceed under accountable maintainer review.
- Good, because no one fabricates an author's statement.
- Bad, because an accepted contribution need not have an author-confirmed understanding tick.
  Maintainers must assess the code and evidence themselves.
- Neutral, because this changes contributor workflow, not the product, deployment, or issue forms.

### Confirmation

Run `pnpm run verify:scripts` and Markdown lint. Metadata tests must accept an empty understanding
box and continue rejecting missing structure or invalid AI choices. Disclosure tests must clear
old tick-only flags, reopen automation-closed PRs, preserve human closures, and retain handling of
other incomplete descriptions. Review confirms the agent and contributor docs match these checks.

## Pros and Cons of the Options

### Keep the author's understanding declaration advisory, with maintainer review deciding readiness

The author retains a truthful declaration while the existing review and test requirements decide
whether the contribution is ready.

- Good, because this follows the maintainer's instruction without another approval mechanism.
- Bad, because the declaration no longer guarantees an author-confirmed review.

### Keep the author tick mandatory before review and merge

The current automation and agent instructions continue blocking an unticked PR.

- Good, because every merged non-exempt PR carries the tick.
- Bad, because this prevents the authorized maintainer work and does not prove code quality.

### Add a second maintainer declaration that unlocks an unchecked author declaration

Another field or approval record would bypass the author gate.

- Good, because automation could distinguish an explicit maintainer exception.
- Bad, because it duplicates existing review responsibility and adds a new gate to maintain.
