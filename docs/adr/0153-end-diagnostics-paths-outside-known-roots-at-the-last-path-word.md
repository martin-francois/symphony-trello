---
status: accepted
date: 2026-10-31
decision-makers: [François Martin]
consulted:
  - "[ADR 0111](0111-resolvable-diagnostics-path-tokens.md)"
  - "[GitHub issue #1030](https://github.com/martin-francois/symphony-trello/issues/1030)"
  - "[GitHub issue #803](https://github.com/martin-francois/symphony-trello/issues/803)"
  - SPEC.md
  - SetupDiagnosticReporter.java
informed: [Future maintainers]
---

# Preserve recognized diagnostics path boundaries

## Context and Problem Statement

Diagnostics hide absolute paths behind keyed tokens. The Podman shim prints
`Emulate Docker CLI using podman. Create /etc/containers/nodocker to quiet msg.`.
The old scanner includes `to quiet msg.` in the path token, so the notice loses its instruction
and lookup maps to the path plus prose. Issue #1030 asks to preserve that text without exposing
any fragment of a private path.

Unquoted text has no general boundary between a filename containing spaces and following prose.
The proposed last-word rule exposed `Doe` from `C:\Users\Jane Doe` and `Tools` from
`/srv/Client Tools`. Checking filesystem existence also changes tokenization after deletion and
cannot identify Windows paths on a Linux host. Those rules violate the privacy contract.

## Decision Drivers

* Hide every private path fragment, including missing paths and names containing spaces.
* Preserve the fixed instruction in the reported Podman notice.
* Keep tokenization independent of filesystem existence for unknown-root paths.
* Resolve only values represented in the current private context.

## Considered Options

* Recognize complete output formats and keep ambiguous text hidden.
* Infer path boundaries from whitespace, separators, or filesystem existence.
* Keep every unknown-root match unchanged.

## Decision Outcome

Chosen option: recognize complete output formats and keep ambiguous text hidden.

After credential filtering, recognize the complete Podman shim notice. Tokenize its path between
the fixed `Emulate Docker CLI using podman. Create` prefix and the `to quiet msg.` suffix,
including the separating spaces. The fixed suffix remains visible;
later prose containing slashes does not extend the extracted path. Quoted paths and structured
log fields after known-root paths keep their established boundaries. Outside-root filenames can
contain `key=value` words, so those words do not delimit their tokens. A standalone `to`, `is`,
or other prose word does not establish a boundary.

Other unquoted paths outside known roots retain their ambiguous tails inside the token. This
includes punctuation and words without separators. Their mappings describe the captured text;
they do not promise a filesystem path without surrounding prose. No existence probe shortens them.
Complete Podman notices use this rule under known roots too, before generic root redaction.
Other known-root behavior remains governed by ADR 0111.

Lookup rebuilds current mappings. A workflow discovered by scanning the config directory disappears
from those mappings when it is deleted. A workflow retained in the connected-board manifest remains
represented by its stored path. Return `not_found` only when the token is absent from current
context; never substitute a similar path. A deleted explicit workflow selector still fails selection.

### Consequences

* Good, because the reported Podman instruction remains readable and its token maps to the path alone.
* Good, because missing files, Windows paths on Linux, and filenames ending in words without
  separators retain every private fragment inside the token.
* Good, because deleting an unknown-root file does not change tokenization of the same notice.
* Bad, because unknown unquoted formats still hide some useful surrounding prose.
* Bad, because supporting another free-text format requires a justified boundary and regression tests.

### Confirmation

`SetupDiagnosticReporterTest` covers missing POSIX and Windows final components containing spaces,
existing files followed by punctuation, slash-containing prose, and token stability after deletion.
It checks the full Podman notice with existing and missing paths, complete path redaction, exact
lookup values, preserved suffixes, and adjacent credentials. Quoted paths retain following prose.

The deleted-workflow regression first proves `found`, then deletes the file and checks `not_found`
with the same selector-less lookup. A separate case retains the deleted path in the manifest.
The `diagnostics-focused-lookup-for-emitted-path-tokens` bug-bash row exercises the complete notice
and checks every distinctive private path component before retaining its `covered` status.

## Pros and Cons of the Options

### Recognize complete output formats and keep ambiguous text hidden

Use a known message's complete grammar or an explicit delimiter to separate a path from prose.
Keep the whole ambiguous match when neither exists.

* Good, because the Podman regression is fixed without allowing private filename fragments out.
* Good, because behavior does not depend on which files happen to exist on the diagnostics host.
* Bad, because arbitrary free text cannot always produce a token containing only a path.

### Infer boundaries from whitespace, separators, or existence

End the path at the first space, the last word containing a separator, or the longest existing prefix.

* Good, because these rules preserve prose in some messages without recognizing their format.
* Bad, because they expose missing filenames containing spaces and fail across operating systems.
* Bad, because later prose such as `1/2` extends the guessed path and deletion changes the token.

### Keep every unknown-root match unchanged

Keep the old scanner, including its handling of recognized notices.

* Good, because it adds no new boundary guesses.
* Bad, because it leaves the Podman instruction hidden and fails the reported regression.

## More Information

* [GitHub issue #1030](https://github.com/martin-francois/symphony-trello/issues/1030)
* [ADR 0111](0111-resolvable-diagnostics-path-tokens.md)
* [Diagnostics contract](../../SPEC.md)
