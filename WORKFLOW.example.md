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
hooks:
  timeout_ms: 60000
agent:
  max_concurrent_agents: 2
  max_turns: 20
  max_retry_backoff_ms: 300000
codex:
  command: codex app-server
  approval_policy: never
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

Maintain one Trello workpad comment by calling trello_upsert_workpad. Reuse the comment that starts
with `## Codex Workpad`; do not create separate progress comments. Keep it current with the plan,
acceptance criteria, progress, validation evidence, blockers, and handoff notes.

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

Use repository-local skills when they fit:

- `.codex/skills/trello-workpad/SKILL.md` for workpad updates.
- `.codex/skills/trello-handoff/SKILL.md` for Trello pickup, review, blocked, merge, and done
  handoff.
- `.codex/skills/review-sweep/SKILL.md` when a pull request or branch is involved.
- `.codex/skills/repo-sync/SKILL.md`, `.codex/skills/commit/SKILL.md`, and
  `.codex/skills/push-pr/SKILL.md` for branch, commit, and PR hygiene.
- `.codex/skills/land/SKILL.md` only when this workflow says the current Trello list is Merging.
- `.codex/skills/debug/SKILL.md` when diagnosing a stuck or retrying run.

## Repository Checkout Policy

Do implementation work inside the current per-card workspace or a writable checkout under it.
Do not edit a shared host checkout directly unless the card explicitly asks you to work there and
that checkout is writable.

If the Trello card names only a repository URL, create or reuse a writable checkout in the current
workspace. Prefer cloning from a readable matching local checkout under an allowed host path, then
set the checkout's `origin` remote to the repository URL when needed. If no matching local checkout
is readable, clone the repository URL directly into the workspace.

If the Trello card names a specific local path or checkout, inspect it as source context. When it is
not writable, clone from that readable local path into the current workspace and work in the clone
instead of blocking. Block only when the path is not readable, the repository cannot be cloned into a
writable workspace, or required repository/auth context is unavailable. If Git rejects a readable
local checkout because of safe-directory ownership checks, add only that source checkout to the
current user's Git safe directories with `git config --global --add safe.directory <source-checkout>`,
then retry a read-only clone with `git clone --no-hardlinks <source-checkout> <workspace-checkout>`.
After cloning from a local checkout, do not inherit the source checkout's current branch as the task
base. Start the task branch from the repository's default branch when it is discoverable, usually
`origin/main`, unless the Trello card explicitly asks for a different base.

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

## Pull Request Feedback Sweep

If the Trello description, Trello comments, current branch, repository context, or open PR list
identifies an associated pull request, use `.codex/skills/review-sweep/SKILL.md` before moving the
card to Human Review or landing from Merging. Cards without PR context do not need GitHub review
checks.

The sweep must check top-level PR comments, inline review comments, review states and summaries,
CI/check status, and Codex review issue comments when present. Every actionable human, bot, or Codex
review comment is blocking until it is addressed with code, tests, docs, or PR metadata, or answered
with a justified response in the right thread. Do not decline correctness feedback without concrete
validation. Failing, pending, or stale required checks mean the work is not ready for handoff when a
pull request is already part of the card.

After feedback-driven changes, rerun the relevant validation and repeat the sweep until no
actionable feedback remains. If GitHub auth, PR discovery, required checks, or review data are
unavailable for a PR-backed card, treat the card as blocked instead of handing it off.
Do not create or push a pull request unless the card, repository policy, or a human explicitly asks
for one. When the card asks only for local commits, hand off with the workspace checkout path, branch
name, commit list, and validation evidence instead of blocking on missing push credentials.

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
prompt. Open `.codex/skills/land/SKILL.md` and follow it.

Before landing, identify the PR, run the PR feedback sweep, run current card-specific validation,
check mergeability, branch state, required reviews, and CI/check status, and follow the repository's
merge policy. Do not enable auto-merge unless the repository policy explicitly requires it.

If PR discovery, checks, auth, branch state, merge policy, or outstanding review feedback is unclear,
update the workpad and move the card to `Blocked` with a concise blocker. After successful landing,
update the workpad with merge evidence, add a concise completion comment when useful, and move the
card to `Done`.

When the work is ready for human review, update the workpad with the final summary and validation
evidence, call trello_add_comment with a concise summary and verification notes, then call
trello_move_current_card with list_name "Human Review". If the work is blocked or unsafe to hand off,
update the workpad with the blocker, add a Trello comment explaining the blocker, then call
trello_move_current_card with list_name "Blocked".

Card URL: {{ card.url }}

{% if attempt %}
This is retry or continuation attempt {{ attempt }}. Inspect existing workspace state before making
changes.
{% endif %}
