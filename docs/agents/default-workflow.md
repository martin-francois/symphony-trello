# Default workflow

## Scope

The order of work for any task: what to read first, how to scope a change, how to verify, and how to
commit and open pull requests. Topic-specific rules live in the pages linked under References.

## Engineering identity

- Work as a senior Quarkus backend engineer implementing a Java 25 LTS and Maven 3 service from
  `SPEC.md`. Treat the adapted Symphony-for-Trello specification as the product contract, and prefer
  specification-driven changes over ad hoc behavior.
- Quality means the change is correct, maintainable, tested at the right level, documented where it
  matters, and easy for another engineer to understand without asking the original implementer.

## Before changing code

1. Start by reading the relevant specification, existing implementation, tests, README,
   `CONTRIBUTING.md`, and ADRs before changing code.
2. If this checkout contains `tessl.json` and the `tessl` command is available, run `tessl install`
   from the repository root before contribution work so generated agent links and rules are fresh.
   Treat `.tessl/` and Tessl-generated `tessl__*` skill links as local generated output;
   `tessl.json` is the tracked source of truth. Do not stage Tessl install churn, and add generated
   tool output that should never be tracked to `.gitignore` instead of repeatedly cleaning it by
   hand. If `tessl` is not available, say so briefly and continue by following `AGENTS.md`,
   `CONTRIBUTING.md`, `AI_CONTRIBUTION_POLICY.md`, and the issue/PR templates directly.

## Making the change

- Prefer TDD when the behavior can be isolated. If TDD is impractical, make sure the final tests
  would have failed for the bug or missing behavior.
- When starting implementation work for a specific GitHub issue, assign that issue to the
  authenticated account if permissions allow. Do this when work on that individual issue actually
  starts, not when merely planning or listing several future issues. If assignment fails because of
  permissions or repository settings, continue without blocking the implementation.
- Keep changes narrowly scoped. Each branch or pull request should cover one cohesive change. If a
  feature or bug fix needs directly related cleanup or refactoring to make that change correct or
  maintainable, keep the cleanup/refactoring focused on that change. Put unrelated cleanup,
  refactoring, formatting, dependency updates, or tooling changes in a separate issue, branch, or
  pull request.
- When the user asks for work that is unrelated to the current branch or pull request and does not
  say where it should go, ask before changing files. Offer lettered choices: commit directly on
  `main`, create a new branch and pull request, or put it on the current branch or pull request as a
  separate commit. Because this work is already unrelated to the current branch or changes, never
  suggest merging it into an existing commit unless the user explicitly asks for history cleanup after
  seeing that tradeoff. Do not leave unrelated committed work unpublished on a local branch; push and
  create or update the selected pull request, or push `main` only when the user chose that path.
- Update documentation and ADRs when behavior, setup, architecture, or tradeoffs change.
- Before finishing a task where you made or explained a deliberate design tradeoff, run an explicit
  ADR check: if the choice would be costly for a future maintainer to rediscover, add or update the
  ADR in the same change without waiting for the user to ask. Treat a user having to ask for an ADR
  after the fact as evidence that the agent-doc trigger was too weak; strengthen or move the
  relevant guidance before finishing.
- Before finishing code that is not self-explanatory, make the meaning or rationale explicit. Prefer
  a named variable, constant, method, type, or helper when the name can carry the explanation. Use a
  short comment or Javadoc when naming cannot express a local constraint clearly. If the reason is a
  durable design tradeoff rather than a local implementation detail, add or update the ADR as well.
- When fixing a documentation pattern, search the relevant file or docs set for similar instances
  before committing instead of correcting only the one sentence the user pointed out.
- When changing tests, read [Testing](testing.md) during the relevant-docs pass and apply its
  duplication rules before committing. If the same setup, command construction, or assertion is
  edited in multiple tests during one change, treat that as a refactoring signal, not as acceptable
  local repetition.
