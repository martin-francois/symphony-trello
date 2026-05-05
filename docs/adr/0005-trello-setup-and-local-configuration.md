---
status: accepted
date: 2026-05-05
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, Trello API behavior]
informed: [Future maintainers]
---

# Provide Java-Based Trello Setup Commands and Project-Root Env Files

## Context and Problem Statement

Many users who would benefit from Symphony for Trello may be new to Trello workspaces, API keys, and
tokens. They should not need to manually build a recommended board or hand-write a first
`WORKFLOW.md` after completing Trello's browser-only API authorization steps.

How should the project make first-time Trello setup easy without hiding important credentials or
creating fragile non-Java helper scripts?

## Decision Drivers

* Minimize hand setup after the user creates a Trello workspace, API key, and token.
* Keep reusable helpers in Java because this is a Java project.
* Avoid committing credentials or printing secret values.
* Support both a new recommended board and importing an existing board.
* Avoid silent no-ops when generated workflow files already exist.
* Keep one Symphony process mapped to one `WORKFLOW.md` and one Trello board.

## Considered Options

* Java setup commands that create or import Trello boards and write workflow files.
* README-only setup where users create boards and workflow files manually.
* Shell, Python, or Node helper scripts for Trello setup.
* Store credentials only through exported environment variables.

## Decision Outcome

Chosen option: "Java setup commands that create or import Trello boards and write workflow files",
because it reduces Trello onboarding friction while keeping setup behavior maintainable in the same
toolchain as the service.

### Consequences

* Good, because new users can create the recommended board structure from one Maven command after
  browser authorization.
* Good, because existing Trello users can import a board into a starter workflow file.
* Good, because `.env` keeps local commands less clumsy while real environment variables still take
  precedence.
* Good, because repeated board setup writes named workflow files instead of silently doing nothing.
* Bad, because Trello browser steps for workspace, API key, and token creation still cannot be
  automated safely by the service.
* Bad, because setup commands must handle Trello API edge cases and useful error output.

### Confirmation

Run `./mvnw -q spotless:check verify`. Live verification should follow `docs/live-e2e.md` with a
real Trello key/token when changing setup behavior.

## Pros and Cons of the Options

### Java setup commands that create or import Trello boards and write workflow files

Provide Maven-executed Java setup behavior for recommended board creation and existing board import.

* Good, because the workflow is repeatable and easier to test than manual instructions alone.
* Good, because maintainers debug Java code rather than a second scripting stack.
* Good, because board-name-based workflow files reduce surprise on repeated setup.
* Neutral, because users still need to understand which workspace or board they want to use.
* Bad, because setup behavior increases the Java surface area beyond the long-running service.

### README-only setup where users create boards and workflow files manually

Document every Trello board and workflow step without automation.

* Good, because there is less code to maintain.
* Bad, because first-time Trello users face more opportunities for mistakes.
* Bad, because repeated project setup stays slow and inconsistent.

### Shell, Python, or Node helper scripts for Trello setup

Use a short script outside the Java application to call Trello APIs.

* Good, because it could be quick to write.
* Bad, because it adds another language and runtime to maintain.
* Bad, because it conflicts with the Java-first maintenance model for reusable helpers.

### Store credentials only through exported environment variables

Require users to export `TRELLO_API_KEY` and `TRELLO_TOKEN` before every local command.

* Good, because it uses standard process environment behavior.
* Bad, because it is clumsy for normal local use.
* Bad, because repeated exports increase the chance of shell-history mistakes.

## More Information

Ignored project-root `.env` files are the recommended local convenience mechanism. Real environment
variables still take precedence, so CI and deployments can use their normal secret stores.
