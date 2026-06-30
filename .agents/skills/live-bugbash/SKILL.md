---
name: live-bugbash
description: Safe-by-default Symphony for Trello bug bash. Uses fake Trello, fake Codex, and fake/local GitHub unless explicitly asked for a real live bug bash; the phrase "hardened host" enables run-scoped danger-full-access and Codex dangerous-bypass coverage. Covers SPEC.md conformance, installers, CLI options, workflow parameters, parallelism, access modes, and sanitized local issue drafts. It never publishes issues to GitHub; use $publish-bugbash-issues later after human review to create new issues or comment on duplicates.
---

# Live bug bash skill for Symphony for Trello

Use this skill when the operator asks for a bug bash, live bug bash, integration bug hunt, SPEC.md conformance pass, installer bug bash, workflow-parameter bug hunt, fake-integration bug bash, real Trello test, real Codex app-server test, access-mode bug hunt, or hardened-host bug bash for this repository.

This skill is safe by default. A normal invocation uses fake Trello, fake Codex, and fake/local GitHub so an accidental trigger cannot create external boards, repositories, issues, pull requests, or worker sessions against real services. Real integrations and hardened-host dangerous access are used only when the goal clearly opts in.

Codex should not implicitly use this skill for ordinary implementation work. This repository includes `agents/openai.yaml` with implicit invocation disabled.

## Minimal invocations

Safe default with fakes and conservative host access:

```text
/goal Use $live-bugbash until <future timestamp>.
```

Safe default with fakes but full hardened-host access-mode coverage:

```text
/goal Use $live-bugbash until <future timestamp>. This is running on a hardened host.
```

Real run with all integrations enabled, but without hardened-host dangerous coverage:

```text
/goal Use $live-bugbash until <future timestamp>. Do a real live bugbash without fakes.
```

Real run with all integrations enabled and hardened-host dangerous coverage:

```text
/goal Use $live-bugbash until <future timestamp>. Do a real live bugbash on a hardened host.
```

Explicit real run equivalent:

```text
/goal Use $live-bugbash until <future timestamp>. REAL_INTEGRATIONS=all HOST_PROFILE=hardened.
```

Mixed integration run:

```text
/goal Use $live-bugbash until <future timestamp>. TRELLO_MODE=real CODEX_MODE=fake GITHUB_MODE=fake.
```

Continuation run:

```text
/goal Use $live-bugbash until <future timestamp>. PREVIOUS_RUN_ID=live-bugbash-20260629T103000Z.
```

Advanced override:

```text
/goal Use $live-bugbash until <future timestamp>. RUN_ID=live-bugbash-manual-2 ACTIVE_EXPLORATION_MINUTES_WITHOUT_FINDINGS=60 REAL_INTEGRATIONS=all HOST_PROFILE=hardened.
```

## Defaults

Unless the operator explicitly overrides them, use these values:

```text
TARGET_REPO=martin-francois/symphony-trello
TARGET_BRANCH=main
RUN_ID=live-bugbash-<UTC timestamp as YYYYMMDDTHHMMSSZ>
RUN_ROOT=target/live-bugbash/<RUN_ID>
ISSUE_DRAFT_DIR=target/live-bugbash/<RUN_ID>/issues
GITHUB_SANDBOX_PREFIX=live-bugbash
ACTIVE_EXPLORATION_MINUTES_WITHOUT_FINDINGS=120
TRELLO_MODE=fake
CODEX_MODE=fake
GITHUB_MODE=fake
HOST_PROFILE=standard
```

Parse `until <time>` in the goal as `RUN_END_SYSTEM_TIME`. Prefer timezone-qualified timestamps. If the timestamp has no timezone, treat it as the Codex host local system time and record that assumption in `progress.md`. If only `HH:MM` is supplied, treat it as today's host-local time. If the parsed end time is already in the past, stop and ask for a future timestamp rather than silently rolling it to another day.

Before using `RUN_ID`, `RUN_ROOT`, `ISSUE_DRAFT_DIR`, or `PREVIOUS_RUN_ID` in any filesystem path,
validate that each run ID is one safe path segment: ASCII letters, digits, `.`, `_`, and `-` only;
not empty; not `.` or `..`; no slash, backslash, control character, shell metacharacter, or
percent-encoded separator. Resolve and normalize `RUN_ROOT` and `ISSUE_DRAFT_DIR` before creating,
reading, or deleting anything. `ISSUE_DRAFT_DIR` must stay under `RUN_ROOT`, and default run roots
must stay under `target/live-bugbash/`. If an explicit path override does not normalize to a
run-owned location, stop and ask for a corrected run-scoped path.

`PREVIOUS_RUN_ID` is optional. When present, apply the same safe-segment validation, then read only
the previous run's final report, issue drafts, and coverage ledger to avoid duplicate testing and
duplicate issue drafts. Do not inherit its mutable state.

