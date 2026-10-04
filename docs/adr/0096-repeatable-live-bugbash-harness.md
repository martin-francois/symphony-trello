---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude Code]
consulted:
  - "[Repeatable live bug-bash harness issue](https://github.com/martin-francois/symphony-trello/issues/492)"
  - "[Themed live bug-bash runs issue](https://github.com/martin-francois/symphony-trello/issues/499)"
  - "[Fake-Trello replay used for the Merging fix](https://github.com/martin-francois/symphony-trello/pull/783)"
informed: [Future maintainers, Contributors]
---

# Run the Live Bug Bash From a Data Manifest With a TypeScript Harness

## Context and Problem Statement

The first live bug bash covered 262 scenarios, but the knowledge to repeat it lived in a private
transcript, ignored evidence, and ad hoc shell snippets. The [repeatable live bug-bash harness issue](https://github.com/martin-francois/symphony-trello/issues/492) asks for an opt-in harness that
lists every scenario as data, runs subsets by profile or family, keeps known bugs visible, cleans up
run-owned Trello boards and processes, writes public-safe reports, and never needs live credentials
in CI. No valid Trello credentials were available while building it, so the harness also had to
prove itself end to end without real Trello.

How should the harness be built so it can run against real Trello, Codex, and GitHub later, and
against fakes today?

## Decision Drivers

* Scenarios are data: id, requirements, expected status, linked issue, and prose for setup, action,
  assertions, and cleanup.
* A contributor can run one scenario, one family, or a profile from one entry point.
* The product is tested at its boundary: the installed `symphony-trello` command, managed workers,
  the state API, and Trello state.
* Real services stay opt-in. A fake run must not reach the real Trello API by accident.
* The harness is testable in the existing script-tests CI job without Java, Docker, or credentials.
* Public reports never carry credentials, board links, account names, or host paths.

## Considered Options

* TypeScript harness on Node with a YAML manifest, an in-process fake Trello API, and the existing
  Java fake Codex app-server extended with scripted behaviors
* Bash scripts with `lib/trello.sh`, `lib/codex.sh`, and similar helpers, as sketched in the issue
* Single-file Java programs run with `java --source 25`, like the existing live-E2E helpers
* JUnit integration tests next to `LiveTrelloE2eIT`

## Decision Outcome

Chosen option: "TypeScript harness on Node with a YAML manifest", because it parses the manifest
with the `yaml` package the repository already uses, gets strict type checking and `node:test`
coverage in the existing script-tests job, and can host a stateful fake Trello API in the same
process as the runner.

The parts:

* `scripts/live-bugbash/run.sh` is the entry point. It checks Node 24 and the script dependencies
  and runs `harness.ts`.
* `manifest.yml` lists every scenario from the issue. Whether a row is automated comes from the
  scenario registry in `scenarios/index.ts`, not from a manifest field, so the two cannot disagree;
  a unit test fails when the registry names an id the manifest lacks.
* Rows run in a fixed mode order: deterministic CLI, Trello CLI, fake-Codex service, real Codex,
  GitHub sandbox, cleanup, soak. Fake-Codex rows therefore always run before real-Codex rows.
* `lib/fake-trello-api.ts` is a stateful stand-in for the Trello REST endpoints Symphony uses. It
  runs inside the harness process, so every CLI call is asynchronous to keep it serving. It records
  unsupported routes instead of guessing.
* `scripts/FakeCodexAppServer.java` stays the single fake app-server. Optional environment
  variables add prompt capture and per-card scripts for tool calls, user-input and approval
  requests, telemetry, malformed JSON, cancelled turns, and stalls. Without them it behaves as
  before, so `LiveTrelloE2eIT` and `docs/live-e2e.md` are unchanged.
* Real Trello, Codex, and GitHub need `--trello real`, `--codex real`, and `--github
  real-sandbox`. A row whose requirement is missing is `skipped`, never passed.
* The issue's six statuses stay, and three are added: `failed` for an unexpected product failure,
  `skipped` for a missing opt-in or tool, and `not-yet-automated` for a manifest row without
  automation. Without `failed`, a new bug would have to be reported as a known bug or a harness
  problem.
* Selection is one function, `selectScenarios`, over a criteria object. Theme support from the
  [themed live bug-bash runs issue](https://github.com/martin-francois/symphony-trello/issues/499) adds a
  criterion there and an optional manifest field instead of filtering elsewhere.

### Consequences

* Good, because the manifest, selection, gating, statuses, reports, redaction, and the fake Trello
  API are covered by `pnpm run verify:scripts` in CI without credentials.
* Good, because a fake run exercises the installed CLI, real workers, and the real state API, and
  the unregistered-board guard catches a scenario that would leak a real board.
* Good, because fixed known bugs keep their rows as regression checks with `linked_issue`.
* Bad, because contributors need Node and `pnpm install` in addition to the JDK to run the harness.
* Bad, because the fake Trello API can drift from Trello; rows run against it carry the
  `trello:fake` tag and need a real run before they count as live coverage.
* Bad, because the Java fake app-server parses JSON with regular expressions; scripted steps must
  stay simple.
* Bad, because real-Codex rows run with a copy of the operator's Codex login. If Codex refreshes the
  login inside the copy, the operator's own file keeps the older tokens. The harness never writes to
  the operator's Codex home; cleanup reports the refresh so the operator can log in again.

### Confirmation

* `scripts/live-bugbash/**/*.test.ts` run in the script-tests CI job. They check that the manifest
  loads with every required field, that known-bug rows link an issue, that every automated id has a
  manifest row, the run order, the gating reasons, the status rules, redaction, and a small run
  against the fake Trello API.
* `scripts/live-bugbash/run.sh --profile release` runs every automated fake-mode row locally.

## Pros and Cons of the Options

### TypeScript harness on Node with a YAML manifest

* Good, because the repository already type-checks and tests TypeScript scripts in CI.
* Good, because YAML parsing, JSON handling, HTTP, and child processes need no extra dependency.
* Good, because the fake Trello API and the runner share state, so scenarios can assert on moves and
  comments directly.
* Bad, because it adds a second runtime next to the JDK for this tool.

### Bash scripts with shell helper libraries

* Good, because the issue sketched this layout and the original run used shell commands.
* Bad, because parsing a YAML manifest in Bash needs an extra tool such as `yq`.
* Bad, because report generation, redaction, and JSON assertions in Bash are hard to test and review.

### Single-file Java programs

* Good, because Java is already required.
* Bad, because single-file programs cannot use the YAML library without a classpath, and the
  harness would need many cooperating files.

### JUnit integration tests

* Good, because they would sit next to `LiveTrelloE2eIT` and reuse test fixtures.
* Bad, because the issue asks for the installed-CLI boundary, profiles, a public ledger, and a soak
  loop, which do not map well onto a Maven test run.
* Bad, because running subsets by profile and resuming a run would need custom JUnit tooling.

## More Information

[docs/live-bugbash.md](../live-bugbash.md) documents usage, statuses, and how to add a scenario.
The `$live-bugbash` skill runs the harness as its repeatable baseline.
