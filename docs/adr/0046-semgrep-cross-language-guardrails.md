---
status: accepted
date: 2026-06-01
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #82](https://github.com/martin-francois/symphony-trello/issues/82)"
  - "[GitHub issue #87](https://github.com/martin-francois/symphony-trello/issues/87)"
  - "[GitHub issue #130](https://github.com/martin-francois/symphony-trello/issues/130)"
  - "[GitHub issue #149](https://github.com/martin-francois/symphony-trello/issues/149)"
  - "[GitHub issue #151](https://github.com/martin-francois/symphony-trello/issues/151)"
  - "[Semgrep releases](https://github.com/semgrep/semgrep/releases)"
  - "[Semgrep Java support](https://semgrep.dev/docs/languages/java)"
  - "[Semgrep registry rules](https://semgrep.dev/docs/running-rules)"
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

How should the project add those cross-language checks and useful Semgrep registry coverage without
adopting redundant or too-broad packs, or a hosted account requirement?

## Decision Drivers

* Keep Java analyzers as the primary `./mvnw -q spotless:check verify` feedback loop.
* Add focused repository-specific Semgrep rules.
* Add selected registry packs when they provide useful preventive coverage and the baseline is clean
  or can be made clean with narrow rule exclusions.
* Reject registry packs that only duplicate stronger local analyzers, are irrelevant to this
  repository, or are too broad for a deterministic blocking gate.
* Keep local and CI runs deterministic and disable Semgrep metrics.
* Make accepted rules blocking in CI once the baseline is clean.
* Pin the Semgrep runtime version and make the pin Renovate-managed.

## Considered Options

* Add a focused Semgrep workflow with local repository rules and selected registry packs.
* Add broad Semgrep registry packs as a blocking gate.
* Add Semgrep registry packs as report-only documentation.
* Add only a documented local Semgrep command.
* Defer Semgrep.

## Decision Outcome

Chosen option: "Add a focused Semgrep workflow with local repository rules and selected registry
packs", because the accepted set is clean, provides useful preventive coverage, and complements the
existing analyzers.

The project adds `config/semgrep/symphony-policy.yml` and selected Semgrep registry packs in a
separate `Semgrep` GitHub Actions workflow. The workflow calls `./scripts/semgrep-docker.sh`, which
uses a pinned Semgrep Docker image, runs with `--metrics=off`, and does not require a Semgrep
account.

The accepted local rules check:

* GitHub Actions `uses:` entries must be pinned to full commit SHAs.
* Maven wrapper commands must not be prefixed with ad hoc `JAVA_HOME` or `PATH` assignments.
* README must not contain contributor validation commands such as `./mvnw` or `spotless:check`.
* Reusable helper scripts must not use Python, Perl, Ruby, Node, Deno, or Bun shebangs without a
  documented project exception.

The accepted registry packs are:

* `p/owasp-top-ten`, for recognized web application security risks. This pack fully covers the
  measured `p/java` and `p/cwe-top-25` rule ids, so those narrower packs are not configured
  separately.
* `p/security-audit`, for additional security review patterns that are not complete overlap with the
  accepted OWASP pack.
* `p/ci`, for Semgrep's CI-oriented low-false-positive rules. This pack fully covers the measured
  `p/comment` rule ids, so `p/comment` is not configured separately.
* `p/github-actions`, for GitHub Actions workflow security rules beyond this project's local action
  pinning rule.
* `p/secrets` and `p/gitleaks`, for two non-identical secret-detection rule sets. Both had clean
  baselines and add preventive coverage for accidentally committed tokens or credentials.
* `p/supply-chain`, for the single supply-chain/Trojan Source rule.

The known local loopback socket false-positive rule
`java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket` is excluded. The resulting
selected registry-pack baseline is clean.

If the same finding family starts needing recurring suppressions in Semgrep and another enforced
tool, reconsider the rule or code boundary before adding more suppressions. A repeated cross-tool
suppression can mean the rule does not fit this repository, a narrower custom rule should replace
the broad rule, or the code should expose a clearer reviewed boundary.

The workflow is separate from the heavy Java CI jobs so Semgrep failures are easy to diagnose.
`./mvnw -q spotless:check verify` remains the primary local Java gate. Semgrep is the first
cross-language policy gate; future rules must still be measured and triaged before becoming
blocking.

`--config auto` was evaluated after the first local rules landed. Semgrep 1.164.0 rejects that
mode when metrics are disabled:

