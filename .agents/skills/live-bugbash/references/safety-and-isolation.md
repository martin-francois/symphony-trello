# Safety And Isolation

This run may exercise powerful behavior when real integrations or hardened-host access are explicitly enabled. Keep the blast radius small by making every mutable resource run-scoped and auditable.

## Global boundaries

These rules apply in fake and real modes, and in both standard and hardened host profiles:

- Do not mutate existing GitHub repositories, issues, pull requests, settings, secrets, collaborators, billing, releases, or organization settings.
- Do not mutate unrelated Trello boards or Trello account settings.
- Do not read or write unrelated host files.
- Do not inspect unrelated private data.
- Do not run broad cleanup, stress, credential-scanning, or global reconfiguration commands.
- Do not use secrets in fake mode.
- Before destructive cleanup, verify that the target is registered as run-owned.

## GitHub boundary

Default `GITHUB_MODE=fake` means no real GitHub writes and no network `gh` calls. Use local Git repos, local bare remotes, fake `gh` wrappers, and local issue drafts.

When `GITHUB_MODE=real-sandbox`, the GitHub account is real and high risk.

Allowed real GitHub writes:

- Create new private sandbox repositories whose names start with `GITHUB_SANDBOX_PREFIX` and include `RUN_ID`.
- In those private sandbox repositories only, create small files, branches, commits, pull requests, labels, issues, comments, and reviews needed to test Symphony workflows.
- Make real code and workflow changes in those private sandbox repositories.

Forbidden real GitHub writes:

- Do not push to `TARGET_REPO` or any existing repository.
- Do not create, edit, close, label, or comment on existing issues or pull requests.
- Do not change repository settings, secrets, collaborators, branch protection, billing, webhooks, tokens, releases, workflows in existing repos, or organization settings.
- Do not delete sandbox repositories unless the operator explicitly requests deletion in the same run.

Read-only GitHub access is allowed only in real GitHub mode, and only for deduplication, documentation, source inspection, and issue searches. In fake mode, prefer the local checkout and local artifacts.

If commits or pull requests are blocked because author identity or verified email is unavailable, configure author identity only in the sandbox clone. If it still fails, record a scoped GitHub blocker and continue with fake-GitHub, local disposable repositories, and source-level inspection.

## Trello boundary

Default `TRELLO_MODE=fake` means no real Trello writes and no real Trello credentials.

When `TRELLO_MODE=real`, the Trello account is disposable for this bug bash.

Allowed real Trello actions:

- Create run-scoped disposable boards.
- Create and edit lists, cards, labels, checklists, comments, due dates, and URL attachments on run-created boards.
- Move cards through active, in-progress, blocked, review, merging, terminal, archived-card, archived-list, and archived-board scenarios.
- Archive run-created boards during cleanup.

Forbidden real Trello actions:

- Do not rotate API keys or revoke tokens.
- Do not change account settings, billing, Workspace settings, Power-Up settings, or membership.
- Do not invite users.
- Do not modify unrelated boards.

At real-run start, write a read-only snapshot of visible boards to `preexisting-trello-boards.json`. Every created board must be appended immediately to `created-trello-boards.jsonl` with board id, name, URL, creation command, and purpose. Cleanup may archive only board IDs in this registry.

## Local host boundary

### Standard host profile

`HOST_PROFILE=standard` is the default. It is meant for accidental or conservative runs.

Allowed host mutations:

- Files and directories under `RUN_ROOT`.
- Explicitly registered run-owned host directories listed in `owned-local-paths.txt`.
- Run-scoped installer prefixes, config dirs, state homes, workspace roots, log dirs, env files, manifests, fake-service files, and disposable local Git repositories.

Forbidden in standard profile:

- Direct host execution of Codex `--sandbox danger-full-access`, `--dangerously-bypass-approvals-and-sandbox`, or `--yolo`.
- Broad host access that is not needed to test a run-owned path.
- `setup-local --danger-full-access` or `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true` in a way that grants broad host access instead of only generating or validating config.

### Hardened host profile

`HOST_PROFILE=hardened` is enabled only when the operator explicitly says the run is on a hardened host or sets the profile directly.

Host danger-full-access is allowed for run-scoped tests, but broad destructive host actions are still forbidden.

Allowed in hardened profile:

- Files and directories under `RUN_ROOT`.
- Explicitly registered run-owned host directories listed in `owned-local-paths.txt`.
- Run-scoped installer prefixes, config dirs, state homes, workspace roots, log dirs, env files, manifests, fake-service files, and disposable local Git repositories.
- `setup-local --allow-all-paths` when used to test generated policy or when effective writes remain run-scoped.
- `setup-local --danger-full-access` with isolated HOME, XDG, SYMPHONY, prefix, state, cache, config, env, manifest, log, and workspace paths.
- `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true` for run-scoped workflow and service scenarios.
- Workflow-authored `dangerFullAccess` for run-scoped workers.
- Direct Codex `--sandbox danger-full-access --ask-for-approval never` when `CODEX_MODE=real` and supported.
- Direct Codex `--dangerously-bypass-approvals-and-sandbox` or `--yolo` when `CODEX_MODE=real` and supported.

Forbidden in every host profile:

- Do not remove, chmod, chown, or rewrite broad paths such as `/`, `/tmp`, `/var`, `$HOME`, `$HOME/.codex`, `$HOME/.ssh`, `$HOME/.config`, or the repository root.
- Do not run broad cleanup commands such as `rm -rf /`, `rm -rf ~`, `find / -delete`, `git clean -fdx` outside disposable clones, `docker system prune`, `killall`, or package-manager cleanup.
- Do not edit shell startup files, OS service configuration, global Git config, credential stores, SSH config, Codex auth files, Trello credential files, or GitHub auth files unless the file is a run-scoped copy under `RUN_ROOT`.
- Do not scan unrelated host directories for secrets or private data.
- Do not stress CPU, memory, disk, network, process tables, or rate limits.
- Do not use danger mode to explore unrelated host contents.

## Installer and uninstaller isolation

Installer, uninstaller, onboarding, setup, repair, cleanup, and destructive local-data paths must run with isolated local state even when hardened-host danger mode is allowed.

Use run-scoped HOME, XDG, and SYMPHONY paths:

```bash
export HOME="$RUN_ROOT/installer-sandboxes/home"
export XDG_CONFIG_HOME="$RUN_ROOT/installer-sandboxes/xdg-config"
export XDG_DATA_HOME="$RUN_ROOT/installer-sandboxes/xdg-data"
export XDG_STATE_HOME="$RUN_ROOT/installer-sandboxes/xdg-state"
export XDG_CACHE_HOME="$RUN_ROOT/installer-sandboxes/xdg-cache"
export SYMPHONY_HOME="$RUN_ROOT/installer-sandboxes/symphony-home"
```

Also pass explicit run-scoped options where available: `--prefix`, `--bin-dir`, `--config-dir`, `--workspace-root`, `--manifest`, `--env`, `--state-home`, and workflow path.

If a script ignores isolation and attempts to touch default local data, stop that sub-scenario, preserve evidence, draft an issue, recover if safe, and continue other scenarios with stricter isolation.

## Registries

Create these before mutating anything:

- `created-trello-boards.jsonl`
- `created-trello-cards.jsonl`
- `created-github-sandbox-repos.jsonl`
- `started-workers.jsonl`
- `owned-local-paths.txt`

Before archiving, stopping, deleting, repairing, uninstalling, or cleaning, verify that the target is registered as run-owned. If not registered, do not mutate it.
