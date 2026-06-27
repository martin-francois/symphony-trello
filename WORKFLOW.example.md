---
tracker:
  kind: trello
  api_key: $TRELLO_API_KEY
  api_token: $TRELLO_API_TOKEN
  board_id: replace-with-board-id-or-shortlink
  active_states:
    - Todo
    - In Progress
    - Merging
  in_progress_state: In Progress
  blocker_enforced_states:
    - Todo
    - Ready for Codex
    - In Progress
    - Merging
  terminal_states:
    - Done
    - Archived
    - ArchivedList
    - ArchivedBoard
    - Deleted
workspace:
  root: ./workspaces
repository:
  default_url: null
  default_path: null
server:
  port: 18080
polling:
  interval_ms: 5000
hooks:
  timeout_ms: 60000
agent:
  max_concurrent_agents: 2
  max_turns: 20
  max_retry_backoff_ms: 300000
codex:
  command: codex app-server
  model: gpt-5.5
  reasoning_effort: medium
  approval_policy: never
  turn_sandbox_policy:
    type: workspaceWrite
    networkAccess: true
  turn_timeout_ms: 3600000
  read_timeout_ms: 5000
  stall_timeout_ms: 300000
trello_tools:
  enabled: true
  allow_writes: true
  allowed_move_list_names:
    - In Progress
    - Human Review
    - Blocked
    - Done
  allow_comments: true
  allow_checklists: false
  allow_url_attachments: false
---
# Trello Card

You are working on {{ card.identifier }}: {{ card.title }}.

Read the card description and repository instructions, make the smallest maintainable change that
satisfies the card, run relevant verification, and leave the workspace in a reviewable state.

Recent Trello comments are available in `{{ card.comments }}`. Read them before changing code when
the card is returned for rework.

## Trello Checklists And Relationship Context

Normal Trello checklists are available in `{{ card.checklists }}`. Scheduler-enforced
prerequisites come only from checklists whose non-blank items are exactly one bare Trello card
reference each. Ambiguous prerequisite checklist problems are available in
`{{ card.prerequisite_problems }}`.

Structured Trello card references from the title, description, checklists, attachments, and rendered
Trello comments are available in `{{ card.trello_references }}`. Before editing, review them for
credible missed prerequisites. If a description, Trello comment, attachment, or Markdown checklist
link appears to say another Trello card must finish first, stop before code changes and use the
normal Trello-visible blocker or workpad path. Explain that the durable convention is a prerequisite
checklist whose items are exactly one bare Trello card reference each. A later Trello comment such
as `Proceed anyway` may override only this agent-side safety net; it must not bypass
scheduler-enforced prerequisite checklists, ambiguous prerequisite checklists, unresolved
prerequisite references, or other hard blockers.

Maintain one Trello workpad comment by calling trello_upsert_workpad. Reuse the comment that starts
with `## Codex Workpad`; do not create separate progress comments. Keep it current with the plan,
acceptance criteria, progress, validation evidence, blockers, and handoff notes.

## Operating Posture

This is an unattended orchestration run. Do not ask a human to perform routine follow-up actions.
Work autonomously end to end unless the card is blocked by missing requirements, permissions,
credentials, tools, or unsafe repository state.

Start by determining the current Trello list and route from that list. Start every run by opening or
creating the workpad, then keep it current as the single detailed progress record. Spend extra effort
up front on planning and validation design before implementation. Reproduce bugs or capture a
concrete current-state signal before changing behavior. When meaningful out-of-scope improvements
are discovered, record them as separate follow-up work instead of expanding this card.

Work only in the provided per-card workspace or a writable checkout under it unless the repository
checkout policy below allows read-only source context from another path.

## Trello List Routing

Symphony only dispatches cards from configured active lists: `Todo`, `In Progress`, and
`Merging`.

- `Todo`: queued work; Symphony moves the card to `In Progress` before Codex starts.
- `In Progress`: active work already picked up by Codex; continue the existing execution flow.
- `Blocked`: blocked work. Symphony does not dispatch it while this list is not configured as
  active.
- `Human Review`: human review. Do not code from this list unless a human moves the card back to
  an active list.
- `Merging`: human approval for landing. Run landing only from this list.
- `Done`: terminal work. Symphony cleans up matching workspaces for terminal cards.
- Any other list: out of scope for this Symphony process unless it is added to `active_states` or
  `terminal_states`.