```text
Cannot create auto config when metrics are off. Please allow metrics or run with a specific config.
```

That makes `--config auto` unsuitable for this metrics-disabled workflow. The project must not
require Semgrep metrics or hosted Semgrep login for local checks.

The explicit registry pack `p/default` was also evaluated with:

```bash
semgrep scan --config p/default --metrics=off --json .
```

Using `semgrep/semgrep:1.164.0`, it selected 1,059 community rules, ran 354 rules on 280
tracked files, and reported 20 findings:

* 13 `java.lang.security.audit.command-injection-process-builder` findings.
* 6 `java.lang.security.audit.crypto.unencrypted-socket` findings.
* 1 `bash.lang.security.ifs-tampering` finding.

The ProcessBuilder findings are audit false positives for this codebase. The production uses are
argument-vector based or intentionally run configured hook scripts through `bash -c`. The test
findings launch local Java, shell, and installer fixtures.

The unencrypted-socket findings are false positives for local loopback health probes and test
servers. Those sockets do not carry remote or public network traffic.

The `IFS` finding points at a `local IFS=/` used only to join already-normalized path segments
inside `install.sh`. It is not a global `IFS` change.

The same scan also emitted shell partial-parse warnings for `install.sh` and `mvnw`, which further
confirms that `p/default` is not clean enough to promote as a blocking gate without a narrower local
rule selection.

No `p/default` finding was accepted as a code change or suppression. The project does not use
`p/default`; it uses the selected registry pack set below plus local custom Semgrep rules instead.

