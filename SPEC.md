# Symphony Service Specification

Status: Final Trello-compatible, language-agnostic

Purpose: Define a service that orchestrates coding agents to get project work done from Trello cards.

## Normative Language

The key words `MUST`, `MUST NOT`, `REQUIRED`, `SHOULD`, `SHOULD NOT`, `RECOMMENDED`, `MAY`, and
`OPTIONAL` in this document are to be interpreted as described in RFC 2119.

`Implementation-defined` means the behavior is part of the implementation contract, but this
specification does not prescribe one universal policy. Implementations MUST document the selected
behavior.

## 1. Problem Statement

Symphony is a long-running automation service that continuously reads work from Trello, creates an
isolated workspace for each Trello card, and runs a coding agent session for that card inside the
workspace.

The service solves four operational problems:

- It turns card execution into a repeatable daemon workflow instead of manual scripts.
- It isolates agent execution in per-card workspaces so agent commands run only inside per-card
  workspace directories.
- It keeps the workflow policy in-repo (`WORKFLOW.md`) so teams version the agent prompt and runtime
  settings with their code.
- It provides enough observability to operate and debug multiple concurrent agent runs.

Implementations are expected to document their trust and safety posture explicitly. This
specification does not require a single approval, sandbox, or operator-confirmation policy; some
implementations target trusted environments with a high-trust configuration, while others require
stricter approvals or sandboxing.

Important boundary:

- Symphony is a scheduler/runner and Trello reader.
- This means the orchestrator does not hard-code business-specific card mutation policy. It does
  not mean every Symphony workflow is read-only.
- Card writes, such as list moves, comments, checklist updates, labels, attachments, and PR links,
  are typically performed by the coding agent using tools available in the workflow/runtime
  environment.
- A successful run can end at a workflow-defined handoff list or state, for example `Human Review`,
  not necessarily `Done`.
- Trello Automation/Butler is not required for this workflow.
- The service uses the Trello REST API.
- All Trello requirements in this specification MUST be implementable on a Trello Free Workspace.

## 2. Goals and Non-Goals

### 2.1 Goals

- Poll Trello on a fixed cadence and dispatch work with bounded concurrency.
- Maintain a single authoritative orchestrator state for dispatch, retries, and reconciliation.
- Create deterministic per-card workspaces and preserve them across runs.
- Stop active runs when Trello card state changes make them ineligible.
- Recover from transient failures with exponential backoff.
- Load runtime behavior from a repository-owned `WORKFLOW.md` contract.
- Expose operator-visible observability, at minimum structured logs.
- Support Trello/filesystem-driven restart recovery without requiring a persistent database; exact
  in-memory scheduler state is not restored.
- Remain compatible with Trello Free plan constraints by relying only on boards, lists, cards,
  labels, comments, normal card checklists, card due dates, and normal card attachments within
  Free-plan limits.

Note: this specification uses Trello's term "list" for the visible board lane that contains cards.

### 2.2 Non-Goals

- Rich web UI or multi-tenant control plane.
- Prescribing a specific status page or terminal UI implementation.
- General-purpose workflow engine or distributed job scheduler.
- Built-in business logic for how to edit cards, PRs, or comments. That logic lives in the workflow
  prompt and agent tooling.
- Requiring workflow handoff to be manual. Read-only operation is a valid deployment mode, but
  write-capable workflows may provide scoped Trello tools for agent-driven handoff.
- Mandating strong sandbox controls beyond what the coding agent and host OS provide.
- Mandating a single default approval, sandbox, or operator-confirmation posture for all
  implementations.
- Requiring Trello Automation/Butler rules.
- Requiring paid-plan-only Trello features.
- Requiring a Trello Power-Up to be enabled on the configured board. Trello credentials are managed
  through Trello's developer API key/token flow, but this service does not depend on board-level
  Power-Up behavior.

## 3. System Overview

### 3.1 Main Components

1. `Workflow Loader`
   - Reads `WORKFLOW.md`.
   - Parses YAML front matter and prompt body.
   - Returns `{config, prompt_template}`.

2. `Config Layer`
   - Exposes typed getters for workflow config values.
   - Applies defaults and environment variable indirection.
   - Performs validation used by the orchestrator before dispatch.

3. `Trello Tracker Client`
   - Fetches candidate cards in active Trello lists/states.
   - Fetches current states for specific card IDs for reconciliation.
   - Fetches terminal-state cards during startup cleanup.
   - Normalizes Trello payloads into a stable card model.

4. `Orchestrator`
   - Owns the poll tick.
   - Owns the in-memory runtime state.
   - Decides which cards to dispatch, retry, stop, or release.
   - Tracks session metrics and retry queue state.

5. `Workspace Manager`
   - Maps card identifiers to workspace paths.
   - Ensures per-card workspace directories exist.
   - Runs workspace lifecycle hooks.
   - Cleans workspaces for terminal cards.

6. `Agent Runner`
   - Creates workspace.
   - Builds prompt from card + workflow template.
   - Launches the coding agent app-server client.
   - Streams agent updates back to the orchestrator.

7. `Status Surface` (OPTIONAL)
   - Presents human-readable runtime status, for example terminal output, status page, or another
     operator-facing view.

8. `Logging`
   - Emits structured runtime logs to one or more configured sinks.

### 3.2 Abstraction Levels

Symphony is easiest to port when kept in these layers:

1. `Policy Layer` (repo-defined)
   - `WORKFLOW.md` prompt body.
   - Team-specific rules for card handling, validation, and handoff.

2. `Configuration Layer` (typed getters)
   - Parses front matter into typed runtime settings.
   - Handles defaults, environment tokens, and path normalization.

3. `Coordination Layer` (orchestrator)
   - Polling loop, card eligibility, concurrency, retries, reconciliation.

4. `Execution Layer` (workspace + agent subprocess)
   - Filesystem lifecycle, workspace preparation, coding-agent protocol.

5. `Integration Layer` (Trello adapter)
   - API calls and normalization for Trello board/list/card data.

6. `Observability Layer` (logs + OPTIONAL status surface)
   - Operator visibility into orchestrator and agent behavior.

### 3.3 External Dependencies

- Trello REST API for `tracker.kind: trello`.
- Local filesystem for workspaces and logs.
- OPTIONAL workspace population tooling, for example Git CLI, if used.
- Coding-agent executable that supports the targeted Codex app-server mode.
- Host environment authentication for Trello and the coding agent.

## 4. Core Domain Model

### 4.1 Entities

#### 4.1.1 Card

Normalized Trello card record used by orchestration, prompt rendering, and observability output.

Fields:

- `id` (string)
  - Stable Trello card ID.
- `identifier` (string)
  - Human-readable card key.
  - Default derivation:
    - `<card_identifier_prefix>-<shortLink>` when Trello `shortLink` is available.
    - `<card_identifier_prefix>-<id>` when `shortLink` is unavailable.
    - Trello card `id` otherwise.
  - Do not include Trello `idShort` in the default workspace identifier because it is board-scoped
    and can change when a card moves between boards.
- `title` (string)
  - Trello card `name`.
- `description` (string or null)
  - Trello card `desc`.
- `priority` (integer or null)
  - Lower numbers are higher priority in dispatch sorting.
  - Trello has no native core priority field; priority is derived from configured priority labels or
    is null.
- `state` (string)
  - Normalized Trello card state used by orchestration.
  - For open cards in open lists on an open board, this is the current Trello list name.
  - For archived cards, this SHOULD be `Archived`.
  - For cards inside archived lists, this SHOULD be `ArchivedList`.
  - For cards inside archived boards, this SHOULD be `ArchivedBoard`.
  - For deleted cards discovered by a per-card lookup, this MAY be `Deleted`.
- `state_source` (string)
  - One of:
    - `list`
    - `card_closed`
    - `list_closed`
    - `board_closed`
    - `deleted`
    - `unknown`
- `list_id` (string or null)
  - Current Trello list ID.
- `list_name` (string or null)
  - Current Trello list name when available.
- `list_closed` (boolean or null)
  - Whether the containing Trello list is archived when known.
  - Trello's REST API exposes this archived-list state as `closed`.
- `board_id` (string or null)
  - Trello board ID for the card.
  - For Trello candidate cards and per-card refresh results, this MUST be populated from `idBoard`.
- `board_closed` (boolean or null)
  - Whether the containing Trello board is archived when known.
  - Trello's REST API exposes this archived-board state as `closed`.
- `closed` (boolean)
  - Trello card archived flag.
  - Trello's REST API field name is `closed`.
- `id_short` (integer or null)
  - Trello `idShort`, when available.
- `short_link` (string or null)
  - Trello `shortLink`, when available.
- `short_url` (string or null)
  - Trello `shortUrl`, when available.
- `branch_name` (string or null)
  - Null by default because Trello has no native branch metadata.
  - Implementations MAY derive this from a documented convention, for example a checklist item,
    label, card description marker, or attachment.
- `url` (string or null)
  - Trello card URL.
- `labels` (list of strings)
  - Normalized to lowercase.
- `label_ids` (list of strings)
  - Trello label IDs, if available.
- `members` (list of strings)
  - Member usernames or IDs if the implementation fetches them; otherwise empty.
- `blocked_by` (list of blocker refs)
  - Trello has no native blocker relation.
  - Default: empty list.
  - Implementations MAY derive blockers from documented conventions, such as checklist items,
    attachments, labels, or linked cards.
  - Each blocker ref contains:
    - `id` (string or null)
    - `identifier` (string or null)
    - `state` (string or null)
    - `url` (string or null)
- `created_at` (timestamp or null)
  - Trello does not expose a simple first-class creation timestamp on all card payloads.
  - Implementations MAY derive this from the Trello card ObjectId timestamp when available.
- `updated_at` (timestamp or null)
  - Trello `dateLastActivity`, if available.
- `due_at` (timestamp or null)
  - Trello `due`, if available.
- `due_complete` (boolean or null)
  - Trello `dueComplete`, if available.
- `position` (number or null)
  - Trello `pos`, if available.

#### 4.1.2 Workflow Definition

Parsed `WORKFLOW.md` payload:

- `config` (map)
  - YAML front matter root object.
- `prompt_template` (string)
  - Markdown body after front matter, trimmed.

#### 4.1.3 Service Config (Typed View)

Typed runtime values derived from `WorkflowDefinition.config` plus environment resolution.

Examples:

- poll interval
- workspace root
- active and terminal Trello states/lists
- concurrency limits
- coding-agent executable/args/timeouts
- workspace hooks

#### 4.1.4 Workspace

Filesystem workspace assigned to one card identifier.

Fields (logical):

- `path` (absolute workspace path)
- `workspace_key` (sanitized card identifier)
- `created_now` (boolean, used to gate `after_create` hook)

#### 4.1.5 Run Attempt

One execution attempt for one card.

Fields (logical):

- `card_id`
- `card_identifier`
- `attempt` (integer or null, `null` for first run, `>=1` for retries/continuation)
- `workspace_path`
- `started_at`
- `status`
- `error` (OPTIONAL)

#### 4.1.6 Live Session (Agent Session Metadata)

State tracked while a coding-agent subprocess is running.

Fields:

- `session_id` (string, `<thread_id>-<turn_id>`)
- `thread_id` (string)
- `turn_id` (string)
- `codex_app_server_pid` (string or null)
- `last_codex_event` (string/enum or null)
- `last_codex_timestamp` (timestamp or null)
- `last_codex_message` (summarized payload)
- `codex_input_tokens` (integer)
- `codex_output_tokens` (integer)
- `codex_total_tokens` (integer)
- `last_reported_input_tokens` (integer)
- `last_reported_output_tokens` (integer)
- `last_reported_total_tokens` (integer)
- `turn_count` (integer)
  - Number of coding-agent turns started within the current worker lifetime.

#### 4.1.7 Retry Entry

Scheduled retry state for a card.

Fields:

- `card_id`
- `identifier` (best-effort human ID for status surfaces/logs)
- `attempt` (integer, 1-based for retry queue)
- `due_at_ms` (monotonic clock timestamp)
- `timer_handle` (runtime-specific timer reference)
- `error` (string or null)

#### 4.1.8 Orchestrator Runtime State

Single authoritative in-memory state owned by the orchestrator.

Fields:

- `poll_interval_ms` (current effective poll interval)
- `max_concurrent_agents` (current effective global concurrency limit)
- `running` (map `card_id -> running entry`)
- `claimed` (set of card IDs reserved/running/retrying)
- `retry_attempts` (map `card_id -> RetryEntry`)
- `ignored_worker_identities` (bounded set of worker identities whose late events are ignored)
- `completed` (set of card IDs; bookkeeping only, not dispatch gating)
- `codex_totals` (aggregate tokens + runtime seconds)
- `codex_rate_limits` (latest rate-limit snapshot from agent events)

### 4.2 Stable Identifiers and Normalization Rules

- `Card ID`
  - Use for Trello lookups and internal map keys.
- `Card Identifier`
  - Use for human-readable logs and workspace naming.
- `Workspace Key`
  - Derive from `card.identifier` by replacing any character not in `[A-Za-z0-9._-]` with `_`.
  - Use the sanitized value for the workspace directory name.
- `Normalized Card State`
  - `normalize_state_name(value)` means trim leading/trailing whitespace, collapse internal
    whitespace to a single space, and lowercase using locale-independent case folding.
  - Compare configured state names and Trello list-derived state names only after
    `normalize_state_name`.
  - For non-archived cards in open lists on open boards, the state is the current list name.
  - For archived cards, cards in archived lists, cards in archived boards, and deleted cards, use
    the special states defined in Section 4.1.1.
- `Session ID`
  - Compose from coding-agent `thread_id` and `turn_id` as `<thread_id>-<turn_id>`.

## 5. Workflow Specification (Repository Contract)

### 5.1 File Discovery and Path Resolution

Workflow file path precedence:

1. Explicit application/runtime setting, set by CLI startup path.
2. Java implementation extension: `SYMPHONY_WORKFLOW_PATH`, when set.
3. Default: `WORKFLOW.md` in the current process working directory.

Loader behavior:

- If the file cannot be read, return `missing_workflow_file` error.
- The workflow file is expected to be repository-owned and version-controlled.

### 5.2 File Format

`WORKFLOW.md` is a Markdown file with OPTIONAL YAML front matter.

Design note:

- `WORKFLOW.md` SHOULD be self-contained enough to describe and run different workflows, including
  prompt, runtime settings, hooks, and Trello selection/config, without requiring out-of-band
  service-specific configuration.

Parsing rules:

- If file starts with `---`, parse lines until the next `---` as YAML front matter.
- Remaining lines become the prompt body.
- If front matter is absent, treat the entire file as prompt body and use an empty config map.
- YAML front matter MUST decode to a map/object; non-map YAML is an error.
- Prompt body is trimmed before use.

Returned workflow object:

- `config`: front matter root object, not nested under a `config` key.
- `prompt_template`: trimmed Markdown body.

### 5.3 Front Matter Schema

Core top-level keys:

- `tracker`
- `polling`
- `workspace`
- `hooks`
- `agent`
- `codex`

Extension top-level keys defined in this specification:

- `server`
- `trello_tools`
- `worker`

Unknown keys SHOULD be ignored for forward compatibility.

Note:

- The workflow front matter is extensible. Extensions MAY define additional top-level keys without
  changing the core schema above.
- Extensions SHOULD document their field schema, defaults, validation rules, and whether changes
  apply dynamically or require restart.

#### 5.3.1 `tracker` (object)

Fields:

- `kind` (string)
  - REQUIRED for dispatch.
  - Current supported value: `trello`
- `endpoint` (string)
  - Default for `tracker.kind == "trello"`: `https://api.trello.com/1`
