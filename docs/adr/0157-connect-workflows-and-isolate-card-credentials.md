---
status: accepted
date: 2026-10-31
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #1216](https://github.com/martin-francois/symphony-trello/issues/1216)"
  - "[ADR 0134](0134-manage-board-cards-through-cli-commands-and-a-codex-skill.md)"
  - "[ADR 0151](0151-bind-trello-credentials-to-an-endpoint.md)"
informed: [Maintainers, Operators]
---

# Connect workflows and isolate card credentials

## Context and Problem Statement

Starting an arbitrary workflow currently bypasses connection registration. Card commands require
registration but read credentials in the same process as the agent's command. Codex's built-in
read-only and workspace profiles permit those credential reads. Keeping secrets out of command
output does not prevent the parent agent from reading the files itself.

The owner chose automatic connection on start, one resolver, continued card access when workers
are stopped, and an explicit unrestricted mode. A helper process must therefore own card
credentials independently of worker lifetime.

## Decision Drivers

* Preserve the six existing card commands and their policy checks while workers are stopped.
* Prevent restricted commands from reading credentials or changing connections and policies.
* Preserve status, logs, stop and disconnect when YAML or credentials are broken.
* Keep explicit `danger-full-access` available and state its limits.
* Avoid granting unrestricted lifecycle or arbitrary HTTP operations through the helper.

## Considered Options

* A shared trusted resolver and an independent card helper with a filesystem request directory.
* Forward card operations to running workers.
* An independent helper reached through Unix sockets or loopback HTTP.
* Keep in-process card execution and deny credential-file reads.
* Move all lifecycle control and worker polling into a new server.

## Decision Outcome

Use a shared trusted resolver and an independently supervised card helper. The helper handles
only the existing typed card operations on registered boards. It accepts no credential source,
endpoint, policy override, setup request or arbitrary workflow to execute. Workers retain their
existing polling and narrower current-card tools.

Use a per-installation filesystem request directory for the local transport. Its parent and the
helper's private state remain read-only or denied to restricted clients. Only the request directory
is writable. The helper takes bounded snapshots of requests without following links, validates a
closed request schema, resolves the board from its fixed trusted config directory, and publishes
bounded responses atomically. Requests have unique identifiers and are claimed once. A timeout
does not trigger an automatic retry of a write whose outcome is unknown. Temporary files, links,
oversized requests and malformed messages never become arbitrary file reads or writes by the
trusted helper. Transport access is restricted to the installation's local user. The bounded request scan retains its directory cursor between passes so ignored directories cannot
starve later requests. It closes the cursor at the end of a scan or shutdown without traversing client
subdirectories. Worker profiles
deny this directory so their narrower tools cannot acquire standalone board-wide authority.

`start --workflow` imports a validated snapshot into managed config storage and registers the
connection before launching it. Successful publication counts an import only when the board was
not already connected, using the manifest from before replacement and the selected dotenv for
reporting opt-out. Re-imports and managed-path starts preserve the counter. Original paths remain selector aliases, but only a subsequent
explicit start imports changed source contents. Connection publication, worker startup and card
execution share the connection lock, acquired before a worker's process lock. This prevents a start
from launching the old connection between its stop and replacement publication. Retargeting an
external source to an unconnected board stops and removes its previous registration.
Forced setup uses the same managed-path and source-alias identity for replacement and reserved-port
checks as lifecycle selection. Guided setup also excludes the selected managed snapshot from its
sibling-file port scan when the source and snapshot share a directory. Other workflow files retain
their reservations. Both setup entry points pass the selected replacement path into the workflow
generator so its final file scan applies the same exclusion. The exclusion belongs to that setup
request; standalone generation retains its ordinary sibling-file checks. It stops every replaced worker before publishing the new registry;
a shutdown failure preserves the old registration and managed snapshot.
Disconnect stops the worker before removing its connection and drains or
refuses card operations. Failure to stop preserves a recoverable connection. Recovery commands
select registered metadata without requiring valid credentials or YAML. If current port settings are
invalid, stop and disconnect probe the registered stable port and retain the same process-ownership
checks before signalling an untracked worker. A foreign PID in managed tracking does not establish
that the board is stopped. Stop and disconnect also probe for an untracked worker, retaining
connection and recovery records when its identity or shutdown cannot be confirmed. An occupied
status port that times out or returns an error is unknown, so stop and disconnect refuse it.

