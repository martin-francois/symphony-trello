# Runbook

## 1. Parse the goal and resolve modes

Extract the end time from the goal phrase, for example `until 2026-06-29T18:00:00+02:00`. Record:

- raw goal text
- parsed end time
- host local time and timezone
- UTC time
- `RUN_ID`
- all overrides
- `TRELLO_MODE`, `CODEX_MODE`, `GITHUB_MODE`, and `HOST_PROFILE`
- exact phrase or parameter that opted into any real integration
- exact phrase or parameter that opted into hardened-host behavior

If no end time is supplied, ask for one before starting bug-bash testing. Do not invent a time budget silently.

Resolve integration modes and host profile from `integration-modes.md` before doing anything mutable. The default is fake Trello, fake Codex, fake GitHub, and standard host.

## 2. Initialize the run root

Create `RUN_ROOT` and these subdirectories:

```text
workflows/
workspaces/
state/
config/
logs/
snapshots/
snapshots/cli-help/
snapshots/fake-trello/
snapshots/fake-codex/
snapshots/fake-github/
issues/
private-evidence/
github-sandbox/
external-projects/
installer-sandboxes/
installer-sandboxes/home/
installer-sandboxes/xdg-config/
installer-sandboxes/xdg-data/
installer-sandboxes/xdg-state/
installer-sandboxes/xdg-cache/
installer-sandboxes/symphony-home/
fakes/
fakes/trello/
fakes/codex/
fakes/github/
fakes/bin/
```

Create initial empty registries and `progress.md`. Record the safety boundaries, resolved integration modes, and host profile in the progress log.

## 3. Sync and baseline

Use the current repository checkout only after confirming it is `TARGET_REPO` at the requested
`TARGET_BRANCH` and intended commit. When network access is appropriate for the selected modes,
fetch the target repository, check out `TARGET_BRANCH`, fast-forward or otherwise sync to the latest
target commit, and record the exact commit SHA. If the current checkout is on a different branch,
stale, dirty in a way that would affect the bug bash, or cannot be verified without disallowed
network access, stop and ask whether to use the current checkout as an explicit override or to switch
to a verified target checkout. Record that decision in `progress.md`.

Read the current project instructions and docs. Run the baseline build when time allows:

```bash
./mvnw -q spotless:check verify
```

If this is too expensive for the available end-time budget, run the strongest smaller baseline available and record why.

## 4. Discover current command surfaces

Do not rely only on this skill's memory of commands. Discover from the installed or source CLI at runtime and store outputs under `snapshots/cli-help/`.

Capture at least:

```bash
symphony-trello --help
symphony-trello --version
symphony-trello list-workspaces --help
symphony-trello new-board --help
symphony-trello import-board --help
symphony-trello setup-local --help
symphony-trello setup-local check --help
symphony-trello setup-local repair-port --help
symphony-trello setup-local configure-github --help
symphony-trello start --help
symphony-trello stop --help
symphony-trello status --help
symphony-trello logs --help
symphony-trello diagnostics --help
```

Also capture runtime app help for the packaged service path where available, including positional `WORKFLOW.md` and `--port` behavior.

For installers and uninstallers, capture help and detected options for:

```bash
./install.sh --help
./uninstall.sh --help
pwsh -File ./install.ps1 -Help
pwsh -File ./uninstall.ps1 -Help
```

Skip PowerShell-specific execution only when PowerShell is unavailable. Record it as a scoped blocker and still inspect source-level behavior.

## 5. Check external tooling according to modes

Always record sanitized outputs for:

```bash
java -version
./mvnw -version
```

In `CODEX_MODE=real`, also record:

```bash
codex --version
codex --help
codex app-server --help
codex app-server generate-json-schema --out "$RUN_ROOT/snapshots/codex-schema"
```

In `CODEX_MODE=fake`, inspect existing test fake app-server patterns and create run-local fake scripts when needed. Do not run real Codex.

In `GITHUB_MODE=real-sandbox`, record sanitized outputs for:

