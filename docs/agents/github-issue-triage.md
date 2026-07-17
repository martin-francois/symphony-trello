# GitHub issue triage

## Scope

How to run issue triage sweeps, manage issue dependencies, and apply triage labels. Spec-alignment
auditing that may create issues lives in
[Specification & ADR discipline](specification-and-adr-discipline.md).

## Issue dependencies

- When creating a GitHub issue or changing an issue's scope, check the other open issues for hard
  ordering dependencies. Represent every issue-to-issue ordering constraint with GitHub's native
  `blocked by`/`blocking` relationship through repository-approved GitHub tooling. In the GitHub web
  interface, use the issue sidebar's **Relationships** menu and choose **Mark as blocked by** or
  **Mark as blocking**. If the currently approved agent tool cannot mutate issue dependencies, stop
  and report that tooling limitation instead of substituting labels or manual dependency lines. The
  native relationship is reciprocal, so do not maintain a second manual relationship solely to make
  both sides discoverable. Explanatory dependency prose may remain when it records why the ordering
  exists, but it must agree with the native relationship. Do not add loose related links as
  dependencies; use this only when one issue really must land before another.
- GitHub issue dependencies currently connect issues, not pull requests. When work must wait for a
  pull request, prefer a native dependency on that pull request's owning issue and keep the pull
  request visible through GitHub's Development relationship or an explanatory link. If no owning
  issue exists, record the pull request prerequisite explicitly in the issue body and remove it when
  the pull request lands. While that unsupported pull-request dependency remains open, add
  `not ready` and withhold `help wanted` and `good first issue`; remove `not ready` when the pull
  request lands unless another not-ready reason remains. Do not use the `blocked` label merely as a
  substitute for an unsupported pull-request dependency.
- Reserve the `blocked` label for an unresolved external prerequisite or waiting condition that
  cannot be represented by a GitHub issue dependency, such as third-party approval, an upstream
  release, or a time-based availability constraint. Do not add it to an issue merely because its
  native `blocked by` relationship contains an open issue. Remove it as soon as the external
  condition is satisfied.

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
  are closed, add missing useful links, fix incorrect links, update labels and milestones, add or
  remove native issue dependencies, and reserve `blocked` for unresolved external prerequisites or
  waiting conditions. For labels, apply the full Triage labels policy on every sweep, including type
  labels, exactly one priority label, exactly one size label, `breaking change`, `blocked` for
  external waits, `needs human review`, `not ready`, `idea`, assigned-issue `help wanted` removal,
  unassigned-ready `help wanted`, and `good first issue` when the 80% one-shot bar is met. A hard
  issue-to-issue prerequisite must have the matching native relationship. Non-ordering links such as
  related work, coordination, and follow-ups remain ordinary references and must not be converted
  into dependencies; keep a matching backlink on the other open issue so both sides remain
  discoverable. Historical provenance for closed work does not require a backlink.
  Prefer editing issue descriptions over adding comments unless a historical note or external
  artifact link must be preserved. For issues created by the authenticated maintainer account, update
  the body as if the issue had been written that way from the start, weaving new context into the
  relevant section instead of appending it to the end or posting a redundant comment; GitHub already
  keeps the edit history. Run the audit in cycles: after making changes, fetch the open issue set
  again and repeat the body/label/milestone/link/dependency review until one full pass finds nothing
  else to change. Summarize which issues were changed, how many cycles ran, and which issues were
  intentionally left unchanged.
- During a sweep, treat contributor pickup comments as actionable only on open, unassigned issues
  with `help wanted`. A pickup comment is a comment by a GitHub user asking to be assigned, asking
  whether they can work on the issue, or saying they want to start or have started work on the issue.
  If multiple users ask, use the earliest qualifying comment. Attempt to assign that user before
  commenting. Only after assignment succeeds, remove `help wanted` per the assigned-issue label rule
  and add a short comment thanking them, saying they are assigned, and saying they can start work. If
  assignment fails because the authenticated account lacks permission or the repository rejects the
  assignee, do not post the assignment comment; include the blocker in the sweep summary instead.
- At the end of every sweep, report open issues that were assigned at least 14 days ago and still
  have no open pull request. Check linked pull requests and open repository pull requests that
  reference the issue URL or number, including closing-keyword references. For each stale assigned
  issue, include the issue title, issue URL, and current assignee logins. Exclude issues assigned
  only to the user who requested the sweep; if that user cannot be identified from the prompt or
  repository context, use the authenticated GitHub account as the best available approximation and
  state that assumption in the summary. Determine assignment age from the assignment timeline when
  available; if the timeline is unavailable, report only issues whose current assignment is otherwise
  known to be at least 14 days old.

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

- When creating or editing an issue, apply the full useful label set before finishing rather than
  adding only the minimum template label. Explicitly evaluate type labels, priority, `blocked`,
  `breaking change`, `not ready`, `help wanted`, and `good first issue` as applicable. Every open
  issue should have exactly one priority label: `priority high`, `priority medium`, or
  `priority low`. Every issue MUST have exactly one size label as defined below. Do not defer
  `good first issue` to a later sweep: apply it immediately when the issue satisfies the 80%
  one-shot implementation bar.

### Size labels

- Every open or closed issue MUST have exactly one of `size XS`, `size S`, `size M`, `size L`, or
  `size XL`. The label estimates the complete repository change needed to satisfy the issue's current
  scope, including implementation, tests, documentation, configuration, and tracked generated
  output:

  | Label | Expected changed lines |
  | --- | ---: |
  | `size XS` | 0-49 |
  | `size S` | 50-199 |
  | `size M` | 200-499 |
  | `size L` | 500-999 |
  | `size XL` | 1,000 or more |

