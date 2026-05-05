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
and maps it to Trello boards, columns, and cards.

## Table of Contents

- [Current Capabilities](#current-capabilities)
- [Quick Start](#quick-start)
- [Trello Setup](#trello-setup)
- [Workflow Contract](#workflow-contract)
- [Advanced Configuration](#advanced-configuration)
- [Operations](#operations)
- [Safety Posture](#safety-posture)
- [Production Deployment](#production-deployment)
- [Build and Test](#build-and-test)

## Current Capabilities

- Trello REST polling for active cards, terminal cards, and per-card reconciliation.
- Trello normalization for open cards, archived cards, archived Trello lists/columns, archived
  boards, labels, priority labels, due dates, positions, and ObjectId-derived creation time.
- Dynamic `WORKFLOW.md` reload with last-known-good behavior after invalid edits.
- Strict prompt rendering with `card`, `issue`, and `attempt` variables.
- Per-card workspace creation, sanitization, root containment checks, and lifecycle hooks.
- Codex app-server subprocess integration over newline-delimited JSON-RPC.
- Scoped Trello handoff tools for Codex to add a comment to the current card and move that card to
  configured board-local review columns.
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
- **Columns** are the lanes on the board. Trello's API calls them lists, so `WORKFLOW.md` and tool
  fields use names like `active_list_ids`, `allowed_move_list_names`, `list_id`, and `list_name`.

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
    Workspaces. Trello uses "lists" there for board columns.
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
Codex` -> `In Progress` -> `Blocked` -> `Review` -> `Done` board for you. One command creates the
board, creates the recommended columns, and writes a workflow file for that board.

Now create the board and workflow:

```bash
./mvnw -q exec:java -Dexec.args='new-board --name "Symphony Work Queue"'
```

If your token can access exactly one Workspace, Symphony uses it automatically. If your token can
access multiple Workspaces, the command stops and asks for `--workspace-id`. Show the available ids:

```bash
./mvnw -q exec:java -Dexec.args='list-workspaces'
./mvnw -q exec:java -Dexec.args='new-board --name "Symphony Work Queue" --workspace-id workspace-id-from-list-workspaces'
```

The command creates this Trello board layout:

1. `Inbox`
2. `Ready for Codex`
3. `In Progress`
4. `Blocked`
5. `Review`
6. `Done`

It also writes a workflow with `Ready for Codex` and `In Progress` as active columns, `Done` as the
terminal column, `In Progress`, `Review`, and `Blocked` as allowed move columns, `./workspaces` as
the local workspace directory, and `max_concurrent_agents: 1`. With that default, Symphony starts one
card at a time from this board.

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
4. Codex moves the card to `In Progress` first, so the board shows that the card was picked up.
5. Codex works in a local workspace, adds a Trello comment with its summary and verification notes,
   and moves the card to `Review` when the prompt-defined work is ready for human review.
6. If Codex cannot safely finish the work, it adds a blocker comment and moves the card to
   `Blocked` so the problem is visible from the board.
7. A human reviews the code and the Trello comment, then moves the card to `Done` after acceptance.

### Fast Path: Import An Existing Board

Use this path when you already have a Trello board and want Symphony to write a starter
[`WORKFLOW.md`](#workflow-contract) for that board.

1. Copy the board short link from the board URL.
   In `https://trello.com/b/abc123/my-board`, use `abc123`.
2. Run the import command:

```bash
./mvnw -q exec:java -Dexec.args='import-board --board abc123 --active "Ready for Codex" --in-progress "In Progress" --terminal Done --blocked Blocked'
```

You may omit `--active` when the board already has a column named `Ready for Codex`. You may omit
`--in-progress` when the board already has a column named `In Progress`. You may omit `--terminal`
when the board already has a column named `Done`. You may omit `--blocked` when the board already has
a column named `Blocked`. If your board has no in-progress column, Codex leaves picked-up cards in
the active column until it moves them to review or blocked. If your board has no blocked column,
import falls back to `Review` when that column exists, so blocked cards still leave the active column.
Create a blocked column or pass
`--blocked` when you want blocked work separated from reviewable work.

For an existing team board, be deliberate about `--active`: every open card in that column is eligible
for Codex work. A conservative import starts with a new column named `Ready for Codex` and only moves
cards there after they have a clear title, useful description, and acceptance criteria.

When the imported board has a column named `Review`, the starter workflow allows Codex to move
reviewable work there. If there is no obvious review column, the generated workflow keeps Trello writes
disabled until you choose one. Do not run a write-disabled workflow with blocked cards left in
`Ready for Codex` unless you plan to move them manually; they can be picked up again.

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
- `--in-progress NAME`: during `import-board`, choose the Trello column where Codex should move a
  card immediately after it is picked up. If you omit it, import uses `In Progress` when the board has
  that column. If no in-progress column is configured, the card stays in the active column while
  Codex works.
- `--no-in-progress`: during `import-board`, do not configure pickup moves even when the board has an
  `In Progress` column.
- `--blocked NAME`: during `import-board`, choose the Trello column where Codex should move cards it
  cannot safely finish. If you omit it, import uses a column named `Blocked` when the board has one.

### Option A: Reuse An Existing Board

Use this manual path when your team already has a Trello board for engineering work and you prefer to
write [`WORKFLOW.md`](#workflow-contract) yourself.

1. Pick the board Symphony should poll.
2. Choose one or two column names that mean "Codex may work on this now".
   A low-risk default is a single column named `Ready for Codex`.
3. Choose an optional in-progress column, such as `In Progress`, if you want Trello to show when
   Codex has picked up a card.
4. Choose terminal column names that mean "never run Codex for this card again".
   A common default is `Done`.
5. Choose a non-active column for blocked work, such as `Blocked`. If your board does not have one,
   use your review column so blocked cards still leave the active column.
6. Copy the board short link from the board URL.
   In `https://trello.com/b/abc123/my-board`, the `board_id` value can be `abc123`.
7. Create [`WORKFLOW.md`](#workflow-contract) and set `tracker.board_id`, `tracker.active_states`,
   `tracker.terminal_states`, and the handoff column names to match your board.

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
    - In Progress
  blocker_enforced_states:
    - Ready for Codex
    - In Progress
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
    - In Progress
    - Review
    - Blocked
  allow_comments: true
  allow_checklists: false
  allow_url_attachments: false
codex:
  command: codex app-server
---
\# Trello Card

Work on {{ card.identifier }}: {{ card.title }}.

## Description

{{ card.description }}

Use the current workspace by default. If the Trello card names a specific local path or project
checkout, inspect that path instead. If that path is inaccessible, treat it as a blocker and follow
the filesystem access blocker instructions below.

If the card is in "Ready for Codex", immediately call trello_move_current_card with list_name
"In Progress" before implementation work. If the card is already in "In Progress", continue the
existing execution flow.

When the work is ready for human review, call trello_add_comment with a concise summary and
verification notes, then call trello_move_current_card with list_name "Review". If the work is
blocked or unsafe to hand off, add a Trello comment explaining the blocker, then call
trello_move_current_card with list_name "Blocked". If the blocker is a local filesystem access
problem, the Trello comment must include the inaccessible path, why it is inaccessible, that deployed
Symphony exposes only managed workspaces and explicitly allowed project roots by default, that
accessible files are available in the current per-card workspace shown by `pwd`, and that an operator
can relax access with the systemd or Ansible allowed-project-roots deployment setting.

Card URL: {{ card.url }}
```

In this workflow config, `allowed_move_list_names` and the tool argument `list_name` use Trello's API
term for board columns.

Operationally, use the board like this:

1. Put new ideas wherever your team normally triages work.
2. Move a card to `Ready for Codex` only when the title and description are clear enough for an
   engineer to start.
3. Watch the card move to `In Progress` after Codex picks it up.
4. Watch Symphony at `http://127.0.0.1:8080/`; see [Operations](#operations) for the available
   status endpoints.
5. Review the card after Codex moves it to `Review` or `Blocked`.
6. Move the card out of active columns if you want to pause or prevent further retries.
7. Move the card to `Done` when the generated work has been reviewed and accepted.

### Option B: Create A New Beginner-Friendly Board

Use this manual path when you want a clean board designed for Symphony from the start but do not want
the setup command to create it for you.

Create a board named `Symphony Work Queue` and add these columns in this order:

1. `Inbox`
2. `Ready for Codex`
3. `In Progress`
4. `Blocked`
5. `Review`
6. `Done`

Recommended meaning:

- `Inbox`: rough tasks, ideas, or incomplete cards. Symphony ignores this column.
- `Ready for Codex`: cards that are ready to run. Symphony polls this column.
- `In Progress`: cards Codex has picked up. Symphony also treats this as active so a restart or
  retry can continue the card.
- `Blocked`: cards Codex could not safely finish. Symphony ignores this column.
- `Review`: work produced by Codex that needs human review. Symphony ignores this column.
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
    - In Progress
  blocker_enforced_states:
    - Ready for Codex
    - In Progress
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
    - In Progress
    - Review
    - Blocked
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

## Description

{{ card.description }}

Read the Trello description carefully, inspect the repository, make the smallest maintainable change,
run relevant verification, and leave the workspace in a reviewable state.
Use the current workspace by default. If the Trello card names a specific local path or project
checkout, inspect that path instead. If that path is inaccessible, treat it as a blocker and follow
the filesystem access blocker instructions below.

If the card is in "Ready for Codex", immediately call trello_move_current_card with list_name
"In Progress" before implementation work. If the card is already in "In Progress", continue the
existing execution flow.

When the work is ready for human review, call trello_add_comment with a concise summary and
verification notes, then call trello_move_current_card with list_name "Review". If the work is
blocked or unsafe to hand off, add a Trello comment explaining the blocker, then call
trello_move_current_card with list_name "Blocked". If the blocker is a local filesystem access
problem, the Trello comment must include the inaccessible path, why it is inaccessible, that deployed
Symphony exposes only managed workspaces and explicitly allowed project roots by default, that
accessible files are available in the current per-card workspace shown by `pwd`, and that an operator
can relax access with the systemd or Ansible allowed-project-roots deployment setting.

Card URL: {{ card.url }}
```

In this workflow config, `allowed_move_list_names` and the tool argument `list_name` use Trello's API
term for board columns.

Start with `max_concurrent_agents: 1`. If two cards are in an active column such as `Ready for Codex`,
that default makes Symphony run one card and leave the other waiting until a slot is free. Raising
the value to `2` lets two cards from the same configured board run at the same time; raising it to
`N` allows up to `N` simultaneous Codex sessions for that process. Raise it only after
one-card-at-a-time runs are routine and predictable.

Within the available slots, Symphony starts cards in a predictable order. Priority labels run first
when you use them. Otherwise, it follows the configured active-column order, then the order of cards
in the Trello column from top to bottom. If there is still a tie, older cards run first.

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
- `trello_move_current_card`: move the current card to a configured board-local column such as
  `In Progress`, `Review`, or `Blocked`.

The move tool uses argument names such as `list_name` and `list_id` because Trello's API calls board
columns lists.

Symphony advertises those tools when `trello_tools.enabled=true` and
`trello_tools.allow_writes=true`. For a read-only scheduler deployment, set
`trello_tools.allow_writes: false` and move cards manually.

If the API token is read-only or Trello rejects writes, Codex still runs, but handoff tool calls fail
and the failures are visible in the Codex session events.

To move cards to workflow columns with different names, set `trello_tools.allowed_move_list_names` to
those allowed column names and update the pickup and final handoff instructions in
[`WORKFLOW.md`](#workflow-contract) to match them. Do not tell Codex to leave blocked cards in an
active column such as `Ready for Codex` or `In Progress`; Symphony may treat the card as still
eligible and run it again.

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
- `SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS`: path-separated roots that are added to Codex's
  `workspaceWrite` sandbox policy.
- `SYMPHONY_CODEX_DANGER_FULL_ACCESS`: set `true` only with an intentionally broad deployment
  sandbox.

For local runs, the same names can be placed in ignored `.env`; real environment variables take
precedence.

For server deployments, workflow values can also read root-managed secret files with `file:/path`.
The deployment guides use `file:$CREDENTIALS_DIRECTORY/trello-api-key` and
`file:$CREDENTIALS_DIRECTORY/trello-api-token` with systemd credentials so Trello secrets are not
placed in the service environment.

## Safety Posture

This implementation targets trusted automation environments by default. Workspace boundaries are
enforced, hooks run inside the per-card workspace, and Trello credentials are injected into HTTP
requests rather than prompts. Hooks and Codex still execute trusted local code, so production
deployments should use a dedicated OS user, a dedicated workspace volume, narrowly scoped Trello
credentials, and Codex approval/sandbox settings appropriate to the board's trust level.

Approval and sandbox values are passed through from [`WORKFLOW.md`](#workflow-contract) to the
installed Codex app-server schema. When additional writable roots are configured, this implementation
adds them to a `workspaceWrite` turn sandbox policy unless the workflow already uses
`dangerFullAccess`. User-input and unsupported dynamic tool requests are answered without waiting
indefinitely.

## Production Deployment

For a server deployment with one or more workflow files, use the systemd guide in
[docs/deployment.md](docs/deployment.md). For repeatable server setup with Ansible Vault secrets and
declared workflow files, use [docs/ansible-deployment.md](docs/ansible-deployment.md).

## Build and Test

```bash
./mvnw spotless:check
./mvnw verify
```

`verify` runs PMD's narrow source check, the deterministic test suite, ArchUnit architecture checks,
the application build, and the JaCoCo coverage gate. The ArchUnit checks reject circular dependencies
between production top-level packages. `verify` also fails if line coverage drops below 80%. The test
suite does not call Trello. Real Trello smoke testing is intentionally environment-dependent and
should use disposable boards/cards; see [docs/live-e2e.md](docs/live-e2e.md). See
[CONTRIBUTING.md](CONTRIBUTING.md) for the full contributor checklist and [AGENTS.md](AGENTS.md) for
repository-local AI agent instructions.
