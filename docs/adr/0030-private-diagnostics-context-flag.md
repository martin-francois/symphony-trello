---
status: accepted
date: 2026-05-29
decision-makers: [Francois Martin, Codex]
consulted: [AGENTS.md, README.md, SPEC.md, docs/operations.md, TrelloBoardSetupMain.java, SetupDiagnosticReporter.java]
informed: [Future maintainers]
---

# Name Private Diagnostics Context Flag

## Context and Problem Statement

The diagnostics command is public-safe by default. It tokenizes or redacts private Trello identifiers,
Trello board URLs, local paths, account names, and credential-looking values so users can paste the
report into a public GitHub issue.

Maintainers and local coding agents sometimes need to map a public-safe value such as `board_hash`,
`key_hash`, or `<path:...>` back to the local Trello board, workflow file, env file, workspace root,
state directory, or log file. That lookup must stay local because it intentionally reveals private
context. It must not reveal Trello API keys, Trello tokens, GitHub tokens, Codex auth data, or worker
log contents.

What should the CLI flag for this local-only lookup be called?

## Decision Drivers

* The name should make the output sound sensitive enough that users do not paste it into public
  issues.
* The name should not imply that credentials or other secret values are printed.
* The name should be broad enough for future private diagnostics context beyond only ids, URLs, and
  paths.
* The name should be understandable without reading a long explanation.
* The default `diagnostics` command must remain safe for public issue reports.

## Considered Options

* `--show-private-context`
* `--local-identifiers`
* `--unredacted`
* `--only-secrets-redacted`
* `--show-identifiers-and-paths`
* `--show-local-references`
* `--reveal-hashes`

## Decision Outcome

Chosen option: `--show-private-context`, because it communicates that the output contains private
local information without implying that credentials are revealed. It is also broad enough for future
private diagnostics context if diagnostics later tokenizes more than identifiers, URLs, and paths.

The flag prints a separate private diagnostics context report. The report must start with a clear
warning that it is not for public issues, may include Trello board names, Trello board ids, Trello
board URLs, and local paths, and does not include credential values or worker log contents.

The public-safe command remains `symphony-trello diagnostics` without this flag.

### Consequences

* Good, because the flag name signals privacy risk.
* Good, because the flag name does not suggest Trello API keys, Trello tokens, GitHub tokens, or
  Codex auth contents will be shown.
* Good, because the name stays valid if future diagnostics add more private context types.
* Bad, because `private context` is less concrete than `identifiers and paths`, so the help text and
  warning must explain the exact current contents.
* Bad, because changing the flag later would be a user-visible breaking CLI change.

### Confirmation

Run these checks before changing this behavior:

```bash
./mvnw -q -Dtest=SetupDiagnosticReporterTest,TrelloBoardSetupMainTest test
corepack pnpm dlx markdownlint-cli2 README.md SPEC.md docs/operations.md AGENTS.md \
  .codex/skills/debug/SKILL.md docs/adr/0030-private-diagnostics-context-flag.md
./mvnw -q spotless:check verify
```

Also check the installed CLI help and output manually when the installed command is available:

```bash
symphony-trello diagnostics --help
symphony-trello diagnostics --show-private-context --board "Board Name"
symphony-trello diagnostics --board "Board Name"
```

Confirm that `--show-private-context` prints private board and path mappings with a warning, and
that the normal diagnostics command still redacts them.

## Pros and Cons of the Options

### `--show-private-context`

Name the local-only mode after what it reveals: the private context - Trello identifiers, Trello
URLs, and local paths - that the public-safe report tokenizes.

* Good, because it warns that the output is private.
* Good, because it does not imply credentials are printed.
* Good, because it covers current and likely future private diagnostic data.
* Bad, because users need the help text or report warning to know the exact fields shown.

### `--local-identifiers`

Name the flag after where the data stays (local) and what kind of data it is (identifiers).

* Good, because it describes the original lookup need for `board_hash`, `key_hash`, and
  `<path:...>` tokens.
* Bad, because `identifier` does not clearly include Trello board URLs or local paths.
* Bad, because it sounds less sensitive than the output really is.
* Bad, because it is too narrow if future diagnostics tokenizes other private context.

### `--unredacted`

Name the flag as the opposite of redaction, implying the report disables redaction entirely.

* Good, because it is short and easy to recognize as sensitive.
* Bad, because it incorrectly suggests the command may reveal secrets or raw log contents.
* Bad, because diagnostics must still keep credentials and auth/session contents redacted.

### `--only-secrets-redacted`

Name the flag after the remaining protection: credential values stay redacted while every other
private identifier and path is printed.

* Good, because it tries to clarify that credentials stay hidden.
* Bad, because `secret` is ambiguous. Trello board ids, Trello board URLs, and local paths are not
  credentials, but they are still private and unsafe for public issue reports.
* Bad, because it describes an implementation policy instead of the user's task.

### `--show-identifiers-and-paths`

Name the flag after the two main data categories it reveals.

* Good, because it names two major categories of currently shown data.
* Bad, because Trello board URLs do not clearly fit either word for many users.
* Bad, because the name would age poorly if future diagnostics need other private context.

### `--show-local-references`

Name the flag after the local reference values the report would print.

* Good, because it is broad.
* Bad, because `references` is vague and does not clearly communicate privacy risk.
* Bad, because users may not understand that it reveals Trello board identifiers, URLs, and paths.

### `--reveal-hashes`

Name the flag after the reverse mapping it enables from public-safe hash tokens back to private
values.

* Good, because it connects to the visible hashed diagnostics values.
* Bad, because hashes are not actually reversed; the command prints local mappings.
* Bad, because it does not cover path tokens clearly.
* Bad, because `reveal` can imply credentials or fully unredacted diagnostics.

## More Information

This decision covers the CLI flag name and private-context report framing only. It does not relax the
public-safe diagnostics contract. Normal diagnostics output must remain safe to paste into public
issue reports by default.
