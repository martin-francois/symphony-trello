---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #788](https://github.com/martin-francois/symphony-trello/issues/788)"
  - "[CreateProcessW documentation](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw)"
  - "[cmd command reference](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/cmd)"
  - "[Rust security advisory CVE-2024-24576](https://blog.rust-lang.org/2024/04/09/cve-2024-24576/)"
informed: [Future maintainers, Contributors]
---

# Start Windows batch shims through cmd.exe

## Context and Problem Statement

The Windows installer can install Codex CLI with npm. npm writes `codex.cmd`, `codex.ps1`, and an
extensionless shell script. There is no `codex.exe`. PowerShell and `cmd.exe` find `codex.cmd`
through `PATH` and `PATHEXT`.

Java's `ProcessBuilder` on Windows passes a bare name such as `codex` to `CreateProcess`. The
`CreateProcess` documentation says it appends `.exe` to a name without an extension, so it does not
find `codex.cmd`. `setup-local` starts `codex --version`, `codex login status`, the interactive
`codex login`, and `codex app-server` by bare name. On a host where Codex exists only as the npm
shim, setup reports Codex as missing, and the login step cannot start, even though `codex` works in
PowerShell.

Setup diagnostics already searched `PATH` with `PATHEXT` and wrapped batch files in `cmd.exe`. The
other launches did not use that code.

How should Symphony start a tool that resolves to a `.cmd` or `.bat` file on Windows?

## Decision Drivers

* An npm-installed Codex must work on native Windows without extra user steps.
* Arguments must reach the tool unchanged, or the launch must fail. A silently changed argument is
  worse than a failure.
* One place in the code owns Windows command lookup and batch quoting.
* Linux and macOS launches must not change.
* Launches that already work on Windows, such as a native `.exe`, must not change.

## Considered Options

* Shared resolver that starts batch files through `cmd.exe /d /s /c` with checked quoting.
* Pass the resolved `.cmd` path straight to `ProcessBuilder`.
* Start Node with the script path read from the npm shim.
* Start the shim through PowerShell (`codex.ps1`).
* Require a native `codex.exe` on Windows.

## Decision Outcome

Chosen option: shared resolver that starts batch files through `cmd.exe /d /s /c` with checked
quoting.

`ExecutableResolver` in the `process` package searches each `PATH` directory for each `PATHEXT`
extension in order, as the Windows shell does. When the first match for the program, or the program
path itself, is a `.cmd` or `.bat` file, `launchCommand` returns
`cmd.exe /d /s /c ""<file>" "<arg>" ..."`, with `cmd.exe` taken from `ComSpec`. Otherwise it
returns the command unchanged. On other platforms it always returns the command unchanged.

Each argument is wrapped in double quotes. Trailing backslashes are doubled so the program does not
read the closing quote as a literal quote. `cmd.exe` still expands `%name%`, and with delayed
expansion `!name!`, inside double quotes, and a double quote or line break ends the quoted text. On
the `windows-powershell` CI runner (Windows Server 2025, Java 25), `cmd.exe` also split quoted
arguments at `&`, `|`, `<`, `>`, and `^`: the argument `"a&b"` ran `b"` as a second command. Java
passed the quoted argument through unchanged in that run, because its legacy argument handling does
not re-quote an argument that already starts and ends with a quote. The exact `cmd.exe` parsing step
that drops the quotes was not identified. `launchCommand` therefore throws an `IOException` for an
argument that contains `"`, `%`, `!`, `&`, `|`, `<`, `>`, `^`, a carriage return, or a line feed.
Callers already treat an `IOException` from `ProcessBuilder.start` as a launch failure, so the
refusal surfaces the same way.

The same CI run showed that spaces, `(`, `)`, `~`, `;`, `,`, `=`, `:`, `[`, `]`, `{`, `}`, `#`,
`$`, `'`, a trailing backslash, and an empty argument reach the program unchanged.

`ProcessCommandRunner` (setup prerequisite probes and the interactive `codex login`),
`CodexModelDefaultsResolver` (`codex app-server` for model defaults), and setup diagnostics use the
resolver. The worker's Codex launch runs `bash -lc` with the configured `codex.command`, so bash
resolves the command there and the worker does not use the resolver.

### Consequences

* Good, because `setup-local` finds and starts an npm-installed `codex.cmd` on native Windows.
* Good, because one class owns `PATH`/`PATHEXT` lookup and batch quoting. Diagnostics and launches
  can no longer disagree about whether Codex exists.
