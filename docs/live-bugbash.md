# Live bug bash harness

`scripts/live-bugbash/run.sh` replays the live bug-bash scenarios as a repeatable run. Each
scenario is a row in [`scripts/live-bugbash/manifest.yml`](../scripts/live-bugbash/manifest.yml)
with a stable id, its requirements, the expected status, and the GitHub issue of a known bug. A run
installs Symphony from the current commit, runs the selected rows against run-scoped resources,
cleans up, and writes reports under `target/live-bugbash/<run-id>/`.

The harness is fake by default. Without opt-in flags it uses a local fake Trello API and the
deterministic fake Codex app-server in `scripts/FakeCodexAppServer.java`. Real Trello, real Codex,
and real GitHub need explicit flags. CI does not run the harness; the script-tests job only runs
its unit tests.

The `$live-bugbash` Codex skill in `.agents/skills/live-bugbash/` uses this harness as the
repeatable baseline and then explores beyond it. For the older two-phase live E2E checklist, see
[live-e2e.md](live-e2e.md).

## Requirements

- Node.js 24 or newer and the script dependencies: `pnpm install --frozen-lockfile`.
- A Java 25 JDK on `PATH`. The source install builds Symphony with the Maven wrapper, and fake Codex
  runs on the JDK.
- Git and Bash.
- For `--codex real`: the `codex` CLI, logged in.
- For `--trello real`: `TRELLO_API_KEY` and `TRELLO_API_TOKEN` in the environment or in the ignored
  project-root `.env`, and a Trello account where disposable boards can be created and archived.

The source install builds the committed `HEAD` of the checkout. Commit local changes first, or pass
`--symphony-command <path>` to test an existing install.

## Quick start

```bash
pnpm install --frozen-lockfile
scripts/live-bugbash/run.sh                      # quick profile, fakes only
scripts/live-bugbash/run.sh --list --profile release
scripts/live-bugbash/run.sh --dry-run --profile full
```

`--list` prints the selected rows with their mode, expected status, and whether they are automated.
`--dry-run` prints which rows would run, which would be skipped and why, and creates nothing.

The quick profile takes about two minutes, most of it the source install. The release profile runs
every automated fake-mode row in about six minutes.

## Selecting scenarios

| Option | Selects |
| --- | --- |
| `--profile NAME` | rows that list the profile; `full` stands for every row |
| `--family NAME` | rows of a family; combined with `--profile`, the intersection |
| `--scenario ID` | one row by id, regardless of profile |

All three options can be repeated. Without any of them, the run uses `--profile quick`.

| Profile | Purpose |
| --- | --- |
| `quick` | deterministic CLI checks and a small fake-Codex smoke subset |
| `release` | every automated row that runs with fakes |
| `trello-live` | Trello rows with fake Codex; pair with `--trello real` |
| `real-codex` | real-Codex rows; pair with `--codex real` |
| `github-live` | GitHub sandbox rows; none are automated yet |
| `repository-source` | repository source selection and prompt rendering |
| `trello-tools` | Trello tool and workpad policy |
| `lifecycle` | start, stop, status, logs, and recovery |
| `soak` | passive health loop after cleanup |
| `full` | every row; requirements still decide what runs |

Rows always run in this order: deterministic CLI rows, Trello CLI rows, service rows with fake Codex,
real-Codex rows, GitHub sandbox rows, cleanup, then the soak. Fake-Codex rows therefore always run
before the real-Codex rows that depend on the same behavior.

## Real integrations

| Flag | Effect |
| --- | --- |
| `--trello real` | uses the real Trello API with credentials from the environment or `.env` |
| `--trello-workspace-id ID` | Workspace for disposable boards when the token sees several |
| `--codex real` | runs real-Codex rows; they are skipped when the `codex` CLI is missing |
| `--github real-sandbox` | reserved for GitHub sandbox rows; none are automated yet |
| `--host-profile hardened` | allows rows that need run-scoped danger-full-access |
| `--network` | allows rows whose Codex sandbox needs outbound network |

A row whose requirement is missing is reported as `skipped` with the reason, never as a pass.

With `--trello real`, every board the harness creates is named `<run-id>-<scenario-id>` and is
written to `created-trello-boards.jsonl` before any list or card is added. Boards that the product
creates (`setup-local`, `new-board`) carry the same prefix and are registered right after the
command. Cleanup archives only registered boards. Before archiving, it also registers any visible
board whose name starts with `<run-id>-` and whose id shows it was created after the run started,
so a scenario that stopped before registering its board does not leak it. Against the fake Trello API the harness also
checks after every row that no unregistered board exists; a row that leaks one is reported as
`harness-invalid`, so a rehearsal with fakes catches the leak before a real run.

Real-Codex rows use a run-scoped `CODEX_HOME` that holds only a copy of `auth.json`, so Codex writes
no sessions or history into the operator's Codex home. Cleanup deletes the copy. If Codex refreshed
its login inside the copy, the cleanup summary says so; the harness never writes to the operator's
Codex home, so run `codex login` again if the normal login stops working. Each real-Codex row
is bounded: the sentinel and protocol probe stop after three minutes or less, and the Trello smoke
card waits at most five minutes.

A row that ran against the fake Trello API carries the `trello:fake` mode tag. When a live profile
such as `trello-live` runs against fakes, the final report says that the run has no live coverage.
Repeat it with `--trello real` before claiming live Trello coverage.

## Run root

