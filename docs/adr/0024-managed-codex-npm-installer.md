---
status: accepted
date: 2026-05-11
decision-makers: [François Martin, Codex]
consulted: [SPEC.md, install.sh, install.ps1, docs/adr/0022-plan-b-local-onboarding.md]
informed: [Future maintainers]
---

# Install Codex CLI With Managed Npm Steps

## Context and Problem Statement

The local installer can reach a first-run state where Codex CLI is missing. Codex's normal
installation guidance uses npm, and many users recognize `npm install -g @openai/codex`. Symphony
for Trello also needs installer behavior that is predictable, reversible, and consistent across
Bash and PowerShell wrappers.

How should the installer offer Codex CLI installation without scaring users with a long shell
pipeline or modifying a user's global npm setup?

## Decision Drivers

* Keep installer-managed files easy to uninstall.
* Avoid requiring `sudo npm install -g` or modifying the user's global npm prefix.
* Keep generated wrappers able to find the installed Codex CLI consistently.
* Make the first-run prompt readable for users who are not shell experts.
* Keep Bash and PowerShell installer behavior aligned.
* Avoid exposing unsupported Codex install modes before the project opens source.

## Considered Options

* Install Codex CLI with `npm install -g @openai/codex`.
* Install Codex CLI into a Symphony-managed npm prefix and show one long combined shell command.
* Install Codex CLI into a Symphony-managed npm prefix and show readable install steps.
* Support a user-provided prebuilt Codex binary path or URL.

## Decision Outcome

Chosen option: "Install Codex CLI into a Symphony-managed npm prefix and show readable install
steps", because it keeps installer-managed state reversible without presenting users with a single
long command line before they decide.

The installer uses npm's `--prefix` option to install Codex CLI under the installer-selected managed
npm prefix. Explicit `SYMPHONY_HOME` installs keep the historical `$SYMPHONY_HOME/npm` location; the
normal POSIX XDG layout and MicroOS `/var` layout use the selected cache directory. The installer
then creates a command link in the configured local command directory so the wrapper and the user's
shell can find `codex`. The prompt uses short labels for the managed install location, command link,
and Codex CLI install command, and states that system-wide npm packages are unchanged.

When npm is already available, the POSIX prompt includes:

```text
Codex CLI is missing.
Install Codex CLI with Symphony-managed npm.
  Location: $HOME/.cache/symphony-trello/npm
  Command: $HOME/.local/bin/codex
This keeps system-wide npm packages unchanged.
  Codex CLI: npm install --global --prefix '$HOME/.cache/symphony-trello/npm' @openai/codex
Run now? [y/N]
```

When npm is missing, the installer first prints the platform package-manager command for Node.js
with npm, then prints the same managed Codex npm command. On apt-based systems, the installer tracks
whether `apt-get update` already ran in the current installer process. The first accepted apt
install includes `apt-get update`; later apt installs omit it.

The installer does not support `SYMPHONY_TRELLO_CODEX_PREBUILT` or
`SYMPHONY_TRELLO_CODEX_PREBUILT_URL`. Those escape hatches were removed because they are not part of
the normal Codex CLI install path and made the first-run decision matrix harder to explain.

### Consequences

* Good, because uninstall can remove the managed npm tree and command link without touching the
  user's system-wide npm packages.
* Good, because users see a recognizable npm command instead of a chained shell pipeline.
* Good, because Bash and PowerShell prompts describe the same install model.
* Good, because apt-based installs avoid repeated package metadata refreshes in one installer run.
* Bad, because the command is longer than `npm install -g @openai/codex`.
* Bad, because users who prefer global npm installs must install Codex CLI themselves before running
  the Symphony installer.
* Bad, because removing the prebuilt-binary escape hatch narrows installer flexibility for unusual
  environments.

### Confirmation

Run `./mvnw -q -Dtest=InstallerScriptTest test` to verify the installer prompt, npm fallback, and
apt update behavior. Run `bash -n install.sh uninstall.sh`, `shellcheck install.sh uninstall.sh`,
and `shfmt -d install.sh uninstall.sh` for POSIX scripts. Run the repository PowerShell Docker
checks when native `pwsh` is unavailable:

```bash
./scripts/pwsh-docker.sh -NoProfile -File ./install.ps1 --dry-run --no-onboard
./scripts/pwsh-docker.sh -NoProfile -File ./uninstall.ps1 --dry-run --yes
```

## Pros and Cons of the Options

### Install Codex CLI With `npm install -g @openai/codex`

Offer the stock global npm install command and let npm pick the install prefix from the user's own
npm configuration.

* Good, because it is the shortest and most familiar npm command.
* Good, because it matches the common Codex CLI installation shape users may already know.
* Bad, because the target prefix depends on the user's npm configuration.
* Bad, because it may require elevated permissions.
* Bad, because uninstall cannot reliably know whether the global package belongs to Symphony.
* Bad, because generated wrappers cannot assume the global npm bin directory is on `PATH`.

### Install Into A Managed Prefix And Show One Long Combined Shell Command

Install Codex into a Symphony-managed npm prefix, but present the whole installation (package
manager work, npm install, command linking) as a single combined shell command that the user
approves once.

* Good, because it prints the exact work the installer will perform.
* Good, because it can combine Node.js/npm installation, Codex installation, and command linking into
  one accepted action.
* Bad, because the combined command is long enough to look risky or unreadable.
* Bad, because the command mixes package-manager work, npm work, and filesystem linking in one line.

### Install Into A Managed Prefix And Show Readable Install Steps

Install Codex into the same Symphony-managed npm prefix, presenting the work as separate labeled
steps - package-manager installation, `npm --prefix` installation, and command linking - that the
user reviews and approves.

* Good, because each step has a clear purpose.
* Good, because users still see the exact package-manager and npm commands before approving.
* Good, because the installer can run the same steps without global npm side effects.
* Good, because the prompt explains why `--prefix` is used.
* Bad, because the prompt prints more lines than a single command.

### Support A User-Provided Prebuilt Codex Binary Path Or URL

Let the user point the installer at an existing Codex binary path or a download URL instead of
installing Codex through npm.

* Good, because it could help constrained environments that cannot use npm.
* Bad, because it is not a normal Codex CLI installation path.
* Bad, because it requires extra Bash and PowerShell code for a path users are unlikely to need.
* Bad, because it makes the first-run output and tests more complex.

## More Information

This decision refines the installer part of
[ADR 0022](0022-plan-b-local-onboarding.md). The broader Plan B local onboarding model is unchanged:
scripts bootstrap the environment and delegate product setup to Java.
