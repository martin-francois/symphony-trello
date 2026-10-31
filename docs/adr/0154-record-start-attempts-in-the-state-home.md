---
status: accepted
date: 2026-10-31
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #804](https://github.com/martin-francois/symphony-trello/issues/804)"
  - "[GitHub issue #492](https://github.com/martin-francois/symphony-trello/issues/492)"
  - "[GitHub PR #1212](https://github.com/martin-francois/symphony-trello/pull/1212)"
informed: [Future maintainers, Contributors]
---

# Record start attempts in the state home

## Context and Problem Statement

When `start --workflow PATH` fails because a file-backed secret is missing or larger than 64 KiB,
it prints a `secret_file_token` and the command
`symphony-trello diagnostics --show-private-context --lookup '<token>'`. The lookup matches the
token against the private context of the diagnostics run. Without a selector that context comes
from the connected-board manifest, so for a workflow that is not connected, for example one kept
outside the config directory, the printed command answered `lookup_status: not_found`. The token
resolved only after adding `--workflow PATH`, which the hint cannot print without showing the path
it hides.

The state home already holds a PID, log, and lock file per workflow, but their names are a hash of
the workflow path, so nothing led from the token back to the workflow. How does a lookup without a
selector learn about a workflow that was only ever started?

## Decision Drivers

* The printed lookup command must work as printed, with no path in the public output.
* The fix must not change what the public diagnostics report shows.
* Any new local state must be small, owned by the same process lock as the other state files, and
  cleaned up.

## Considered Options

* Print the lookup command with `--workflow` and the workflow path.
* Print the workflow as a token and let the lookup resolve it.
* Record the workflow file of every start attempt in the state home and map those records in a
  broad private-context run.
* Keep the command and add a note that unconnected workflows need `--workflow`.

## Decision Outcome

Chosen option: record the workflow file of every start attempt in the state home.

* `start` writes `<name>.workflow` next to the `<name>.pid`, `.log`, `.err`, and `.lock` files,
  where `<name>` is the same hashed state name. It writes the record under the per-workflow process
  lock, after the path checks and before it resolves the workflow configuration, so a start that
  fails on the workflow content has already left its record. The write goes through a sibling
  temporary file and an atomic move, so a reader never sees a half-written record.
* The record holds the absolute, normalized workflow path selected for configuration resolution,
  with symbolic links in place. The tokens in the failure message come from that spelling, because the worker resolves
  `file:` secrets against the workflow path it was given, so the record must use the same spelling
  or the tokens would not match.
* A diagnostics run without a selector lists the records, skips those whose workflow no longer
  exists, and maps the remaining workflows and their file-backed secret paths in its private
  context, after the workflows the report already covers. Private mappings keep distinct lexical
  spellings even when a symlink and its target refer to the same file; public rows retain their
  existing canonical deduplication. The record reader preserves whitespace in filenames. A run with `--board` or `--workflow` maps
  only the selected workflows, as before. A state home that cannot be listed contributes nothing,
  the way the PID file listing already behaves, so an unreadable state home does not fail
  `diagnostics`.
* `stop` deletes the record together with the PID file, for a tracked worker, an untracked but
  verified worker, and a worker that is already gone. Without connected boards, selector-less
  `stop` visits both PID files and record-only attempts under their process locks. With connected
  boards, it keeps the existing connected-worker scope. Successful cleanup removes both files;
  failed stops and unresolved worker ownership keep the record. A record whose workflow was deleted stays until the next stop of that workflow but is
  never mapped.

### Consequences

* Good, because the command a failed start prints resolves as printed, for connected and
  unconnected workflows and through symbolic links.
* Good, because the public report, the error codes, and the exit codes are unchanged.
* Bad, because the state home gains one more file per workflow, which holds a local path. The
  state home is private to the install and already holds worker logs, so this adds no new kind of
  private data.
* Bad, because a workflow that is started and then deleted leaves a record until `stop` runs for
  it. The record is never mapped while the workflow is missing.

### Confirmation

* `LocalWorkerManagerTest` starts an unconnected workflow with a missing and with an oversized
  secret file and resolves the printed token with a selector-less lookup, does the same through a
  symlinked workflow directory, and checks that `stop` removes the record.
* `SetupDiagnosticReporterTest` maps a recorded workflow's secret file in a broad run, skips a
  recorded workflow that no longer exists, and renders public diagnostics with an unreadable
  state home.
* `ManagedProcessStoreTest` covers the record file, its spelling, its deletion, and its listing.
* This change covers literal secret paths and paths expanded from the process environment.
  [Issue #1220](https://github.com/martin-francois/symphony-trello/issues/1220) tracks the existing
  mismatch for path variables defined only in a workflow's dotenv; it needs a separate resolver fix.
* The live bug bash rows `missing-secret-file-launch-diagnostics` and
  `oversized-secret-file-launch-diagnostics` are `covered`.

## Pros and Cons of the Options

### Print the workflow path in the lookup command

The hint would add `--workflow PATH` with the path the user typed.

* Good, because no state is needed.
* Bad, because the failure output is written to be safe for public issue reports and must not
  show the workflow path; the token exists for that reason.

### Print the workflow as a token

The hint would add `--workflow <path:token>` and the lookup would resolve the token.

* Good, because the output stays public-safe.
* Bad, because the token is a keyed hash and the lookup can only compare it against paths it
  already knows; an unconnected workflow outside the config directory is not among them, so this
  option needs the same record as the chosen one and adds a second token to parse.

### Record every start attempt in the state home

Each start attempt writes its absolute workflow path beside the managed PID file under the
process lock. Selector-less private diagnostics read these records and map each existing workflow
using its recorded spelling. Stop removes the record when it finishes that workflow's lifecycle.

* Good, because the lookup needs no new option and every start attempt is covered.
* Bad, because of the extra state file and its cleanup.

### Add a note to the hint

The hint would say that unconnected workflows need `--workflow`.

* Good, because it is the smallest change.
* Bad, because the command still does not work as printed, which is the contract the live bug bash
  checks.

## More Information

* [SPEC section 6](../../SPEC.md) defines private lookup and lifecycle behavior.
* [Issue #804](https://github.com/martin-francois/symphony-trello/issues/804) records the failed
  lookup for an unconnected workflow.
