---
status: accepted
date: 2026-08-30
decision-makers: [François Martin, Codex]
consulted:
  - "[GitHub issue #668](https://github.com/martin-francois/symphony-trello/issues/668)"
  - "[GitHub issue #678](https://github.com/martin-francois/symphony-trello/issues/678)"
  - "[GitHub PR #669](https://github.com/martin-francois/symphony-trello/pull/669)"
  - "[ADR 0063](0063-microos-and-xdg-installer-layout.md)"
informed: [Future maintainers, Contributors]
---

# Bound the managed state home migration

## Context and Problem Statement

The installer selects separate config and state homes on normal macOS, Linux, and WSL2 systems.
Guided `setup-local` received the config directory but not the selected state home, so Java derived a
config-sibling `state` directory. The installer wrapper later used the configured state home. This
split left worker PID files and logs in different directories and made lifecycle commands report the
worker incorrectly.

Correcting the default fixes new installs, but existing installs can still have an active worker PID
file in the derived directory. How should an update move active lifecycle ownership to the configured
state home without making users remember a recovery command?

## Decision Drivers

* Keep the normal install and update commands flag-free.
* Stop each known managed worker safely before replacing the application.
* Make the configured state home authoritative after the update.
* Preserve old logs for troubleshooting and audit history.
* Avoid making an incorrect path fallback permanent.
* Give the temporary migration a dated removal decision.

## Considered Options

* Inspect configured and legacy PID roots during update, then restart in the configured root.
* Add a permanent runtime fallback that reads both state roots.
* Correct new installs only and document manual recovery for existing users.
* Move every legacy state file into the configured state home.

## Decision Outcome

Chosen option: "Inspect configured and legacy PID roots during update, then restart in the configured
root", because it repairs existing installs without turning the incorrect layout into a supported
runtime contract.

During an update, each installer checks the configured state home and the former
`parent(config directory)/state` location for managed worker PID files. It stops workers tracked in
either location through the existing lifecycle command, installs the update, and starts connected
boards with the configured state home. New PID files and logs use only the configured root.

The legacy root is shared with other applications. Only names emitted by the managed process store,
with a workflow filename and a twelve-digit hexadecimal hash, are candidates for migration. A live
PID also needs this installation's app-home marker and application JAR path. Records for other live
processes remain untouched, including workers from another installation sharing the directory.
The installer copies eligible PID files into a temporary state directory and invokes the old command with an empty
temporary config directory. This keeps old commands that enumerate every PID file away from unrelated
state. After a successful stop, it removes an original PID file only when its bytes still match the
copied record and the stop removed the temporary record. A changed or retained record stops the update.
The temporary directory is removed on success and failure.

A live worker with the same application prefix also needs a workflow argument from the selected
configuration's connected-board manifest. Both installers check this before stopping anything; if
none matches, the installer refuses the update before stopping workers or replacing application
files. Both repeat the check immediately before stopping workers, because another configuration can
start one while the update waits on prompts or downloads. The operator stops that worker through its
own configuration before retrying. Filtering it out and continuing was rejected because the update
would replace application files still used by that worker. App-home ownership alone was rejected
because two configurations can share one application and the former state directory while
`start --all` restarts only the selected configuration.
The workflow comparison accepts its recorded spelling and its full path. Java retains the recorded
spelling in process arguments, while PowerShell's `GetFullPath` expands Windows 8.3 names. Comparing
only the expanded spelling incorrectly rejects an owned worker launched through a short path.
The POSIX installer compares the worker's last argument, its workflow, with two spellings of each
manifest path: `Path.of`'s form, which `start --all` passes and which collapses repeated slashes, and
the lexically normalized form that `start --workflow` passes. Without `/proc`, it matches the end of
the `ps` command line instead. It reads the manifest without a JSON tool and decodes only the
escapes a path string needs (`\"`, `\\` and `\/`). Any other escape makes ownership uncertain, so
the installer refuses while such a worker is live instead of guessing.

Calling the old command directly on the shared root was rejected because it deletes unrelated PID
files when no boards are connected. Duplicating process termination in both installers was rejected
because the existing lifecycle command already owns process identity checks and bounded shutdown.
The installer still checks live-process ownership before exposing a record to an old command, because
that command deletes tracking for an unrelated live process even though it correctly refuses to stop it.
The ownership check lives in each installer rather than in the Java command because it runs before
the update replaces the application, while the installed command is still the older release, which
has no such check.

The update leaves old log files in place. Moving or merging logs would risk name collisions and
would blur their original location and time. The legacy directory is therefore historical data, not
a fallback for future commands.

This compatibility logic is temporary.
[GitHub issue #678](https://github.com/martin-francois/symphony-trello/issues/678) has the
`breaking change` label and requires review by 2027-08-30 or at the next breaking release, whichever
comes first. If that release removes the migration, its release notes must name the latest
intermediate version that still performs it and recommend upgrading through that version first.

### Consequences

* Good, because affected updates repair themselves without extra flags or manual file changes.
* Good, because one configured state home owns all new lifecycle state.
* Good, because historical logs remain available.
* Bad, because update scripts temporarily contain two-root discovery logic.
* Bad, because an install that skips every migration-bearing release will need the documented
  breaking-release upgrade sequence after removal.
* Neutral, because an empty legacy state directory can remain after migration.

### Confirmation

This decision is still implemented when:

* POSIX and PowerShell installer lifecycle tests use separate config and state homes;
* an update with only a legacy PID file stops that worker and creates its replacement PID in the
  configured state home;
* flag-free lifecycle commands read the configured state home after the update;
* legacy log files remain untouched;
* no runtime lifecycle command searches the legacy root after migration; and
* [GitHub issue #678](https://github.com/martin-francois/symphony-trello/issues/678) remains linked,
  dated, labeled, and reviewed before removal.

## Pros and Cons of the Options

### Inspect Both PID Roots During Update

The installer detects managed PID files in the configured and former derived roots, stops those
workers, and restarts connected boards in the configured root.

* Good, because the update performs the repair at the point that already replaces and restarts the
  application.
* Good, because normal lifecycle behavior has one authoritative root.
* Bad, because both installer implementations need temporary migration code.

### Add A Permanent Runtime Fallback

Every lifecycle command searches the configured root and the former derived root indefinitely.

* Good, because skipped updates and unusual historical states remain discoverable.
* Bad, because users can never know which directory owns current PID files and logs.
* Bad, because deleting the incorrect layout becomes a permanent compatibility problem.

### Require Manual Recovery

The implementation fixes new setup calls and tells affected users to stop or move workers
themselves.

* Good, because the implementation is smaller.
* Bad, because the default installer created the inconsistent state, while users must diagnose and
  repair it.
* Bad, because users need to remember paths that the installer was meant to manage.

### Move Every Legacy State File

The updater moves PID files, locks, logs, and other state from the legacy directory to the configured
state home.

* Good, because no legacy directory remains.
* Bad, because moving live PID files does not stop the corresponding processes safely.
* Bad, because logs with the same name can collide or lose their provenance.

## More Information

This migration covers the path mismatch introduced by the guided setup boundary. It does not make
arbitrary historical state directories discoverable. The installed wrapper and the public
`--state-home` option define the authoritative current layout.
