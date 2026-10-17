---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #94](https://github.com/martin-francois/symphony-trello/issues/94)"
  - "[GitHub issue #67](https://github.com/martin-francois/symphony-trello/issues/67)"
  - "[commonmark-java](https://github.com/commonmark/commonmark-java)"
  - "[GitHub Markdown API](https://docs.github.com/en/rest/markdown/markdown)"
informed: [Future maintainers, Contributors]
---

# Keep the Local Diagnostics Markdown Table Helper

## Context and Problem Statement

The `diagnostics` command and the setup-failure report write GitHub-flavored Markdown that users
share in public GitHub issues. `SetupDiagnosticReporter` builds every table in that report through
`MarkdownTable`, a helper of about 80 lines added with the first diagnostics implementation in
[GitHub issue #67](https://github.com/martin-francois/symphony-trello/issues/67). The helper writes
fixed headers, left, right, and center alignment markers, and rows with one space on each side of
every cell, such as `| git | available |`. It checks that every row
has one cell per header. It escapes only table syntax: a pipe becomes `\|`, CR and LF become spaces,
and `null` becomes an empty cell. Redaction happens before a value reaches the helper.

[GitHub issue #94](https://github.com/martin-francois/symphony-trello/issues/94) asked whether
commonmark-java and its GFM tables extension should render these tables instead. Should the
diagnostics tables keep the local helper or move to commonmark-java?

## Decision Drivers

* The raw report bytes are what most readers see. The bug report template's "Relevant output" field
  uses `render: text`, so GitHub shows a pasted report as plain text.
* Column names, redaction markers such as `<redacted>`, and path tokens such as
  `<path:0123456789ab>` must stay readable and searchable in that plain text.
  `SetupDiagnosticReporterTest` matches more than 30 table rows or row fragments literally, and
  several of them contain column names with underscores.
* Row-length checks and table syntax escaping must stay explicit, local, and tested.
* Markdown escaping must never be confused with redaction.
* A new runtime dependency needs a benefit that outweighs its review, license, and update cost.

## Considered Options

* Keep the local `MarkdownTable` helper.
* Build a commonmark-java table AST and render it with `MarkdownRenderer` and `TablesExtension`.
* Build the commonmark-java AST with raw `HtmlInline` cell content to skip text escaping.
* Plug a custom `MarkdownNodeRendererFactory` into commonmark-java that writes the current format.

## Decision Outcome

Chosen option: "Keep the local `MarkdownTable` helper", because it is the only option that keeps
the report bytes unchanged without adding code.

commonmark-java produces valid GFM tables, but its Markdown renderer changes the raw bytes of every
table: it drops the spaces around cells and escapes underscores, angle brackets, backticks, and
other Markdown characters. Keeping today's output would mean replacing the renderer for tables and
text, which is the same code the helper already has, plus a dependency. The class comment in
`MarkdownTable` points to this ADR.

### Consequences

* Good, because the report keeps its current bytes, and readers, tests, and `--lookup` users see
  column names and tokens exactly as written.
* Good, because the whole table format, escaping included, stays in one class of about 80 lines
  that a reviewer can audit without reading library internals.
* Good, because the runtime classpath does not grow and there is no new third-party license to
  track.
* Bad, because the helper does not escape `<`. When GitHub renders a report as Markdown outside a
  code fence, as it does for issues the setup-failure flow posts with `gh issue create --body-file`,
  `<redacted>` disappears and `<path:...>` loses its angle brackets.
  [GitHub issue #846](https://github.com/martin-francois/symphony-trello/issues/846) tracks that.
  The problem also affects report lines outside tables, so moving tables to commonmark-java would
  not fix it.

### Confirmation

* `MarkdownTableTest` covers alignment markers, pipe escaping, CR and LF handling, empty and `null`
  cells, verbatim Markdown characters other than pipes, and row-length failures.
* `pom.xml` has no `org.commonmark` dependency.
* Run:

  ```bash
  ./mvnw -q -Dtest=MarkdownTableTest,SetupDiagnosticReporterTest,TrelloBoardSetupMainTest test
  ```

## Pros and Cons of the Options

### Keep the Local MarkdownTable Helper

`SetupDiagnosticReporter` keeps calling `MarkdownTable.of(...)` or `MarkdownTable.leftAligned(...)`,
adds rows, and appends the table to the report. The helper owns the table syntax, row-length checks,
and pipe and line-break escaping.

* Good, because the output is unchanged.
* Good, because no dependency is added.
* Good, because the escaping rules are one short method next to the row-length check.
* Bad, because the project maintains its own small Markdown writer.
* Bad, because it does not escape other Markdown characters, so a rendered report can lose markers
  (see [GitHub issue #846](https://github.com/martin-francois/symphony-trello/issues/846)).

### Build a commonmark-java AST and Render It With MarkdownRenderer

Add `org.commonmark:commonmark` and `org.commonmark:commonmark-ext-gfm-tables`. For each table,
build a `TableBlock` with a `TableHead`, a `TableBody`, `TableRow` nodes, and `TableCell` nodes with
an alignment and a `Text` child. Render the document with
`MarkdownRenderer.builder().extensions(List.of(TablesExtension.create())).build()`.

* Good, because the library and extension are maintained, licensed under BSD-2-Clause, and small.
  Version 0.30.0 reached Maven Central on 2026-08-06, past the seven-day release-age rule. The
  project released six versions between 2025-09-13 and 2026-08-06, is not archived, and has about
  2,700 GitHub stars. The two jars are 244,624 bytes together and have no runtime dependencies. That
  adds about 0.6% to the 38.7 MiB of compile and runtime dependency jars (140 jars) the project uses
  today.
* Good, because it writes `---:` for right alignment and `:---:` for center alignment. Parsing its
  workflow summary output with the same extension returned the original cell text, once line breaks
  had been replaced with spaces.
* Bad, because none of the tables in the spike matched the current output byte for byte: not the
  three diagnostics tables and not a small center-alignment table. Rows lose the spaces around cells
  (`|tool|status|detail|` instead of `| tool | status | detail |`), and empty cells become `||`.
* Bad, because it escapes characters inside column names and values: `board\_hash`,
  `danger\_full\_access`, `\<redacted\>`, `\<path:0a1b2c3d4e5f\>`, `` \`corepack\` ``, `\*`, `\[`,
  `\&`, and `C:\\Users`. In a plain-text paste, this makes the report harder to read and breaks
  literal searches for column names and tokens.
* Bad, because a line break inside a `Text` node comes out as `&#10;`, so the adapter still needs
  the line-break rule. It also still needs the row-length check, the null rule, and a mapping from
  the project's alignment names to `TableCell.Alignment`. The AST-building code alone was 26 lines
  in the compact spike, so the adapter would not be smaller than the helper.
* Bad, because `MarkdownRenderer.Builder` only offers `lineSeparator`, `nodeRendererFactory`, and
  `extensions`. There is no option for spaces around cells or for turning escaping off.

### Build the AST With Raw HtmlInline Cell Content

Same as the previous option, but put each cell value in an `HtmlInline` node. The renderer writes
that node as it is, except that it still escapes pipes.

* Good, because underscores, angle brackets, and backticks stay as they are. The spike rendered
  `board_hash` and ``<path:0a1b> `x` _y_`` unchanged.
* Bad, because rows still lose the spaces around cells.
* Bad, because a line break in the value ends the row and breaks the table, so the adapter still
  owns the line-break rule.
* Bad, because it uses an HTML node type for plain text, which a reviewer has to understand before
  trusting the output.

### Plug a Custom MarkdownNodeRendererFactory Into commonmark-java

Register a node renderer for the table nodes and for `Text` that writes the spaces around cells and
escapes only pipes and line breaks.

* Good, because it could reproduce the current output.
* Bad, because the extension's table renderer is in an `internal` package, so the project would
  write its own renderer for five table node types plus text. That is the helper's logic again,
  spread over the library's renderer interfaces.
* Bad, because it adds the dependency without removing any project code.

## More Information

The spike ran on 2026-10-04 with commonmark-java 0.30.0 as a standalone Java program outside the
build. It rendered the tool availability, connected boards, and workflow summary tables plus a small
center-aligned table with a copy of the helper logic and with commonmark-java, and compared the
strings. The rows used the reporter's columns and alignments with hand-written values in the shapes
the reporter emits, not the `SetupDiagnosticReporterTest` fixtures. They covered hash tokens,
empty hashes, `null` cells, `<path:...>` tokens, `<redacted>` markers, Windows paths, and values
with pipes, backticks, `*`, `_`, `[`, `&`, and CR and LF. A short table with the helper's output and
the same table with commonmark-java's output also went through the GitHub Markdown API in `gfm`
mode. GitHub showed `Bearer  exit 1` and `path:0a1b2c3d4e5f` for the helper's output, and
`Bearer <redacted> exit 1` and `<path:0a1b2c3d4e5f>` for commonmark-java's output.

The issue's evaluation questions and the answers from the spike:

1. Building a table needs about as much adapter code as the helper, not less.
2. `MarkdownRenderer` with `TablesExtension` does render GFM table Markdown.
3. It writes `---:` for right alignment, but it does not keep the rest of the current syntax, such
   as the spaces around cells.
4. Row-length checks would stay local in either design, because commonmark-java does not check them.
5. It escapes pipes, but it writes CR and LF as `&#10;` instead of spaces. Its extra escaping is
   Markdown escaping, not redaction, and must not be treated as redaction.
6. The dependency is light, but light is not enough when the output gets worse for the main reader.
7. The adapter would be harder to audit than the helper, because the escaping rules would live in
   library code.

Revisit this decision if diagnostics stop being shared as plain text, for example if the report
format moves to rendered Markdown only. In that case, re-run the comparison against the
then-current commonmark-java release.