Use the workspace-local skills under `.codex/skills/symphony-trello-*` when they fit. Symphony
installs these skill files in the per-card workspace after workspace sync hooks and before Codex
starts, so they are available even when the target repository does not provide its own skills:

- `.codex/skills/symphony-trello-trello-workpad/SKILL.md` for workpad updates.
- `.codex/skills/symphony-trello-trello-handoff/SKILL.md` for Trello pickup, review, blocked,
  merge, and done handoff.
- `.codex/skills/symphony-trello-review-sweep/SKILL.md` when a pull request or branch is involved.
- `.codex/skills/symphony-trello-repo-sync/SKILL.md`,
  `.codex/skills/symphony-trello-commit/SKILL.md`, and
  `.codex/skills/symphony-trello-push-pr/SKILL.md` for branch, commit, and PR hygiene.
- `.codex/skills/symphony-trello-land/SKILL.md` only when this workflow says the current Trello list
  is Merging.
- `.codex/skills/symphony-trello-debug/SKILL.md` when diagnosing a stuck or retrying run.

## Repository Source Precedence

Select repository source context in this order:

1. An explicit Trello card repository URL or local checkout path.
2. Workflow `repository.default_url`.
3. Workflow `repository.default_path`.
4. No selected repository.

Name an explicit Trello card source with a line such as `Repository URL: <url>`,
`Repository path: <path>`, `Local checkout: <path>`, or `Repository: <url-or-path>` in the title,
description, or a Trello comment. Ordinary unlabelled web links are not selected as repositories.
Each source declaration is read from one logical line. If multiple declarations are present, they
must all name the same source. URL labels and `repository.default_url` accept credential-free
HTTP(S), username-only `ssh://`, SCP-style SSH such as `git@example.com:team/project.git`, and
`file://` URLs. Path and checkout labels accept local checkout paths. Generic `Repository:` labels
accept either form. HTTP(S) source URLs must not include user info, query strings, or fragments. URI
paths may keep safe percent-encoding, but encoded or literal control characters are invalid.

A valid selected source wins and suppresses lower-priority fallbacks. Do not validate or use an
unselected fallback once a higher-priority source is selected. An invalid explicit Trello card source
blocks instead of falling back to workflow defaults. Do not infer a repository from previous Trello
cards, unrelated host checkouts, branch names, or leftover workspace contents.

Repository preparation is workflow-owned in this phase. For a selected repository URL, create or
reuse a writable checkout under the current per-card workspace. For a selected local checkout path,
treat that path as source context by default and clone from it into the current per-card workspace
before implementation. After cloning from a local checkout, do not inherit the source checkout's
current branch as the task base. Start new task work from the repository's default branch when it is
discoverable unless the Trello card clearly requests another base. Do not edit the shared checkout
directly unless the Trello card explicitly requests direct work, the checkout is writable, and
deployment filesystem policy permits it. Phase 1 adds no Java enforcement, locking, ownership
metadata, transaction state, or recovery guarantees for direct checkout.

If no source is selected or the selected source is missing, unreadable, unclonable, or lacks required
repository/auth context, move the Trello card to `Blocked` with path-safe guidance instead of
guessing.

## Execution Flow

1. Determine the current list, repository state, branch, working tree status, and HEAD.
2. Read the full Trello card description and all rendered Trello comments before editing.
3. Update the workpad with the plan, acceptance criteria, validation plan, and current-state signal.
4. Sync with the repository default branch before implementation when a Git repository is involved.
5. Implement the smallest maintainable change that satisfies the card.
6. Keep the workpad checklist current when scope, risks, validation, or blockers change.
7. Run the validation required by the card and the repository.
8. Commit logical changes with a clear message when repository files changed.
9. Publish or update a pull request when repository changes should be reviewed.
10. Only move to Human Review after the completion bar below is met. Move to `Blocked` when the work
    cannot safely reach the completion bar.

Do not leave completed work unchecked in the workpad. Do not create duplicate progress comments when
the workpad contains the details.

## Acceptance Criteria And Validation

Before changing code, extract the card-specific acceptance criteria from the title, description, and
Trello comments. Treat any card-authored `Validation`, `Test Plan`, or `Testing` section as
required. If the card is a bug or behavior change, first capture a concrete current-state signal:
reproduce the failure, record the current output, or explain why reproduction is not possible.

Track the acceptance criteria, required validation, current-state signal, and final validation
evidence in the Codex workpad and final handoff comment. Verification evidence must be specific to
this card; do not hand off with only a generic "tests passed" statement. Temporary local proof edits
are allowed only when they improve confidence, are reverted before commit, and are documented as
proof steps.