- `api_key` (string)
  - MAY be a literal token, `$VAR_NAME`, or an implementation-defined file reference.
  - This Java implementation supports `file:/path` for file-backed secrets, resolves relative file
    paths relative to the directory containing `WORKFLOW.md`, strips trailing line breaks, and
    rejects files larger than 64 KiB.
  - Default environment variable for `tracker.kind == "trello"`: `TRELLO_API_KEY`.
  - If `$VAR_NAME` resolves to an empty string, treat the key as missing.
- `api_token` (string)
  - MAY be a literal token, `$VAR_NAME`, or an implementation-defined file reference.
  - This Java implementation supports `file:/path` for file-backed secrets, resolves relative file
    paths relative to the directory containing `WORKFLOW.md`, strips trailing line breaks, and
    rejects files larger than 64 KiB.
  - Default environment variable for `tracker.kind == "trello"`: `TRELLO_API_TOKEN`.
  - If `$VAR_NAME` resolves to an empty string, treat the token as missing.
- `board_id` (string)
  - REQUIRED for dispatch when `tracker.kind == "trello"`.
  - May be a Trello board ID, shortLink, or another board identifier accepted by the implementation's
    Trello API client.
  - Implementations MUST resolve this value to the board ID returned by Trello during validation/startup
    and use that resolved ID for all runtime board-scope checks.
- `active_states` (list of strings)
  - Trello list names or normalized special states considered dispatch-active.
  - Default: `Todo`, `In Progress`
  - Production deployments SHOULD explicitly configure `active_states` or `active_list_ids` for the
    intended Trello workflow; the default preserves the original Symphony starter behavior.
  - Production startup SHOULD emit an operator-visible warning when neither `active_states` nor
    `active_list_ids` is explicitly configured.
- `active_list_ids` (list of strings)
  - OPTIONAL Trello list IDs considered dispatch-active.
  - When non-empty, implementations MUST use list ID matching for list-backed open cards instead of
    list-name matching. When empty, active list-name matching uses `active_states`.
- `in_progress_state` (string)
  - OPTIONAL Trello list name used as the visible pickup state.
  - When configured, implementations MAY move a dispatch-eligible card from an earlier active
    list/state into this list after revalidation and before starting the coding agent.
  - When `active_list_ids` is empty, this value MUST also appear in `active_states`.
  - The move is workflow-configured tracker behavior for Trello's visible board state; it does not
    change the coding agent's responsibility for later handoff comments and state transitions.
- `blocker_enforced_states` (list of strings)
  - Trello list names or normalized states where non-terminal blockers prevent dispatch.
  - Default: `Todo`, `Ready for Codex`
  - State names are normalized with `normalize_state_name` from Section 4.2.
- `terminal_states` (list of strings)
  - Trello list names or normalized special states considered terminal.
  - Default: `Done`, `Archived`, `ArchivedList`, `ArchivedBoard`, `Deleted`
- `terminal_list_ids` (list of strings)
  - OPTIONAL Trello list IDs considered terminal.
  - When non-empty, implementations MUST use list ID matching for list-backed open cards instead of
    list-name matching. When empty, terminal list-name matching uses `terminal_states`.
    `terminal_states` still applies to normalized special states such as
    `Archived`, `ArchivedList`, `ArchivedBoard`, and `Deleted`.
- `priority_labels` (map `label_name -> integer`)
  - Label names are normalized to lowercase for lookup.
  - Lower numbers are higher priority.
  - Default:
    - `p1: 1`
    - `p2: 2`
    - `p3: 3`
    - `p4: 4`
    - `priority: critical: 1`
    - `priority: high: 2`
    - `priority: medium: 3`
    - `priority: low: 4`
- `card_identifier_prefix` (string)
  - Used for default human card identifiers.
  - Default: `TRELLO`
- `request_timeout_ms` (integer)
  - Default: `30000`
  - Applies to Trello API requests.
- `max_api_retries` (integer)
  - Default: `3`
  - Applies to retryable Trello API transport errors and `429` rate-limit responses.
- `api_retry_base_delay_ms` (integer)
  - Default: `1000`
  - Base delay for retryable Trello API errors.

#### 5.3.2 `polling` (object)

Fields:

- `interval_ms` (integer)
  - Default: `30000`
  - Changes SHOULD be re-applied at runtime and affect future tick scheduling without restart.

#### 5.3.3 `workspace` (object)

Fields:

- `root` (path string or `$VAR`)
  - Default: `<system-temp>/symphony_workspaces`
  - `~` is expanded.
  - Relative paths are resolved relative to the directory containing `WORKFLOW.md`.
  - The effective workspace root is normalized to an absolute path before use.

#### 5.3.4 `hooks` (object)

Fields:

- `after_create` (multiline shell script string, OPTIONAL)
  - Runs only when a workspace directory is newly created.
  - Failure aborts workspace creation.
- `before_run` (multiline shell script string, OPTIONAL)
  - Runs before each agent attempt after workspace preparation and before launching the coding
    agent.
  - Failure aborts the current attempt.
- `after_run` (multiline shell script string, OPTIONAL)
  - Runs after each agent attempt, success, failure, timeout, or cancellation, once the workspace
    exists.
  - Failure is logged but ignored.
- `before_remove` (multiline shell script string, OPTIONAL)
  - Runs before workspace deletion if the directory exists.
  - Failure is logged but ignored; cleanup still proceeds.
- `timeout_ms` (integer, OPTIONAL)
  - Default: `60000`
  - Applies to all workspace hooks.
  - Invalid values fail configuration validation.
  - Changes SHOULD be re-applied at runtime for future hook executions.

#### 5.3.5 `agent` (object)

Fields:

- `max_concurrent_agents` (integer)
  - Default: `10`
  - Changes SHOULD be re-applied at runtime and affect subsequent dispatch decisions.
- `max_turns` (positive integer)
  - Default: `20`
  - Limits the number of coding-agent turns within one worker session.
  - Invalid values fail configuration validation.
- `max_retry_backoff_ms` (integer)
  - Default: `300000` (5 minutes)
  - Changes SHOULD be re-applied at runtime and affect future retry scheduling.
- `max_concurrent_agents_by_state` (map `state_name -> positive integer`)
  - Default: empty map.
  - State keys are normalized with `normalize_state_name` from Section 4.2 for lookup.
  - Invalid entries, non-positive or non-numeric, are ignored.

#### 5.3.6 `codex` (object)

Fields:

For Codex-owned config values such as `approval_policy`, `thread_sandbox`, and
`turn_sandbox_policy`, supported values are defined by the targeted Codex app-server version.
Implementors SHOULD treat them as pass-through Codex config values rather than relying on a
hand-maintained enum in this spec. To inspect the installed Codex schema, run
`codex app-server generate-json-schema --out <dir>` and inspect the generated definitions. The
generated schema is version-specific to the installed Codex binary. Implementations MAY validate
these fields locally if they want stricter startup checks.

- `command` (string shell command)
  - Default: `codex app-server`
  - The runtime launches this command in the workspace directory.
  - On POSIX systems, `bash -lc <codex.command>` is a conforming default.
  - Non-POSIX process launch behavior is implementation-defined.
  - The launched process MUST speak a compatible app-server protocol.
- `approval_policy` (Codex `AskForApproval` value)
  - Default: implementation-defined.
- `thread_sandbox` (Codex `SandboxMode` value)
  - Default: implementation-defined.
- `turn_sandbox_policy` (Codex `SandboxPolicy` value)
  - Default: implementation-defined.
- `additional_writable_roots` (list of path strings, OPTIONAL Java implementation extension)
  - Additional files or folders to merge into a Codex `workspaceWrite` turn sandbox policy.
  - Relative paths resolve relative to the workflow file.
  - The Java implementation also supports the `SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS`
    environment value for deployment-managed allowed host paths.
- `turn_timeout_ms` (integer)
  - Default: `3600000` (1 hour)
- `read_timeout_ms` (integer)
  - Default: `5000`
- `stall_timeout_ms` (integer)
  - Default: `300000` (5 minutes)
  - If `<= 0`, stall detection is disabled.

#### 5.3.7 `trello_tools` (object, OPTIONAL extension)

This top-level key configures scoped Trello client-side tools such as `trello_rest` when an
implementation ships them.

Fields:

- `enabled` (boolean)
  - Default: `false`
  - Enables the scoped Trello tool surface when implemented.
- `allow_writes` (boolean)
  - Default: `false`
  - When false, all write-capable Trello tool operations MUST fail with a structured tool error.
- `allowed_move_list_ids` (list of strings)
  - Default: empty list.
  - When non-empty, the tool MAY move the current card only to these Trello list IDs.
- `allowed_move_list_names` (list of strings)
  - Default: empty list.
  - Used only when `allowed_move_list_ids` is empty.
  - Implementations SHOULD prefer IDs over names because list names are mutable.
- `allow_comments` (boolean)
  - Default: true when `allow_writes == true`, otherwise false.
- `allow_checklists` (boolean)
  - Default: true when `allow_writes == true`, otherwise false.
- `allow_url_attachments` (boolean)
  - Default: true when `allow_writes == true`, otherwise false.
- `allow_destructive_operations` (boolean)
  - Default: `false`
  - Destructive operations include deleting cards, deleting comments, deleting checklists, deleting
    attachments, deleting labels, deleting tokens, and equivalent operations.
- `assume_write_scope` (boolean)
  - Default: `false`
  - Lets an operator assert that the configured Trello token has write permission when startup cannot
    verify write capability without side effects.

Move operations MUST be disabled unless at least one move allowlist is configured or the
implementation documents an equivalent local policy with the same board-local restriction.

### 5.4 Prompt Template Contract

The Markdown body of `WORKFLOW.md` is the per-card prompt template.

Rendering requirements:

- Use a strict template engine. Liquid-compatible semantics are sufficient.
- Unknown variables MUST fail rendering.
- Unknown filters MUST fail rendering.

Template input variables:

- `card` (object)
  - Includes all normalized card fields, including labels and blockers.
- `issue` (object)
  - REQUIRED compatibility alias for `card`.
  - It MUST point to the same normalized card data as `card`.
  - This preserves compatibility with prompts originally written for issue trackers.
- `attempt` (integer or null)
  - `null`/absent on first attempt.
  - Integer on retry or continuation run.

Fallback prompt behavior:

- If the workflow prompt body is empty, the runtime MAY use a minimal default prompt
  (`You are working on a Trello card.`).
- Workflow file read/parse failures are configuration/validation errors and SHOULD NOT silently fall
  back to a prompt.

### 5.5 Workflow Validation and Error Surface

Error classes:

- `missing_workflow_file`
- `workflow_parse_error`
- `workflow_front_matter_not_a_map`
- `template_parse_error` (during prompt rendering)
- `template_render_error` (unknown variable/filter, invalid interpolation)

Dispatch gating behavior:

- Workflow file read/YAML errors block new dispatches until fixed.
- Template errors fail only the affected run attempt.

### 5.6 Trello Board Setup Command Extension (OPTIONAL)

Implementations MAY provide setup commands that create Trello boards, inspect existing Trello boards,
and write starter `WORKFLOW.md` files. These commands are onboarding helpers, not a runtime
conformance requirement.

If implemented, setup commands SHOULD:

- use the same Trello credential resolution contract as runtime config, including `.env` when the
  implementation supports it
- avoid printing Trello API keys or tokens
- create or import only Trello Free-plan-compatible boards and lists
- write workflow files that satisfy this specification's runtime schema
- refuse to overwrite an existing workflow file unless the operator explicitly asks for overwrite,
  or select a predictable alternate workflow file when that behavior is documented
- emit enough output for the operator to know which board and workflow file were created or imported

This Java implementation provides:

- `list-workspaces`: prints Workspaces accessible to the configured Trello token
- `new-board`: creates the recommended Trello board and starter workflow
- `import-board`: writes a starter workflow for an existing board

When `new-board` is used without an explicit Workspace and the token can access exactly one Trello
Workspace, the Java implementation uses that Workspace automatically. If the token can access
multiple Workspaces, setup fails with an operator-visible message that lists the available choices
and asks for `--workspace-id`.

The recommended board created by `new-board` uses these lists, in order:

1. `Inbox`
2. `Ready for Codex`
3. `In Progress`
4. `Blocked`
5. `Human Review`
6. `Merging`
7. `Done`

The generated workflow treats `Ready for Codex`, `In Progress`, and `Merging` as active lists,
`Done` as terminal, `In Progress` as the visible pickup list, `Blocked` as the blocked handoff list,
`Human Review` as the review handoff list, and `Merging` as the human approval list for landing.
When the generated workflow receives repository work, it tells Codex to use a writable checkout under
the per-card workspace by default, to clone from a readable matching local checkout or repository URL
when the card names only a repository URL, and to clone from a readable but non-writable local
checkout into the workspace instead of editing that shared checkout directly. If Git rejects a
readable local checkout because of safe-directory ownership checks, the generated workflow tells
Codex to add only that source checkout to the current user's Git safe directories before retrying the
read-only clone. It also tells Codex to start task branches from the repository's default branch when
that branch is discoverable, instead of inheriting the source checkout's current branch.
The generated workflow treats unavailable push credentials as blocking only when a card, repository
policy, or human requires a push or pull request. For repository-changing work in the recommended
workflow, `Human Review` means a pull request is available for review unless the card explicitly asks
for local-only or no-push work. It also allows handoff with documented, clearly unrelated broad
validation failures when card-specific validation passed.

When `new-board` would otherwise write `WORKFLOW.md` and that file already exists, the Java
implementation writes a board-specific file named `WORKFLOW.<slugified-board-name>.md`. If that file
also exists, it appends a numeric suffix. Passing `--force` intentionally overwrites the selected
workflow file.

When `import-board` reads an existing board, the Java implementation detects common list names:
`Ready for Codex` for queued work, `In Progress` for visible pickup, `Blocked` for blocked handoff,
`Human Review` or legacy `Review` for review handoff, `Merging` for landing approval when a terminal
list exists, and `Done` for terminal work. Explicit command options override detected list names.

## 6. Configuration Specification

### 6.1 Configuration Resolution Pipeline

Configuration is resolved in this order:

1. Select the workflow file path, explicit runtime setting, otherwise cwd default.
2. Parse YAML front matter into a raw config map.
3. Apply built-in defaults for missing OPTIONAL fields.
4. Resolve `$VAR_NAME` indirection only for config values that explicitly contain `$VAR_NAME`.
5. Coerce and validate typed values.

Environment variables do not globally override YAML values. They are used only when a config value
explicitly references them.

This Java implementation also loads an ignored project-root `.env` file as a local development
convenience. Real environment variables take precedence over `.env` entries. `.env` loading is not a
portable core Symphony requirement, and production deployments SHOULD use the host secret mechanism
documented for that deployment rather than relying on project-local files.

Value coercion semantics:

- Path/command fields support:
  - `~` home expansion
  - `$VAR` expansion for env-backed path values
  - Apply expansion only to values intended to be local filesystem paths; do not rewrite URIs or
    arbitrary shell command strings.
- Relative `workspace.root` values resolve relative to the directory containing the selected
  `WORKFLOW.md`.

### 6.2 Dynamic Reload Semantics

Dynamic reload is REQUIRED:

- The software MUST detect `WORKFLOW.md` changes.
- On change, it MUST re-read and re-apply workflow config and prompt template without restart.
- The software MUST attempt to adjust live behavior to the new config, for example polling cadence,
  concurrency limits, active/terminal states, Codex settings, workspace paths/hooks, and prompt
  content for future runs.
- Reloaded config applies to future dispatch, retry scheduling, reconciliation decisions, hook
  execution, and agent launches.
- Implementations are not REQUIRED to restart in-flight agent sessions automatically when config
  changes.
- Extensions that manage their own listeners/resources, for example an HTTP server port change, MAY
  require restart unless the implementation explicitly supports live rebind.
- Implementations SHOULD also re-validate/reload defensively during runtime operations, for example
  before dispatch, in case filesystem watch events are missed.
- Invalid reloads MUST NOT crash the service; keep operating with the last known good effective
  configuration and emit an operator-visible error.

### 6.3 Dispatch Preflight Validation

