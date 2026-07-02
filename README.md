# Symphony for Trello

Symphony for Trello lets Codex work from Trello cards. Use it when prompts and terminal history are
not enough, and you want a clear board that shows what Codex should do, what is running, and what is
ready for review.

<p align="center">
  <video src="docs/assets/readme-demo.mp4" poster="docs/assets/readme-demo-poster.png" width="900" controls muted playsinline>
    <a href="docs/assets/readme-demo.mp4">Watch the Symphony for Trello demo video.</a>
  </video>
</p>

Use it when you want to:

- Plan Codex work in Trello.
- Reuse an existing engineering board.
- Create a simple board just for Codex tasks.
- Let Codex pick up ready cards without starting each one by hand.
- Keep each card in its own local workspace.
- Have Codex comment on the card and move it to review when it is done.
- Check running and retrying work with `symphony-trello status` or the local status page.

Trello is still where you plan and review work. Codex still writes the code. Symphony connects them
so the same workflow can run again and again.

You can use it with one board, or run it for several boards at the same time.

Symphony for Trello is a variant of [OpenAI's Symphony](https://github.com/openai/symphony) adapted
for Trello. The original Symphony spec uses Linear; this project keeps the same orchestration idea
and maps it to Trello boards, lists, and cards. In Trello, a list is the board column that contains
cards, for example `Ready for Codex` or `In Progress`.

Symphony for Trello is an independent project. It is not affiliated with, endorsed by, or sponsored
by OpenAI, Atlassian, or Trello.

Symphony for Trello is preview automation for trusted environments. Start with a disposable board or
a small low-risk project, then expand once the workflow matches how you review and land work.

## How It Works

1. You put a Trello card in a configured active list such as `Ready for Codex`.
2. Symphony polls the board, claims the next eligible card, and creates a local workspace for it.
3. Symphony starts the Codex CLI in app-server mode inside that workspace.
4. Codex receives the `WORKFLOW.md` prompt with the card title, description, comments, and routing
   rules.
5. Codex works in the workspace, keeps one `## Codex Workpad` comment current, and moves the card to
   `In Progress`, `Human Review`, `Blocked`, `Merging`, or `Done` when the workflow says to.
6. Symphony keeps the same Codex session going while the card remains active, then stops when the
   card reaches a review, blocked, terminal, or otherwise inactive list.
7. You watch running and retrying cards with `symphony-trello status`, the local status page, or
   JSON API.

## Table of Contents

- [How It Works](#how-it-works)
- [What You Get](#what-you-get)
- [Quick Start](#quick-start)
- [Trello Setup](#trello-setup)
- [Using Symphony Day To Day](#using-symphony-day-to-day)
- [Installer Reference](#installer-reference)
- [Advanced Setup](#advanced-setup)
- [Workflow Contract](#workflow-contract)
- [Advanced Configuration](#advanced-configuration)
- [Operations](#operations)
- [Safety Posture](#safety-posture)
- [Production Deployment](#production-deployment)
- [Building 1.0.0 by the Numbers](#building-100-by-the-numbers)
- [Contributing and Security](#contributing-and-security)

## What You Get

All Symphony for Trello features work with Trello's Free plan.

- Guided local setup that can create a Trello board for you or connect an existing board.
- A repeatable Trello flow from `Ready for Codex` to active work, human review, blocked, merge, and
  done lists.
- One local workspace per Trello card so Codex work is separated by task.
- Optional GitHub pull request flow for repository-changing work.
- Trello comments that show progress, blockers, validation, and handoff notes on the card.
- A local worker status page and JSON API for running, retrying, blocked, and finished work.
- Configurable workflow files for board lists, workspace paths, Codex settings, concurrency, and
  safe local file access.

## Quick Start

Use the installer for the shortest path to a working local board. It installs Symphony for Trello,
checks the required tools, runs guided setup, and starts the local worker.

macOS, Linux, and Windows through WSL2:

```bash
curl -fsSL https://symphony-trello.fmartin.ch/install.sh | bash
```

For Windows, WSL2 is recommended. Run the Linux installer inside WSL2.
Native Windows PowerShell is best effort; see [Installer Reference](#installer-reference).

Follow the prompts. When setup finishes, create a Trello card and move it to `Ready for Codex`.
Symphony should move the card to `In Progress`, add or update one `## Codex Workpad` Trello comment
with what Codex is doing, then move the card to the next review, blocked, merge, or done list when
the workflow says to.

If the card does not move or the workpad comment does not appear, run `symphony-trello status` and
`symphony-trello logs`. For issue reports, see [Operations](#operations).

## Trello Setup

Trello has three concepts that matter for Symphony:

- A **Workspace** groups boards and is also where Trello lets you create a custom Power-Up for API
  access.
- A **Board** is the project or queue Symphony polls.
- **Lists** are the lanes on the board that contain cards. `WORKFLOW.md` and tool fields use names
  like `active_list_ids`, `allowed_move_list_names`, `list_id`, and `list_name`.

Symphony reads cards, creates local Codex workspaces, and runs Codex. When GitHub integration is
configured, the generated workflow tells Codex to commit the change, create or update a pull request,
leave a Trello handoff comment, and move the card to `Human Review` when the PR is ready for a
person. Without GitHub integration, the workflow stays local/non-GitHub and does not create the
GitHub-specific `Merging` list.
When several active Trello lists have cards, Symphony handles later workflow lists first, such as
`Merging` before `In Progress` before `Ready for Codex`, then uses priority labels and Trello card
position within the same list.

Start with the browser setup below. Those steps create the Trello credentials used by both board
setup paths.

### One-Time Browser Setup: Workspace, API Key, Token

Complete these browser steps once before creating the recommended board or importing an existing
board. Symphony can create boards and write `WORKFLOW.md`, but Trello requires you to create the
Workspace and authorize the API token in the browser.

1. Sign in to Trello in your browser.
2. [Create a Workspace](https://support.atlassian.com/trello/docs/creating-a-new-workspace/) if you
   do not already have one that you want to use with Symphony.

   The Workspace owns the Trello app/admin entry that provides the API key. If you create a new
   board with Symphony, the board is created in this Workspace. If you import an existing board, the
   token you generate here must be able to access that board. It is fine to use a Workspace that also
   contains non-Symphony boards; Symphony only polls the board configured in `WORKFLOW.md`. Create a
   separate Workspace when you want cleaner separation of automation credentials, collaborators, or
   boards.

3. Open the [Trello apps administration page](https://trello.com/power-ups/admin).
4. If Trello asks you to accept or complete a developer agreement before creating an app, complete
   that browser flow.
5. Click `New` to create a new app/admin entry for the Workspace.
6. Fill the required fields with clear, recognizable values:

   - `Name`: `Symphony for Trello Automation`
   - `Workspace`: the Workspace you created or chose above
   - `Email` / `Support Contact`: an email you control
   - `Author`: your name or your team name
   - `iframe Connector URL`: leave this blank. Symphony only needs REST API credentials and does not
     need a board-enabled Power-Up UI.

7. Create the app/admin entry.
8. Open its `API Key` tab and choose `Generate a new API Key`.
9. If Trello warns that generating a key replaces the API key used for Personal Data Storage and
   GDPR compliance, continue when this is the new `Symphony for Trello Automation` app/admin entry.
   For an existing app already used elsewhere, rotating the key means those uses must be updated.
10. Copy the API key somewhere temporary. The API key identifies the app, but the token is the
    sensitive credential.
11. On the same API key page, click the `Token` link below the key.
12. Review the authorization screen. Confirm it shows `Symphony for Trello Automation`, your Trello
    account, and permissions to make comments and create or update cards, lists, boards, and
    Workspaces.
13. Click `Allow`.
14. Copy the generated token. Treat it like a password: it grants access as your Trello account to
    boards and Workspaces your account can access.
15. Save both values in the `.env` file read by the command you will run:

    - Installed `symphony-trello` command: `$HOME/.local/share/symphony-trello/config/.env`,
      unless you installed with a custom `SYMPHONY_HOME` or `SYMPHONY_TRELLO_CONFIG_DIR`.
    - Source checkout with Maven: the project-root `.env`.

```properties
TRELLO_API_KEY=replace-with-generated-key
TRELLO_API_TOKEN=replace-with-generated-token
```

### Fast Path: Create The Recommended Board

Use this path when you are new to Trello or want Symphony to create a clean `Inbox` -> `Ready for
Codex` -> `In Progress` -> `Blocked` -> `Human Review` -> `Merging` -> `Done` board for you. One
command creates the board, creates the recommended lists, and writes a workflow file for that
board. This direct command keeps the GitHub PR flow enabled by default. Use `setup-local` or pass
`--no-github` when you want a local/non-GitHub workflow without `Merging`.

Now create the board and workflow:

```bash
symphony-trello new-board --name "Symphony Work Queue"
```

For a non-GitHub board:

```bash
symphony-trello new-board --name "Symphony Work Queue" --no-github
```

If your token can access exactly one Workspace, Symphony uses it automatically. If your token can
access multiple Workspaces, the command stops and asks for `--workspace-id`. Show the available ids:

```bash
symphony-trello list-workspaces
symphony-trello new-board --name "Symphony Work Queue" --workspace-id workspace-id-from-list-workspaces
```

The command creates this Trello board layout:

1. `Inbox`
2. `Ready for Codex`
3. `In Progress`
4. `Blocked`
5. `Human Review`
6. `Merging`
7. `Done`

It also writes a workflow with `Ready for Codex`, `In Progress`, and `Merging` as active lists,
`Done` as the terminal list, `In Progress`, `Human Review`, `Blocked`, and `Done` as allowed move
lists, `./workspaces` as the local workspace directory, a stable HTTP status port, and
`max_concurrent_agents: 1`. With that default, Symphony starts one card at a time from this board.
With `--no-github`, `Merging` is not created, the active lists are `Ready for Codex` and
`In Progress`, and the workflow does not require PR publication or landing.
During guided `setup-local`, `--no-in-progress` can create a local board without `In Progress`; in
that case picked-up cards stay in the active list until they move to review or blocked.

The first run writes `WORKFLOW.md`. If that file already exists and you did not pass `--workflow`,
Symphony keeps the existing file and writes a board-specific file instead. For a board named
`My Project`, the next file is `WORKFLOW.my-project.md`. If that file also exists, Symphony adds a
number, such as `WORKFLOW.my-project-2.md`. Very long board-name slugs are shortened with a stable
hash so the generated workflow file name stays filesystem-safe. Symphony also chooses the first
unused status port from `18080`, `18081`, `18082`, and so on by checking other workflow files in the
same folder. Use `--server-port` when you want to choose the port yourself.

Pass `--force` only when you intentionally want to replace the selected workflow file:

```bash
symphony-trello new-board --name "Symphony Work Queue" --force
```

Start Symphony with the command printed under `Next` after the file is generated. It looks like
this:

```bash
symphony-trello start --env .env --workflow WORKFLOW.md
```

Use the generated board like this:

1. Put raw ideas or incomplete tasks in `Inbox`.
2. Move only cards that are ready for agent work into `Ready for Codex`.
3. Symphony starts Codex for cards in `Ready for Codex`.
4. Symphony moves the card to `In Progress` before Codex starts, so the board shows active work.
5. Codex works in a local workspace, creates or updates a pull request for repository-changing work,
   adds a Trello comment with its summary and verification notes, and moves the card to
   `Human Review` when the PR is ready for human review.
6. If PR checks fail because of the card's changes or current branch, Codex keeps the card in
   `In Progress`, reruns the failing check or closest local equivalent, fixes reproducible failures,
   pushes again, and repeats the review sweep. If the related CI failure passes locally and looks
   flaky, Codex can move the card to `Human Review` with that caveat. If CI cannot run, Codex runs
   equivalent local CI checks. If CI fails for a clearly unrelated reason, Codex does not try to
   reproduce that unrelated failure locally; it explains why the failure is unrelated in the Trello
   comment.
7. If Codex cannot safely finish the work, it adds a blocker comment and moves the card to
   `Blocked` so the problem is visible from the board.
8. If changes are needed, a human moves the card from `Human Review` back to `Ready for Codex`.
   Codex treats that as rework: it rereads the updated card, new Trello comments, the existing
   workpad, and linked PR feedback before changing code again. It normally updates the existing PR
   instead of starting over.
9. If the work is accepted and should be landed by Codex, a human moves the card to `Merging`.
   Codex treats `Merging` as the approval signal, runs the landing skill, checks PR feedback and CI,
   resolves addressed GitHub review threads when GitHub allows it, follows the repository's merge
   policy, and moves successful landed work to `Done`.
10. If you land outside Codex instead, move the card to `Done` yourself after the work is accepted and
   landed.

### Fast Path: Import An Existing Board

Use this path when you already have a Trello board and want Symphony to write a starter
[`WORKFLOW.md`](#workflow-contract) for that board.

1. Copy the board short link from the board URL.
   In `https://trello.com/b/SYNTH001/my-board`, use `SYNTH001`.
2. Run the import command:

```bash
symphony-trello import-board --board SYNTH001 --active "Ready for Codex" --in-progress "In Progress" --terminal Done --blocked Blocked
```

Add `--no-github` when the imported board should not use GitHub PR publication or landing:

```bash
symphony-trello import-board --board SYNTH001 --active "Ready for Codex" --in-progress "In Progress" --terminal Done --blocked Blocked --no-github
```

You may omit `--active` when the board already has a list named `Ready for Codex`. You may omit
`--in-progress` when the board already has a list named `In Progress`. You may omit `--terminal`
when the board already has a list named `Done`. You may omit `--blocked` when the board already has
a list named `Blocked`. If your board has no in-progress list, Codex leaves picked-up cards in
the active list until it moves them to review or blocked. If your board has no blocked list,
import falls back to the review handoff list, so blocked cards still leave the active list.
Create a blocked list or pass
`--blocked` when you want blocked work separated from reviewable work.

For an existing team board, be deliberate about `--active`: every open card in that list is eligible
for Codex work. A conservative import starts with a new list named `Ready for Codex` and only moves
cards there after they have a clear title, useful description, and acceptance criteria.

When the imported board has a list named `Human Review`, the starter workflow allows Codex to move
reviewable work there after it has created or updated a PR for repository-changing work. Boards that
still use a `Review` list remain supported when `Human Review` is absent. If there is no obvious
review list, the generated workflow keeps Trello writes disabled until you choose one. Do not run a
write-disabled workflow with blocked cards left in `Ready for Codex` unless you plan to move them
manually; they can be picked up again.

When the imported board has a list named `Merging` and a terminal list such as `Done`, the
starter workflow treats `Merging` as the human approval list for landing and allows Codex to move
landed work to the terminal list. Boards without `Merging`, or without a terminal list, still
work for implementation and human review; landing stays a manual step unless you add both lists to
the workflow.

Common setup command options:

- `--workflow PATH`: write the generated workflow file somewhere other than `WORKFLOW.md`. Use this
  when you want to choose the exact file name yourself. If that file exists, Symphony stops unless
  you also pass `--force`.
- `--workspace-root PATH`: choose where Symphony creates the local work directory for each Trello
  card. The generated workflow uses `./workspaces`; choose another path when you want those
  checkouts on a different disk or clearly separated from the repository.
- `--server-port PORT`: choose the HTTP status port written into the generated workflow. Use a port
  from `1024` through `65535`. If you omit it, Symphony uses the first unused workflow port starting
  at `18080`.
- `--max-agents N`: choose how many cards from this board are processed concurrently. Guided setup
  asks about this when you do not pass the option. Keep `1` until your machine can run several Codex
  sessions, builds, tests, package installs, and network calls at once. If cards depend on other
  cards, add prerequisite checklist items before moving them into `Ready for Codex`.
- `--codex-model MODEL`: write a Codex model into generated workflows without prompting. Omit it
  during guided setup to accept or edit the recommended model interactively.
- `--codex-reasoning-effort EFFORT`: write a Codex reasoning effort into generated workflows
  without prompting. Omit it during guided setup to accept or edit the recommended reasoning effort.
- `--force`: replace an existing workflow file. Use this only when you are fine losing the current
  generated workflow content.
- `--key` and `--token`: pass Trello credentials directly for this one command instead of reading
  them from `.env` or environment variables.
- `--workspace-id ID`: choose the Trello Workspace for a new board when your token can access more
  than one Workspace.
- `--active NAME`: during `import-board`, choose an exact Trello list name that Symphony may poll
  for ready work. Repeat `--active` for multiple active lists.
- `--terminal NAME`: during `import-board`, choose an exact Trello list name that means work is done.
  Repeat `--terminal` for multiple terminal lists.
- `--in-progress NAME`: during `import-board`, choose the Trello list where Codex should move a
  card immediately after it is picked up. If you omit it, import uses `In Progress` when the board has
  that list. If no in-progress list is configured, the card stays in the active list while
  Codex works.
- `--no-in-progress`: during guided `setup-local` or `import-board`, do not configure pickup moves
  even when the board has an `In Progress` list. For a new guided local board, the `In Progress` list
  is not created.
- `--blocked NAME`: during `import-board`, choose the Trello list where Codex should move cards it
  cannot safely finish. If you omit it, import uses a list named `Blocked` when the board has one.

## Using Symphony Day To Day

This section explains the board features you use after setup. It does not cover every configuration
possibility; use [Advanced Configuration](#advanced-configuration) when you need to change how the
workflow is wired.

The recommended board gives you a simple flow:

- `Inbox`: collect ideas, rough tasks, and notes that are not ready for Codex yet.
- `Ready for Codex`: put cards here when they have enough context for Codex to start.
- `In Progress`: Symphony moves a card here when Codex is actively working on it.
- `Blocked`: Codex moves a card here when it cannot safely continue without a human or environment
  fix.
- `Human Review`: Codex moves work here when it is ready for a person to review. If you find
  something that needs more work, comment on the Trello card or on the linked PR, then move the card
  back to `Ready for Codex`.
- `Merging`: move a reviewed card here when you want Codex to do final checks and merge the pull
  request.
- `Done`: finished work. For PR-based work, Codex moves the card here after you move it to
  `Merging` and the PR merges successfully. For work that does not need a PR, move it here after
  human review is complete.

For a normal coding task, write the Trello card like you would write a clear issue or a prompt you
would normally send to a coding agent. Include the goal, important constraints, what should not
change, and how you expect the result to be verified. Then move the card to `Ready for Codex`.
Symphony picks it up, moves it to `In Progress`, gives Codex the card title, description, comments,
and workflow instructions, and keeps the card visible on the board while work is running.

Codex keeps one Trello comment named `## Codex Workpad` up to date. The workpad is the best place to
look while a card is running: it should say what Codex is doing, what it has learned, what remains,
and whether it is blocked. Symphony updates that same comment instead of creating a long stream of
progress comments.

Use normal Trello checklists when one card must wait for another. Add a checklist such as
`Must finish first`; the checklist name is only for people. Put one bare Trello card reference in
each prerequisite item, for example `https://trello.com/c/abc123`. Symphony waits until each linked
card is in a list that this workflow treats as finished, such as `Done`. It keeps the checklist
checkmarks in sync and keeps one waiting comment on the blocked Trello card.

Keep related Trello card links out of prerequisite checklists, or write them as Markdown links such
as `related to [the API card](https://trello.com/c/abc123)`. A bare Trello card link with extra text,
such as `Wait for https://trello.com/c/abc123`, is unclear and blocks until someone fixes the
checklist. Links in descriptions, Trello comments, attachments, and Markdown checklist links are
given to Codex as context. Codex can use them to notice likely missing prerequisites before it edits,
but those links do not block scheduling by themselves.

Use priority labels when one ready card should run before another. The default labels are `p1`,
`p2`, `p3`, `p4`, `priority: critical`, `priority: high`, `priority: medium`, and `priority: low`.
Within the same active Trello list, higher-priority cards run first. When priority is the same,
Trello card order decides. Configure this with
[`tracker.priority_labels`](#dispatch-controls).

With the default concurrency, Symphony works on one card per board at a time. That keeps review and
dependency ordering easy to understand. You can run several boards at once; each connected board has
its own workflow and worker. If you later raise per-board concurrency, make sure your machine can run
several Codex sessions and project test suites at the same time, and make sure the cards do not rely
on each other being completed in a specific order. Configure this with
[`agent.max_concurrent_agents`](#dispatch-controls) and
[`agent.max_concurrent_agents_by_state`](#dispatch-controls).

If setup found an authenticated GitHub CLI, your workflow can include GitHub support automatically.
For repository-changing work, Codex can then create or update a pull request. `Human Review` means a
person should review the Trello result and the PR. If review feedback needs more work, write the
finding on the Trello card or, when there is a linked PR, on the PR. Then move the card back to
`Ready for Codex`. Codex treats that as rework, rereads the card, comments, workpad, PR feedback,
and checks, and normally updates the existing PR instead of starting over.

Move a card to `Merging` when you have reviewed it and want Codex to merge the PR. `Merging` tells
Codex to do final checks, review PR comments and checks, follow the repository's merge policy, and
move the card to `Done` when the PR merges successfully. If there is exact feedback that was already
on the PR before the card entered `Merging`, Codex can make that fix first and then merge after
clean checks. If the feedback needs a broader change or is unclear, Codex should move the card back
to `Human Review` with the reason. If merging is blocked by an unresolved review, required approval,
failing check, auth problem, or repository rule, Codex should move the card to `Blocked` with the
next human action.

`Blocked` is for work that needs attention before Codex can continue. Common examples are missing
credentials, missing allowed file access, unclear requirements, failing external services, or a PR
state that cannot be resolved automatically. After fixing the problem, move the card back to
`Ready for Codex` so Codex can continue with the new context.

Each card gets its own local workspace. That separation makes it easier to inspect the files for one
task, avoids mixing unrelated changes, and lets Symphony clean up terminal work later. By default,
Codex can write in the card workspace. If a card needs another local repository or shared folder,
allow that path during setup or in the workflow before asking Codex to edit it. Keep broad filesystem
access for trusted boards and machines only. Configure extra write access with
[`codex.additional_writable_roots`](#allowed-host-paths-and-sandbox), and prepare workspaces with
[`hooks.after_create`](#workspace-hooks) or [`hooks.before_run`](#workspace-hooks) when needed.

You can connect more than one Trello board. This is useful when different projects or teams should
have separate queues, workspace roots, GitHub behavior, or status ports. The installer and lifecycle
commands understand connected boards, so you can check, start, stop, inspect logs, and run diagnostics
for all boards or for one selected board.

If your workflow has required labels, Symphony only starts cards that have those labels. For
example, a card in `Ready for Codex` will wait until it has every required label. This is useful on
team boards where many cards are visible but only some are meant for automation. If a required label
is removed from a running or retrying card, Symphony stops treating that card as ready until the
label comes back. Configure this with [`tracker.required_labels`](#dispatch-controls).

Symphony tries again automatically after short waits when a problem is likely temporary. A card may
pause briefly if Codex exits, Trello is temporarily unavailable, a rate limit is hit, or a worker
appears stalled. `symphony-trello status` and the worker's local status page show running and
retrying cards so you can distinguish "still working" from "needs human attention". Configure the
maximum wait with
[`agent.max_retry_backoff_ms`](#dispatch-controls).

Diagnostics are safe to paste into public issues by default. They summarize local setup, connected
boards, workflow files, health probes, and recent logs while hiding secrets and private context. Use
`symphony-trello diagnostics` when asking for help. Use `--deep` when a maintainer needs deeper
public-safe checks. Use `--show-private-context` only locally when you need to map diagnostics hashes
back to your own board, workflow, log file, or PID/state file. Add `--lookup <token>` when you only
need one mapping. Do not paste private-context output into public issues.

## Installer Reference

The installer downloads the latest GitHub Release archive, verifies its SHA3-256 checksum, and
unpacks it into the local app directory. It installs or updates Symphony for Trello, then runs guided
setup and starts the managed local worker unless you pass `--no-onboard`.

For Windows, WSL2 is the recommended setup path. Run the Linux installer inside WSL2, keep Codex CLI
and Git on the WSL2 `PATH`, and use Linux paths for allowed local files and folders. Native Windows
PowerShell is implemented as best effort.

Supported local platforms:

| Platform | Status |
| --- | --- |
| macOS arm64/amd64 | Supported |
| Linux arm64/amd64 | Supported |
| WSL2 on Windows | Supported through the Linux installer |
| Windows amd64 PowerShell | Best effort |
| Windows arm64 PowerShell | Best effort |

Native Windows PowerShell:

```powershell
powershell -c "irm https://symphony-trello.fmartin.ch/install.ps1 | iex"
```

The installer checks a Java 25+ JDK, Codex CLI auth, Trello credentials, and optional GitHub CLI
auth. It checks Git only when you install from a source checkout. When a required local tool is
missing, the installer offers a concrete install command before it continues.

GitHub is not required unless you want Symphony to create and land GitHub pull requests. If GitHub is
not configured, setup creates a Trello board without the GitHub-specific `Merging` list and writes a
non-GitHub starter workflow. If you enable GitHub while importing an existing board, setup creates
the missing `Merging` list when needed.

With WSL2, browser login may open in Windows; if that is awkward, choose device login when setup asks
whether the machine can open a browser. The managed `start`, `stop`, `status`, and `logs` commands
are lightweight per-user processes, not a Windows service or systemd unit.

Setup saves Trello credentials that you type or pass directly. If credentials already come from real
environment variables or an existing `.env` file, setup uses them without copying them into another
file. Workflows, connected-board metadata, workspaces, and logs live under `SYMPHONY_HOME` by
default, separate from the installer-managed app checkout.

The installer writes the command to `$HOME/.local/bin/symphony-trello` on macOS, Linux, and WSL2, or
`$HOME\.local\bin\symphony-trello.ps1` on native Windows PowerShell. If that directory is not on
`PATH`, the installer updates the shell profile or current user PATH when it can. Pass
`--no-update-path` if you want to manage `PATH` yourself. Open a new terminal after PATH setup. If
`symphony-trello` is still not found, add the printed directory to `PATH` manually.

Pass `--version 0.x.y` to install a specific release. Pass `--from-source --repo URL --ref REF` only
when you are developing or testing from a Git checkout instead of the published release archive.

During setup, you can keep the default Codex workspace access or allow extra local files/folders.
Extra paths are opt-in because Trello cards should not make Codex read or edit unrelated files by
accident. You can also opt into `danger-full-access`, but setup asks for confirmation because that
disables Codex's command/filesystem sandbox for that workflow.

To inspect the installer first:

```bash
curl -fsSL https://symphony-trello.fmartin.ch/install.sh -o install.sh
less install.sh
bash install.sh
```

```powershell
irm https://symphony-trello.fmartin.ch/install.ps1 -OutFile install.ps1
notepad install.ps1
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

Pass installer flags like this:

```bash
curl -fsSL https://symphony-trello.fmartin.ch/install.sh | bash -s -- --dry-run
```

```powershell
powershell -c "& ([scriptblock]::Create((irm https://symphony-trello.fmartin.ch/install.ps1))) --dry-run --no-onboard"
```

Useful commands after install:

```bash
symphony-trello setup-local check
symphony-trello setup-local repair-port --board "My Board Name"
symphony-trello status --board "My Board Name"
symphony-trello logs --board "My Board Name"
symphony-trello diagnostics --board "My Board Name" --output diagnostics.txt
symphony-trello diagnostics --workflow WORKFLOW.md --output diagnostics.txt
symphony-trello diagnostics --show-private-context
symphony-trello stop --board "My Board Name"
```

Use `setup-local repair-port --board "My Board Name"` only when `setup-local check` reports that
a board's local HTTP port is already used by another process.

Uninstall removes only the installer-managed command and app checkout by default. It preserves local
`.env` files, workflows, connected-board metadata, workspaces, logs/state, Codex auth, GitHub auth,
Trello boards, and any PATH entry you accepted during install.

```bash
curl -fsSL https://symphony-trello.fmartin.ch/uninstall.sh | bash
```

```powershell
powershell -c "irm https://symphony-trello.fmartin.ch/uninstall.ps1 | iex"
```

To inspect uninstall first or pass cleanup scopes:

```bash
curl -fsSL https://symphony-trello.fmartin.ch/uninstall.sh -o uninstall.sh
less uninstall.sh
bash uninstall.sh --dry-run
bash uninstall.sh --remove-config --remove-workspaces --remove-state
bash uninstall.sh --yes --yes-local-data --remove-all-local-data
```

`--yes` only skips the prompt for installer-managed app files. Add `--yes-local-data` only when you
also want unattended deletion of local `.env` files, workflows, workspaces, state, and logs.

## Advanced Setup

Use this section only when the guided setup, `new-board`, or `import-board` commands do not fit how
you want to connect a board. Most first-time local installs can skip it.

### Option A: Reuse An Existing Board

Use this manual path when your team already has a Trello board for engineering work and you prefer to
write [`WORKFLOW.md`](#workflow-contract) yourself.

1. Pick the board Symphony should poll.
2. Choose one or two list names that mean "Codex may work on this now".
   A low-risk default is a single list named `Ready for Codex`.
3. Choose an optional in-progress list, such as `In Progress`, if you want Trello to show when
   Codex has picked up a card.
4. Choose terminal list names that mean "never run Codex for this card again".
   A common default is `Done`.
5. Choose a non-active list for blocked work, such as `Blocked`. If your board does not have one,
   use your review list so blocked cards still leave the active list.
6. Copy the board short link from the board URL.
   In `https://trello.com/b/SYNTH001/my-board`, the `board_id` value can be `SYNTH001`.
7. Create [`WORKFLOW.md`](#workflow-contract) and set `tracker.board_id`, `tracker.active_states`,
   `tracker.in_progress_state`, `tracker.terminal_states`, and the handoff list names to match
   your board.

Example for an existing board:

```markdown
---
tracker:
  kind: trello
  api_key: $TRELLO_API_KEY
  api_token: $TRELLO_API_TOKEN
  board_id: SYNTH001
  active_states:
    - Ready for Codex
    - In Progress
  in_progress_state: In Progress
  blocker_enforced_states:
    - Ready for Codex
    - In Progress
  terminal_states:
    - Done
    - Archived
    - ArchivedList
    - ArchivedBoard
    - Deleted
workspace:
  root: ./workspaces
polling:
  interval_ms: 5000
trello_tools:
  enabled: true
  allow_writes: true
  allowed_move_list_names:
    - In Progress
    - Human Review
    - Blocked
  allow_comments: true
  allow_checklists: false
  allow_url_attachments: false
codex:
  command: codex app-server
  model: gpt-5.5
  reasoning_effort: medium
---
\# Trello Card

Work on {{ card.identifier }}: {{ card.title }}.

## Description

{{ card.description }}

## Trello Comments

{% for comment in card.comments %}
- {{ comment.created_at }}{% if comment.author %} {{ comment.author }}{% endif %}: {{ comment.text }}
{% endfor %}

## Trello Checklists And Related Cards

Normal Trello checklists are available in `{{ card.checklists }}`. A checklist blocks the card only
when every non-blank item is exactly one bare Trello card reference. Problems with prerequisite
checklists are available in `{{ card.prerequisite_problems }}`.

Trello card links from the title, description, checklists, attachments, and Trello comments are
available in `{{ card.trello_references }}`. Before editing, check whether any of them look like a
missing prerequisite. A later Trello comment such as `Proceed anyway` may ignore only this agent
warning. That comment is not allowed to skip prerequisite checklists, unclear prerequisite
checklists, unresolved prerequisites, or other hard blockers.

## Codex Workpad

Maintain one Trello workpad comment for this card by calling trello_upsert_workpad. Reuse the
existing comment that starts with "## Codex Workpad"; do not create separate progress comments.
Keep it current with the plan, acceptance criteria, progress, validation evidence, blockers, and
handoff notes.

## Repository Source Precedence

Select repository source context in this order:

1. An explicit Trello card repository URL or local checkout path.
2. Workflow `repository.default_url`.
3. Workflow `repository.default_path`.
4. No selected repository.

Name an explicit Trello card source with a line such as `Repository URL: <url>`,
`Repository path: <path>`, `Local checkout: <path>`, or `Repository: <url-or-path>` in the title,
description, or a Trello comment. Ordinary unlabelled web links are not selected as repositories.
Each source declaration is read from one logical line. If multiple declarations are present, they
must all name the same source. URL labels and `repository.default_url` accept credential-free
HTTP(S), username-only `ssh://`, SCP-style SSH such as `git@example.com:team/project.git`, and
`file://` URLs. Path and checkout labels accept local checkout paths. Generic `Repository:` labels
accept either form. HTTP(S) source URLs must not include user info, query strings, or fragments. URI
paths may keep safe percent-encoding, but encoded or literal control characters are invalid.

A valid selected source wins and suppresses lower-priority fallbacks. Do not validate or use an
unselected fallback once a higher-priority source is selected. An invalid explicit Trello card source
blocks instead of falling back to workflow defaults. Do not infer a repository from previous Trello
cards, unrelated host checkouts, branch names, or leftover workspace contents.

Repository preparation is workflow-owned in this phase. For a selected repository URL, create or
reuse a writable checkout under the current per-card workspace. For a selected local checkout path,
treat that path as source context by default and clone from it into the current per-card workspace
before implementation. After cloning from a local checkout, do not inherit the source checkout's
current branch as the task base. Start new task work from the repository's default branch when it is
discoverable unless the Trello card clearly requests another base. Do not edit the shared checkout
directly unless the Trello card explicitly requests direct work, the checkout is writable, and
deployment filesystem policy permits it, including Git metadata writes when the task needs direct
commits. `--add-path <checkout>` grants extra filesystem access, but it is not a direct-checkout
commit guarantee. If Git metadata is not writable, clone into the workspace, leave a patch, or block
with path-safe guidance.

If no source is selected or the selected source is missing, unreadable, unclonable, or lacks required
repository/auth context, move the Trello card to the configured blocker or review fallback with
path-safe guidance. When no move destination is configured, record a path-safe blocker in the workpad
and explain that an operator must move the Trello card to the appropriate blocked list.

## Acceptance Criteria And Validation

Before changing code, extract card-specific acceptance criteria from the title, description, and
Trello comments. Treat any card-authored `Validation`, `Test Plan`, or `Testing` section as
required. For bugs or behavior changes, capture a concrete current-state signal before editing code.
Track the acceptance criteria, required validation, current-state signal, and final validation
evidence in the workpad and handoff comment. If broad validation fails for reasons clearly unrelated
to the card, record the failure and the narrower passing validation instead of blocking. If required
validation cannot be performed, treat the work as blocked.

When `tracker.in_progress_state` is configured, Symphony moves cards from "Ready for Codex" to
"In Progress" before Codex starts. If the card is already in "In Progress", continue the existing
execution flow.

If a human moves a reviewed card from "Human Review" back to "Ready for Codex" or "In Progress",
treat the next run as rework. Reread the card, new Trello comments, existing workpad, and linked PR
feedback before changing code again.

For repository-changing work, "Human Review" means there is a pull request ready for a person.
Commit the change, push the branch, create or update the PR, and include the PR URL in the workpad
and handoff comment before moving the card. When the target repository has a pull request template
in GitHub-supported repository-local locations, inspect the default/base template source, preserve
the selected template's headings, checklists, and prompts, and fill concrete task details and
validation. If multiple directory templates exist and no Trello card or repository instruction
selects one, block instead of guessing. This does not apply when the card explicitly asks for
local-only or no-push work.

When the work is ready for human review, update the workpad with the final summary, validation
evidence, and PR URL when applicable, call trello_add_comment with a concise summary and verification
notes, then call trello_move_current_card with list_name "Human Review". If the work is blocked or
unsafe to hand off, update the workpad with the blocker, add a Trello comment explaining the blocker,
then call trello_move_current_card with list_name "Blocked". If the blocker is a local filesystem access
problem, the Trello comment must explain that the requested file or folder is inaccessible because
deployed Symphony blocks undeclared host paths by default for security reasons, so Trello cards
cannot make Codex read or edit unrelated host files. Tell the operator to use files already available
in the per-card workspace or ask an operator to allow the needed file or folder with the manual
deployment settings `BindPaths`, `ReadWritePaths`, and
`SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS`, as documented in
`docs/deployment.md#allow-host-path-access`. Do not copy absolute host paths, per-card workspace
locations, account names, or deployment-specific paths into Trello comments or the workpad; use
labels such as "the requested path" and "the per-card workspace" instead.

Card URL: {{ card.url }}
```

In this workflow config, `allowed_move_list_names` and the tool argument `list_name` use Trello's API
term for board lists.

Operationally, use the board like this:

1. Put new ideas wherever your team normally triages work.
2. Move a card to `Ready for Codex` only when the title and description are clear enough for an
   engineer to start.
3. Watch the card move to `In Progress` after Codex picks it up.
4. Watch Symphony at `http://127.0.0.1:18080/`; see [Operations](#operations) for the available
   status endpoints.
5. Review the card after Codex moves it to `Human Review` or `Blocked`.
6. Move the card out of active lists if you want to pause or prevent further retries.
7. Move the card to `Done` when the generated work has been reviewed and accepted.

### Option B: Create A New Beginner-Friendly Board

Use this manual path when you want a clean board designed for Symphony from the start but do not want
the setup command to create it for you.

Create a board named `Symphony Work Queue` and add these lists in this order:

1. `Inbox`
2. `Ready for Codex`
3. `In Progress`
4. `Blocked`
5. `Human Review`
6. `Merging`
7. `Done`

Recommended meaning:

- `Inbox`: rough tasks, ideas, or incomplete cards. Symphony ignores this list.
- `Ready for Codex`: cards that are ready to run. Symphony polls this list.
- `In Progress`: active Codex work. If an attempt fails and waits for a retry, Symphony moves the
  card back to the queue list when it can.
- `Blocked`: cards Codex could not safely finish. Symphony ignores this list.
- `Human Review`: work produced by Codex that needs human review. Symphony ignores this list.
- `Merging`: human-approved work that is ready for the landing flow.
- `Done`: finished cards. Symphony treats this as terminal.

For each task card, use a title that reads like a pull request title, then put the useful details in
the description:

```markdown
Goal:
- What should change?

Acceptance criteria:
- What must be true when this is done?

Verification:
- Which command or manual check should pass?

Notes:
- Links, constraints, or gotchas.
```

Codex treats card-written `Validation`, `Test Plan`, and `Testing` sections as required. For bugs or
behavior changes, include the current behavior you expect Codex to reproduce or capture before it
edits code. If a required check needs credentials, files, or tools that are not available to the
deployed service, Codex should move the card to `Blocked` instead of `Human Review`.

If a card already has a pull request or branch, put the PR URL or branch name in the description or a
Trello comment. Codex will use that link to sweep PR comments, inline review feedback, Codex review
comments, and checks before it moves the card to `Human Review`.

Use this [`WORKFLOW.md`](#workflow-contract) starter for the new board:

```markdown
---
tracker:
  kind: trello
  api_key: $TRELLO_API_KEY
  api_token: $TRELLO_API_TOKEN
  board_id: replace-with-board-shortlink
  active_states:
    - Ready for Codex
    - In Progress
    - Merging
  in_progress_state: In Progress
  blocker_enforced_states:
    - Ready for Codex
    - In Progress
    - Merging
  terminal_states:
    - Done
    - Archived
    - ArchivedList
    - ArchivedBoard
    - Deleted
workspace:
  root: ./workspaces
trello_tools:
  enabled: true
  allow_writes: true
  allowed_move_list_names:
    - In Progress
    - Human Review
    - Blocked
    - Done
  allow_comments: true
  allow_checklists: false
  allow_url_attachments: false
agent:
  max_concurrent_agents: 1
codex:
  command: codex app-server
  model: gpt-5.5
  reasoning_effort: medium
  approval_policy: never
  turn_sandbox_policy:
    type: workspaceWrite
    networkAccess: true
  turn_timeout_ms: 3600000
  read_timeout_ms: 5000
  stall_timeout_ms: 300000
---
\# Trello Card

You are working on {{ card.identifier }}: {{ card.title }}.

## Description

{{ card.description }}

## Trello Comments

{% for comment in card.comments %}
- {{ comment.created_at }}{% if comment.author %} {{ comment.author }}{% endif %}: {{ comment.text }}
{% endfor %}

## Trello Checklists And Related Cards

Normal Trello checklists are available in `{{ card.checklists }}`. A checklist blocks the card only
when every non-blank item is exactly one bare Trello card reference. Problems with prerequisite
checklists are available in `{{ card.prerequisite_problems }}`.

Trello card links from the title, description, checklists, attachments, and Trello comments are
available in `{{ card.trello_references }}`. Before editing, check whether any of them look like a
missing prerequisite. A later Trello comment such as `Proceed anyway` may ignore only this agent
warning. That comment is not allowed to skip prerequisite checklists, unclear prerequisite
checklists, unresolved prerequisites, or other hard blockers.

## Codex Workpad

Maintain one Trello workpad comment for this card by calling trello_upsert_workpad. Reuse the
existing comment that starts with "## Codex Workpad"; do not create separate progress comments.
Keep it current with the plan, acceptance criteria, progress, validation evidence, blockers, and
handoff notes. Do not include private host paths; use sanitized workspace or repository names when
context is needed.

Read the Trello description carefully, inspect the repository, make the smallest maintainable change,
run relevant verification, and leave the workspace in a reviewable state.
## Repository Source Precedence

Select repository source context in this order:

1. An explicit Trello card repository URL or local checkout path.
2. Workflow `repository.default_url`.
3. Workflow `repository.default_path`.
4. No selected repository.

Name an explicit Trello card source with a line such as `Repository URL: <url>`,
`Repository path: <path>`, `Local checkout: <path>`, or `Repository: <url-or-path>` in the title,
description, or a Trello comment. Ordinary unlabelled web links are not selected as repositories.
Each source declaration is read from one logical line. If multiple declarations are present, they
must all name the same source. URL labels and `repository.default_url` accept credential-free
HTTP(S), username-only `ssh://`, SCP-style SSH such as `git@example.com:team/project.git`, and
`file://` URLs. Path and checkout labels accept local checkout paths. Generic `Repository:` labels
accept either form. HTTP(S) source URLs must not include user info, query strings, or fragments. URI
paths may keep safe percent-encoding, but encoded or literal control characters are invalid.

A valid selected source wins and suppresses lower-priority fallbacks. Do not validate or use an
unselected fallback once a higher-priority source is selected. An invalid explicit Trello card source
blocks instead of falling back to workflow defaults. Do not infer a repository from previous Trello
cards, unrelated host checkouts, branch names, or leftover workspace contents.

Repository preparation is workflow-owned in this phase. For a selected repository URL, create or
reuse a writable checkout under the current per-card workspace. For a selected local checkout path,
treat that path as source context by default and clone from it into the current per-card workspace
before implementation. After cloning from a local checkout, do not inherit the source checkout's
current branch as the task base. Start new task work from the repository's default branch when it is
discoverable unless the Trello card clearly requests another base. Do not edit the shared checkout
directly unless the Trello card explicitly requests direct work, the checkout is writable, and
deployment filesystem policy permits it, including Git metadata writes when the task needs direct
commits. `--add-path <checkout>` grants extra filesystem access, but it is not a direct-checkout
commit guarantee. If Git metadata is not writable, clone into the workspace, leave a patch, or block
with path-safe guidance.

If no source is selected or the selected source is missing, unreadable, unclonable, or lacks required
repository/auth context, move the Trello card to the configured blocker or review fallback with
path-safe guidance. When no move destination is configured, record a path-safe blocker in the workpad
and explain that an operator must move the Trello card to the appropriate blocked list.

## Acceptance Criteria And Validation

Before changing code, extract card-specific acceptance criteria from the title, description, and
Trello comments. Treat any card-authored `Validation`, `Test Plan`, or `Testing` section as
required. For bugs or behavior changes, capture a concrete current-state signal before editing code.
Track the acceptance criteria, required validation, current-state signal, and final validation
evidence in the workpad and handoff comment. If broad validation fails for reasons clearly unrelated
to the card, record the failure and the narrower passing validation instead of blocking. If required
validation cannot be performed, treat the work as blocked.

When `tracker.in_progress_state` is configured, Symphony moves cards from "Ready for Codex" to
"In Progress" before Codex starts. If the card is already in "In Progress", continue the existing
execution flow.

If a human moves a reviewed card from "Human Review" back to "Ready for Codex" or "In Progress",
treat the next run as rework. Reread the card, new Trello comments, existing workpad, and linked PR
feedback before changing code again.

Only land work when the card is in "Merging". Before landing, sweep PR comments, review threads, and
checks, resolve addressed GitHub review threads when GitHub allows it, run the card-specific
validation, follow the repository's merge policy, and move successful landed work to "Done". If
landing cannot safely proceed, move the card to "Blocked" with a concise blocker.

For repository-changing work, "Human Review" means there is a pull request ready for a person.
Commit the change, push the branch, create or update the PR, and include the PR URL in the workpad
and handoff comment before moving the card. When the target repository has a pull request template
in GitHub-supported repository-local locations, inspect the default/base template source, preserve
the selected template's headings, checklists, and prompts, and fill concrete task details and
validation. If multiple directory templates exist and no Trello card or repository instruction
selects one, block instead of guessing. This does not apply when the card explicitly asks for
local-only or no-push work.

When the work is ready for human review, update the workpad with the final summary, validation
evidence, and PR URL when applicable, call trello_add_comment with a concise summary and verification
notes, then call trello_move_current_card with list_name "Human Review". If the work is blocked or
unsafe to hand off, update the workpad with the blocker, add a Trello comment explaining the blocker,
then call trello_move_current_card with list_name "Blocked". If the blocker is a local filesystem access
problem, the Trello comment must explain that the requested file or folder is inaccessible because
deployed Symphony blocks undeclared host paths by default for security reasons, so Trello cards
cannot make Codex read or edit unrelated host files. Tell the operator to use files already available
in the per-card workspace or ask an operator to allow the needed file or folder with the manual
deployment settings `BindPaths`, `ReadWritePaths`, and
`SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS`, as documented in
`docs/deployment.md#allow-host-path-access`. Do not copy absolute host paths, per-card workspace
locations, account names, or deployment-specific paths into Trello comments or the workpad; use
labels such as "the requested path" and "the per-card workspace" instead.

Card URL: {{ card.url }}
```

In this workflow config, `allowed_move_list_names` and the tool argument `list_name` use Trello's API
term for board lists.

Start with `max_concurrent_agents: 1`. If two cards are in an active list such as `Ready for Codex`,
that default makes Symphony run one card and leave the other waiting until a slot is free. Raising
the value to `2` lets two cards from the same configured board run at the same time; raising it to
`N` allows up to `N` simultaneous Codex sessions for that process. Raise it only after
one-card-at-a-time runs are routine and predictable.

Within the available slots, Symphony starts cards in a predictable order. Priority labels run first
when you use them. Otherwise, it follows the configured active-list order, then the order of cards
in the Trello list from top to bottom. If there is still a tie, older cards run first.

List routing for the recommended board:

- `Inbox`: rough ideas or incomplete tasks. Symphony ignores this list.
- `Ready for Codex`: queued work. Symphony dispatches from here and moves the card to
  `In Progress`.
- `In Progress`: work currently running in Codex. If an attempt is waiting for retry or no
  concurrency slot is available, Symphony moves the card back to the queue list when it can.
- `Blocked`: work that needs a human or environment fix. Symphony ignores it until a human moves it
  back to an active list.
- `Human Review`: work ready for a person. Symphony does not code from this list.
- `Merging`: human approval for landing. Codex can land only from this list when it is configured
  as active.
- `Done`: terminal work. Symphony treats it as complete and removes matching workspaces during
  terminal cleanup.

Each running card keeps one Codex app-server session while it remains active. After a successful
turn, Symphony refreshes the Trello card. If the card is still in an active list and the worker has
not reached `agent.max_turns`, Symphony sends a short continuation prompt to the same Codex thread.
If the card moved to `Human Review`, `Blocked`, `Done`, or another non-active list, that worker
stops.

## Workflow Contract

Symphony reads `WORKFLOW.md` from the working directory unless `SYMPHONY_WORKFLOW_PATH` points to a
different file. Each running process uses one workflow file. For Trello, that workflow contains one
`tracker.board_id`, so one process polls one Trello board.

For multiple boards or projects, create one workflow per board and start one process per workflow.
Give each process its own workflow path and HTTP port. The setup commands write a stable
`server.port` for generated workflows and choose the next port automatically when other workflow
files in the same folder already use earlier ports. If another local Symphony process is already
using the same resolved workflow file, startup takes a workflow-local operating-system lock and fails
before polling Trello. If that lock cannot be created, startup fails instead of using a weaker
process-local fallback.

[`WORKFLOW.example.md`](WORKFLOW.example.md) contains a complete starter. YAML front matter
configures runtime behavior. The Markdown body becomes the prompt template for the card.

Minimum example:

```markdown
---
tracker:
  kind: trello
  api_key: $TRELLO_API_KEY
  api_token: $TRELLO_API_TOKEN
  board_id: your-board-id-or-shortlink
  active_states: [Todo, In Progress]
  in_progress_state: In Progress
  terminal_states: [Done, Archived, ArchivedList, ArchivedBoard, Deleted]
workspace:
  root: ./workspaces
server:
  port: 18080
polling:
  interval_ms: 5000
codex:
  command: codex app-server
  model: gpt-5.5
  reasoning_effort: medium
---
\# Task

Work on Trello card {{ card.identifier }}: {{ card.title }}.
```

Unknown template variables fail the affected attempt, then the orchestrator applies normal retry
behavior. This is intentional; typo-tolerant prompts hide broken automation.

## Advanced Configuration

### Dispatch Controls

Use dispatch controls when a board has more than one kind of card, when your machine cannot run many
Codex sessions at once, or when some cards need an explicit readiness signal.

`tracker.active_states` names the Trello lists Symphony may dispatch from. Generated GitHub workflows
use `Ready for Codex`, `In Progress`, and `Merging`. Generated non-GitHub workflows use
`Ready for Codex` and `In Progress`. Keep draft ideas, human review, blocked work, and completed
work out of active lists.

`tracker.active_list_ids` is the ID-based version of `tracker.active_states`. Use it when list names
are duplicated or likely to be renamed. When it is set, open Trello cards are matched by list ID
instead of list name.

`tracker.in_progress_state` is the visible pickup list. When it is configured, Symphony moves a card
there after it decides to run the card and before Codex starts. If the card is waiting for retry or a
concurrency slot, Symphony moves it back to the queue list when it can so `In Progress` represents
work that is actually running.

`tracker.blocker_enforced_states` names active Trello lists where dependency blockers prevent
dispatch. The default covers `Todo` and `Ready for Codex`. Leave review, blocked, and terminal lists
out of this setting because Symphony does not dispatch from those lists.

`tracker.blocked_state` is the Trello list the generated prompt tells Codex to use when it cannot
safely finish. Symphony does not dispatch from this list. Use a separate blocked list so unresolved
work is visible and does not keep getting picked up.

`tracker.terminal_states` names Trello lists or special states that mean the card is done for this
workflow. Generated workflows use `Done`, `Archived`, `ArchivedList`, `ArchivedBoard`, and
`Deleted`.

`tracker.terminal_list_ids` is the ID-based version of `tracker.terminal_states` for open cards in
Trello lists. Use it when terminal list names are duplicated or likely to be renamed. Special states
such as `Archived`, `ArchivedList`, `ArchivedBoard`, and `Deleted` still come from
`tracker.terminal_states`.

`tracker.required_labels` is an optional dispatch gate. A card must have every configured Trello
label before Symphony dispatches it. The default is an empty list, so labels are not required.
Configured labels and Trello card labels are matched after trimming surrounding whitespace and
ignoring case. Multiple configured labels are all required. A blank configured label matches no card,
so it blocks every card until the workflow is fixed.

Example:

```yaml
tracker:
  required_labels:
    - Ready for automation
    - backend
```

If a running or retrying card no longer has every required label, Symphony releases or requeues it
instead of continuing to dispatch it.

`tracker.priority_labels` lets selected labels run before other cards in the same active list. Lower
numbers run first. The default recognizes `p1` through `p4` and
`priority: critical`, `priority: high`, `priority: medium`, and `priority: low`.

Example:

```yaml
tracker:
  priority_labels:
    p1: 1
    p2: 2
    customer escalation: 1
```

`agent.max_concurrent_agents` controls how many cards from one workflow are processed concurrently.
Generated workflows use `1`. Guided setup asks for this value and keeps the current value when you
press Enter. Raise it only when the machine can run that many Codex sessions and their build/test
commands in parallel. If cards depend on other cards, use prerequisite checklist items before moving
the dependent cards into an active Trello list.

`agent.max_turns` limits how many Codex turns one worker session may take before Symphony stops that
session and schedules normal continuation handling. Increase it only when long cards need more
Codex turns and the extra runtime is acceptable.

`agent.max_concurrent_agents_by_state` can put a narrower per-list cap on an active state. Use it
when one active list, such as `Merging`, should run with lower concurrency than normal coding work.

`agent.max_retry_backoff_ms` caps the exponential retry delay after worker failures, timeouts, or
stalls. Generated workflows keep the default five-minute cap. Lower it only when quick retries are
more important than reducing load; raise it when repeated failures are noisy or expensive.

### Allowed Host Paths And Sandbox

Each Trello card runs in its own workspace. By default, Codex can write there and cannot freely edit
unrelated host files. Keep that default until a card needs another local file or folder.

During guided setup, you can add allowed host paths. In `WORKFLOW.md`, those become
`codex.additional_writable_roots`. Relative paths resolve relative to the workflow file. The runtime
adds those paths to a Codex `workspaceWrite` sandbox policy unless the workflow already uses
`dangerFullAccess`. Generated workflows use `workspaceWrite` so Codex can write inside each card's
workspace, and they set `networkAccess: true` so selected repository URLs can be cloned while
keeping the filesystem sandbox enabled.

`--add-path <checkout>` gives Codex filesystem access to that checkout as an additional writable
root. It may be enough for source-file edits, but it does not by itself guarantee that direct work in
a Git checkout can create commits. Direct commits also need writable Git metadata for that checkout.
If you need direct checkout commits, use a deployment policy that grants both the checkout files and
Git metadata, or let Codex clone into the per-card workspace and commit there.

Example:

```yaml
codex:
  additional_writable_roots:
    - ../shared-library
    - /opt/symphony-fixtures
```

Use `danger-full-access` only for a trusted board and machine. It disables Codex's command and
filesystem sandbox for that workflow, so Trello cards can ask Codex to read or edit much more than
the card workspace.

### Workspace Hooks

Workspace hooks let operators prepare or clean up the per-card workspace with shell snippets in
`WORKFLOW.md`.

- `hooks.after_create`: runs once after Symphony creates a new card workspace. Use it to clone a
  repository, install project-local files, or prepare a checkout.
- `hooks.before_run`: runs before each Codex attempt. Use it for lightweight sync or validation that
  must happen before Codex starts.
- `hooks.after_run`: runs after each Codex attempt. Use it for best-effort cleanup or log capture.
- `hooks.before_remove`: runs before Symphony removes a workspace for terminal cleanup.
- `hooks.timeout_ms`: limits each hook run.

`after_create` and `before_run` failures abort the current attempt. `after_run` and `before_remove`
failures are logged and cleanup continues where possible.

Example:

```yaml
hooks:
  after_create: |
    git clone https://github.com/example/project.git .
  before_run: |
    git status --short
  timeout_ms: 60000
```

### Polling Interval

Generated workflows poll Trello every 5 seconds:

```yaml
polling:
  interval_ms: 5000
```

Increase `polling.interval_ms` in [`WORKFLOW.md`](#workflow-contract) if Trello rate-limit warnings
repeat in the logs. Deployments above roughly 5-10 boards sharing the same Trello token should
consider a higher value, such as `10000` or `30000`.

To check for rate limits, search the service logs for `Trello rate limit reached`. Occasional
warnings can happen during bursts; repeated warnings mean the interval is too aggressive for the
current board count and token.

### Codex Command

Generated workflows include a Codex section like this:

```yaml
codex:
  command: codex app-server
  model: gpt-5.5
  reasoning_effort: medium
```

Symphony runs `codex.command` with `bash -lc` from the card workspace. Any command is acceptable if
it starts a Codex app-server on stdio for the same OS user that runs Symphony. Examples include an
absolute path such as `/opt/codex/bin/codex app-server` or a small wrapper script that sets up the
environment before starting `codex app-server`.

Generated workflows also make model selection explicit. During guided setup, Symphony asks the
installed Codex CLI which model and reasoning effort it recommends, then lets you press Enter to use
those defaults or type different values. Non-interactive setup writes the recommended values unless
you pass `--codex-model` or `--codex-reasoning-effort`. If the model list is available but does not
name a default, setup uses the first returned model. If the model list is empty or lacks usable model
details, setup writes the fallback values shown above. If the installed Codex app-server cannot
answer the model-list request at all, setup omits the first-class model fields so the generated
workflow remains compatible with older app-server versions.

`codex.model` maps to the app-server `model` request field. `codex.reasoning_effort` maps to the
app-server turn `effort` field. Edit those workflow values to choose a different model or reasoning
level. Existing workflow values remain the source of truth for that Trello board; setup preserves
them when regenerating a workflow unless you pass `--codex-model`, pass
`--codex-reasoning-effort`, or edit the workflow value. If either field is omitted, the installed
Codex CLI/app-server default or `codex.command` configuration decides the value.

`codex.turn_timeout_ms` limits a full Codex turn. `codex.read_timeout_ms` limits app-server startup
and request/response reads. `codex.stall_timeout_ms` controls how long Symphony waits without Codex
events before killing the worker and scheduling a retry. Set `stall_timeout_ms` to `0` only when you
intentionally want to disable stall detection for that workflow.

### Workspace-Local Skills

The generated workflow points Codex to small procedure files in `.codex/skills/symphony-trello-*`.
When a workflow references those paths, Symphony installs the files into each per-card workspace
after workspace sync hooks and before Codex starts, so they are available even when the target
repository does not provide its own skills. They keep the workflow prompt readable while still
spelling out repeated work such as committing, pushing a PR, sweeping review comments, updating the
Trello workpad, and handling Trello handoff.

The most common skills are:

- `.codex/skills/symphony-trello-trello-workpad/SKILL.md`: keep the single `## Codex Workpad`
  comment current.
- `.codex/skills/symphony-trello-trello-handoff/SKILL.md`: move the current card through pickup,
  review, blocked, merge, and done handoff.
- `.codex/skills/symphony-trello-review-sweep/SKILL.md`: check PR comments, inline review feedback,
  GitHub review threads, and checks before handoff.
- `.codex/skills/symphony-trello-commit/SKILL.md`: commit focused changes, follow the target
  repository's commit message convention, and, for PR-bound work, reuse a workflow-verified
  checkout-local commit author or configure one from the authenticated GitHub account before
  committing.
- `.codex/skills/symphony-trello-push-pr/SKILL.md`: push the branch, check PR-bound commit authors,
  and create or update the pull request. Repository-changing work creates a ready-for-review PR by
  default, and PR bodies preserve the target repository's pull request template when one exists.
  Cards that need a draft PR must ask for one explicitly.
- `.codex/skills/symphony-trello-land/SKILL.md`: land an approved PR only from `Merging`, resolve
  addressed review threads when possible, then move successful work to the configured completion list
  or blocked landing attempts to `Blocked`.
- `.codex/skills/symphony-trello-debug/SKILL.md`: diagnose stuck, retrying, blocked, or failed runs.

These files are instructions for Codex. Symphony copies them into the workspace but does not execute
them directly. When the workspace is a Git checkout root, Symphony adds the namespaced skill
directories to that checkout's local Git exclude file so they do not appear as task changes.

For GitHub pull requests, Codex first checks the task checkout's local `git config user.name`,
`git config user.email`, and `git config symphony-trello.github-author-verified`. If the author is
complete and marked verified, it reuses that author and avoids another GitHub API lookup. Otherwise,
Codex uses the GitHub CLI account that publishes the PR as the commit author and stores the verified
author in the checkout-local Git config. If that account has no public email, Codex fetches the
account's actual GitHub noreply email through `gh api user/emails`, which needs the `user:email`
GitHub CLI scope. If that email is not accessible, PR-bound work must stop before committing. If
commits show the wrong author, check `gh auth status`, `gh api user`, `gh api user/emails`, and the
repository's local `git config user.name`, `git config user.email`, and
`git config symphony-trello.github-author-verified` inside the task checkout.

### Trello Write Controls

The recommended workflow gives Codex three scoped Trello handoff tools:

- `trello_add_comment`: add a comment to the current card.
- `trello_upsert_workpad`: create or update the single `## Codex Workpad` comment on the current
  card.
- `trello_move_current_card`: move the current card to a configured board-local list such as
  `In Progress`, `Human Review`, or `Blocked`.

Hand-authored workflows can enable two more current-card tools:

- `trello_upsert_checklist_item`: create or update one checklist item when
  `trello_tools.allow_checklists=true`.
- `trello_add_url_attachment`: attach one HTTP or HTTPS URL without credentials, query string, or
  fragment when `trello_tools.allow_url_attachments=true`.

The move tool uses argument names such as `list_name` and `list_id` because Trello calls these board
lanes lists.

Symphony advertises those tools when `trello_tools.enabled=true` and
`trello_tools.allow_writes=true`; each write type is controlled by its own allow flag. The generated
workflow keeps checklist and URL attachment tools disabled unless you edit those flags. For a
read-only scheduler deployment, set `trello_tools.allow_writes: false` and move cards manually.

If the API token is read-only or Trello rejects writes, Codex still runs, but handoff tool calls fail
and the failures are visible in the Codex session events.

To move cards to workflow lists with different names, set `trello_tools.allowed_move_list_names` to
those allowed list names and update the pickup and final handoff instructions in
[`WORKFLOW.md`](#workflow-contract) to match them. Do not tell Codex to leave blocked cards in an
active list such as `Ready for Codex` or `In Progress`; Symphony may treat the card as still
eligible and run it again.

If a destination list name matches more than one open Trello list, Symphony refuses the name-based
move instead of guessing. Rename the duplicate lists or configure `trello_tools.allowed_move_list_ids`
and tell Codex to move with `list_id`.

The standardized generic `trello_rest` dynamic tool extension is documented in [SPEC.md](SPEC.md) but
is not yet advertised to Codex by this Java implementation. The Java implementation currently uses
the narrower handoff tools above.

## Operations

Useful endpoints:

- `GET /` returns a small human-readable status page.
- `GET /api/v1/state` returns running sessions, retry queue, token totals, and rate-limit data when
  reported by Codex.
- `GET /api/v1/{card_identifier}` returns card-specific runtime details.
- `POST /api/v1/refresh` queues an immediate poll/reconciliation cycle.

For field meanings, token totals, logging conventions, and troubleshooting expectations, see
[docs/operations.md](docs/operations.md).

For issue reports, run `symphony-trello diagnostics`,
`symphony-trello diagnostics --board "My Board Name"`, or
`symphony-trello diagnostics --workflow WORKFLOW.md`. Use `--board` or `--workflow` to keep the
report smaller. Without a selector, the report covers every connected Trello board and also lists
workflow files in the config directory that are not connected to any board in a separate
Unconnected Workflow Files section, without probing their ports. `--board` and `--workflow` are
mutually exclusive; if multiple connected Trello boards have the same name, use the Trello board id
or short link. The command prints sanitized local
setup, workflow, health, and recent log context without Trello, GitHub, or Codex network calls by
default. Add `--deep` when the default report does not include enough context; it runs deeper
public-safe checks such as local Codex and GitHub auth-status commands. Review the output before
sharing it.

Diagnostics replaces private values with stable local tokens such as the value in a `board_hash` or
`key_hash` row, and `<path:...>` path tokens, so maintainers can compare related rows without seeing
your Trello identifiers or local paths. These tokens are created with a random key stored on your
machine, so they are stable for your installation but are not meant to be guessed by other people.
If a maintainer asks which local board, workflow, or log one of those tokens refers to, run
`symphony-trello diagnostics --show-private-context` on your machine. Add `--lookup <token>` to
resolve only one token instead of printing the full private-context report. Private context maps
tokens back to your local Trello board ids, board URLs, workflow paths, env file path, workspace
root, state directory, worker log files, managed PID/state files, and file-backed secret paths. It
does not print the secret values stored in those files. The lifecycle commands may also print one of
these tokens when hiding a local path in an error. Use it to inspect your own machine or answer a
maintainer's question in your own words. Do not paste the `--show-private-context` output
into public issues because it intentionally contains private Trello identifiers, URLs, and local
paths. It does not include credential values or worker log contents.

[`WORKFLOW.md`](#workflow-contract) is watched for changes and also checked defensively on each
scheduler tick. Invalid reloads are logged and the last known good configuration remains active.

Important environment variables:

- `SYMPHONY_WORKFLOW_PATH`: workflow file path, default `WORKFLOW.md`.
- `SYMPHONY_HTTP_PORT`: Quarkus HTTP port, default `18080`.
- `SYMPHONY_AUTOSTART`: set `false` in tests or when only using injected services.
- `SYMPHONY_TRELLO_DOTENV`: ignored dotenv file used by local managed runs when credentials are not
  in real environment variables. The installer wrapper sets this when you start with
  `symphony-trello start --env PATH --workflow WORKFLOW.md`.
- `TRELLO_API_KEY` and `TRELLO_API_TOKEN`: default Trello credential variable names.
- `SYMPHONY_CODEX_ADDITIONAL_WRITABLE_ROOTS`: path-separated files or folders that are added to
  Codex's `workspaceWrite` sandbox policy for deployments that intentionally expose extra host
  paths.
- `SYMPHONY_CODEX_DANGER_FULL_ACCESS`: set `true` only with an intentionally broad deployment
  sandbox.

For local runs, the same names can be placed in ignored `.env`; real environment variables take
precedence.

For server deployments, workflow values can also read root-managed secret files with `file:/path`.
The deployment guides use `file:$CREDENTIALS_DIRECTORY/trello-api-key` and
`file:$CREDENTIALS_DIRECTORY/trello-api-token` with systemd credentials so Trello secrets are not
placed in the service environment.

## Safety Posture

This implementation targets trusted automation environments by default. Workspace boundaries are
enforced, hooks run inside the per-card workspace, and Trello credentials are injected into HTTP
requests rather than prompts. Hooks and Codex still execute trusted local code, so production
deployments should use a dedicated OS user, a dedicated workspace volume, narrowly scoped Trello
credentials, and Codex approval/sandbox settings appropriate to the board's trust level.

Approval and sandbox values are passed through from [`WORKFLOW.md`](#workflow-contract) to the
installed Codex app-server schema. When additional writable roots are configured, this implementation
adds them to a `workspaceWrite` turn sandbox policy unless the workflow already uses
`dangerFullAccess`. Generated workflows use `workspaceWrite` so Codex can write inside each card's
workspace, and they enable network access so Codex can clone selected repository URLs or publish
pull requests without disabling the filesystem sandbox. User-input and unsupported dynamic tool requests
are answered without waiting indefinitely.

## Production Deployment

For a server deployment with one or more workflow files, use the manual systemd guide in
[docs/deployment.md](docs/deployment.md). The primary public setup path remains the installer and
managed local worker; manual systemd deployment is the advanced server path.

## Building 1.0.0 by the Numbers

A lot went into getting Symphony for Trello 1.0.0 ready.

Across the audited AI-assisted development sessions, the build footprint was approximately:

- **15.13 billion** total tokens
- **$13,240.24** in API/API-equivalent token cost
- **2,041** human prompts and follow-ups, totaling **72,542** words,
  roughly the length of Mary Shelley's _Frankenstein_, or about **8 hours**
  as an audiobook
- **96,825+** agent/model turns
- **107,796+** tool/function calls
- **82,242+** shell executions

These are historical development metrics, not runtime requirements. Running Symphony for Trello does
not require this token volume or cost. They are included to make the scale of iteration, validation,
and review behind the 1.0.0 release visible.

## Contributing and Security

See [CONTRIBUTING.md](CONTRIBUTING.md) for contributor setup and development checks,
[AI_CONTRIBUTION_POLICY.md](AI_CONTRIBUTION_POLICY.md) for AI-assisted contribution expectations,
and [SECURITY.md](SECURITY.md) for private vulnerability reporting guidance.

Release notes are tracked in [CHANGELOG.md](CHANGELOG.md).
