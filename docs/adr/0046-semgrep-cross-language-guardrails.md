---
status: accepted
date: 2026-06-01
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #82](https://github.com/martin-francois/symphony-trello/issues/82)"
  - "[GitHub issue #87](https://github.com/martin-francois/symphony-trello/issues/87)"
  - "[GitHub issue #130](https://github.com/martin-francois/symphony-trello/issues/130)"
  - "[Semgrep releases](https://github.com/semgrep/semgrep/releases)"
informed: [Future maintainers, Contributors]
---

# Add Semgrep for Cross-Language Guardrails

## Context and Problem Statement

The project now enforces Java-focused analyzers through the normal validation command. PMD,
SpotBugs, FindSecBugs, Error Prone, Picnic Error Prone Support, ArchUnit, tests, and coverage cover
the main Java feedback loop.

Some repository rules are cross-language or policy-oriented. They apply to YAML, Markdown, shell
scripts, and helper files as much as Java. These rules are awkward to express with the Java
analyzers and too important to leave only in `AGENTS.md`.

How should the project add those cross-language checks without adopting broad Semgrep registry packs
or a hosted account requirement?

## Decision Drivers

* Keep Java analyzers as the primary `./mvnw -q spotless:check verify` feedback loop.
* Add only focused repository-specific Semgrep rules.
* Avoid broad registry scans until their baseline is measured and triaged.
* Keep private-repository runs local and disable Semgrep metrics.
* Make accepted rules blocking in CI once the baseline is clean.
* Pin the Semgrep runtime version and make the pin Renovate-managed.

## Considered Options

* Add a focused Semgrep workflow with local repository rules.
* Add Semgrep registry packs as a blocking gate.
* Add only a documented local Semgrep command.
* Defer Semgrep.

## Decision Outcome

Chosen option: "Add a focused Semgrep workflow with local repository rules", because the first rule
set is clean, repository-specific, and complements the existing analyzers.

The project adds `config/semgrep/symphony-policy.yml` and runs it in a separate `Semgrep` GitHub
Actions workflow. The workflow calls `./scripts/semgrep-docker.sh`, which uses a pinned Semgrep
Docker image, runs with `--metrics=off`, and does not require a Semgrep account.

The first accepted rules check:

* GitHub Actions `uses:` entries must be pinned to full commit SHAs.
* Maven wrapper commands must not be prefixed with ad hoc `JAVA_HOME` or `PATH` assignments.
* README must not contain contributor validation commands such as `./mvnw` or `spotless:check`.
* Reusable helper scripts must not use Python, Perl, Ruby, Node, Deno, or Bun shebangs without a
  documented project exception.

The workflow is separate from the heavy Java CI jobs so Semgrep failures are easy to diagnose.
`./mvnw -q spotless:check verify` remains the primary local Java gate. Semgrep is the first
cross-language policy gate; future rules must still be measured and triaged before becoming
blocking.

### Consequences

* Good, because cross-language policy regressions now fail a deterministic check.
* Good, because accepted rules are narrow and repository-specific.
* Good, because Semgrep runs without a hosted account and with metrics disabled.
* Good, because the Semgrep version pin is visible to Renovate.
* Bad, because the default local Maven command does not run Semgrep.
* Bad, because CI now pulls and runs an additional Docker image.
* Bad, because future Semgrep upgrades can change rule parsing or generic matching behavior.

### Confirmation

Run:

```bash
./scripts/semgrep-docker.sh
```

Also run:

```bash
./mvnw -q spotless:check verify
```

## Pros and Cons of the Options

### Add a Focused Semgrep Workflow With Local Repository Rules

Store local custom rules under `config/semgrep` and run them in a dedicated CI workflow.

* Good, because the rules are reviewable in the repository.
* Good, because the workflow can be blocking once the baseline is clean.
* Good, because no Semgrep account is required.
* Bad, because contributors need Docker or a local Semgrep install to run the exact same check.

### Add Semgrep Registry Packs as a Blocking Gate

Run a broad Semgrep registry configuration.

* Good, because it would provide wider coverage quickly.
* Bad, because it duplicates specialized Java analyzers without first measuring signal.
* Bad, because a broad baseline could hide justified findings behind volume.

### Add Only a Documented Local Semgrep Command

Document the command but do not add CI enforcement.

* Good, because it would add no CI cost.
* Bad, because accepted cross-language policy checks would stay optional.

### Defer Semgrep

Keep all cross-language policy rules in `AGENTS.md` and human review.

* Good, because no new tool is added.
* Bad, because mechanical repository policy regressions would still depend on reviewer memory.

## More Information

Future Semgrep rules should start small. A rule is not noisy only because it finds many issues. A
rule is noisy when representative findings are false positives or the code is cleaner without the
suggested change. Handle findings by fixing them first, then tuning the rule, then using narrow
rule-specific `nosemgrep` suppressions with a reason.
