---
status: accepted
date: 2026-05-29
decision-makers: [Francois Martin, Codex]
consulted: [SPEC.md, README.md, docs/operations.md, SetupDiagnosticReporter.java, DiagnosticsTokenHasher.java]
informed: [Future maintainers]
---

# Use Local Keyed Diagnostics Tokens

## Context and Problem Statement

Public-safe diagnostics must hide Trello board names, Trello board ids, short links, Trello URLs,
local paths, account names, and other private context. The report still needs stable tokens such as
`board_hash`, `key_hash`, and `<path:...>` so maintainers can tell whether two rows refer to the
same private value.

The first implementation used truncated `SHA-256(value)` tokens. That is simple, but it has two
limits:

* Low-entropy values can be guessed by hashing likely candidates.
* The same private value has the same token across different installations and issue reports.

What token scheme should diagnostics use?

## Decision Drivers

* Keep normal diagnostics safe enough for public issue reports.
* Let maintainers correlate repeated values within and across reports from the same installation.
* Let local users and coding agents map tokens back to private values with `--show-private-context`.
* Avoid adding a cryptography dependency for a small diagnostics feature.
* Do not print credential values, worker log contents, or the diagnostics token key.
* Avoid making diagnostics fail only because a token key cannot be persisted.

## Considered Options

* Use a local random key with `HmacSHA3-256` and truncate the result for display.
* Use a local random key with `HmacSHA256` and truncate the result for display.
* Use unkeyed `SHA3-256(value)` and truncate the result for display.
* Keep unkeyed `SHA-256(value)` and truncate the result for display.
* Use a random per-report key or salt.

## Decision Outcome

Chosen option: "Use a local random key with `HmacSHA3-256` and truncate the result for display",
because the key prevents simple dictionary guessing while keeping tokens stable for one local
installation. Java 25 provides `HmacSHA3-256` through the standard JCE provider, so no new dependency
is needed.

The diagnostics key is stored in the local config directory as `.symphony-trello-diagnostics-key`.
The file contains random bytes encoded as hex. The key file is ignored by Git and is created with
owner-only file permissions on file systems that support POSIX permissions. If the key cannot be read
or created, diagnostics uses an in-memory random key for that run rather than failing. In that
fallback case, tokens may not match a later `--show-private-context` run, but diagnostics still stays
public-safe and available. The report says when this temporary-key fallback is active, without
printing the key file path, the key value, or the original error.

Displayed tokens remain short by design. They are correlation handles, not cryptographic proofs. The
private diagnostics context command exists to map them back locally when needed.

Focused lookup reuses the same tokens. `diagnostics --show-private-context --lookup <token>` accepts
only recognized public diagnostics token shapes, such as a 12-hex value from a `board_hash` or
`key_hash` row, or a `<path:...>` token. It reports missing or ambiguous tokens explicitly instead
of searching arbitrary private strings or choosing one collision silently.

Setup and lifecycle output may include those tokens when it hides local workflow, log, managed
PID/state, or file-backed secret paths. The same persisted diagnostics key makes those tokens
resolvable by a later focused lookup on the same installation without printing credential values.

Diagnostics also reads installer context such as `repo_url`, `ref`, and `source_commit` so public
reports do not repeat private install-source identifiers from recent logs or failure text. Those
values are not all equally distinctive:

* Always tokenize `repo_url` because even short repository URLs can identify private infrastructure.
* Tokenize `ref` only when it is not a generic public ref word such as `HEAD`, `main`, `master`, or
  `trunk`. Generic ref words appear in framework thread names and ordinary prose, so using them as
  global redaction terms would make reports harder to read without protecting private context.
* Tokenize `source_commit` only when it is shaped like a Git commit id, currently 7 to 64 hex
  characters. This covers short and full SHA-1/SHA-256 commit identifiers without turning malformed
  or unrelated text into a broad redaction term.

Do not use a generic string-length threshold for installer-context redaction terms. Redaction terms
must be tied to the meaning of the context key so the public report does not hide unrelated words
only because they are long enough.

