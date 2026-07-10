---
status: accepted
date: 2026-05-12
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, Installed Codex app-server schema]
informed: [Future maintainers]
---

# Configure Codex Model and Reasoning Through Workflow Fields

## Context and Problem Statement

Symphony for Trello launches Codex through the app-server protocol. Until now, generated workflows
made the command explicit but left model and reasoning selection to the installed Codex CLI defaults
or to extra command-line configuration embedded in `codex.command`.

The installed Codex app-server schema exposes first-class request fields for model selection and
turn reasoning effort. The workflow should make those choices visible without turning the command
string into the main configuration surface.

How should Symphony let a board choose its Codex model and reasoning effort while staying compatible
with the app-server protocol boundary?

## Decision Drivers

* Keep the workflow file as the source of truth for board behavior.
* Make generated model and reasoning choices easy to review in pull requests and deployments.
* Preserve the existing `codex.command` escape hatch for app-server launch details.
* Use fields supported by the installed Codex app-server schema.
* Avoid copying Codex-owned model or reasoning enums into Java validation code.

## Considered Options

* Discover Codex defaults during setup and write workflow fields that map to app-server request
  fields.
* Add workflow fields that map to app-server request fields with static generated defaults.
* Encode model and reasoning with `codex.command` `--config` flags.
* Rely entirely on the Codex CLI default model and reasoning effort.

## Decision Outcome

Chosen option: "Discover Codex defaults during setup and write workflow fields that map to
app-server request fields", because it makes the important choice explicit while keeping the command
focused on launching app-server.

During setup, the Java implementation starts the installed Codex app-server on stdio and calls
`model/list`. Generated workflows use the model marked as the Codex default and that model's
recommended reasoning effort when it has one. If the list does not mark a default, setup uses the
first non-hidden returned model with a usable model id. Each model's ordered
`supportedReasoningEfforts` is the authoritative source for setup choices and descriptions. Guided
setup displays that model's exact list with Codex-provided
descriptions, so values such as `xhigh`, `max`, and `ultra` become available without a Symphony code
change. It derives the default marker from the model's `defaultReasoningEffort` and the current
marker from the effective setup or preserved workflow value. Setup rejects a newly selected value
outside a non-empty advertised list and reports the accepted values. When a model does not advertise
an effort list, guided setup says so and accepts the value as a pass-through setting for
compatibility with custom or older catalogs. Setup retains hidden entries for explicit or preserved
model ids while excluding them from default selection.

This is compatible fail-fast validation: a newly selected effort outside the authoritative list
cannot run successfully for that Codex model. No successfully working configuration is removed;
existing workflow values stay preserved during regeneration and runtime workflow values remain
pass-through settings.

If `model/list` succeeds but returns an empty list or unusable model details, setup falls back to:

```yaml
codex:
  command: codex app-server
  model: gpt-5.5
  reasoning_effort: medium
```

Reasoning effort resolves in this order: an explicit setup effort, a preserved existing workflow
effort, and the exact selected model's recommendation. When model discovery succeeds but that exact
model is absent or has no recommendation, setup omits `reasoning_effort`. Guided setup uses an
unbracketed prompt in that case, so another model's recommendation is never presented as the current
one. When discovery itself is unsupported, an explicit model request keeps the `medium`
compatibility fallback unless a higher-precedence value exists. This fallback is limited to the
unsupported protocol path; a supported catalog miss is an authoritative absence, not a reason to
reuse the fallback model's effort.

`setup-local --dry-run` does not start Codex solely for catalog discovery because app-server startup
can initialize Codex-owned files. If no catalog is already available without side effects, dry-run
defers model-specific effort validation and the real setup run performs it before any Trello or
workflow mutation.

The Java client sends `codex.model` as the app-server `model` field and sends
`codex.reasoning_effort` as the turn `effort` field. If either workflow value is omitted, Symphony
does not invent a replacement at runtime; the installed Codex CLI/app-server default or command
configuration decides the value.

If the installed Codex app-server cannot answer the model-list request, setup omits these first-class
workflow fields when the operator requested neither a model nor a reasoning effort instead of
assuming that an older app-server accepts them. Existing workflow values are preserved during forced
regeneration unless the user explicitly changes them.

### Consequences

* Good, because generated workflows show the selected model and reasoning effort directly.
* Good, because setup follows the installed Codex CLI recommendation at the time the board is
  connected.
