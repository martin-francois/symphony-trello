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
  decision. Use the `idea` label when the work is exploratory rather than ready to implement.
- Until the first public release, apply [pre-public clean breaks](#pre-public-clean-breaks): implement
  only the canonical current contract and update private deployments manually when private
  pre-release files need a one-time edit.
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

## Pre-Public Clean Breaks

- Until the first public release, do not add or retain product migrations, legacy-shape support,
  backward-compatibility shims, old-template fingerprints, old-private-state fallbacks, or automatic
  upgrade code for private pre-release files, manifests, generated output, state, or release
  destinations.
- Implement and test only the canonical current contract. If private deployed files or generated
  workflows are stale, update them manually once outside product code.
- Do not add compatibility tests whose only purpose is to prove behavior for private historical
  shapes. Test current behavior, current validation, and current failure modes instead.
- Do not add generated-output version markers solely to support upgrades of private pre-release
  generated files.
- Do not commit private paths, credentials, Trello board links, account names, deployment details, or
  private backup contents when doing a one-time private edit.
- After the first public release, compatibility decisions require an explicit issue, specification
  change, or ADR that defines the supported public contract.

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

- Fix bugs in this order: investigate first, reproduce second, fix third. The investigation must
  name the exact mechanism that produces the failure - the specific code path, lock, state, timing,
  or interaction - and why it exists, before any fix is designed. A plausible category such as
  "flaky", "transient", "timing", "GC", "race", or "host load" is a symptom description, not a root
  cause; for behavior in code this project owns, keep digging until the failing mechanism is
  identified in the code, and accept an environmental explanation only after the owned code has been
  ruled out. Then reproduce the bug and turn the reproduction into the smallest regression test,
  confirm that the test fails for the identified mechanism and not for an incidental reason, and only
  then implement the fix at the mechanism. A tolerance mechanism such as a retry, longer timeout, or
  fallback is acceptable only as a documented compatibility or defense-in-depth layer on top of the
  mechanism fix, never as a replacement for finding it.
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
- If the user explicitly wants a multi-commit pull request, keep each commit focused and Conventional
  Commit titled. Use one commit for the user-visible feature or fix and separate commits only for
  directly supporting cleanup or refactoring that belongs to the same cohesive change; unrelated work
  still belongs in a separate branch or pull request.
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
  unless the user explicitly asks you to resolve them. When a review reply says code changed, include
  a small snippet of the resulting code with enough surrounding context that the reviewer can
  understand the change without hunting through the diff. If the user also asks to squash the
  already-pushed PR commits first, squash them into one base commit before adding the separate
  review-response commit.
- Keep `feat/issue-35-plan-b-onboarding` as a single commit on top of `main`; amend or squash and
  force-push when changing that branch.
- Use closing keywords such as `Closes #123`, `Fixes #123`, or `Resolves #123` only when the PR or
  commit fully implements the issue and should close it on merge. If the change only adds guidance,
  creates a prerequisite, documents a decision, or implements part of the issue, use a non-closing
  reference such as `Refs #123` and state what remains.
- The pull request title is linted in CI because the repository normally squash-merges with that
  title. CI also lints pull request commit messages so intentionally multi-commit or rebase-merged
  PRs keep release automation input clean. Before publishing a PR, run the same local check with the
  exact title:

  ```bash
  printf '%s\n' 'docs: describe static-analysis policy' | pnpm dlx --package @commitlint/cli@21.0.1 --package @commitlint/config-conventional@21.0.1 commitlint --config commitlint.config.cjs
  ```

  For a PR that may be rebase-merged or intentionally keeps multiple commits, also lint the commit
  range:

  ```bash
  pnpm dlx --package @commitlint/cli@21.0.1 --package @commitlint/config-conventional@21.0.1 commitlint --config commitlint.config.cjs --from origin/main --to HEAD --verbose
  ```

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
