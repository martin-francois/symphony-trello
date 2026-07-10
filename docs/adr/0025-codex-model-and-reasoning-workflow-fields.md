---
status: accepted
date: 2026-05-12
decision-makers: [François Martin, Codex]
consulted:
  - SPEC.md
  - Installed Codex app-server schema
  - "[GitHub issue #548](https://github.com/martin-francois/symphony-trello/issues/548)"
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

The Codex-owned `isDefault` marker does not necessarily identify Symphony for Trello's preferred
model for a new workflow. Symphony should prefer the broadly usable `gpt-5.6-terra` model when the
installed catalog offers it, without treating the paid-user `gpt-5.6-sol` model as an automatic
substitute.

How should Symphony let a board choose its Codex model and reasoning effort while staying compatible
with the app-server protocol boundary?

## Decision Drivers

* Keep the workflow file as the source of truth for board behavior.
* Make generated model and reasoning choices easy to review in pull requests and deployments.
* Preserve the existing `codex.command` escape hatch for app-server launch details.
* Use fields supported by the installed Codex app-server schema.
* Avoid copying Codex-owned model or reasoning enums into Java validation code.
* Prefer `gpt-5.6-terra` for newly generated workflows when the installed catalog makes it visible.
* Keep fallback model selection catalog-driven when Terra is unavailable.

## Considered Options

* Discover the catalog, prefer exact visible Terra, and then use generic catalog fallback rules.
* Follow the Codex `isDefault` marker without a Symphony model preference.
* Treat `gpt-5.6-sol` as a special fallback when Terra is unavailable.
* Add workflow fields that map to app-server request fields with static generated defaults.
* Encode model and reasoning with `codex.command` `--config` flags.
* Rely entirely on the Codex CLI default model and reasoning effort.

## Decision Outcome

Chosen option: "Discover the catalog, prefer exact visible Terra, and then use generic catalog
fallback rules", because it makes the product choice explicit while keeping availability and
capability details tied to the installed Codex CLI.

During setup, the Java implementation starts the installed Codex app-server on stdio and calls
`model/list`. For a new workflow without an explicit or preserved model, setup selects the exact
visible `gpt-5.6-terra` entry when present. If Terra is absent or hidden, setup selects the first
visible usable model marked `isDefault`; if no visible model is marked as the default, it selects the
first visible usable model in catalog order. There is no special fallback to `gpt-5.6-sol`. Sol can
still be selected explicitly, preserved from an existing workflow, or selected through the same
generic catalog fallback rules as any other model. Explicit setup choices, a model newly typed during
guided setup, and existing workflow values remain higher precedence than the new-workflow
recommendation. Each model's ordered
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

Model and reasoning precedence are resolved separately. For a new workflow without an explicit
model, a successful but empty or unusable catalog selects the deterministic compatibility fallback
model `gpt-5.5`. Reasoning effort then resolves from an explicit setup effort, a preserved existing
workflow effort or preserved omission, `medium` for that deterministic fallback model, and finally
the exact selected model's recommendation. An explicit effort can therefore replace `medium` while
keeping the fallback model. For an explicit or preserved model, or for a catalog-selected model from
an otherwise usable supported catalog, a missing recommendation omits `reasoning_effort`. Guided
setup uses an unbracketed prompt in that case, so another model's recommendation is never presented as
the current one. When discovery itself is unsupported, an explicit model request keeps the separate
`medium` compatibility fallback unless a higher-precedence value exists. Thus, the supported-empty or
unusable fallback pairs its deterministic model with `medium` by default, while a selected-model miss
is an authoritative absence and does not reuse that effort. The Terra preference fixes only the model
id. Its reasoning effort always comes from that catalog entry's non-blank `defaultReasoningEffort`
and remains omitted when the entry does not provide one.

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
* Good, because new workflows prefer the intended broadly usable GPT-5.6 model when it is available.
* Good, because fallback selection still follows the installed Codex catalog instead of inventing an
  unavailable model.
