---
name: symphony-trello-board-cards
description: >
  List, show, create, move, comment on, or archive cards on a Trello board
  connected to Symphony for Trello, through the `symphony-trello card`
  commands. Use when the user asks about or wants to change cards on their
  Symphony board.
---

# Symphony Board Cards

The `symphony-trello card` commands read the board's Trello key and token
themselves and never print them. Work only through these commands: the `.env`
files and any secret file a workflow names stay unread, because the commands
never need you to see a credential.

## Steps

1. Run `symphony-trello status` to see the connected boards. When more than
   one board is connected, pass `--board NAME` to every card command, with a
   board name that status shows.
2. Run `symphony-trello card list --board NAME --json` to learn the lists,
   their roles, where moves and new cards are allowed, and the open cards.
3. Run the card command the request needs. Add `--json` whenever you read the
   output, and name each card by its title and URL in your answer.

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

- Archive a card only after the user confirmed that exact card, by title, in
  this conversation. Pass that title as `--confirm-title`; the command refuses
  a title that differs from the card's. Trello keeps archived cards, and the
  board menu restores them.
- The workflow's `trello_tools` settings decide where cards may move and start.
  A failure prints `setup_failed code=CODE message=...` and exits with 2. For a
  `trello_*` code, tell the user which setting refused the write; edit the
  workflow only when the user asks for that.
- In a Symphony worker run on a card, where the `trello_move_current_card` tool
  exists, handle that card with the worker's `trello_*` tools.