```bash
gh auth status
gh api user
```

In `GITHUB_MODE=fake`, do not call network `gh`. Use local Git repositories and a run-local fake `gh` wrapper if a scenario requires GitHub CLI behavior.

If `codex --help` exposes `--dangerously-bypass-approvals-and-sandbox` or `--yolo`, include direct execution only when `CODEX_MODE=real` and `HOST_PROFILE=hardened`. In fake mode or standard-host mode, cover generated configuration, command construction, and source-level handling instead.

## 6. Fake service setup

When `TRELLO_MODE=fake`:

- Prefer repository `FakeTrelloServer` or an equivalent current test fixture.
- Configure workflow `tracker.endpoint` to the fake endpoint.
- Use synthetic credentials.
- Script fake responses for open cards, archived cards, archived lists, archived boards, terminal lists, labels, comments, attachments, checklists, prerequisite references, auth errors, permission errors, not found, malformed payloads, rate limits, and retryable transport errors as needed.

When `CODEX_MODE=fake`:

- Create fake app-server scripts under `RUN_ROOT/fakes/codex/`.
- Set workflow `codex.command` to the fake script.
- Cover successful turns, nonzero exits, stalls, unsupported tools, approval requests, user-input-required behavior, usage telemetry, rate-limit telemetry, and malformed protocol responses where practical.

When `GITHUB_MODE=fake`:

- Create local Git repos under `RUN_ROOT/fakes/github/`.
- Use local branches and local bare remotes.
- Use a fake `gh` wrapper under `RUN_ROOT/fakes/bin/gh` only when a scenario needs GitHub CLI behavior.
- Put `RUN_ROOT/fakes/bin` first on `PATH` for that scenario, and never fall back to the real `gh` binary in fake mode.

## 7. Real Trello board design

Run this section only when `TRELLO_MODE=real`.

Run `list-workspaces` first. If exactly one safe Workspace is available, use it. If several are available, choose an obviously disposable Workspace when clear; otherwise continue with paths that do not require Workspace selection and record a scoped blocker for live board creation.

Create several run-scoped board archetypes instead of one monolithic board:

1. Standard generated board from `new-board`.
2. Imported custom board with renamed lists and multiple active and terminal lists.
3. Parallelism board with at least two active lists, multiple priority labels, several cards, and `max-agents` greater than one.
4. Edge-state board with archived cards, archived lists, terminal-list cards, prerequisite checklists, labels with case and whitespace variations, comments, URL attachments, and due dates.
5. Access-mode board or cards used to exercise path-scoped roots and, when `HOST_PROFILE=hardened`, full-access modes with real Codex.

Append every board and important card to the registries immediately.

## 8. Real GitHub sandbox design

Run this section only when `GITHUB_MODE=real-sandbox`.

Create private sandbox repositories only when a GitHub workflow scenario needs real GitHub writes. Names must start with `GITHUB_SANDBOX_PREFIX` and include `RUN_ID`.

Use sandbox repos for small real code changes, for example:

- create a tiny Java, TypeScript, or Markdown project
- modify a file through a Codex card
- create a branch and pull request
- exercise review, rework, blocked, merge, and done instructions when safe
- verify that workflow prompts never target existing repos

Do not delete sandbox repos automatically. List them in `cleanup-summary.md` for manual cleanup unless the operator explicitly requested deletion.

## 9. Access-mode coverage

Balance major paths across these modes without exposing them as normal user-facing parameters. In `HOST_PROFILE=standard`, execute path-scoped and run-scoped modes and cover dangerous modes by generation, validation, dry-run, fake Codex, or source inspection. In `HOST_PROFILE=hardened`, execute the full dangerous host matrix when the installed surfaces exist, while keeping all mutations run-owned:

- default sandbox or workspace-write behavior
- path-scoped additional writable roots, including multiple roots
- `setup-local --add-path`
- `setup-local --allow-all-paths` with actual writes still confined to run-owned paths
- workflow-authored `codex.additional_writable_roots`
- workflow-authored `turn_sandbox_policy` with writable roots
- workflow-authored or setup-authored `dangerFullAccess`
- `setup-local --danger-full-access`
- `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true`
- direct Codex `--sandbox danger-full-access --ask-for-approval never` when `CODEX_MODE=real`, `HOST_PROFILE=hardened`, and supported
- direct Codex `--dangerously-bypass-approvals-and-sandbox` or `--yolo` when `CODEX_MODE=real`, `HOST_PROFILE=hardened`, and supported
- invalid or conflicting combinations, such as path-scoped roots with danger mode, relative paths, blank paths, root path without explicit allow-all, or unsupported access options on subcommands

Coverage rules:

- Under `HOST_PROFILE=standard`, cover dangerous-access rows by help output, setup command generation, workflow validation, source inspection, fake Codex, or dry-run behavior. Do not launch unrestricted child Codex.
- Under `HOST_PROFILE=hardened`, execute dangerous-access rows where practical, but use only run-owned files and directories for actual mutations.
- For every dangerous-access scenario, create a run-owned sentinel file and ask the product or child Codex to read or modify only that sentinel or adjacent run-owned files.
- Do not ask the product or child Codex to try writing outside run-owned paths.
- For each access-mode finding or successful path, record the effective access mode, host profile, command, workflow snippet, expected boundary, actual boundary, integration modes, and cleanup result.

## 10. Service scenarios

Run Symphony workers against fake or real boards according to `TRELLO_MODE`. Use fake or real Codex according to `CODEX_MODE`.

For each service scenario:

- start with a run-scoped workflow path, workspace root, config dir, state home, env file, manifest, port, and logs
- create cards that perform small safe tasks
- watch `/api/v1/state` and logs
- verify card pickup, in-progress movement, retries, stall or timeout behavior, terminal cleanup, archived state cleanup, and handoff behavior
- modify `WORKFLOW.md` during a run to test dynamic reload and last-known-good behavior
- stop only workers recorded in `started-workers.jsonl`

## 11. Installer and lifecycle scenarios

Test installer and uninstaller behavior with run-scoped HOME/XDG/SYMPHONY paths, prefixes, config dirs, manifests, workspaces, and state homes. Include:

- install with onboarding
- install with no onboarding
- reinstall or update over existing run-scoped install
- custom prefix and bin directory
- missing dependencies and degraded paths where practical
- non-interactive setup
- setup dry-run
- fake Trello setup by endpoint override or equivalent when supported
- fake Codex setup by configured command or equivalent when supported
- fake GitHub setup by local git and fake `gh` wrapper when supported
- real Trello, real Codex, and real GitHub sandbox variants only when explicitly opted in
- standard-host generated-config coverage for dangerous access
- hardened-host execution coverage for `--allow-all-paths`, `--danger-full-access`, `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true`, and direct Codex dangerous flags when supported
- uninstall from custom prefix
- uninstall when files are missing
- uninstall without touching default local data
- installed wrapper defaults for config dir, env file, manifest, workflow, state, and workspace options
- lifecycle commands: start, stop, status, logs, diagnostics, repair-port, configure-github

For any destructive operation, verify target ownership from registries first.

## 12. Active exploration loop

At the start of each major area and after each issue draft:

1. Check current host time.
2. Stop if the configured end time has arrived.
3. Pick the next uncovered or weakly covered matrix row.
4. Prefer rows that combine several features safely.
5. Continue only if there is enough time left to collect evidence and perform cleanup.

When the matrix is meaningfully covered, continue active exploration for the configured active exploration period only if useful work remains. Do not wait to consume time. Stop immediately when useful work is exhausted.

## 13. Issue handling and reporting

Use `issue-drafts-and-reporting.md`. Do not file or update upstream issues. Draft local issues only.

Before the final report, run a private-context scan over issue drafts, logs selected for publication, final report, and cleanup summary. Redact secrets, private Trello URLs, private GitHub URLs that should not be shared, private host paths, account names, tokens, and raw auth status details.
