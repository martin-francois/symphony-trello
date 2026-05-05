---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [MADR lint workflow, MADR ADR template]
informed: [Future maintainers]
---

# Lint Markdown with markdownlint and Enforce ADR Shape in Java Tests

## Context and Problem Statement

The repository now keeps ADRs in the official MADR structure. The upstream MADR repository uses a
GitHub Actions lint workflow based on `markdownlint-cli2-action`, but markdownlint checks general
Markdown style rather than whether ADRs contain filled MADR sections.

How should the project automatically catch broken Markdown and incomplete ADR records?

## Decision Drivers

* Keep the lint workflow close to MADR's own Markdown linting approach.
* Keep GitHub Actions pinned to full commit SHAs.
* Avoid a broad documentation rewrite only to satisfy default 80-column line length.
* Enforce ADR metadata, heading order, and template-placeholder removal.
* Keep ADR structure checks in the existing Java test suite.

## Considered Options

* Use markdownlint for Markdown plus a Java test for MADR ADR shape.
* Use only markdownlint.
* Use only a custom Java test.
* Rewrite all Markdown to satisfy default markdownlint rules.

## Decision Outcome

Chosen option: "Use markdownlint for Markdown plus a Java test for MADR ADR shape", because it uses
the standard Markdown linter for general document hygiene while enforcing the project-specific ADR
contract in a test that gives focused failure messages.

### Consequences

* Good, because CI now catches broken Markdown formatting.
* Good, because ADRs must keep required MADR metadata and sections in order.
* Good, because template placeholders cannot accidentally be committed in ADRs.
* Good, because line length and bare URL defaults are configured once for the current docs.
* Bad, because documentation changes now need one more local check.
* Bad, because the Java ADR-shape test must be updated if the project adopts a different ADR
  template.

### Confirmation

Run `pnpm dlx markdownlint-cli2` for Markdown linting and
`./mvnw -q -Dtest=JavaStyleTest test` for ADR structure enforcement. The full verification command
`./mvnw -q spotless:check verify` also runs the Java ADR test.

## Pros and Cons of the Options

### Use markdownlint for Markdown plus a Java test for MADR ADR shape

Add a CI lint job with `DavidAnson/markdownlint-cli2-action`, keep project Markdown rules in
`.markdownlint-cli2.yaml`, and enforce ADR shape in `JavaStyleTest`.

* Good, because it follows the upstream MADR linting tool.
* Good, because ADR-specific requirements are enforced directly.
* Good, because the Java test stays in the normal Maven verification path.
* Neutral, because pnpm is used locally for the Markdown linter invocation.
* Bad, because two mechanisms are involved instead of one.

### Use only markdownlint

Rely only on markdownlint and its standard rules.

* Good, because it is simple and close to the upstream MADR workflow.
* Bad, because markdownlint does not know whether an ADR has `Decision Drivers`,
  `Considered Options`, or filled metadata.
* Bad, because template placeholders could still pass if they are valid Markdown.

### Use only a custom Java test

Skip markdownlint and enforce all documentation rules through Java tests.

* Good, because all checks stay in Maven.
* Bad, because reimplementing general Markdown linting is unnecessary.
* Bad, because standard Markdown style issues would be missed unless custom rules are added.

### Rewrite all Markdown to satisfy default markdownlint rules

Keep the default lint rules and reflow every existing Markdown file to 80 columns.

* Good, because it would avoid custom lint configuration.
* Bad, because it would create a large low-value documentation churn.
* Bad, because `SPEC.md` and long setup commands are easier to maintain with a relaxed line-length
  rule.

## More Information

The lint workflow intentionally omits MADR's changelog-specific `heylogs` job because this repository
does not currently have a `CHANGELOG.md`.
