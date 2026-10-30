---
status: accepted
date: 2026-10-30
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #603](https://github.com/martin-francois/symphony-trello/issues/603)"
  - "[Design decision on GitHub issue #603](https://github.com/martin-francois/symphony-trello/issues/603#issuecomment-6519507423)"
  - "[GitHub PR #790](https://github.com/martin-francois/symphony-trello/pull/790)"
informed: [Future maintainers, Contributors]
---

# Manage board cards through CLI commands and a Codex skill

## Context and Problem Statement

Outside a worker run, a user wants to ask an agent about the cards on a connected board, or to have
it file, move, comment on, or archive a card. Codex in a normal terminal session has no Trello
tools, and handing it the board's Trello key and token would let it do anything the token allows.

[GitHub issue #603](https://github.com/martin-francois/symphony-trello/issues/603) asked for this.
[GitHub PR #790](https://github.com/martin-francois/symphony-trello/pull/790) answered it with a
`symphony-trello codex` command that launched an interactive Codex session with its own local MCP
server for Trello tools. That PR added about 2,200 lines of production code for the session launcher
and the tool server. On 2026-10-30 martinfrancois closed it in favor of
[CLI commands plus a skill](https://github.com/martin-francois/symphony-trello/issues/603#issuecomment-6519507423).

How should an agent outside a worker run read and change cards on a connected board?

## Decision Drivers

* The agent MUST NOT see the Trello key or token, and no output may print them.
* Writes MUST follow the same `trello_tools` rules as the worker's Trello tools, so a workflow that
  refuses a move refuses it everywhere.
* The feature SHOULD work for any agent that can run a shell command, not only Codex.
* The feature SHOULD add little code that only this path uses.
* A normal `codex` session SHOULD know the commands without extra setup.

## Considered Options

* `symphony-trello card` subcommands plus a Codex skill that the installer puts into the user's
  Codex skills directory.
* A `symphony-trello codex` command that starts a Codex session with a local MCP server, as in
  [GitHub PR #790](https://github.com/martin-francois/symphony-trello/pull/790).
* Give the agent the Trello credentials and document the Trello REST API in a skill.

## Decision Outcome

Chosen option: "`symphony-trello card` subcommands plus a Codex skill", because it keeps the
credentials inside Symphony, reuses the worker's write rules, works for any shell agent, and needs
far less code than a session launcher with a tool server.

The commands are `card list`, `card show`, `card create`, `card move`, `card comment`, and
`card archive`. They select the board like `start`, `stop`, `status`, and `logs`, read the board's
credentials the same way a worker start does, and write through `TrelloCardWrites`, the class the
worker's Trello tools also use. The worker keeps what only applies to a run on one card: the
workpad, follow-up card metadata, and the rule that tools act only on the current card.

Details that the design decision left open:

* A list argument accepts a list name or a role from the workflow: `active`, `in-progress`,
  `human-review`, `blocked`, `merging`, or `terminal`. A list name wins over a role name, so a user
  list called `Active` stays reachable.
* `card move` uses the worker's move allowlist unchanged. `card create` accepts a list from that
  allowlist or a list that is neither active nor terminal, such as `Inbox`. Without the second
  group, the recommended board could not create a card in `Inbox`. Without a limit, the agent could
  create a card straight into `Ready for Codex` and start a run that the move rules would refuse.
* `card archive` needs only `trello_tools.enabled` and `trello_tools.allow_writes`. Trello keeps
  archived cards and can restore them, so archiving is not one of the deletes that
  `trello_tools.allow_destructive_operations` guards. The safeguard is a title check taken from
  [GitHub PR #790](https://github.com/martin-francois/symphony-trello/pull/790): `card archive`
  needs `--confirm-title` with the card's current title and refuses any other title before it
  writes. The skill tells the agent to show the user the card and pass the title the user
  confirmed.
* `card list` and `card show` only read, so they do not depend on `trello_tools`.
* `--workflow` accepts only the workflow of a connected board, and the commands send the Trello key
  and token only to the Trello REST API at `https://api.trello.com/1`. `start` also runs an
  unconnected workflow and uses its `tracker.endpoint`, but the agent that runs card commands can
  write files. Without these two rules, a workflow the agent wrote could point `tracker.endpoint` at
  another host and the command would send that host the credentials of the default `.env` file.
  The [Codex security review on GitHub PR #1139](https://github.com/martin-francois/symphony-trello/pull/1139#discussion_r4422817881)
  found this.
* The skill lives in `.codex/skills/symphony-trello-board-cards` under the name it gets when
  installed. Both installers copy it to `$CODEX_HOME/skills`, or to `~/.codex/skills` when
  `CODEX_HOME` is unset, and write a marker file into the directory. A directory without the marker
  belongs to the user and stays untouched, and the uninstallers remove only a marked directory. A
  failure to write the skill does not fail the install, because the commands work without it.
* The skill copy and the marker checks live in the install and uninstall scripts, in Bash and in
  PowerShell, instead of in a Java command. The uninstallers never start Java: they must work when
  the app directory is already gone or its build is broken, like the other managed Codex files they
  remove. Keeping the install side in the same scripts keeps the marker rule in one layer, next to
  the install context that records the skill directory.
* Workspace skill installation does not copy this skill into card workspaces. A worker run already
  has Trello tools for its card, and the skill tells an agent inside a worker run to use those.

### Consequences

* Good, because the key and token stay in Symphony's process. The agent only sees command output,
  and tests check that the output never contains the credentials.
* Good, because a `trello_tools` change applies to the worker and to the card commands at once.
* Good, because Claude Code, scripts, and other shell agents can use the same commands.
* Good, because `--json` gives agents and scripts a stable output shape.
* Neutral, because the recommended move allowlist includes `In Progress`, an active list. Moving or
  creating a card there through the commands lets Symphony pick it up, the same as a worker move.
* Bad, because the commands do not work with a custom `tracker.endpoint`, such as a proxy or the
  fake Trello API of a live bugbash run, even though the worker does.
* Bad, because the commands do not cover every Trello field, such as labels, due dates, or members.
  Adding one means a new option or subcommand.
* Bad, because the agent learns the commands from the skill text, not from tool schemas, so a
  wrong command fails only when it runs.
* Bad, because the skill copy and its marker rule exist twice, in Bash and in PowerShell, and
  `InstallerScriptTest` has to cover both.

### Confirmation

* `BoardCardCommandsTest` drives every command against a fake Trello board and covers board
  selection, an unconnected workflow and a workflow with another endpoint that both fail before any
  request, role moves, `trello_tools` refusals, a card on another board, archived cards, the
  archive title check, the `--json` shape, and credentials that never appear in output.
* `TrelloListRolesTest` and `TrelloCardSelectorsTest` cover role and card reference parsing.
* The worker tool tests, such as `TrelloHandoffToolHandlerTest` and `TrelloFollowUpCardToolTest`,
  still pass unchanged after the shared write rules moved into `TrelloCardWrites`.
* `InstallerScriptTest` covers the skill copy, the marker, the recorded skill directory, removal on
  uninstall, and a user-made directory that both scripts keep, for `install.sh` and `install.ps1`.
* `CodexSkillStructureTest` checks the skill file.

## Pros and Cons of the Options

### `symphony-trello card` subcommands plus a Codex skill

Six CLI subcommands read and change cards on one connected board. A short skill, installed into the
user's Codex skills directory, tells Codex when and how to run them.

* Good, because the credentials never reach the agent.
* Good, because it reuses the board selection, credential loading, and write rules that already
  exist.
* Good, because it works for any agent that can run a shell.
* Bad, because each command call starts a JVM, which is slower than a tool call to a running
  server.

### A `symphony-trello codex` session with a local MCP server

The command starts an interactive Codex session and gives it a local MCP server whose tools read
and change cards on the selected board. This is the approach of
[GitHub PR #790](https://github.com/martin-francois/symphony-trello/pull/790).

* Good, because Codex sees typed tool schemas.
* Good, because one server process answers many tool calls.
* Bad, because it adds about 2,200 lines of production code for the launcher and the server.
* Bad, because it works only for Codex sessions that the command starts, not for a normal `codex`
  session or other agents.

### Give the agent the credentials and a REST skill

A skill documents the Trello REST API, and the agent reads the key and token from the board's
`.env` file.

* Good, because it needs no new code in Symphony.
* Bad, because the agent sees credentials that can change any board the token can reach.
* Bad, because nothing applies the workflow's `trello_tools` rules.

## More Information

[SPEC.md section 19.8](../../SPEC.md#198-board-card-command-profile) holds the command contract. The
README section [Manage Cards From An Agent](../../README.md#manage-cards-from-an-agent) explains the
commands for users.