This validation is a scheduler preflight run before attempting to dispatch new work. It validates
the workflow/config needed to poll and launch workers, not a full audit of all possible workflow
behavior.

Startup validation:

- Validate configuration before starting the scheduling loop.
- If startup validation fails, fail startup and emit an operator-visible error.

Per-tick dispatch validation:

- Re-validate before each dispatch cycle.
- If validation fails, skip dispatch for that tick, keep reconciliation active, and emit an
  operator-visible error.

Validation checks:

- Workflow file can be loaded and parsed.
- `tracker.kind` is present and supported.
- `tracker.api_key` is present after `$` resolution.
- `tracker.api_token` is present after `$` resolution.
- `tracker.board_id` is present when REQUIRED by the selected tracker kind.
- For Trello, `tracker.board_id` resolves to a Trello board ID and that board is not closed.
- `codex.command` is present and non-empty.

### 6.4 Config Fields Summary (Cheat Sheet)

This section is intentionally redundant so a coding agent can implement the config layer quickly.
Extension fields are documented in the section that defines them. Core conformance does not require
recognizing or validating extension fields unless that extension or conformance profile is
implemented.

- `tracker.kind`: string, REQUIRED, currently `trello`
- `tracker.endpoint`: string, default `https://api.trello.com/1` when `tracker.kind=trello`
- `tracker.api_key`: string, `$VAR`, or implementation-defined file reference, default environment
  variable `TRELLO_API_KEY` when `tracker.kind=trello`
- `tracker.api_token`: string, `$VAR`, or implementation-defined file reference, default
  environment variable `TRELLO_API_TOKEN` when `tracker.kind=trello`
- `tracker.board_id`: string, REQUIRED when `tracker.kind=trello`
- `tracker.active_states`: list of Trello list/state names, default
  `["Todo", "In Progress"]`
- `tracker.active_list_ids`: list of Trello list IDs, default `[]`
- `tracker.blocker_enforced_states`: list of Trello list/state names, default
  `["Todo", "Ready for Codex"]`
- `tracker.terminal_states`: list of Trello list/state names, default
  `["Done", "Archived", "ArchivedList", "ArchivedBoard", "Deleted"]`
- `tracker.terminal_list_ids`: list of Trello list IDs, default `[]`
- `tracker.priority_labels`: map of lowercase label names to positive integers, default includes
  `p1` through `p4` and `priority: critical` through `priority: low`
- `tracker.card_identifier_prefix`: string, default `TRELLO`
- `tracker.request_timeout_ms`: integer, default `30000`
- `tracker.max_api_retries`: integer, default `3`
- `tracker.api_retry_base_delay_ms`: integer, default `1000`
- `polling.interval_ms`: integer, default `30000`
- `workspace.root`: path resolved to absolute, default `<system-temp>/symphony_workspaces`
- `hooks.after_create`: shell script or null
- `hooks.before_run`: shell script or null
- `hooks.after_run`: shell script or null
- `hooks.before_remove`: shell script or null
- `hooks.timeout_ms`: integer, default `60000`
- `agent.max_concurrent_agents`: integer, default `10`
- `agent.max_turns`: integer, default `20`
- `agent.max_retry_backoff_ms`: integer, default `300000` (5m)
- `agent.max_concurrent_agents_by_state`: map of positive integers, default `{}`
- `codex.command`: shell command string, default `codex app-server`
- `codex.approval_policy`: Codex `AskForApproval` value, default implementation-defined
- `codex.thread_sandbox`: Codex `SandboxMode` value, default implementation-defined
- `codex.turn_sandbox_policy`: Codex `SandboxPolicy` value, default implementation-defined
- `codex.additional_writable_roots`: list of path strings, default `[]`
- `SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS`: implementation environment extension that appends
  host-managed allowed roots to `codex.additional_writable_roots`
- `SYMPHONY_CODEX_DANGER_FULL_ACCESS`: implementation environment extension that forces the Codex
  turn sandbox policy to `dangerFullAccess`
- `codex.turn_timeout_ms`: integer, default `3600000`
- `codex.read_timeout_ms`: integer, default `5000`
- `codex.stall_timeout_ms`: integer, default `300000`
- `trello_tools.enabled`: boolean, default `false`
- `trello_tools.allow_writes`: boolean, default `false`
- `trello_tools.allowed_move_list_ids`: list of Trello list IDs, default `[]`
- `trello_tools.allowed_move_list_names`: list of Trello list names, default `[]`
- `trello_tools.allow_comments`: boolean, default true when writes are enabled
- `trello_tools.allow_checklists`: boolean, default true when writes are enabled
- `trello_tools.allow_url_attachments`: boolean, default true when writes are enabled
- `trello_tools.allow_destructive_operations`: boolean, default `false`
- `trello_tools.assume_write_scope`: boolean, default `false`

## 7. Orchestration State Machine

The orchestrator is the only component that mutates scheduling state. All worker outcomes are
reported back to it and converted into explicit state transitions.

### 7.1 Card Orchestration States

This is not the same as Trello states/lists (`Todo`, `In Progress`, etc.). This is the service's
internal claim state.

1. `Unclaimed`
   - Card is not running and has no retry scheduled.

2. `Claimed`
   - Orchestrator has reserved the card to prevent duplicate dispatch.
   - In practice, claimed cards are either `Running` or `RetryQueued`.

3. `Running`
   - Worker task exists and the card is tracked in `running` map.

4. `RetryQueued`
   - Worker is not running, but a retry timer exists in `retry_attempts`.

5. `Released`
   - Claim removed because card is terminal, non-active, missing/deleted, or retry path completed
     without re-dispatch.

Important nuance:

- A successful worker exit does not mean the card is done forever.
- The worker MAY continue through multiple back-to-back coding-agent turns before it exits.
- After each normal turn completion, the worker re-checks the Trello card state.
- If the card is still in an active state, the worker SHOULD start another turn on the same live
  coding-agent thread in the same workspace, up to `agent.max_turns`.
- The first turn SHOULD use the full rendered task prompt.
- Continuation turns SHOULD send only continuation guidance to the existing thread, not resend the
  original task prompt that is already present in thread history.
- Once the worker exits normally, the orchestrator still schedules a short continuation retry,
  about 1 second, so it can re-check whether the card remains active and needs another worker
  session.

### 7.2 Run Attempt Lifecycle

A run attempt transitions through these phases:

1. `PreparingWorkspace`
2. `BuildingPrompt`
3. `LaunchingAgentProcess`
4. `InitializingSession`
5. `StreamingTurn`
6. `Finishing`
7. `Succeeded`
8. `Failed`
9. `TimedOut`
10. `Stalled`
11. `CanceledByReconciliation`

Distinct terminal reasons are important because retry logic and logs differ.

### 7.3 Transition Triggers

- `Poll Tick`
  - Reconcile active runs.
  - Validate config.
  - Fetch candidate cards.
  - Dispatch until slots are exhausted.

- `Worker Exit (normal)`
  - Remove running entry.
  - Update aggregate runtime totals.
  - Schedule continuation retry, attempt `1`, after the worker exhausts or finishes its in-process
    turn loop.

- `Worker Exit (abnormal)`
  - Remove running entry.
  - Update aggregate runtime totals.
  - Schedule exponential-backoff retry.

- `Codex Update Event`
  - After worker identity validation, update live session fields, token counters, and rate limits.

- `Retry Timer Fired`
  - Re-fetch the current card and attempt re-dispatch, clean terminal/deleted workspaces, or release
    claim if no longer eligible.

- `Reconciliation State Refresh`
  - Stop runs whose card states are terminal, deleted, or no longer active.

- `Stall Timeout`
  - Kill worker and schedule retry.

### 7.4 Idempotency and Recovery Rules

- The orchestrator serializes state mutations through one authority to avoid duplicate dispatch.
- `claimed` and `running` checks are REQUIRED before launching any worker.
- A card MUST be claimed before worker spawn begins.
- A pending `running` entry with an orchestrator-generated per-spawn worker identity MUST be
  recorded before worker spawn can emit lifecycle events; worker handles MAY be filled in after
  successful spawn.
- Worker exit events MUST include that orchestrator-generated worker identity. The identity MUST be
  unique per worker spawn for at least the lifetime of the orchestrator process and MUST NOT be
  derived only from card ID, attempt number, or other values that can repeat for the same card.
- All worker-originated events sent to the orchestrator MUST include that worker identity, including
  Codex update events, worker lifecycle events, and worker exit events.
- The orchestrator MUST ignore worker-originated events when the worker identity is marked ignored,
  when no current running entry exists for the card, or when the current running entry's worker
  identity differs from the event's worker identity.
- A stale worker event MUST NOT update session metadata, token counters, stall timestamps, retry
  state, or running card snapshots for a newer running entry for the same card.
- Ignored worker identities MUST be bounded by size, TTL, or both. Expired entries MAY be removed
  after the implementation's maximum expected worker shutdown grace period.
- If worker spawn fails, the card MUST either remain claimed with a retry entry or be explicitly
  released.
- Reconciliation runs before dispatch on every tick.
- Restart recovery is Trello-driven and filesystem-driven, without a durable orchestrator DB.
- Startup terminal cleanup removes stale workspaces for cards already discoverable in terminal
  states.
- Deleted-card cleanup can only happen when the implementation can associate a deleted card with a
  stored or running card identifier.
- Core conformance assumes one active orchestrator process per configured Trello board. If multiple
  orchestrators may target the same board, the implementation MUST add an external claim mechanism
  such as a dedicated running list, claim label/comment, or durable lock store.

## 8. Polling, Scheduling, and Reconciliation

### 8.1 Poll Loop

At startup, the service validates config, performs startup cleanup, schedules an immediate tick, and
then repeats every `polling.interval_ms`.

The effective poll interval SHOULD be updated when workflow config changes are re-applied.

Tick sequence:

1. Reconcile running cards.
2. Run dispatch preflight validation.
3. Fetch candidate cards from Trello using active states/list IDs.
4. Sort cards by dispatch priority.
5. Revalidate a selected card before dispatch. If `tracker.in_progress_state` is configured and the
   card is in an earlier active state/list, move it to that in-progress list and use the refreshed
   card snapshot for the prompt.
6. Dispatch eligible cards while slots remain.
7. Notify observability/status consumers of state changes.

If per-tick validation fails, dispatch is skipped for that tick, but reconciliation still happens
first.

### 8.2 Candidate Selection Rules

A card is dispatch-eligible only if all are true:

- It has `id`, `identifier`, `title`, and `state`.
- Its `board_id`, when known, matches the resolved configured Trello board ID.
- It matches active selection:
  - If it is a list-backed open card and `active_list_ids` is non-empty, `list_id` MUST be in
    `active_list_ids`.
  - Otherwise, its normalized state MUST be in `active_states`.
- It does not match terminal selection:
  - If it is a list-backed open card and `terminal_list_ids` is non-empty, `list_id` MUST NOT be in
    `terminal_list_ids`.
  - If `terminal_list_ids` is empty, list-backed open cards MUST NOT have a normalized state in
    `terminal_states`.
  - Non-list-backed special states such as `Archived`, `ArchivedList`, `ArchivedBoard`, and `Deleted`
    MUST NOT be in `terminal_states`.
- It is not archived.
- It is not in an archived list.
- It is not in an archived board.
- It is not deleted or missing.
- It is not already in `running`.
- It is not already in `claimed`.
- Global concurrency slots are available.
- Per-state concurrency slots are available.
- Blocker rule passes:
  - If the card state is in `tracker.blocker_enforced_states`, do not dispatch when any blocker is
    non-terminal.
  - If the implementation does not support blocker derivation, `blocked_by` defaults to empty and
    this rule always passes.

`is_out_of_configured_board_scope(card)` means the card is normalized from Trello, `board_id` is known,
and `board_id` differs from the resolved configured board ID. Trello candidate and state-refresh
fetches MUST include `idBoard`; if a Trello result omits it, the implementation SHOULD treat that card
as a refresh/normalization failure instead of dispatching it.

Sorting order (stable intent):

1. `priority` ascending, where lower values are preferred and null/unknown sorts last
2. active-state/list order as configured in `tracker.active_list_ids`, then `tracker.active_states`
3. Trello `position` ascending, if available
4. `created_at` oldest first, if available
5. `identifier` lexicographic tie-breaker

### 8.3 Concurrency Control

Global limit:

- `available_slots = max(max_concurrent_agents - running_count, 0)`

Per-state limit:

- `max_concurrent_agents_by_state[state]` if present, state key normalized
- otherwise fallback to global limit

The runtime counts cards by their current tracked state in the `running` map.

### 8.4 Retry and Backoff

Retry entry creation:

- Cancel any existing retry timer for the same card.
- Store `attempt`, `identifier`, `error`, `due_at_ms`, and new timer handle.
- Ensure the card ID remains in `claimed` while a retry is queued.

Backoff formula:

- Normal continuation retries after a clean worker exit use a short fixed delay of `1000` ms.
- Failure-driven retries use `delay = min(10000 * 2^(attempt - 1), agent.max_retry_backoff_ms)`.
- Power is capped by the configured max retry backoff, default `300000` / 5m.

Retry handling behavior:

1. Fetch the current normalized card by `card_id`, including enough fields to evaluate board ID, state,
   list ID, labels, and blockers when supported.
2. If the card lookup returns not found/deleted:
   - Remove the retry entry.
   - Remove the claim.
   - Clean the workspace using the retry entry's stored identifier when available.
3. If the card's board ID no longer matches the resolved configured Trello board ID:
   - Remove the retry entry.
   - Remove the claim.
   - Do not clean the workspace.
4. If the card is terminal:
   - Remove the retry entry.
   - Remove the claim.
   - Clean the workspace.
5. If the card is no longer active:
   - Remove the retry entry.
   - Remove the claim.
   - Do not clean the workspace.
6. If the card is active but not otherwise candidate-eligible, for example blocked:
   - Requeue with an explicit error reason, unless the implementation documents a release policy.
7. If the card is active and candidate-eligible:
   - Dispatch if slots are available.
   - Otherwise requeue with error `no available orchestrator slots`.

Note:

- Terminal-state workspace cleanup is handled by startup cleanup, active-run reconciliation, and
  retry-time state checks.
- Deleted cards cannot always be discovered at startup through board-level Trello fetches; deleted
  card cleanup is best-effort unless a persisted card/workspace index is implemented.

### 8.5 Active Run Reconciliation

Reconciliation runs every tick and has two parts.

Part A: Stall detection

- For each running card, compute `elapsed_ms` since:
  - `last_codex_timestamp` if any event has been seen, else
  - `started_at`
- If `elapsed_ms > codex.stall_timeout_ms`, terminate the worker and queue a retry.
- If `stall_timeout_ms <= 0`, skip stall detection entirely.

Part B: Trello state refresh

- Fetch current card states for all running card IDs.
- For each running card:
  - If the lookup returns not found/deleted: terminate worker, clean workspace, and suppress retry.
  - If the card's board ID no longer matches the resolved configured board ID: terminate worker
    without workspace cleanup and suppress retry.
  - If Trello state/list ID is terminal: terminate worker, clean workspace, and suppress retry.
  - If Trello state/list ID is still active: update the in-memory card snapshot.
  - If Trello state/list ID is neither active nor terminal: terminate worker without workspace
    cleanup and suppress retry.
- If state refresh fails globally, keep workers running and try again on the next tick.
- If state refresh partially fails for some cards, implementations MAY keep those workers running
  and retry them on the next tick, but MUST log which card IDs were not refreshed.

Termination policies:

- Terminal, deleted, board-out-of-scope, and non-active reconciliation terminations MUST suppress
  retry.
- Stall terminations MUST schedule retry.
- Operator-triggered cancellation is implementation-defined, but the implementation MUST document
  whether cancellation schedules retry.
- When retry is suppressed, the running entry MUST be removed, the claim MUST be released, and the
  later worker process-exit notification for the same worker identity MUST be ignored for retry
  purposes.