* Good, because selecting a model never inherits a recommendation from a different catalog entry.
* Good, because effort choices follow the installed model catalog, including new values without a
  Symphony release.
* Good, because `codex.command` remains available for operators that need custom app-server launch
  configuration.
* Good, because existing hand-written workflows without these fields continue to use their Codex
  defaults.
* Neutral, because setup permits pass-through values when a model does not advertise its supported
  efforts.
* Neutral, because a supported catalog without an exact recommendation produces a workflow that lets
  Codex choose its own reasoning default.
* Bad, because the Java client must be updated if the app-server field names change.
* Bad, because generated workflows do not silently change later when a new Codex model launches.
* Bad, because setup needs a fallback path when Codex app-server cannot be queried.

### Confirmation

Run `./mvnw -q spotless:check verify`. Codex app-server client tests must assert the request payload
contains `model` and `effort` when the workflow configures them and omits them when the workflow
does not. Setup tests must assert generated workflows include explicit values when the model list is
compatible, omit them when compatibility is unknown, and preserve existing workflow values during
forced regeneration. Codex default resolver tests must assert setup honors the app-server
`model/list` default, retains exact per-model effort lists and descriptions, and distinguishes
fallback defaults from unsupported first-class fields. Setup tests must also assert that every
advertised model-specific choice is displayed in catalog order, default and current values are
identified, an advertised `xhigh` value is accepted, an unsupported new value reports the accepted
choices, and a missing capability list keeps pass-through compatibility. Command-boundary tests must
also prove that a later catalog page supplies the exact selected-model recommendation, a supported
catalog miss or blank recommendation omits `reasoning_effort`, preserved and explicit values retain
precedence, and unsupported discovery keeps its compatibility fallback.

## Pros and Cons of the Options

### Discover Codex defaults during setup and write workflow fields

Ask the installed Codex app-server for its model catalog during setup, write the recommended values
to `WORKFLOW.md`, and pass them through to the app-server protocol at runtime.

* Good, because operators can review and change the values without editing shell command syntax.
* Good, because newly connected boards pick up the installed Codex CLI's current recommendation.
* Good, because the workflow stays stable after setup instead of changing unexpectedly when Codex
  releases a new default model.
* Neutral, because setup needs a fallback if Codex app-server cannot answer `model/list`.
* Bad, because generated workflows do not automatically upgrade when the Codex CLI default changes.

### Add workflow fields that map to app-server request fields with static generated defaults

Expose `codex.model` and `codex.reasoning_effort` in `WORKFLOW.md` and always generate the same
default values.

* Good, because operators can review and change the values without editing shell command syntax.
* Good, because the workflow values can be tested independently from the installed CLI defaults.
* Good, because this follows the existing pass-through treatment for Codex-owned policy fields.
* Neutral, because configured strings are validated against the installed model catalog rather than
  a Java enum.
* Bad, because the mapping depends on the targeted app-server protocol version.
* Bad, because generated workflows can lag behind the installed Codex CLI recommendation.

### Encode model and reasoning with `codex.command` `--config` flags

Keep the workflow schema unchanged and generate a command such as
`codex -c model="gpt-5.5" app-server`.

* Good, because the Codex CLI already understands command-line config overrides.
* Neutral, because it remains useful as an escape hatch for unusual deployments.
* Bad, because the most important model choice becomes harder to notice in generated workflows.
* Bad, because command quoting becomes more fragile across shells and platforms.

### Rely entirely on the Codex CLI default model and reasoning effort

Do not write model or reasoning values in generated workflows.

* Good, because Codex default changes arrive without Symphony changes.
* Bad, because deployments cannot tell which model a generated board is expected to use.
* Bad, because different machines can silently run different defaults for the same workflow.

## More Information

The schema used for this decision was inspected with:

```bash
codex app-server generate-json-schema --out <dir>
```

In Codex CLI 0.144.0, `ThreadStartParams` and `TurnStartParams` support `model`, and
`TurnStartParams` supports `effort` for reasoning effort. Each v2 model catalog entry includes
`supportedReasoningEfforts`, whose entries expose `reasoningEffort` and `description`. The default is
exposed separately as the model's `defaultReasoningEffort`; the catalog does not expose an effort
display name or current flag. The schema defines `ReasoningEffort` as a non-empty string rather than
a closed enum, so the selected model's catalog entry is the source of truth for supported values.
