---
status: accepted
date: 2026-05-08
decision-makers: [François Martin, Codex]
consulted:
  - SPEC.md
  - "[GitHub issue #35](https://github.com/martin-francois/symphony-trello/issues/35)"
  - README.md
  - docs/adr/0005-trello-setup-and-local-configuration.md
informed: [Future maintainers]
---

# Add Plan B Local Onboarding With Java-Owned Setup Logic

## Context and Problem Statement

The project already had Java commands for Trello board creation and import, plus Ansible and systemd
deployment documentation. That was enough for maintainers, but too much for first-time users who
want a real local installation without learning Maven, Quarkus, Ansible, and workflow-file details
up front.

How should Symphony for Trello add a simpler OpenClaw-inspired install and onboarding path without
duplicating Trello, Codex, GitHub, board, and workflow decisions in shell scripts?

## Decision Drivers

* Keep Trello/workflow setup behavior in Java where it can be tested with normal project tests.
* Provide repository-hosted one-liner entrypoints for install, update-oriented reruns, and uninstall.
* Keep GitHub optional for users who want local or non-GitHub workflows.
* Avoid presenting GitHub-specific `Merging` and PR requirements as the non-GitHub default.
* Preserve the existing direct `new-board` and `import-board` commands for advanced/manual setup.
* Keep uninstall conservative and avoid deleting Trello boards or user data by default.

## Considered Options

* Plan B: bootstrap scripts delegate product setup to Java.
* Plan A: implement the whole first-run flow in Bash and PowerShell.
* Keep only the existing README, Maven commands, and Ansible deployment path.

## Decision Outcome

Chosen option: "Plan B: bootstrap scripts delegate product setup to Java", because it improves the
first-run path while keeping product behavior in the maintainable Java codebase. The repository now
ships `install.sh`, `install.ps1`, `uninstall.sh`, and `uninstall.ps1` as public entrypoints. Those
scripts detect the supported platform and core prerequisites, offer concrete install commands for
missing tools where the platform has a known package-manager path, choose Codex CLI installation in
the order existing authenticated CLI and user-local npm fallback, bootstrap or update the source
checkout, build the Quarkus app, install a small local command wrapper, and then call `setup-local`
unless onboarding was explicitly skipped.

The Java `setup-local` command owns local product setup and checks. It verifies core tools, validates
Trello credentials, writes ignored local credentials only when the user typed or passed credentials
directly, decides GitHub integration before board creation, creates or connects a Trello board with
the correct list set, then prompts for workspace access and Codex sandbox mode. It writes the
workflow file, records a connected-board manifest, starts the managed local worker unless explicitly
skipped, verifies managed-worker HTTP health after start, and prints a short first-card tutorial.
GitHub CLI auth is opportunistic: if it is already authenticated, GitHub integration is enabled;
otherwise non-interactive setup skips GitHub unless the user explicitly asks for it. When GitHub is
explicitly enabled and GitHub CLI is missing, setup offers assisted GitHub CLI installation, runs
`gh auth login`, and verifies `gh auth status`. When GitHub is explicitly enabled for an existing
connected non-GitHub board, setup upgrades that board in place and can create the missing `Merging`
list so the workflow file and board shape agree.

`setup-local check` is a real health check instead of a manifest echo. It reports missing or
invalid workflow files, GitHub auth gaps, stopped workers, occupied ports, and ports serving a
different Symphony workflow or board by querying the loopback-only local HTTP status endpoint instead
of trusting PID files alone. Setup avoids both workflow-reserved and already-bound localhost ports
when choosing the initial managed HTTP port. When a local workflow has a port conflict, `setup-local
repair-port --board NAME` updates only the selected workflow and connected-board manifest to the
next available port.

Installer-managed app files are separate from user data. The local layout has an app checkout, a
config directory for `.env`, workflows, and connected-board metadata, a workspace root for per-card
workspaces, and a state/log directory for PID and log files. When a user chooses a non-default dotenv
file during setup, the local wrapper starts the workflow with that same file through
`symphony-trello start --env PATH --workflow WORKFLOW.md`. The wrappers bootstrap the Java command
environment, and Java owns local worker lifecycle behavior for start, stop, status, and logs. The
uninstall scripts stop managed local processes before removing installer-managed files so uninstall
does not leave a background Symphony process managing Trello after the command has been removed.