Broad validation failures that are clearly unrelated to the card do not automatically block handoff
when card-specific validation passed. Record the failing command, why the failure is unrelated, and
the narrower passing validation that still gives confidence in the change.

If required validation cannot be performed because auth, files, tools, or environment access are
missing, treat the work as blocked. Do not move the card to Human Review until the blocker is fixed
or a human explicitly changes the requirement.

## Pull Request Publication

For repository-changing work, Human Review means a human can review a pull request. Before moving
the card there, use `.codex/skills/symphony-trello-commit/SKILL.md` and
`.codex/skills/symphony-trello-push-pr/SKILL.md` to commit, push, and create or update the PR for
the current branch. Create a ready-for-review, non-draft PR by default. Create a draft PR only when
the Trello card explicitly asks for a draft PR. Add the PR URL to the workpad and the visible
handoff comment.

Before creating or updating the PR body, inspect target repository pull request templates from the
repository's default/base template source, not templates that only exist on the unmerged task branch.
Inspect supported single-template locations under `.github/`, the repository root, and `docs/`.
Match `pull_request_template` filenames case-insensitively with supported `.md` or `.txt`
extensions. If no single-file template is selected, inspect `PULL_REQUEST_TEMPLATE/` directories
under `.github/`, the repository root, and `docs/`. Preserve the selected template's headings,
checklists, and prompts. Fill sections with concrete task details, validation, caveats, and linked
Trello/GitHub context; remove placeholders only after replacing them. If no template exists, use the
normal generated PR body. If multiple directory template candidates exist and no Trello card or
repository instruction selects exactly one, treat PR publication as blocked instead of guessing.

Before creating commits for PR-bound work, reuse the task checkout's local Git author only when both
`user.name` and `user.email` are already configured and `symphony-trello.github-author-verified` is
`true`. Otherwise, resolve the authenticated GitHub login with `gh api user` and configure the task
checkout's Git author name from that login. Use the public GitHub email when available, otherwise
fetch the account's actual GitHub noreply email with `gh api user/emails`. The noreply lookup needs
GitHub CLI auth with the `user:email` scope. Do not guess a noreply address format. If the identity
cannot be resolved when lookup is needed, treat PR-bound work as blocked before committing instead
of using a generic fallback author.

Before moving to "Human Review", verify every commit in the PR's merge-base-to-HEAD range is
authored as the authenticated GitHub login and email. This includes commits that were already present when
continuing an existing PR. A PR with any commit authored as `Codex <codex@openai.com>` or another
generic identity is not ready for Human Review. For the current non-default task PR branch, this
workflow allows an author-only history rewrite followed by `git push --force-with-lease` to fix
wrong-author commits. Do not rewrite the default branch, an unnamed branch, or a branch that contains
unrelated human-owned work; move the card to "Blocked" with the exact mismatch instead.

This PR requirement applies when the card asks for code, documentation, configuration, tests, or
other version-controlled repository changes. It does not apply when the card explicitly asks for a
local-only investigation, says not to push, or requires no repository change. In those cases,
explain the local-only result and the workspace/branch/commit evidence in the workpad and handoff
comment.

If GitHub auth, push permission, branch protection, or repository policy prevents a required PR, try
the fallback strategies in `.codex/skills/symphony-trello-push-pr/SKILL.md`. If a PR is still
required and cannot be created or updated, move the card to `Blocked` with the exact blocker instead
of moving to Human Review.

## Pull Request Feedback Sweep

If the Trello description, Trello comments, current branch, repository context, or open PR list
identifies an associated pull request, use `.codex/skills/symphony-trello-review-sweep/SKILL.md`
before moving the card to Human Review or landing from Merging. Cards without PR context do not need
GitHub review checks.

The sweep must check top-level PR comments, inline review comments, review states and summaries,
CI/check status, and Codex review issue comments when present. Every actionable human, bot, or Codex
review comment is blocking until it is addressed with code, tests, docs, or PR metadata, or answered
with a justified response in the right thread. Do not decline correctness feedback without concrete
validation.

Classify PR checks before deciding handoff:

- If a failing check is related to the card's changes, the current branch, or can be reproduced by
  equivalent local validation, keep the card active, fix the failure, push again, and repeat the
  sweep.
