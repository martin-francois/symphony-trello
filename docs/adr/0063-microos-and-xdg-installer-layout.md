---
status: accepted
date: 2026-07-07
decision-makers: [François Martin, Codex]
consulted:
  - README.md
  - SPEC.md
  - install.sh
  - uninstall.sh
  - "[GitHub issue #530](https://github.com/martin-francois/symphony-trello/issues/530)"
  - "[ADR 0049](0049-release-archive-installer.md)"
  - "[ADR 0062](0062-remove-manual-systemd-deployment-path.md)"
  - "[openSUSE MicroOS storage requirements](https://microos.opensuse.org/blog/2020-11-23-requirements/)"
  - "[openSUSE MicroOS design notes](https://en.opensuse.org/Portal:MicroOS/Design)"
informed: [Future maintainers, Contributors]
---

# MicroOS And XDG Installer Layout

## Context and Problem Statement

The POSIX installer originally used one base directory:
`$HOME/.local/share/symphony-trello`. App files, config, state, logs, workspaces, and the managed npm
prefix all lived below that directory unless the operator supplied custom paths.

That was simple, but it was not a good default for openSUSE MicroOS-like hosts. On those systems,
`/home` can be on a small root-backed filesystem while `/var` is the large mutable workload
filesystem. Symphony workspaces can become large because they contain cloned repositories,
dependency stores, build output, and generated test artifacts.

The strict symlink rejection in the installer also blocked a valid MicroOS pattern where a logical
home path such as `/home/codex` resolves into a user-owned location below `/var`.

How should the POSIX installer choose default paths and how should uninstall rediscover them?

## Decision Drivers

* Keep the public install command flag-free for normal users.
* Keep ordinary Linux, macOS, and WSL2 installs user-local and predictable.
* Put large mutable data on `/var` for MicroOS-like hosts when the home filesystem is root-backed.
* Allow safe symlinked homes that resolve under a trusted user-owned root.
* Preserve explicit path overrides.
* Let uninstall find the selected layout without requiring the same flags that were used during
  install.
* Avoid deleting broad system paths or crossing into unrelated filesystems during uninstall.

## Considered Options

* Keep the legacy single `SYMPHONY_HOME` default everywhere.
* Switch every POSIX install to `/var/lib/symphony-trello`.
* Use XDG paths generally and a MicroOS `/var` layout only when needed.
* Require MicroOS users to pass custom path flags.

## Decision Outcome

Chosen option: "Use XDG paths generally and a MicroOS `/var` layout only when needed", because it
keeps the simple installer command while choosing storage that matches the host.

For normal Linux, macOS, and WSL2 installs, the POSIX installer uses XDG-style paths:

* app and workspaces under the data home;
* config under the config home;
* state and logs under the state home;
* caches and the managed npm prefix under the cache home.

For openSUSE MicroOS-like Linux hosts, and for other Linux hosts with the same storage topology, the
installer chooses `/var/lib/symphony-trello/users/<user>` as the data root when the user's home is
root-backed and `/var` is separate and larger. The command shim stays in `$HOME/.local/bin` because
it is small and user-facing.

If the user's home already resolves below `/var`, the installer keeps the logical XDG paths under
`$HOME` and allows the symlinked or bind-mounted home. Path validation still rejects broad dangerous
targets such as `/`, `/etc`, `/home`, `/usr`, and `/var`.

The installer writes an install context to the selected config and state directories and to stable
default context locations under `$HOME`. The uninstaller reads that context before falling back to
defaults, so users do not need to remember custom flags or the auto-selected MicroOS path. POSIX
uninstall also deletes nested btrfs subvolumes under requested cleanup targets before using recursive
delete. When a requested removal target is itself a subvolume, it deletes nested subvolumes deepest
first and then deletes the target subvolume.

### Consequences

* Good, because normal installs follow familiar user-local XDG locations.
* Good, because MicroOS-like and matching storage-topology installs avoid putting large mutable
  workspaces under a small root-backed home.
* Good, because symlinked homes into `/var` work without disabling path safety.
* Good, because uninstall can find an auto-selected or previously selected layout.
* Bad, because the POSIX scripts now have more path-resolution logic.
* Neutral, because explicit path overrides remain the escape hatch for unusual hosts.

### Confirmation

This decision is still implemented when:

* `install.sh --dry-run --no-onboard` on a generic POSIX host reports config under
  `$HOME/.config/symphony-trello`, state under `$HOME/.local/state/symphony-trello`, and cache under
  `$HOME/.cache/symphony-trello`.
* MicroOS-like and matching storage-topology installer tests show root-backed homes using
  `/var/lib/symphony-trello/users/<user>` or the test equivalent.
* MicroOS-like installer tests show homes that resolve into `/var` keeping logical XDG paths under
  `$HOME`.
* install context files are written below both config and state and to the stable default discovery
  locations.
* uninstall tests prove that uninstall uses install context without requiring the original path
  flags.
* uninstall tests prove btrfs subvolume targets and nested btrfs subvolumes under ordinary cleanup
  targets are removed with `btrfs subvolume delete`.

## Pros and Cons of the Options

### Keep The Legacy Single `SYMPHONY_HOME` Default Everywhere

Keep app, config, state, logs, workspaces, and managed npm under
`$HOME/.local/share/symphony-trello` unless the user supplies overrides.

* Good, because it is simple and already implemented.
* Good, because existing tests and docs need little change.
* Bad, because it puts large generated workload data under root-backed homes on MicroOS-like hosts.
* Bad, because it does not follow normal XDG separation for config, state, and cache.

### Switch Every POSIX Install To `/var/lib/symphony-trello`

Use `/var/lib/symphony-trello/users/<user>` for all POSIX installs, regardless of host layout.

* Good, because large generated data is moved away from `$HOME`.
* Bad, because ordinary desktop users would need privileged directory creation for no good reason.
* Bad, because it makes the simple install command less predictable on normal Linux and macOS hosts.

### Use XDG Paths Generally And A MicroOS `/var` Layout Only When Needed

Use XDG-style user-local paths on normal hosts. Use a `/var/lib/symphony-trello/users/<user>` layout
only for MicroOS-like or matching storage-topology hosts where home is root-backed and `/var` is the
large mutable filesystem.

* Good, because defaults match common desktops, MicroOS-like hosts, and matching storage-topology
  hosts.
* Good, because no flags are needed for the expected happy paths.
* Good, because explicit overrides still work.
* Bad, because the scripts need filesystem and context discovery logic.

### Require MicroOS Users To Pass Custom Path Flags

Keep the installer unchanged and document `SYMPHONY_HOME`, `--prefix`, and related overrides for
MicroOS users.

* Good, because the installer remains simpler.
* Bad, because the public quick start fails to be the happy path on MicroOS-like hosts.
* Bad, because uninstall would still need users to remember the chosen custom paths.

## More Information

This decision only changes POSIX install and uninstall layout behavior. Native Windows PowerShell
keeps its existing local app-data layout.
