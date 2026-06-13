---
status: accepted
date: 2026-06-13
decision-makers: [François Martin, Claude]
consulted:
  - "SPEC.md"
  - "OpenAI Symphony SPEC.md"
  - "OpenAI Symphony reference implementation (elixir/WORKFLOW.md, elixir/lib/symphony_elixir/config/schema.ex)"
  - "[GitHub issue #54](https://github.com/martin-francois/symphony-trello/issues/54)"
  - "[GitHub issue #97](https://github.com/martin-francois/symphony-trello/issues/97)"
informed: [Future maintainers]
---

# Align Polling Defaults With the Upstream Reference Implementation

## Context and Problem Statement

`polling.interval_ms` controls how often a worker polls Trello for new and changed cards. Upstream
Symphony defines two different values for it, and they disagree:

- The Elixir config schema and the upstream `SPEC.md` default the field to `30000`
  (`elixir/lib/symphony_elixir/config/schema.ex`). This value applies only when a workflow omits the
  field.
- The upstream reference implementation's own generated workflow sets `interval_ms: 5000`
  (`elixir/WORKFLOW.md`). Because the generated workflow always sets the field, a reference
  deployment actually polls every five seconds in practice.

The upstream fidelity audit in [GitHub issue #97](https://github.com/martin-francois/symphony-trello/issues/97)
found that Symphony for Trello had collapsed these into a single value: both the generated workflow
and the runtime fallback used `5000`. The generated-workflow value matched upstream, but the runtime
fallback (used when a hand-written workflow omits the field) silently differed from upstream's
`30000`.

What value should each of the two surfaces use?

## Decision Drivers

* Symphony for Trello should stay as close to the upstream reference implementation as practical, on
  both surfaces, since Trello requires no different value.
* First-time users create boards with `new-board`/`import-board` and expect Trello card pickup to
  feel responsive without tuning config.
* A hand-written workflow that omits the field should behave the way an upstream deployment behaves
  when it omits the field.
* Trello documents per-key and per-token rate limits, so the fallback for unconfigured workflows
  should stay conservative.

## Considered Options

* Match upstream on both surfaces: generated workflows write `5000`, runtime fallback resolves to
  `30000`.
* Use `5000` on both surfaces, so even a workflow that omits the field polls every five seconds.
* Use `30000` on both surfaces, so generated workflows poll every thirty seconds like the upstream
  schema default.

## Decision Outcome

Chosen option: "Match upstream on both surfaces", because it makes Symphony for Trello byte-identical
to the upstream reference implementation for polling defaults. Generated workflows keep `interval_ms:
5000`, matching `elixir/WORKFLOW.md`, so freshly created boards stay responsive. The runtime fallback
resolves an omitted `polling.interval_ms` to `30000`, matching the upstream config schema, so a
hand-written workflow that omits the field behaves exactly like an upstream deployment that omits it.

Operators can still set any positive `polling.interval_ms` per workflow. When Trello rate limiting
happens, the runtime logs a warning that names the current interval and the workflow file and
recommends increasing the interval, especially with more than 5-10 boards sharing one Trello token.

There is no remaining intentional divergence from upstream for this field. Realigning further is not
applicable; if upstream changes either value, Symphony for Trello should follow.

### Consequences

* Good, because both polling surfaces now match the upstream reference implementation exactly, so the
  fidelity audit records no divergence for this field.
* Good, because new cards still start within seconds on a freshly generated board.
* Good, because a workflow that omits the field behaves like upstream instead of like a generated
  Symphony for Trello workflow.
* Neutral, because the generated-workflow value and the runtime fallback are now two named constants
  with two values; a comment in `ConfigDefaults` explains why.
* Bad, because a hand-written workflow that omits `polling.interval_ms` now polls every thirty
  seconds instead of five, so authors who want faster pickup must set the field themselves, exactly
  as upstream requires.

### Confirmation

`ConfigDefaults.DEFAULT_POLLING_INTERVAL` is thirty seconds and is the resolver fallback in
`ConfigResolver`; `ConfigDefaults.GENERATED_WORKFLOW_POLLING_INTERVAL` is five seconds and is the
value `TrelloBoardSetup` writes into generated workflows. `SPEC.md` Section 5.3.2 documents both, and
`TrelloBoardSetupTest` asserts the generated workflow writes `interval_ms: 5000` and resolves to five
seconds, while `WorkflowConfigPromptTest` and `TrelloClientTest` assert that a workflow omitting the
field falls back to the thirty-second default.

## Pros and Cons of the Options

### Match upstream on both surfaces

Write `5000` into generated workflows and resolve an omitted field to `30000`, exactly as the
upstream reference implementation does.

* Good, because Symphony for Trello matches upstream for both the generated workflow and the
  unconfigured fallback.
* Good, because generated boards stay responsive while the bare fallback stays conservative.
* Bad, because the two surfaces hold two different values, which a reader must understand from the
  `ConfigDefaults` comment.

### Use `5000` on both surfaces

Keep the previous Symphony for Trello behavior where the generated workflow and the runtime fallback
both poll every five seconds.

* Good, because every workflow, generated or hand-written, feels responsive by default.
* Bad, because the runtime fallback diverges from the upstream schema default of `30000` for no
  Trello reason, which the fidelity audit flagged.

### Use `30000` on both surfaces

Adopt the upstream schema default everywhere, including generated workflows.

* Good, because there is a single value to remember.
* Bad, because freshly generated boards would feel slow, unlike the upstream reference
  implementation's own generated workflow, which uses `5000`.

## More Information

The five-second generated-workflow value was first requested in
[GitHub issue #54](https://github.com/martin-francois/symphony-trello/issues/54). The
[fidelity audit in GitHub issue #97](https://github.com/martin-francois/symphony-trello/issues/97)
found that the runtime fallback had drifted to `5000` while upstream keeps it at `30000`, and this
ADR records aligning the fallback back to upstream while keeping the generated-workflow value that
already matched. [ADR 0028](0028-upstream-spec-and-reference-implementation-boundary.md) defines the
general boundary between the adapted specification and upstream sources, including that the upstream
reference implementation is evidence of intent alongside the upstream specification.
