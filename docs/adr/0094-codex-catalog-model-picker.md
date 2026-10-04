---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude Code]
consulted:
  - SPEC.md
  - "[ADR 0025](0025-codex-model-and-reasoning-workflow-fields.md)"
  - Installed Codex CLI 0.160.0 (`codex app-server` `model/list` and `codex debug models`)
  - "[GitHub issue #550](https://github.com/martin-francois/symphony-trello/issues/550)"
  - "[GitHub issue #65](https://github.com/martin-francois/symphony-trello/issues/65)"
informed: [Future maintainers, Contributors]
---

# Offer a numbered Codex model picker backed by model/list

## Context and Problem Statement

Guided `setup-local` asked for the Codex model with a free-text prompt such as `Model [gpt-5.5]:`.
Users had to know the exact model ID. The installed Codex app-server already returns the visible
model catalog through `model/list`, with model IDs, display names, a default marker, and reasoning
metadata. Setup used that response only to pick one default model and to look up reasoning efforts.

How should guided setup let an operator choose a model from the installed catalog without blocking
models that the catalog does not list?

## Decision Drivers

* An operator can pick a listed model without typing its exact ID.
* Any model ID still works, including a newly released one that the installed catalog does not list.
* Enter keeps the preselected value, as every other setup prompt does.
* The setup `Terminal` abstraction is line based. Tests drive it through standard input, and the
  same flow runs in piped and non-TTY environments.
* Existing workflow values stay the source of truth during regeneration unless the operator changes
  them.
