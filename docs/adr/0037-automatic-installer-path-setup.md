---
status: accepted
date: 2026-05-30
decision-makers: [Francois Martin, Codex]
consulted:
  - "[GitHub issue #110](https://github.com/martin-francois/symphony-trello/issues/110)"
  - "[GitHub PR #122](https://github.com/martin-francois/symphony-trello/pull/122)"
  - "OpenClaw installer behavior"
  - "Hermes installer behavior"
  - "README.md"
  - "install.sh"
  - "install.ps1"
informed: [Future maintainers]
---

# Prefer Automatic Installer PATH Setup

## Context and Problem Statement

The installer should make the `symphony-trello` command usable after installation. Before this
decision, a user could install successfully but still need to run the binary through its full path
when their shell did not include the install directory in `PATH`.

The first-time install path should stay smooth. The user should not need to understand shell profile
files, Windows user PATH entries, or internal install paths before they can run the command.

Should the installer ask before changing `PATH`, change `PATH` automatically, or only print manual
instructions?

## Decision Drivers

* The default installer path should be easy for new users.
* The installer should avoid prompts that ask users to make low-value technical decisions.
* POSIX and PowerShell installer behavior should be consistent where practical.
* Managed environments need a way to opt out of PATH changes.
* PATH update failures should not fail an otherwise successful install.
* POSIX installer code must support macOS Bash 3.2.
* New shell support should be added only when requested or tracked in an issue.
* Installer options should not include positive flags that only restate default behavior.

## Considered Options

* Ask before editing shell profile files or the Windows current-user PATH.
* Update PATH automatically by default and provide `--no-update-path`.
* Never update PATH and only print manual instructions.
* Add broader shell support, such as fish shell support, in the same change.

## Decision Outcome

Chosen option: "Update PATH automatically by default and provide `--no-update-path`".

The POSIX installer updates supported shell profile files automatically. The PowerShell installer
updates the Windows current-user PATH automatically. Both installers keep `--no-update-path` for
users or automation that manage PATH themselves.

The installers do not expose a positive `--update-path` flag. That flag would duplicate default
behavior and make the option list look more complex without changing the common install path.
Future installer flags should follow the same rule: add an opt-out for default behavior when needed,
but do not add a positive flag unless it selects a distinct behavior.

If PATH setup cannot be written, installation continues and the installer prints manual instructions.
The installer does not add fish shell support in this change. A user can request that separately if
they need it.

This follows the smooth installer behavior used by OpenClaw and Hermes. Those installers do not ask
for a separate PATH prompt in the common path. More explicit prompting was considered, but it would
add a decision that most first-time users do not need.

### Consequences

* Good, because new users can run `symphony-trello` by name in a new terminal after install.
* Good, because the installer has fewer prompts and less setup text.
* Good, because automation and managed hosts can opt out with `--no-update-path`.
* Good, because PATH write failures degrade to manual instructions instead of aborting install.
* Bad, because the installer edits shell profile files or current-user PATH by default.
* Bad, because unsupported shells still require manual PATH setup.

### Confirmation

Installer tests cover automatic PATH setup, opt-out behavior, dry-run output, and install lifecycle
ordering.

Local validation for this change included:

* `bash -n install.sh uninstall.sh`
* `shfmt -d install.sh uninstall.sh`
* `shellcheck install.sh uninstall.sh`
* PowerShell install dry-run through `./scripts/pwsh-docker.sh`
* `./mvnw -q -Dtest=InstallerScriptTest,InstallerScriptLifecycleTest test`
* `./mvnw -q spotless:check verify`

## Pros and Cons of the Options

### Ask before editing PATH

Prompt during installation and edit shell profile files or the Windows user `PATH` only after the
user explicitly agrees.

* Good, because the user sees the profile or PATH change before it happens.
* Bad, because it adds another prompt during first-time setup.
* Bad, because many users do not know how to evaluate the choice.
* Bad, because it diverges from the smoother OpenClaw and Hermes installer behavior.

### Update PATH automatically by default and provide `--no-update-path`

Edit the managed `PATH` block in the relevant shell profiles (or the Windows user `PATH`) by
default during install, offer `--no-update-path` as the opt-out, and print the manual instructions
whenever the automatic update is skipped or fails.

* Good, because it optimizes for the common install path.
* Good, because users still have an explicit opt-out for managed environments.
* Good, because the installer can keep the next command simple.
* Bad, because the default install writes to profile files or current-user PATH.

### Never update PATH

Only print manual `PATH` setup instructions and never edit profile files.

* Good, because the installer avoids shell profile and PATH writes.
* Bad, because users must keep using a full binary path or edit PATH themselves.
* Bad, because the install can appear incomplete even after success.

### Add broader shell support in the same change

Extend the automatic profile editing to additional shells beyond the defaults in the same change.

* Good, because more users would get automatic PATH setup.
* Bad, because it expands the scope beyond the current installer need.
* Bad, because unsupported shell behavior can be tracked and tested in a focused follow-up issue.

## More Information

* [GitHub issue #110](https://github.com/martin-francois/symphony-trello/issues/110)
* [GitHub PR #122](https://github.com/martin-francois/symphony-trello/pull/122)
