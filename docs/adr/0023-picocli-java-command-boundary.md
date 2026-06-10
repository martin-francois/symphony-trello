---
status: accepted
date: 2026-05-09
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, README.md, docs/adr/0022-plan-b-local-onboarding.md]
informed: [Future maintainers]
---

# Use Picocli For Java Command Parsing

## Context and Problem Statement

The Java setup entrypoints had grown hand-written parsers for board creation, board import,
workspace listing, local onboarding, local checks, port repair, and GitHub configuration. The
manual parsers duplicated option handling and made the command surface harder to evolve safely before
the project becomes open source.

How should Symphony for Trello keep command-line behavior maintainable without moving product setup
logic into command parser classes?

## Decision Drivers

* Reduce hand-written option parsing and custom help text.
* Keep Trello, Codex, GitHub, workflow, health-check, and onboarding behavior in Java services.
* Make invalid command usage fail consistently with useful help.
* Use a maintained Java CLI library instead of adding parsing logic to shell or PowerShell.
* Keep the public command model clean while the project is still private.

## Considered Options

* Use picocli for Java command parsing and delegate to setup services.
* Keep hand-written Java parsers and improve them incrementally.
* Move setup command parsing into Bash and PowerShell wrappers.

## Decision Outcome

Chosen option: "Use picocli for Java command parsing and delegate to setup services", because it
removes repeated parser code while keeping product behavior in the existing Java service layer.

The public Java command tree is:

* `symphony-trello setup-local`
* `symphony-trello setup-local check`
* `symphony-trello setup-local repair-port --board NAME`
* `symphony-trello setup-local configure-github`
* `symphony-trello new-board`
* `symphony-trello import-board`
* `symphony-trello list-workspaces`
* `symphony-trello start [--board NAME | --workflow PATH]`
* `symphony-trello stop [--board NAME | --workflow PATH]`
* `symphony-trello status [--board NAME | --workflow PATH]`
* `symphony-trello logs [--board NAME | --workflow PATH] [--follow]`

Picocli command classes declare options, validate command shape, convert values into request
objects, call setup services, and map failures to exit codes. They do not own the onboarding state
machine or external-system behavior.

The installed Bash and PowerShell wrappers route root help, version, setup, board setup, workspace
listing, lifecycle commands, and unknown commands to this Java command boundary. The wrappers
bootstrap paths and environment. Java owns local lifecycle state, health checks, process selection,
and start/stop/status/logs behavior so POSIX and PowerShell do not duplicate process-management
logic.

The previous mode flags `setup-local --check`, `setup-local --repair-port`, and
`setup-local --configure-github` are not kept as public compatibility aliases. The project is still
private, so the cleaner nested command model is preferred before release.

### Consequences

* Good, because Java help output and validation now come from one maintained parser library.
* Good, because the setup service tests can focus on behavior instead of parser details.
* Good, because shell and PowerShell wrappers stay focused on bootstrap and Java invocation.
* Good, because local worker lifecycle behavior is implemented once in Java instead of separately in
  Bash and PowerShell.
* Bad, because existing local notes that used old setup-local mode flags need to use the new
  subcommands.

### Confirmation

Run `./mvnw -q -Dtest=TrelloBoardSetupMainTest,LocalSetupTest test` to verify command parsing,
delegation, and setup behavior. Run `./mvnw -q spotless:check verify` before merging to cover the
full project.

## Pros and Cons of the Options

### Use Picocli For Java Command Parsing And Delegate To Setup Services

Adopt the Picocli library as the single Java command-line boundary: each setup command becomes a
thin annotated command class that declares its options, lets Picocli parse and validate them, and
delegates the actual work to the existing setup services.

* Good, because it provides typed options, generated help, arity checks, and parse errors.
* Good, because command classes stay thin and testable.
* Good, because it avoids implementing parser edge cases by hand.
* Bad, because it adds one runtime dependency.

### Keep Hand-Written Java Parsers And Improve Them Incrementally

Keep the existing custom argument-parsing code in the Java entrypoints and clean it up command by
command as the CLI surface changes.

* Good, because it avoids adding a dependency.
* Bad, because the existing parser code already duplicated help, option parsing, defaults, and error
  handling.
* Bad, because every new CLI option would require more custom validation code.

### Move Setup Command Parsing Into Bash And PowerShell Wrappers

Parse setup command options inside the Bash and PowerShell wrapper scripts and pass already-parsed
values to the Java services.

* Good, because installer scripts already exist.
* Bad, because behavior would be duplicated across scripting languages.
* Bad, because setup command behavior would drift from Java tests and services.
* Bad, because users can still invoke the Java setup commands directly.

## More Information

Picocli 4.7.7 is used as a minimal runtime dependency. The annotation processor is not included
because this change does not generate shell completions, native-image metadata, or command
documentation.