- When the user or a review comment identifies a concrete mistake or maintainability pattern, check
  for similar occurrences before finishing. Fix matching cases that are in the current branch scope
  or directly touched by the change. For matching cases outside scope, create or suggest a focused
  follow-up issue instead of silently leaving them for rediscovery. In the final response, state the
  related files or pattern set you checked, what you changed, and whether a follow-up issue was
  created or intentionally not created. This is required even when the direct fix is small.
- When you notice a potential improvement that is outside the current task scope, keep the current
  work focused and create or suggest a GitHub issue instead of adding "future improvement",
  "convenience gap", or similar sections to user-facing documentation.
- When a user raises a concrete task, follow-up, or correction that remains actionable but the
  conversation is redirected before it is finished, record it immediately in a durable place instead
  of relying on chat memory. Prefer a GitHub issue for product work, a checked-in checklist file when
  the user explicitly asks for one, or a short workpad/PR note for active branch work. Do this before
  switching to the new task, and mention where it was recorded.
- When you investigate a real improvement but decide not to implement it because no option satisfies
  the current constraints, create or suggest a GitHub issue before finishing. The issue must state
  the desired outcome, the options checked, why each option is not good enough right now, what a
  future acceptable solution would need to provide, and any ADR or PR that records the current
  decision. Use the `idea` label only together with `not ready` when the work is exploratory rather
  than ready to implement.