* The model list comes from the installed Codex CLI, not from a list maintained in Symphony.
* The picker should be reusable when an upgrade flow asks whether to move to a newer model
  ([GitHub issue #65](https://github.com/martin-francois/symphony-trello/issues/65)).

## Considered Options

* Numbered picker on the existing line-based prompt, with an other-model choice and typed IDs.
* Arrow-key picker in raw terminal mode, similar to the Codex TUI.
* Print the discovered model list above the existing free-text prompt.
* Hard-code a list of known model IDs in Symphony.
* Read the catalog from `codex debug models` instead of app-server `model/list`.

## Decision Outcome

Chosen option: "Numbered picker on the existing line-based prompt, with an other-model choice and
typed IDs", because it keeps one interaction model for all setup prompts, works without a TTY, and
never blocks a model that the catalog does not list.

`CodexModelDefaultsResolver` keeps using app-server `model/list`. It parses each usable entry once.
When a model ID repeats, in one page or across pages, the first entry wins for the display name,
hidden flag, default marker, and reasoning metadata, so the picker label and the reasoning
recommendation always come from the same entry. Entries without a usable model ID are skipped.
Control characters in the model ID or reasoning metadata still make setup treat the whole catalog as
unsupported, as ADR 0025 already does for model metadata. The display name is only a label, so a
display name with control characters is dropped and the picker shows the model ID instead.

`CodexModelPicker` shows the non-hidden entries in catalog order, marks Symphony's catalog
recommendation, and preselects the current value. The current value is the preserved workflow model
during regeneration and the recommendation for a new workflow. A preserved model that the picker
does not list, for example a hidden or hand-edited one, gets its own choice. A workflow that omits
the model gets a choice that keeps the omission. The last choice is `Other model ID`, which asks for
any non-blank single-line ID. The prompt also accepts a typed model ID directly, so a user who knows
the ID does not need the extra step. Picking the current model counts as keeping it, so the
workflow's reasoning effort stays as it is. Picking another model resolves reasoning effort for that
model as ADR 0025 describes.

When discovery lists no visible usable model, setup keeps the old free-text prompt. When discovery
is unsupported, setup follows ADR 0025: it asks for no model unless the operator passed a model or
reasoning effort, and then it uses the free-text prompt. `--codex-model` and `--non-interactive`
never show the picker.

### Consequences

* Good, because users pick a listed model by number and still reach any model through the
  other-model choice or by typing it.
* Good, because scripted input that typed a model ID at the old prompt keeps working at the new
  prompt.
* Good, because the picker takes a plain list and a current value, so the upgrade flow in
  [GitHub issue #65](https://github.com/martin-francois/symphony-trello/issues/65) can reuse it.
* Neutral, because display names longer than 40 characters are shortened with `...`. Model IDs are
  never shortened.
* Neutral, because an answer made only of ASCII digits is read as a choice number. A model whose ID
  is only digits must be entered through the other-model choice.
* Bad, because a numbered list is less direct than arrow-key selection in an interactive terminal.
* Bad, because an invalid number stops setup with `setup_invalid_choice`, like the other numbered
  setup prompts, instead of asking again.

### Confirmation

Run `./mvnw -q spotless:check verify`. `CodexModelPickerTest` covers Enter and end of input,
selection by number, typed IDs, the other-model choice, invalid numbers, blank IDs, an arrow-key
escape sequence, current-model markers, unlisted and omitted workflow models, and display-name
shortening within 80 columns. `CodexModelDefaultsResolverTest` covers catalog order, hidden entries,
malformed entries, duplicates across pages, the first of several default markers, and a dropped
unsafe display name. `LocalSetupTest` covers the picker at the command boundary against a fake
app-server: Enter, a number that switches the reasoning recommendation, the other-model choice,
preselection of an existing workflow model, the free-text fallback for an empty catalog, and no
picker for `--codex-model` or `--non-interactive`.

## Pros and Cons of the Options

### Numbered picker on the existing line-based prompt, with an other-model choice and typed IDs

Print the visible catalog models as numbered lines, followed by an `Other model ID` choice. Read one
line: blank keeps the preselected model, digits pick a choice, any other text is a model ID.

* Good, because it matches the numbered prompts setup already uses for workspaces, board setup, and
  connected boards.
* Good, because it works the same in a terminal, over a pipe, and in tests.
* Good, because a typed model ID stays valid input, so the change does not break scripted answers.
* Bad, because the operator reads a number from the list instead of moving a highlight.

### Arrow-key picker in raw terminal mode, similar to the Codex TUI

Switch the terminal to raw mode and move a highlight with arrow keys, like the model popup in the
Codex TUI.

* Good, because it matches what Codex users already know.
* Bad, because the `Terminal` abstraction is line based. Raw mode needs a terminal library or
  platform-specific code for POSIX terminals and Windows consoles.
* Bad, because piped input, CI, and the existing setup tests have no TTY, so a second line-based path
  would still be required.

### Print the discovered model list above the existing free-text prompt

Keep the `Model [id]:` prompt and print the catalog above it as plain information.

* Good, because it is the smallest change.
* Bad, because users still have to type the exact model ID, which is the problem the issue describes.

### Hard-code a list of known model IDs in Symphony

Ship a fixed list of model IDs in setup.

* Good, because it works without querying Codex.
* Bad, because the list goes stale with every Codex release and can offer models the installed CLI or
  account cannot use.
* Bad, because ADR 0025 already decided that the installed catalog is the source of truth.

### Read the catalog from `codex debug models` instead of app-server `model/list`

Run `codex debug models` and parse its JSON output.

* Good, because it prints the catalog without starting an app-server session.
* Bad, because Codex documents it as a debugging command that renders the raw internal catalog. Its
  snake_case fields (`slug`, `visibility`, `supported_reasoning_levels`) are not the app-server
  protocol and can change without a protocol version.
* Bad, because setup already starts the app-server for `model/list`, so a second source adds a second
  parser for the same data.

## More Information

On Codex CLI 0.160.0, `model/list` with `includeHidden: true` returned 11 entries in one page, 2 of
them hidden. Each entry had `id`, `model`, `displayName`, `description`, `hidden`, `isDefault`,
`defaultReasoningEffort`, and `supportedReasoningEfforts`. The picker showed the 9 visible models
plus the other-model choice and preselected `gpt-5.6-terra`. `codex debug models` returned the same
models with internal field names.
