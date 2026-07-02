---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, README.md, OpenAI brand guidelines, Atlassian trademark guidelines]
informed: [Future maintainers]
---

# Name the Product Symphony for Trello and Use a Non-OpenAI Java Namespace

## Context and Problem Statement

This project implements a Trello adaptation of OpenAI's Symphony concept. The original Symphony work
is associated with OpenAI, but this repository is not an official OpenAI implementation and should
not look like it is maintained by OpenAI.

How should the project name and Java namespace communicate that relationship clearly?

## Decision Drivers

* Make the product name clear to people using Trello.
* Avoid implying OpenAI ownership or employment through package names.
* Preserve the connection to the Symphony concept from the adapted specification.
* Keep technical identifiers suitable for Maven, repository names, and service names.
* Keep future open-source publication straightforward.
* Avoid implying endorsement or sponsorship by OpenAI, Atlassian, or Trello.

## Considered Options

* Human-facing name "Symphony for Trello" with technical name `symphony-trello`.
* Keep `com.openai.symphony` as the Java namespace.
* Use an unrelated name that does not mention Symphony.

## Decision Outcome

Chosen option: "Human-facing name 'Symphony for Trello' with technical name `symphony-trello`",
because it makes the Trello adaptation obvious while avoiding an official-OpenAI namespace. The
README carries an explicit disclaimer that the project is independent and is not affiliated with,
endorsed by, or sponsored by OpenAI, Atlassian, or Trello.

### Consequences

* Good, because README and setup docs can speak plainly about the Trello use case.
* Good, because `ch.fmartin.symphony.trello` and the Maven group id avoid implying OpenAI ownership.
* Good, because `symphony-trello` remains usable where spaces and title case are not.
* Good, because the README now states the third-party relationship explicitly.
* Bad, because the relationship to upstream Symphony and Trello must still be explained in docs.
* Bad, because future upstream alignment work must account for package-name differences.

### Confirmation

Run `./mvnw -q spotless:check verify`. Review should confirm Maven coordinates and Java packages use
`ch.fmartin.symphony.trello`, while user-facing docs use "Symphony for Trello".

## Pros and Cons of the Options

### Human-facing name "Symphony for Trello" with technical name `symphony-trello`

Use "Symphony for Trello" in prose and `symphony-trello` for repository, artifact, service, and file
identifiers.

* Good, because users immediately see the Trello focus.
* Good, because technical names remain conventional.
* Good, because the Java namespace can reflect the maintainer rather than OpenAI.
* Neutral, because docs must mention that this is adapted from Symphony and integrates with Trello.
* Bad, because it is slightly longer than a single short project name.

### Keep `com.openai.symphony` as the Java namespace

Use a namespace matching the original Symphony source.

* Good, because package names would resemble upstream examples.
* Bad, because it can imply this project is official OpenAI software.
* Bad, because it makes ownership and support responsibility less clear.

### Use an unrelated name that does not mention Symphony

Rename the project to avoid the Symphony name entirely.

* Good, because there is no risk of implying official OpenAI ownership.
* Bad, because it hides the relationship to the adapted Symphony specification.
* Bad, because users familiar with Symphony would not recognize the intended workflow.

## More Information

The README should explain that this project is a Trello-oriented variant of OpenAI's Symphony, but
not as the first thing a new user must understand before seeing the product value. The README should
also keep the independent-project disclaimer near that relationship explanation so readers do not
mistake the project for an official OpenAI, Atlassian, or Trello product.
