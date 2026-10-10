---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #820](https://github.com/martin-francois/symphony-trello/issues/820)"
  - "[GitHub issue #799](https://github.com/martin-francois/symphony-trello/issues/799)"
  - "[GitHub PR #821](https://github.com/martin-francois/symphony-trello/pull/821)"
  - "[ADR 0058](0058-generated-workflow-repository-source-precedence.md)"
  - "[SPEC.md Section 6.1](../../SPEC.md#61-configuration-resolution-pipeline)"
informed: [Future maintainers, Contributors]
---

# Fail path settings on missing environment variables

## Context and Problem Statement

Path settings may start with an environment reference, such as `$SYMPHONY_PARENT` or
`$SYMPHONY_PARENT/workspaces`. When that variable was unset, most path settings kept the reference
as literal text and resolved it relative to `WORKFLOW.md`:

- `workspace.root: $SYMPHONY_PARENT/workspaces` became a directory named
  `$SYMPHONY_PARENT/workspaces` next to `WORKFLOW.md`, and Symphony created workspaces there
  without a warning.
- `codex.additional_writable_roots` entries gave Codex a writable root at the same kind of literal
  directory.
- `file:$SYMPHONY_SECRETS/api-key` failed, but the error named the literal path instead of the
  missing variable.

`repository.default_path` behaved differently: [ADR 0058](0058-generated-workflow-repository-source-precedence.md)
resolves a missing or blank variable there to absent. The same text meant three different things
depending on the field. A blank variable was no better: `$SYMPHONY_PARENT/workspaces` with an empty
value became the absolute path `/workspaces`.

`SPEC.md` Section 6.1 resolves `$VAR` only for values that explicitly reference a variable, and
Section 5.3.1 treats an empty credential variable as missing. It said nothing about a path whose
variable is missing. What should each path setting do with an unset or blank variable?

## Decision Drivers

- A configured path must never silently become a literal `$NAME` directory or a different
  directory than the one the user wrote.
