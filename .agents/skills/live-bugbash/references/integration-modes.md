# Integration Modes And Host Access Profiles

Resolve integration modes and the host profile before doing any setup or running any command that could touch Trello, Codex, GitHub, workers, credentials, or host state.

## Default behavior

The default is safe and fake:

```text
TRELLO_MODE=fake
CODEX_MODE=fake
GITHUB_MODE=fake
HOST_PROFILE=standard
```

A default run must not create real Trello boards, use real Trello credentials, launch real `codex app-server`, create real GitHub repositories, write to real GitHub, run host-level Codex dangerous bypass, or start long-lived real workers against external services.

The skill name contains `live` for historical and project vocabulary reasons. The word `live-bugbash` alone is not a real-service opt-in and is not a hardened-host opt-in.

## Real-service opt-in

Enable real integrations only when the goal contains an explicit opt-in:

- `REAL_INTEGRATIONS=all`
- `TRELLO_MODE=real`, `CODEX_MODE=real`, or `GITHUB_MODE=real-sandbox`
- `use real trello`, `use real codex`, or `use real github`
- `use real trello, codex and github`
- `without fakes`
- `no fakes`
- `do a real live bugbash`
- `run against real services`

When the goal says `without fakes`, `no fakes`, `REAL_INTEGRATIONS=all`, or `do a real live bugbash`, set:

```text
TRELLO_MODE=real
CODEX_MODE=real
GITHUB_MODE=real-sandbox
```

When the goal names only one real integration, enable only that integration and keep the other modes fake unless explicit key-value settings say otherwise.

If natural-language and key-value settings conflict, explicit key-value modes win. If intent remains ambiguous, choose the safer fake mode for the ambiguous service and record the decision.

## Hardened-host opt-in

Enable hardened-host dangerous access only when the goal contains an explicit opt-in:

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

When the goal contains one of those phrases, set `HOST_PROFILE=hardened`. This enables host-level dangerous-access product scenarios under the no-trash guardrails in `safety-and-isolation.md` and `host-profiles.md`. Cover the full dangerous-access matrix unless the goal explicitly asks for a narrower pass.

`HOST_PROFILE=hardened` does not by itself enable real Trello, real Codex, or real GitHub. It only says the host may be used for run-scoped dangerous access-mode tests. Combine it with `do a real live bugbash without fakes` or `REAL_INTEGRATIONS=all` when real external services are intended.

If the goal says both `hardened host` and `no dangerous host access`, the explicit safer instruction wins unless a key-value setting explicitly sets `HOST_PROFILE=hardened`.

## Host profile values

### `HOST_PROFILE=standard`

Use conservative host behavior.

Rules:

- Exercise path-scoped access modes normally.
- Test dangerous access generation, validation, workflow output, CLI option parsing, and source-level behavior where possible.
- Do not launch a child Codex process with `--sandbox danger-full-access`, `--dangerously-bypass-approvals-and-sandbox`, or `--yolo` on the host.
- Do not run `setup-local --danger-full-access` or `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true` in a way that can grant broad host access. Use dry-run, fake-mode, run-scoped paths, or source-level inspection instead.
- If a coverage row requires real host dangerous access, mark it `requires_hardened_host` and continue.

### `HOST_PROFILE=hardened`

The operator has said the host is hardened for this run. This unlocks real host-level access-mode testing, but only inside run-scoped guardrails.

Allowed:

- `setup-local --allow-all-paths` when the effective target is run-owned or the test asserts broad access without mutating unrelated files.
- `setup-local --danger-full-access` with isolated HOME, XDG, SYMPHONY, prefix, state, cache, config, manifest, env, log, and workspace paths.
- `SYMPHONY_CODEX_DANGER_FULL_ACCESS=true` for run-scoped service scenarios.
- Workflow-authored `dangerFullAccess` for run-scoped workspaces.
- Direct Codex `--sandbox danger-full-access --ask-for-approval never` when `CODEX_MODE=real` and the installed CLI supports it.
- Direct Codex `--dangerously-bypass-approvals-and-sandbox` or `--yolo` when `CODEX_MODE=real` and the installed CLI exposes it.

Still forbidden:

- Wiping, stress testing, credential scanning, global reconfiguration, unrelated host writes, broad deletes, broad chmod or chown, shell profile edits, package-manager cleanup, service-manager changes, killing unrelated processes, or reading unrelated private data.
- Using host danger access as a shortcut for convenience when a path-scoped mode would test the intended product behavior more directly.

For every hardened-host scenario, record the exact command, the reason danger mode was needed, the run-owned paths used, the expected boundary, the actual boundary, and the cleanup result.

## Mode values

### `TRELLO_MODE=fake`

Use local fake Trello behavior only.

Preferred approaches, in order:

1. Reuse repository test support such as `src/test/java/.../testsupport/FakeTrelloServer.java` when available.
2. Start a run-local HTTP server under `RUN_ROOT/fakes/trello` that implements only the Trello REST routes needed for the scenario.
3. Use existing tests and test fixtures that already exercise fake Trello behavior.
4. Use source inspection and focused local tests when a full fake route is not practical.

Rules:

- Do not use real Trello API keys or tokens.
- Do not call `https://api.trello.com`.
- Use synthetic credentials such as `fake-key` and `fake-token`.
- Configure workflow `tracker.endpoint` to the fake server endpoint.
- Record fake routes, requests, responses, and gaps under `RUN_ROOT/snapshots/fake-trello/`.
- If a scenario depends on a Trello behavior that the fake cannot model, mark that row `needs-real-confirmation` rather than silently switching to real Trello.

### `TRELLO_MODE=real`

Use real Trello credentials and real Trello API only for run-scoped disposable boards and cards.

Rules:

- Snapshot visible boards before mutation.
- Create only boards whose names include `RUN_ID`.
- Register every created board in `created-trello-boards.jsonl` immediately.
- Register important cards in `created-trello-cards.jsonl` immediately.
- Archive only board IDs listed in `created-trello-boards.jsonl`.
- Do not modify unrelated boards, account settings, Workspace settings, membership, billing, Power-Up configuration, API keys, or tokens.

### `CODEX_MODE=fake`

Use local fake Codex behavior only.

Preferred approaches, in order:

1. Reuse fake app-server patterns from current repository tests such as `CodexAppServerClientTest`.
2. Create a run-local fake app-server script under `RUN_ROOT/fakes/codex/` and set workflow `codex.command` to that script.
3. Use tests or source inspection when the protocol surface is too large for a small fake.

Rules:

- Do not launch real `codex app-server`.
- Do not call the real model.
- Do not use Codex auth files.
- The fake may return canned JSON-RPC responses, simulated usage, simulated errors, simulated tool calls, slow responses, exits, stalls, and cancellation behavior.
- Record fake app-server scripts, transcripts, stdin/stdout/stderr, and protocol gaps under `RUN_ROOT/snapshots/fake-codex/`.
- Access-mode generation can still be tested in fake Codex mode by inspecting generated workflow config, command construction, sandbox payloads, and setup output.
- Direct Codex `--sandbox danger-full-access`, `--dangerously-bypass-approvals-and-sandbox`, or `--yolo` execution requires `CODEX_MODE=real` and `HOST_PROFILE=hardened`.

### `CODEX_MODE=real`

Use the installed Codex CLI and real `codex app-server`.

Rules:

- Capture `codex --version` and relevant `codex --help` output.
- Use run-scoped workspaces.
- Do not rely on broad host state.
- When testing `danger-full-access`, `--dangerously-bypass-approvals-and-sandbox`, or `--yolo`, require `HOST_PROFILE=hardened` and use only run-owned files and directories.
- Do not ask real Codex to wipe, stress, credential-scan, globally reconfigure, kill unrelated processes, or inspect unrelated private data.

### `GITHUB_MODE=fake`

Use local-only GitHub substitutes.

Preferred approaches, in order:

1. Use local Git repositories and local bare remotes under `RUN_ROOT/fakes/github/`.
2. Use a run-local `gh` wrapper placed first on `PATH` that records calls and returns canned responses for the small subset of `gh` behavior needed by a scenario.
3. Use source-level inspection and local issue drafts when a real GitHub API behavior cannot be faked.

Rules:

- Do not create real GitHub repositories.
- Do not use `gh` against the network.
- Do not write to real GitHub.
- Do not read private GitHub data unless the operator explicitly opted into real GitHub.
- Generated PR flows may be simulated with local branches, local remotes, fake `gh` output, and local draft artifacts.
- If a scenario requires behavior that cannot be faked, record a scoped `fake-github-gap` and continue.

### `GITHUB_MODE=real-sandbox`

Use real GitHub only inside newly created private sandbox repositories.

Rules:

- Repository names must start with `GITHUB_SANDBOX_PREFIX` and include `RUN_ID`.
- Register every created sandbox repository in `created-github-sandbox-repos.jsonl` immediately.
- Writes are allowed only inside those new private sandbox repositories.
- Never modify existing repositories, existing issues, existing pull requests, settings, secrets, collaborators, billing, workflows in existing repos, releases, or organization settings.
- Do not delete sandbox repositories unless the operator explicitly asks for deletion in the same run.
- If author identity or verified email blocks commits or PRs, configure author identity only in the sandbox clone. If it still fails, record a scoped GitHub blocker and continue with non-GitHub or fake-GitHub paths.

## Reporting mode confidence

Every coverage row and issue draft must record the integration profile and host profile used:

```yaml
integration_profile:
  trello: fake | real
  codex: fake | real
  github: fake | real-sandbox
host_profile: standard | hardened
real_integration_opt_in: <none | explicit parameter | natural language phrase>
hardened_host_opt_in: <none | explicit parameter | natural language phrase>
needs_real_confirmation: true | false
needs_hardened_host_confirmation: true | false
```

A bug found in fake mode can still be a confirmed product bug when the failing behavior is deterministic local logic, generated configuration, command handling, installer behavior, parsing, validation, prompt rendering, or safety-boundary logic. If the bug depends on external-service behavior that the fake might not faithfully model, mark it as `needs_real_confirmation: true` and do not overstate it as a real-service reproduction. If the bug depends on a danger-mode effect that was only inspected or simulated under `HOST_PROFILE=standard`, mark it as `needs_hardened_host_confirmation: true`.