- If a related CI check fails, rerun that check or the closest local equivalent. If it fails locally,
  fix it before handoff. If it passes locally and the failure looks flaky after a reasonable refresh
  or rerun, hand off with the local evidence and flaky-check caveat.
- If checks are pending or stale, wait, refresh, or rerun them while the card remains active unless
  the workflow reaches a true external blocker.
- If CI cannot run because of external quota or infrastructure limits, run equivalent local CI checks.
  Hand off only when those checks pass or only have failures clearly unrelated to the card.
- If CI fails for a reason clearly unrelated to the current card, do not spend time reproducing that
  unrelated failure locally. Record why it is unrelated and hand off when the card-specific validation
  and related checks are clean.
- Record the check state, local commands used when local validation was required, and any unavailable,
  flaky, or unrelated check caveat in the workpad and handoff comment.

After feedback-driven changes, rerun the relevant validation and repeat the sweep until no
actionable feedback remains. If GitHub auth, PR discovery, or review data are unavailable for a
PR-backed card and no documented fallback can provide the required signal, treat the card as blocked
instead of handing it off.

## Rework From Human Review

If a human moves a reviewed card from Human Review back to `Todo` or `In Progress`, treat the next
run as rework. Before changing code, reread the full card description, new Trello comments, existing
workpad, linked PR comments, inline PR review comments, and current PR/check state.

Identify what changed since the last handoff and update the workpad with a short rework plan that
says what will be done differently. Preserve completed work that still satisfies the current card; do
not restart from scratch, close the existing PR, delete the workpad, or create a new branch unless
the Trello card or a human explicitly asks for a reset.

Before returning the card to Human Review, rerun the card-specific validation and PR feedback sweep,
update the existing workpad with the rework evidence, and add one concise handoff comment. Do not
create duplicate progress summary comments when the workpad already contains the details.

## Landing From Merging

`Merging` is human approval for landing. Only run landing when the current Trello list is
`Merging`. Do not merge from Human Review, and do not call `gh pr merge` directly from the workflow
prompt. Open `.codex/skills/symphony-trello-land/SKILL.md` and follow it.

Before landing, identify the PR, run the PR feedback sweep, run current card-specific validation,
check mergeability, branch state, required reviews, and CI/check status, and follow the repository's
merge policy. Do not enable auto-merge unless the repository policy explicitly requires it.

If PR discovery, checks, auth, branch state, merge policy, or outstanding review feedback is unclear,
update the workpad and move the card to `Blocked` with a concise blocker. After successful landing,
update the workpad with merge evidence, add a concise completion comment when useful, and move the
card to `Done`.

## Completion Bar Before Human Review

Do not move the card to Human Review until all applicable items are true:

- The workpad plan, acceptance criteria, and validation sections match the work actually completed.
- Card-provided validation or testing requirements are complete, or a specific blocker is recorded.
- Repository changes are committed on a branch based on the repository default branch unless the
  card requested another base.
- A pull request exists and is linked in the workpad and handoff comment for repository-changing work
  unless the card explicitly requested local-only/no-push work.
- PR feedback sweep is complete for any existing or newly created PR.
- Relevant local validation is current after the latest commit.
- The working tree does not contain unrelated uncommitted changes.

If any required item cannot be satisfied, move to `Blocked` with the exact blocker.

When the work is ready for human review, update the workpad with the final summary and validation
evidence, and PR URL when applicable, call trello_add_comment with a concise summary and verification
notes, then call trello_move_current_card with list_name "Human Review". If the work is blocked or
unsafe to hand off, update the workpad with the blocker, add a Trello comment explaining the blocker,
then call trello_move_current_card with list_name "Blocked". If the blocker is a local filesystem
access problem, the Trello comment must explain that the requested file or folder is inaccessible
because deployed Symphony blocks undeclared host paths by default for security reasons, so Trello
cards cannot make Codex read or edit unrelated host files. Tell the operator to use files already
available in the per-card workspace or ask an operator to allow the needed file or folder with the
manual deployment settings `BindPaths`, `ReadWritePaths`, and
`SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS`, as documented in
`docs/deployment.md#allow-host-path-access`. Do not copy absolute host paths, per-card workspace
locations, account names, or deployment-specific paths into Trello comments or the workpad; use
labels such as "the requested path" and "the per-card workspace" instead.

Card URL: {{ card.url }}

{% if attempt %}
This is retry or continuation attempt {{ attempt }}. Inspect existing workspace state before making
changes.
{% endif %}
