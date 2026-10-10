---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #551](https://github.com/martin-francois/symphony-trello/issues/551)"
  - "[ADR 0022](0022-plan-b-local-onboarding.md)"
informed: [Future maintainers, Contributors]
---

# Phased installer progress output

## Context and Problem Statement

Before this decision, `install.sh` and `install.ps1` printed one flat stream on every run: the
detected platform, five paths, the source, prerequisite checks, a `RUN` line for each low-level
command such as `mkdir -p`, `git fetch`, `tar -xzf`, and `mv`, Git's own progress messages, setup
output, service-manager output, and finally the Java-owned handoff from
[ADR 0022](0022-plan-b-local-onboarding.md). Some facts appeared twice. The command path was in
the header, in `OK  Command installed: PATH`, and again under a `Command PATH setup` heading.
Reruns printed `PATH setup already exists` for every profile.

On a routine install or update the user could not tell which part of the run they were in, what
had finished, or what to do when a step failed.

How should the installers structure their default output so that a successful run is short, a
failed run says where it stopped and what to do next, and both installers stay aligned?

## Decision Drivers

* A routine install or update should fit on one screen before the Java handoff.
* Commands the user must approve, and prompts, must stay visible with the exact command.
* A failure must name where the run stopped and give a recovery step.
* Dry-run must name the same work as a real run without claiming success.
* `install.sh` and `install.ps1` must express the same milestones with the same words.
* The Java handoff stays the last block and keeps its single owner (ADR 0022).
* Output must not depend on color, animation, emoji, or Unicode symbols, and must stay readable
  when redirected.
* Options, prompts, exit codes, installed files, install context, and stream use stay unchanged.

## Considered Options

* Plan, numbered phases, and quiet routine commands.
* Delete selected lines only.
* Add a quiet flag and keep the verbose default.
* Hide all child-process output.
* Capture routine child output and replay it only on failure.
* Always print the same four phases and mark skipped ones.

## Decision Outcome

Chosen option: "Plan, numbered phases, and quiet routine commands", because it is the only option
that shortens the normal success path in both installers without hiding approvals, prompts, or
failure output, and without adding a second output mode.

Both installers now print, in this order:

1. `Symphony for Trello installer` and the detected platform.
2. One plan headed `Install plan` or `Update plan`. An existing app directory makes the run an
   update. The plan names the source (`release archive` with version and asset URL, or
   `Git checkout` with repository and ref) and the app, config, workspace, state/log, and command
   paths, each once. Credentials in repository URLs stay redacted.
3. Numbered phases in the form `[n/N] Title`. The plan fixes `N` before the first phase:
   * `Checking prerequisites`, which also covers prerequisite installs and Codex login.
   * `Installing Symphony` or `Updating Symphony`, which covers stopping managed workers, the
     checkout or release archive, the command wrapper, install context, and PATH setup.
   * `Running setup`, only when onboarding runs.
   * `Starting managed workers` or `Restarting managed workers`, when onboarding runs, or when
     `--no-onboard` updates an install that had managed workers running.
4. With onboarding, the Java handoff from ADR 0022 as the last block. With `--no-onboard`, one
   `Symphony for Trello installed.` or `Symphony for Trello updated.` line and one `Next step:`
   command. After a worker restart that is `status`. When the config directory already has a
   connected-board manifest it is `start --all`, and otherwise `setup-local`. The command is
   `symphony-trello` when the command directory was already on `PATH`, and the quoted command path
   otherwise.

Inside a phase each milestone prints one result line such as `  OK  Command installed`.
Routine commands such as `mkdir`, `rm`, `mv`, `tar`, `curl`, `git`, the Maven wrapper build, and
the completion-only Java call no longer print a `RUN` line. Git runs with `-q` so a successful
checkout prints nothing. When a routine command fails, the installer prints
`Command failed with exit code N: COMMAND` with the redacted label on stderr and keeps the exit
code. Commands the user approves or answers in the terminal still print `RUN  COMMAND` first:
package-manager installs, the Codex npm install, the MicroOS data-root command, `codex login`,
and the interactive `setup-local` run. A prerequisite install that succeeds prints one
`OK  NAME installed` line. Decisions that change existing files print a `NOTE` line first, such
as replacing a release-archive app with a Git checkout or changing the checkout's `origin`.

When the run exits with a non-zero status inside a phase, the installer prints
`Installer stopped during [n/N] Title.` and a recovery step for that phase on stderr, after the
error that caused the stop. POSIX does this in an `EXIT` trap, PowerShell in its existing `trap`.
Errors before the first phase, such as option validation, keep their plain output.

Dry-run prints the plan, `Dry run: no files changed.`, and the same phases with `WOULD` lines.
The POSIX dry-run now previews the managed-worker phase through the same service-manager
functions as a real run, as the PowerShell dry-run already did. Dry-run never prints `OK` result
lines for work it did not do.

