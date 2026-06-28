---
status: accepted
date: 2026-06-28
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub PR #478](https://github.com/martin-francois/symphony-trello/pull/478)"
  - "[Spotless Maven plugin](https://github.com/diffplug/spotless/tree/main/plugin-maven)"
  - "[Google Java Format](https://github.com/google/google-java-format)"
  - "[Eclipse JDT formatter](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.user/reference/preferences/java/codestyle/ref-preferences-formatter.htm)"
  - "[GitHub issue #479](https://github.com/martin-francois/symphony-trello/issues/479)"
informed: [Future maintainers, Contributors]
---

# Keep Palantir Java Format

## Context and Problem Statement

PR #478 added a small custom formatter that split multi-operation stream and Optional chains after
the source expression. The goal was to make code such as `items.stream().map(...).toList()` easier to
read.

The custom formatter was implemented as a line-oriented Java helper. It handled the cases observed
in the PR, but it was not a Java parser. Future Java syntax, different stream sources, nested method
calls, comments, strings, and multiline expressions would require more local parser logic. That
creates a maintenance burden for a readability preference.

Which formatter should own Java formatting in this repository?

## Decision Drivers

* Keep formatting deterministic through `./mvnw -q spotless:check verify`.
* Avoid maintaining a project-specific Java parser for style-only changes.
* Prefer established formatter behavior over local formatter logic.
* Avoid broad formatter churn unless it clearly improves the repository.
* Keep code readable, but do not make every readability preference a build-enforced rule.

## Considered Options

* Keep Palantir Java Format and remove the custom stream-chain formatter.
* Keep Palantir Java Format plus the custom stream-chain formatter.
* Switch to Google Java Format.
* Switch to the Eclipse JDT formatter with chain-wrapping settings.
* Switch to Prettier Java through Spotless.
* Use an IntelliJ-based formatter.

## Decision Outcome

Chosen option: keep Palantir Java Format and remove the custom stream-chain formatter.

Spotless still runs Palantir Java Format, removes unused imports, trims trailing whitespace, and
ensures a final newline. Stream and Optional chains are formatted by Palantir's normal rules. Short
chains may stay on one line. Longer chains may wrap when the formatter decides they should.

When a chain is hard to read, prefer a normal code refactor such as a named intermediate value, a
helper method, or a clearer pipeline shape. Do not add another local formatter or parser only to
force this style.

### Consequences

* Good, because Java formatting stays owned by a maintained formatter.
* Good, because the repository no longer maintains a custom line-oriented Java formatter.
* Good, because Spotless remains the single formatting entry point.
* Neutral, because short stream or Optional chains may remain on one line.
* Bad, because the build no longer enforces the exact chain-wrapping style requested during the PR.

### Confirmation

Run:

```bash
./mvnw -q spotless:check verify
```

Confirm that `pom.xml` uses `palantirJavaFormat` for Java formatting and no custom native command
formats Java method chains.

## Pros and Cons of the Options

### Keep Palantir Java Format and Remove the Custom Formatter

Use the already configured Palantir Java Format step as the only Java source formatter.

* Good, because it is already used by the repository.
* Good, because it avoids project-owned parser logic.
* Good, because the formatting diff is limited to returning to the formatter's normal output.
* Bad, because it does not always split short fluent chains.

### Keep Palantir Java Format Plus the Custom Formatter

Run Palantir Java Format first, then run a local Java helper that rewrites selected stream and
Optional chains.

* Good, because it enforces the exact requested chain shape for known examples.
* Bad, because the helper must understand enough Java syntax to avoid corrupting source.
* Bad, because supporting every way to create a stream or Optional chain would grow local formatter
  code.
* Bad, because it creates formatting behavior that contributors' IDEs will not naturally reproduce.

### Switch to Google Java Format

Replace Palantir Java Format with Google Java Format.

* Good, because Google Java Format is widely used and maintained.
* Good, because it wraps long fluent chains.
* Bad, because it does not split short chains only because they have multiple operations.
* Bad, because it is a repository-wide style migration for a rule it does not fully implement.

### Switch to the Eclipse JDT Formatter

Replace Palantir Java Format with the Eclipse JDT formatter and configure method-invocation
selector alignment.

* Good, because Eclipse JDT has more formatting settings than Palantir or Google Java Format.
* Good, because it is the closest maintained formatter to IDE-style configuration.
* Good, because manually split chains survive formatting when the selector-wrapping setting is
  enabled.
* Bad, because the tested JDT selector-wrapping setting splits all selected method invocations. It
  changes `card.labels().stream().map(...).toList()` into a generic chain shape where `.stream()`
  moves to the next line too.
* Bad, because the same setting also splits simple one-terminal chains such as
  `card.labels().stream().toList()`, which is broader than the requested rule.
* Bad, because the surviving shape is not the preferred stream-aware shape. The repository wanted
  `card.labels().stream()` to stay together and only later operations to wrap, not `.stream()` on a
  separate line.
* Bad, because switching formatters would create broad style churn without enforcing the requested
  stream-aware shape.

### Switch to Prettier Java Through Spotless

Run Prettier with the Java plugin as a Spotless formatter step.

* Good, because Spotless can run Prettier-based formatting through its generic formatter support.
* Bad, because the tested Prettier Java output kept the short chain on one line at normal print
  width.
* Bad, because lowering print width split the chain before the base expression and `.stream()`, not
  after `.stream()`.
* Bad, because this would replace the current Java style with a different two-space style for a
  rule it does not fully implement.

### Use an IntelliJ-Based Formatter

Run an IntelliJ formatter or IDE code-style profile from the build.

* Good, because IntelliJ can be configured to wrap chained method calls.
* Bad, because the project does not currently use an IntelliJ formatter in Maven.
* Bad, because adding an IDE formatter would add a heavier formatting dependency and contributor
  setup surface.
* Bad, because it is not justified only to enforce this readability preference.

## More Information

This decision only covers Java source formatting. It does not prevent local refactors that make a
specific stream or Optional pipeline easier to read.

[GitHub issue #479](https://github.com/martin-francois/symphony-trello/issues/479) tracks the
future idea of finding a maintained formatter or lint rule that can enforce the desired fluent-chain
readability without custom repository code.