## Mode resolution

Read `references/integration-modes.md`, `references/host-profiles.md`, and `references/safety-and-isolation.md` before doing any setup. Resolve integration modes and the host profile before any command that could touch Trello, GitHub, Codex, workers, credentials, or host state.

The words `live-bugbash` or `$live-bugbash` by themselves do not opt into real external services or host dangerous access.

Real integrations require one of these affirmative explicit signals in the goal. Ignore the phrase
when it appears inside a denial such as `do not use real Trello`, `without real services`, or `not a
real run`; keep the affected mode fake when intent is ambiguous.

- `REAL_INTEGRATIONS=all`
- `TRELLO_MODE=real`, `CODEX_MODE=real`, or `GITHUB_MODE=real-sandbox`
- `use real trello`, `use real codex`, or `use real github`
- `use real trello, codex and github`
- `without fakes`
- `no fakes`
- `do a real live bugbash`
- `run against real services`

When the goal affirmatively says `without fakes`, `no fakes`, `REAL_INTEGRATIONS=all`, `do a real
live bugbash`, or `run against real services`, set:

```text
TRELLO_MODE=real
CODEX_MODE=real
GITHUB_MODE=real-sandbox
```

Hardened-host dangerous access requires one of these affirmative explicit signals in the goal. Ignore
the phrase when it appears inside a denial such as `not a hardened host`, `no dangerous host access`,
or `do not run dangerous access on host`; keep `HOST_PROFILE=standard` when intent is ambiguous.

- `HOST_PROFILE=hardened`
- `HARDENED_HOST=true`
- `hardened host`
- `hardened system`
- `hardened runner`
- `this host is hardened`
- `host is hardened`
- `run dangerous access on host`
- `exercise dangerous access on host`
- `run the dangerously things on the host`
- `safe to use danger full access on host`

When hardened-host access is enabled, set `HOST_PROFILE=hardened`. This means the run should include host-level dangerous-access scenarios where the resolved integration modes allow them. It includes `setup-local --danger-full-access`, `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true`, workflow-authored `dangerFullAccess`, direct Codex `--sandbox danger-full-access --ask-for-approval never`, and Codex `--dangerously-bypass-approvals-and-sandbox` or `--yolo` when supported by the installed Codex and when `CODEX_MODE=real` for direct Codex execution.

A hardened-host signal by itself does not imply real Trello, real Codex, or real GitHub. The phrase `do a real live bugbash on a hardened host` opts into both real integrations and hardened-host dangerous-access coverage.

`GITHUB_MODE=real-sandbox` still means GitHub writes are restricted to newly created private sandbox repositories whose names include `RUN_ID`. It never permits writes to existing repositories, existing issues, existing pull requests, settings, secrets, organization configuration, billing, collaborators, or releases.

If the goal contains conflicting integration or host-profile instructions, prefer explicit key-value settings over natural-language phrases. If ambiguity remains, choose the safer mode for the ambiguous service or access profile and record the decision.

## Required references

When this skill is used for an actual bug bash, read these files before starting mutable work:

1. `../../../AGENTS.md`
2. `../../../README.md`
3. `../../../SPEC.md`
4. `../../../.github/ISSUE_TEMPLATE/bug_report.yml`
5. `../../../CONTRIBUTING.md`
6. `references/integration-modes.md`
7. `references/host-profiles.md`
8. `references/safety-and-isolation.md`
9. `references/runbook.md`
10. `references/coverage-matrix.md`
11. `references/issue-drafts-and-reporting.md`

Also read these repository docs when present: `../../../docs/live-e2e.md`,
`../../../docs/operations.md`, `../../../docs/deployment.md`,
`../../../docs/agents/default-workflow.md`, and relevant ADRs found during the run. If a listed file
does not exist, record that as a repository-shape observation and continue with the available
equivalents.

## Goal

Run an evidence-based bug bash against the latest `TARGET_BRANCH` of `TARGET_REPO` until one of the stop conditions is met.

In fake mode, use deterministic fake or local services to exercise the same product surfaces without touching external Trello, Codex, or GitHub services. In real mode, use real Trello and real Codex through `codex app-server`; use real GitHub only through newly created private sandbox repositories.

When `HOST_PROFILE=hardened`, exercise the full host access-mode matrix, including path-scoped writable roots, all-path setup flows, danger-full-access, `setup-local --danger-full-access`, `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true`, workflow-authored `dangerFullAccess`, Codex `--sandbox danger-full-access --ask-for-approval never`, and Codex `--dangerously-bypass-approvals-and-sandbox` or `--yolo` when real Codex is enabled and the installed CLI exposes those flags. These tests must remain run-scoped and must not intentionally damage or globally reconfigure the host.