- The error must name the setting and the variable, so the user knows what to set.
- Keep settings whose absent state is meaningful and documented working the same way.
- Compose with [GitHub PR #821](https://github.com/martin-francois/symphony-trello/pull/821), which
  moves path reference parsing into `EnvironmentReferences.pathReference`, without a second parser.

## Considered Options

- Fail configuration for path settings without an absent state, and keep `repository.default_path`
  absent.
- Fail configuration for every path setting, including `repository.default_path`.
- Treat a missing variable as an absent setting everywhere.
- Keep the literal text and log a warning.

## Decision Outcome

Chosen option: "Fail configuration for path settings without an absent state, and keep
`repository.default_path` absent", because each of those settings either has a default the user
chose to override or no meaningful empty value, so any substitute directory would be a guess.

`ConfigResolver.pathFailingOnMissingVariable` resolves `workspace.root`, `codex.additional_writable_roots` entries,
`SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS` entries, and `file:` secret paths. It passes
`ConfigResolver.expandPath` a lookup that throws a `missing_path_environment_variable`
`ConfigException` when the variable is unset or blank, for example
`workspace.root references missing environment variable SYMPHONY_PARENT`. Service startup fails
with that error, and a dynamic reload keeps the last good configuration, as for any other invalid
configuration. `symphony-trello start` shows `workspace.root references a missing environment
variable.` Start output uses fixed labels so that text read from the workflow file never reaches
it, the same way the `tracker.board_id` and `server.port` start checks work. The variable name stays
in the logged cause.

`repository.default_path` keeps its lenient expansion. It is optional with no default, and a
workflow without a repository default is a supported setup, so absent is a real state there.

`SetupDiagnosticReporter` also calls `expandPath` to map `file:` secret paths to lookup tokens. It
keeps the lenient lookup, because diagnostics must report a broken workflow, not fail on it.

The strict lookup is a function argument, not a second parser. After
[GitHub PR #821](https://github.com/martin-francois/symphony-trello/pull/821),
`expandPath` calls the lookup only for a valid `$NAME` or `${NAME}` first segment, so the strict
lookup covers the braced form without further changes. Until then, a value such as
`${NAME}/suffix` fails with the variable name `{NAME}` instead of becoming a literal directory.

### Consequences

- Good, because a workflow whose path variable is missing fails at startup with the setting and
  variable in the error, instead of running in the wrong directory.
- Good, because a blank variable can no longer turn `$NAME/workspaces` into `/workspaces`.
- Good, because a `file:` secret error now names the missing variable instead of a literal path.
- Bad, because the same missing variable still means two things: a configuration error in most path
  settings and absent in `repository.default_path`. The SPEC states both.
- Neutral, because the change only affects values that already resolved to a wrong directory or
  failed later. `SPEC.md` never promised that an unresolved reference stays literal, so the change
  is a compatible fail-fast fix.

### Confirmation

- `ConfigResolverTest.missingEnvironmentReferenceInPathSettingFailsConfigurationInsteadOfBecomingLiteralDirectory`
  covers every strict setting with an unset and a blank variable, through `WorkflowLoader` and
  `ConfigResolver`.
- `ConfigResolverTest.missingEnvironmentReferenceInRepositoryDefaultPathPrefixResolvesToAbsent`,
  `missingOptionalRepositoryDefaultPathEnvironmentReferenceResolvesToAbsent`, and
  `blankOptionalRepositoryDefaultPathEnvironmentReferenceResolvesToAbsent` keep
  `repository.default_path` absent.
- `WorkflowConfigEditorTest.launchValidationNamesPathSettingThatReferencesMissingEnvironmentVariable`
  checks the `symphony-trello start` message.
- `SPEC.md` Sections 5.3.3, 6.1, 6.3, 6.4, and 17.1 state the rule per setting.

## Pros and Cons of the Options

### Fail configuration for path settings without an absent state

`workspace.root`, Codex writable roots, and `file:` secret paths fail configuration when their
leading variable is unset or blank. `repository.default_path` resolves to absent, as
[ADR 0058](0058-generated-workflow-repository-source-precedence.md) decided.

- Good, because each setting gets the result that matches its meaning: a required location fails,
  an optional fallback disappears.
- Good, because the error appears at startup, before any workspace or Codex sandbox uses the path.
- Bad, because the rule differs by setting, so the SPEC must list the settings.

### Fail configuration for every path setting

`repository.default_path` would fail too.

- Good, because one rule covers every path setting.
- Bad, because it breaks workflows that leave the variable unset on purpose, for example one
  workflow shared by hosts where only some have a local checkout. ADR 0058 documents that absent
  result, and those workflows run today.

### Treat a missing variable as an absent setting everywhere

`workspace.root` would fall back to `<system-temp>/symphony_workspaces`, a writable-root entry would
be dropped, and a `file:` secret would count as a missing credential. The upstream Symphony
reference implementation does this for a whole-value `workspace.root: $VAR`.

- Good, because it matches the upstream reference implementation for that one form.
- Bad, because workspaces would move to a temporary directory that the user did not choose and that
  some hosts clear on reboot, with no error.
- Bad, because a dropped writable root fails later inside a Codex turn, far from the cause.
- Bad, because a missing `file:` secret variable would report `tracker.api_key is required`, which
  hides the real problem.

### Keep the literal text and log a warning

Resolution keeps working as before and logs that the variable is missing.

- Good, because nothing that resolves today stops resolving.
- Bad, because the service still creates workspaces in a literal `$NAME` directory, which is the
  bug. A warning in a log is easy to miss.

## More Information

This differs from the upstream Symphony reference implementation for a whole-value
`workspace.root: $VAR`, which falls back to the default root. It does not differ from the upstream
specification, which does not define a missing path variable. Realigning would need a way to tell
the user that the configured root was replaced, and would still leave the prefix form undefined.

[ADR 0109 in GitHub PR #821](https://github.com/martin-francois/symphony-trello/pull/821) records
why `${NAME}` and `${NAME}/suffix` expand in path settings. This ADR covers what happens when the
referenced variable is missing.
