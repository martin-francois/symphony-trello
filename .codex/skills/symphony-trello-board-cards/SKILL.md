---
name: symphony-trello-board-cards
description: >
  List, show, create, move, comment on, or archive cards on a Trello board
  connected to Symphony for Trello, through the `symphony-trello card`
  commands. Use when the user asks about or wants to change cards on their
  Symphony board.
---

# Symphony Board Cards

The `symphony-trello card` commands use a trusted helper that owns credentials and policy.
Use only these commands for card work. The operator starts the helper and configures the external
agent's permission profile before the session. Never read credential files or change that profile.

## Steps

1. Run `symphony-trello card list --json`, adding `--board NAME` when the user names a board.
   If several connected boards match, ask the user for the board id, short link or registered path.
   Do not read the manifest or run lifecycle administration to discover it.
2. Use the returned lists, roles and write permissions to choose the requested operation.
3. Run the card command. Add `--json` when reading output, and name each card by its title and URL.
4. If the helper is unavailable, ask the operator to run `symphony-trello card-helper start` from a
   trusted terminal. Do not start it inside the restricted session or fall back to direct Trello
   requests. After an uncertain outcome, inspect the board before proposing a write retry.

## Commands

| Command | Does |
| --- | --- |
| `card list [--list LIST]` | Lists with roles, permissions, and open cards |
| `card show CARD` | One card with description, checklists, attachments, comments |
| `card create --title TITLE [--description TEXT] --list LIST` | New card at the bottom of a list |
| `card move CARD --to LIST` | Moves a card |
| `card comment CARD --text TEXT` | Adds a comment; Symphony appends its footer |
| `card archive CARD --confirm-title TITLE` | Archives a card |

`CARD` is a card id, a short link, or a `https://trello.com/c/` URL. `LIST` is
an open list name or a role: `active`, `in-progress`, `human-review`,
`blocked`, `merging`, or `terminal`. A list name wins over a role name.

## Rules

- When the user does not name the list for a new card or a move, and the list
  roles from `card list` leave more than one list that fits, ask the user which
  list to use. Never guess a list.
- Archive a card only after the user confirmed that exact card, by title, in
  this conversation. Pass that title as `--confirm-title`; the command refuses
  a title that differs from the card's. Trello keeps archived cards, and the
  board menu restores them.
- The workflow's `trello_tools` settings decide where cards may move and start.
  A failure prints `setup_failed code=CODE message=...` and exits with 2. These
  codes mean a setting refused the write: `trello_tools_disabled`,
  `trello_writes_disabled`, `trello_comments_disabled`,
  `trello_move_not_allowed`, `trello_move_allowlist_required`, and
  `trello_create_list_not_allowed`. For them, tell the user which setting
  refused the write. Workflow changes belong in a trusted operator terminal.
- Any other code, such as `trello_auth_failed` or `trello_api_request`, is not
  a workflow setting. Tell the user the message and the `Next step:` line that
  follows it, and leave `trello_tools` alone.
- In a Symphony worker run on a card, where the `trello_move_current_card` tool
  exists, handle that card with the worker's `trello_*` tools.
