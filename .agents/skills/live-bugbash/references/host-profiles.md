# Host Profiles

Resolve the host profile before running installer, uninstaller, setup-local, access-mode, child Codex, or destructive-local-data scenarios.

## Default: `HOST_PROFILE=standard`

`standard` is the safe default.

In standard mode:

- Use fake Trello, fake Codex, and fake/local GitHub unless the goal separately opts into real integrations.
- Use run-scoped HOME, XDG, SYMPHONY, prefix, config, state, cache, workspace, manifest, env, and log paths for installer and lifecycle tests.
- Execute normal workspace-write and path-scoped writable-root tests when they remain inside run-owned locations.
- Exercise dangerous modes mainly by inspecting source, validating generated workflow configuration, checking CLI parsing, using dry-run paths, using fake Codex app-server behavior, or asserting that the product refuses unsafe combinations.
- Do not launch unrestricted host-level child Codex processes, and do not run Codex `--dangerously-bypass-approvals-and-sandbox` or `--yolo`.
- If a matrix row requires actual host-level dangerous execution, mark that row `requires_hardened_host` and continue with other rows.

## Opt-in: `HOST_PROFILE=hardened`

Set `HOST_PROFILE=hardened` only when the goal affirmatively contains one of these exact or
equivalent signals. If the phrase appears inside a denial such as `not a hardened host`, `no
dangerous host access`, or `do not run dangerous access on host`, keep `HOST_PROFILE=standard`. If
intent remains ambiguous, keep `HOST_PROFILE=standard` and record the safer decision.

- `HOST_PROFILE=hardened`
- `HARDENED_HOST=true`
- `hardened host`
- `hardened system`
- `this host is hardened`
- `host is hardened`
- `this system is hardened`
- `hardened runner`
- `hardened machine`
- `run dangerous access on host`
- `exercise dangerous access on host`
- `run the dangerously things on the host`
- `safe to use danger full access on host`

A hardened host is an operator assertion that this machine is hardened enough for run-scoped dangerous access-mode testing. It is not a real-service opt-in by itself. External-service modes are still resolved separately from `TRELLO_MODE`, `CODEX_MODE`, `GITHUB_MODE`, `REAL_INTEGRATIONS=all`, `without fakes`, or `do a real live bugbash`.

In hardened mode:

- Cover the full dangerous-access matrix unless the goal explicitly asks for a narrower pass.
- Execute path-scoped writable-root tests and all-path setup tests with run-owned paths.
- Execute `setup-local --danger-full-access` when available.
- Execute `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true` when relevant.
- Execute workflow-authored `dangerFullAccess` scenarios.
- Execute direct Codex `--sandbox danger-full-access --ask-for-approval never` when real Codex is enabled and the installed Codex supports it.
- Execute direct Codex `--dangerously-bypass-approvals-and-sandbox` or `--yolo` when real Codex is enabled and the installed Codex help exposes it.
- Keep every dangerous operation run-scoped, observable, registered, and recoverable.

## Hardened host no-trash guardrails

Hardened mode is permission to exercise Symphony and Codex access-mode behavior. It is not permission to damage the host.

Allowed mutations:

- Files and directories under `RUN_ROOT`.
- Paths listed in `owned-local-paths.txt` before mutation.
- Run-scoped installer prefixes, wrapper bin dirs, config dirs, state dirs, cache dirs, workspace roots, log dirs, env files, manifests, fake services, local fake Git repos, and private GitHub sandbox clones.
- Processes listed in `started-workers.jsonl` or child processes of those registered workers.

Forbidden mutations and probes:

- Do not remove, rewrite, chmod, chown, or recursively traverse broad paths such as `/`, `/tmp`, `/var`, `$HOME`, `$HOME/.codex`, `$HOME/.ssh`, `$HOME/.config`, the repository root, or parent directories of the run root.
- Do not run broad cleanup commands such as `rm -rf /`, `rm -rf ~`, `find / -delete`, `git clean -fdx` outside disposable clones, `docker system prune`, `killall`, package-manager cleanup, or service-manager cleanup.
- Do not edit shell startup files, global Git config, OS service configuration, package-manager state, credential stores, SSH config, Codex auth files, Trello credential files, or GitHub auth files unless the file is a run-scoped copy under `RUN_ROOT`.
- Do not scan unrelated host directories for secrets, tokens, private data, SSH keys, browser state, or credentials.
- Do not stress CPU, memory, disk, network, process tables, rate limits, or filesystem watchers.
- Do not use dangerous access to bypass GitHub boundaries or Trello boundaries.

## Dangerous operation preflight

Before any operation that uses `danger-full-access`, `--dangerously-bypass-approvals-and-sandbox`, `--yolo`, `--allow-all-paths`, or destructive installer/uninstaller behavior, write a short preflight entry to `progress.md`:

```yaml
hardened_operation_preflight:
  scenario: <short name>
  command_or_surface: <command, workflow, or installer path>
  host_profile: standard | hardened
  integration_profile:
    trello: fake | real
    codex: fake | real
    github: fake | real-sandbox
  expected_mutation_roots:
    - <run-owned path>
  registry_entries_checked:
    - owned-local-paths.txt
    - started-workers.jsonl
    - created-trello-boards.jsonl
    - created-github-sandbox-repos.jsonl
  forbidden_targets_checked: true
  cleanup_plan: <how this scenario will be cleaned up>
```

If the expected mutation root is not under `RUN_ROOT` and is not listed in `owned-local-paths.txt`, do not run the operation.

## Reporting

Every coverage row and issue draft involving access modes must record:

```yaml
host_profile: standard | hardened
hardened_host_opt_in: <none | explicit parameter | natural language phrase>
access_mode_executed: <workspace-write | extra-writable-roots | allow-all-paths | danger-full-access | dangerously-bypass | generated-only | dry-run-only | fake-only | not-applicable>
dangerous_host_command_executed: true | false
run_scoped_guardrails_used: true | false
```
