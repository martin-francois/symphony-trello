---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #386](https://github.com/martin-francois/symphony-trello/issues/386)"
  - "[SmallRye Config](https://smallrye.io/smallrye-config/Main/)"
  - "[MicroProfile Config ConfigValue](https://download.eclipse.org/microprofile/microprofile-config-3.1/apidocs/org/eclipse/microprofile/config/ConfigValue.html)"
  - "[GitHub PR #801](https://github.com/martin-francois/symphony-trello/pull/801)"
  - "[GitHub PR #808](https://github.com/martin-francois/symphony-trello/pull/808)"
informed: [Future maintainers, Contributors]
---

# Share one Trello credential resolver without a config library

## Context and Problem Statement

Setup, the CLI commands such as `list-workspaces`, and worker start each find a Trello API key and
token. The contract is the same everywhere:

* A value passed with `--key` or `--token`, or entered at the setup prompt, wins. It wins even when
  it is blank.
* Otherwise a non-blank shell environment variable wins.
* Otherwise a non-blank value from the credential file (`.env` or `.env.NAME`) is used.
* Only a directly entered value is written back to the credential file.
* A credential file value that looks like an environment reference, such as `$TRELLO_API_KEY` or
  `${TRELLO_API_KEY:-x}`, fails with `setup_credentials_environment_reference`. The check applies
  only when the file supplies the value. A shell variable that wins skips it.
* Worker start reports missing credentials before it runs that check.

Before this decision, the code had this contract three times: in
`TrelloCredentialStore.credentialValue`, in `TrelloBoardSetupMain.TrelloAuthOptions.credentialValue`,
and in `LocalWorkerManager.credentialSource`. Two enums named the winning source:
`TrelloCredentialStore.CredentialSource` (`DIRECT`, `ENVIRONMENT`, `DOTENV`) and
`TrelloBoardSetupException.TrelloCredentialSource` (`SHELL_ENVIRONMENT`, `DOTENV_FILE`,
`WORKFLOW_CONFIG`, `MISSING`). The first enum also tagged a missing value as `DOTENV`.

SmallRye Config is already on the classpath through Quarkus. Its ordered config sources and
`ConfigValue#getSourceName` model "a precedence chain that tells you which source won". Should
the credential lookup move onto SmallRye Config, should the three copies become one shared
resolver, or should the code stay as it is?

## Decision Drivers

* No change in which source wins, in where a credential can come from, or in what setup saves.
  Each is a user-facing contract.
* The credential file check stays on every path and applies only to a value the file supplies.
* Error codes, the dotenv path, environment variable names, and the source shown for each credential
  in worker-start errors and `SetupDiagnosticReporter` hints stay the same.
* Credential files are chosen at runtime, one per board, at explicit paths.
* A change must remove concepts, not add them.

## Considered Options

* One shared resolver and one source enum, no new dependency.
* A SmallRye Config source chain plus glue code.
* SmallRye Config with its default behavior.
* Keep the current shape.

## Decision Outcome

Chosen option: "One shared resolver and one source enum, no new dependency", because it removes two
of the three copies and one of the two enums without any behavior change, while SmallRye Config
needs more glue than the lookup it would replace.

`TrelloCredentialResolver` now owns the lookup. `resolve(name, directValue)` returns a
`CredentialValue(name, value, source)`. `resolveTrelloCredentials(directKey, directToken)` resolves
both default variables and runs the credential file check, key first, for setup and the CLI
commands. Worker start calls `resolve` for each variable that the workflow references, reports
missing values, and then calls `CredentialValue#requireLiteralCredentialFileValue`, so its error
order stays the same. `persist()` is derived from the source instead of being stored next to it.
The single enum `TrelloCredentialSource` has `DIRECT_INPUT`, `SHELL_ENVIRONMENT`, `DOTENV_FILE`,
`WORKFLOW_CONFIG`, and `MISSING`.