The phase logic lives in both scripts, not in Java. The prerequisites phase installs Java itself,
and the install phase downloads or builds the Java app, so no Java code exists yet when most of
the transcript is printed. The two copies use the same titles, status words, and recovery text,
and `InstallerProgressOutputTest` checks the transcript of both. Its PowerShell tests run in the
Windows CI lane.

The output adds no color, cursor movement, spinner, emoji, or Unicode symbols. Output styling is
a separate decision.

### Consequences

* Good, because a routine guided update prints about 30 lines before the handoff, including
  the plan and Java's own setup lines, and every phase heading says what is running.
* Good, because each fact appears once, and reruns no longer repeat PATH setup that is already in
  place.
* Good, because a failure ends with the phase name and a next step even when the failing
  command printed only a terse error.
* Good, because POSIX and PowerShell print the same phase titles, result words, and dry-run
  structure.
* Good, because approval commands, prompts, warnings (`NOTE`), and child error output stay
  visible.
* Bad, because a successful run no longer shows each low-level command. Someone who wants that
  detail has to read the script or run `--dry-run`.
* Bad, because tests and any external tooling that matched old prose such as `Starting setup...`
  or `RUN  git clone` must change. Installer prose is not a supported machine-readable API.
* Bad, because Java setup output inside `Running setup` keeps its own format. Changing
  `setup-local` output is outside this decision.

### Confirmation

`InstallerProgressOutputTest` runs the installers end to end with test doubles and checks the
transcript order. It covers a guided source install and update, a release-archive install and
update with `--no-onboard`, the dry-run with and without onboarding, a release checksum failure,
a PowerShell dry-run, a PowerShell source install and update, and a PowerShell prerequisite
failure. The tests assert one plan, ordered phases, no `RUN` lines on the routine path, the
handoff once and last, and no escape characters in redirected output.
`InstallerScriptTest.installersOrderFinalHandoffAfterManagedWorkerSetupInSource` still guards the
handoff order in the script source. SPEC.md Section 19.4 states the contract.

## Pros and Cons of the Options

### Plan, numbered phases, and quiet routine commands

The installers print one plan and a fixed number of `[n/N]` phases with one result line per
milestone. Routine commands run silently on success and name themselves on failure. Approved and
interactive commands still print `RUN` first.

* Good, because it meets every decision driver without a new option.
* Good, because PowerShell can do the same thing without redirecting native command streams.
* Neutral, because it keeps child error output as the child printed it rather than reformatting
  it.
* Bad, because the phase count must be computed before the first phase, so the plan needs the
  update and managed-worker checks up front. Those checks only read files.

### Delete selected lines only

Remove the noisiest lines, such as the `RUN` lines and the duplicate command path, and keep the
flat structure.

* Good, because the diff is small.
* Bad, because the reader still cannot tell which part of the run is active or where a failure
  happened.
* Bad, because without a structure each later change decides on its own what to print.

### Add a quiet flag and keep the verbose default

Keep the old transcript as the default and add an option for short output.

* Good, because nothing changes for current users.
* Bad, because first-time users, who need the short output most, never pass the flag.
* Bad, because two output modes must be maintained and tested in both installers.

### Hide all child-process output

Send every child command's output to `/dev/null` or a log file and print only installer lines.

* Good, because the success path is as short as it can be.
* Bad, because approval commands, prompts, and failure details would disappear or move to a file
  the user has to find. The issue rejects this.

### Capture routine child output and replay it only on failure

Run routine commands with stdout and stderr captured to temporary files, discard them on
success, and print them on failure.

* Good, because it also hides chatty output from commands that have no quiet flag.
* Bad, because it is hard to do the same way in `install.ps1`. Windows PowerShell 5.1, which runs
  the public `irm ... | iex` one-liner, treats redirected native stderr as error records, and the
  script runs with `$ErrorActionPreference = "Stop"`. The two installers would then differ in
  the exact case where a user needs the details.
* Bad, because temporary capture files add cleanup paths, and a command that waits on input
  would look hung.
* Neutral, because `git -q`, `curl -fsSL`, `tar`, and `mvnw -q` are already quiet on success,
  so the remaining benefit is small.

### Always print the same four phases and mark skipped ones

Print `[1/4]` to `[4/4]` on every run and show `SKIP` for setup and worker phases under
`--no-onboard`.

* Good, because the phase list never changes.
* Bad, because a `--no-onboard` run would print two headings for work it never planned, which
  makes the transcript longer and less truthful.

## More Information

[GitHub issue #551](https://github.com/martin-francois/symphony-trello/issues/551) defines the
desired output. The handoff ordering it builds on comes from
[ADR 0022](0022-plan-b-local-onboarding.md). The uninstaller output and `setup-local` output are
out of scope for this decision.
