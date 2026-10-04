---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #603](https://github.com/martin-francois/symphony-trello/issues/603)"
  - "[ADR 0003](0003-scoped-trello-handoff-tools.md)"
  - "[MCP streamable HTTP transport](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)"
  - "Codex CLI 0.160.0 `codex --help` and `codex mcp add --help`"
  - "[MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk)"
  - "[Quarkus MCP Server](https://github.com/quarkiverse/quarkus-mcp-server)"
informed: [Future maintainers, Contributors]
---

# Launch interactive Codex with a loopback board-tool server

## Context and Problem Statement

Symphony for Trello starts automated Codex workers for cards. Users also want to open a normal
interactive Codex session that already knows one connected Trello board and can create, read,
update, move, comment on, and archive cards on it, without explaining the board again and without
arranging Trello access by hand.

The session needs board context before the first request, Trello access that honors the workflow's
`trello_tools` policy, and the user's own Codex login, sandbox, and approval settings. The Trello
API key and token decide the full Trello access of the account, so where they go matters.

How should `symphony-trello codex` give an interactive Codex session board context and Trello access?

## Decision Drivers

* Keep the standard Codex terminal UI, login, sandbox, approvals, and working directory.
* Keep the Trello key and token out of Codex's arguments, environment, conversation, and
  configuration files when that costs little.
* Enforce `trello_tools` policy and board scoping in code, not only in instructions.
* Work the same way on Linux, macOS, and Windows with the installed Codex CLI.
* Clean up every helper when Codex exits or the user interrupts it.
* Reuse the connected-board manifest, lifecycle selectors, credential resolution, `TrelloClient`,
  and move allowlist.

## Considered Options

* Normal Codex CLI with an ephemeral board-scoped MCP server inside the `symphony-trello` process.
* The same server built on the MCP Java SDK.
* The same server built on the Quarkus MCP Server extension.
* Normal Codex CLI with a board-scoped stdio MCP server that Codex starts.
* Custom interactive client on top of Codex app-server.
* Board-management subcommands that Codex runs through its shell tool.
* Trello credentials in the Codex child environment plus board instructions.

## Decision Outcome

Chosen option: normal Codex CLI with an ephemeral board-scoped MCP server inside the
`symphony-trello` process.

`symphony-trello codex [--board NAME | --workflow PATH]` selects one connected board with the
shared lifecycle selector rules in `ConnectedBoardSelection`. Without a selector it checks every
manifest row with the same workflow loading as `start`, skips unusable rows with a reason, uses the
only eligible board directly, shows a numbered picker on an interactive terminal, and fails with a
`--board`/`--workflow` hint otherwise. A cancelled picker exits with status 130 before Codex starts
or Trello is contacted.

After selection the command checks `codex --version` and `codex login status` and only uses their
exit status. It then resolves the workflow through `LocalWorkerManager` with the same credential
file, shell-environment precedence, local validation, and Trello credential preflight as `start`.

The command starts `BoardSessionMcpServer`, an MCP streamable HTTP server on an ephemeral
`127.0.0.1` port in the same JVM. It answers each JSON-RPC request with one JSON response and offers
no event stream. Codex authenticates with a random 256-bit bearer token. The token exists only in
memory and in the Codex child environment variable `SYMPHONY_TRELLO_BOARD_SESSION_TOKEN`. The server
also refuses requests with an `Origin` header or a non-loopback `Host`.

The command runs the installed `codex` with structured arguments and the caller's terminal:

```text
codex -c mcp_servers.symphony_trello.url=http://127.0.0.1:PORT/mcp
      -c mcp_servers.symphony_trello.bearer_token_env_var=SYMPHONY_TRELLO_BOARD_SESSION_TOKEN
      -c developer_instructions=Symphony for Trello started this Codex session ...
```

The values carry no quotes. Codex reads a `-c` value that is not valid TOML as plain text, and
platform command-line parsing cannot strip quotes that are not there. For the same reason the
developer instructions keep only letters, digits, spaces, and `-_.,:/+@`. Trello names with other
characters appear with those characters replaced by spaces, and the overview tool returns exact
names. The command passes no model, sandbox, approval, or full-access option. A connected board's
worker `dangerFullAccess` setting does not apply.

Board context reaches Codex in three ways:

* The developer instructions name the selected board, its short link, the workflow file name, the
  workflow list roles, and the default list for new cards. They state that there is no current card.
* The MCP initialize result carries the same context with exact names as server instructions.
* `trello_board_overview` returns the open lists, role tags, default list, and allowed operations.

