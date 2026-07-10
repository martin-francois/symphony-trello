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

- `generated_at`: when the snapshot was created.
- `counts.running`: number of cards with active Codex workers.
- `counts.retrying`: number of cards waiting for a retry timer.
- `routing.active_lists`: Trello lists this process dispatches from.
- `routing.terminal_lists`: Trello lists treated as complete.
- `routing.handoff_lists`: lists Codex may move cards to with the scoped Trello move tool.
- `running`: active worker rows.
- `retrying`: retry timer rows.
- `codex_totals`: token and runtime totals observed from Codex app-server events.
- `dispatch_pause`: `null` normally; while Codex is usage-limited, contains `code`, `detected`, and
  `until` for the workflow-wide pickup pause.
- `rate_limits`: latest Codex rate-limit payload when Codex reports one.

Each `running` row includes:

- `card_id`: Trello's internal card id. Use this for debugging only.
- `card_identifier`: the stable human-facing identifier, such as `TRELLO-abc123`.
- `state`: current Trello list name from the last dispatch refresh.
- `session_id`: combined Codex thread and turn id when both are known.
- `turn_count`: number of started or continued Codex turns in this worker session.
- `last_event` and `last_message`: latest Codex app-server event summary Symphony observed.
- `started_at` and `last_event_at`: worker start time and latest event time.
- `tokens`: latest per-thread cumulative token values observed for this worker.

Each `retrying` row includes:

- `card_id` and `card_identifier`: the card waiting for retry.
- `attempt`: next retry attempt number.
- `due_at`: when Symphony will try again.
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
2. If it is running or retrying, open `/api/v1/{card_identifier}` and read `last_event`,
   `last_message`, `recent_events`, and `last_error`.
3. Search logs by `card_identifier`.
4. If the card is absent, check the Trello list. Symphony only dispatches configured active lists and
   ignores review, blocked, terminal, archived, and out-of-scope lists.
5. If a workflow edit did not take effect, search logs for `workflow` and `reload`. Invalid reloads
   keep the last known good workflow active until the file is fixed.
6. If Codex cannot read a host path, check the Trello blocked comment and the deployment's allowed
   host path settings.

## Diagnostics Identifiers

`symphony-trello diagnostics` is safe to paste into public issues by default. It hides Trello board
names, board ids, short links, Trello URLs, local paths, and secret-looking values. To keep the
report useful, it replaces hidden values with stable local tokens such as `board_hash`, `key_hash`,
and `<path:...>`. Those tokens use a random diagnostics key stored on your machine. They let
maintainers tell whether two rows refer to the same board, workflow, env file, workspace, state
directory, or log file without seeing the private value or being able to guess it easily.

If the default report does not include enough context, run:

```bash
symphony-trello diagnostics --deep
```

Deep diagnostics still keeps output public-safe, but it may run active checks such as Codex and
GitHub auth-status commands.

If a maintainer asks what one of those identifiers means on your machine, run:

```bash
symphony-trello diagnostics --show-private-context
```

Use `--board` or `--workflow` with `--show-private-context` when you only need one connected Trello
board or workflow. The command prints private diagnostics context that maps diagnostics tokens to
local Trello identifiers, URLs, and paths. A coding agent running locally can use the same command to
understand which installed board, workflow, env file, workspace, state directory, log file, or
file-backed secret path a sanitized diagnostics row refers to before inspecting files or reproducing a
failure. It can help you or a local coding agent answer questions such as:

- which Trello board a `board_hash` refers to.
- which workflow file a `<path:...>` token refers to.
- which worker log file matches a diagnostics log section.
- which env file, workspace root, or state directory the installed wrapper used.
- which managed PID/state file a lifecycle command hid behind a token.
- which file-backed secret path a lifecycle command hid behind a token, without printing the secret
  value stored in that file.

When you only need one mapping, add `--lookup <token>`:

```bash
symphony-trello diagnostics --show-private-context --lookup '<path:abc123def456>'
```

Lookup accepts the public diagnostics token values shown in sanitized output, such as the 12-hex
value in a `board_hash` or `key_hash` row, or a `<path:...>` token. It does not search arbitrary
private strings.

Some `start`, `stop`, or `status` failures also include a `<path:...>` token and the same lookup
command when they need to hide a local workflow, log, managed PID/state path, or file-backed secret
path.

Do not paste `--show-private-context` output into public issues. It intentionally contains private
Trello board identifiers and local paths. It does not print Trello API keys, Trello tokens, GitHub
tokens, Codex auth files, or worker log contents.

## Token Totals

Symphony uses Codex app-server token usage events for live totals.

The important rule is simple: cumulative totals are authoritative, increments are not added again.

Current behavior:

- `thread/tokenUsage/updated.tokenUsage.total` is the preferred source.
- `tokenUsage.last` is not added to totals.
- Generic fields named `usage` are not counted unless the event type gives them a known meaning.
- `turn/completed` is used as turn state, not as an unconditional extra token increment.
- Multiple turns can happen on the same Codex thread when a card remains active.

