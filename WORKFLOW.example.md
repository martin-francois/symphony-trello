---
tracker:
  kind: trello
  api_key: $TRELLO_API_KEY
  api_token: $TRELLO_API_TOKEN
  board_id: replace-with-board-id-or-shortlink
  active_states:
    - Todo
    - In Progress
  blocker_enforced_states:
    - Todo
    - Ready for Codex
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
    - Human Review
    - Blocked
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

## Trello Column Routing

Symphony only dispatches cards from configured active columns: `Todo` and `In Progress`.

- `Todo`: queued work; move the card to `In Progress` before active implementation.
- `In Progress`: active work already picked up by Codex; continue the existing execution flow.
- `Blocked`: blocked work. Symphony does not dispatch it while this column is not configured as
  active.
- `Human Review`: human review. Do not code from this column unless a human moves the card back to
  an active column.
- `Merging`: human approval for landing. Do not merge from Human Review, and do not run landing
  unless this workflow explicitly configures Merging as active.
- `Done`: terminal work. Symphony cleans up matching workspaces for terminal cards.
- Any other column: out of scope for this Symphony process unless it is added to `active_states` or
  `terminal_states`.

Use repository-local skills when they fit:

- `.codex/skills/trello-workpad/SKILL.md` for workpad updates.
- `.codex/skills/trello-handoff/SKILL.md` for Trello pickup, review, blocked, merge, and done
  handoff.
- `.codex/skills/review-sweep/SKILL.md` when a pull request or branch is involved.
- `.codex/skills/repo-sync/SKILL.md`, `.codex/skills/commit/SKILL.md`, and
  `.codex/skills/push-pr/SKILL.md` for branch, commit, and PR hygiene.
- `.codex/skills/land/SKILL.md` only when this workflow says the current Trello column is Merging.
- `.codex/skills/debug/SKILL.md` when diagnosing a stuck or retrying run.

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
validation. Failing, pending, or stale required checks mean the work is not ready for handoff.

After feedback-driven changes, rerun the relevant validation and repeat the sweep until no
actionable feedback remains. If GitHub auth, PR discovery, required checks, or review data are
unavailable for a PR-backed card, treat the card as blocked instead of handing it off.

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