* Good, because selecting a model never inherits a recommendation from a different catalog entry.
* Good, because effort choices follow the installed model catalog, including new values without a
  Symphony release.
* Good, because `codex.command` remains available for operators that need custom app-server launch
  configuration.
* Good, because existing hand-written workflows without these fields continue to use their Codex
  defaults.
* Neutral, because setup permits pass-through values when a model does not advertise its supported
  efforts.
* Neutral, because an otherwise usable supported catalog without an exact selected-model
  recommendation produces a workflow that lets Codex choose its own reasoning default.
* Neutral, because the narrow Terra preference intentionally takes precedence over Codex's
  `isDefault` marker.
* Bad, because the Java client must be updated if the app-server field names change.
* Bad, because changing Symphony's preferred model id requires a Symphony code and documentation
  change.
* Bad, because generated workflows do not silently change later when a new Codex model launches.
* Bad, because setup needs a fallback path when Codex app-server cannot be queried.

### Confirmation

Run `./mvnw -q spotless:check verify`. Codex app-server client tests must assert the request payload
contains `model` and `effort` when the workflow configures them and omits them when the workflow
does not. Setup tests must assert generated workflows include explicit values when the model list is
compatible, omit them when compatibility is unknown, and preserve existing workflow values during
forced regeneration. Codex default resolver tests must assert setup honors the app-server
`model/list` selection order, retains exact per-model effort lists and descriptions, and distinguishes
fallback defaults from unsupported first-class fields. Resolver and setup-boundary tests must prove
that visible Terra on a later page beats an earlier Codex default, hidden Terra does not win, Terra's
catalog recommendation is used without a hard-coded effort, a default-less Terra entry omits
`reasoning_effort`, and Terra absence uses the generic default/first-visible rule without a special
Sol fallback. Setup tests must also assert that every advertised model-specific choice is displayed
in catalog order, default and current values are identified, an advertised `xhigh` value is accepted,
an unsupported new value reports the accepted choices, and a missing capability list keeps
pass-through compatibility. Command-boundary tests must also prove that a later catalog page supplies
the exact selected-model recommendation, supported empty or unusable catalogs produce the
`gpt-5.5`/`medium` compatibility pair, a selected-model miss or blank recommendation in an otherwise
usable supported catalog omits `reasoning_effort`, preserved and explicit values retain precedence,
and unsupported discovery keeps its separate compatibility fallback.

## Pros and Cons of the Options

### Discover the catalog, prefer exact visible Terra, and then use generic catalog fallback rules

Ask the installed Codex app-server for its model catalog during setup. Prefer exact visible Terra,
then the first visible Codex default, then the first visible usable model in catalog order. Write the
selected model and its own optional recommendation to `WORKFLOW.md`.

* Good, because operators can review and change the values without editing shell command syntax.
* Good, because newly connected boards use the intended broadly usable GPT-5.6 model when available.
* Good, because absence, visibility, fallback, and reasoning recommendations still come from the
  installed catalog.
* Good, because the workflow stays stable after setup instead of changing unexpectedly when Codex
  releases a new default model.
* Neutral, because setup needs a fallback if Codex app-server cannot answer `model/list`.
* Bad, because the preferred model id is a Symphony product policy that must change deliberately.
* Bad, because generated workflows do not automatically upgrade when the Codex CLI default changes.

### Follow the Codex `isDefault` marker without a Symphony model preference

Select the first visible usable model marked `isDefault`, or the first visible usable catalog entry
when no default is marked.

* Good, because Symphony does not add a model preference on top of Codex's catalog.
* Bad, because newly generated workflows may continue to use an older catalog default even when the
  intended broadly usable Terra model is available.

### Treat `gpt-5.6-sol` as a special fallback when Terra is unavailable

Prefer Terra first, then prefer Sol before applying the catalog's generic default and first-visible
rules.

* Good, because a GPT-5.6 model would be selected whenever either named variant is present.
* Bad, because Sol is not broadly available to every user.
* Bad, because special-casing two variants makes catalog fallback less predictable and adds another
  product-maintained model id.

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
