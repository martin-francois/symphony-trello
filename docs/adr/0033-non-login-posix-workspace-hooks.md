---
status: accepted
date: 2026-05-29
decision-makers: [Francois Martin, Codex]
consulted: [SPEC.md, HookRunner.java, WorkspaceManagerTest, LocalAgentRunnerTest, GitHub Actions CI]
informed: [Future maintainers]
---

# Run POSIX Workspace Hooks With a Non-Login Shell

## Context and Problem Statement

Workspace hooks run configured shell snippets such as `after_create`, `before_run`, and `after_run`.
They must run with the per-card workspace as the current working directory.

The implementation used `bash -lc <script>`. Hosted CI showed that hooks reported success but wrote
files outside the expected workspace. The affected tests failed because files such as `before.txt`
and `created.txt` were missing from the workspace.

Which POSIX shell mode should run workspace hooks?

## Decision Drivers

* Hooks must run in the workspace directory selected by Symphony.
* Hook behavior should not depend on host-specific login shell startup files.
* CI and local runs should execute hook snippets with the same directory semantics.
* Users can still write explicit setup in the hook script when they need extra environment setup.

## Considered Options

* Keep `bash -lc <script>`.
* Use `bash -c <script>`.
* Invoke hook scripts directly without a shell.

## Decision Outcome

Chosen option: "Use `bash -c <script>`".

The POSIX hook runner now uses a non-login Bash shell. The configured workspace directory remains the
process working directory, and login shell profile files do not get a chance to change it.

### Consequences

* Good, because hooks write relative files into the workspace reliably.
* Good, because tests do not depend on CI runner login-shell behavior.
* Bad, because hooks that relied on login profile setup must make that setup explicit in the hook
  script or service environment.

### Confirmation

`HookRunnerTest.requiredHookRunsInWorkspaceWithNonLoginShell` verifies that hooks run in the
configured workspace and that the shell is not a login shell.

## Pros and Cons of the Options

### Keep `bash -lc <script>`

* Good, because login profile setup may expose user tools without extra hook text.
* Bad, because profile startup can make hook behavior depend on host shell configuration.
* Bad, because hosted CI showed relative hook output could land outside the workspace.

### Use `bash -c <script>`

* Good, because it keeps shell features while preserving the configured working directory.
* Good, because it avoids hidden login profile side effects.
* Bad, because workflows that need profile-managed tools must source setup explicitly.

### Invoke hook scripts directly without a shell

* Good, because it avoids shell startup behavior entirely.
* Bad, because current hook fields are shell snippets, not executable paths with argument arrays.
* Bad, because it would break existing workflow snippets that use shell syntax.

## More Information

* [GitHub issue #110](https://github.com/martin-francois/symphony-trello/issues/110)
