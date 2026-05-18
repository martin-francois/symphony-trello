---
status: accepted
date: 2026-05-19
decision-makers: [François Martin, Codex]
consulted: [AGENTS.md, Java Optional API, Vavr documentation]
informed: [Future maintainers]
---

# Use Plain Branching at Checked-IO Optional Boundaries

## Context and Problem Statement

The setup code often receives optional CLI values. When a value is absent, the fallback path may
prompt through the terminal or perform setup IO and therefore declares `IOException`.

Java's `Optional.map(...)` and `Optional.orElseGet(...)` do not accept checked exceptions. During
the Optional cleanup, we considered and briefly used a small `OptionalIo` helper to keep the call
sites fluent while adapting checked exceptions through `UncheckedIOException`.

How should the code handle optional CLI values when the absent branch can throw checked IO
exceptions?

## Decision Drivers

* Keep setup code readable to maintainers familiar with ordinary Java.
* Keep checked IO visible in method signatures instead of hiding it for fluent Optional syntax.
* Preserve the rule against Optional-as-null-control-flow in ordinary code.
* Avoid adding dependencies or custom functional helpers for only a few call sites.
* Keep behavior, public CLI output, prompts, and exception messages unchanged.

## Considered Options

* Use plain branching at checked-IO Optional boundaries.
* Keep a small local `OptionalIo` or `CheckedOptionals` helper.
* Add Vavr and use `Option` / `Try` for checked functional composition.
* Add a smaller checked-functional helper library such as jOOλ or Apache Commons Lang `Failable`.
* Catch and wrap `IOException` inside each fallback helper.

## Decision Outcome

Chosen option: "Use plain branching at checked-IO Optional boundaries", because it keeps the
checked exception in the surrounding method contract and avoids introducing a functional abstraction
for three setup selection paths.

This is a narrow exception to the normal Optional guidance. It is acceptable to branch on an
`Optional` and then read its value when all of the following are true:

* the present branch uses an already provided value;
* the absent branch calls a named helper that may prompt or throw a checked exception;
* the enclosing method declares the checked exception; and
* the branch is not used to disguise general nullable control flow.

For collection searches, keep using streams when they make the code clearer. This decision does not
discourage collection streams that end in `findAny()`, `findFirst()`, or another Optional-returning
terminal operation.

### Consequences

* Good, because setup selection code now reads as ordinary Java control flow.
* Good, because `IOException` remains visible at the methods that can prompt or perform IO.
* Good, because the project does not carry a helper whose only job is to work around checked
  exceptions in `Optional.orElseGet(...)`.
* Neutral, because this permits a small, documented exception to the general Optional rule.
* Bad, because the code is a few lines longer than a fluent functional helper.
* Bad, because future agents must distinguish this checked-IO boundary from the forbidden
  Optional-as-null-control-flow pattern.

### Confirmation

Run `./mvnw -q spotless:check verify`. Production code should not contain `OptionalIo` or another
project-specific checked Optional helper. Remaining Optional presence checks should be reviewed to
ensure they are either boolean-only checks, collection-search boundaries, or the narrow checked-IO
boundary described here.

## Pros and Cons of the Options

### Use plain branching at checked-IO Optional boundaries

Write the branch explicitly, then call the prompting or IO fallback only when the optional value is
absent.

* Good, because no dependency or helper abstraction is needed.
* Good, because checked exceptions remain honest in the method signature.
* Good, because debuggers and stack traces show straightforward Java calls.
* Neutral, because the code uses a documented exception to the normal Optional style.
* Bad, because it is less compact than a fluent Optional expression.

### Keep a small local `OptionalIo` or `CheckedOptionals` helper

Keep a package-private helper that adapts checked fallback suppliers through `UncheckedIOException`.

* Good, because call sites stay compact.
* Good, because it avoids repeating the same branch shape at each call site.
* Neutral, because the helper can stay package-private and dependency-free.
* Bad, because it is uncommon Java and hides checked-exception adaptation inside a utility.
* Bad, because only a few call sites currently need it.

### Add Vavr and use `Option` / `Try`

Adopt Vavr for richer functional control flow that can compose optional values and failures.

* Good, because Vavr is an established Java functional library.
* Good, because it has first-class abstractions for `Option`, `Try`, and related flows.
* Neutral, because it could be limited to these setup paths at first.
* Bad, because it introduces a second Optional-like type alongside `java.util.Optional`.
* Bad, because it is a project-wide style decision for a small local problem.
* Bad, because future contributors would need clear rules for when Vavr types are appropriate.

### Add jOOλ or Apache Commons Lang `Failable`

Use a smaller checked-functional helper library rather than Vavr's broader functional style.

* Good, because these libraries directly address checked functional interfaces.
* Neutral, because they may be less likely than Vavr to spread into domain model types.
* Bad, because they still add a dependency for a few call sites.
* Bad, because they do not make this setup code clearer than explicit branching.

### Catch and wrap `IOException` inside each fallback helper

Change the prompting helpers so they do not declare checked exceptions and instead wrap failures.

* Good, because callers could use `Optional.orElseGet(...)` directly.
* Bad, because it hides real IO behavior inside helper methods.
* Bad, because wrapping would be repeated across unrelated helpers.
* Bad, because changing exception shape could affect CLI error output.

## More Information

Vavr remains a possible future direction if the codebase develops enough checked functional flow to
justify a broader style decision. That should be evaluated separately from this maintainability
cleanup.