A differential spike compared the code on `main` with the new resolver and with SmallRye Config
3.17.2:

| Option | Owned lines for lookup and file check | Single-value cases that differ | Two-value and worker cases that differ |
| --- | --- | --- | --- |
| Code on `main` (three copies) | 72 | (baseline) | (baseline) |
| Shared resolver | 40 | 0 | 0 |
| SmallRye Config plus glue | 51 | 0 | not run |
| SmallRye Config with its default behavior | 0 | 2,963 of 6,750 | not run |

Owned lines count non-blank, non-comment Java lines of the lookup, the file check, and the worker
glue. The SmallRye glue replaces only the 12-line lookup with 23 lines. The file check, `persist()`,
and the worker glue stay owned code in both variants. The spike used three inputs:

* 6,750 single-value cases: every combination of 15 direct, shell, and file values (absent, empty,
  whitespace, two literals, `$NAME`, `${NAME}`, `${NAME:-x}`, ` $A `, `${`, `$`, `a${B}`, `$$A`,
  `${A}${B}`, `file:/x`), each with and without unrelated variables named `trello_api_key` and
  `_PROD_TRELLO_API_KEY` in the environment. The file values were written to real files and read
  with `LocalEnvironment.load`.
* 15,625 two-value cases for setup and the CLI commands, and 5,625 worker-start cases with default,
  custom, and missing variable names, to check the key-before-token rejection, the
  missing-before-reference order, and the names and sources in error context.
* 200,000 seeded random single-value cases over `$ { } : - A K _ # " '`, space, and tab.

The spike compared the value (blank values as one class, because every consumer treats `null` and
blank alike), the persist flag, the source, and the error code and message. It counted the old
setup tag `DOTENV` with a blank value as `MISSING`, which is the new name for that state. The one
place where the two names could still differ is `CredentialSelection#sourceDescription` for two
missing values. Setup calls it only after it has prompted for every blank value, so that input
never reaches it. `TrelloCredentialResolverTest` covers the three combinations that do. Two deliberately broken
copies of the old setup code, one without the file check and one without the blank shell filter,
differed in 36 and 60 cases, so the comparison detects real changes.

### Consequences

* Good, because the precedence lives in one method, and the file check lives on the value type, so a
  new caller cannot forget that a winning shell variable skips the check.
* Good, because one enum names the source for persistence, the setup message, and worker-start
  error context.
* Good, because the runtime classpath does not change.
* Bad, because the repository still owns the lookup, about 12 lines.
* Neutral, because the CLI commands now read the credential file once even when both `--key` and
  `--token` are given. The values from the file are ignored in that case, as before.
* Neutral, because `SetupDiagnosticReporter` has a hint line for `DIRECT_INPUT` that worker start
  never produces today.

### Confirmation

* `TrelloCredentialResolver` is the only code that decides which source a Trello credential comes
  from for setup, the CLI commands, and the worker-start credential check.
* `TrelloBoardSetupException` declares no source enum of its own, and `TrelloCredentialStore`
  declares no `CredentialValue` or `CredentialSource`.
* `TrelloCredentialResolverTest`, `TrelloCredentialStoreTest`, `LocalWorkerManagerTest`,
  `TrelloBoardSetupMainTest`, `LocalSetupTest`, and `SetupDiagnosticReporterTest` pass in
  `./mvnw -q spotless:check verify`.
* `pom.xml` gains no dependency for credential lookup.

## Pros and Cons of the Options

### One shared resolver and one source enum, no new dependency

`TrelloCredentialResolver` loads the credential file once and walks direct value, shell
environment, and file in order. It returns a `CredentialValue` that carries the variable name, the
value, and a `TrelloCredentialSource`. Setup, the CLI commands, and worker start all call it.

* Good, because the spike found no difference from any of the three old copies.
* Good, because it removes about 32 owned lines and one enum.
* Good, because the contract is visible in one place and has a focused test table.
* Bad, because it is still hand-written code.