* Good, because Linux, macOS, and Windows `.exe` launches keep their exact previous command.
* Bad, because an argument with `"`, `%`, `!`, `&`, `|`, `<`, `>`, `^`, or a line break cannot reach
  a batch shim. This includes an executable path with one of these characters. Current callers pass
  fixed arguments, and the developer instructions planned for
  [GitHub PR #790](https://github.com/martin-francois/symphony-trello/pull/790) already limit
  themselves to letters, digits, spaces, and `-_.,:/+@`.
* Neutral, because the tool runs as a child of `cmd.exe`. When a setup probe times out,
  `ProcessCommandRunner` stops the started process and its descendants, so the tool does not
  outlive the probe.
* Good, because the wrapper starts the interpreter that `ComSpec` names when it is an absolute
  path. A bare `cmd.exe` would let `CreateProcess` pick up a copy from the current directory first.

### Confirmation

* `ExecutableResolverTest` checks the command built for a simulated Windows host: an npm shim found
  through `PATH`, the `ComSpec` interpreter, quoting of spaces, parentheses and other punctuation,
  trailing backslashes and empty arguments, refusal of the unsafe characters, a `.exe` that comes
  first in `PATHEXT`, a missing tool, and an unchanged command on POSIX. It runs on Linux in the
  `test` job and on Windows in the `windows-powershell` job.
* `ProcessCommandRunnerTest` checks that a refused argument becomes a launch failure and that a
  timed-out command's child processes stop too.
* `CodexModelDefaultsResolverTest` checks on a simulated Windows host that `codex app-server` starts
  through the `ComSpec` interpreter with the npm shim and its argument.
* `WindowsBatchShimLaunchTest` runs only on Windows, in the `windows-powershell` CI job. It writes a
  shim with npm's structure, shows that `ProcessBuilder` cannot start it by bare name from a JVM
  whose `PATH` contains only the shim directory, and shows that the resolver starts it. It also
  checks that `run` passes each argument from `WindowsShimFixtures.CMD_SAFE_ARGUMENTS`, and
  `runInteractive` its arguments, through the shim unchanged with the tool's exit status.

## Pros and Cons of the Options

### Shared resolver that starts batch files through `cmd.exe /d /s /c` with checked quoting

Symphony finds the file itself and builds the `cmd.exe` command line itself, quoting each argument
and refusing characters that `cmd.exe` would change.

* Good, because Symphony controls the full command line that `cmd.exe` parses.
* Good, because it reuses the lookup that diagnostics already had.
* Bad, because ten characters, listed above, cannot be passed.

### Pass the resolved `.cmd` path straight to `ProcessBuilder`

`ProcessBuilder` gets `C:\...\codex.cmd` as the program, and Windows starts it through `cmd.exe`
behind the scenes.

* Good, because it needs no wrapper code.
* Bad, because `cmd.exe` then parses a command line built with quoting rules made for normal
  programs. The Rust advisory CVE-2024-24576 describes how such arguments can change or inject
  commands. Symphony could not refuse or check what reaches `cmd.exe`.

### Start Node with the script path read from the npm shim

Symphony parses `codex.cmd`, finds the `codex.js` path inside it, and starts `node` directly.

* Good, because it avoids `cmd.exe` entirely.
* Bad, because it depends on the text layout of npm's shim and on the Codex package layout, which
  can change in any npm or Codex release.

### Start the shim through PowerShell (`codex.ps1`)

Symphony starts `powershell -File codex.ps1` with the arguments.

* Good, because PowerShell's argument passing does not expand `%`.
* Bad, because the execution policy can block the script, and PowerShell has its own quoting rules
  for native commands that differ between Windows PowerShell and PowerShell 7.
* Bad, because it adds noticeable startup time to every probe.

### Require a native `codex.exe` on Windows

Setup asks users to install a Codex build that ships an `.exe` on `PATH`.

* Good, because `ProcessBuilder` finds it without changes.
* Bad, because the installer's npm fallback, which the specification requires, would not work.

## More Information

[GitHub PR #790](https://github.com/martin-francois/symphony-trello/pull/790) adds the
`symphony-trello codex` command, which also starts `codex` by bare name. That command should build
its launch with `ExecutableResolver.launchCommand` so it gets the same Windows behavior, and the
Windows consequence in its ADR 0090 should point here.
