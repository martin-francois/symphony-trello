---
status: accepted
date: 2026-06-06
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #340](https://github.com/martin-francois/symphony-trello/issues/340)"
  - "[GitHub issue #344](https://github.com/martin-francois/symphony-trello/issues/344)"
  - "[GitHub issue #346](https://github.com/martin-francois/symphony-trello/issues/346)"
  - "[GitHub PR #350](https://github.com/martin-francois/symphony-trello/pull/350)"
  - "[GitHub PR #351](https://github.com/martin-francois/symphony-trello/pull/351)"
  - "[GitHub PR #352](https://github.com/martin-francois/symphony-trello/pull/352)"
  - "[GitHub PR #353](https://github.com/martin-francois/symphony-trello/pull/353)"
  - "[BetterLeaks](https://github.com/betterleaks/betterleaks)"
  - "[Kingfisher](https://github.com/mongodb/kingfisher)"
  - "[Gitleaks](https://github.com/gitleaks/gitleaks)"
  - "[TruffleHog](https://github.com/trufflesecurity/trufflehog)"
  - "[ADR 0046](0046-semgrep-cross-language-guardrails.md)"
informed: [Future maintainers, Contributors]
---

# Use BetterLeaks for Private Context Scanning

## Context and Problem Statement

Agents and maintainers handle diagnostics, live reproduction notes, local setup output, commits,
fixtures, and pull request branches that can contain private Trello context. That context is not
always a credential. It can be a Trello board short link, a Trello card URL, a 24-character Trello
id, a private host path, or a token-shaped value copied from local output.

GitHub Secret Scanning with repository custom patterns is configured as the primary protection for
GitHub-hosted issue, pull request, review, and comment text. The repository still needs a local and
CI guardrail for content before it is committed, pushed for review, or manually posted from copied
diagnostic output.

The first implementation direction used repository-owned shell logic for this scanning. The user
rejected that direction and asked whether the project should use a maintained scanner instead. The
follow-up comparison included BetterLeaks, Kingfisher, Gitleaks, TruffleHog, similar secret
scanners, and Semgrep.

Which scanner should protect repository content, commit messages, pull request ranges, and manually
scanned text from private operational context while staying easy for agents to run locally and in
CI?

## Decision Drivers

* Prefer a maintained scanner over hand-rolled secret-detection logic.
* Support local file, stdin, worktree, and commit-range checks.
* Support repository-specific rules for private context that is not always a credential.
* Complement GitHub Secret Scanning instead of replacing hosted-text alerting and push protection.
* Keep scan output redacted so a failed check does not leak the matched value again.
* Run deterministically in CI and locally without a hosted account.
* Keep the tool version pinned and Renovate-managed.
* Keep Semgrep focused on cross-language policy rules rather than turning it into the primary
  secret scanner.
* Avoid maintaining a GitHub CLI grammar parser for generated, interactive, file-backed, API, and
  shorthand flag forms.
* Avoid live credential validation in the normal pre-commit or pull-request path unless the project
  later accepts the network and privacy tradeoff.

## Considered Options

* Use BetterLeaks with repository-specific private-context rules.
* Use Kingfisher as the primary scanner.
* Use Gitleaks as the primary scanner.
* Use TruffleHog as the primary scanner.
* Use Semgrep custom rules as the primary scanner.
* Build a generic `gh` safety wrapper and GitHub event-content scanner.
* Keep a hand-rolled repository scanner.

## Decision Outcome

Chosen option: "Use BetterLeaks with repository-specific private-context rules", because it provides
a maintained secret-scanning engine, custom TOML rules, file and stdin scanning, Git-oriented
workflows, redacted JSON output that can be post-processed by the wrapper, and a small Docker image
integration that works well for agents and CI.

The project pins BetterLeaks through `scripts/betterleaks-docker.sh` and tracks the image with
Renovate. The accepted private-context rules live in `config/betterleaks/private-context.toml`.
`scripts/check-private-context` is the local entry point for:

* stdin text before manually posting copied diagnostic output;
* individual files;
* worktree scans; and
* commit-range scans.