- When workspace cleanup follows worker termination, implementations SHOULD either wait until the
  worker is confirmed stopped or document a bounded grace/force-kill policy before deleting the
  workspace.

### 8.6 Startup Terminal Workspace Cleanup

When the service starts:

1. Query Trello for cards in terminal states/list IDs.
2. Include archived cards, cards in archived lists, and cards in archived boards when the
   corresponding normalized special states are terminal.
3. For each returned card identifier, remove the corresponding workspace directory.
4. If the terminal-cards fetch fails, log a warning and continue startup.

This prevents stale terminal workspaces from accumulating after restarts.

Limitations:

- Deleted cards usually cannot be enumerated from a Trello board after deletion.
- Implementations SHOULD NOT delete unknown workspace directories at startup unless they have a
  documented persisted card/workspace index or a documented destructive cleanup policy.

## 9. Workspace Management and Safety

### 9.1 Workspace Layout

Workspace root:

- `workspace.root` (normalized absolute path)

Per-card workspace path:

- `<workspace.root>/<sanitized_card_identifier>`

Workspace persistence:

- Workspaces are reused across runs for the same card.
- Successful runs do not auto-delete workspaces.

### 9.2 Workspace Creation and Reuse

Input: `card.identifier`

Algorithm summary:

1. Sanitize identifier to `workspace_key`.
2. Compute workspace path under workspace root.
3. Ensure the workspace path exists as a directory.
4. Mark `created_now=true` only if the directory was created during this call; otherwise
   `created_now=false`.
5. If `created_now=true`, run `after_create` hook if configured.

Notes:

- This section does not assume any specific repository/VCS workflow.
- Workspace preparation beyond directory creation, for example dependency bootstrap, checkout/sync,
  code generation, is implementation-defined and is typically handled via hooks.

### 9.3 OPTIONAL Workspace Population (Implementation-Defined)

The spec does not require any built-in VCS or repository bootstrap behavior.

Implementations MAY populate or synchronize the workspace using implementation-defined logic and/or
hooks, for example `after_create` and/or `before_run`.

Failure handling:

- Workspace population/synchronization failures return an error for the current attempt.
- If failure happens while creating a brand-new workspace, implementations MAY remove the partially
  prepared directory.
- Reused workspaces SHOULD NOT be destructively reset on population failure unless that policy is
  explicitly chosen and documented.

### 9.4 Workspace Hooks

Supported hooks:

- `hooks.after_create`
- `hooks.before_run`
- `hooks.after_run`
- `hooks.before_remove`

Execution contract:

- Execute in a local shell context appropriate to the host OS, with the workspace directory as
  `cwd`.
- On POSIX systems, `sh -lc <script>` or a stricter equivalent such as `bash -lc <script>` is a
  conforming default.
- Hook timeout uses `hooks.timeout_ms`; default: `60000 ms`.
- Log hook start, failures, and timeouts.

Failure semantics:

- `after_create` failure or timeout is fatal to workspace creation.
- `before_run` failure or timeout is fatal to the current run attempt.
- `after_run` failure or timeout is logged and ignored.
- `before_remove` failure or timeout is logged and ignored.

### 9.5 Safety Invariants

This is the most important portability constraint.

Invariant 1: Run the coding agent only in the per-card workspace path.

- Before launching the coding-agent subprocess, validate:
  - `cwd == workspace_path`

Invariant 2: Workspace path MUST stay inside workspace root.

- Normalize both paths to absolute.
- Require `workspace_path` to have `workspace_root` as a prefix directory, not merely a string
  prefix.
- Reject any path outside the workspace root.

Invariant 3: Workspace key is sanitized.

- Only `[A-Za-z0-9._-]` allowed in workspace directory names.
- Replace all other characters with `_`.

## 10. Agent Runner Protocol (Coding Agent Integration)

This section defines Symphony's language-neutral responsibilities when integrating a Codex
app-server. The Codex app-server protocol for the targeted Codex version is the source of truth for
protocol schemas, message payloads, transport framing, and method names.

Protocol source of truth:

- Implementations MUST send messages that are valid for the targeted Codex app-server version.
- Implementations MUST consult the targeted Codex app-server documentation or generated schema
  instead of treating this specification as a protocol schema.
- If this specification appears to conflict with the targeted Codex app-server protocol, the Codex
  protocol controls protocol shape and transport behavior.
- Symphony-specific requirements in this section still control orchestration behavior, workspace
  selection, prompt construction, continuation handling, and observability extraction.

### 10.1 Launch Contract

Subprocess launch parameters:

- Command: `codex.command`
- Working directory: workspace path
- Transport/framing: the protocol transport required by the targeted Codex app-server version

Notes:

- The default command is `codex app-server`.
- On POSIX systems, `bash -lc <codex.command>` is a conforming launch default.
- On non-POSIX systems, process launch behavior is implementation-defined and MUST be documented.
- Approval policy, sandbox policy, cwd, prompt input, and OPTIONAL tool declarations are supplied
  using fields supported by the targeted Codex app-server version.

RECOMMENDED additional process settings:

- Max line size: 10 MB, for safe buffering.

### 10.2 Session Startup Responsibilities

Reference: https://developers.openai.com/codex/app-server/

Startup MUST follow the targeted Codex app-server contract. Symphony additionally requires the
client to:

- Start the app-server subprocess in the per-card workspace.
- Initialize the app-server session using the targeted Codex app-server protocol.
- Create or resume a coding-agent thread according to the targeted protocol.
- Supply the absolute per-card workspace path as the thread/turn working directory wherever the
  targeted protocol accepts cwd.
- Start the first turn with the rendered card prompt.
- Start later in-worker continuation turns on the same live thread with continuation guidance rather
  than resending the original card prompt.
- Supply the implementation's documented approval and sandbox policy using fields supported by the
  targeted protocol.
- Include card-identifying metadata, such as `<card.identifier>: <card.title>`, when the targeted
  protocol supports turn or session titles.
- Advertise implemented client-side tools using the targeted protocol.

For stdio app-server transport, the client MUST:

1. Start `codex app-server` or the configured compatible app-server command.
2. Read and write messages using the framing required by the targeted protocol; current Codex stdio
   app-server versions use newline-delimited JSON messages.
3. Send the targeted protocol's `initialize` request.
4. Send the targeted protocol's `initialized` notification after initialization succeeds.
5. Only then call `thread/start`, `thread/resume`, or the equivalent version-specific thread
   creation/resume method.

If client-side dynamic tools are implemented through Codex app-server experimental APIs, startup
MUST enable the experimental capability required by the targeted protocol, for example
`capabilities.experimentalApi = true` in Codex protocol versions that define that field.

Session identifiers:

- Extract `thread_id` from the thread identity returned by the targeted Codex app-server protocol.
- Extract `turn_id` from each turn identity returned by the targeted Codex app-server protocol.
- Emit `session_id = "<thread_id>-<turn_id>"`
- Reuse the same `thread_id` for all continuation turns inside one worker run.

### 10.3 Streaming Turn Processing

The client processes app-server updates according to the targeted Codex app-server protocol until
the active turn terminates.

Completion conditions:

- Targeted-protocol turn completion signal -> success
- Targeted-protocol turn failure signal -> failure
- Targeted-protocol turn cancellation signal -> failure
- turn timeout (`turn_timeout_ms`) -> failure
- subprocess exit -> failure

Continuation processing:

- If the worker decides to continue after a successful turn, it SHOULD start another turn on the same
  live thread using the targeted protocol.
- The app-server subprocess SHOULD remain alive across those continuation turns and be stopped only
  when the worker run is ending.

Transport handling requirements:

- Follow the transport and framing rules of the targeted Codex app-server version.
- For stdio-based transports, keep protocol stream handling separate from diagnostic stderr handling
  unless the targeted protocol specifies otherwise.

### 10.4 Emitted Runtime Events (Upstream to Orchestrator)

The app-server client emits structured events to the orchestrator callback. Each event SHOULD
include:

- `event` (enum/string)
- `timestamp` (UTC timestamp)
- `codex_app_server_pid` (if available)
- `worker_identity` for all worker-originated events
- OPTIONAL `usage` map (token counts)
- payload fields as needed

Worker-originated event handling requirements:

- Events from a worker MUST include the orchestrator-generated worker identity assigned before spawn.
- The orchestrator MUST validate worker identity before mutating running state, session metadata,
  token counters, rate-limit snapshots, stall timestamps, retry state, or running card snapshots.
- Events for ignored, missing, or stale worker identities MUST be logged at debug level and ignored.

Important emitted events include, for example:

- `session_started`
- `startup_failed`
- `turn_completed`
- `turn_failed`
- `turn_cancelled`
- `turn_ended_with_error`
- `turn_input_required`
- `approval_auto_approved`
- `unsupported_tool_call`
- `notification`
- `other_message`
- `malformed`

### 10.5 Approval, Tool Calls, and User Input Policy

Approval, sandbox, and user-input behavior is implementation-defined.

Policy requirements:

- Each implementation MUST document its chosen approval, sandbox, and operator-confirmation posture.
- Approval requests and user-input-required events MUST NOT leave a run stalled indefinitely.
- An implementation MAY either satisfy them, surface them to an operator, auto-resolve them, or fail
  the run according to its documented policy.

Example high-trust behavior:

- Auto-approve command execution approvals for the session.
- Auto-approve file-change approvals for the session.
- Treat user-input-required turns as hard failure.

Unsupported dynamic tool calls:

- Supported dynamic tool calls that are explicitly implemented and advertised by the runtime SHOULD
  be handled according to their extension contract.
- If the agent requests a dynamic tool call that is not supported, return a tool failure response
  using the targeted protocol and continue the session.
- This prevents the session from stalling on unsupported tool execution paths.

Optional client-side tool extension:

- An implementation MAY expose a limited set of client-side tools to the app-server session.
- Current standardized optional tool: `trello_rest`.
- If implemented, supported tools SHOULD be advertised to the app-server session during startup
  using the protocol mechanism supported by the targeted Codex app-server version.
- If implemented using Codex app-server dynamic tools, the implementation MUST enable the targeted
  protocol's experimental API capability and advertise the tool through `dynamicTools` or the
  equivalent version-specific mechanism.
- Unsupported tool names SHOULD still return a failure result using the targeted protocol and
  continue the session.
- When Trello tool operations are enabled, the implementation MUST enforce `trello_tools` policy
  before executing the request.

`trello_rest` extension contract:

- Purpose: execute a Trello REST API request using Symphony's configured Trello auth for the current
  session.
- Availability: only meaningful when `tracker.kind == "trello"` and valid Trello auth is
  configured.
- Trello Automation/Butler is not required for this tool.
- Trello Workflow Conformance, defined in Section 11.5, requires this tool or an equivalent
  write-capable Trello tool when workflows expect the agent to perform Trello handoff writes.
- Preferred input shape:

  ```json
  {
    "method": "GET",
    "path": "cards/{cardId}",
    "query": {
      "fields": "name,desc,idList,closed,labels,dateLastActivity"
    },
    "body": {
      "optional": "json object for write requests"
    }
  }
  ```

- `method` MUST be a non-empty string.
- `method` SHOULD be one of `GET`, `POST`, `PUT`, or `DELETE`.
- `path` MUST be a non-empty string.
- `path` MUST be a Trello API path relative to `tracker.endpoint`, without a leading slash.
- `path` MUST NOT be a full URL, scheme-relative URL, absolute path, or contain `.` / `..` path
  traversal segments.
- `path` MUST NOT contain a query string or fragment; callers MUST use `query` for query parameters.
- Implementations MUST construct the request URL by appending the validated path under
  `tracker.endpoint`'s path prefix, preserving the `/1` prefix in the default endpoint.
- `query` is OPTIONAL and, when present, MUST be a JSON object.
- `body` is OPTIONAL and, when present, SHOULD be a JSON object unless the implementation explicitly
  supports another Trello request body shape.
- The implementation MUST inject Trello auth from the active Symphony workflow/runtime config.
- The coding agent MUST NOT be required to read raw Trello tokens from disk.
- The tool MUST reject requests whose `query`, `body`, or implementation-supported headers contain
  Trello authentication material, including `key`, `token`, `oauth_consumer_key`, `oauth_token`, or
  equivalent auth fields. The runtime is solely responsible for injecting Trello auth.
- The Trello tool execution context MUST include the current Trello card ID and resolved board ID
  from the active worker session. For current-card-scoped operations, the tool MUST compare requested
  card IDs against this session context and reject requests targeting any other card unless an
  explicit allowlist permits broader access.
- If `trello_tools.enabled == false`, implementations SHOULD NOT advertise `trello_rest`; any direct
  invocation MUST fail with a structured disabled-tool error.
- The tool MUST execute one Trello HTTP operation per tool call.
- Multipart attachment uploads are out of scope unless an implementation explicitly documents
  support for them within Trello Free-plan attachment limits.
- Implementations SHOULD restrict the allowed path/method combinations to the current configured
  board and its cards unless they intentionally document broader access.
- Implementations SHOULD disallow token, member account, organization-wide, enterprise, and
  destructive `DELETE` operations by default unless explicitly enabled by documented policy.
- Write-capable operations MUST require `trello_tools.allow_writes == true`.
- Move operations MUST be limited to the configured board and the configured move allowlist.
- Comment, checklist, and URL attachment writes MUST respect the corresponding `trello_tools`
  allow flags.
- For write-capable operations, implementations SHOULD prefer typed high-level tools, for example
  `trello_add_comment`, `trello_upsert_workpad`, `trello_move_current_card`,
  `trello_upsert_checklist_item`, and `trello_add_url_attachment`.
- Implementations MAY ship only a subset of typed high-level tools when generated or documented
  workflows need only that subset. Unsupported tool names still MUST return a structured tool
  failure instead of stalling the session.
- A Trello workpad tool, when implemented, SHOULD maintain one current-card comment whose text starts
  with `## Codex Workpad`, update that comment instead of creating duplicate progress comments, and
  fail visibly if the existing workpad cannot be updated.
- If writes are exposed through generic `trello_rest`, the implementation MUST classify each write
  request before execution and enforce the same policy as the corresponding high-level operation. If
  a write request cannot be safely classified, it MUST fail with a structured policy error.
- A request that is valid Trello API syntax but violates local `trello_tools` policy MUST return
  `success=false` with a structured policy error.
- Tool result semantics:
  - HTTP 2xx -> `success=true`
  - HTTP non-2xx -> `success=false`, preserving status code and response body when safe
  - invalid input, missing auth, unsupported path/method, or transport failure -> `success=false`
    with an error payload
- Return the Trello response or error payload as structured tool output that the model can inspect
  in-session.

User-input-required policy:

- Implementations MUST document how targeted-protocol user-input-required signals are handled.
- A run MUST NOT stall indefinitely waiting for user input.
- A conforming implementation MAY fail the run, surface the request to an operator, satisfy it
  through an approved operator channel, or auto-resolve it according to its documented policy.
- The example high-trust behavior above fails user-input-required turns immediately.

### 10.6 Timeouts and Error Mapping

Timeouts:

- `codex.read_timeout_ms`: request/response timeout during startup and sync requests
- `codex.turn_timeout_ms`: total turn stream timeout
- `codex.stall_timeout_ms`: enforced by orchestrator based on event inactivity

Error mapping (RECOMMENDED normalized categories):

- `codex_not_found`
- `invalid_workspace_cwd`
- `response_timeout`
- `turn_timeout`
- `process_exit`
- `response_error`
- `turn_failed`
- `turn_cancelled`
- `turn_input_required`
- `codex_protocol_error`

### 10.7 Agent Runner Contract

The `Agent Runner` wraps workspace + prompt + app-server client.

Behavior:

1. Create/reuse workspace for card.
2. Build prompt from workflow template.
3. Start app-server session.
4. Forward app-server events to orchestrator.
5. On any error, fail the worker attempt. The orchestrator will retry.

