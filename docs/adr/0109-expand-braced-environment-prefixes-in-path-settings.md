---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #799](https://github.com/martin-francois/symphony-trello/issues/799)"
  - "[GitHub issue #384](https://github.com/martin-francois/symphony-trello/issues/384)"
  - "[GitHub PR #801](https://github.com/martin-francois/symphony-trello/pull/801)"
  - "[SPEC.md Section 6.1](../../SPEC.md#61-configuration-resolution-pipeline)"
informed: [Future maintainers, Contributors]
---

# Expand braced environment prefixes in path settings

## Context and Problem Statement

`tracker.api_key` and `tracker.api_token` accept `$NAME` and `${NAME}` through
`EnvironmentReferences`. Path settings did not use that class for the part that matters. They split
a value at the first `/` with their own code and took everything after the `$` as the variable
name. For `${NAME}/suffix` that name is `{NAME}`, which never exists:

- `repository.default_path: ${NAME}/project` resolved to no path at all, with no error.
- `workspace.root`, `codex.additional_writable_roots`, and `file:` secret paths kept the text
  literally, so `workspace.root: ${NAME}/workspaces` became a directory named
  `${NAME}/workspaces` next to `WORKFLOW.md`.

The two path code paths also disagreed with each other. `repository.default_path` checked the
variable name, the others looked up any text after the `$`.

`SPEC.md` promised `$VAR` expansion for path values and said nothing about `${VAR}`. Which way
should a braced prefix go: expand it, or reject it with a configuration error?

## Decision Drivers

- A configured path must never silently become no path or a literal directory.
- One reference grammar for every workflow value, so a user who learned `${NAME}` for credentials
  gets the same result in a path.
- The bare `$NAME/suffix` form already expands, so the braced form should not behave worse.
- Keep the change small and in the existing classifier, which
  [GitHub PR #801](https://github.com/martin-francois/symphony-trello/pull/801) for
  [GitHub issue #384](https://github.com/martin-francois/symphony-trello/issues/384) keeps hand-rolled.

## Considered Options

- Expand `${NAME}` and `${NAME}/suffix` in path settings through `EnvironmentReferences`.
- Reject a braced path prefix with a configuration error that names the supported `$NAME` forms.
- Teach the two existing path parsers about braces and leave them separate.
- Interpolate references anywhere in a path value.

## Decision Outcome

Chosen option: "Expand `${NAME}` and `${NAME}/suffix` in path settings through
`EnvironmentReferences`", because it matches how the bare `$NAME/suffix` form behaves today and
gives path settings the same grammar as the credential settings.

`EnvironmentReferences.pathReference` splits a value at the first `/` and classifies the first
segment with `referenceName`. `ConfigResolver.expandPath` and the `repository.default_path`
resolution both use it, and so does setup diagnostics through `expandPath`. What each caller does
with an unset variable does not change: `repository.default_path` treats it as absent, the other
path settings keep the text.

The name check now applies to every path setting. Before, `workspace.root: $MY-DIR/x` looked up a
variable named `MY-DIR`. Now `MY-DIR` is not a valid name, so the value stays literal. The `.env`
loader already rejects such names and POSIX shells cannot export them, so only a variable set
through another mechanism could have reached that lookup. `repository.default_path` already
applied the check.

### Consequences

- Good, because `${NAME}` and `${NAME}/suffix` now resolve in `workspace.root`,
  `repository.default_path`, `codex.additional_writable_roots`, and `file:` secret paths.
- Good, because one class owns the reference grammar for credentials and paths.
- Good, because setup diagnostics and runtime configuration resolve `file:` secret paths the same
  way, since both call `expandPath`.
- Bad, because a variable whose name fails the check, such as `MY-DIR`, no longer expands in
  `workspace.root`, `codex.additional_writable_roots`, or `file:` secret paths. No documented or
  generated workflow used such a name.
- Neutral, because an unset variable in `workspace.root` still leaves the value literal. That is the
  existing behavior of the bare form and is tracked in
  [GitHub issue #820](https://github.com/martin-francois/symphony-trello/issues/820).

### Confirmation

- `ConfigResolverTest.expandsEnvironmentReferenceAtStartOfEveryPathSetting` resolves `$NAME`,
  `${NAME}`, `$NAME/suffix`, and `${NAME}/suffix` in `workspace.root`, `repository.default_path`,
  and `codex.additional_writable_roots`.
- `ConfigResolverTest.expandsEnvironmentReferenceAtStartOfSecretFilePath` reads a `file:` secret
  through both prefix forms.
- `SetupDiagnosticReporterTest.privateContextLookupResolvesFileBackedSecretTokenWithoutReadingSecretValue`
  finds the same `file:` secret through a literal, a `$NAME`, and a `${NAME}` directory.
- `SPEC.md` Section 6.1 lists the accepted path reference shapes and the name rule.

## Pros and Cons of the Options

### Expand `${NAME}` and `${NAME}/suffix` through `EnvironmentReferences`

Both path code paths classify the first segment with the credential classifier and expand either
form.

- Good, because both forms behave the same, as they already do for credentials.
- Good, because it removes the hand-written split in two places.
- Bad, because it applies the name check to settings that had none.

### Reject a braced path prefix with a configuration error

Configuration fails when a path starts with `${`, and the error names `$NAME` and `$NAME/suffix` as
the supported forms.

- Good, because the error tells the user what to write instead.
- Bad, because the credential settings accept the same text, so the grammar would differ by field.
- Bad, because it needs a new error path and SPEC wording for a form users already expect to work.

### Teach the two existing path parsers about braces

`expandPath` and the `repository.default_path` code each strip `{` and `}` themselves and keep their
own split at the first `/`.

- Good, because it is the smallest diff in each method.
- Bad, because it keeps two parsers that already disagreed about name checks, which is how this bug
  appeared.

### Interpolate references anywhere in a path value

Every `$NAME` or `${NAME}` inside a path value is replaced, wherever it appears.

- Good, because `/srv/${NAME}/x` would work.
- Bad, because SPEC Section 6.1 resolves only values that explicitly are a reference, and existing
  paths that contain a literal `$` would change meaning.
- Bad, because [GitHub PR #801](https://github.com/martin-francois/symphony-trello/pull/801) found that the
  interpolating libraries change which credential values count as references.

## More Information

The reference grammar itself, and why it stays hand-rolled, is recorded for
[GitHub issue #384](https://github.com/martin-francois/symphony-trello/issues/384) in
[GitHub PR #801](https://github.com/martin-francois/symphony-trello/pull/801).