The board tools are `trello_board_overview`, `trello_list_cards`, `trello_get_card`,
`trello_create_card`, `trello_update_card`, `trello_move_card`, `trello_add_card_comment`,
`trello_set_card_checklist_item`, and `trello_archive_card`. They use `list_name` and accept a card
id, short link, or card URL. Every card tool loads the card first and refuses it with
`card_not_on_selected_board` unless its board id matches the selected board. The current-card tools
from [ADR 0003](0003-scoped-trello-handoff-tools.md) are not exposed because a board session has no
current card.

`trello_tools` applies to the board tools as follows. One check decides both advertisement and
execution:

* `enabled: false` withholds every board tool.
* `allow_writes: false` leaves only the three read tools.
* `allow_comments` and `allow_checklists` gate those writes.
* Moves use the shared `TrelloMoveTargets` allowlist resolution, the same as
  `trello_move_current_card`.
* Creating and updating cards needs `allow_writes`. A new card goes to the named open list, or to
  the workflow's single queue list when the request names none. With several queue lists the tool
  fails with `list_name_required` and Codex asks the user.

The archive safeguard is a title confirmation. `trello_archive_card` needs `allow_writes` and a
`confirm_title` equal to the card's current title, the developer instructions tell Codex to ask the
user first, and the MCP annotation marks the tool destructive so Codex can ask for approval. Trello
keeps archived cards and can restore them. No delete tool exists, so `allow_destructive_operations`
has nothing to gate.

The Codex child environment starts from the command's environment without `TRELLO_API_KEY`,
`TRELLO_API_TOKEN`, any other variable that the workflow names as its credential source, and
`SYMPHONY_TRELLO_DOTENV`. Codex keeps its own login. No Codex configuration file is written.

The command waits for Codex and exits with Codex's exit status. When the waiting thread is
interrupted, or the JVM shuts down because a signal reached only Symphony, the command asks Codex to
exit, forces it after five seconds, and stops Codex's remaining child processes. The MCP server
closes when the command returns. The design starts no helper process and writes no temporary file.

### Consequences

* Good, because Codex never receives the Trello key or token, so they cannot appear in its
  arguments, environment, conversation, logs, or configuration.
* Good, because board scoping, `trello_tools` policy, and the move allowlist are enforced in Java
  before any Trello request, and tests prove it with the synthetic Trello board.
* Good, because the user keeps the normal Codex TUI with their own login, sandbox, approvals, model,
  and working directory.
* Good, because the server lives and dies with the `symphony-trello` process, so cleanup is closing
  one socket.
* Bad, because the isolation keeps credentials out of Codex but is not an operating-system boundary.
  Codex runs as the same user and could read the credential file if its sandbox allows it. The
  developer instructions forbid searching for credentials, and the user's Codex sandbox decides what
  Codex can read.
* Bad, because the bearer token sits in the Codex environment, so Codex's own commands could read it
  and call the board tools directly. The tools give the same access Codex already has, only for the
  selected board, and only while the session runs.
* Bad, because Codex runs in embedded mode when started with `-c` overrides. It prints a startup
  notice about the shared background server, and in local testing it took between 3 and 21 seconds
  to exit after Ctrl+C. A plain `codex -c ...` run without Symphony took 21 seconds.
* Bad, because `codex resume` of a board session does not reconnect the board tools. A new
  `symphony-trello codex` session is needed.