GitHub Secret Scanning remains the primary protection for GitHub-hosted issue, pull request,
review, and comment text. The repository deliberately does not maintain a generic `gh` safety
wrapper or a GitHub event-content scanner. That approach was considered and rejected after review
found that GitHub CLI parsing includes generated bodies, editor and web modes, API payloads,
short-flag clusters, files, stdin, and future behavior that this repository should not mirror.
Contributors and agents should scan exact text manually when they have copied diagnostic content to
post:

```bash
printf '%s\n' 'text to post' | scripts/check-private-context --stdin --label github-body
```

Pull request guardrails do not execute scanner code from the pull request checkout. The
`private-context` workflow checks out the pull request source separately from the trusted base-branch
scanner checkout, then runs the trusted `scripts/check-private-context` and trusted BetterLeaks
config against the pull request worktree and commit range. This keeps a pull request from weakening
the guardrail scripts or private-context rules that are judging that same pull request. The initial
pull request that introduces these files cannot run scanner code from a base branch that does not
contain the scanner yet; after it merges, later pull requests must use the trusted scanner checkout.

The project deliberately does not use BetterLeaks native Git patch output as the final report for
commit-range patch checks. Manual validation with dummy private-shaped values found that native Git
metadata can include the raw commit message. The wrapper scans commit messages and added patch lines
through stdin instead, so findings stay tied to the commit range while the report keeps matched
values redacted.

Kingfisher is not chosen for this narrow path. It is a strong scanner for broad platform scans and
live validation, but those strengths are not required for the default local guardrail. Adopting it
for this use case would add a newer tool and live-validation-oriented behavior before the project
has accepted that broader security program.

TruffleHog is not chosen for the same narrow path. It has broad source coverage and credential
verification, but those capabilities are heavier than this repository needs for local agent checks
and GitHub text writes.

Gitleaks is not chosen because BetterLeaks is the successor-style option with compatible concepts
and newer filtering and validation support.

Semgrep remains accepted for cross-language policy guardrails under
[ADR 0046](0046-semgrep-cross-language-guardrails.md). It is not the primary private-context
scanner because this problem is closer to secret scanning than static code pattern enforcement.

The hand-rolled scanner is rejected. It would put parsing, redaction, rule tuning, and future
scanner behavior on this repository instead of using a maintained engine.

### Consequences

* Good, because private-context detection depends on a maintained scanner instead of custom shell
  matching.
* Good, because repository-specific Trello and host-path rules remain reviewable in the repository.
* Good, because agents can run the same checks locally and in CI.
* Good, because the repository complements GitHub Secret Scanning without duplicating hosted-text
  alerting.
* Good, because pull request scans run trusted scanner code and config against untrusted pull
  request content.
* Good, because the BetterLeaks image pin is visible to Renovate.
* Good, because the rejected hand-rolled and generic `gh` wrapper directions are recorded for future
  maintainers.
* Bad, because CI now depends on another external Docker image.
* Bad, because BetterLeaks behavior can change when the pinned version is updated.
* Bad, because the wrapper still owns glue code for local modes and redacted reporting.
* Bad, because manually posted GitHub text relies on contributors scanning exact copied text before
  posting or on GitHub Secret Scanning after creation.
* Bad, because broad platform scanning and live validation remain out of scope for this PR.

### Confirmation

Run the local private-context checks:

```bash
scripts/check-private-context --worktree
scripts/check-private-context --git-range origin/main..HEAD
```

Run the full project validation:

```bash
./mvnw -q spotless:check verify
```

Confirm the pull request checks include passing `private-context`, `semgrep`, `lint`, `test`,
`windows-powershell`, `renovate`, and `commitlint` jobs. GitHub-hosted text is protected by the
repository's configured GitHub Secret Scanning custom patterns, not by repository-owned event
scanner code.

For pull requests, confirm the `private-context` job runs `scanner/scripts/check-private-context`
from the trusted checkout while its working directory is the pull request source checkout.

When validating private-context rules, use dummy token-shaped values and synthetic Trello-shaped
fixtures. Do not test with a real Trello token, real GitHub token, real OpenAI token, live Trello
board id, live Trello short link, or private host path copied from a real machine.

## Pros and Cons of the Options

### Use BetterLeaks With Repository-Specific Private-Context Rules

