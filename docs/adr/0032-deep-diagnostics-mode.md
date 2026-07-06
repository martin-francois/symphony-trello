---
status: accepted
date: 2026-05-29
decision-makers: [Francois Martin, Codex]
consulted: [SPEC.md, README.md, docs/operations.md, AGENTS.md, SetupDiagnosticReporter.java]
informed: [Future maintainers]
---

# Use `--deep` For Active Diagnostics Probes

## Context and Problem Statement

The default `symphony-trello diagnostics` report is safe for public issue reports. It avoids Trello,
GitHub, and Codex network or auth-status calls by default.

Some troubleshooting needs deeper checks. One example is verifying whether the local Codex CLI or
GitHub CLI can report an authenticated state. These checks can help debug setup and worker failures,
but they may run external commands that inspect auth state.

What flag should enable those deeper checks?

## Decision Drivers

* Keep default diagnostics public-safe and low-risk.
* Give users and coding agents a short flag for deeper troubleshooting.
* Keep deeper diagnostics public-safe unless combined with `--show-private-context`.
* Leave room for future public-safe probes beyond auth checks.
* Avoid compatibility aliases for unreleased temporary flag names.

## Considered Options

* `--deep`
* `--probe-auth`
* `--show-private-context`
* Private-context variants such as `--show-identifiers-and-paths`, `--show-local-references`,
  `--unhashed`, `--unredacted`, `--only-secrets-redacted`, and `--reveal-hashes`
* Enable auth probes by default

## Decision Outcome

Chosen option: `--deep`, because it describes the troubleshooting mode instead of one current
implementation detail. Today it runs Codex and GitHub auth-status checks. Later it may include other
public-safe probes.

`--probe-auth` is not kept as an alias. That temporary flag was never part of the supported command
contract, so there is no compatibility contract for it.

`--show-private-context` remains separate. `--deep` means "collect more public-safe diagnostics".
`--show-private-context` means "print private local identifiers, URLs, and paths for local
debugging". Combining both flags is allowed when local debugging needs both behaviors.

### Consequences

* Good, because the flag name still fits if deep diagnostics grows.
* Good, because coding agents can use one flag when the default report omits useful details.
* Good, because private context remains an explicit separate decision.
* Bad, because users who saw the temporary `--probe-auth` name must switch to `--deep`.

### Confirmation

Run these checks before changing this behavior:

```bash
./mvnw -q -Dtest=SetupDiagnosticReporterTest,TrelloBoardSetupMainTest test
corepack pnpm dlx markdownlint-cli2 README.md SPEC.md docs/operations.md AGENTS.md \
  .codex/skills/debug/SKILL.md docs/adr/0032-deep-diagnostics-mode.md
./mvnw -q spotless:check verify
```

When the installed command is available, also check:

```bash
symphony-trello diagnostics
symphony-trello diagnostics --deep
symphony-trello diagnostics --show-private-context
```

Confirm that `--deep` enables deeper public-safe probes, normal diagnostics does not run auth-status
commands, and `--show-private-context` is still the only flag that prints private local context.

## Pros and Cons of the Options

### `--deep`

Add one flag that enables all deeper public-safe checks at once; currently that means running the
Codex and GitHub auth-status probes in addition to the default report.

* Good, because it is short and understandable.
* Good, because it can cover future public-safe probes.
* Bad, because users may need help understanding exactly what extra checks run.

### `--probe-auth`

Add a narrower flag named after the specific auth-status probes it would enable.

* Good, because it names the current extra behavior.
* Bad, because it is too narrow if deeper diagnostics later includes non-auth checks.

### `--show-private-context`

Reuse the existing private-context flag so one flag both reveals private values and runs the deeper
checks.

* Good, because it already exists for local debugging.
* Bad, because it prints private local identifiers and paths, while auth probes should remain
  public-safe by themselves.
* Bad, because it would overload a privacy boundary flag with active probe behavior. A maintainer or
  coding agent may need public-safe auth status without printing Trello board names, Trello ids,
  Trello URLs, or local paths.

### Private-Context Name Variants

Rename the private-context mode and fold the deeper checks into that renamed mode.

* Good, because names such as `--show-identifiers-and-paths` describe some current private-context
  fields.
* Bad, because the extra auth checks do not reveal those private fields by themselves.
* Bad, because names tied to identifiers, paths, hashes, or redaction would not fit future deep
  diagnostics that may collect other public-safe status checks.

### Enable Auth Probes By Default

Run the Codex and GitHub auth-status probes in every diagnostics run without any flag.

* Good, because it could catch auth problems without another flag.
* Good, because the report could still print only `ok`, `not-ok`, or `unknown`.
* Bad, because default diagnostics would run auth-status commands instead of staying passive.
* Bad, because command failures could mean no auth, network trouble, keychain trouble, or tool
  trouble.
* Bad, because users may paste diagnostics into public issues. They should not be surprised that a
  default report ran Codex or GitHub auth commands, even when the output is reduced to status words.

## More Information

This decision covers the command flag for deeper diagnostics. It does not change the separate
private-context policy or the keyed diagnostics token policy.