Note:

- Workspaces are intentionally preserved after successful runs.

## 11. Tracker Integration Contract (Trello-Compatible)

### 11.1 REQUIRED Operations

An implementation MUST support these tracker adapter operations:

1. `fetch_candidate_cards()`
   - Return cards in configured active states/list IDs for a configured Trello board.

2. `fetch_terminal_cards()`
   - Return cards in configured terminal states/list IDs for startup cleanup.
   - Include archived cards, archived-list cards, and archived-board cards when those normalized
     states are terminal.

3. `fetch_card_states_by_ids(card_ids)`
   - Used for active-run reconciliation and retry-time checks.
   - Returns normalized cards when found.
   - For per-card not-found/deleted results, implementations SHOULD return a typed per-card missing
     result rather than failing the entire batch when possible.

Compatibility note:

- Implementations MAY expose deprecated aliases named `fetch_candidate_issues`,
  `fetch_issues_by_states`, and `fetch_issue_states_by_ids`, but their semantics MUST be Trello
  card semantics when `tracker.kind == "trello"`.

### 11.2 Query Semantics (Trello)

Trello-specific requirements for `tracker.kind == "trello"`:

- `tracker.kind == "trello"`
- REST endpoint default `https://api.trello.com/1`
- Auth uses `tracker.api_key` and `tracker.api_token`
- Auth MUST NOT be logged
- Auth SHOULD use the Trello-supported `Authorization` header format:
  - `Authorization: OAuth oauth_consumer_key="<api_key>", oauth_token="<api_token>"`
- Implementations MAY use Trello's documented `key=<api_key>` and `token=<api_token>` query
  parameters when necessary, but request URLs MUST be redacted before logging.
- `tracker.board_id` maps to the configured Trello board and is resolved to the board ID returned by
  Trello
- Candidate card fetch uses the configured board and the active selection rules in Section 8.2
- State refresh by ID uses Trello card IDs and resolves each card's current board ID, list name, and
  list archived state
- Startup terminal cleanup fetches cards matching the terminal selection rules in Section 8.2,
  archived cards, cards in archived lists, and cards in archived boards when those normalized states
  are terminal
- Network timeout default: `30000 ms`
- The adapter MUST account for documented Trello API rate limits and `429` responses
- The adapter SHOULD enforce local rate limiting with separate buckets for Trello's documented
  limits:
  - API key: 300 requests per 10 seconds
  - API token: 100 requests per 10 seconds
- The adapter SHOULD use bounded exponential backoff with jitter for retryable Trello transport
  errors and `429` responses
- The adapter SHOULD honor response retry hints such as `Retry-After` if Trello provides them
- The adapter SHOULD avoid unnecessary `/1/members/` calls because member-related endpoints can be
  more constrained than board/card reads

Recommended Trello API access pattern:

1. Fetch the configured board with at least `id`, `name`, and `closed`, and store the returned `id` as
   the resolved configured board ID.
2. Fetch board lists with `filter=all` and fields such as `id`, `name`, `closed`, and `pos`.
3. Build `list_id -> list` mapping from the fetched lists.
4. Fetch open board cards from the configured board for candidate dispatch.
5. Normalize each card and attach its current list metadata as `state`, `list_name`, `list_closed`,
   `state_source`, and `board_id`.
6. Filter candidate cards using `tracker.active_list_ids` as authoritative for list-backed open
   cards when configured, otherwise using `tracker.active_states`.
7. Fetch archived board cards only when needed for terminal cleanup or state reconciliation.
8. For individual card refresh, fetch the card with `idBoard` and its list, or maintain a fresh enough
   list mapping, to normalize the current state accurately and reject cards that moved to another board.

Recommended `fetch_terminal_cards()` strategy:

1. Fetch the configured board metadata.
2. Fetch all board lists with `filter=all`.
3. Fetch board cards using a filter that includes archived cards. Trello's REST API calls archived
   cards `closed`.
4. For terminal list IDs and archived lists, fetch list cards when needed to avoid missing open cards
   in terminal or archived lists.
5. Normalize all results through the same card normalization pipeline and de-duplicate by Trello card
   ID.

Important:

- Trello REST payload shapes and available fields can drift. Keep request construction isolated and
  test the exact fields REQUIRED by this specification.
- Trello card `closed` only means the card itself is archived; archived lists and boards must be
  handled separately.
- A non-Trello implementation MAY change transport details, but the normalized outputs MUST match
  the domain model in Section 4.

### 11.3 Normalization Rules

Candidate card normalization SHOULD produce fields listed in Section 4.1.1.

Additional normalization details:

- Trello `id` -> `id`
- Trello `name` -> `title`
- Trello `desc` -> `description`
- Trello `idList` -> `list_id`
- Trello list name -> `list_name`
- Trello list name -> `state` when card, list, and board are all open
- Trello `closed == true` on the card -> `state = "Archived"` and `state_source = "card_closed"`
- Trello list `closed == true` and card `closed == false` -> `state = "ArchivedList"` and
  `state_source = "list_closed"`
- Trello board `closed == true` -> `state = "ArchivedBoard"` and `state_source = "board_closed"`
- Deleted or not-found card in a per-card lookup -> typed missing/deleted result; if normalized as a
  card snapshot, use `state = "Deleted"` and `state_source = "deleted"`
- Trello `shortLink` -> `short_link`
- Trello `shortUrl` -> `short_url`
- Trello `idShort` -> `id_short`
- Trello `url` -> `url`
- Trello `dateLastActivity` -> `updated_at`
- Trello `due` -> `due_at`
- Trello `dueComplete` -> `due_complete`
- Trello `pos` -> `position`
- `labels` -> lowercase strings
  - Prefer label `name` when present.
  - Fall back to label color/name conventions only if documented.
- `label_ids` -> Trello label IDs when available.
- `priority` -> integer from configured `tracker.priority_labels`, using the best/highest priority
  label match.
- `created_at` -> derived from Trello ObjectId timestamp when possible, otherwise null.
- `blocked_by` -> empty by default unless the implementation documents a Trello-specific blocker
  convention.

### 11.4 Error Handling Contract

RECOMMENDED error categories:

- `unsupported_tracker_kind`
- `missing_tracker_api_key`
- `missing_tracker_api_token`
- `missing_tracker_board_id`
- `trello_api_request` (transport failures)
- `trello_api_status` (non-2xx HTTP)
- `trello_api_rate_limited`
- `trello_unknown_payload`
- `trello_missing_list_mapping`
- `trello_unsupported_operation`
- `trello_auth_failed`
- `trello_permission_denied`
- `trello_card_not_found`
- `trello_board_closed`

Orchestrator behavior on tracker errors:

- Candidate fetch failure: log and skip dispatch for this tick.
- Running-state refresh global failure: log and keep active workers running.
- Running-state refresh per-card not-found/deleted result: terminate that worker and clean workspace.
- Startup terminal cleanup failure: log warning and continue startup.

### 11.5 Tracker Writes (Important Boundary)

Symphony does not require first-class tracker write APIs in the orchestrator.

- Card mutations, such as list moves, comments, checklist updates, labels, attachments, and PR
  metadata, are typically handled by the coding agent using tools defined by the workflow prompt.
- The service remains a scheduler/runner and Trello reader, except for workflow-configured pickup
  transitions such as `tracker.in_progress_state`.
- This boundary only limits where card mutation decisions live. It does not require operators to
  infer completion from logs or move cards manually when a workflow is configured with scoped Trello
  write tools.
- Workflow-specific success often means "reached the next handoff state", for example
  `Human Review`, rather than Trello terminal state `Done`.
- The handoff signal is normally produced by the agent, because the agent has the semantic context
  to know whether the requested work is reviewable, blocked, or unsafe to hand off.
- A workflow MAY also configure an initial pickup transition, such as moving a Trello card from
  `Ready for Codex` to `In Progress`, before implementation work starts. This mirrors the original
  Symphony workflow pattern while keeping the mutation decision in `WORKFLOW.md`.
- If the `trello_rest` client-side tool extension is implemented, it is still part of the agent
  toolchain rather than orchestrator business logic.
- Write-capable Trello operations require a token that was authorized with write permission.
- Read-only deployments SHOULD disable write-capable Trello tool operations.

Trello Workflow Conformance:

- Workflows that expect the agent to perform Trello handoff transitions MUST provide a scoped
  Trello client-side tool, MCP tool, or documented equivalent that supports the required writes.
- The configured Trello tool MUST enforce `trello_tools` or a documented equivalent local policy.
- Write-capable workflows MUST support the specific Trello writes they instruct the agent to
  perform. For the recommended workflow in this Java implementation, that means adding a comment to
  the current card, upserting the single `## Codex Workpad` comment, and moving the current card to
  an allowed board-local list.
- If a workflow instructs the agent to add or update checklist items, labels, URL attachments, PR
  links, or other Trello fields, the implementation MUST provide a scoped tool or documented
  equivalent for those writes before claiming conformance for that workflow.
- Write tools MUST be scoped to the configured board and current card unless an explicit allowlist
  permits broader access.
- Read-only deployments MAY disable write-capable operations, but then the implementation MUST
  document that the agent cannot perform Trello handoff transitions itself.
- When Trello Workflow Conformance is enabled and write-capable operations are enabled, startup
  validation SHOULD verify that the configured token can perform write operations or emit an
  operator-visible warning if capability verification is not possible without side effects.
- Implementations MAY verify write capability using a documented non-destructive check, a dedicated
  test card, or `trello_tools.assume_write_scope=true`.

## 12. Prompt Construction and Context Assembly

### 12.1 Inputs

Inputs to prompt rendering:

- `workflow.prompt_template`
- normalized `card` object
- compatibility alias `issue`, pointing to the same normalized card object
- OPTIONAL `attempt` integer, retry/continuation metadata

### 12.2 Rendering Rules

- Render with strict variable checking.
- Render with strict filter checking.
- Convert card object keys to strings for template compatibility.
- Preserve nested arrays/maps, labels and blockers, so templates can iterate.
- `issue` and `card` MUST be identical by value for prompt rendering.

### 12.3 Retry/Continuation Semantics

`attempt` SHOULD be passed to the template because the workflow prompt can provide different
instructions for:

- first run (`attempt` null or absent)
- continuation run after a successful prior session
- retry after error/timeout/stall

### 12.4 Failure Semantics

If prompt rendering fails:

- Fail the run attempt immediately.
- Let the orchestrator treat it like any other worker failure and decide retry behavior.

## 13. Logging, Status, and Observability

### 13.1 Logging Conventions

REQUIRED context fields for card-related logs:

- `card_id`
- `card_identifier`

REQUIRED context for coding-agent session lifecycle logs:

- `session_id`

Compatibility note:

- Implementations MAY additionally emit `issue_id` and `issue_identifier` aliases for legacy log
  consumers, but `card_id` and `card_identifier` are the Trello-native fields.

Message formatting requirements:

- Use stable `key=value` phrasing.
- Include action outcome (`completed`, `failed`, `retrying`, etc.).
- Include concise failure reason when present.
- Avoid logging large raw payloads unless necessary.
- Avoid logging Trello request URLs when using query-parameter auth.

### 13.2 Logging Outputs and Sinks

The spec does not prescribe where logs are written, such as stderr, file, remote sink, etc.

Requirements:

- Operators MUST be able to see startup/validation/dispatch failures without attaching a debugger.
- Implementations MAY write to one or more sinks.
- If a configured log sink fails, the service SHOULD continue running when possible and emit an
  operator-visible warning through any remaining sink.

### 13.3 Runtime Snapshot / Monitoring Interface (OPTIONAL but RECOMMENDED)

If the implementation exposes a synchronous runtime snapshot for status pages or monitoring, it
SHOULD return:

- `running` (list of running session rows)
- each running row SHOULD include `turn_count`
- `retrying` (list of retry queue rows)
- `routing`
  - `activeLists`
  - `terminalLists`
  - `handoffLists`
- `codex_totals`
  - `input_tokens`
  - `output_tokens`
  - `total_tokens`
  - `seconds_running` (aggregate runtime seconds as of snapshot time, including active sessions)
- `rate_limits` (latest coding-agent rate limit payload, if available)

RECOMMENDED snapshot error modes:

- `timeout`
- `unavailable`

### 13.4 OPTIONAL Human-Readable Status Surface

A human-readable status surface, terminal output, status page, etc., is OPTIONAL and
implementation-defined.

If present, it SHOULD draw from orchestrator state/metrics only and MUST NOT be REQUIRED for
correctness.

### 13.5 Session Metrics and Token Accounting

Token accounting rules:

- Agent events can include token counts in multiple payload shapes.
- Prefer absolute thread totals when available, such as:
  - `thread/tokenUsage/updated` payloads
  - `total_token_usage` within token-count wrapper events
- Ignore delta-style payloads such as `last_token_usage` for status page/API totals.
- Extract input/output/total token counts leniently from common field names within the selected
  payload.
- For absolute totals, track deltas relative to last reported totals to avoid double-counting.
- Do not treat generic `usage` maps as cumulative totals unless the event type defines them that
  way.
- Accumulate aggregate totals in orchestrator state.

Runtime accounting:

- Runtime SHOULD be reported as a live aggregate at snapshot/render time.
- Implementations MAY maintain a cumulative counter for ended sessions and add active-session
  elapsed time derived from `running` entries, for example `started_at`, when producing a
  snapshot/status view.
- Add run duration seconds to the cumulative ended-session runtime when a session ends, normal exit
  or cancellation/termination.
- Continuous background ticking of runtime totals is not REQUIRED.

Rate-limit tracking:

- Track the latest rate-limit payload seen in any agent update.
- Any human-readable presentation of rate-limit data is implementation-defined.

### 13.6 Humanized Agent Event Summaries (OPTIONAL)

Humanized summaries of raw agent protocol events are OPTIONAL.

If implemented:

- Treat them as observability-only output.
- Do not make orchestrator logic depend on humanized strings.

### 13.7 OPTIONAL HTTP Server Extension

This section defines an OPTIONAL HTTP interface for observability and operational control.

If implemented:

- The HTTP server is an extension and is not REQUIRED for conformance.
- The implementation MAY serve server-rendered HTML or a client-side application for the status page.
- The status page/API MUST be observability/control surfaces only and MUST NOT become REQUIRED for
  orchestrator correctness.
- If the HTTP server binds to a non-loopback interface, operational endpoints such as
  `POST /api/v1/refresh` SHOULD require authentication or be otherwise access-controlled.

Extension config:

- `server.port` (integer, OPTIONAL)
  - Enables the HTTP server extension.
  - `0` requests an ephemeral port for local development and tests.
  - CLI `--port` overrides `server.port` when both are present.
- `SYMPHONY_HTTP_PORT` / `QUARKUS_HTTP_PORT` (Java implementation environment extension)
  - Uses Quarkus's normal HTTP port override path.
  - When either external port override is present, it takes precedence over `server.port`.
- `SYMPHONY_AUTOSTART` (Java implementation environment extension)
  - Defaults to `true`.
  - Set to `false` only for tests or embedded/injected service usage where the caller starts the
    orchestrator explicitly.

Enablement (extension):

- Start the HTTP server when a CLI `--port` argument is provided.
- Start or reconfigure the HTTP server through the implementation's host framework when an external
  port override such as `SYMPHONY_HTTP_PORT` or `QUARKUS_HTTP_PORT` is present.
- Start the HTTP server when `server.port` is present in `WORKFLOW.md` front matter.
- The `server` top-level key is owned by this extension.
- Positive `server.port` values bind that port.
- Implementations SHOULD bind loopback by default (`127.0.0.1` or host equivalent) unless explicitly
  configured otherwise.
- Changes to HTTP listener settings, for example `server.port`, do not need to hot-rebind;
  restart-required behavior is conformant.

#### 13.7.1 Human-Readable Status Page (`/`)