* Bad, because the `-c developer_instructions=...` override replaces a `developer_instructions`
  value from the user's own Codex configuration for this session. Codex has no option that appends
  to it. [GitHub issue #789](https://github.com/martin-francois/symphony-trello/issues/789)
  tracks a way to keep both.
* Bad, because the command starts `codex` by name. On Windows, Java finds only `codex.exe`, so an
  npm-installed `codex.cmd` shim is not found, the same as for the existing `codex login` step.
  [GitHub issue #788](https://github.com/martin-francois/symphony-trello/issues/788)
  tracks launching the shim. The Windows behavior is not verified yet.
* Neutral, because Codex asks for approval of write tools according to the user's Codex approval
  settings.

### Confirmation

* `BoardSessionToolsTest` covers advertisement and refusal per `trello_tools` setting, board reads,
  card creation with explicit, default, missing, closed, and duplicate lists, updates, moves inside
  and outside the allowlist, comments, checklist items, the archive title confirmation, cards on
  another board, card reference parsing, and credential-free tool output.
* `BoardSessionMcpServerTest` covers protocol negotiation, tool annotations, tool calls, batches,
  notifications, bearer-token and `Origin` rejection, the missing event stream, oversized requests,
  and port release on close.
* `BoardSessionInstructionsTest` checks that the command-line instructions contain only safe
  characters and name the board and list roles.
* `CodexBoardSessionTest` starts a fake Codex executable and covers every selector form, the picker,
  cancellation, skipped workflows, non-interactive rejection, missing Codex, missing Codex login,
  credential precedence, missing credentials, the exact Codex arguments, terminal inheritance,
  exit-code propagation, credential absence from arguments, environment, MCP responses, and output,
  and cleanup after exit and interruption.
* `ArchitectureTest` keeps `setup` free of `agent` and keeps the new `boardsession` package free of
  cycles.

## Pros and Cons of the Options

### Normal Codex CLI with an ephemeral board-scoped MCP server inside the symphony-trello process

`symphony-trello` hosts a streamable HTTP MCP server on a loopback port for the lifetime of the
session and tells Codex about it with `-c mcp_servers.<name>.url` and `bearer_token_env_var`.

* Good, because the credentials stay in the JVM that already resolved them.
* Good, because the standard Codex TUI and runtime MCP configuration do the rest.
* Good, because the JDK HTTP server and Jackson cover the small MCP subset needed: initialize,
  ping, tools/list, and tools/call.
* Bad, because the project owns a small MCP server implementation and must follow protocol changes
  that affect those four methods.

### The same server built on the MCP Java SDK

The official MCP Java SDK (MIT license, release 2.0.1 in August 2026, more than 3,000 GitHub stars)
provides the protocol classes and server transports.

* Good, because the SDK follows MCP protocol changes, so the project would not track them itself.
* Bad, because its streamable HTTP server transports are servlet providers. The command would need
  an embedded servlet container such as Jetty or Tomcat, which this project does not use, plus
  Reactor, only to answer four JSON-RPC methods.
* Bad, because its stdio transport would put the server in a second process, with the same problems
  as the stdio option below.

### The same server built on the Quarkus MCP Server extension

The Quarkiverse Quarkus MCP Server extension (Apache-2.0 license, release 2.0.2 in September 2026,
about 200 GitHub stars) declares MCP tools as CDI beans served by Quarkus HTTP.

* Good, because the project already uses Quarkus for the worker runtime.
* Bad, because `symphony-trello` commands run as a plain picocli main without booting Quarkus. The
  session would have to boot a Quarkus application inside the CLI and keep its HTTP port and
  configuration apart from the worker's `server.port`.
* Bad, because the extension would also be served by every worker unless the build split the
  runtimes.

### Normal Codex CLI with a stdio MCP server that Codex starts

Codex launches a hidden `symphony-trello` subcommand as a stdio MCP server.

* Good, because stdio is the most common MCP transport.
* Bad, because Codex passes only a small default environment to stdio servers. The server would have
  to read the credential file itself, or Codex would need the credentials in its own environment to
  forward them, which defeats the isolation.
* Bad, because a second JVM starts inside the session, so startup is slower and cleanup depends on
  Codex stopping its child.

### Custom interactive client on top of Codex app-server

Symphony drives `codex app-server` and renders its own terminal UI, reusing the dynamic tools that
workers already use.

* Good, because the existing app-server client and tool handler could be reused.
* Bad, because it duplicates the Codex TUI, its key handling, approvals, diffs, and resize behavior,
  and must follow every TUI change.

### Board-management subcommands run through the Codex shell tool

Symphony adds commands such as `symphony-trello card create`, and the instructions tell Codex to run
them.

* Good, because no protocol work is needed.
* Bad, because every Trello action becomes a shell command that the sandbox and approval policy must
  allow, and the subcommands need the credentials in the Codex shell environment or a file Codex can
  read.
* Bad, because tool output parsing and argument quoting move into prompts.

### Trello credentials in the Codex child environment

Symphony exports `TRELLO_API_KEY` and `TRELLO_API_TOKEN` to Codex with board instructions and lets
Codex call the Trello API directly.

* Good, because it is the smallest implementation.
* Bad, because the token, not the workflow, decides Codex's Trello access, so `trello_tools` and
  board scoping become instructions that Codex could ignore.
* Bad, because the secrets can reach command output, logs, and the conversation.

## More Information

The command name `codex` follows the issue proposal. It names the tool the user gets, matches
`symphony-trello start` and `status` style, and leaves room for other session types later.

The MCP server echoes the client's protocol version when it is one of 2025-11-25, 2025-06-18,
2025-03-26, or 2024-11-05, and otherwise answers with 2025-11-25. Revisit this ADR when Codex adds a
supported way to pass instructions from a file or to attach an MCP server without command-line
configuration overrides, or when the MCP methods used here change.
