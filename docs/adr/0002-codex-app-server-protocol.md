# 0002 Codex App-Server Protocol Boundary

Status: Accepted

Date: 2026-05-04

## Context

The spec states that the targeted Codex app-server schema is the protocol source of truth. The local
Codex CLI can generate JSON Schema, and schema details are version-specific.

## Decision

The Java client implements the installed Codex 0.128 app-server flow:

1. Launch configured command in the per-card workspace.
2. Send `initialize`.
3. Send `initialized`.
4. Send `thread/start`.
5. Send `turn/start`.
6. Stream notifications until `turn/completed`.

Approval requests are accepted for the current session in this trusted default posture. User-input
and unsupported dynamic tool requests are answered immediately so a run does not stall indefinitely.
Codex config values such as approval and sandbox policy are pass-through values from `WORKFLOW.md`.

## Consequences

The implementation remains aligned with the generated protocol shape while avoiding a hand-maintained
enum for Codex policy values. Future Codex schema changes should be handled by focused client tests
and this ADR should be amended when startup or request methods change.
