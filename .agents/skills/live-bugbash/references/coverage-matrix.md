# Coverage Matrix

Use this as a minimum coverage model. Expand it from the current `SPEC.md`, `README.md`, installer help, CLI help, tests, and code discovered during the run.

Record coverage in `coverage-ledger.md` with status values: `not-started`, `covered`, `weak`,
`blocked`, `bug-drafted`, `needs-real-confirmation`, `requires_hardened_host`, or
`not-applicable`.

Every row must record integration modes and host profile used: `TRELLO_MODE`, `CODEX_MODE`, `GITHUB_MODE`, and `HOST_PROFILE`.

## Integration mode coverage

Cover these before or during product scenarios:

- fake Trello endpoint configured without real Trello credentials
- real Trello opt-in detection when explicitly requested
- fake Codex command configured without launching real Codex
- real Codex opt-in detection when explicitly requested
- fake GitHub via local Git and fake `gh` wrapper or local issue drafts
- real GitHub sandbox opt-in detection when explicitly requested
- mixed modes such as real Trello with fake Codex, fake Trello with real Codex, and fake GitHub with real Codex
- conflict handling between natural-language real opt-in and explicit fake key-value settings
- no silent escalation from fake to real when fake support is incomplete
- reporting of `needs_real_confirmation` for fake-only findings that depend on external-service fidelity
- hardened-host opt-in detection and standard-host fallback for dangerous-access execution
- reporting of `requires_hardened_host` for dangerous-access rows that cannot be executed on a standard host

## Workflow and config

Cover these with generated workflows, imported workflows, and hand-authored workflow variants:

- workflow path precedence: explicit argument, default `./WORKFLOW.md`, env or configured path when supported
- missing workflow file
- invalid YAML front matter
- front matter that is not a map
- unknown top-level keys ignored for forward compatibility
- dynamic reload after valid change
- invalid reload keeps last known good config and logs operator-visible error
- `tracker.kind`, invalid kind, and Trello endpoint, including fake endpoint and real endpoint modes
- Trello credentials from env vars, `.env`, direct CLI options, and `file:` references where supported
- empty or missing credential variables
- board id, short link, and URL selectors
- `active_states` and `active_list_ids`
- `terminal_states` and `terminal_list_ids`
- `required_labels` case, whitespace, missing labels, and blank entries
- `in_progress_state` inside active states and invalid overlap with other roles
- `blocked_state` as handoff list and Trello tool allowlist input
- `blocker_enforced_states`
- priority label maps with defaults, custom values, invalid values, and ties
- `card_identifier_prefix`
- Trello request timeout and retry settings
- polling interval valid, invalid, and runtime change
- workspace root defaults, absolute path, relative path, `~`, `$VAR`, and root containment
- hooks: `after_create`, `before_run`, `after_run`, `before_remove`, success, failure, timeout, and dynamic changes
- agent max turns, max retry backoff, max concurrent agents, and per-state concurrency overrides
- Codex command, model, reasoning effort, approval policy, thread sandbox, turn sandbox policy, additional writable roots, turn timeout, read timeout, and stall timeout
- Trello tools enabled and disabled, writes allowed and denied, comments, checklists, URL attachments, move allowlists by ids and names, destructive operations denied by default, and assume-write-scope behavior
- prompt rendering for `card`, `issue` alias, `attempt`, comments, checklists, attachments, Trello references, and unknown variables

## Trello normalization and board behavior

Cover fake Trello for broad matrix coverage. Cover real Trello for these rows only when `TRELLO_MODE=real`.

- open card in active list
- card in generated board default lists
- card in custom imported lists
- multiple active lists and multiple terminal lists
- active list ids taking precedence over active list names
- terminal list ids taking precedence over terminal list names
- archived card
- card in archived list
- card in archived board
- deleted or not-found card when practical
- labels normalized to lowercase
- priority derived from labels
- member, checklist, comment, attachment, short link, URL, idShort, and board id context in normalized card data
- title, description, checklist item, attachment, and comment references to other Trello cards
- prerequisite checklist blockers, completed blockers, malformed blockers, mixed valid and invalid checklist entries, and scheduler-enforced blockers
- Trello API auth failures, permission failures, not found, malformed payloads, rate limits, retryable transport errors, and endpoint limits when practical

## Orchestrator, parallelism, and reconciliation

Cover at least one scenario for each group, using fake integrations by default and real integrations only when opted in:

- dispatch sort order across active states, priorities, Trello position, creation time, and identifier
- several active cards competing for one slot
- several active cards with `agent.max_concurrent_agents > 1`
- `max_concurrent_agents_by_state` with normalized state names and invalid entries
- required-label eligibility and loss of eligibility while running
- blocker-enforced states with terminal and non-terminal blockers
- visible in-progress pickup movement and rollback when slots are unavailable
- worker spawn failure leaving retry entry or releasing claim
- normal worker exit scheduling continuation retry
- abnormal worker exit scheduling exponential backoff
- retry backoff cap
- slot exhaustion requeue reason
- stall detection and retry
- stale worker identity ignored after a newer worker claims the card
- active card refresh updates running state
- non-active state stops agent without workspace cleanup
- terminal state stops agent and cleans workspace
- archived card, archived list, archived board, deleted card, board-out-of-scope, and not-found card cleanup and retry suppression
- reconciliation with no running cards
- snapshot or status API showing running rows, retry rows, token totals, and rate limits when implemented

## Codex app-server behavior

Use fake Codex by default and real Codex only when `CODEX_MODE=real`. Execute host-level dangerous Codex modes only when `HOST_PROFILE=hardened`; otherwise cover them by generated config, validation, fake Codex, and source inspection.

- launch command cwd is the per-card workspace
- POSIX `bash -lc <codex.command>` behavior when applicable
- initialize and initialized order
- thread start or resume behavior
- model and reasoning effort pass-through
- approval, sandbox, thread sandbox, and turn sandbox payloads
- workspace-write, extra writable roots, allow-all paths, danger-full-access, and dangerously bypass modes, with real host execution only in hardened-host profile
- request read timeout
- turn timeout
- stall timeout disabled when configured <= 0
- protocol stderr separated from stdout
- unsupported dynamic tool calls rejected without stalling
- user input requests handled without indefinite stall
- usage and rate-limit telemetry extracted when present
- Trello client-side tool advertised when enabled
- `trello_rest` valid method, path, query, and body
- Trello tool policy failures for writes disabled, comments disabled, checklists disabled, attachments disabled, moves outside allowlist, caller-supplied auth, current-card scope violations, unclassified writes, disabled tool, and destructive operations denied

## Installer, setup, CLI, and host lifecycle

Cover available commands and options discovered at runtime:

- `install.sh` and `install.ps1` help and option parsing
- one-line installer behavior with run-scoped prefix
- install with onboarding and without onboarding
- reinstall or update over existing run-owned install
- custom prefix, bin dir, config dir, state home, env file, manifest, workflow, workspace root, and port
- missing dependency paths, degraded Codex unavailable, degraded GitHub unavailable, degraded Trello unavailable
- `uninstall.sh` and `uninstall.ps1` custom prefix behavior
- uninstall when files are missing
- uninstall must not remove default local data when run with custom prefix or isolated HOME/XDG/SYMPHONY
- `new-board` with GitHub and `--no-github`, fake and real Trello as modes allow
- `import-board` with active, in-progress, terminal, blocked, GitHub, no GitHub, list names with spaces, duplicate names, missing lists, and invalid board selectors
- `list-workspaces` single, multiple, empty, auth failure, permission failure, and fake endpoint responses
- `setup-local`, `setup-local check`, `setup-local repair-port`, and `setup-local configure-github`
- `setup-local --add-path`, multiple add paths, invalid paths, relative paths, duplicate paths, and path boundaries
- `setup-local --allow-all-paths`, with actual writes still confined to run-owned paths
- `setup-local --danger-full-access`, including standard-host config coverage and hardened-host execution coverage
- `SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS`
- `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true`, including standard-host config coverage and hardened-host execution coverage
- runtime positional workflow path
- runtime `--port`, `server.port`, and `SYMPHONY_HTTP_PORT` precedence
- `start`, `stop`, `status`, `logs`, and `diagnostics`, including `diagnostics --deep` when available
- wrapper defaults for config, env, manifest, workflow, state, logs, and workspaces
- port collisions, stale pid files, missing manifests, and repair behavior

## GitHub workflow behavior

Use fake GitHub by default and real private sandbox repositories only when `GITHUB_MODE=real-sandbox`.

- generated workflow with GitHub enabled and disabled
- local fake repo source in Trello card description
- explicit repository URL, local checkout path, and invalid source selection
- branch creation and existing branch behavior
- author identity and verified email policy in fake and real-sandbox modes
- PR create, update, ready-for-review, draft disallowed by default, review handoff, rework, blocked handoff, merging, done, and landing safety paths
- PR template discovery from sandbox or fake repo
- review sweep sources: top-level comments, inline comments, reviews, checks, stale checks, pending checks, unrelated failure caveat, and unresolved threads
- no writes to existing repositories even when card text points at an existing repo

## Observability and private context

Cover:

- structured logs include card/session context
- validation failures are operator-visible
- logging sink failures do not crash orchestration
- credentials and private paths redacted from logs and diagnostics
- token and rate-limit aggregation across repeated updates
- status API and human-readable status surface when implemented
- final report and issue drafts pass private-context scanning
- fake data is marked synthetic so scanners and reviewers do not confuse it with real secrets