Use BetterLeaks as the scanning engine, add project-owned TOML rules, and keep shell wrappers only
for local/CI orchestration and redacted output.

* Good, because it replaces custom detection logic with a maintained scanner.
* Good, because it supports file, directory, Git, and stdin-oriented workflows.
* Good, because custom rules can describe Trello-shaped private context.
* Good, because the Docker invocation is simple enough for CI and local agent runs.
* Bad, because the project still owns wrapper logic around the scanner.
* Bad, because a BetterLeaks upgrade can change rule behavior or report fields.

### Use Kingfisher as the Primary Scanner

Adopt Kingfisher for local and CI scans.

* Good, because Kingfisher has broad rule coverage and live-validation-oriented features.
* Good, because it is a serious candidate for a later broader security scanning program.
* Bad, because the normal local and CI guardrail does not need live validation.
* Bad, because adopting it here would choose a newer and broader tool for a narrow local use case.
* Bad, because this PR still needs repository-specific private-context rules either way.

### Use Gitleaks as the Primary Scanner

Adopt Gitleaks with custom rules and wrappers.

* Good, because it is established and widely used for repository secret scanning.
* Good, because its concepts map closely to the needed checks.
* Bad, because BetterLeaks is the more current successor-style option with newer filtering and
  validation features.
* Bad, because choosing Gitleaks would not avoid the need for local wrappers and custom rules.

### Use TruffleHog as the Primary Scanner

Adopt TruffleHog for local and CI scans.

* Good, because TruffleHog has broad source support and credential verification.
* Good, because it is useful when confirmed active secrets and blast-radius analysis matter.
* Bad, because the default local guardrail should avoid live credential validation.
* Bad, because the broader scanner surface is heavier than the specific private-context use case.

### Build a Generic `gh` Safety Wrapper and GitHub Event-Content Scanner

Wrap supported `gh` text writes and scan GitHub event payloads in repository-owned workflows.

* Good, because it can catch some copied text before `gh` sends it.
* Good, because it can scan hosted text through a repository-controlled path.
* Bad, because GitHub Secret Scanning with custom patterns already owns hosted issue, pull request,
  review, and comment text.
* Bad, because a dependable wrapper must model GitHub CLI command grammar, API payloads,
  generated-body modes, editor and web modes, file and stdin inputs, short-flag clusters, and future
  CLI behavior.
* Bad, because partial parsing can create a false sense of safety by scanning one clean field while
  forwarding another text-bearing payload uninspected.
* Bad, because this repository only needs a small local and CI complement to managed GitHub Secret
  Scanning.

### Use Semgrep Custom Rules as the Primary Scanner

Write Semgrep rules for Trello ids, Trello URLs, tokens, and host paths.

* Good, because Semgrep is already present for cross-language policy checks.
* Good, because custom rules are reviewable in the repository.
* Bad, because this turns a static-analysis policy tool into the primary secret scanner.
* Bad, because secret-scanner behavior such as redaction, stdin handling, and built-in token
  coverage would need more project-owned glue.
* Bad, because it overlaps with Semgrep's existing role from ADR 0046.

### Keep a Hand-Rolled Repository Scanner

Use project-owned regular expressions and shell logic without a maintained scanner engine.

* Good, because the implementation could be small for today's patterns.
* Good, because it would avoid another external tool.
* Bad, because the project would own scanner correctness, redaction behavior, and future tuning.
* Bad, because the approach was rejected after comparing maintained scanner options.
* Bad, because it would make future contributors rediscover why a premade scanner was not used.

## More Information

This ADR records the decision for the private-context guardrail introduced by
[GitHub PR #353](https://github.com/martin-francois/symphony-trello/pull/353). It does not
make BetterLeaks the only security scanner for all future use cases, and it does not replace GitHub
Secret Scanning for GitHub-hosted text. If the project later needs organization-wide scanning, live
credential validation, provider permission mapping, automatic revocation workflows, or a formally
maintained command-line posting gateway, reassess Kingfisher, TruffleHog, and GitHub-native
features for that broader program.

The rules intentionally allow clearly synthetic Trello-shaped examples such as `SYNTH001`,
`SYNTH002`, and `000000000000000000000001`. Tests and documentation should use those values instead
of real Trello ids or short links.
