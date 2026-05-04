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
- Trello API key/token with read access to the configured board.

Create a `WORKFLOW.md` from `WORKFLOW.example.md`, then set credentials:

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
