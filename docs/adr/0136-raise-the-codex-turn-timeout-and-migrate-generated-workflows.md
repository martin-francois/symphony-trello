---
status: accepted
date: 2026-10-30
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #1124](https://github.com/martin-francois/symphony-trello/issues/1124)"
  - "[GitHub PR #853](https://github.com/martin-francois/symphony-trello/pull/853)"
  - "[GitHub issue #1025](https://github.com/martin-francois/symphony-trello/issues/1025)"
  - "[ADR 0121](0121-pre-handoff-codex-review-loop.md)"
  - "[ADR 0095](0095-generated-workflow-body-migration.md)"
informed: [Future maintainers, Contributors, Operators]
---

# Raise The Codex Turn Timeout And Migrate Generated Workflows

## Context and Problem Statement

[ADR 0121](0121-pre-handoff-codex-review-loop.md) makes Codex run one review round before it hands
off repository changes. One separate `codex review` run is limited by
`repository.codex_review_timeout_ms`, default `3600000` (one hour). The review runs inside a Codex
turn, and `codex.turn_timeout_ms` also defaulted to `3600000`.

With both limits at one hour, a review that reaches its own limit has already used the whole turn,
because the turn also spent time on the implementation. The turn times out first, and the fallback
in the prompt (review the same diff in the session) never gets a chance to run.

