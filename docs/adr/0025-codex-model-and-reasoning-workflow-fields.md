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
recommended reasoning effort. If the list does not mark a default, setup uses the first returned
model. If `model/list` succeeds but returns an empty list or unusable model details, setup falls
back to:

```yaml
codex:
  command: codex app-server
  model: gpt-5.5
  reasoning_effort: medium
```

The Java client sends `codex.model` as the app-server `model` field and sends
`codex.reasoning_effort` as the turn `effort` field. If either workflow value is omitted, Symphony
does not invent a replacement at runtime; the installed Codex CLI/app-server default or command
configuration decides the value.

If the installed Codex app-server cannot answer the model-list request, setup omits these
first-class workflow fields instead of assuming that an older app-server accepts them. Existing
workflow values are preserved during forced regeneration unless the user explicitly changes them.

### Consequences

* Good, because generated workflows show the selected model and reasoning effort directly.
* Good, because setup follows the installed Codex CLI recommendation at the time the board is
  connected.
* Good, because `codex.command` remains available for operators that need custom app-server launch
  configuration.
* Good, because existing hand-written workflows without these fields continue to use their Codex
  defaults.
* Bad, because the Java client must be updated if the app-server field names change.
* Bad, because generated workflows do not silently change later when a new Codex model launches.
* Bad, because setup needs a fallback path when Codex app-server cannot be queried.

### Confirmation

Run `./mvnw -q spotless:check verify`. Codex app-server client tests must assert the request payload
contains `model` and `effort` when the workflow configures them and omits them when the workflow
does not. Setup tests must assert generated workflows include explicit values when the model list is
compatible, omit them when compatibility is unknown, and preserve existing workflow values during
forced regeneration. Codex default resolver tests must assert setup honors the app-server
`model/list` default and distinguishes fallback defaults from unsupported first-class fields.

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
* Neutral, because the configured strings are intentionally not validated against a Java enum.
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

In Codex CLI 0.130.0, `ThreadStartParams` and `TurnStartParams` support `model`, and
`TurnStartParams` supports `effort` for reasoning effort.
