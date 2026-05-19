---
status: accepted
date: 2026-05-19
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, OpenAI Symphony SPEC.md, OpenAI Symphony Elixir implementation, Linear issue relations documentation]
informed: [Future maintainers]
---

# Treat the Adapted Specification as Normative and the Reference Implementation as Informative

## Context and Problem Statement

Symphony for Trello adapts OpenAI's Symphony concept from Linear to Trello. The upstream project has
a specification and an Elixir reference implementation. This project has its own Java
implementation, installer, generated workflow text, and Trello-specific behavior.

When these sources differ, which one should guide future changes?

## Decision Drivers

* Keep this project aligned with Symphony instead of turning it into unrelated Trello automation.
* Keep shared concepts close to the upstream Symphony specification.
* Adapt behavior where Linear and Trello work differently.
* Do not treat the Elixir reference implementation as a second specification.
* Allow documented implementation extensions beyond the core spec when they do not violate or
  contradict `SPEC.md`.

## Considered Options

* Treat this repository's `SPEC.md` as the normative contract.
* Treat the upstream reference implementation as binding whenever it differs from this Java code.
* Treat the Java implementation as the source of truth and update the spec afterward.

## Decision Outcome

Chosen option: "Treat this repository's `SPEC.md` as the normative contract", because it is the
Trello adaptation of the Symphony specification.

Spec-defined concepts should stay close to the upstream Symphony specification. For example,
upstream Symphony works from Linear issues. Symphony for Trello maps that work item concept to
Trello cards, while keeping compatibility aliases such as the prompt `issue` object when the spec
requires them.

The reference implementation is useful for intent, edge cases, and compatibility checks. It is not
binding. The Java implementation may use different internal code, setup flow, tests, installer
behavior, or Trello-specific defaults when those choices still follow `SPEC.md`, fit Trello, and are
covered by this project's ADRs.

### Consequences

* Good, because maintainers have one local contract to review against.
* Good, because upstream terms stay visible when they are part of the spec.
* Good, because Java and Trello-specific choices can differ from the Elixir/Linear reference.
* Bad, because maintainers must decide when reference behavior shows a gap in `SPEC.md`.
* Bad, because Trello-specific docs may need to explain an upstream term instead of replacing it.

### Confirmation

Review changes against `SPEC.md` first. If a change alters a spec-defined concept, compare it with
the upstream Symphony specification and document the Trello adaptation when needed. A difference
from the upstream reference implementation is acceptable when it still follows `SPEC.md`, fits
Trello, and is covered by this project's ADRs.

## Pros and Cons of the Options

### Treat this repository's `SPEC.md` as the normative contract

Use the adapted Trello specification as the contract. Use the upstream Symphony specification as the
compatibility target for shared concepts.

* Good, because it gives contributors a single local source of truth.
* Good, because it preserves upstream alignment without requiring Linear-specific implementation
  choices.
* Neutral, because disagreements still require judgment and ADRs.
* Bad, because spec gaps must be fixed deliberately instead of hidden in implementation behavior.

### Treat the upstream reference implementation as binding whenever it differs from this Java code

Use the Elixir/Linear implementation as the final arbiter for behavior.

* Good, because it can reveal intended edge cases that are not obvious from prose.
* Bad, because it would force Linear-specific implementation details into a Trello/Java project.
* Bad, because it can override Trello Free-plan constraints, installer needs, or Java architecture.

### Treat the Java implementation as the source of truth and update the spec afterward

Let current code behavior define the contract.

* Good, because it is quick when implementation and tests already exist.
* Bad, because it makes the specification descriptive instead of normative.
* Bad, because accidental behavior can become documented behavior.

## More Information

The prompt context exposes both `card` and the compatibility alias `issue`, and both point to the
same normalized Trello card data.
