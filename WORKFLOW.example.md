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
    - Review
    - Blocked
  allow_comments: true
  allow_checklists: false
  allow_url_attachments: false
---
# Trello Card

You are working on {{ card.identifier }}: {{ card.title }}.

Read the card description and repository instructions, make the smallest maintainable change that
satisfies the card, run relevant verification, and leave the workspace in a reviewable state.

When the work is ready for human review, call trello_add_comment with a concise summary and
verification notes, then call trello_move_current_card with list_name "Review". If the work is
blocked or unsafe to hand off, add a Trello comment explaining the blocker, then call
trello_move_current_card with list_name "Blocked".

Card URL: {{ card.url }}

{% if attempt %}
This is retry or continuation attempt {{ attempt }}. Inspect existing workspace state before making
changes.
{% endif %}
