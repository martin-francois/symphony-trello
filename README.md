# Symphony Trello Java

Symphony Trello Java is a Quarkus daemon that turns Trello cards into isolated Codex work sessions.
It polls a configured Trello board, creates one deterministic workspace per card, renders your
repository-owned `WORKFLOW.md` prompt, and runs `codex app-server` in that card's workspace.

The service is useful when a Trello board already represents engineering work and you want repeatable
agent execution without hand-running scripts for every card. Symphony stays deliberately narrow: it
schedules, isolates, observes, and retries work. Card handoff behavior belongs in `WORKFLOW.md` and in
the tools available to Codex.

## Current Capabilities

- Trello REST polling for active cards, terminal cards, and per-card reconciliation.
- Trello normalization for open cards, archived cards, archived lists, archived boards, labels,
  priority labels, due dates, positions, and ObjectId-derived creation time.
- Dynamic `WORKFLOW.md` reload with last-known-good behavior after invalid edits.
- Strict prompt rendering with `card`, `issue`, and `attempt` variables.
- Per-card workspace creation, sanitization, root containment checks, and lifecycle hooks.
- Codex app-server subprocess integration over newline-delimited JSON-RPC.
- Single-authority in-memory orchestration state, claim-before-spawn dispatch, retries, stall checks,
  stale worker identity filtering, and terminal workspace cleanup.
- JSON and HTML status surfaces at `/api/v1/state`, `/api/v1/{card_identifier}`, `/api/v1/refresh`,
  and `/`.

The standardized `trello_rest` dynamic tool extension is documented in `SPEC.md` but is not yet
advertised to Codex by this Java implementation. Read-only scheduler deployments are supported; Trello
handoff writes currently require external tools available to the Codex runtime.

## Quick Start

Prerequisites:

- Java 25 LTS.
- The Maven wrapper in this repository.
- Codex CLI with `codex app-server`.
- A Trello board and a Trello API key/token with read access to that board.

If you already have Trello credentials and a board, create a `WORKFLOW.md` from
`WORKFLOW.example.md`, set credentials, and start the service:

```bash
export TRELLO_API_KEY=...
export TRELLO_API_TOKEN=...
./mvnw quarkus:dev
```

By default the status page binds to `127.0.0.1:8080`. Use `SYMPHONY_HTTP_PORT=0` for an ephemeral
test port, configure `server.port` in `WORKFLOW.md`, or pass `--port` for local development.
Command-line `--port` wins over `server.port`.

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

Official Trello references if the UI has moved since this README was written:

- Trello REST API introduction: <https://developer.atlassian.com/cloud/trello/guides/rest-api/api-introduction/>
- Trello board/list basics: <https://support.atlassian.com/trello/docs/adding-lists-to-a-board/>

Symphony reads cards, creates local Codex workspaces, and runs Codex. It does not currently move cards
or write Trello comments by itself. Start with read-only automation: move a card into a configured
active list when you want Codex to work on it, then move it out of that active list when the work is
ready for human review or done.

### Option A: Reuse An Existing Board

Use this path when your team already has a Trello board for engineering work.

1. Pick the board Symphony should poll.
2. Choose one or two list names that mean "Codex may work on this now".
   A low-risk default is a single list named `Ready for Codex`.
3. Choose terminal list names that mean "never run Codex for this card again".
   A common default is `Done`.
4. Copy the board short link from the board URL.
   In `https://trello.com/b/abc123/my-board`, the `board_id` value can be `abc123`.
5. Create `WORKFLOW.md` and set `tracker.board_id`, `tracker.active_states`, and
   `tracker.terminal_states` to match your board.

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
codex:
  command: codex app-server
---
# Trello Card

Work on {{ card.identifier }}: {{ card.title }}.

Card URL: {{ card.url }}
```

Operationally, use the board like this:

1. Put new ideas wherever your team normally triages work.
2. Move a card to `Ready for Codex` only when the title and description are clear enough for an
   engineer to start.
3. Watch Symphony at `http://127.0.0.1:8080/`.
4. Move the card to `Done` when the generated work has been reviewed and accepted.
5. Move the card out of `Ready for Codex` if you want to pause or prevent further retries.

### Option B: Create A New Beginner-Friendly Board

Use this path when you want a clean board designed for Symphony from the start.

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

Use this `WORKFLOW.md` starter for the new board:

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
agent:
  max_concurrent_agents: 1
codex:
  command: codex app-server
  approval_policy: never
  turn_timeout_ms: 3600000
  read_timeout_ms: 5000
  stall_timeout_ms: 300000
---
# Trello Card

You are working on {{ card.identifier }}: {{ card.title }}.

Read the Trello description carefully, inspect the repository, make the smallest maintainable change,
run relevant verification, and leave the workspace in a reviewable state.

Card URL: {{ card.url }}
```

Start with `max_concurrent_agents: 1`. Raise it only after one-card-at-a-time runs are boring and
predictable.

### Create A Trello Workspace

You need a Trello Workspace before creating the Power-Up that provides the API key.

1. Sign in to Trello.
2. Open the left sidebar and choose the workspace switcher or workspace area.
3. Create a new Workspace if you do not already have one.
4. Give it a clear name, for example `Symphony Automation`.
5. Create or move the Symphony board into that Workspace.

### Create The Trello API Key And Token

Trello API access is created through a custom Power-Up:

1. Open Trello's Power-Ups admin page: `https://trello.com/power-ups/admin`.
2. Create a new Power-Up in the Workspace that contains your Symphony board.
3. Name it something explicit, for example `Symphony Local Automation`.
4. You do not need to enable this Power-Up on the board for Symphony's read-only polling use case.
5. Open the Power-Up's API key area and generate an API key.
6. Generate an API token for your Trello account from that API key page.
7. Export both values before starting Symphony:

```bash
export TRELLO_API_KEY=replace-with-generated-key
export TRELLO_API_TOKEN=replace-with-generated-token
```

Treat the token like a password. If it is exposed, revoke it in Trello and generate a new one.

## Workflow Contract

Symphony reads `WORKFLOW.md` from the working directory unless `SYMPHONY_WORKFLOW_PATH` points to a
different file. YAML front matter configures runtime behavior. The Markdown body becomes the prompt
template for the card.

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
# Task

Work on Trello card {{ card.identifier }}: {{ card.title }}.
```

Unknown template variables fail the affected attempt, then the orchestrator applies normal retry
behavior. This is intentional; typo-tolerant prompts hide broken automation.

## Operations

Useful endpoints:

- `GET /` returns a small human-readable status page.
- `GET /api/v1/state` returns running sessions, retry queue, token totals, and rate-limit data when
  reported by Codex.
- `GET /api/v1/{card_identifier}` returns card-specific runtime details.
- `POST /api/v1/refresh` queues an immediate poll/reconciliation cycle.

`WORKFLOW.md` is watched for changes and also checked defensively on each scheduler tick. Invalid
reloads are logged and the last known good configuration remains active.

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

Approval and sandbox values are passed through from `WORKFLOW.md` to the installed Codex app-server
schema. User-input and unsupported dynamic tool requests are answered without waiting indefinitely.

## Build and Test

```bash
./mvnw test
./mvnw spotless:check
./mvnw package
```

The test suite is deterministic and does not call Trello. Real Trello smoke testing is intentionally
environment-dependent and should use an isolated board/card.