- Host a human-readable status page at `/`.
- The returned document SHOULD depict the current state of the system, for example active sessions,
  retry delays, token consumption, runtime totals, recent events, and health/error indicators.
- It is up to the implementation whether this is server-generated HTML or a client-side app that
  consumes the JSON API below.

#### 13.7.2 JSON REST API (`/api/v1/*`)

Provide a JSON REST API under `/api/v1/*` for current runtime state and operational debugging.

Minimum endpoints:

- `GET /api/v1/state`
  - Returns a summary view of the current system state, running sessions, retry queue/delays,
    aggregate token/runtime totals, latest rate limits, and any additional tracked summary fields.
  - Suggested response shape:

    ```json
    {
      "generated_at": "2026-02-24T20:15:30Z",
      "counts": {
        "running": 2,
        "retrying": 1
      },
      "running": [
        {
          "card_id": "65df9f70b4a22a1234567890",
          "card_identifier": "TRELLO-aBcDeFgH",
          "state": "In Progress",
          "session_id": "thread-1-turn-1",
          "turn_count": 7,
          "last_event": "turn_completed",
          "last_message": "",
          "started_at": "2026-02-24T20:10:12Z",
          "last_event_at": "2026-02-24T20:14:59Z",
          "tokens": {
            "input_tokens": 1200,
            "output_tokens": 800,
            "total_tokens": 2000
          }
        }
      ],
      "retrying": [
        {
          "card_id": "65df9f70b4a22a1234567891",
          "card_identifier": "TRELLO-zYxWvUtS",
          "attempt": 3,
          "due_at": "2026-02-24T20:16:00Z",
          "error": "no available orchestrator slots"
        }
      ],
      "codex_totals": {
        "input_tokens": 5000,
        "output_tokens": 2400,
        "total_tokens": 7400,
        "seconds_running": 1834.2
      },
      "rate_limits": null
    }
    ```

- `GET /api/v1/<card_identifier>`
  - Returns card-specific runtime/debug details for the identified card, including any information
    the implementation tracks that is useful for debugging.
  - Suggested response shape:

    ```json
    {
      "card_identifier": "TRELLO-aBcDeFgH",
      "card_id": "65df9f70b4a22a1234567890",
      "status": "running",
      "workspace": {
        "path": "/tmp/symphony_workspaces/TRELLO-aBcDeFgH"
      },
      "attempts": {
        "restart_count": 1,
        "current_retry_attempt": 2
      },
      "running": {
        "session_id": "thread-1-turn-1",
        "turn_count": 7,
        "state": "In Progress",
        "started_at": "2026-02-24T20:10:12Z",
        "last_event": "notification",
        "last_message": "Working on tests",
        "last_event_at": "2026-02-24T20:14:59Z",
        "tokens": {
          "input_tokens": 1200,
          "output_tokens": 800,
          "total_tokens": 2000
        }
      },
      "retry": null,
      "logs": {
        "codex_session_logs": [
          {
            "label": "latest",
            "path": "/var/log/symphony/codex/TRELLO-aBcDeFgH/latest.log",
            "url": null
          }
        ]
      },
      "recent_events": [
        {
          "at": "2026-02-24T20:14:59Z",
          "event": "notification",
          "message": "Working on tests"
        }
      ],
      "last_error": null,
      "tracked": {}
    }
    ```

  - If the card is unknown to the current in-memory state, return `404` with an error response, for
    example `{"error":{"code":"card_not_found","message":"..."}}`.

- `POST /api/v1/refresh`
  - Queues an immediate Trello poll + reconciliation cycle, best-effort trigger; implementations
    MAY coalesce repeated requests.
  - Suggested request body: empty body or `{}`.
  - Suggested response (`202 Accepted`) shape:

    ```json
    {
      "queued": true,
      "coalesced": false,
      "requested_at": "2026-02-24T20:15:30Z",
      "operations": ["poll", "reconcile"]
    }
    ```

API design notes:

- The JSON shapes above are the RECOMMENDED baseline for interoperability and debugging ergonomics.
- Implementations MAY add fields, but SHOULD avoid breaking existing fields within a version.
- This Java implementation serializes HTTP API fields with Java record accessor names, for example
  `generatedAt`, `codexTotals`, `cardIdentifier`, `turnCount`, and `routing.activeLists`.
- Endpoints SHOULD be read-only except for operational triggers like `/refresh`.
- Unsupported methods on defined routes SHOULD return `405 Method Not Allowed`.
- API errors SHOULD use a JSON envelope such as `{"error":{"code":"...","message":"..."}}`.
- If the status page is a client-side app, it SHOULD consume this API rather than duplicating state
  logic.

## 14. Failure Model and Recovery Strategy

### 14.1 Failure Classes

1. `Workflow/Config Failures`
   - Missing `WORKFLOW.md`
   - Invalid YAML front matter
   - Unsupported tracker kind or missing Trello credentials/board ID
   - Missing coding-agent executable

2. `Workspace Failures`
   - Workspace directory creation failure
   - Workspace population/synchronization failure, implementation-defined; can come from hooks
   - Invalid workspace path configuration
   - Hook timeout/failure

3. `Agent Session Failures`
   - Startup handshake failure
   - Turn failed/cancelled
   - Turn timeout
   - User input requested and handled as failure by the implementation's documented policy
   - Subprocess exit
   - Stalled session, no activity
   - App-server protocol error

4. `Tracker Failures`
   - API transport errors
   - Non-2xx status
   - Rate limits
   - Auth/permission failures
   - Malformed payloads
   - Card not found/deleted
   - Board/list archived or unavailable

5. `Observability Failures`
   - Snapshot timeout
   - Status page render errors
   - Log sink configuration failure

### 14.2 Recovery Behavior

- Dispatch validation failures:
  - Skip new dispatches.
  - Keep service alive.
  - Continue reconciliation where possible.

- Worker failures:
  - Convert to retries with exponential backoff.

- Trello candidate-fetch failures:
  - Skip this tick.
  - Try again on next tick.

- Reconciliation state-refresh failures:
  - Keep current workers when the failure is global.
  - Cleanly stop and cleanup a worker when its specific card is confirmed deleted or terminal.
  - Retry unknown per-card refresh failures on the next tick.

- Trello API rate limits:
  - Back off and retry within configured bounds.
  - If retries are exhausted, surface a tracker failure and let the normal tick/retry path continue.

- Status page/log failures:
  - Do not crash the orchestrator.

### 14.3 Partial State Recovery (Restart)

Current design is intentionally in-memory for scheduler state.
Restart recovery means the service can resume useful operation by polling Trello state and reusing
preserved workspaces. It does not mean retry timers, running sessions, or live worker state survive
process restart.

After restart:

- No retry timers are restored from prior process memory.
- No running sessions are assumed recoverable.
- Service recovers by:
  - startup terminal workspace cleanup
  - fresh polling of active Trello cards
  - re-dispatching eligible work

Limitations:

- Deleted cards cannot always be discovered from Trello after deletion.
- If deterministic cleanup of deleted-card workspaces is required, implementations SHOULD persist a
  card/workspace index.

### 14.4 Operator Intervention Points

Operators can control behavior by:

- Editing `WORKFLOW.md`, prompt and most runtime settings.
- `WORKFLOW.md` changes are detected and re-applied automatically without restart according to
  Section 6.2.
- Changing card state in Trello:
  - terminal list, archived card, archived list, or archived board -> running session is stopped and
    workspace cleaned when reconciled
  - non-active list -> running session is stopped without cleanup
- Deleting a Trello card:
  - running session is stopped and workspace cleaned when deletion is discovered by per-card lookup
  - queued retry is released and workspace cleaned when deletion is discovered
- Restarting the service for process recovery or deployment, not as the normal path for applying
  workflow config changes.

## 15. Security and Operational Safety

### 15.1 Trust Boundary Assumption

Each implementation defines its own trust boundary.

Operational safety requirements:

- Implementations SHOULD state clearly whether they are intended for trusted environments, more
  restrictive environments, or both.
- Implementations SHOULD state clearly whether they rely on auto-approved actions, operator
  approvals, stricter sandboxing, or some combination of those controls.
- Workspace isolation and path validation are important baseline controls, but they are not a
  substitute for whatever approval and sandbox policy an implementation chooses.

### 15.2 Filesystem Safety Requirements

Mandatory:

- Workspace path MUST remain under configured workspace root.
- Coding-agent cwd MUST be the per-card workspace path for the current run.
- Workspace directory names MUST use sanitized identifiers.

RECOMMENDED additional hardening for deployments:

- Run under a dedicated OS user.
- Restrict workspace root permissions.
- Mount workspace root on a dedicated volume if possible.
- Keep deployed access to existing host paths opt-in. If a deployment supports access to paths
  outside the managed workspace root, operators SHOULD configure explicit allowed host paths rather
  than exposing broad filesystem access by default.

### 15.3 Secret Handling

- Support `$VAR` indirection in workflow config.
- For production deployments, prefer file-backed secret mechanisms over plain service environment
  variables when the host supervisor supports them.
- Do not log Trello API keys, Trello tokens, Codex credentials, or secret env values.
- Validate presence of secrets without printing them.
- Do not place Trello credentials in rendered prompts.
- Client-side Trello tools MUST inject auth internally rather than requiring the agent to read or
  print raw credentials.
- Prefer Trello authorization headers over query-parameter auth when practical.
- If query-parameter auth is used, request URLs MUST be redacted before logging.

### 15.4 Hook Script Safety

Workspace hooks are arbitrary shell scripts from `WORKFLOW.md`.

Implications:

- Hooks are fully trusted configuration.
- Hooks run inside the workspace directory.
- Hook output SHOULD be truncated in logs.
- Hook timeouts are REQUIRED to avoid hanging the orchestrator.

### 15.5 Harness Hardening Guidance

Running Codex agents against repositories, Trello boards, and other inputs that can contain
sensitive data or externally-controlled content can be dangerous. A permissive deployment can lead
to data leaks, destructive mutations, or full machine compromise if the agent is induced to execute
harmful commands or use overly-powerful integrations.

Implementations SHOULD explicitly evaluate their own risk profile and harden the execution harness
where appropriate. This specification intentionally does not mandate a single hardening posture, but
implementations SHOULD NOT assume that Trello card data, repository contents, prompt inputs, or tool
arguments are fully trustworthy just because they originate inside a normal workflow.

Possible hardening measures include:

- Tightening Codex approval and sandbox settings described elsewhere in this specification instead
  of running with a maximally permissive configuration.
- Adding external isolation layers such as OS/container/VM sandboxing, network restrictions, or
  separate credentials beyond the built-in Codex policy controls.
- Filtering which Trello boards, lists, labels, members, or cards are eligible for dispatch so
  untrusted or out-of-scope tasks do not automatically reach the agent.
- Narrowing the `trello_rest` tool so it can only read or mutate data inside the intended board
  scope, rather than exposing general workspace-wide Trello access.
- Disallowing destructive Trello API methods by default.
- Reducing the set of client-side tools, credentials, filesystem paths, and network destinations
  available to the agent to the minimum needed for the workflow.
- Using a dedicated Trello token with the minimum practical scope and membership needed for the
  configured board.

The correct controls are deployment-specific, but implementations SHOULD document them clearly and
treat harness hardening as part of the core safety model rather than an optional afterthought.

## 16. Reference Algorithms (Language-Agnostic)

### 16.1 Service Startup

```text
function start_service():
  configure_logging()
  start_observability_outputs()
  start_workflow_watch(on_change=reload_and_reapply_workflow)

  state = {
    poll_interval_ms: get_config_poll_interval_ms(),
    max_concurrent_agents: get_config_max_concurrent_agents(),
    running: {},
    claimed: set(),
    retry_attempts: {},
    ignored_worker_identities: bounded_set(),
    completed: set(),
    codex_totals: {input_tokens: 0, output_tokens: 0, total_tokens: 0, seconds_running: 0},
    codex_rate_limits: null
  }

  validation = validate_dispatch_config()
  if validation is not ok:
    log_validation_error(validation)
    fail_startup(validation)

  startup_terminal_workspace_cleanup()

  schedule_tick(delay_ms=0)

  event_loop(state)
```

### 16.2 Poll-and-Dispatch Tick

```text
on_tick(state):
  state = reconcile_running_cards(state)

  validation = validate_dispatch_config()
  if validation is not ok:
    log_validation_error(validation)
    notify_observers()
    schedule_tick(state.poll_interval_ms)
    return state

  cards = tracker.fetch_candidate_cards()
  if cards failed:
    log_tracker_error()
    notify_observers()
    schedule_tick(state.poll_interval_ms)
    return state

  for card in sort_for_dispatch(cards):
    if no_available_slots(state):
      break

    if should_dispatch(card, state):
      state = dispatch_card(card, state, attempt=null)

  notify_observers()
  schedule_tick(state.poll_interval_ms)
  return state
```

### 16.3 Reconcile Active Runs

```text
function reconcile_running_cards(state):
  state = reconcile_stalled_runs(state)

  running_ids = keys(state.running)
  if running_ids is empty:
    return state

  refreshed = tracker.fetch_card_states_by_ids(running_ids)
  if refreshed global failure:
    log_debug("keep workers running")
    return state

  for running_id in running_ids:
    result = refreshed.get(running_id)

    if result is missing_or_deleted:
      state = terminate_running_card(
        state, running_id, cleanup_workspace=true, suppress_retry=true
      )
    else if result failed:
      log_debug("keep worker running for unrefreshed card")
    else if is_out_of_configured_board_scope(result.card):
      state = terminate_running_card(
        state, result.card.id, cleanup_workspace=false, suppress_retry=true
      )
    else if is_terminal_card(result.card):
      state = terminate_running_card(
        state, result.card.id, cleanup_workspace=true, suppress_retry=true
      )
    else if is_active_card(result.card):
      state.running[result.card.id].card = result.card
    else:
      state = terminate_running_card(
        state, result.card.id, cleanup_workspace=false, suppress_retry=true
      )

  return state
```

```text
function terminate_running_card(state, card_id, cleanup_workspace, suppress_retry):
  running_entry = state.running.remove(card_id)
  if running_entry is missing:
    return state

  if running_entry.worker_identity is not null:
    state.ignored_worker_identities.add(
      running_entry.worker_identity,
      expires_after=max_worker_shutdown_grace_period()
    )
  kill_worker_best_effort(running_entry.worker_handle)

  if cleanup_workspace:
    workspace_manager.remove_for_identifier_if_present(running_entry.identifier)

  if suppress_retry:
    state.claimed.remove(card_id)
  else:
    state = schedule_retry(state, card_id, next_attempt_from(running_entry), {
      identifier: running_entry.identifier,
      error: "worker terminated"
    })

  return state
```

### 16.4 Dispatch One Card

```text
function dispatch_card(card, state, attempt):
  if card.id in state.claimed or card.id in state.running:
    return state

  state.claimed.add(card.id)
  cancel_retry_timer_if_present(card.id)
  state.retry_attempts.remove(card.id)
  worker_identity = new_unique_worker_identity()

  state.running[card.id] = {
    worker_handle: null,
    monitor_handle: null,
    worker_identity,
    start_status: "pending_spawn",
    identifier: card.identifier,
    card,
    session_id: null,
    codex_app_server_pid: null,
    last_codex_message: null,
    last_codex_event: null,
    last_codex_timestamp: null,
    codex_input_tokens: 0,
    codex_output_tokens: 0,
    codex_total_tokens: 0,
    last_reported_input_tokens: 0,
    last_reported_output_tokens: 0,
    last_reported_total_tokens: 0,
    retry_attempt: normalize_attempt(attempt),
    started_at: now_utc()
  }

  worker = spawn_worker(
    identity=worker_identity,
    fn -> run_agent_attempt(card, attempt, worker_identity, parent_orchestrator_pid) end
  )

  if worker spawn failed:
    state.running.remove(card.id)
    return schedule_retry(state, card.id, next_attempt(attempt), {
      identifier: card.identifier,
      error: "failed to spawn agent"
    })

  state.running[card.id].worker_handle = worker.handle
  state.running[card.id].monitor_handle = worker.monitor_handle
  state.running[card.id].start_status = "running"

  return state
```