### A SmallRye Config source chain plus glue code

Build a `SmallRyeConfig` per lookup with `SmallRyeConfigBuilder` and three sources: the direct value
(ordinal 400), the shell environment (300), and the credential file (100). Read the winner with
`getConfigValue(name)` and map it back to `TrelloCredentialSource`. SmallRye Config 3.17.2 comes with
the Quarkus BOM, so this adds no dependency.

* Good, because it fits a runtime-chosen file. A config can be built for any path without a Quarkus
  application, and the spike did so.
* Good, because the spike found no difference once the glue was in place.
* Bad, because the glue has to undo SmallRye behavior. It must not add the default interceptors,
  because they expand `${...}` and `$$`. It must put only the exact, non-blank entry into each
  source, because a blank value counts as set and `EnvConfigSource` also answers other spellings
  such as `trello_api_key`. It must map the winner back by ordinal, because `getSourceName()`
  returns text such as `PropertiesConfigSource[source=env]`.
* Bad, because it does not own the rest of the contract. The file check, the persist rule, and the
  missing-before-reference order in worker start still need owned code. The result is 23 lines of
  glue in place of a 12-line lookup, plus a framework concept for a three-step fallback.

### SmallRye Config with its default behavior

Use the same three sources with `addDefaultInterceptors()` and `EnvConfigSource` for the shell
environment, which is how SmallRye Config reads the environment unless code changes it. The spike
set no config profile.

* Good, because it needs no glue to build.
* Bad, because 2,963 of the 6,750 single-value cases chose a different source or value, grouped
  here by the first reason that applies:
  * 1,924 cases expanded `${...}` or `$$` in the winning value. A file value `${X:-y}`, which the
    current code rejects, became `-y`. `$$A` became `$A`. The literal `a${B}` became no value.
  * 962 cases failed with `IllegalArgumentException`, for example a file value
    `${TRELLO_API_KEY:-x}`, which SmallRye expands recursively until it gives up.
  * 45 cases took the value of an unrelated `trello_api_key` variable.
  * 30 cases let a blank shell variable win instead of falling back to the file.
  * 2 cases reported a blank file value as set instead of missing.
* Bad, because the expansion and the other-spelling cases change where a credential can come from,
  which is a user-facing contract change.

### Keep the current shape

Leave the three copies and the two enums as they were.

* Good, because it needs no change.
* Bad, because a precedence or check change must be made three times. The worker-start copy already
  had its own form of the file check and its own enum.
* Bad, because the setup enum tagged a missing value as `DOTENV`, which hid the real state from
  readers of the code.

## More Information

The spike copied the three old implementations from `main` into a standalone Java program in the
`ch.fmartin.symphony.trello.setup` package, compiled it against the branch's classes, and called
the real `TrelloCredentialResolver`. Worker start keeps its glue in private methods, so the spike
used a copy of that glue around the real resolver. The spike never printed a value. All inputs were
synthetic placeholders. The description of the pull request that closes
[GitHub issue #386](https://github.com/martin-francois/symphony-trello/issues/386) contains the spike
source and its output.

The method follows [GitHub PR #801](https://github.com/martin-francois/symphony-trello/pull/801),
which keeps the hand-rolled environment reference classifier, and
[GitHub PR #808](https://github.com/martin-francois/symphony-trello/pull/808), which keeps the
hand-rolled dotenv parser that this resolver uses through `LocalEnvironment.load`.

Worker start launches with values that the generic workflow lookup in `WorkflowEnvironmentResolver`
resolves. That lookup uses the same shell-before-file order for every workflow variable, as
`LocalEnvironment.get` does, but returns only the value. The credential resolver keeps its own walk
because it also needs the direct value, the winning source, and the credential file check. This
decision does not change the generic lookup.

Revisit this decision if credential lookup needs more sources than the three in this record, for
example a secret store. Then compare a SmallRye Config source chain again, with the glue listed
above, against `TrelloCredentialResolverTest`.
