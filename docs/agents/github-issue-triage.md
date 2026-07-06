# GitHub issue triage

## Scope

How to run issue triage sweeps, manage issue dependencies, and apply triage labels. Spec-alignment
auditing that may create issues lives in
[Specification & ADR discipline](specification-and-adr-discipline.md).

## Issue dependencies

- When creating a GitHub issue or changing an issue's scope, check the other open issues for hard
  ordering dependencies. Add dependencies bidirectionally using the exact headings
  `Must be implemented before:` and `Must be implemented after:` so both sides stay discoverable. Do
  not add loose related links as dependencies; use this only when one issue really must land before
  another.
- When an open issue has one or more unresolved `Must be implemented after:` dependencies, make sure
  it has the `blocked` label. When all issues listed under `Must be implemented after:` are closed,
  remove the `blocked` label so the issue queue reflects that it can be started.

## Triage sweeps

- Keep open issues aligned with the accepted product direction. If a product decision rejects a
  prototype, architecture path, or implementation layer, close or explicitly de-scope issues that
  would invite contributors to build that rejected direction. Use `not planned` for closed GitHub
  issues unless the issue was actually completed, and leave a short comment that names the current
  accepted boundary. Do not keep speculative or superseded prototype issues open merely as archived
  ideas; use closed issue comments, ADRs, or external planning notes for historical context.
- When the user asks for an issue triage audit, triage sweep, issue audit, or similar wording, audit
  all open issues, not only the examples named by the user. Read each open issue's title, body,
  labels, milestone, and relevant comments. Update issue bodies directly so they read as current and
  intentionally scoped, not as historical chat notes. Remove stale dependency wording once blockers
  are closed, add missing useful links, fix incorrect links, update labels and milestones, maintain
  bidirectional dependency lines, and add or remove `blocked` based on unresolved
  `Must be implemented after:` dependencies. Issue-to-issue relationship links must be bidirectional:
  if an open issue says it is related to, coordinates with, follows up from, or is a prerequisite for
  another open issue, the other issue should link back with the matching relationship unless the
  reference is only historical provenance for closed work. Prefer editing issue descriptions over
  adding comments unless a historical note or external artifact link must be preserved. Run the audit
  in cycles: after making changes, fetch the open issue set again and repeat the
  body/label/milestone/link/dependency review until one full pass finds nothing else to change.
  Summarize which issues were changed, how many cycles ran, and which issues were intentionally left
  unchanged.

## Already-implemented check

- Every triage sweep also checks each open issue against the current code: does the issue describe
  behavior that already exists? When the implementation is verified against the code, tests, or a
  reproduction, close the issue with a comment that references where it was addressed - the
  implementing commit, pull request, issue, or code location - and add `already implemented`. When
  the behavior only appears to exist but verification is not conclusive, add `already implemented`,
  `needs human review`, and `not ready` instead of closing, and comment what evidence exists and what
  a maintainer must confirm. Never close on resemblance alone: the issue's actual acceptance criteria
  must be met, not just its premise or a partial overlap.

## Triage labels

- During issue triage, check every open issue for breaking-change risk, especially before the first
  public release when incompatible changes are cheaper to make. Add the `breaking change` label when
  the accepted implementation may intentionally change or invalidate current user-facing contracts:
  CLI arguments, exit codes, config keys or values, environment precedence, workflow schema,
  generated workflow behavior, Trello interpretation rules, installer version syntax, release
  artifact contracts, or other behavior that scripts or operators could reasonably rely on. Do not
  remove or omit the label only because the issue plans an automatic migration, upgrade prompt,
  compatibility path, or other mitigation; the label tracks the contract change itself. Do not add
  the label to purely internal refactors, behavior-preserving library evaluations, documentation
  cleanup, or additive opt-in features whose default behavior stays unchanged. When a not-ready issue
  contains both breaking and non-breaking options, keep the label if the issue is explicitly asking
  the maintainer to choose whether to break the current contract. Whenever adding the label, record
  why the issue is breaking, whether migration or compatibility logic would make sense, and whether
  such compatibility is possible or intentionally not recommended. If the issue was opened by the
  current authenticated GitHub account, edit the issue body itself so future readers see the
  rationale without scanning comments. If another account opened the issue, add that explanation as a
  comment instead of rewriting the reporter's issue body. Determine the current account from the
  active GitHub tool or CLI authentication; do not hardcode a personal username in this rule or in
  triage automation.
