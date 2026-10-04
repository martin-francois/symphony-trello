---
status: accepted
date: 2026-10-04
decision-makers: [François Martin]
consulted: ["[GitHub issue #826](https://github.com/martin-francois/symphony-trello/issues/826)", "[ADR 0111](0111-resolvable-diagnostics-path-tokens.md)", SPEC.md, SetupDiagnosticReporter.java]
informed: [Future maintainers]
---

# End Command-Option Path Tokens Like Known-Root Path Tokens

## Context and Problem Statement

The public diagnostics report replaces private paths with `<path:...>` tokens
([ADR 0031](0031-keyed-diagnostics-tokens.md)). Two code paths create these tokens for paths below
a private directory:

* Known roots, such as the home directory and the directories set through
  `SYMPHONY_TRELLO_CONFIG_DIR`, `SYMPHONY_TRELLO_STATE_HOME`, or `SYMPHONY_TRELLO_WORKSPACE_ROOT`.
  Their child path may contain spaces.
* Command option values that are absolute paths, such as `--config-dir`, `--state-home`,
  `--workspace-root`, or `--workflow`. Their child path ended at the first whitespace.

A log line `cloned into <config-dir>/private client/repo`, with the config directory passed as
`--config-dir`, rendered as `cloned into <path:...> client/repo`
([GitHub issue #826](https://github.com/martin-francois/symphony-trello/issues/826)). The text
` client/repo` does not start with `/`, so the generic absolute-path pass left it visible.

Where does a path below a command option directory end in free text?

## Decision Drivers

* No part of a private path may appear in the public report.
* A path below a private directory should get the same token whether the directory came from an
  environment variable or a command option.
* Text after a path in a log line should stay visible when that is safe.

## Considered Options

* Same end rule as known roots: before a key=value field or the line boundary
* First whitespace
* Whole match to the end of the line
* Add command option directories to the known-root list

## Decision Outcome

Chosen option: "Same end rule as known roots: before a key=value field or the line boundary", because it
removes the space/bracket leak and gives both sources one rule without inspecting the filesystem.

The child path of a command option value now uses the same pattern as the child path of a known
root. It accepts spaces and brackets and stops only at a line break or at one of the characters `"`,
`'`, `` ` ``, `<`, `>`, or `|`. A following `key=value` log field marks the end of the path. Otherwise
the whole match stays in one token, even when a shorter directory exists. [ADR 0111](0111-resolvable-diagnostics-path-tokens.md)
records that boundary rule for known roots. Both sources share `CHILD_PATH_SUFFIX` and
`redactPathMatches`, including the path-token collector used by private lookup. The existing root
boundary prevents a directory prefix from matching an unrelated sibling directory.

The option pattern used to stop at `)` and `]` as well. That leaked too: in
`<config-dir>/client (old) notes/repo`, the text ` notes/repo` stayed visible.

### Consequences

* Good, because `<config-dir>/private client/repo` renders as one token when it does not exist.
* Good, because a following `key=value` field remains visible and does not enter the path token.
* Good, because existing shorter directories do not expose the rest of a missing child path.
* Bad, because unstructured trailing text enters the token, even when the path exists.
* Bad, because a closing bracket right after a path, as in `(see <config-dir>/WORKFLOW.md)`, now
  ends up inside the token. Known-root paths already behave this way.

### Confirmation

`SetupDiagnosticReporterTest.rendersPathWithSpaceBelowCommandOptionDirectoryWithoutLeavingItsTailVisible`
passes the config directory as an option. It checks that a missing child path with a space, with or
without brackets, renders as one token with no visible tail despite an existing shorter prefix.
It also checks that a `key=value` field remains visible and that unstructured trailing text stays
inside the token. `privateContextLookupResolvesSpacedToolPathBelowCommandOptionDirectory` verifies
that a version-output token resolves to the same complete path through private lookup.

## Pros and Cons of the Options

### Same end rule as known roots: before a key=value field or the line boundary

Let the child path of an absolute command option value include spaces and brackets, then end the
token before a following `key=value` field. Keep the whole match when there is no such field.

* Good, because spaces and brackets do not expose the remainder of a private child path.
* Good, because a path gets the same token whether its directory came from an option or an
  environment variable.
* Good, because sanitizing text does not read the filesystem.
* Bad, because unstructured trailing text cannot be distinguished from a path containing spaces.

### First whitespace

Keep the existing rule for command option values.

* Good, because it does not read the file system.
* Bad, because a child directory name with a space leaks its tail, which is this bug.

### Whole match to the end of the line

Let the child path run to the end of the line and put all of it into one token.

* Good, because it hides the most text and does not read the file system.
* Bad, because a log line loses everything after the path, including structured `key=value` fields, and the
  token differs from the known-root token for the same path.

### Add command option directories to the known-root list

Add each absolute command option value to the list of known roots, so one redaction pass handles
both.

* Good, because one pattern covers both sources.
* Bad, because the known-root list holds normalized paths, while the option value is matched as
  typed. A value such as `/srv/symphony/../config` that a log line repeats as typed would no longer
  match.
* Bad, because the known-root list comes from the environment, while option values arrive with each
  request together with non-path values such as board names. Merging them moves request state into
  a list that does not depend on the request today.

## More Information

The generic absolute-path pass for paths outside private directories keeps its end rule. It already
accepts spaces and does not stop at whitespace.