Uninstall is conservative by default. It removes the installer-managed command and marked app
checkout only, and preserves local config, `.env` files, workflows, connected-board metadata,
workspaces, state/logs, Codex auth, GitHub auth, and Trello boards unless explicit cleanup scopes are
requested.

The direct `new-board` and `import-board` commands now accept `--github` and `--no-github`. Their
default remains GitHub-enabled for compatibility with existing documented PR workflows, while
`setup-local` can choose the non-GitHub path for users who do not have or do not want GitHub
integration. Non-GitHub generated boards omit `Merging`, and their workflow prompt describes local
or non-GitHub repository work instead of requiring PR publication and landing.

### Consequences

* Good, because the public first-run path is now a one-liner instead of a Maven-first flow.
* Good, because setup behavior is testable in Java instead of split across shell and PowerShell.
* Good, because GitHub is optional in the generated board/workflow path.
* Good, because non-GitHub workflows no longer include `Merging` or PR landing requirements.
* Good, because uninstall does not delete or archive Trello boards or local user data by default.
* Bad, because the first installer still builds from source and therefore needs Java 25 and Git.
* Bad, because assisted prerequisite installation depends on the host package manager and can still
  fall back to manual install guidance on platforms without a known command.
* Bad, because the local wrapper is a lightweight managed-run convenience, not a full cross-platform
  service manager.
* Bad, because the source-checkout installer still cannot provide the same experience as a signed
  release artifact or native package.
* Bad, because signed release artifacts and native packages remain future Plan C/Plan D work.

### Confirmation

Run `./mvnw -q -Dtest=TrelloBoardSetupTest,TrelloBoardSetupMainTest,LocalSetupTest,InstallerScriptTest test`
and confirm that GitHub-enabled setup creates `Merging` and PR workflow sections, while non-GitHub
setup omits them. `InstallerScriptTest` must also exercise the POSIX installer lifecycle with test
doubles: interactive Codex login selection, guided setup handoff, update from a changed source
repository, automatic managed start/status, conservative uninstall preserving user data, and
explicit cleanup scopes. PowerShell installer smoke checks should run either with native `pwsh` or
through the repository's Docker-backed PowerShell wrapper. Run `bash -n install.sh` and
`bash -n uninstall.sh` to check POSIX script syntax. Run the normal CI checks before merging.

## Pros and Cons of the Options

### Plan B: Bootstrap Scripts Delegate Product Setup to Java

Scripts install or update the checkout and command wrapper, then Java performs product setup.

* Good, because Java tests cover board/workflow decisions.
* Good, because shell and PowerShell stay focused on bootstrap mechanics.
* Good, because Ansible and future deployment code can reuse the same setup concepts.
* Bad, because it requires more Java command surface than a pure README change.

### Plan A: Implement the Whole First-Run Flow in Bash and PowerShell

Scripts would handle credentials, GitHub mode, board creation, workflow generation, and startup.

* Good, because it could be prototyped without new Java commands.
* Bad, because Trello and workflow behavior would be duplicated in two scripting languages.
* Bad, because the script behavior would drift from Java setup and tests.
* Bad, because cross-platform secret and path handling would become harder to maintain.

### Keep Only Existing README, Maven Commands, and Ansible Deployment

The project would keep the existing operator-focused setup paths.

* Good, because it adds no new code.
* Bad, because first-time users must understand too many implementation details before trying the
  product.
* Bad, because GitHub-specific generated workflows continue to look mandatory.
* Bad, because local install, update, and uninstall remain manual.

## More Information

[GitHub issue #35](https://github.com/martin-francois/symphony-trello/issues/35) contains the
detailed Plan B UX target implemented here. Release artifacts and runtime-bundled distributions
remain future Plan C/Plan D work.
