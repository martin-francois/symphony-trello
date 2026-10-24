---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #52](https://github.com/martin-francois/symphony-trello/issues/52)"
  - "[JDK-8351435: Change the default Console implementation back to the built-in one in java.base](https://bugs.openjdk.org/browse/JDK-8351435)"
  - "[JDK-8308591: JLine as the default Console provider](https://bugs.openjdk.org/browse/JDK-8308591)"
  - "[JDK-8361911: System.console() should only be available for interactive terminal](https://bugs.openjdk.org/browse/JDK-8361911)"
  - "[JLine 3](https://github.com/jline/jline3)"
informed: [Future maintainers, Contributors]
---

# Use The JDK Line-Editing Console For Setup Prompts

## Context and Problem Statement

Guided setup asks questions such as `Additional paths, comma-separated:` and `Trello token:`. Before
this decision, `StreamTerminal` printed the prompt and read the answer with a `BufferedReader` over
standard input. The terminal stayed in its normal line mode, so it did not know about arrow keys.
Pressing the left arrow key printed `^[[D`, and the escape bytes became part of the answer. The
token prompt used `Console.readPassword`, which already hid the input.

[GitHub issue #52](https://github.com/martin-francois/symphony-trello/issues/52) asks for normal
command-line editing in interactive prompts, hidden secrets, and unchanged non-interactive setup.

The JDK contains its own copy of JLine in the `jdk.internal.le` module. JDK 20 and 21 offered it as
an opt-in `System.console()` implementation, JDK 22 made it the default, and JDK 25 made it opt-in
again with the system property `jdk.console=jdk.internal.le`
([JDK-8351435](https://bugs.openjdk.org/browse/JDK-8351435)). Setup requires a Java 25 or newer
JDK, so every supported install has this module.

## Decision Drivers

* Left and right arrow keys, Home, End, and Backspace work in every setup prompt on every platform
  that setup supports: Linux, macOS, and Windows.
* Secret prompts stay hidden, and a typed secret never comes back through input history.
* Piped or redirected setup input keeps its current behavior.
* The change lives in the one prompt-reading class, so prompts added later get it without extra
  work.
* No new third-party dependency without a clear benefit. Each dependency needs Renovate ownership,
  the seven-day release-age rule, and an entry in
  [Dependency upgrade confidence](../testing/dependency-upgrade-confidence.md).
* Ctrl+C at a prompt still stops setup.

## Considered Options

* Select the JDK's JLine-based console provider at startup and read interactive prompts through
  `System.console()`
* Add JLine 3 as a Maven dependency and build a `LineReader` for setup prompts
* Keep plain `BufferedReader` prompts

## Decision Outcome

Chosen option: "Select the JDK's JLine-based console provider at startup and read interactive
prompts through `System.console()`", because it gives the requested editing on all platforms the
JDK supports, keeps `readPassword` hidden, and adds no dependency.

`TrelloBoardSetupMain.main` calls `SystemConsole.enableLineEditing()` before anything touches
`System.console()`. That method sets `jdk.console=jdk.internal.le` only when the launch did not set
`jdk.console` already. `StreamTerminal` reads a prompt through the console when
`SystemConsole.current()` returns one, and through the injected `BufferedReader` otherwise.
`SystemConsole.current()` returns a console only when `Console.isTerminal()` is true, so redirected
input keeps the plain path even on JDK builds that return a console for redirected streams.

While the line editor reads a line, it handles the interrupt signal itself. Ctrl+C then makes the
JDK editor throw its internal `UserInterruptException` instead of ending the JVM. `StreamTerminal`
catches that exception by class name and exits with status 130, the same status the interrupt
signal produced with plain prompts. Shutdown hooks still run, and setup prints no stack trace or
failure report. The terminal still sends the signal to the whole foreground process group, so a
calling installer script sees the same signal and the same exit status as before. This was checked
on Linux.

The change was tested on Linux only. The JDK's provider has its own Windows console support, which
is how `jshell` edits lines on Windows, but nobody has run setup prompts through it on Windows or
macOS yet.

### Consequences

* Good, because every prompt that goes through `Terminal.readLine` or `Terminal.readSecret` gets
  editing, including prompts added by later changes.
* Good, because the token prompt stays hidden. The JDK editor does not add masked input to its
  history, so the up arrow recalls earlier plain answers but never a secret.
* Good, because the repository adds no dependency, no Renovate rule, and no native library.
* Good, because an operator who wants plain prompts can launch Java with `-Djdk.console=java.base`,
  for example through `JAVA_TOOL_OPTIONS`.
* Bad, because the provider lives in an internal JDK module. The JDK maintainers said in
  [JDK-8351435](https://bugs.openjdk.org/browse/JDK-8351435) that this console was hard to maintain.
  A future JDK could remove it. If it does, `System.console()` falls back to the `java.base` console
  and prompts lose editing but keep working.
* Bad, because the Ctrl+C handling matches the internal exception by its simple class name. A
  renamed class would turn Ctrl+C into a stack trace with exit status 1 until the name is updated.
* Bad, because the line editor writes terminal control sequences around each prompt, such as the
  bracketed-paste switch. Terminal transcripts of interactive sessions contain them.
* Neutral, because terminals in application cursor-key mode send `ESC O D` for the left arrow. The
  editor turns that mode on itself, so real terminals behave correctly; tests that type raw bytes
  must send the application-mode sequences.

### Confirmation

* `StreamTerminalLineEditingTest` starts a child JVM in a pseudo-terminal through util-linux
  `script`. It edits the first answer with the left and right arrow keys, Home, End, and Backspace
  and expects `/tmp/abc`. It types a token and expects the transcript not to contain it, and presses
  the up arrow at the next prompt and expects the earlier plain answer. It presses Ctrl+C at a plain
  prompt and at the token prompt and expects exit status 130 without an exception. It also
  redirects input from a file and expects the escape bytes to stay in the answer with no
  line-editor control output.
* The test needs util-linux `script`, so it runs on Linux and is skipped on macOS and Windows.
* Before this decision the editing case typed `/tmp/ac`, the left arrow key, and `b` and got
  `path=[/tmp/ac<ESC>ODb]` instead of `/tmp/abc`.
* When a JDK upgrade lands, run `StreamTerminalLineEditingTest`. A failure in the editing case means
  the provider changed or was removed; revisit this ADR and the JLine 3 option. A failure in the
  Ctrl+C case means the editor's interrupt exception changed its name.

## Pros and Cons of the Options

### Select The JDK's JLine-Based Console Provider At Startup And Read Interactive Prompts Through `System.console()`

Setup sets the `jdk.console` system property to `jdk.internal.le` before the first
`System.console()` call and reads interactive answers with `Console.readLine` and
`Console.readPassword`. Piped input still uses the `BufferedReader`.

* Good, because it needs no dependency and is present in every Java 25 JDK. The provider includes
  the JDK's own Windows console support.
* Good, because the JDK maintains the terminal handling together with the runtime.
* Good, because a launch flag restores plain prompts.
* Bad, because the provider is internal and opt-in, and its future is not guaranteed.
* Bad, because Ctrl+C needs special handling.

### Add JLine 3 As A Maven Dependency And Build A `LineReader` For Setup Prompts

The setup CLI would depend on `org.jline:jline-reader` and `org.jline:jline-terminal` plus a native
terminal provider (FFM or JNI) and create its own `Terminal` and `LineReader`.

* Good, because JLine 3 is a public, maintained library with documented options for history,
  completion, and key bindings.
* Good, because the application controls Ctrl+C handling through a documented exception type.
* Bad, because it adds several artifacts with native code to the release archive, each needing
  Renovate ownership, the release-age rule, and upgrade-confidence evidence.
* Bad, because the FFM provider prints a restricted-method warning on Java 25 unless every launcher
  passes `--enable-native-access`, and the JNI provider ships native libraries per platform.
* Bad, because the features beyond basic editing, such as completion, are not needed for setup.

### Keep Plain `BufferedReader` Prompts

Setup would keep printing the prompt and reading a line from standard input in the terminal's
normal line mode.

* Good, because it has no new behavior to maintain.
* Bad, because arrow keys keep inserting escape sequences into answers, which is the problem the
  issue reports.

## More Information

[GitHub PR #796](https://github.com/martin-francois/symphony-trello/pull/796) adds the Codex
model picker prompts and
[GitHub PR #813](https://github.com/martin-francois/symphony-trello/pull/813) adds the Codex
investigation consent prompt. Both read answers through `Terminal.readLine`, so they use line
editing without changes of their own.

The [Java style guide](../agents/java-style.md) forbids using non-exported JDK internals such as
`jdk.internal.*` packages and adding `--add-exports` or `--add-opens` flags. This decision does not
import, call, or open any internal type. It selects a console provider by the module name that the
JDK documents for the `jdk.console` property and uses only the public `java.io.Console` API. The
Ctrl+C handling compares the simple name of a thrown exception and does not depend on its type.

Revisit the JLine 3 option if the JDK removes the `jdk.internal.le` console provider.