### Consequences

* Good, because likely Trello board names, short links, and paths cannot be checked by hashing a
  candidate list without the local key.
* Good, because reports from the same installation keep stable tokens while the key exists.
* Good, because the implementation uses standard Java 25 APIs.
* Good, because the local key is owner-only on POSIX file systems.
* Good, because private installer refs and commit ids can be traced with the same local token
  mechanism as other private diagnostics context.
* Good, because diagnostics remains best-effort even when the key file cannot be persisted.
* Bad, because replacing the local key changes all future tokens for that installation.
* Bad, because very short displayed tokens can theoretically collide, so they are only for
  diagnostics correlation.
* Bad, because generic branch names such as `main` remain visible in public reports when they are
  not attached to a private repository URL or another distinctive value.
* Bad, because an ephemeral fallback key means a report generated while the config directory is not
  writable may not be mappable by a later command.

### Confirmation

Run these checks before changing this behavior:

```bash
./mvnw -q -Dtest=DiagnosticsTokenHasherTest,SetupDiagnosticReporterTest,TrelloBoardSetupMainTest test
corepack pnpm dlx markdownlint-cli2 README.md SPEC.md docs/operations.md AGENTS.md \
  docs/adr/0031-keyed-diagnostics-tokens.md
./mvnw -q spotless:check verify
```

When the installed command is available, also check:

```bash
symphony-trello diagnostics --board "Board Name"
symphony-trello diagnostics --show-private-context --board "Board Name"
```

Confirm that normal diagnostics shows only tokens, private context maps those tokens to local values,
and neither output prints the diagnostics key.

## Pros and Cons of the Options

### Local Random Key With `HmacSHA3-256`

Generate a random key once per install, store it in the local config directory, and tokenize
private values with `HmacSHA3-256(key, value)`, so tokens stay stable per install but cannot be
verified or brute-forced from value guesses without the local key.

* Good, because it prevents simple dictionary guessing without adding dependencies.
* Good, because SHA-3 uses a sponge construction instead of SHA-2's Merkle-Damgard construction and
  is not affected by length-extension properties. HMAC already protects `HmacSHA256` from that
  practical issue, so this is a conservative margin rather than a required security fix.
* Good, because Java 25 provides it in the standard runtime.
* Bad, because older Java versions would not be enough, but this project already requires Java 25.

### Local Random Key With `HmacSHA256`

Use the same locally stored random key, but compute tokens with the SHA-2-based `HmacSHA256`
algorithm instead of the SHA-3 family.

* Good, because it also prevents simple dictionary guessing.
* Good, because it is widely available.
* Bad, because `HmacSHA3-256` is available in Java 25 and gives the project a newer standardized
  hash family with a different construction, without adding a dependency.

### Unkeyed `SHA3-256`

Tokenize private values with plain truncated `SHA3-256(value)` and no key.

* Good, because it is a modern hash and available in Java 25.
* Bad, because low-entropy values can still be guessed from candidate lists.
* Bad, because the same private value has the same token across installations.

### Keep Unkeyed `SHA-256`

Keep the original truncated `SHA-256(value)` tokens unchanged.

* Good, because it is simple and widely available.
* Bad, because it has the same privacy limits as unkeyed `SHA3-256`.
* Bad, because users may question why a privacy feature uses a plain unsalted hash.

### Random Per-Report Key Or Salt

Generate a fresh random key or salt for every report run, making tokens consistent within one
report but different across reports.

* Good, because tokens from different reports cannot be linked.
* Bad, because a later `--show-private-context` command cannot map old tokens unless the report key
  is also stored and selected.
* Bad, because report-key management adds complexity that is not needed for the current debugging
  workflow.

## More Information

This decision covers diagnostics correlation tokens only. It is not a password-storage scheme and
does not change the rule that credential values, Codex auth data, GitHub tokens, and worker log
contents must not be printed.

[GitHub issue #112](https://github.com/martin-francois/symphony-trello/issues/112) tracks a
possible local-only recovery path for rare reports generated with a temporary token key.