- Apply [compatibility discipline](#compatibility-discipline): default to the current documented
  contract, make breaking-vs-compatible choices explicit in issues and pull requests, and add
  compatibility behavior only when the issue, specification, or ADR deliberately chooses it.
- When the user states a generally useful working preference or corrects a repeatable pattern,
  proactively persist it in the same change by updating the most relevant agent-docs page; do not
  wait for the user to explicitly ask for durable guidance. If the right persistence scope is
  unclear, ask before finishing. See
  [Maintaining agent docs](maintaining-agent-docs.md) for how to decide where it goes and how to
  check for conflicts; keep detailed guidance in docs/agents, and add only a concise root
  `AGENTS.md` summary when the preference changes broad agent operation. If the mistake happened
  even though a rule already existed, do not conclude that no durable change is needed. Strengthen the
  wording, move the rule closer to the always-read workflow, or add a cross-link so the rule is more
  likely to be applied next time.
- When an eval issue captures a bad or missing behavior in an installed skill, add or update a
  temporary repo-local override in the relevant agent-docs page. Before applying an installed skill
  in an area covered by temporary overrides, use those overrides as a checklist: check each linked
  issue's GitHub state at most once for the turn, compare the planned change with every still-open
  matching override, and follow the override when it is stricter than the skill's normal advice. If
  all linked issues for an override are closed, remove that override in the same change instead of
  following it. Do not query the same issue on every individual code use inside one turn.
  Discovering after the fact that an override was missed is itself a durable-instructions failure;
  strengthen or move the relevant override guidance before finishing the task.

## Compatibility Discipline

- Treat the current documented contract as the default implementation target. When a change alters a
  user-facing contract, prefer a documented breaking change unless the user, issue, specification, or
  ADR explicitly chooses compatibility.
- Every issue and pull request must record the compatibility decision: breaking change, compatible
  change with temporary migration or legacy support, permanent compatibility contract, or no
  user-facing contract change. For documentation, tests, or internal-only work, record that there is
  no user-facing contract change instead of leaving the decision implicit. If the answer is not
  clear, mark the issue or PR as needing a maintainer decision before implementation proceeds.
- Judge compatibility against previously supported behavior that succeeds end to end, not merely an
  input that one intermediate layer accepted. Rejecting an input earlier when the authoritative
  downstream system already rejects it is a compatible fail-fast improvement unless the documented
  contract promises acceptance or pass-through. Record the downstream evidence in the pull request.
- For a breaking change, write down what breaks and the migration path operators, contributors, or
  users should follow. Do not add hidden compatibility fallbacks merely to soften the break.
- Breaking changes must use both Conventional Commit markers in the message that reaches `main`: `!`
  before the colon in the type or scope, and a `BREAKING CHANGE:` footer. The `!` makes the break
  visible in commit-title-only views, and the footer lets release automation include the reason and
  migration path in the generated changelog. The pull request template records the intended result
  in plain language. **Combine this pull request into one final commit** may defer the footer to the
  final combined message. **Keep the individual commits** requires both markers in the retained
  commit that owns the breaking behavior. Any branch commit containing either marker must contain
  both, regardless of the selected result. Commitlint rejects incomplete marker-bearing commits.
- As soon as a pull request is classified as breaking, make its title and merge-message source
  breaking-ready; never mark the breaking-footer check as N/A. Before making a pull request ready,
  verify that the compatibility selection, title, and retained commit messages agree. The PR metadata
  validator checks the authoritative template's `Because`, `Breaks`, `Migration`, and `Alternative`
  fields plus the PR title's `!`; it does not require a PR-body `BREAKING CHANGE:` field that the
  template does not provide. The same command validates the explicit Commit History in Main choice
  and cross-checks the PR commit range. Combine mode does not require a current branch commit with
  breaking markers. Keep mode does. In both modes, either marker in a branch commit requires both
  markers in that commit, a Breaking template selection, and `!` in the PR title. Commitlint
  independently enforces both markers on each marker-bearing commit. Final merge review must ensure
  the selected result matches the actual merge method and the message reaching `main` has both
  breaking markers.
- Do not add or retain product migrations, legacy-shape support, backward-compatibility shims,
  old-template fingerprints, historical-state fallbacks, or automatic upgrade code unless an
  explicit issue, specification change, or ADR defines the supported public contract.
- When temporary migration or compatibility behavior is accepted, define how long it stays or the
  exact condition that removes it. Track that cleanup in the issue, PR, ADR, or a dedicated follow-up
  issue before merging the compatibility logic.
- Keep accepted temporary compatibility logic narrow, named, and easy to find and delete later.
  Cover it with tests that explain the old shape it preserves and, where practical, keep those tests
  grouped so the cleanup can remove the code and tests together.
- Permanent compatibility contracts, such as deliberately supported aliases, must be stated in
  `SPEC.md` or an ADR rather than being left as incidental migration code.
- Do not add compatibility tests whose only purpose is to prove behavior for private historical
  shapes. Test current behavior, current validation, and current failure modes instead.
- Do not add generated-output version markers solely to support uncommitted or unsupported generated
  files.
- Do not commit private paths, credentials, Trello board links, account names, deployment details, or
  private backup contents when doing local recovery or one-off maintenance.

## Upstream Symphony Rebase

When the user asks to "rebase main to the Symphony repo" or equivalent, treat it as a history
operation whose default outcome is no tree change. The purpose is to make `main` descend from the
latest `openai/symphony` `main` while preserving the current Symphony for Trello content unless the
user explicitly chooses a spec carry-over.

1. Confirm the working tree is clean and fetch `origin` and `upstream`.
2. Inspect upstream `SPEC.md` changes first:
   - Identify the merge base between local `main` and `upstream/main`.
   - Review `git log <base>..upstream/main -- SPEC.md`.
   - Review the net `git diff <base>..upstream/main -- SPEC.md`.
   - Compare every surviving upstream spec change with this repo's Trello-adapted `SPEC.md` and
     implementation.
3. If upstream contains a `SPEC.md` change that is not already present in an equivalent Trello form,
   stop before rebasing. Summarize the upstream change, the likely Trello equivalent, and ask the
   user whether to carry it over. Do not create the backup branch or rewrite history until the user
   decides.
4. If there is nothing to carry over, or after the user decides exactly which spec changes to carry
   over, record the current `HEAD` and tree hash, create a timestamped local and remote backup branch
   such as `backup/main-before-upstream-rebase-YYYYMMDDHHMMSS`, and only then rebase `main` onto
   `upstream/main`.
5. Resolve conflicts so the final tree contains only the user-approved changes. If the user approved
   no spec carry-over, the final tree must match the pre-rebase tree exactly. Upstream-only files
   that this repo intentionally removed, such as the original Elixir reference project, must remain
   removed.
6. Before force-pushing, verify:
   - `git diff <backup>..HEAD` is empty when no carry-over was approved, or contains only the
     user-approved carry-over.
   - `git rev-parse <backup>^{tree}` matches `git rev-parse HEAD^{tree}` when no carry-over was
     approved.
   - `git merge-base --is-ancestor upstream/main HEAD` succeeds.
   - `git diff --check <backup>..HEAD` passes.
7. Force-push `main` only with `--force-with-lease` against the pre-rebase `origin/main` SHA.
8. After pushing, fetch `origin/main` and verify local `HEAD`, `origin/main`, the backup branch, the
   tree comparison, and the clean working tree. Report the backup branch, old and new head SHAs,
   upstream SHA, and the exact tree/diff verification.

## Fixing bugs

Treat reproduction, a failing regression test, and post-fix reproduction as hard gates rather than
optional verification. Follow this order:

1. Investigate the report enough to define the exact symptom and a safe reproduction procedure.
2. Before changing product code, deploy the affected current state locally and reproduce the bug.
   Prefer an isolated Docker or Podman container over the host system whenever the container can
   reproduce the scenario faithfully. Use real Trello and real Codex when they are relevant and
   reasonably available, and use only disposable, run-scoped Trello boards and repositories. Follow
   [Deployment & live verification](deployment-and-live-verification.md#live-bug-fix-reproduction-loop).
3. If the bug cannot be reproduced, stop without implementing a speculative fix. Explain what was
   attempted, why live reproduction was unavailable when applicable, or what behaved as expected,
   then ask the requester for the missing reproduction details.
4. Identify the exact mechanism that produces the reproduced failure - the specific code path, lock,
   state, timing, or interaction - and why it exists before designing the fix. A plausible category
   such as "flaky", "transient", "timing", "GC", "race", or "host load" is a symptom description,
   not a root cause; for behavior in code this project owns, keep digging until the failing mechanism
   is identified, and accept an environmental explanation only after owned code has been ruled out.
5. Turn the reproduction into the smallest regression test, run it against the buggy code, and
   confirm that it fails for the expected bug-specific reason rather than an incidental failure. Do
   not change product code until this red test is established. Follow
   [Testing](testing.md#regression-tests).
6. Fix the identified mechanism and run the focused regression test until it passes. A retry, longer
   timeout, fallback, or other tolerance mechanism is acceptable only as a documented compatibility
   or defense-in-depth layer on top of the mechanism fix, never as a replacement for finding it.
7. Deploy the fixed state and repeat the same live reproduction procedure and success criteria. Use
   fresh disposable resources when reuse could hide or contaminate the result. Claim the bug is fixed
   only when the original symptom is no longer reproducible; otherwise continue the loop.

- Never present a guess as a finding. Before stating a cause, behavior, or fact about this system,
  verify it against the code, the issue evidence, a reproduction, or documentation, and base
  decisions on what the verification showed. When something cannot be verified with reasonable
  effort, label it explicitly as unverified and say what evidence would confirm it. This applies
  doubly to explanations given to the user: a mechanism asserted from pattern matching that later
  turns out wrong costs more than saying "not verified yet".

## Verifying

- Run the relevant verification before finishing. For normal code changes, use:

  ```bash
  ./mvnw -q spotless:check verify
  ```

  Use `spotless:apply` before that when formatting changed.
  PowerShell installer tests use native `pwsh` automatically on Windows. On Linux, set
  `SYMPHONY_TRELLO_TEST_PWSH=./scripts/pwsh-docker.sh` for Java tests that support a configurable
  PowerShell command, or run `./scripts/pwsh-docker.sh` directly for script checks. Do not report
  PowerShell as skipped only because `pwsh` is missing if Docker is available.
- When validating repository-local Codex skills with the external skill-creator quick validator, use
  `scripts/validate-codex-skill <skill-dir>` instead of calling `quick_validate.py` directly. The
  wrapper creates a cached isolated Python environment with pinned PyYAML so validation does not
  depend on global Python packages.
- Keep the full required pull request CI pipeline fast: the target is about 5 minutes end to end, not
  5 minutes per job or step. If a useful check would make total CI slower, first consider parallel
  jobs, narrower focused commands, splitting the gate, or keeping it as a documented manual/periodic
  command. Do not put continuous fuzzing or long soak tests in required CI; CI fuzzing should stay a
  deterministic regression gate with a short timeout.

## Committing and pull requests

- Commit with Conventional Commits for this repository when asked to commit, and keep the working
  tree clean before claiming the work is done. When an agent creates commits inside another target
  repository for a Trello card, follow that repository's documented commit convention first. If it
  has no documented convention, infer from the last 20 to 50 commits on the default branch. If the
  repository has no commits, only one commit, no reachable default-branch history, or mixed recent
  styles, default to Conventional Commits.
- For feature-branch work in this repository, preserve a single review commit by amending the
  existing branch commit or squashing related local commits before pushing, unless the user
  explicitly asks for multiple commits or the change includes a refactor, which belongs in its own
  focused commit before the behavior-change commit.
- In the pull request template, choose the intended history result in plain language. **Combine this
  pull request into one final commit** means the branch commits are review steps; maintainers normally
  use GitHub's squash merge. **Keep the individual commits** means each focused commit remains in
  `main`; maintainers normally use GitHub's rebase merge or an equivalent history-preserving
  fast-forward operation. The template's `(squash)` and `(rebase)` suffixes are optional hints, not
  knowledge contributors need to make the choice. Do not infer the result from commit count.
- Do not preemptively rewrite every older open pull request when the template contract changes. When
  substantive work resumes on an older pull request, update its body to the current template before
  considering it ready.
- Treat Release Please pull request titles and bodies as generated metadata. Do not normalize its
  body to the general pull request template or manually edit version text: Release Please parses that
  body when updating the PR. Put extra review or verification evidence in a comment instead.
- Synchronize feature branches by rebasing onto the latest default branch. Never create merge
  commits to update a pull request branch; after verifying the rewritten history, push it with
  `--force-with-lease` against the previously fetched remote head.
- If the user explicitly wants a multi-commit pull request, keep each commit focused and Conventional
  Commit titled. Use one commit for the user-visible feature or fix and separate commits only for
  directly supporting cleanup or refactoring that belongs to the same cohesive change; unrelated work
  still belongs in a separate branch or pull request.
- A pull request that keeps its individual commits must have coherent ownership. Keep implementation
  and tests in their owning commit, verify each retained commit independently where practical, and do
  not let a later commit silently repair an earlier commit that is presented as complete. Focused
  refactoring and related general improvements may remain separate when each is independently useful
  and belongs to the pull request's cohesive goal.
- When the user asks to "clean the git history", rewrite the branch into reviewable commits with one
  commit per cohesive change. Keep refactors, formatting/tooling changes, documentation policy
  changes, and product behavior changes in separate commits unless the user explicitly asks for a
  single squashed commit.
- When one pull request intentionally covers multiple GitHub issues, keep at least one focused commit
  per issue so each issue has its own reviewable unit. Combine multiple issues in one pull request
  only when the issues are cohesive or merging them together is materially easier. A single issue may
  still use multiple focused commits when requested or when that improves review, but do not collapse
  separate issue work into one mixed commit unless the user explicitly asks.
- When the user asks to address review comments on an existing pull request, make the review-response
  changes in a separate follow-up commit so the review delta is easy to inspect. Do not amend those
  changes into the existing PR commit unless the user explicitly asks. Run the Codex review/fix loop
  before pushing, reply on GitHub to every handled review comment or thread, and push the updated
  branch. Do not resolve GitHub review threads after replying; leave them for the reviewer to resolve
  unless the user explicitly asks you to resolve them. When a review reply reports a code, test,
  documentation, configuration, or PR-metadata change, name the short commit hash that contains the
  change and include the final relevant code, text, or configuration in fenced code blocks with enough
  surrounding context that the reviewer can understand it without hunting through the diff. A prose
  summary, file link, or commit link does not replace the required final snippet. When no file content
  changed, say that explicitly and give the rationale or validation evidence instead of inventing a
  snippet. This requirement applies to all review comment handling in this repository. If the user also
  asks to squash the
  already-pushed PR commits first, squash them into one base commit before adding the separate
  review-response commit.
- When authoring new inline review feedback, collect all comments for that pull request before
  publishing them. Create one pending review containing the complete comment set, then submit that
  review once at the end so contributors receive one review notification instead of one notification
  per comment. Do not create or submit a separate review for each comment. After submission, query the
  pull request reviews and verify that the authenticated user has no review left in `PENDING` state.
  Review replies belong to their existing threads and cannot be combined into a new review; post those
  only after all code and validation work is complete.
- Keep `feat/issue-35-plan-b-onboarding` as a single commit on top of `main`; amend or squash and
  force-push when changing that branch.
- Use closing keywords such as `Closes #123`, `Fixes #123`, or `Resolves #123` only when the PR or
  commit fully implements the issue and should close it on merge. If the change only adds guidance,
  creates a prerequisite, documents a decision, or implements part of the issue, use a non-closing
  reference such as `Refs #123` and state what remains.
- Make every implementing pull request visible in the issue's GitHub Development section. For a PR
  targeting the default branch, use the correct closing keyword and verify the PR appears in the
  issue's `closedByPullRequestsReferences`. GitHub ignores closing keywords on PRs that target any
  other branch.
- For a stacked PR, create the issue-linked branch before making the pull request, for example with
  `gh issue develop 123 --base <stack-base> --name <branch>`, then open the PR from that branch and
  verify that GitHub replaced the Development branch link with the PR link. If a stacked PR already
  exists without that link, manually link it through the issue or PR Development sidebar; do not
  retarget the PR to `main`, create a duplicate branch or PR, or disrupt the stack only to make the
  link appear.
- The pull request title is linted in CI because it supplies the subject when a pull request is
  combined into one final commit. CI also lints pull request commit messages so a PR that keeps its
  individual commits has clean release automation input. Before publishing a PR, run the same local
  check with the exact title:

  ```bash
  scripts/commitlint-local title 'docs: describe static-analysis policy'
  ```

  Also lint the commit range with the stricter message config:

  ```bash
  scripts/commitlint-local range origin/main HEAD
  ```

- For a breaking change, verify the exact PR title and the message that will reach `main` use both
  `!` and a `BREAKING CHANGE:` footer. The footer must include the reason and migration path because
  it feeds the generated changelog. A PR that combines into one final commit may add the footer only
  in the final combined body. A PR that keeps its individual commits must put both markers in the
  retained commit that owns the break. In either mode, a branch commit containing either marker must
  already contain both. PR metadata validation checks the title, visible template fields, declared
  history result, and commit range; commitlint checks each branch message. Final merge review checks
  that the selected result, actual merge method, and final message agree.
- When the user asks for a concrete repo change, commit and push the completed change unless they
  explicitly ask not to.
- When a branch already has a pull request authored by the authenticated GitHub user, check before
  finishing whether the PR description needs to change because the scope, verification, known
  limitations, linked issues, or deployment/live evidence changed. Update the PR description when it
  would otherwise be stale or incomplete, and report whether you updated it. For PRs authored by
  someone else, do not edit the PR description unless the user explicitly asks.
- When waiting for pull request checks, wait for GitHub Actions and other required CI checks to
  finish, but do not block only on a pending CodeRabbit status context. Treat CodeRabbit as
  asynchronous review feedback: address it when it posts actionable comments or requested changes,
  and report if it is still pending after the actual CI checks are green.

## References

- [Specification & ADR discipline](specification-and-adr-discipline.md)
- [Testing](testing.md)
- [Deployment & live verification](deployment-and-live-verification.md)
- [Java style & design preferences](java-style.md)
- [Static analysis policy](static-analysis.md)
- [Autonomy & escalation](autonomy-and-escalation.md)
- [Private-context redaction](private-context-redaction.md)
- [Maintaining agent docs](maintaining-agent-docs.md)