### 16.5 Worker Attempt (Workspace + Prompt + Agent)

```text
function run_agent_attempt(card, attempt, worker_identity, orchestrator_channel):
  workspace = workspace_manager.create_for_card(card.identifier)
  if workspace failed:
    fail_worker("workspace error")

  if run_hook("before_run", workspace.path) failed:
    fail_worker("before_run hook error")

  session = app_server.start_session(workspace=workspace.path)
  if session failed:
    run_hook_best_effort("after_run", workspace.path)
    fail_worker("agent session startup error")

  max_turns = config.agent.max_turns
  turn_number = 1

  while true:
    prompt = build_turn_prompt(workflow_template, card, attempt, turn_number, max_turns)
    if prompt failed:
      app_server.stop_session(session)
      run_hook_best_effort("after_run", workspace.path)
      fail_worker("prompt error")

    turn_result = app_server.run_turn(
      session=session,
      prompt=prompt,
      card=card,
      on_message=(msg) -> send(orchestrator_channel, {
        codex_update,
        card.id,
        worker_identity,
        msg
      })
    )

    if turn_result failed:
      app_server.stop_session(session)
      run_hook_best_effort("after_run", workspace.path)
      fail_worker("agent turn error")

    refreshed_card = tracker.fetch_card_states_by_ids([card.id])
    if refreshed_card global failure:
      app_server.stop_session(session)
      run_hook_best_effort("after_run", workspace.path)
      fail_worker("card state refresh error")

    result = refreshed_card.get(card.id)
    if result is missing_or_deleted:
      break

    if result failed:
      app_server.stop_session(session)
      run_hook_best_effort("after_run", workspace.path)
      fail_worker("card state refresh error")

    if result contains card:
      card = result.card

    if is_out_of_configured_board_scope(card):
      break

    if is_terminal_card(card):
      break

    if not is_active_card(card):
      break

    if turn_number >= max_turns:
      break

    turn_number = turn_number + 1

  app_server.stop_session(session)
  run_hook_best_effort("after_run", workspace.path)

  exit_normal()
```

### 16.6 Worker Exit and Retry Handling

```text
function current_running_entry_for_worker_event(card_id, worker_identity, state):
  state.ignored_worker_identities.remove_expired()

  if worker_identity in state.ignored_worker_identities:
    state.ignored_worker_identities.remove(worker_identity)
    return ignored

  running_entry = state.running.get(card_id)
  if running_entry is missing:
    return missing

  if running_entry.worker_identity != worker_identity:
    return stale

  return running_entry
```

```text
on_codex_update(card_id, worker_identity, msg, state):
  running_entry = current_running_entry_for_worker_event(card_id, worker_identity, state)
  if running_entry is ignored or missing or stale:
    log_debug("stale or unknown Codex update ignored")
    return state

  state = apply_codex_update_to_running_entry(state, card_id, msg)
  notify_observers()
  return state
```

```text
on_worker_exit(card_id, worker_identity, reason, state):
  running_entry = current_running_entry_for_worker_event(card_id, worker_identity, state)
  if running_entry is ignored or missing or stale:
    log_debug("stale or unknown worker exit ignored")
    return state

  state.running.remove(card_id)
  state = add_runtime_seconds_to_totals(state, running_entry)

  if reason == normal:
    state.completed.add(card_id)  # bookkeeping only
    state = schedule_retry(state, card_id, 1, {
      identifier: running_entry.identifier,
      delay_type: continuation
    })
  else:
    state = schedule_retry(state, card_id, next_attempt_from(running_entry), {
      identifier: running_entry.identifier,
      error: format("worker exited: %reason")
    })

  notify_observers()
  return state
```

```text
on_retry_timer(card_id, state):
  retry_entry = state.retry_attempts.pop(card_id)
  if missing:
    return state

  refreshed = tracker.fetch_card_states_by_ids([card_id])
  if refreshed global failure:
    return schedule_retry(state, card_id, retry_entry.attempt + 1, {
      identifier: retry_entry.identifier,
      error: "retry card refresh failed"
    })

  result = refreshed.get(card_id)

  if result is missing_or_deleted:
    state.claimed.remove(card_id)
    workspace_manager.remove_for_identifier_if_present(retry_entry.identifier)
    return state

  if result failed:
    return schedule_retry(state, card_id, retry_entry.attempt + 1, {
      identifier: retry_entry.identifier,
      error: "retry card refresh failed"
    })

  card = result.card

  if is_out_of_configured_board_scope(card):
    state.claimed.remove(card_id)
    return state

  if is_terminal_card(card):
    state.claimed.remove(card_id)
    workspace_manager.remove_for_identifier_if_present(card.identifier or retry_entry.identifier)
    return state

  if not is_active_card(card):
    state.claimed.remove(card_id)
    return state

  if not should_dispatch_ignoring_claim(card, state):
    return schedule_retry(state, card_id, retry_entry.attempt + 1, {
      identifier: card.identifier,
      error: "card is active but not currently dispatch-eligible"
    })

  if available_slots(state) == 0:
    return schedule_retry(state, card_id, retry_entry.attempt + 1, {
      identifier: card.identifier,
      error: "no available orchestrator slots"
    })

  state.claimed.remove(card_id)
  return dispatch_card(card, state, attempt=retry_entry.attempt)
```

## 17. Test and Validation Matrix

A conforming implementation SHOULD include tests that cover the behaviors defined in this
specification.

Validation profiles:

- `Core Conformance`: deterministic tests REQUIRED for all conforming implementations.
- `Trello Workflow Conformance`: REQUIRED when the workflow expects the agent to perform Trello
  handoff transitions.
- `Extension Conformance`: REQUIRED only for OPTIONAL features that an implementation chooses to
  ship.
- `Real Integration Profile`: environment-dependent smoke/integration checks RECOMMENDED before
  production use.

Unless otherwise noted, Sections 17.1 through 17.7 are `Core Conformance`. Bullets that begin with
`If ... is implemented` are `Extension Conformance`.

### 17.1 Workflow and Config Parsing

- Workflow file path precedence:
  - explicit runtime path is used when provided
  - cwd default is `WORKFLOW.md` when no explicit runtime path is provided
- Workflow file changes are detected and trigger re-read/re-apply without restart
- Invalid workflow reload keeps last known good effective configuration and emits an
  operator-visible error
- Missing `WORKFLOW.md` returns typed error
- Invalid YAML front matter returns typed error
- Front matter non-map returns typed error
- Config defaults apply when OPTIONAL values are missing
- `tracker.kind` validation enforces currently supported kind (`trello`)
- `tracker.api_key` works, including `$VAR` indirection
- `tracker.api_token` works, including `$VAR` indirection
- `$VAR` resolution works for tracker API key, tracker API token, and path values
- `~` path expansion works
- `tracker.blocker_enforced_states` defaults and normalization work
- `codex.command` is preserved as a shell command string
- Per-state concurrency override map normalizes state names and ignores invalid values
- Priority label map normalizes label names and ignores invalid values
- Prompt template renders `card`, compatibility alias `issue`, and `attempt`
- `card` and `issue` prompt variables contain identical normalized card data
- Prompt rendering fails on unknown variables, strict mode

### 17.2 Workspace Manager and Safety

- Deterministic workspace path per card identifier
- Missing workspace directory is created
- Existing workspace directory is reused
- Existing non-directory path at workspace location is handled safely, replace or fail per
  implementation policy
- OPTIONAL workspace population/synchronization errors are surfaced
- `after_create` hook runs only on new workspace creation
- `before_run` hook runs before each attempt and failure/timeouts abort the current attempt
- `after_run` hook runs after each attempt and failure/timeouts are logged and ignored
- `before_remove` hook runs on cleanup and failures/timeouts are ignored
- Workspace path sanitization and root containment invariants are enforced before agent launch
- Agent launch uses the per-card workspace path as cwd and rejects out-of-root paths
- Prefix checks treat workspace root as a directory boundary, not a plain string prefix

### 17.3 Trello Tracker Client

- Candidate card fetch uses active states/list names and board ID
- Candidate card fetch treats `active_list_ids` as authoritative for list-backed open cards when
  configured
- Terminal card fetch treats `terminal_list_ids` as authoritative for list-backed open cards when
  configured
- Trello auth is sent without logging API key/token values
- Trello API fetch maps `idList` to Trello list name
- Trello API fetch handles archived card, archived list, and archived board states separately
- `fetch_terminal_cards()` includes configured terminal states and terminal list IDs
- `fetch_terminal_cards()` uses a card filter that includes archived cards when archived cards are
  terminal
- `fetch_terminal_cards()` fetches list cards when needed for terminal or archived list coverage
- Candidate card fetch accounts for endpoint limits, batching, and rate limits when present
- If proactive local Trello rate limiting is implemented, it tracks API-key and API-token buckets
  independently
- `429` rate-limit responses are retried within configured bounds
- Archived cards normalize to terminal state `Archived` when configured
- Cards in archived lists normalize to `ArchivedList` when list metadata is available
- Cards in archived boards normalize to `ArchivedBoard` when board metadata is available
- Deleted/not-found cards from per-card lookup produce a typed missing result
- Labels are normalized to lowercase
- Priority is derived from configured priority labels
- Blockers default to empty when no Trello-specific blocker convention is implemented
- If blocker derivation is implemented, blockers are normalized according to the documented
  convention
- Card state refresh by ID returns minimal normalized cards or typed per-card missing results
- Error mapping covers request errors, non-2xx statuses, rate limits, missing list mappings, auth
  failures, permission failures, not-found cards, and malformed payloads

### 17.4 Orchestrator Dispatch, Reconciliation, and Retry

- Dispatch sort order is priority, active-state/list order, Trello position, creation time,
  identifier
- Card in `tracker.blocker_enforced_states` with non-terminal blockers is not eligible
- Card in `tracker.blocker_enforced_states` with terminal blockers is eligible
- Card is claimed before worker spawn begins
- Pending running entry with unique per-spawn worker identity is recorded before worker spawn can
  emit lifecycle events
- Worker-originated events include the unique per-spawn worker identity
- Stale worker-originated events for an older worker identity do not update session metadata, token
  counters, stall timestamps, retry state, running card snapshots, or newer running entries
- Ignored worker identity tracking is bounded by size, TTL, or both
- Worker spawn failure leaves a retry entry or explicitly releases the claim
- Active-state card refresh updates running entry state
- Non-active state stops running agent without workspace cleanup
- Terminal state stops running agent and cleans workspace
- Archived card stops running agent and cleans workspace when `Archived` is terminal
- Card in archived list stops running agent and cleans workspace when `ArchivedList` is terminal
- Card in archived board stops running agent and cleans workspace when `ArchivedBoard` is terminal
- Deleted/not-found card stops running agent and cleans workspace
- Terminal, deleted, board-out-of-scope, and non-active reconciliation terminations suppress retry
- Reconciliation-suppressed worker exits are ignored for retry purposes
- Retry timer checks current card state before re-dispatch
- Retry timer cleans terminal/deleted workspaces when possible
- Reconciliation with no running cards is a no-op
- Normal worker exit schedules a short continuation retry, attempt 1
- Abnormal worker exit increments retries with 10s-based exponential backoff
- Retry backoff cap uses configured `agent.max_retry_backoff_ms`
- Retry queue entries include attempt, due time, identifier, and error
- Stall detection kills stalled sessions and schedules retry
- Slot exhaustion requeues retries with explicit error reason
- If a snapshot API is implemented, it returns running rows, retry rows, token totals, and rate
  limits
- If a snapshot API is implemented, timeout/unavailable cases are surfaced

### 17.5 Coding-Agent App-Server Client

- Launch command uses workspace cwd and invokes the configured command
- On POSIX systems, launch supports `bash -lc <codex.command>`
- Session startup follows the targeted Codex app-server protocol
- For stdio app-server transport, startup sends initialize, then initialized, before thread start or
  resume
- Client identity/capability payloads are valid when the targeted Codex app-server protocol requires
  them
- Policy-related startup payloads use the implementation's documented approval/sandbox settings
- Thread and turn identities exposed by the targeted protocol are extracted and used to emit
  `session_started`
- Request/response read timeout is enforced
- Turn timeout is enforced
- Transport framing required by the targeted protocol is handled correctly
- For stdio-based transports, diagnostic stderr handling is kept separate from the protocol stream
- Command/file-change approvals are handled according to the implementation's documented policy
- Unsupported dynamic tool calls are rejected without stalling the session
- User input requests are handled according to the implementation's documented policy and do not
  stall indefinitely
- Usage and rate-limit telemetry exposed by the targeted protocol is extracted
- Approval, user-input-required, usage, and rate-limit signals are interpreted according to the
  targeted protocol
- If client-side tools are implemented, session startup advertises the supported tool specs using
  the targeted app-server protocol
- If client-side tools use Codex app-server experimental APIs, startup enables the targeted
  protocol's experimental capability
- If the `trello_rest` client-side tool extension is implemented and enabled for the session:
  - the tool is advertised to the session
  - valid `method` / `path` / `query` / `body` inputs execute against configured Trello auth
  - HTTP non-2xx responses produce `success=false` while preserving safe response details
  - invalid arguments, missing auth, unsupported operations, and transport failures return
    structured failure payloads
  - unsupported tool names still fail without stalling the session
  - caller-supplied Trello auth material in `query`, `body`, or implementation-supported headers is
    rejected
  - current-card-scoped operations reject card IDs outside the active worker session context unless
    explicitly allowlisted
  - generic write requests are classified before execution; unclassified writes fail with structured
    policy errors
  - `trello_tools` policy violations return structured policy errors
  - destructive operations are disallowed by default unless explicitly configured
- If the standardized `trello_rest` tool is implemented but disabled for the session, it is withheld
  or direct invocation fails with a structured disabled-tool error

### 17.6 Observability

- Validation failures are operator-visible
- Structured logging includes card/session context fields
- Logging sink failures do not crash orchestration
- Trello credentials are redacted from logs
- Token/rate-limit aggregation remains correct across repeated agent updates
- If a human-readable status surface is implemented, it is driven from orchestrator state and does
  not affect correctness
- If humanized event summaries are implemented, they cover key wrapper/agent event classes without
  changing orchestrator behavior

### 17.7 CLI and Host Lifecycle

- CLI accepts a positional workflow path argument (`path-to-WORKFLOW.md`)
- CLI uses `./WORKFLOW.md` when no workflow path argument is provided
- CLI errors on nonexistent explicit workflow path or missing default `./WORKFLOW.md`
- CLI surfaces startup failure cleanly
- CLI exits with success when application starts and shuts down normally
- CLI exits nonzero when startup fails or the host process exits abnormally

### 17.8 Trello Workflow Conformance

These checks are REQUIRED when the workflow expects the agent to perform Trello handoff transitions.

- Scoped Trello write-capable tool, MCP tool, or equivalent is available to the agent.
- `trello_tools.enabled=false` disables or withholds the standardized `trello_rest` tool.
- `trello_tools.allow_writes=false` causes write requests to fail with structured policy errors.
- Comment writes are allowed only when `trello_tools.allow_comments` permits them.
- Card moves are allowed only to configured board-local list IDs or names.
- Checklist writes are allowed only when `trello_tools.allow_checklists` permits them.
- URL attachment writes are allowed only when `trello_tools.allow_url_attachments` permits them.
- Destructive operations are disabled unless explicitly configured.
- Startup validates write capability or emits an operator-visible warning when verification is not
  possible without side effects.

### 17.9 Real Integration Profile (RECOMMENDED)

These checks are RECOMMENDED for production readiness and MAY be skipped in CI when credentials,
network access, or external service permissions are unavailable.

- A real Trello smoke test can be run with valid credentials supplied by `TRELLO_API_KEY` and
  `TRELLO_API_TOKEN` or a documented local bootstrap mechanism.
