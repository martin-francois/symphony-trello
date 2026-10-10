---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted: ["[GitHub issue #803](https://github.com/martin-francois/symphony-trello/issues/803)", SPEC.md, SetupDiagnosticReporter.java]
informed: [Future maintainers]
---

# Make Diagnostics Path Tokens In Log Lines And Tool Output Resolvable

## Context and Problem Statement

The public diagnostics report replaces private paths with `<path:...>` tokens, and
`diagnostics --show-private-context --lookup <token>` maps a token back to its local value
([ADR 0030](0030-private-diagnostics-context-flag.md),
[ADR 0031](0031-keyed-diagnostics-tokens.md)). The repeatable bug bash found two kinds of tokens
that the lookup reported as `not_found`
([GitHub issue #803](https://github.com/martin-francois/symphony-trello/issues/803)):

* A worker log line such as `workflow=<path> outcome=loaded`. The path is under the config directory,
  one of the known private roots. The child part of the known-root pattern accepts spaces, because
  directory names can contain them, so it ran to the end of the line. The token hashed the path plus
  ` outcome=loaded`, and that text disappeared from the report.
* The tool table. On a host where `docker` is the Podman shim, the first output line of
  `docker --version` is the notice `Emulate Docker CLI using podman. Create
  /etc/containers/nodocker to quiet msg.`. Diagnostics tokenizes that path, but no private-context
  mapping lists it.

Where does a known private path end in free text, and how can the lookup resolve paths that appear
in tool output?

## Decision Drivers

* No part of a private path may appear in the public report. A path that contains a space must not
  leave its tail visible.
* Every path token in the report should resolve through a focused lookup with the same selector.
* Text after a path in a log line should stay visible.
* The lookup must not run network or auth probes and must not print log contents.

## Considered Options

For the end of a path under a known private root:

* Longest existing prefix before whitespace
* First whitespace
* Whitespace before a `key=` pair
* Whole match to the end of the line

For paths in tool output:

* Lookup reruns tool version probes
* Leave tool output untokenized
* Fixed list of tool paths

## Decision Outcome

Chosen options: "Longest existing prefix before whitespace" and "Lookup reruns tool version probes",
because together they make the reported tokens resolve without
leaving any part of a private path visible.

The workflow, log, and PID file paths that worker log lines name normally exist while diagnostics
runs, so their tokens now end at the real path and the lookup finds them. A path that no longer
exists stays one token with the text after it, as before.

The lookup runs the same `--version` commands as the public report and records the path tokens that
sanitizing the first output line emits, under the `tool` category. The codex and gh auth status
(`; login=...`, `; auth=...`) is now appended after the sanitized version line, so a path token in
that line is the same with and without `--deep`. The lookup never runs the auth probes.

### Consequences

* Good, because `workflow=<path:...> outcome=loaded` keeps its trailing text and resolves to the
  workflow path.
* Good, because a path with a space, such as a directory named `private client`, still renders as one
  token when it does not exist on disk.
* Good, because any path that a tool prints in its version line, such as the Podman notice or a
  `Picked up JAVA_TOOL_OPTIONS:` line from `java -version`, resolves without a hand-kept list.
* Bad, because the token for a path followed by text now depends on whether the path exists when
  diagnostics runs. A file deleted between two reports changes its token in the second report.
* Bad, because a path followed by text still swallows that text when the path is gone. The lookup then
  resolves nothing for it, which matches the behavior before this change.
* Bad, because a focused lookup now runs the local tool version commands, which adds a short delay.
* Bad, because the tool token for the Podman notice still covers ` to quiet msg.`, since that path
  is outside the known roots and does not exist. The lookup shows that whole text as the value.

### Confirmation

`SetupDiagnosticReporterTest` covers both cases:

* `privateContextLookupResolvesWorkerLogPathTokenFollowedByText` checks that the log line keeps
  ` outcome=loaded`, that the emitted token resolves to the workflow path, and that a missing child
  path with a space leaves no fragment in the report.
* `privateContextLookupResolvesPathTokensFromToolVersionOutput` checks that the docker and codex tool
  tokens from a `--deep` report resolve through a lookup.

The `diagnostics-focused-lookup-for-emitted-path-tokens` row of the live bug-bash harness from
[GitHub PR #809](https://github.com/martin-francois/symphony-trello/pull/809) replays the original
report end to end against the fake Trello API and fake Codex.

## Pros and Cons of the Options

### Longest existing prefix before whitespace

Match the known root and its child path as before, including spaces, then end the token at the
longest prefix before whitespace that exists on disk. Keep the whole match when no prefix exists.

* Good, because real paths with spaces stay whole and text after a real path stays visible.
* Good, because it never leaves a partial path visible.
* Bad, because it reads the file system while sanitizing text.

### First whitespace

End the child path at the first whitespace, as the existing rule for absolute-path sensitive values
already does.

* Good, because it is the smallest change.
* Bad, because a path with a space leaks its tail. `<config>/private client/repo` would render as
  `<path:...> client/repo`. The regression test fails with this rule.

### Whitespace before a `key=` pair

End the child path at whitespace that is followed by a word and `=`, the format of the orchestrator
log lines.

* Good, because it does not read the file system.
* Bad, because it covers only key-value log lines, not free text such as exception messages.

### Whole match to the end of the line

Keep the current pattern, which puts everything from the known root to the end of the line into one
token.

* Good, because it hides the most text.
* Bad, because the token cannot be resolved and the log line loses information.

### Lookup reruns tool version probes

Run each diagnostics tool's version command during the lookup and record the token and value for
every path that sanitizing the first output line tokenizes.

* Good, because it maps exactly what the report printed, whatever the tool wrote.
* Bad, because tool output that changes between the report and the lookup, such as after an upgrade,
  no longer resolves.

### Leave tool output untokenized

Print the tool version line without path redaction.

* Good, because nothing needs a mapping.
* Bad, because the first line can carry private paths. Process output includes stderr, and
  `java -version` prints `Picked up JAVA_TOOL_OPTIONS: ...` first when that variable is set.

### Fixed list of tool paths

Add mappings for the resolved tool executables and other known tool locations.

* Good, because the lookup does not run any command.
* Bad, because the tokens come from free-form tool output, so a fixed list cannot match a path such
  as `/etc/containers/nodocker`.

## More Information

The generic absolute-path redaction for paths outside the known roots keeps its current end rule.
Those paths are not in the private-context mapping unless a tool printed them, and checking whether
arbitrary text exists on disk could reach a network share on Windows. The existence check runs only
on matches that start with a known root, so it reaches a network share only when that root, such as
a redirected Windows home directory, already lives on one.

The setup-failure report shows a longer tool list than `diagnostics`. The lookup maps only the
`diagnostics` tool list, because the lookup is part of the `diagnostics` command.