- During issue triage, add `needs human review` when an issue cannot be implemented as written until
  a maintainer decision, owner-only repository action, external account/form submission, secret
  provisioning, external prerequisite, or explicit human review happens. Issues with
  `needs human review` are not ready for an implementer as written, so they must also have the
  `not ready` label. Remove `needs human review` once the decision/action/prerequisite is captured in
  the issue and an implementer can proceed without asking the maintainer first; remove `not ready` at
  the same time unless another not-ready reason remains. Do not use this label for ordinary technical
  decisions that the issue already asks the implementer to evaluate and document.
- Use `needs human review` only when the issue is missing a human decision, required context, or an
  external action that is not already represented by a dependency or clear issue step. Do not use it
  for issues that are merely blocked by another issue or waiting for a clearly described timing
  condition; use `blocked` and dependency notes for those. Do not add `needs human review` only
  because the implementation step itself is a maintainer or owner/admin action, such as transferring a
  repository, when that action is already the clear issue scope. Whenever adding `needs human review`,
  also add `not ready` and immediately add an issue comment that states exactly what human decision,
  external action, or missing context is needed so the labels can be removed in a later sweep.
  Before passing `needs human review` to `gh issue create`, re-read the issue body: if it already
  tells an implementer to investigate the external system, choose a supported path, or document that
  no supported path exists, the issue is actionable and must not get the label.
- During issue triage, keep labels aligned with implementability. Use `not ready` when the issue is an
  idea/research note, lacks enough accepted scope to implement, or needs a prior non-dependency
  decision/action before work can start. Use `idea` only for speculative product or design options
  that are not ready to implement; every `idea` issue must also have `not ready`. Do not add `idea` to
  already-scoped work that an implementer can start from the issue description, even when the feature
  was originally discussed as a possibility. Idea issue descriptions should ask interested users to
  upvote the issue so maintainers can judge demand before accepting or prioritizing the work. Use
  `help wanted` for open implementable issues except dependency dashboard issues and issues marked
  `not ready`. Use `good first issue` only when a coding agent could be given only "implement this
  issue <url>" and, in one shot without an elaborate extra prompt, submit a PR that would need no
  maintainer PR comments and receive LGTM in roughly 80% of attempts. The same bar applies to a
  developer who is new to the repository. That means the issue must be small, well-scoped, low-risk,
  independent of unresolved decisions or external timing, and specific enough that the expected
  implementation is clear. Use `already implemented` when the issue appears to describe behavior that
  already exists. Close it with a reference comment when the implementation is verified against code
  or tests (triage sweeps do this check on every open issue); when verification is inconclusive, keep
  it open with `needs human review` and `not ready` per the GitHub Issue Triage section.
- Use `not ready` when the issue scope, design, or acceptance criteria are not finalized enough to
  implement, or when `needs human review` applies. Do not use `not ready` for an otherwise actionable
  issue that is only blocked by another issue, waiting for a clear timing condition, or waiting for an
  owner/admin action. Do not add `needs human review` only because an issue has `not ready`;
  `needs human review` is only for the narrower cases above.
- Apply `good first issue` only when a newcomer or one-shot coding agent could implement the issue
  from the issue URL alone and the resulting pull request would likely need no maintainer comments.

## References

- [Specification & ADR discipline](specification-and-adr-discipline.md)
- [Default workflow](default-workflow.md)
