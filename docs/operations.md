# Operations

This guide explains what the status page, JSON API, logs, and token counters mean while Symphony for
Trello is running.

Use it when a card looks stuck, a Codex run is retrying, token totals look surprising, or a workflow
reload did not behave as expected.

## Status Endpoints

- `GET /`: small human-readable status page for a quick check.
- `GET /api/v1/state`: full runtime snapshot.
- `GET /api/v1/{card_identifier}`: card-specific runtime details for a running or retrying card.
- `POST /api/v1/refresh`: asks Symphony to poll Trello and reconcile state immediately.

`POST /api/v1/refresh` returns `202 Accepted`. Multiple refresh requests may be coalesced because a
single poll/reconcile cycle is enough to observe the latest Trello state.

## Runtime State

`GET /api/v1/state` returns these top-level fields:

- `generatedAt`: when the snapshot was created.
- `counts.running`: number of cards with active Codex workers.
- `counts.retrying`: number of cards waiting for a retry timer.
- `routing.activeLists`: Trello lists this process dispatches from.
- `routing.terminalLists`: Trello lists treated as complete.
- `routing.handoffLists`: lists Codex may move cards to with the scoped Trello move tool.
- `running`: active worker rows.
- `retrying`: retry timer rows.
- `codexTotals`: token and runtime totals observed from Codex app-server events.
- `rateLimits`: latest Codex rate-limit payload when Codex reports one.

Each `running` row includes:

- `cardId`: Trello's internal card id. Use this for debugging only.
- `cardIdentifier`: the stable human-facing identifier, such as `TRELLO-abc123`.
- `state`: current Trello list name from the last dispatch refresh.
- `sessionId`: combined Codex thread and turn id when both are known.
- `turnCount`: number of started or continued Codex turns in this worker session.
- `lastEvent` and `lastMessage`: latest Codex app-server event summary Symphony observed.
- `startedAt` and `lastEventAt`: worker start time and latest event time.
- `tokens`: latest per-thread cumulative token values observed for this worker.

Each `retrying` row includes:

- `cardId` and `cardIdentifier`: the card waiting for retry.
- `attempt`: next retry attempt number.
- `dueAt`: when Symphony will try again.
- `error`: the reason the previous attempt did not complete normally.

## Card Details

Use `GET /api/v1/{card_identifier}` when the summary page does not explain enough.

The card detail response includes:

- workspace path for the card.
- current retry attempt and restart count.
- the matching running or retry row.
- recent Codex events retained in memory.
- the last error when Symphony has one.
- tracked card fields rendered from Trello.

The endpoint only returns cards that are currently running or retrying. A `404` usually means the
card is no longer active in this process, reached a terminal list, or belongs to a different
workflow file.

## Logs

Logs use stable `key=value` fields where practical. Useful fields include:

- `workflow`: workflow file that loaded, reloaded, or failed.
- `card_id`: Trello internal card id.
- `card_identifier`: human-facing card identifier.
- `worker_identity`: Symphony's internal guard against stale worker events.
- `outcome`: lifecycle result such as `loaded`, `reloaded`, `dispatched`, `retrying`,
  `terminated`, `completed`, or `failed`.
- `reason`: short explanation for a failure, retry, or termination.
- `attempt`: retry attempt number.
- `delay_ms`: retry delay.
- `hook`: workspace hook name.
- `cwd`: hook working directory.

Typical troubleshooting flow:

1. Check `/api/v1/state` to see whether the card is running, retrying, or absent.
2. If it is running or retrying, open `/api/v1/{card_identifier}` and read `lastEvent`,
   `lastMessage`, `recentEvents`, and `lastError`.
3. Search logs by `card_identifier`.
4. If the card is absent, check the Trello list. Symphony only dispatches configured active lists and
   ignores review, blocked, terminal, archived, and out-of-scope lists.
5. If a workflow edit did not take effect, search logs for `workflow` and `reload`. Invalid reloads
   keep the last known good workflow active until the file is fixed.
6. If Codex cannot read a host path, check the Trello blocked comment and the deployment's allowed
   host path settings.

## Token Totals

Symphony uses Codex app-server token usage events for live totals.

The important rule is simple: cumulative totals are authoritative, increments are not added again.

Current behavior:

- `thread/tokenUsage/updated.tokenUsage.total` is the preferred source.
- `tokenUsage.last` is not added to totals.
- Generic fields named `usage` are not counted unless the event type gives them a known meaning.
- `turn/completed` is used as turn state, not as an unconditional extra token increment.
- Multiple turns can happen on the same Codex thread when a card remains active.

`codexTotals.inputTokens`, `codexTotals.outputTokens`, and `codexTotals.totalTokens` are process
totals derived from the latest cumulative worker totals. `codexTotals.secondsRunning` is elapsed
worker time for completed and currently running workers; it is not billable usage.

`running[].tokens` contains the latest cumulative token values for that worker's current Codex
thread. If Codex does not emit token usage events, the values remain zero.

## Rate Limits

`rateLimits` mirrors the latest `account/rateLimits/updated` event from Codex when available.
Symphony does not invent rate-limit state. If the field is absent or `null`, Codex has not reported
rate-limit data to this process yet.

Trello API rate limits are logged separately. Search logs for `Trello rate limit reached`. The
warning includes the workflow file and current `polling.interval_ms`. Repeated warnings usually mean
the workflow should poll less often, especially when more than 5-10 boards share the same Trello
token.

## Common States

- A card in `Ready for Codex` is queued.
- A card in `In Progress` was picked up by Codex and remains active.
- A card in `Blocked` needs human or environment action before Symphony should try again.
- A card in `Human Review` is ready for a person and is not dispatched.
- A card in `Merging` is approved for landing when the workflow configures landing.
- A card in `Done` is terminal and eligible for workspace cleanup.

If your workflow uses different list names, read `routing` in `/api/v1/state`; it is the runtime
source for what this process considers active, terminal, and allowed handoff destinations.
