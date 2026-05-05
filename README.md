# Symphony Trello Java

Symphony Trello Java is a Quarkus daemon that turns Trello cards into isolated Codex work sessions.
It polls a configured Trello board, creates one deterministic workspace per card, renders your
repository-owned `WORKFLOW.md` prompt, and runs `codex app-server` in that card's workspace.

The service is useful when you want Trello to become a lightweight control surface for parallel
software work: one place to capture tasks, see what is ready for Codex, track what is in review, and
resume context across multiple projects without hand-running scripts for every card. It also works
well when an existing Trello board already represents engineering work. Symphony stays deliberately
narrow: it schedules, isolates, observes, and retries work. Card handoff behavior belongs in
[`WORKFLOW.md`](#workflow-contract) and in the tools available to Codex.

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

- Java 25 LTS.
- The Maven wrapper in this repository.
- Codex CLI with `codex app-server`.
- A Trello API key/token. If you do not have one yet, follow the Trello Setup section below in
  order.

If you already have Trello credentials and a configured [`WORKFLOW.md`](#workflow-contract), set
credentials and start the service:

```bash
export TRELLO_API_KEY=...
export TRELLO_API_TOKEN=...
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

The setup guide below is meant to be followed in order. First create the Trello Workspace and API
credentials in the browser. After that, choose one of the two board setup paths:

- **Create the recommended board**: best when you are new to Trello or want Symphony to create a
  clean `Inbox` -> `Ready for Codex` -> `Review` -> `Done` board for you.
- **Import an existing board**: best when you already have a Trello board and want Symphony to write
  a starter `WORKFLOW.md` for it.

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

3. Open the [Trello apps administration page](https://trello.com/power-ups/admin). Atlassian calls
   this the App Admin Portal; use its
   [Managing Apps](https://developer.atlassian.com/cloud/trello/guides/power-ups/managing-apps/)
   guide if the page layout has changed.
4. If Trello asks you to accept or complete a developer agreement before creating an app, complete
   that browser flow. Symphony cannot automate that part.
5. Click `New` to create a new app/admin entry for the Workspace.
6. Fill the required fields with clear, recognizable values:

   - `Name`: `Symphony Local Automation`
   - `Workspace`: the Workspace you created or chose above
   - `Email` / `Support Email`: an email you control
   - `Author`: your name or your team name
   - `iframe Connector URL`: leave this blank if Trello allows it. Symphony only needs REST API
     credentials and does not need a board-enabled Power-Up UI. If Trello changes the form, follow
     the field help in Trello and Atlassian's
     [Managing Apps](https://developer.atlassian.com/cloud/trello/guides/power-ups/managing-apps/)
     guide.

7. Create the app/admin entry.
8. Open its `API Key` tab and choose `Generate a new API Key`.
9. Copy the API key somewhere temporary. The API key identifies the app, but the token is the
    sensitive credential.
10. On the same API key page, click the `Token` link next to the key. Use Atlassian's
    [REST API introduction](https://developer.atlassian.com/cloud/trello/guides/rest-api/api-introduction/)
    if the token page has changed.
11. Review the authorization screen. For the generated handoff workflow, the token needs write
    access because Codex can add comments and move the current card to `Review`.
12. Click `Allow`.
13. Copy the generated token. Treat it like a password: it grants access as your Trello account to
    boards and Workspaces your account can access.
14. Export both values in the terminal where you will run Symphony:

```bash
export TRELLO_API_KEY=replace-with-generated-key
export TRELLO_API_TOKEN=replace-with-generated-token
```

If your token can access exactly one Workspace, the `new-board` command can select it automatically.
If it can access multiple Workspaces, use `list-workspaces` first and pass `--workspace-id`.

### Fast Path: Create The Recommended Board

Use this path when you are new to Trello or want the lowest-friction setup. Symphony cannot create the
Workspace, API key, or API token for you because Trello requires browser authorization for those
steps. After that, one command creates the board, creates the recommended lists, and writes
[`WORKFLOW.md`](#workflow-contract).

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

It also writes `WORKFLOW.md` with `Ready for Codex` as the active list, `Done` as the terminal list,
`Review` as the allowed handoff list, `./workspaces` as the local workspace root, and
`max_concurrent_agents: 1`.

If `WORKFLOW.md` already exists, the command stops instead of overwriting it. Pass `--force` only when
you intentionally want to replace the file:

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

Use this path when a Trello board already exists but you want Symphony to write the starter
[`WORKFLOW.md`](#workflow-contract) for you.

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

- `--workflow PATH`: write a workflow somewhere other than `WORKFLOW.md`.
- `--workspace-root PATH`: set the generated `workspace.root`.
- `--max-agents N`: set the initial `agent.max_concurrent_agents`.
- `--force`: overwrite an existing workflow file.
- `--key` and `--token`: pass credentials directly instead of using environment variables.
- `--workspace-id ID`: for `new-board`, create the board in a specific Trello Workspace.

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

Start with `max_concurrent_agents: 1`. Raise it only after one-card-at-a-time runs are routine and
predictable.

## Workflow Contract

Symphony reads `WORKFLOW.md` from the working directory unless `SYMPHONY_WORKFLOW_PATH` points to a
different file. [`WORKFLOW.example.md`](WORKFLOW.example.md) contains a complete starter. YAML front
matter configures runtime behavior. The Markdown body becomes the prompt template for the card.

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
- `TRELLO_API_KEY` and `TRELLO_API_TOKEN`: canonical Trello credentials.

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
./mvnw test
./mvnw spotless:check
./mvnw package
```

The test suite is deterministic and does not call Trello. Real Trello smoke testing is intentionally
environment-dependent and should use an isolated board/card. See [CONTRIBUTING.md](CONTRIBUTING.md)
for the full contributor checklist and [AGENTS.md](AGENTS.md) for repository-local AI agent
instructions.