[GitHub issue #151](https://github.com/martin-francois/symphony-trello/issues/151) evaluated
remaining relevant Semgrep registry packs on 2026-06-02. The discovery method was:

* inspect the repository's tracked file types and existing tool coverage;
* use Semgrep's Java documentation, which lists `p/default`, `p/java`, and `p/findsecbugs` as the
  Java rulesets;
* query the Semgrep registry rulesets API and filter actual public rulesets by this repository's
  languages, file types, and concerns;
* run relevant registry pack ids with `semgrep/semgrep:1.164.0`, `--metrics=off`, and the same
  tracked-file behavior used by local checks;
* classify all reported findings before deciding whether a pack should become blocking.

The sweep included actual Java, FindSecBugs-style, security, CI, GitHub Actions, JavaScript, Node,
secret-scanning, container/IaC, and Semgrep-rule packs. It excluded ecosystems with no tracked source
here, such as Python, Go, Ruby, PHP, C#, C/C++, Kotlin/Android, Scala, Swift, and mobile application
packs.

The measured registry-pack outcomes were:

| Pack | Rules run | Files scanned | Findings | Decision |
| --- | ---: | ---: | ---: | --- |
| `p/java` | 60 | 165 | 6 | Covered by adopted `p/owasp-top-ten`, so it is not configured separately. |
| `p/findsecbugs` | 141 Java rules | 165 | 36 | Reject. Findings duplicate better native SpotBugs/FindSecBugs coverage or are false positives. |
| `p/security-audit` | 225 | 280 | 6 | Adopt with the same loopback socket rule exclusion. It is not complete overlap with accepted OWASP coverage. |
| `p/owasp-top-ten` | 544 | 280 | 6 | Adopt with the same loopback socket rule exclusion. It covers useful Java, YAML, Bash, JSON, and generic security guardrails. |
| `p/ci` | 147 | 280 | 6 | Adopt with the same loopback socket rule exclusion. It is designed for CI use and is not complete overlap with accepted OWASP/security coverage. |
| `p/cwe-top-25` | 215 | 280 | 0 | Covered by adopted `p/owasp-top-ten`, so it is not configured separately. |
| `p/secrets` | 51 | 281 | 0 | Adopt. A clean baseline still adds useful preventive secret-scanning coverage. |
| `p/gitleaks` | 174 multilang rules | 281 | 0 | Adopt. It is not complete overlap with `p/secrets`. |
| `p/github-actions` | 5 | 9 | 0 | Adopt. It covers GitHub Actions security rules beyond local action pinning. |
| `p/javascript` | 68 JavaScript rules | 1 | 0 | Do not adopt. Only `commitlint.config.cjs` is scanned. |
| `p/nodejs` | 36 JavaScript rules | 1 | 0 | Do not adopt. Only `commitlint.config.cjs` is scanned. |
| `p/comment` | 127 | 280 | 0 | Covered by adopted `p/ci`, so it is not configured separately. |
| `p/supply-chain` | 1 | 185 | 0 | Adopt. The rule is small, relevant, and clean. |
| `p/dockerfile` | 7 | 9 | 0 | Do not adopt. The repository has no Dockerfile baseline to protect. |
| `p/docker-compose` | 6 YAML rules | 9 | 0 | Do not adopt. The repository has no Docker Compose baseline to protect. |
| `p/kubernetes` | 11 YAML rules | 9 | 0 | Do not adopt. The repository has no Kubernetes manifests. |
| `p/terraform` | 63 | 0 | 0 | Do not adopt. The repository has no Terraform files. |
| `p/semgrep-rule-lints` | 6 YAML rules | 9 | 0 | Do not adopt. It targets Semgrep rule authoring, not product code. |

The following plausible pack names were also tried and are not valid Semgrep registry configs in
Semgrep 1.164.0: `p/bash`, `p/yaml`, `p/maven`, `p/xml`, `p/json`, `p/generic`,
`p/powershell`, and `p/generic-secrets`. Each failed with Semgrep configuration errors instead of
repository findings.

The `p/findsecbugs` findings break down as:

* `COMMAND_INJECTION`: the production finding is `HookRunner`, where running configured hook
  scripts is the product boundary. Native SpotBugs already has a precise suppression for this
  reviewed boundary. Test findings launch installer fixtures.
* `CUSTOM_INJECTION`: false positives on prompt and CLI tutorial text assembly.
* `HARD_CODE_KEY`: false positives on constant names, prompt text, and test fixture strings that
  contain words such as "key" or "private" but are not secrets.
* `IMPROPER_UNICODE`: false positive on `StateNames.normalize`, which normalizes user-visible
  Trello list names with `NFKC` and `Locale.ROOT`.
* `UNENCRYPTED_SOCKET` and `URLCONNECTION_SSRF_FD`: false positives on local loopback health probes
  and test servers. Native SpotBugs already has a precise suppression for the production loopback
  socket check.

No new Semgrep registry finding was accepted as a code change. The project adopts the selected pack
set with one known false-positive rule exclusion and keeps repository-specific policy rules in
`config/semgrep`.

### Consequences

* Good, because cross-language policy regressions now fail a deterministic check.
* Good, because accepted local rules are narrow and repository-specific.
* Good, because selected registry packs prevent future regressions even when the current baseline is
  clean after the loopback socket exclusion.
* Good, because Semgrep runs without a hosted account and with metrics disabled.
* Good, because the Semgrep version pin is visible to Renovate.
* Good, because broad and overlapping registry packs were measured before being rejected.
* Bad, because the default local Maven command does not run Semgrep.
* Bad, because CI now pulls and runs an additional Docker image.
* Bad, because selected registry packs can change outside this repository when Semgrep updates the
  rulesets.
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

### Add a Focused Semgrep Workflow With Local Repository Rules and Selected Registry Packs

Store local custom rules under `config/semgrep`, add selected registry packs, and run them in a
dedicated CI workflow.

* Good, because the rules are reviewable in the repository.
* Good, because selected registry packs add preventive coverage for Java, GitHub Actions, secrets,
  supply-chain, and common web application security risks.
* Good, because the workflow can be blocking once the baseline is clean.
* Good, because no Semgrep account is required.
* Bad, because contributors need Docker or a local Semgrep install to run the exact same check.
* Bad, because registry pack content is maintained outside this repository.

### Add Broad Semgrep Registry Packs as a Blocking Gate

Run broad Semgrep registry configurations such as `p/default`, `p/ci`, or `p/owasp-top-ten`.

* Good, because it would provide wider coverage quickly.
* Bad, because `--config auto` requires metrics to build the automatic configuration.
* Bad, because `p/default` reported only false positives in the measured baseline.
* Bad, because broad packs overlap current accepted coverage and existing Java analyzers.
* Bad, because broad registry packs can change outside this repository.
* Bad, because the baseline also included shell partial-parse warnings.

### Add Semgrep Registry Packs as Report-Only Documentation

Document a report-only registry command but do not enforce it.

* Good, because maintainers could rerun broader scans occasionally.
* Bad, because it creates another non-blocking command whose findings can go stale.
* Bad, because the measured `p/default` result did not identify actionable work.
* Bad, because `--config auto` cannot satisfy the metrics-disabled requirement.

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
