---
status: accepted
date: 2026-10-31
decision-makers: [François Martin]
consulted:
  - "[ADR 0120](0120-keep-local-diagnostics-markdown-table-helper.md)"
  - "[GitHub issue #846](https://github.com/martin-francois/symphony-trello/issues/846)"
  - "[GitHub Markdown API](https://docs.github.com/en/rest/markdown/markdown)"
  - "[CommonMark 0.31.2: backslash escapes, raw HTML, autolinks](https://spec.commonmark.org/0.31.2/)"
  - SPEC.md
informed: [Future maintainers, Contributors]
---

# Escape Angle Brackets in Diagnostics Markdown Text

## Context and Problem Statement

The `diagnostics` report and the setup-failure report are GitHub-flavored Markdown. They replace
private values with markers such as `<redacted>`, `<path:0123456789ab>`, `<value:...>`, and
`<id:...>`. The setup-failure flow posts the report as an issue body with
`gh issue create --body-file`, and users paste reports into issues by hand.

GitHub reads `<redacted>` as an HTML tag and drops it. It reads `<path:0123456789ab>` as an autolink
with an unknown scheme and shows `path:0123456789ab` without the brackets. The GitHub Markdown API
in `gfm` mode turns `start --config-dir <redacted> --app-home <path:5c43464e7e19>` into
`start --config-dir  --app-home path:5c43464e7e19`
([GitHub issue #846](https://github.com/martin-francois/symphony-trello/issues/846)). The private
values were never in the text, but a reader of the rendered issue cannot see that anything was
redacted.

How should the report write these markers so that a rendered issue shows them?

## Decision Drivers

* A rendered issue must show every marker, in list lines and in table cells.
* The plain-text report must stay readable, because the bug report template's "Relevant output"
  field uses `render: text`.
* `--lookup` must accept a token copied from the rendered issue and from the raw report.
* Redaction must not change. Markdown escaping is not redaction.
* Code spans and fenced blocks already show their content literally and must keep their bytes.

## Considered Options

* Escape `<` as `\<` in Markdown text outside code
* Put each marker in a code span
* Post the setup-failure issue body with the report inside a fenced block
* Change the marker shape, for example to `[redacted]` and `[path:...]`

## Decision Outcome

Chosen option: "Escape `<` as `\<` in Markdown text outside code", because it fixes every `<` that
GitHub would read as HTML, not only the known markers, and it leaves the redaction code alone.

`SetupReportMarkdown.escapeHtmlStart` writes each `<` outside a code span as `\<`. A backslash
in a run directly before a `<` becomes `\\`, so every original backslash stays visible.
Backslash parity determines whether an opening backtick is escaped. An odd run escapes only the
first backtick; any remaining backticks still participate in code-span matching. Backslashes do not
escape closing backticks inside a code span. A code span keeps its bytes,
because a renderer shows its content literally. The list-line helper `SetupReportMarkdown.line` and
every `MarkdownTable` cell use it. Headings, fixed sentences, and fenced blocks are written as before.
The report then contains `--config-dir \<redacted>`, and GitHub shows `--config-dir <redacted>`.

`--lookup` strips one leading backslash before `<`, so `\<path:0123456789ab>` copied from the raw
file resolves like `<path:0123456789ab>` copied from the rendered issue. The local Codex
investigation prompt tells Codex about the escape. The `--json` output carries the same Markdown
text in its `report` field, whose `format` is `markdown`.

### Consequences

* Good, because a rendered issue shows `<redacted>`, `<path:...>`, `<value:...>`, `<id:...>`,
  `<github-url:...>`, and any other `<` from sanitized text.
* Good, because the redaction code and its tests do not change; only the expected bytes of Markdown
  lines gain a backslash.
* Good, because one function owns the rule, next to the table escaping that ADR 0120 keeps local.
* Bad, because the plain-text report shows `\<redacted>` and `\<path:...>`. A reader of a pasted
  `render: text` report sees one extra character per marker.
* Bad, because the private-context output also shows `\<path:...>` in its tables, though it is
  meant for a terminal, not GitHub. One rule for every Markdown line is easier to audit than two.
* Bad, because a renderer that ignores backslash escapes would show the backslash. GitHub and
  CommonMark renderers apply them.

### Confirmation

* `SetupReportMarkdownTest` renders list lines and table cells with commonmark-java and its GFM
  tables extension. It checks one through four backslashes before both marker shapes, odd and even
  backslash parity before opening backticks, multi-backtick spans, and backslashes inside code.
* `MarkdownTableTest.keepsMarkdownCharactersOtherThanPipesAndHtmlStartsVerbatim` covers the raw cell bytes.
* `SetupDiagnosticReporterTest.setupFailureReportEscapesEveryHtmlStartOutsideCodeSoGitHubShowsTheMarkers`
  renders a real setup-failure report and checks visible markers and the absence of private values.
* `commonmark` and `commonmark-ext-gfm-tables` are test-only dependencies, pinned together in
  `pom.xml` and owned by Renovate's Maven manager. Version 0.31.0 was published to Maven Central on
  2026-10-22, more than seven days before adoption. They add no runtime dependencies. The independent
  parser catches mistakes that assertions on escape bytes and a duplicate code-span scanner missed.
* `SetupDiagnosticReporterTest.privateContextLookupResolvesOneDiagnosticsToken` looks up the
  token with and without the backslash.

## Pros and Cons of the Options

### Escape `<` as `\<` in Markdown Text Outside Code

Before writing a list value or table cell, put a backslash before every `<` that is not inside a
code span. CommonMark treats `\<` as a literal `<`.

* Good, because it covers every HTML start, not a fixed list of marker names.
* Good, because the GitHub Markdown API returned `&lt;redacted&gt;` and `&lt;path:...&gt;` for
  escaped list lines and table cells.
* Bad, because the raw text gains a backslash per `<`.
* Bad, because the escaper has to find code spans, so it implements the CommonMark backtick rule.

### Put Each Marker in a Code Span

Write `` `<redacted>` `` and `` `<path:...>` `` in Markdown text.

* Good, because the rendered marker stands out as code.
* Bad, because only text matching a known marker shape gets wrapped. Any other `<`, such as
  `<init>` in a stack frame, still disappears.
* Bad, because a marker that is already inside a code span would break the span, so the writer
  needs the same code-span scanner as the chosen option.
* Bad, because the plain text gains two backticks per marker instead of one backslash.

### Post the Setup-Failure Issue Body With the Report Inside a Fenced Block

Keep the report bytes and wrap the whole report in a fence when the setup-failure flow creates the
issue.

* Good, because the report file does not change.
* Bad, because the issue shows the tables as raw pipes instead of rendered tables.
* Bad, because it covers only the issues the setup-failure flow creates, not reports that users
  paste into an issue or comment themselves.

### Change the Marker Shape

Replace the angle brackets with a shape GitHub leaves alone, such as `[redacted]` and
`[path:0123456789ab]`.

* Good, because the plain text stays as readable as today. The GitHub Markdown API showed
  `[redacted]` and `[path:...]` unchanged in a list line and a table cell.
* Bad, because it changes the redaction code, every lifecycle hint that prints a token, the
  documented token format in `SPEC.md`, the bug-bash patterns, and every test that names a marker.
* Bad, because `[...]` is link syntax. A marker followed by `(` or a matching link reference
  definition becomes a link.
* Bad, because tokens in reports from released versions use `<path:...>`, so `--lookup` would have
  to accept both shapes indefinitely.

## More Information

The comparison used the GitHub Markdown API (`gh api markdown`, `mode: gfm`) on 2026-10-31 with a
list line and a table taken from a setup-failure report.