[GitHub issue #1124](https://github.com/martin-francois/symphony-trello/issues/1124) listed four
options. The maintainer chose to raise the turn timeout and to reject a review timeout that does not
fit inside the turn. Every 1.x release wrote `codex.turn_timeout_ms: 3600000` into generated
workflows and no review-loop settings, so the check alone would make those workflows fail after the
update. How do existing workflows reach the new values?

## Decision Drivers

* A full review run must fit inside one Codex turn, with time left for the in-session fallback.
* The review loop will grow: a later change plans three rounds by default, up to five for risky
  findings, with a total review time budget. That budget also has to fit inside one turn.
* A workflow that setup generated must work after the update without any manual edit. Operators
  should not have to set the review timeout or remember to raise the turn timeout.
* A hand-written value that does not fit must fail with a message that says how to fix it, instead
  of a turn that times out in the middle of a review.
* 2.0.0 already ships breaking changes, so it is the release to move these defaults.
* The migration must not touch values that an operator chose.

## Considered Options

* Raise the turn timeout to three hours, check the review timeout against it, and migrate the old
  generated value in `migrate-workflows`
* Keep both defaults at one hour
* Lower the review timeout default
* Raise the turn timeout and check it, without a migration
* Rewrite the front matter through the YAML mapper that setup uses
* Rewrite the front matter through SnakeYAML's comment-preserving node tree

## Decision Outcome

Chosen option: "Raise the turn timeout to three hours, check the review timeout against it, and
migrate the old generated value in `migrate-workflows`", because it is the only option that keeps
updated boards working without a manual step and still stops a review limit that cannot fit.

### Default

`ConfigDefaults.DEFAULT_CODEX_TURN_TIMEOUT` is three hours. Config parsing uses it when a workflow
omits `codex.turn_timeout_ms`, and setup writes it into every generated workflow, so the value is
defined once. The maintainer first chose two hours and then raised it to three hours, so the review
budget of the planned review-loop redesign also fits inside the default turn.

This is an intentional upstream divergence. Upstream Symphony defaults `codex.turn_timeout_ms` to
one hour. This project runs the pre-handoff review loop inside the turn, so the turn needs more
time. Realigning with upstream would need a review loop that does not run inside the coding-agent
turn, for example a review that Symphony starts in a separate thread.

### Check

While `repository.codex_review_max_rounds` is above `0`, `ConfigResolver.validateForDispatch`
rejects a `repository.codex_review_timeout_ms` that is not below `codex.turn_timeout_ms`. The error
code is `codex_review_timeout_not_below_turn_timeout`. The message names both keys and both values
and lists the three fixes: raise the turn timeout, lower the review timeout, or set the round limit
to `0`. Setup shows the same message when it checks a workflow before it starts a worker. With the
loop off, the two values are unrelated, so the check does not run.

The check compares one review run with the turn. The planned review-loop redesign replaces it with
a check that the total review budget is below the turn timeout.

### Migration

`migrate-workflows` gets a second, temporary step that runs before the body migration from
[ADR 0095](0095-generated-workflow-body-migration.md):

* It changes `codex.turn_timeout_ms` from the integer `3600000` to `10800000`. That number is the
  value every 1.x setup wrote. Any other value, including `"3600000"` as a string, stays.
* It adds `repository.codex_review_max_rounds: 1` and `repository.codex_review_timeout_ms: 3600000`
  when the `repository` section exists and lacks them. Freshly generated workflows have both, so
  the update leaves an old generated workflow in the same state.
* It edits only those lines. It replaces the number on its own line and inserts missing keys after
  the last setting line of the `repository` section. Comments, quoting, spacing, the order of other
  lines, and the body stay byte for byte. The edited front matter must parse to the original
  settings plus exactly the planned changes. When that is not possible, for example in flow-style
  YAML, nothing is written and the output names the values to change by hand.
* It runs without confirmation, and also with `--non-interactive`, which installers pass when there
  is no terminal. Otherwise an unattended update would leave boards that fail to dispatch.
* It writes the same timestamped backup as a body migration and checks the written file the same
  way, except that the body must stay unchanged. When the write fails, the same run leaves the body
  of that workflow alone, so the failure is reported once and the next run starts from a known file.

The step checks the same workflows as the body migration: connected workflows from the manifest, or
the one selected with `--board` or `--workflow`. No 1.x release records generated workflows in
`generated-workflows.json`, so a record cannot tell generated and hand-written workflows apart. The
exact old default is the signal instead. A connected hand-written workflow that set exactly that
value would also fail the check, so raising it is the fix it needs anyway.

[ADR 0095](0095-generated-workflow-body-migration.md) keeps metadata unchanged and asks before it
replaces a body. That rule protects the body text that users edit. This step only changes a number
that Symphony itself wrote and adds two settings that equal the runtime defaults, so it needs no
question. Unattended body migrations still only report.

### Removal

The step is temporary. It is reviewed at the next breaking release or by 2027-10-30, whichever comes
first, in [GitHub issue #1150](https://github.com/martin-francois/symphony-trello/issues/1150). When
it is removed, the release notes must name the latest version that still performs it, so operators
who skipped it can update to that version first. The check stays after the removal, as the guard for
hand-written values.

### Consequences

* Good, because a full review run and the in-session fallback fit inside the default turn.
* Good, because boards set up with 1.x get the new values on the installer's update, also when the
  update runs without a terminal.
* Good, because a review limit that cannot fit fails before dispatch with a message that names the
  fix, instead of a turn timeout in the middle of a review.
* Good, because every operator-chosen value other than exactly `3600000` stays unchanged.
* Bad, because a workflow that `migrate-workflows` does not check, or one whose other turn timeout
  is not above the review timeout, fails dispatch after the update until the operator changes a
  value. That is why the change is marked breaking.
* Bad, because a stuck turn now runs up to three hours before Symphony stops it, instead of one.
* Bad, because the default now differs from upstream Symphony.
* Bad, because `migrate-workflows` now changes metadata without a question, which the body migration
  never does. The step is narrow, keeps a backup, and is temporary.

### Confirmation

* `ConfigResolverTest` checks the three-hour default, an explicit turn timeout, the check for equal
  and longer review timeouts with the exact message, and that the check accepts a review timeout
  below the turn or a loop that is off.
* `WorkflowConfigEditorTest` checks that setup shows the message before a worker starts.
* `CodexTimeoutSettingsMigrationTest` checks the update of the 1.x generated front matter, kept
  comments, spacing, and CRLF line breaks, unchanged explicit values, a missing `repository`
  section, a value in flow-style YAML, and unreadable front matter.
* `WorkflowBodyMigrationTest` checks the update in interactive and unattended runs, the dry run, a
  workflow that already has the current values, and a body migration in the same run.
* `TrelloBoardSetupTest` and `WorkflowConfigPromptTest` check that generated workflows and
  `WORKFLOW.example.md` use the default turn timeout, which is above the review timeout.
* `SPEC.md` Sections 5.3.6, 6.3, 19.4.2, and 19.7 hold the contract.

## Pros and Cons of the Options

### Raise the turn timeout to three hours, check the review timeout against it, and migrate the old generated value in `migrate-workflows`

The turn timeout default goes to three hours, dispatch validation rejects a review timeout that is
not below the turn timeout while the loop is on, and `migrate-workflows` changes the exact old
generated value and adds the missing review settings by editing those lines.

* Good, because updated boards keep working without a manual edit.
* Good, because the check catches hand-written values that cannot work.
* Neutral, because it reuses the installer's existing `migrate-workflows` call, backup, and checks.
* Bad, because it adds temporary migration code that needs a cleanup issue and a later removal.

### Keep both defaults at one hour

Both limits stay at one hour, and a hung nested review ends the turn with a turn timeout.

* Good, because nothing changes for existing workflows.
* Bad, because the fallback in the prompt can never run at the defaults.

### Lower the review timeout default

The review timeout default drops, for example to 30 minutes, so the fallback can run inside the
one-hour turn.

* Good, because existing workflows keep their turn timeout.
* Bad, because the maintainer decided in
  [GitHub issue #1025](https://github.com/martin-francois/symphony-trello/issues/1025) that a
  review of a large change needs up to an hour.
* Bad, because the planned multi-round review budget would not fit in one hour either.

### Raise the turn timeout and check it, without a migration

The default goes up and the check rejects values that do not fit, but existing workflows keep the
value that setup wrote.

* Good, because `migrate-workflows` stays a body-only command.
* Bad, because every board set up with 1.x fails dispatch after the update until the operator edits
  its workflow, which is the manual step the maintainer ruled out.

### Rewrite the front matter through the YAML mapper that setup uses

`WorkflowConfigEditor` reads the front matter into a map with Jackson and writes the whole map back.

* Good, because it reuses existing code and handles any YAML layout.
* Bad, because writing the map back drops comments and changes quoting, key order, and spacing in
  every line, so a small update would rewrite the operator's whole front matter.

### Rewrite the front matter through SnakeYAML's comment-preserving node tree

SnakeYAML 2.6 is already on the classpath through `jackson-dataformat-yaml`. With
`LoaderOptions.setProcessComments(true)` and `DumperOptions.setProcessComments(true)`, `compose`
reads the front matter into a node tree that keeps comments, and `serialize` writes it back. A
probe with that version kept comments, blank lines, key order, quoting, and flow sequences.

* Good, because it edits the parsed tree instead of matching lines, so it handles more layouts,
  including flow-style mappings.
* Bad, because the probe still rewrote other lines: it collapsed `command:    codex app-server` to
  one space and `trello   # the tracker` to one space before the comment, and it turned CRLF line
  breaks into LF. The update would then change lines the operator never asked it to touch.
* Bad, because the dumper applies its own layout rules: in the same probe it re-indented a
  four-space block to two spaces and split a long plain value over two lines at its default width of
  80 columns.

## More Information

* `SPEC.md` Section 19.4.2 defines the update and its removal condition; Section 19.7 defines the
  check.
* The update lives in `src/main/java/ch/fmartin/symphony/trello/setup/CodexTimeoutSettingsMigration.java`.
* README sections "Codex Review Before Handoff" and "Migrate Generated Workflows" explain the new
  default and the update.
