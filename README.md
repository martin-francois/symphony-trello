# Symphony for Trello

Symphony for Trello lets Codex work from Trello cards. Use it when prompts and terminal history are
not enough, and you want a clear board that shows what Codex should do, what is running, and what is
ready for review.

Use it when you want to:

- Plan Codex work in Trello.
- Reuse an existing engineering board.
- Create a simple board just for Codex tasks.
- Let Codex pick up ready cards without starting each one by hand.
- Keep each card in its own local workspace.
- Have Codex comment on the card and move it to review when it is done.
- Check running and retrying work from a status page.

Trello is still where you plan and review work. Codex still writes the code. Symphony connects them
so the same workflow can run again and again.

You can use it with one board, or run it for several boards at the same time.

Symphony for Trello is a variant of [OpenAI's Symphony](https://github.com/openai/symphony) adapted
for Trello. The original Symphony spec uses Linear; this project keeps the same orchestration idea
and maps it to Trello boards, lists, and cards.

## Table of Contents

- [Current Capabilities](#current-capabilities)
- [Quick Start](#quick-start)
- [Trello Setup](#trello-setup)
- [Workflow Contract](#workflow-contract)
- [Advanced Configuration](#advanced-configuration)
- [Operations](#operations)
- [Safety Posture](#safety-posture)
- [Build and Test](#build-and-test)

## Current Capabilities

- Trello REST polling for active cards, terminal cards, and per-card reconciliation.
- Trello normalization for open cards, archived cards, archived lists, archived boards, labels,
  priority labels, due dates, positions, and ObjectId-derived creation time.
- Dynamic `WORKFLOW.md` reload with last-known-good behavior after invalid edits.
- Strict prompt rendering with `card`, `issue`, and `attempt` variables.
- Per-card workspace creation, sanitization, root containment checks, and lifecycle hooks.
- Codex app-server subprocess integration over newline-delimited JSON-RPC.
- Scoped Trello handoff tools for Codex to add a comment to the current card and move that card to
  configured board-local review lists.
- Single-authority in-memory orchestration state, claim-before-spawn dispatch, retries, stall checks,
  stale worker identity filtering, and terminal workspace cleanup.
- JSON and HTML status surfaces at `/api/v1/state`, `/api/v1/{card_identifier}`, `/api/v1/refresh`,
  and `/`.

## Quick Start

Prerequisites:

- JDK 25 available on `PATH`. The project uses Azul Zulu 25 through SDKMAN for local development.
- [Codex CLI installed](https://help.openai.com/en/articles/11096431-openai-codex-ligetting-started)
  and signed in.

By default, Symphony starts Codex by running `codex app-server`. That works when `codex` is on the
`PATH` for the user that starts Symphony. If not, set `codex.command` in
[`WORKFLOW.md`](#workflow-contract) to the full path or to a wrapper script.

Start with [Trello Setup](#trello-setup) if you do not yet have Trello credentials or
[`WORKFLOW.md`](#workflow-contract). That section walks you through creating the Trello key/token,
then either creating the recommended board or importing an existing board.

If you already have the Trello key/token but no `WORKFLOW.md`, skip the browser credential steps and
use one of the board setup paths in [Trello Setup](#trello-setup).

If you already have both Trello credentials and `WORKFLOW.md`, put the credentials in an ignored
project-root `.env` file:

```bash
cp .env.example .env
chmod 600 .env
```

Fill `.env` with:

```properties
TRELLO_API_KEY=replace-with-generated-key
TRELLO_API_TOKEN=replace-with-generated-token
```

Exported environment variables with the same names also work and take precedence over `.env`.

Start the service:

```bash
./mvnw quarkus:dev
```

By default the [status page](#operations) binds to `127.0.0.1:8080`. Use `SYMPHONY_HTTP_PORT=0` for
an ephemeral test port, configure `server.port` in [`WORKFLOW.md`](#workflow-contract), or pass
`--port` for local development. Command-line `--port` wins over `server.port`.

Packaged runs also accept a positional workflow path and `--port`:

```bash
./mvnw package
java -jar target/quarkus-app/quarkus-run.jar ./WORKFLOW.md --port 8081
```

## Trello Setup

Trello has three concepts that matter for Symphony:

- A **Workspace** groups boards and is also where Trello lets you create a custom Power-Up for API
  access.
- A **Board** is the project or queue Symphony polls.
- **Lists** are the columns on the board. Symphony treats configured list names as states.

Symphony reads cards, creates local Codex workspaces, and runs Codex. The generated workflow lets
Codex leave a Trello comment and move the card to `Review` when the prompt-defined work is ready for
human review.

Start with the browser setup below. Those steps create the Trello credentials used by both board
setup paths.

### One-Time Browser Setup: Workspace, API Key, Token

Complete these browser steps once before creating the recommended board or importing an existing
board. Symphony can create boards and write `WORKFLOW.md`, but Trello requires you to create the
Workspace and authorize the API token in the browser.

1. Sign in to Trello in your browser.
2. [Create a Workspace](https://support.atlassian.com/trello/docs/creating-a-new-workspace/) if you
   do not already have one that you want to use with Symphony.

   The Workspace owns the Trello app/admin entry that provides the API key. If you create a new
   board with Symphony, the board is created in this Workspace. If you import an existing board, the
   token you generate here must be able to access that board. It is fine to use a Workspace that also
   contains non-Symphony boards; Symphony only polls the board configured in `WORKFLOW.md`. Create a
   separate Workspace when you want cleaner separation of automation credentials, collaborators, or
   boards.

3. Open the [Trello apps administration page](https://trello.com/power-ups/admin).
4. If Trello asks you to accept or complete a developer agreement before creating an app, complete
   that browser flow.
5. Click `New` to create a new app/admin entry for the Workspace.
6. Fill the required fields with clear, recognizable values:

   - `Name`: `Symphony for Trello Automation`
   - `Workspace`: the Workspace you created or chose above
   - `Email` / `Support Contact`: an email you control
   - `Author`: your name or your team name
   - `iframe Connector URL`: leave this blank. Symphony only needs REST API credentials and does not
     need a board-enabled Power-Up UI.

7. Create the app/admin entry.
8. Open its `API Key` tab and choose `Generate a new API Key`.
9. If Trello warns that generating a key replaces the API key used for Personal Data Storage and
   GDPR compliance, continue when this is the new `Symphony for Trello Automation` app/admin entry.
   For an existing app already used elsewhere, rotating the key means those uses must be updated.
10. Copy the API key somewhere temporary. The API key identifies the app, but the token is the
    sensitive credential.
11. On the same API key page, click the `Token` link below the key.
12. Review the authorization screen. Confirm it shows `Symphony for Trello Automation`, your Trello
    account, and permissions to make comments and create or update cards, lists, boards, and
    Workspaces.
13. Click `Allow`.
14. Copy the generated token. Treat it like a password: it grants access as your Trello account to
    boards and Workspaces your account can access.
15. Save both values in the project-root `.env` file:

```properties
TRELLO_API_KEY=replace-with-generated-key
TRELLO_API_TOKEN=replace-with-generated-token
```

### Fast Path: Create The Recommended Board

Use this path when you are new to Trello or want Symphony to create a clean `Inbox` -> `Ready for
Codex` -> `Review` -> `Done` board for you. One command creates the board, creates the recommended
lists, and writes a workflow file for that board.

Now create the board and workflow:

```bash
./mvnw -q exec:java -Dexec.args='new-board --name "Symphony Work Queue"'
```

If your token can access exactly one Workspace, Symphony uses it automatically. If your token can
access multiple Workspaces, the command stops and asks for `--workspace-id`. List the available ids:

```bash
./mvnw -q exec:java -Dexec.args='list-workspaces'
./mvnw -q exec:java -Dexec.args='new-board --name "Symphony Work Queue" --workspace-id workspace-id-from-list-workspaces'
```

The command creates this Trello board layout:

1. `Inbox`
2. `Ready for Codex`
3. `Review`
4. `Done`

It also writes a workflow with `Ready for Codex` as the active list, `Done` as the terminal list,
`Review` as the allowed handoff list, `./workspaces` as the local workspace directory, and
`max_concurrent_agents: 1`. With that default, Symphony starts one card at a time from this board.

The first run writes `WORKFLOW.md`. If that file already exists and you did not pass `--workflow`,
Symphony keeps the existing file and writes a board-specific file instead. For a board named
`My Project`, the next file is `WORKFLOW.my-project.md`. If that file also exists, Symphony adds a
number, such as `WORKFLOW.my-project-2.md`.

Pass `--force` only when you intentionally want to replace the selected workflow file:

```bash
./mvnw -q exec:java -Dexec.args='new-board --name "Symphony Work Queue" --force'
```

Start Symphony after the file is generated:

```bash
./mvnw quarkus:dev
```

Use the generated board like this:

1. Put raw ideas or incomplete tasks in `Inbox`.
2. Move only cards that are ready for agent work into `Ready for Codex`.
3. Symphony starts Codex for cards in `Ready for Codex`.
4. Codex works in a local workspace, adds a Trello comment with its summary and verification notes,
   and moves the card to `Review` when the prompt-defined work is ready for human review.
5. A human reviews the code and the Trello comment, then moves the card to `Done` after acceptance.

### Fast Path: Import An Existing Board

Use this path when you already have a Trello board and want Symphony to write a starter
[`WORKFLOW.md`](#workflow-contract) for that board.

1. Copy the board short link from the board URL.
   In `https://trello.com/b/abc123/my-board`, use `abc123`.
2. Run the import command:

```bash
./mvnw -q exec:java -Dexec.args='import-board --board abc123 --active "Ready for Codex" --terminal Done'
```

You may omit `--active` when the board already has a list named `Ready for Codex`. You may omit
`--terminal` when the board already has a list named `Done`.

For an existing team board, be deliberate about `--active`: every open card in that list is eligible
for Codex work. A conservative import starts with a new list named `Ready for Codex` and only moves
cards there after they have a clear title, useful description, and acceptance criteria.

When the imported board has a list named `Review`, the starter workflow enables the same handoff
tools as the recommended new board and allows Codex to move cards there. If your existing board uses
a different review list, update the generated [`WORKFLOW.md`](#workflow-contract) to use that same
list name before starting the daemon. If there is no obvious review list, the generated workflow
keeps Trello writes disabled until you choose one.

Common setup command options:

- `--workflow PATH`: write the generated workflow file somewhere other than `WORKFLOW.md`. Use this
  when you want to choose the exact file name yourself. If that file exists, Symphony stops unless
  you also pass `--force`.
- `--workspace-root PATH`: choose where Symphony creates the local work directory for each Trello
  card. The generated workflow uses `./workspaces`; choose another path when you want those
  checkouts on a different disk or clearly separated from the repository.
- `--max-agents N`: choose how many cards from this board may run at the same time. Start with `1`
  if you want one-at-a-time review, or raise it when your machine and workflow can handle parallel
  Codex sessions.
- `--force`: replace an existing workflow file. Use this only when you are fine losing the current
  generated workflow content.
- `--key` and `--token`: pass Trello credentials directly for this one command instead of reading
  them from `.env` or environment variables.
- `--workspace-id ID`: choose the Trello Workspace for a new board when your token can access more
  than one Workspace.

### Option A: Reuse An Existing Board

Use this manual path when your team already has a Trello board for engineering work and you prefer to
write [`WORKFLOW.md`](#workflow-contract) yourself.

1. Pick the board Symphony should poll.
2. Choose one or two list names that mean "Codex may work on this now".
   A low-risk default is a single list named `Ready for Codex`.
3. Choose terminal list names that mean "never run Codex for this card again".
   A common default is `Done`.
4. Copy the board short link from the board URL.
   In `https://trello.com/b/abc123/my-board`, the `board_id` value can be `abc123`.
5. Create [`WORKFLOW.md`](#workflow-contract) and set `tracker.board_id`, `tracker.active_states`,
   and `tracker.terminal_states` to match your board.

Example for an existing board:

```markdown
---
tracker:
  kind: trello
  api_key: $TRELLO_API_KEY
  api_token: $TRELLO_API_TOKEN
  board_id: abc123
  active_states:
    - Ready for Codex
  terminal_states:
    - Done
    - Archived
    - ArchivedList
    - ArchivedBoard
    - Deleted
workspace:
  root: ./workspaces
trello_tools:
  enabled: true
  allow_writes: true
  allowed_move_list_names:
    - Review
  allow_comments: true
  allow_checklists: false
  allow_url_attachments: false
codex:
  command: codex app-server
---
\# Trello Card

Work on {{ card.identifier }}: {{ card.title }}.

When the work is ready for human review, call trello_add_comment with a concise summary and
verification notes, then call trello_move_current_card with list_name "Review".

Card URL: {{ card.url }}
```

Operationally, use the board like this:

1. Put new ideas wherever your team normally triages work.
2. Move a card to `Ready for Codex` only when the title and description are clear enough for an
   engineer to start.
3. Watch Symphony at `http://127.0.0.1:8080/`; see [Operations](#operations) for the available
   status endpoints.
4. Review the card after Codex moves it to `Review`.
5. Move the card out of `Ready for Codex` if you want to pause or prevent further retries.
6. Move the card to `Done` when the generated work has been reviewed and accepted.

### Option B: Create A New Beginner-Friendly Board

Use this manual path when you want a clean board designed for Symphony from the start but do not want
the setup command to create it for you.

Create a board named `Symphony Work Queue` and add these lists in this order:

1. `Inbox`
2. `Ready for Codex`
3. `Review`
4. `Done`

Recommended meaning:

- `Inbox`: rough tasks, ideas, or incomplete cards. Symphony ignores this list.
- `Ready for Codex`: cards that are ready to run. Symphony polls this list.
- `Review`: work produced by Codex that needs human review. Symphony ignores this list.
- `Done`: finished cards. Symphony treats this as terminal.

For each task card, use a title that reads like a pull request title, then put the useful details in
the description:

```markdown
Goal:
- What should change?

Acceptance criteria:
- What must be true when this is done?

Verification:
- Which command or manual check should pass?

Notes:
- Links, constraints, or gotchas.
```

Use this [`WORKFLOW.md`](#workflow-contract) starter for the new board:

```markdown
---
tracker:
  kind: trello
  api_key: $TRELLO_API_KEY
  api_token: $TRELLO_API_TOKEN
  board_id: replace-with-board-shortlink
  active_states:
    - Ready for Codex
  blocker_enforced_states:
    - Ready for Codex
  terminal_states:
    - Done
    - Archived
    - ArchivedList
    - ArchivedBoard
    - Deleted
workspace:
  root: ./workspaces
trello_tools:
  enabled: true
  allow_writes: true
  allowed_move_list_names:
    - Review
  allow_comments: true
  allow_checklists: false
  allow_url_attachments: false
agent:
  max_concurrent_agents: 1
codex:
  command: codex app-server
  approval_policy: never
  turn_timeout_ms: 3600000
  read_timeout_ms: 5000
  stall_timeout_ms: 300000
---
\# Trello Card

You are working on {{ card.identifier }}: {{ card.title }}.

Read the Trello description carefully, inspect the repository, make the smallest maintainable change,
run relevant verification, and leave the workspace in a reviewable state.

When the work is ready for human review, call trello_add_comment with a concise summary and
verification notes, then call trello_move_current_card with list_name "Review".

Card URL: {{ card.url }}
```

Start with `max_concurrent_agents: 1`. If two cards are in an active list such as `Ready for Codex`,
that default makes Symphony run one card and leave the other waiting until a slot is free. Raising
the value to `2` lets two cards from the same configured board run at the same time; raising it to
`N` allows up to `N` simultaneous Codex sessions for that process. Raise it only after
one-card-at-a-time runs are routine and predictable.

Within the available slots, Symphony starts cards in a predictable order. Priority labels run first
when you use them. Otherwise, it follows the configured active-list order, then the order of cards in
the Trello list from top to bottom. If there is still a tie, older cards run first.

## Workflow Contract

Symphony reads `WORKFLOW.md` from the working directory unless `SYMPHONY_WORKFLOW_PATH` points to a
different file. Each running process uses one workflow file. For Trello, that workflow contains one
`tracker.board_id`, so one process polls one Trello board.

For multiple boards or projects, create one workflow per board and start one process per workflow.
Give each process its own workflow path and HTTP port.

[`WORKFLOW.example.md`](WORKFLOW.example.md) contains a complete starter. YAML front matter
configures runtime behavior. The Markdown body becomes the prompt template for the card.

Minimum example:

```markdown
---
tracker:
  kind: trello
  api_key: $TRELLO_API_KEY
  api_token: $TRELLO_API_TOKEN
  board_id: your-board-id-or-shortlink
  active_states: [Todo, In Progress]
  terminal_states: [Done, Archived, ArchivedList, ArchivedBoard, Deleted]
workspace:
  root: ./workspaces
codex:
  command: codex app-server
---
\# Task

Work on Trello card {{ card.identifier }}: {{ card.title }}.
```

Unknown template variables fail the affected attempt, then the orchestrator applies normal retry
behavior. This is intentional; typo-tolerant prompts hide broken automation.

## Advanced Configuration

### Codex Command

Generated workflows use:

```yaml
codex:
  command: codex app-server
```

Symphony runs that value with `bash -lc` from the card workspace. Any command is acceptable if it
starts a Codex app-server on stdio for the same OS user that runs Symphony. Examples include an
absolute path such as `/opt/codex/bin/codex app-server` or a small wrapper script that sets up the
environment before starting `codex app-server`.

### Trello Write Controls

The recommended workflow gives Codex two scoped Trello handoff tools:

- `trello_add_comment`: add a comment to the current card.
- `trello_move_current_card`: move the current card to a configured board-local review list.

Symphony advertises those tools when `trello_tools.enabled=true` and
`trello_tools.allow_writes=true`. For a read-only scheduler deployment, set
`trello_tools.allow_writes: false` and move cards manually.

If the API token is read-only or Trello rejects writes, Codex still runs, but handoff tool calls fail
and the failures are visible in the Codex session events.

To move cards to a review list other than `Review`, set `trello_tools.allowed_move_list_names` to the
allowed list name and update the final handoff instruction in [`WORKFLOW.md`](#workflow-contract) to
match it.

The standardized generic `trello_rest` dynamic tool extension is documented in [SPEC.md](SPEC.md) but
is not yet advertised to Codex by this Java implementation. The Java implementation currently uses
the narrower handoff tools above.

## Operations

Useful endpoints:

- `GET /` returns a small human-readable status page.
- `GET /api/v1/state` returns running sessions, retry queue, token totals, and rate-limit data when
  reported by Codex.
- `GET /api/v1/{card_identifier}` returns card-specific runtime details.
- `POST /api/v1/refresh` queues an immediate poll/reconciliation cycle.

[`WORKFLOW.md`](#workflow-contract) is watched for changes and also checked defensively on each
scheduler tick. Invalid reloads are logged and the last known good configuration remains active.

Important environment variables:

- `SYMPHONY_WORKFLOW_PATH`: workflow file path, default `WORKFLOW.md`.
- `SYMPHONY_HTTP_PORT`: Quarkus HTTP port, default `8080`.
- `SYMPHONY_AUTOSTART`: set `false` in tests or when only using injected services.
- `TRELLO_API_KEY` and `TRELLO_API_TOKEN`: default Trello credential variable names.

For local runs, the same names can be placed in ignored `.env`; real environment variables take
precedence.

## Safety Posture

This implementation targets trusted automation environments by default. Workspace boundaries are
enforced, hooks run inside the per-card workspace, and Trello credentials are injected into HTTP
requests rather than prompts. Hooks and Codex still execute trusted local code, so production
deployments should use a dedicated OS user, a dedicated workspace volume, narrowly scoped Trello
credentials, and Codex approval/sandbox settings appropriate to the board's trust level.

Approval and sandbox values are passed through from [`WORKFLOW.md`](#workflow-contract) to the
installed Codex app-server schema. User-input and unsupported dynamic tool requests are answered
without waiting indefinitely.

## Build and Test

```bash
./mvnw spotless:check
./mvnw verify
```

`verify` runs the deterministic test suite, builds the application, generates the JaCoCo report, and
fails if line coverage drops below 80%. The test suite does not call Trello. Real Trello smoke
testing is intentionally environment-dependent and should use disposable boards/cards; see
[docs/live-e2e.md](docs/live-e2e.md). See [CONTRIBUTING.md](CONTRIBUTING.md) for the full
contributor checklist and [AGENTS.md](AGENTS.md) for repository-local AI agent instructions.