`codex_totals.input_tokens`, `codex_totals.output_tokens`, and `codex_totals.total_tokens` are process
totals derived from the latest cumulative worker totals. `codex_totals.seconds_running` is elapsed
worker time for completed and currently running workers; it is not billable usage.

`running[].tokens` contains the latest cumulative token values for that worker's current Codex
thread. If Codex does not emit token usage events, the values remain zero.

## Rate Limits

`rate_limits` mirrors the merged `account/rateLimits/updated` snapshot from Codex when available.
Sparse updates from concurrent workers using the same configured `codex.command` retain the latest
reported fields for other windows and all available top-level metadata; absent or null fields do
not erase earlier values. The merge commits only after the orchestrator accepts the worker identity,
so an ignored or stale worker cannot change either the visible snapshot or a later retry deadline. A
different configured command has an independent snapshot so a workflow reload cannot mix
potentially different Codex accounts. Symphony does not invent rate-limit state.
An update whose entire `rateLimits` value is null, omitted, or malformed keeps the last valid
snapshot. If no valid snapshot has been reported yet, the notification remains observable but
`rate_limits` stays `null`.

A valid `codex.command` reload switches the visible snapshot to that command's independent scope
(`null` until that scope has reported a valid snapshot) and removes the old command's pause as a
gate for new work. Late rate-limit events from still-current prior-command workers remain in their
launch command's snapshot; ignored worker events, typed usage results, and stale callbacks cannot
mutate the new command's pause. Normal card lifecycle may create
an ordinary retry under the new command when the physical tracker target is unchanged. A usage
retry can restart immediately in that case, while unrelated retries recover their original
unpaused deadline. Invalid reloads keep the current command and pause intact.

A physical tracker-target change means the tracker kind, transport-equivalent endpoint, or resolved
board changed. Endpoint scheme/host case, a trailing DNS dot, a default port, percent-escape hex
case, and slashes removed only from the end of the whole raw endpoint before URI parsing by the
Trello client are equivalent; slashes before query or fragment components are not removed. Raw dot
segments, other path/port/query/fragment/user-info changes, and absent versus explicitly
empty URI components are distinct. Credential rotation and selectors that resolve to the same board
remain one target. Old-target retries retire instead of being applied to same-ID cards on the new
target, while in-flight reconciliation, card release, workspace cleanup, and managed-section cleanup
retain their launch target/config. If the command is unchanged, its account pause remains, but old
card/probe/workpad ownership detaches and the deadline probes a fresh new-target candidate.

When an agent attempt reports the exact structured Codex error `usageLimitExceeded`,
`dispatch_pause.code` is `CODEX_USAGE_LIMIT`. `detected` is the first detection time and `until` is
the latest usable exhausted-window reset, or the configured maximum retry backoff when Codex did not
provide one. A non-positive configured maximum uses a one-second safety floor to prevent a hot loop.
New candidate and retry dispatches wait before Trello I/O, but workers that were already running
continue. At the deadline Symphony rechecks with exactly one affected or eligible card while the
pause remains visible. A repeated limit extends the pause without shortening it. If the selected card
is no longer active, Symphony transfers the probe; if no eligible card exists, it waits another
fallback interval.

The affected Trello workpad shows the clean Codex message and deadline when comment writes are
allowed. Typed usage-limit event summaries also keep only that clean message, so raw Codex account
and provider details are not copied into `running[].last_message` or card `recent_events`. Those raw
details and the configured command string are likewise absent from `dispatch_pause` and the managed
workpad section. Failed managed-section removals use bounded,
delayed cleanup attempts on later
polls and graceful stop. The pause is process-local; after a service restart, one attempt may
encounter and re-establish the same limit. When that card next becomes eligible, its normal prompt
refresh lets Symphony remove a stale managed section before dispatch.

Trello API rate limits are logged separately. Search logs for `Trello rate limit reached`. The
warning includes the workflow file and current `polling.interval_ms`. Repeated warnings usually mean
the workflow should poll less often, especially when more than 5-10 boards share the same Trello
token.

## Common States

- A card in `Ready for Codex` is queued.
- An active `CODEX_USAGE_LIMIT` dispatch pause means queued cards stay put until the displayed
  deadline, when Symphony selects one affected or eligible card as the availability check.
- A card in `In Progress` was picked up by Codex and remains active.
- A card in `Blocked` needs human or environment action before Symphony should try again.
- A card in `Human Review` is ready for a person and is not dispatched.
- A card in `Merging` is approved for merging when the workflow configures a merge flow.
- A card in `Done` is terminal and eligible for workspace cleanup.

If your workflow uses different list names, read `routing` in `/api/v1/state`; it is the runtime
source for what this process considers active, terminal, and allowed handoff destinations.