- Estimate changed lines as additions plus deletions across the entire issue, including all expected
  pull requests when an issue deliberately requires more than one. Choose the smallest label whose
  upper bound contains the reasonable estimate. When the reasonable range crosses a label boundary,
  use the larger label. Re-evaluate the size whenever the accepted scope changes.
- Size labels measure expected review volume. They MUST NOT substitute for priority, implementation
  effort, cognitive complexity, risk, readiness, or `good first issue` evaluation. Keep those
  classifications independent.
- Pull requests use the same labels, but `.github/workflows/size-labeler.yml` measures their current
  GitHub additions plus deletions instead of copying the linked issue's estimate. Maintainers SHOULD
  let that workflow own pull-request size labels; a manual override is appropriate only while the
  workflow is unavailable, because the next pull-request update recalculates the label.
  Metadata-only `pull_request_target` runs handle normal updates, conflicts, skip directives, and
  forks without executing pull-request code. Trusted Commitlint completions cover ordinary pull
  requests whose paths match the Release Please exclusions. A scheduled default-branch
  reconciliation repairs suppressed events, base-branch diff changes, and pull requests that were
  already open when the workflow was introduced.

- During issue triage, check every open issue for breaking-change risk, especially while the
  affected contract is still small enough to change deliberately. Add the `breaking change` label
  when the accepted implementation may intentionally change or invalidate current user-facing
  contracts:
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
- During issue triage, ensure every issue records a compatibility decision before it is considered
  ready for implementation. The recorded decision must say whether the issue chooses a breaking
  change, a compatible change with temporary migration or legacy support, a permanent compatibility
  contract, or no user-facing contract change. For documentation, tests, or internal-only work,
  record no user-facing contract change instead of leaving the decision implicit. For breaking
  changes, include the affected contract, why the break is necessary, and the migration path. Also
  note that the implementing squash commit body or retained commit must use a Conventional Commit
  `BREAKING CHANGE:` footer so release automation puts the reason and migration guidance in the
  generated changelog. For
  temporary compatibility or migration logic, include the removal condition and where cleanup is
  tracked, such as a follow-up issue. If the issue template did not capture this clearly, edit issues
  opened by the authenticated account, or comment on issues opened by another account, so the
  decision is explicit before work starts.
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
  condition; use the native issue dependency for the former and `blocked` for the latter. Do not add
  `needs human review` only because the implementation step itself is a maintainer or owner/admin
  action, such as transferring a repository, when that action is already the clear issue scope.
  Whenever adding `needs human review`,
  also add `not ready` and immediately add an issue comment that states exactly what human decision,
  external action, or missing context is needed so the labels can be removed in a later sweep.
  Before passing `needs human review` to `gh issue create`, re-read the issue body: if it already
  tells an implementer to investigate the external system, choose a supported path, or document that
  no supported path exists, the issue is actionable and must not get the label.
- During issue triage, keep labels aligned with implementability. Use `not ready` when the issue is an
  idea/research note, lacks enough accepted scope to implement, or needs a prior non-dependency
  decision/action before work can start. Use `idea` only for speculative product or design options
  that are not ready to implement; every `idea` issue must also have `not ready`, though `not ready`
  may be used without `idea` for non-speculative blocked scope. Do not add `idea` to already-scoped
  work that an implementer can start from the issue description, even when the feature was originally
  discussed as a possibility. Idea issue descriptions should ask interested users to upvote the issue
  so maintainers can judge demand before accepting or prioritizing the work. An issue with
  `not ready`, a native dependency on an open issue, or `blocked` must not have `help wanted` or
  `good first issue`, because those labels tell contributors the issue is ready to pick up.
  Dependency dashboard issues must not have
  `help wanted` or `good first issue`, because they track automated dependency update state rather
  than contributor-implementable work. Every open, unassigned issue that is not a dependency
  dashboard issue, has no unresolved native issue dependency, and has neither `not ready` nor
  `blocked` should have at least `help wanted`, so contributor-pickup status is explicit. When any
  user is assigned to an issue, remove `help wanted` because the issue is claimed; if all assignees
  are later removed, re-evaluate whether `help wanted` should return. Add `good first issue` when the
  issue meets the 80% one-shot bar: a coding agent
  could be given only
  "implement this issue <url>"
  and, in one shot without an elaborate extra prompt, submit a PR that would need no maintainer PR
  comments and receive LGTM in roughly 80% of attempts. The same bar applies to a developer who is
  new to the repository. That means the issue must be small, well-scoped, low-risk, independent of
  unresolved decisions or external timing, and specific enough that the expected implementation is
  clear. Use `already implemented` when the issue appears to describe behavior that already exists.
  Close it with a reference comment when the implementation is verified against code or tests (triage
  sweeps do this check on every open issue); when verification is inconclusive, keep it open with
  `needs human review` and `not ready` per the GitHub Issue Triage section.
- Use `not ready` when the issue scope, design, or acceptance criteria are not finalized enough to
  implement, when `needs human review` applies, or when an open pull-request prerequisite cannot be
  represented as a native issue dependency because it has no owning issue. Do not use `not ready`
  for an otherwise actionable issue that only has a native dependency on another issue, is waiting
  for a clear timing condition, or is waiting for an owner/admin action. Do not add
  `needs human review` only because an issue has `not ready`; `needs human review` is only for the
  narrower cases above.
- Apply `good first issue` only when a newcomer or one-shot coding agent could implement the issue
  from the issue URL alone and the resulting pull request would likely need no maintainer comments.

## References

- [GitHub issue dependencies](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/creating-issue-dependencies)
- [Specification & ADR discipline](specification-and-adr-discipline.md)
- [Default workflow](default-workflow.md)
