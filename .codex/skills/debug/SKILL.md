---
name: debug
description: >
  Diagnose stuck, retrying, blocked, or failed Symphony-for-Trello runs. Use
  when a card is not moving, a worker is retrying, comments show blockers, or a
  deployment smoke test fails.
---

# Debug

## Goals

- Determine whether the problem is Trello routing, scheduler state, Codex
  execution, filesystem access, credentials, or handoff tooling.
- Use safe identifiers and avoid exposing secrets or private local paths.
- Leave enough evidence for the next agent to continue.

## Inputs

- Trello card title or short link.
- The workflow file for the board.
- The status page or `/api/v1/state`.
- Service logs from the local run or deployment.
- Recent Trello comments on the card.

## Triage Flow

1. Confirm the service is reading the intended workflow file and Trello board.
2. Open `/api/v1/state` and check:
   - queued candidates
   - running cards
   - retrying cards
   - last Codex event and message
   - token totals and rate-limit data
3. Check the card's current Trello column. Cards in non-active columns are not
   dispatched.
4. Read recent Trello comments on the card. Blocker comments often contain the
   exact missing path, auth, or permission problem.
5. Search logs by card identifier, worker identity, and session id. Prefer `rg`.
6. Classify the failure:
   - Trello credentials or board access.
   - Workflow parse or invalid config.
   - No eligible active cards.
   - Codex app-server startup/auth failure.
   - Codex turn timeout or stall.
   - Filesystem sandbox or allowed-root blocker.
   - Trello handoff tool failure.
7. Fix the smallest cause, then rerun a local or deployed live verification that
   exercises the same path.

## Useful Commands

```bash
curl -fsS http://127.0.0.1:8080/api/v1/state | jq
rg -n "card_identifier=<redacted>|worker_identity=|session_id=" log/ target/ /var/log 2>/dev/null
systemctl status 'symphony-trello@*'
journalctl -u 'symphony-trello@<workflow>' --since '1 hour ago'
```

Adjust paths for the environment. Do not paste secrets or unrelated host paths
into Trello or GitHub.

## Evidence To Record

- Card identifier or sanitized card title.
- Workflow file name, not a private absolute path unless required for the fix.
- Current Trello column.
- Last service state for the card.
- Last Codex event and error message.
- Trello comment summary, especially blocker comments.
- Verification command or live scenario rerun.

## Stop Conditions

- The fix requires broader filesystem access, credentials, or deployment changes
  that the operator has not approved.
- The investigation would expose secrets or unrelated private project details.
