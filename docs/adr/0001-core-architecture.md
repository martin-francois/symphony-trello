# 0001 Core Architecture

Status: Accepted

Date: 2026-05-04

## Context

The adapted Symphony specification requires a Trello-backed scheduler, deterministic workspaces,
strict prompt construction, Codex app-server execution, and operator-visible runtime state. The
repository was otherwise empty, so the first implementation needed to establish boundaries that are
easy to test and evolve.

## Decision

Implement the service as a Quarkus application with these modules:

- `workflow` and `config` for `WORKFLOW.md` parsing, defaulting, and validation.
- `tracker` for Trello REST transport and normalization.
- `workspace` for directory lifecycle and hooks.
- `prompt` for strict Pebble template rendering.
- `agent` for local Codex app-server execution.
- `orchestrator` for the single mutable scheduler state.
- `api` for status and refresh endpoints.

The orchestrator owns all scheduling maps and validates worker identity before applying worker
events. Retry and running state stay in memory, matching the spec's restart recovery model.

## Consequences

The code is simple to test with fakes and does not need a database. Restarted services rely on Trello
and preserved workspaces rather than restored retry timers. Future persistent state can be added
behind the orchestrator without changing Trello or Codex adapters.