Find confirmed, reproducible product bugs. For each confirmed bug, create a sanitized local issue draft under `ISSUE_DRAFT_DIR`. Do not create, edit, label, or comment on issues in `martin-francois/symphony-trello` or any other existing GitHub repository. Publishing reviewed issue drafts and commenting on duplicate issues is a separate explicit workflow handled by `$publish-bugbash-issues`, not by this skill.

## Safety model

The safety model depends on the integration modes and host profile, but these rules always apply:

- Never mutate existing GitHub repositories, issues, pull requests, settings, secrets, collaborators, billing, workflows, releases, or organization settings. This skill creates local issue drafts only.
- Never read or write unrelated host data.
- Never intentionally wipe, stress, credential-scan, broad-delete, globally reconfigure, change shell profiles, mutate package managers, change service managers, kill unrelated processes, chmod or chown unrelated paths, or inspect unrelated private data.
- Installer, uninstaller, onboarding, cleanup, repair, and destructive local-data tests must isolate HOME, XDG, SYMPHONY, config, state, cache, workspace, prefix, bin, manifest, and env-file paths to run-owned locations.
- Real Trello, when enabled, may create and archive only run-scoped disposable boards and cards.
- Real GitHub, when enabled, may write only inside newly created private sandbox repositories whose names include `RUN_ID`.
- Host `danger-full-access` and Codex `--dangerously-bypass-approvals-and-sandbox` or `--yolo` are allowed only when `HOST_PROFILE=hardened`. This is permission to exercise product access modes, not permission to damage the machine.

## Active exploration stop rule

The no-new-findings period is measured as active exploration time, not passive wall-clock waiting.

Stop at the earliest of:

1. `RUN_END_SYSTEM_TIME`
2. A non-recoverable global safety blocker after all safer alternatives are exhausted
3. Full meaningful matrix coverage plus `ACTIVE_EXPLORATION_MINUTES_WITHOUT_FINDINGS` minutes of active exploration without finding a new unique confirmed issue
4. Lack of useful remaining work

Active exploration means useful bug-bash work, such as testing uncovered behavior, varying workflow parameters, reproducing or falsifying suspected issues, inspecting relevant source code, collecting evidence, deduplicating, writing issue drafts, cleaning run-scoped artifacts, or writing the final report.

Do not sleep, poll timestamps, run no-op commands, or keep a background terminal open merely to accumulate no-new-findings time.

Short waits are allowed only when they are tied to concrete product behavior under test, such as Symphony poll intervals, fake or real Trello API consistency, fake or real Codex app-server startup, worker shutdown, retry backoff, rate-limit recovery, or log flushing. Record what condition is being waited for and continue as soon as the condition has been checked.

If no meaningful testing, triage, evidence gathering, cleanup, or reporting remains, stop under `lack_of_useful_remaining_work` instead of waiting.

## Working loop

Use this loop throughout the run:

1. Check the current host system time and compare it to `RUN_END_SYSTEM_TIME`.
2. Pick the next weakly covered row from the coverage ledger, not a random path.
3. Design a small but meaningful scenario for that row using the resolved integration modes and host profile.
4. Create only run-scoped Trello, local, fake, and GitHub sandbox artifacts required for the scenario.
5. Execute through the product surface where practical: CLI, installer, packaged app, service API, workflow config, Trello adapter, Codex app-server adapter, and setup-local lifecycle commands.
6. Observe CLI output, logs, HTTP responses, fake or real app-server behavior, fake or real Trello state, process state, workspace files, generated workflow files, and source code.
7. Reproduce suspected bugs at least twice unless doing so would be destructive, rate-limited, or clearly unsafe.
8. Search existing local issue drafts and prior-run issue drafts for duplicates. In real GitHub mode, upstream issue searches may be read-only. Do not write to upstream issues.
9. Create or update a local sanitized issue draft for each unique confirmed issue.
10. Update `coverage-ledger.md`, `progress.md`, and run-owned registries immediately.
11. Clean up run-scoped mutable artifacts when safe, or record why they are intentionally left behind.
12. Continue until a stop condition is met.

## Final output

An actual run must leave at least these artifacts under `RUN_ROOT`:

- `progress.md`
- `coverage-ledger.md`
- `final-report.md`
- `cleanup-summary.md` or an equivalent cleanup section in the final report
- `issues/` with sanitized local issue drafts
- `created-trello-boards.jsonl`
- `created-trello-cards.jsonl`
- `created-github-sandbox-repos.jsonl`
- `started-workers.jsonl`
- `owned-local-paths.txt`
- snapshots and private evidence as needed under `snapshots/` and `private-evidence/`

The final report must include the stop reason, integration modes, host profile, real-service opt-in phrase, hardened-host opt-in phrase when any, target commit, coverage summary, issue draft list, blockers, cleanup status, artifacts intentionally left behind, private-context scan result, and recommended next runs.