- Real integration tests SHOULD use isolated test cards/workspaces and clean up Trello artifacts
  when practical.
- Real integration tests SHOULD verify behavior against open cards, archived cards, cards in
  archived lists, terminal-list cards, and per-card not-found/deleted lookup behavior when
  practical.
- A skipped real-integration test SHOULD be reported as skipped, not silently treated as passed.
- If a real-integration profile is explicitly enabled in CI or release validation, failures SHOULD
  fail that job.

## 18. Implementation Checklist (Definition of Done)

Use the same validation profiles as Section 17:

- Section 18.1 = `Core Conformance`
- Section 18.2 = `Trello Workflow Conformance`
- Section 18.3 = `Extension Conformance`
- Section 18.4 = `Real Integration Profile`

### 18.1 REQUIRED for Conformance

- Workflow path selection supports explicit runtime path and cwd default
- `WORKFLOW.md` loader with YAML front matter + prompt body split
- Typed config layer with defaults and `$` resolution
- Dynamic `WORKFLOW.md` watch/reload/re-apply for config and prompt
- Polling orchestrator with single-authority mutable state
- Trello tracker client with candidate fetch + state refresh + terminal fetch
- Trello normalization that distinguishes card archived state, list archived state, board archived
  state, and per-card deleted/not-found state
- Trello API rate-limit handling for `429` responses
- Trello auth handling with credential redaction
- Workspace manager with sanitized per-card workspaces
- Workspace lifecycle hooks (`after_create`, `before_run`, `after_run`, `before_remove`)
- Hook timeout config (`hooks.timeout_ms`, default `60000`)
- Coding-agent app-server subprocess client using compatible app-server protocol handling
- Codex launch command config (`codex.command`, default `codex app-server`)
- Strict prompt rendering with `card`, compatibility alias `issue`, and `attempt` variables
- Exponential retry queue with continuation retries after normal exit
- Configurable retry backoff cap (`agent.max_retry_backoff_ms`, default 5m)
- Reconciliation that stops runs on terminal/non-active/deleted Trello states
- Reconciliation termination policies that suppress retry for terminal/deleted/board-out-of-scope/
  non-active cards and retry stalled cards
- Workspace cleanup for terminal cards, startup sweep + active/retry transition
- Claim-before-spawn dispatch behavior with a pending running entry before worker spawn and unique
  per-spawn worker identity checks on worker-originated events
- Structured logs with `card_id`, `card_identifier`, and `session_id`
- Operator-visible observability, structured logs; OPTIONAL snapshot/status surface

### 18.2 REQUIRED for Trello Workflow Conformance

Required when the workflow expects the agent to perform Trello handoff transitions:

- Scoped Trello write-capable client-side tool, MCP tool, or documented equivalent.
- `trello_tools` policy enforcement before every Trello tool request.
- `trello_tools.enabled=true` for the standardized `trello_rest` tool, or equivalent documented
  enablement for non-`trello_rest` tools.
- Trello tool execution context includes the active worker session's current card ID and resolved
  board ID.
- Ability to perform every Trello write the workflow tells the agent to perform.
- Ability to add comments to the current card when the workflow uses comment handoff and
  `trello_tools.allow_comments` permits it.
- Ability to move the current card to allowed board-local lists.
- Ability to maintain the current-card `## Codex Workpad` comment when the workflow uses the workpad
  pattern and `trello_tools.allow_comments` permits it.
- Ability to add or update checklist items on the current card when the workflow asks for checklist
  writes and `trello_tools.allow_checklists` permits it.
- Ability to add policy-enabled URL attachments, such as GitHub PR links, when the workflow asks for
  URL attachment writes and `trello_tools.allow_url_attachments` permits it.
- Write operations are scoped to the configured board and current card unless explicitly allowlisted.
- If generic `trello_rest` is implemented, writes are classified and unclassified writes fail with
  structured policy errors.
- Destructive operations are disabled by default.
- Write-capable operations require a Trello token with write permission, or an operator-visible
  warning when startup cannot verify write capability without side effects.

### 18.3 RECOMMENDED Extensions (Not REQUIRED for Core Conformance)

- HTTP server extension honors CLI `--port` over `server.port`, uses a safe default bind host, and
  exposes the baseline endpoints/error semantics in Section 13.7 if shipped.
- HTTP server extension access-controls operational endpoints when bound to a non-loopback
  interface.
- Repository-local agent skills follow Section 19.1 when generated workflows reference skills.
- Manual systemd deployments follow Section 19.2 when this repository's deployment template is used.
- Ansible desired-state deployments follow Section 19.3 when the optional playbook is used.
- Deployment host path access follows Section 19.4 when operators expose files outside managed
  workspaces.
- The opt-in Java live E2E harness follows Section 19.5 when external Trello credentials are
  supplied.
- Java repository quality gates follow Section 19.6 for this repository's maintained implementation.
- `trello_rest` client-side tool extension exposes scoped Trello REST access through the app-server
  session using configured Symphony auth.
- `trello_rest` client-side tool extension disallows destructive operations by default.
- TODO: Persist retry queue and session metadata across process restarts.
- TODO: Make observability settings configurable in workflow front matter without prescribing UI
  implementation details.
- TODO: Consider additional typed Trello write tools, such as checklist and URL attachment helpers,
  while keeping workflow-specific mutation decisions in either the agent toolchain or explicit
  workflow configuration rather than implicit orchestrator policy.
- TODO: Add pluggable tracker adapters beyond Trello.

### 18.4 Operational Validation Before Production (RECOMMENDED)

- Run the `Real Integration Profile` from Section 17.9 with valid credentials and network access.
- Verify hook execution and workflow path resolution on the target host OS/shell environment.
- If the OPTIONAL HTTP server is shipped, verify the configured port behavior and loopback/default
  bind expectations on the target environment.
- Verify that the Trello token has read permission for scheduler-only deployments and write
  permission only when write-capable Trello tools are intentionally enabled.
- Verify proactive local Trello rate limiting against documented API-key and API-token limits when
  the implementation chooses to ship local throttling.

## 19. Java Implementation Extension Profiles

The profiles in this section document optional behavior shipped by this Java repository. They are not
required for a language-agnostic Symphony-for-Trello implementation, but they are part of this
repository's supported behavior when the corresponding files, commands, or deployment paths are used.

### 19.1 Repository-Local Agent Skills

Generated workflows MAY reference repository-local Codex skills to keep `WORKFLOW.md` readable while
still giving the agent detailed operational procedures.

When this profile is used:

- skill files live under `.codex/skills/<skill-name>/SKILL.md`
- the workflow prompt names the skills that are relevant for the current board workflow
- skills are instructional prompt context for the coding agent, not Java runtime plugins
- missing or ignored skills MUST NOT break scheduler startup, but can reduce agent behavior quality
- skills that cause Trello writes still rely on the scoped Trello tools from Section 10.5 and the
  `trello_tools` policy from Section 5.3.7
- skills MUST NOT require the agent to read Trello API keys, Trello tokens, Codex auth files, or
  other deployment secrets

This Java repository ships skills for Trello workpad updates, Trello handoff, PR feedback sweeps,
repository sync, commits, push/PR preparation, landing from `Merging`, and live-run debugging. The
recommended generated workflow references those skills only as supporting instructions. The workflow
front matter and scoped tools remain the authoritative runtime controls.

### 19.2 Manual systemd Deployment Profile

The manual systemd deployment profile lets one installed application run one or more workflow
services on a Linux host while preserving the one-process-per-workflow contract.

When this profile is used:

- one systemd template instance runs one workflow file
- the instance name maps to `/etc/symphony-trello/workflows/<name>.WORKFLOW.md`
- each workflow file still contains exactly one `tracker.board_id`
- each workflow service SHOULD use its own HTTP port and workspace root
- the packaged Quarkus application is installed under `/opt/symphony-trello`
- workflow files and root-managed config live under `/etc/symphony-trello`
- persistent runtime workspaces live under `/var/lib/symphony-trello`
- Trello credentials SHOULD be loaded through systemd credential files and referenced from workflow
  config with `file:$CREDENTIALS_DIRECTORY/...`
- Trello credential environment variables SHOULD be removed before launching Codex or workflow hooks
- Codex CLI authentication SHOULD reuse the existing Codex auth file created by `codex login`
- the unit SHOULD run as a dedicated `symphony-trello` user
- the unit SHOULD bind the HTTP server to loopback by default unless an operator intentionally
  exposes it

The deployment profile is intentionally transparent. Operators can inspect and manage each workflow
with normal `systemctl` and `journalctl` commands.

### 19.3 Ansible Desired-State Deployment Profile

The Ansible deployment profile automates the manual systemd layout from declared desired state. It is
an optional deployment path, not a runtime dependency.

When this profile is used:

- `symphony_trello_workflows` is the desired state for deployed workflow services
- each declared workflow has a local source file and a service name
- the playbook MAY expose variables for host-specific choices such as
  `symphony_trello_service_environment`, `symphony_trello_codex_auth_src`,
  `symphony_trello_allowed_project_roots`, and `symphony_trello_codex_danger_full_access`
- rerunning the playbook SHOULD be idempotent when inputs have not changed
- changed application inputs SHOULD rebuild the app on the controller before deployment
- changed app artifacts or workflow files SHOULD restart only affected managed services
- removing a workflow from desired state SHOULD stop and disable the matching service and remove the
  managed workflow file
- Trello secrets SHOULD be stored in Ansible Vault and installed as root-only systemd credential
  files
- real inventory, vault files, vault password files, `.env`, and private workflow files MUST remain
  ignored and uncommitted
- the playbook SHOULD validate systemd units and lint Ansible files in CI without requiring real
  deployment secrets

This profile uses the same one-process-per-workflow model as the manual systemd profile. It adds
repeatable installation, update, restart, and removal behavior for operators who manage more than one
workflow or redeploy often.

### 19.4 Deployment Host Path Access Profile

Deployed Symphony should expose host paths explicitly. The default posture is that Codex can read and
write Symphony-managed workspace paths, but not arbitrary host files.

When this profile is used:

- undeclared host paths are blocked by default for security reasons
- an operator may allow one or more files or folders with documented systemd or Ansible settings
- allowed host paths SHOULD be reflected in both the systemd filesystem policy and Codex's
  `workspaceWrite` writable roots when that sandbox mode is used
- the Ansible profile maps allowed host paths from `symphony_trello_allowed_project_roots` into the
  systemd drop-in and Codex writable-root environment for managed services
- the manual systemd profile documents the equivalent `BindPaths`, `ReadWritePaths`, and
  `SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS` settings
- a less strict Codex inner sandbox MAY be enabled for trusted workflows while systemd still limits
  the visible writable host paths
- blocker comments for filesystem access problems SHOULD state the inaccessible path, why it was
  inaccessible, where the per-card workspace is, and which documented setting relaxes access

Generated workflows SHOULD prefer writable per-card checkouts over editing shared host checkouts.
When a readable host checkout is not writable, the agent should clone it into the per-card workspace
and work there. This preserves the security default while still allowing cards to use existing host
repositories as source context when an operator has allowed read access.

### 19.5 Opt-In Java Live E2E Harness

Live Trello and real-Codex verification is environment-dependent. This repository therefore keeps
normal CI deterministic while providing an opt-in Java integration-test harness and documented manual
strict real-Codex procedures.

When this profile is used:

- normal `verify` MUST NOT require Trello credentials or live Codex execution
- opt-in live tests MUST require explicit credentials or flags before touching Trello
- this Java repository uses `SYMPHONY_RUN_LIVE_E2E=1` for fake-Codex live Trello checks and
  `SYMPHONY_RUN_REAL_CODEX_DOCKER_E2E=1` for the slower real-Codex Docker live check
- live tests SHOULD create isolated disposable Trello boards or cards
- deterministic fake-Codex live tests SHOULD run before strict real-Codex checks
- strict real-Codex checks SHOULD wait for Trello handoff and `/api/v1/state` to drain to zero
  running and retrying entries before reporting success
- live verification loops SHOULD poll Trello comments and the card's current list, not only local
  service state
- live test output and committed documentation MUST avoid leaking Trello tokens, private board IDs,
  private card IDs, private project names, account names, or private host paths

### 19.6 Java Repository Quality Profile

This profile documents build and maintenance rules for this Java repository. These rules are not
runtime conformance requirements for other Symphony-for-Trello implementations.

When this profile is used:

- the repository targets Java 25 LTS
- the Maven wrapper uses Maven 3
- Quarkus manages the JUnit 6 test stack
- Mockito uses the documented Java-agent startup pattern instead of dynamic self-attachment
- `./mvnw verify` runs deterministic tests, the Quarkus package build, Spotless checks, PMD's narrow
  source rule for unnecessary fully qualified Java type names, ArchUnit architecture checks, and the
  JaCoCo coverage gate
- line coverage MUST stay at or above 80 percent
- ArchUnit SHOULD reject circular dependencies between production top-level packages
- Markdown and ADR linting SHOULD run in CI
- Renovate SHOULD keep Maven dependencies, GitHub Actions, and pinned tool versions current
- GitHub Actions SHOULD be pinned to full commit SHAs, with Renovate allowed to update non-major
  action pins after the configured release-age delay

## Appendix A. SSH Worker Extension (OPTIONAL)

This appendix describes a common extension profile in which Symphony keeps one central orchestrator
but executes worker runs on one or more remote hosts over SSH.

Extension config:

- `worker.ssh_hosts` (list of SSH host strings, OPTIONAL)
  - When omitted, work runs locally.
- `worker.max_concurrent_agents_per_host` (positive integer, OPTIONAL)
  - Shared per-host cap applied across configured SSH hosts.

### A.1 Execution Model

- The orchestrator remains the single source of truth for polling, claims, retries, and
  reconciliation.
- `worker.ssh_hosts` provides the candidate SSH destinations for remote execution.
- Each worker run is assigned to one host at a time, and that host becomes part of the run's
  effective execution identity along with the card workspace.
- `workspace.root` is interpreted on the remote host, not on the orchestrator host.
- The coding-agent app-server is launched over SSH stdio instead of as a local subprocess, so the
  orchestrator still owns the session lifecycle even though commands execute remotely.
- Continuation turns inside one worker lifetime SHOULD stay on the same host and workspace.
- A remote host SHOULD satisfy the same basic contract as a local worker environment: reachable
  shell, writable workspace root, coding-agent executable, and any required auth or repository
  prerequisites.

### A.2 Scheduling Notes

- SSH hosts MAY be treated as a pool for dispatch.
- Implementations MAY prefer the previously used host on retries when that host is still available.
- `worker.max_concurrent_agents_per_host` is an OPTIONAL shared per-host cap across configured SSH
  hosts.
- When all SSH hosts are at capacity, dispatch SHOULD wait rather than silently falling back to a
  different execution mode.
- Implementations MAY fail over to another host when the original host is unavailable before work
  has meaningfully started.
- Once a run has already produced side effects, a transparent rerun on another host SHOULD be
  treated as a new attempt, not as invisible failover.

### A.3 Problems to Consider

- Remote environment drift:
  - Each host needs the expected shell environment, coding-agent executable, auth, and repository
    prerequisites.
- Workspace locality:
  - Workspaces are usually host-local, so moving a card to a different host is typically a cold
    restart unless shared storage exists.
- Path and command safety:
  - Remote path resolution, shell quoting, and workspace-boundary checks matter more once execution
    crosses a machine boundary.
- Startup and failover semantics:
  - Implementations SHOULD distinguish host-connectivity/startup failures from in-workspace agent
    failures so the same card is not accidentally re-executed on multiple hosts.
- Host health and saturation:
  - A dead or overloaded host SHOULD reduce available capacity, not cause duplicate execution or an
    accidental fallback to local work.
- Cleanup and observability:
  - Operators need to know which host owns a run, where its workspace lives, and whether cleanup
    happened on the right machine.
