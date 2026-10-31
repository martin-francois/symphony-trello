---
status: accepted
date: 2026-10-31
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #1085](https://github.com/martin-francois/symphony-trello/issues/1085)"
  - "[GitHub PR #798](https://github.com/martin-francois/symphony-trello/pull/798)"
  - "[git read-tree documentation](https://git-scm.com/docs/git-read-tree)"
  - "[Git environment variables](https://git-scm.com/docs/git#Documentation/git.txt-codeGITALTERNATEOBJECTDIRECTORIEScode)"
informed: [Future maintainers, Contributors]
---

# Detect source checkout collisions with a Git dry-run merge

## Context and Problem Statement

A source update in `install.sh` and `install.ps1` runs a preflight, stops managed workers, then
fetches the checked commit into the app checkout and runs `git checkout`. Git refuses that checkout
when an untracked local path would be overwritten or removed, for example an untracked
`docs/collision.txt` that the new commit tracks. The refusal came after the workers had stopped, so
the installer exited with workers down and no restart
([GitHub issue #1085](https://github.com/martin-francois/symphony-trello/issues/1085)).

The build-input guard from [GitHub PR #798](https://github.com/martin-francois/symphony-trello/pull/798)
only covers fixed input paths such as `src/` and `.mvn/`, so it cannot catch a collision in any
other path.

How should preflight find out, before workers stop, that the checkout would fail?

## Decision Drivers

* The check refuses exactly the updates that `git checkout` refuses: no new refusals, and no misses
  on Git 2.34 or later. Earlier Git versions leave paths after an ignored collision unchecked.
* Nothing in the installed checkout changes before workers stop, not even fetched objects.
* Bash and PowerShell use the same mechanism, so they cannot drift apart.
* Local files are never deleted or overwritten to make the update pass.

## Considered Options

* A dry-run two-way merge with `git read-tree -n -m -u` that reads the target commit from the staged
  clone.
* Fetch the target commit into the app checkout during preflight, then run the dry-run merge.
* List the paths that the target commit adds and test each one in the working tree from the shell.
* Run the real `git checkout` before stopping workers.

## Decision Outcome

Chosen option: a dry-run two-way merge that reads the target commit from the staged clone.

After preflight resolves the checked commit, both installers run, in the existing app checkout:

```bash
alternates="$(git_object_directory_entry "$SOURCE_CHECKOUT/.git/objects")"
if [[ -n "${GIT_ALTERNATE_OBJECT_DIRECTORIES:-}" ]]; then
  alternates+=":$GIT_ALTERNATE_OBJECT_DIRECTORIES"
fi
GIT_ALTERNATE_OBJECT_DIRECTORIES="$alternates" \
  git -C "$APP_DIR" read-tree -n -m -u --exclude-per-directory=.gitignore HEAD "$SOURCE_COMMIT"
```

`-m -u` runs the same two-way merge with working tree checks that `git checkout` runs, and `-n`
stops before the index or any file changes. A checkout without commits has no `HEAD` tree, so the
installers pass only the target commit. That one-way merge makes the same untracked-file checks as
`git checkout` from an unborn branch. `--exclude-per-directory=.gitignore` makes ignored
files replaceable, as they are for `git checkout`. Without it, read-tree treats ignored files as
untracked and would refuse updates that work today. The alternate object directory makes the
target commit readable without fetching it. Each path uses Git's C-style quoted entry syntax, so
colons on POSIX, semicolons on Windows, and embedded quotes or backslashes remain part of one
directory instead of becoming list syntax.
The inherited alternate object list is appended without re-encoding its entries, so the installed
revision remains readable when its objects live outside the checkout. PowerShell uses the host's
path-list separator and restores the inherited value after the dry run.

The installers run read-tree with `LC_ALL=C` and match its complete diagnostic. Embedded newlines
remain part of the filename, including blank lines in PowerShell native output. A diagnostic that says an
untracked file would be overwritten or removed, or that updating a directory would lose untracked
files in it, is a collision: the installer prints the move-or-back-up instruction and Git's lines
prefixed with `git:`, then exits. Any other failure, such as a stale `index.lock`, gets a different
instruction that asks the operator to fix the problem Git reports. Calling it a collision would
send the operator looking for a file that does not exist.

Git before 2.34 reads only per-directory `.gitignore` files in read-tree. It ignores
`.git/info/exclude` and `core.excludesFile`, which its `git checkout` honors. On such a Git, a file
ignored only there makes read-tree refuse an update that `git checkout` completes. Before it
refuses, the installer therefore asks Git whether the named path is ignored, with `git check-ignore`
for a file and `git ls-files --others --exclude-standard` for a directory. Both read every ignore
source on every Git version. If the path is ignored, read-tree's answer proves nothing, and Git
stopped at that path, so later paths are unchecked. The installer prints a note and continues,
which leaves the decision to the checkout after workers stop, as before this check existed. On Git
2.34 and later, read-tree reads every ignore source, so a named path is never ignored and this
branch never runs. A minimum Git version was not chosen: Debian 11 ships Git 2.30, and refusing
such hosts would block updates that work today.

The check lives in both installer scripts, not in the Java command. It runs before the requested
version is built, while the only Java command present is the previous installation's, which does
not have the check. [Documentation & README](../agents/documentation-and-readme.md) keeps installed
command semantics in Java when Java is available. For this check it is not, so it stays in Bash and
PowerShell: one Git command and the parsing of its diagnostic, kept the same in both
scripts.

### Consequences

* Good, because a collision now stops the update before workers stop, the build runs, or the
  installed revision changes.
* Good, because Git itself decides what collides, including file and directory collisions, so the
  check follows Git's rules as they change.
* Good, because the app checkout gets no new objects, refs or `FETCH_HEAD` from preflight.
* Bad, because read-tree stops at the first conflicting path, while `git checkout` lists them all.
  An operator with several colliding files learns about them one rerun at a time. The message says
  so.
* Bad, because the installers parse Git's English error text. A future Git that rewords it makes
  a collision look like another Git failure, which still stops before workers stop but with the
  wrong instruction. The installer tests assert the collision instruction, so they fail first.
* Bad, because on Git before 2.34 a file ignored only through `.git/info/exclude` or
  `core.excludesFile` at a tracked path ends the check early, and a collision at a later path is
  again found only after workers stop.
* Neutral, because an ignored file that the target commit tracks is still overwritten, as it was
  before. Build inputs keep their stricter guard, which also refuses ignored files.

### Confirmation

`InstallerScriptTest.installersRefuseCheckoutCollisionsBeforeStoppingWorkers` runs both installers
against local Git fixtures: an untracked file the target tracks, an untracked directory where the
target tracks a file, and an untracked file where the target tracks a directory. It asserts the
refusal, the message, no worker stop, no build or migration, an unchanged `HEAD` and unchanged
local bytes. The other installer tests cover the rest of this decision:

* `installersUpdateBesideUntrackedFilesThatDoNotCollide` and
  `posixUpdateBesideUntrackedFilesStopsAndRestartsManagedWorkers`: an untracked file next to a
  newly tracked path does not block the update, and the POSIX update still stops and restarts
  workers. Temporary roots cover the platform path-list separator and POSIX quote, backslash,
  tab, and newline characters. The PowerShell fixture sets its temporary root inside the child
  runtime so container environment filtering cannot bypass the test.
* `installersExplainCollisionsWithNewlinesInTheFilename`: file and directory collisions with
  embedded blank lines retain the move-or-back-up instruction and leave workers and local bytes intact.
* `installersPreserveInheritedObjectDirectories`: the installed revision exists only in an inherited
  alternate object directory, whose name contains the platform's path-list separator, and both
  installers still complete a collision-free update.
* `installersReplaceIgnoredFilesAtNewlyTrackedPaths`: files ignored through `.gitignore`,
  `.git/info/exclude` or `core.excludesFile` are replaced, as `git checkout` replaces them.
* `installersReportOtherGitFailuresBeforeStoppingWorkers`: a stale `index.lock` gets Git's message
  and the fix-the-problem instruction, not the collision text.
* `installersLetTheCheckoutDecideWhenOlderGitRefusesAnIgnoredFile`: a `git` wrapper answers
  read-tree as Git before 2.34 does, and the update continues with a note.

Run:

```bash
./mvnw test -Dtest='InstallerScriptTest#installers*Checkout*+installers*Untracked*+posixUpdateBeside*+installers*Ignored*+installersReportOtherGitFailures*'
```

Set `SYMPHONY_TRELLO_TEST_PWSH=./scripts/pwsh-docker.sh` on Linux to include the PowerShell rows.

## Pros and Cons of the Options

### Dry-run merge reading the staged clone

Run `git read-tree -n -m -u --exclude-per-directory=.gitignore HEAD <commit>` in the app checkout,
with the staged clone's object directory added through `GIT_ALTERNATE_OBJECT_DIRECTORIES`.

* Good, because it uses the merge code that `git checkout` uses.
* Good, because it writes nothing to the app checkout.
* Bad, because it reports only the first conflicting path.

### Fetch into the app checkout first

Move the existing `git fetch` from the update phase into preflight, then run the same dry-run
merge against the fetched commit.

* Good, because no environment variable is needed.
* Bad, because preflight then changes the installed checkout's object store and `FETCH_HEAD`
  before workers stop, which the issue rules out.

### Compare paths from the shell

Use `git diff --name-only` between `HEAD` and the target commit, then test each added path, and
each parent directory, in the working tree from Bash and PowerShell.

* Good, because it could list every conflicting path at once.
* Bad, because it reimplements Git's rules for ignored files, case folding, links and
  file-to-directory changes twice, once per shell, and any gap is a missed refusal or a new false
  one.

### Check out before stopping workers

Run the real `git checkout` during preflight and stop workers afterwards.

* Good, because Git lists every conflicting path.
* Bad, because a successful checkout changes the installed revision before workers stop, which the
  issue rules out, and a later failure would leave a checkout that no longer matches the built app.

## More Information

[GitHub issue #1085](https://github.com/martin-francois/symphony-trello/issues/1085) holds the
original reproduction and the completion criteria. `SPEC.md` Section 19.4.1, under Installers,
states the contract.