```text
target/live-bugbash/<run-id>/
  progress.md              public: run header and a timestamped log
  coverage-ledger.md       public: one row per scenario
  final-report.md          public: counts, findings, skipped reasons, cleanup, scan result
  cleanup-summary.md       public: what cleanup archived, stopped, and deleted
  issues/                  public: issue drafts written after triage
  created-*.jsonl          registries of run-owned Trello boards, cards, and GitHub repositories
  started-workers.jsonl    registry of started workers
  owned-local-paths.txt, sensitive-cleanup-paths.txt
  config/ workflows/ workspaces/ logs/ state/ fakes/ installer-sandboxes/ install/
  private-evidence/<scenario-id>/   raw command output, prompts, and responses
```

The public files are written through a redaction pass that replaces run-root and repository paths,
Trello links and ids, token-shaped values, email addresses, and other absolute host paths. At the end
of a run, `scripts/check-private-context` scans the public files and issue drafts; the result is in
the final report and changes the exit code. Everything under `target/` is ignored by Git.

Every command runs with `HOME`, `XDG_*`, and `SYMPHONY_HOME` pointing into
`installer-sandboxes/`. The source install still uses the operator's Maven cache through
`MAVEN_USER_HOME`, so a run does not download every dependency again.

## Statuses

| Status | Meaning |
| --- | --- |
| `covered` | all assertions passed |
| `covered-with-caveat` | passed with a caveat recorded in the row |
| `known-bug` | reproduced a known bug that the row links; not a clean pass |
| `harness-invalid` | the harness could not produce a trustworthy result |
| `interrupted` | stopped by `--time-budget`, the ten-minute row limit, or a signal |
| `corrected` | passed on `--resume` after an earlier `harness-invalid` result |
| `failed` | unexpected product failure; a new finding to triage |
| `skipped` | a requirement or opt-in was missing |
| `not-yet-automated` | listed in the manifest without automation yet |

A row that hits the ten-minute limit keeps running in the background, so the run starts no further
row and reports the rest as `interrupted`. Run `--cleanup-only` once that row has stopped.

The run exits 0 when no row is `failed`, `harness-invalid`, or `interrupted`, cleanup left nothing
open or running, and the private-context scan found nothing. It exits 130 after an interruption and
1 otherwise.

A `failed` row is a candidate bug, not a confirmed one. Reproduce it twice, for example with
`run.sh --scenario <id>`, then draft an issue under `issues/` with the live-bugbash skill.

## Known bugs

A row with `expected: known-bug` must link the open GitHub issue in `linked_issue`. While the bug
reproduces, the row reports `known-bug`. When it stops reproducing, the row reports
`covered-with-caveat` and asks for the manifest row to change to `expected: covered`. Only failed assertions count as a reproduction: a product failure that stops
the row before its assertions, such as a timeout, is reported as `failed` even on a known-bug row.
A row with `expected: covered` may keep `linked_issue` to name the fixed bug it guards against.

## Cleanup, resume, and soak

Cleanup runs at the end of every run, also after a failure or an interruption. It stops registered
workers, terminates any process that still belongs to the run, archives every registered board, and
deletes registered sensitive copies such as env files and the Codex auth copy. A process belongs to
the run when it is a Java or Codex program whose command line contains the run root, or a
`codex app-server` or `codex exec` process whose working directory is inside the run root. Shells,
editors, and an interactive Codex session opened inside a run directory are left alone. Running cleanup again is safe:

```bash
scripts/live-bugbash/run.sh --cleanup-only <run-id>
```

`--resume <run-id>` reruns the rows of an earlier run that did not pass or reproduce a known bug and
keeps the others.

The passive soak checks Trello auth through the installed CLI, queries every registered board,
scans for run-owned processes, and, with `--codex real`, appends one line to a run-owned file with
real Codex. It runs until `--soak-duration` or `--soak-until`, with `--soak-interval` between cycles,
and reports per-cycle results in `progress.md`:

```bash
scripts/live-bugbash/run.sh --profile soak --soak-duration 10m --trello real --codex real
```

`--time-budget 10m` hard-stops a whole run; rows that have not finished by then are reported as
`interrupted`.

## Manifest rows

Each row in `manifest.yml` has these fields:

| Field | Content |
| --- | --- |
| `id` | stable kebab-case scenario id |
| `family` | one of the families in the manifest |
| `profiles` | profiles that select the row |
| `requires` | `trello`, `codex` (`false`, `fake`, or `real`), `github`, `network`, optional `hardened_host` |
| `mode` | execution mode, which also fixes the run order |
| `description`, `setup`, `action`, `cleanup` | what the row checks and how |
| `assertions` | the expectations, as a list |
| `evidence` | public and private evidence locations; the manifest `defaults` fill them |
| `expected` | `covered`, `covered-with-caveat`, or `known-bug` |
| `linked_issue` | GitHub issue number, required for `known-bug` |
| `harness_only` | `true` for cleanup and report rows that the coverage percentages leave out |
| `notes` | optional context, such as why a row is not automated yet |

## Adding a scenario

1. Add the row to `manifest.yml`.
2. Implement it in `scripts/live-bugbash/scenarios/<family>.ts` under the same id. A scenario
   receives a context with `check` for assertions, `evidence` for private output, and helpers for
   the CLI, the Trello API, and shared fixtures. It returns a one-line public summary.
3. Use the helpers in `scenarios/support.ts`: `serviceBoard` creates a registered board, connects it
   with `import-board`, and points the workflow at the selected Trello endpoint and a per-fixture
   fake Codex. `script` gives one card a scripted fake-Codex turn, for example a tool call or a
   malformed response.
4. Pass `--endpoint` to every CLI command that talks to Trello, so a fake run never reaches the real
   API.
5. Run `pnpm run verify:scripts` and the row itself with `run.sh --scenario <id>`.

Several rows can share one worker through `context.fixture`, which runs a setup once per run.
Repository-source and Trello-tool rows use this to put all their cards on one board.