A source already connected to one board cannot retarget another connected board until the operator
disconnects its old connection. Refusal happens before stopping either worker or publishing files.
Setup's explicit custom manifest remains authoritative for that worker. Trusted launchers carry its
path through `SYMPHONY_TRELLO_MANIFEST`; helper identity also includes a nondefault manifest so
separate registries do not share a helper. Restricted profiles deny the selected manifest.
Automatic workspace defaults are siblings of the config directory. When config is inside the
application checkout, they are siblings of that checkout instead. Source and installed setup share
this derivation so their defaults stay outside both protected directories. A numbered suffix avoids
a directory-name collision with config or app storage. Explicit workspace
overrides still pass through permission validation.

Windows helper directories use a protected owner-only DACL at creation. The Windows PowerShell helper builds a
`DirectorySecurity` with inheritance disabled and passes it to `DirectoryInfo.Create`; it never
creates a shared directory and tightens permissions afterward. It creates all missing helper
directories in one process and leaves existing directories to the Java ownership/ACL validator.
The helper is part of the application, and paths and owner names cross the shell as base64 JSON.
The native test supplies inheritable grants for other principals and checks that helper directories
contain only the owner's grant. It does not assume how Java's initial `acl:acl` attribute handles
inheritance. The Windows CI helper test step reports test classes, limits the fork to three minutes,
and prints thread dumps on failure so a blocked process does not leave the job waiting indefinitely.
Native process tests also require stopped helper logs to be deletable. A failed deletion queries
Windows Restart Manager for the owning process IDs and executable names, without reading log
contents or stopping those processes. This distinguishes a retained handle from a cleanup delay.
See [DirectoryInfo.Create](https://learn.microsoft.com/en-us/dotnet/api/system.io.directoryinfo.create?view=netframework-4.8.1)
and [SetAccessRuleProtection](https://learn.microsoft.com/en-us/dotnet/api/system.security.accesscontrol.objectsecurity.setaccessruleprotection).

Helper shutdown reads retained lifecycle metadata and verifies the process marker and main class.
It does not initialize storage or require the configuration directory to remain present. Startup
still validates config ownership and private storage before launching the helper.
After confirmed termination, the manager removes that helper's readiness record while holding the
helper's process lock. It preserves a replacement generation and leaves a live helper's record
alone. Windows terminates the process without running Java shutdown hooks, so relying on the
server's shutdown cleanup left stopped helpers advertised as ready. The client then submitted a
request and waited for a response that never arrived. The trusted manager owns this cleanup;
checking the PID in the restricted client would break callers whose PID namespace hides the host
helper. A unit test models termination without shutdown hooks, and the native process test checks
the subsequent CLI failure. The earlier Linux-only run did not exercise Windows termination.
Native Windows still retained log handles and the helper process lock after Java's stop returned.
Restart Manager found no owner by the time its diagnostic ran. The Windows platform now waits on
the kernel process objects for the helper and its previously observed descendants after the Java
stop succeeds. Java retains responsibility for signalling processes and its PID-reuse checks.
The native wait has synchronization access only and never signals a process. It shares one bounded
timeout across the observed process IDs, reports failure when inspection fails, and permits an
already removed process object. A reused PID causes only a bounded wait, never a signal.
This follows the documented synchronization requirement for asynchronous
[TerminateProcess](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-terminateprocess).
Waiting for the process object replaces a fixed cleanup delay and lets readiness cleanup acquire
the released helper lock before reporting success. The native process tests remain the regression
for immediate readiness removal and log deletion.

Windows process identification queries the native command line through CIM and parses it with
`CommandLineToArgvW`. Both worker and helper ownership checks use those argument boundaries;
missing or failed inspection never authorizes signalling a process. JDK process information alone
does not supply arguments on Windows. A live recorded PID whose arguments cannot be inspected
fails helper start, stop and status. Shutdown cannot report success and authorize an installer
update while the old helper's identity remains unknown. Worker ownership checks also distinguish
unavailable inspection from a known foreign process. A live worker with unavailable arguments
fails lifecycle operations and retains its PID record and connection for recovery.

Uninstall identifies the helper through its recorded PID, installation marker, main class and
config argument before signalling it with native process tools. A live PID with unavailable command inspection stops
uninstall before file removal, preserving the installation for recovery. It does not require the app or Java
to remain usable. Each initialized helper retains private lifecycle metadata identifying its config
and manifest. Updates enumerate those identities, stop every helper for the selected config before
replacing code, and restart them afterwards. Helpers for another config directory remain running.
Retained helper metadata does not establish that the old launcher exists. Reinstallation with a
missing launcher proceeds when no recorded helper PID is running. If a recorded PID remains live,
the installer refuses replacement and asks the operator to restore the launcher or verify and stop
that process. It does not signal a process whose ownership it cannot establish.
The Windows launcher passes environment values through the process environment, keeping expanded
Java classpaths out of its encoded PowerShell command and below the native command-line limit.
It uses `ProcessCommandRunner` to capture launcher output in a file and wait up to ten seconds
for PowerShell to exit. Reading the launcher pipe to EOF blocked the native Windows helper test
inside `readAllBytes` while the detached helper stayed alive. File capture removes that pipe
dependency and reuses the existing timeout and interruption cleanup. A separate asynchronous pipe
reader would add another thread and cleanup path without improving the launcher contract.
`CardHelperProcessTest` exercises the real detached helper on native Windows and caught this failure.
Both the launcher and process-argument query disable PowerShell progress before loading modules.
Native Windows emitted module-preparation progress as CLIXML alongside the PID, and the strict PID
parser rejected it. The query also requires clean JSON. Suppressing progress at its source preserves
strict parsing and keeps command errors visible; it avoids stripping arbitrary output in Java.
For source launchers such as Maven `exec:java`, the helper uses its defining URL classloader's
application entries. Ordinary JVM launches use their process classpath, resolved against the caller's working directory
before the helper changes to the selected application directory. Maven's bootstrap classpath
does not contain the helper or its dependencies and cannot launch it independently.

Trusted setup holds the connection transaction while changing workflow policy and registration.
Its nested lifecycle calls reuse the same thread's lock; other threads and processes still wait.
Interactive setup therefore delays card operations until it finishes or exits. This avoids exposing
a partially edited policy or publishing a stale manifest after a concurrent disconnect.

Restricted worker turns select a protected named Codex permission profile on both thread and turn
requests. They do not send legacy sandbox fields that override that profile. Protected paths
include config and actual credential sources, including custom files and environment references.
Child environments, including hooks, remove credential values and their source variables. The
installation and installed wrapper directories are read-only to restricted workers and external
agents. Both profiles resolve launch paths from trusted launcher environment and matching installer
context, including canonical directory aliases and wrapper symlink targets. Protecting the whole
wrapper directory prevents replacement and creation of sibling launchers, but also prevents these
sessions from installing unrelated executables into that directory. Linux Codex refuses to enforce a
read-only directory rule through a writable symlink. That installation layout fails closed; operators
must use a real directory rather than remove its protection. Hooks and custom launchers remain trusted operator
code; dependencies outside the installation require operator protection. An external agent needs
an equivalent operator-configured profile; a child CLI cannot confine its parent.

Explicit `danger-full-access` remains supported. It permits credential reads and config writes,
so the restricted-mode protections do not apply. A protected copy cannot remove a secret already
disclosed through the user's original file or environment. Unsupported restricted policies fail
before agent execution instead of silently falling back to unrestricted access.

### Consequences

* Good, because card access survives worker shutdown without exposing Trello credentials.
* Good, because the request directory needs no additional network access or platform socket bridge.
* Good, because registration, endpoint binding and card policy have one trusted implementation.
* Bad, because installations must start and maintain one additional process.
* Bad, because filesystem transport needs bounded frames, stale-request cleanup and explicit
  uncertain-write outcomes.
* Bad, because moving workflow execution into managed copies changes source-file reload behavior.

### Confirmation

Linux preflight with Codex 0.166.0 completed real model turns. Built-in read-only and workspace
profiles allowed synthetic config reads and refused writes. A named exact-path deny refused both,
including reads through a symlink and `/proc/self/root`. Unrestricted mode allowed both. App-server
thread and turn `permissions` preserved the deny without legacy policy fields.

The built helper completed `card list` against a synthetic Trello API during a real Codex turn
using the CLI-generated profile, with no worker running. Credential reads, config and runtime
writes, response forgery and reads of the helper environment were denied. A built worker turn
refused custom file and dotenv credentials, source workflow and helper state; inherited custom
credential variables and aliases were absent, while workspace writes succeeded. Installation writes
were denied. An explicit full-access worker turn allowed the same file reads and config and launcher
writes; credential variables still stayed out of its inherited environment. These Linux
checks do not establish native Windows or macOS behavior.

Implementation verification must cover stopped-worker card operations; all six operation policies;
unregistered board, path and policy injection; malformed, linked and oversized requests; uncertain
writes; helper restart; disconnect races; original-file snapshots; credential-source protection;
and explicit unrestricted mode. Real restricted turns must exercise the built CLI and helper.
Native Windows and macOS claims require native evidence. Until those checks pass, this ADR records
the accepted design rather than a completed implementation.

## Pros and Cons of the Options

### Shared resolver and filesystem card helper

A trusted process reads typed requests from a dedicated local directory and returns card results.
It loads credentials and policy itself from protected registered configuration.

* Good, because it works independently of workers and does not require network permission.
* Bad, because safe file publication, request claiming and cleanup need dedicated tests.

### Existing workers

The CLI sends card operations to an already-running board worker.

* Good, because it reuses a credential-owning process.
* Bad, because stopping that worker removes restricted external-agent card access. The owner chose
  to preserve that access.

### Socket or loopback helper

A separate process serves typed card operations over local IPC or authenticated HTTP.

* Good, because established request-response transports handle framing and connection lifetime.
* Bad, because Linux Codex preflight refused direct socket connections under the restricted
  profile. Its documented Unix-socket proxy is macOS-only. Granting a socket path filesystem write
  access caused sandbox startup to fail. Loopback networking would require a separate permission
  and server-authentication design.

### In-process CLI with credential deny rules

Keep card execution in the CLI and block its credential files in the agent sandbox.

* Good, because it adds no helper process.
* Bad, because the built CLI preflight then fails with missing credentials before making any
  Trello request. The same process cannot both read and be denied those files.

### Broad control server

A new server owns setup, lifecycle administration, polling and card operations.

* Good, because all state transitions live in one process.
* Bad, because it gives the transport more authority and moves working lifecycle code unnecessarily.

## More Information

The compatibility decision is breaking for 2.0.0. Operators re-run trusted start to connect existing
source workflows, use managed paths or source aliases for selection, and restart explicitly to
import source edits. Existing connections remain registered. Restricted external sessions use the
generated permission profile and require the helper to be running; the helper remains independent
of board-worker status. The migration does not weaken endpoint binding from ADR 0151.

References: [Codex permission profiles](https://learn.chatgpt.com/docs/permissions) and
[Codex network proxy platform notes](https://github.com/openai/codex/blob/main/codex-rs/network-proxy/README.md#platform-notes).
