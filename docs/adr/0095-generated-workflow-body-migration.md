---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - "[GitHub issue #566](https://github.com/martin-francois/symphony-trello/issues/566)"
  - "[GitHub issue #567](https://github.com/martin-francois/symphony-trello/issues/567)"
  - "[GitHub issue #545](https://github.com/martin-francois/symphony-trello/issues/545)"
  - "[SPEC.md](../../SPEC.md)"
informed: [Future maintainers, Contributors, Operators]
---

# Migrate Generated Workflow Bodies With Recorded Provenance

## Context and Problem Statement

Setup writes a `WORKFLOW.md` whose body is a long generated prompt. New Symphony versions change
that prompt to add fixes and support new features, for example the repository selection rules from
[GitHub issue #545](https://github.com/martin-francois/symphony-trello/issues/545). Existing users
keep the old body unless they recreate the workflow or compare and copy text by hand. A supported
downgrade has the opposite problem: the workflow may describe features the older version does not
have.

[GitHub issue #566](https://github.com/martin-francois/symphony-trello/issues/566) asks for a
version-aware migration of the body only, with exact matching instead of a merge, explicit
confirmation, backups, and a permanent compatibility contract in `SPEC.md` and an ADR. The issue
leaves three choices to this ADR: how the source generated body and its version are represented,
how long that data is kept, and how workflows without that data are treated.

The generated body is not a fixed text per version. It depends on generation inputs: the active,
terminal, in-progress, review, blocked, and merging list names, and whether GitHub is enabled. The
metadata does not record all of them; the review and merging lists only appear indirectly. A later
version therefore cannot reproduce an older body from the version number alone.

## Decision Drivers

* Never change user content without an exact, unique match and an explicit confirmation.
* Work for updates and for downgrades within the same major version, in both directions.
* Keep the product decision in Java so `install.sh` and `install.ps1` cannot classify differently.
* Keep the mechanism general: later template changes, such as the open pull requests that edit
  the generated prompt, must not need migration code of their own.
* Do not keep copies of old generators or fingerprints of old templates in the code base.
* Keep the prompt that Codex receives free of bookkeeping text.
* Let an older version read the stored data during a supported downgrade.
* Leave a clean extension point for the optional Codex review in
  [GitHub issue #567](https://github.com/martin-francois/symphony-trello/issues/567).

## Considered Options

* Record the exact generated body, its version, and its inputs in a store beside the manifest.
* Ship historical templates or body fingerprints with every release.
* Add a version marker or body hash to the workflow front matter.
* Wrap the generated block in marker comments inside the body.
* Add provenance fields to `connected-boards.json`.
* Write a provenance file next to every workflow.
* Merge old and new bodies with a general three-way merge.

## Decision Outcome

Chosen option: "Record the exact generated body, its version, and its inputs in a store beside the
manifest", because it is the only option that gives every version the exact source text and the
inputs to render its own target text, without old generator code and without touching the
workflow file.

Representation. Setup writes `generated-workflows.json` next to the connected-board manifest. It
holds one record per workflow path with the Symphony version, the generation inputs, and the exact
body text. Readers ignore unknown fields, so an older version can read records from a newer one.
New input fields must have defaults that render the older body.

Retention. A record holds only the latest body that Symphony generated, migrated, confirmed as
already current, or recorded through `--mark-migrated`. It is replaced on the next of these events.
There is no automatic pruning; a record for a removed workflow is ignored because discovery only
looks at connected or explicitly selected workflows.

Unversioned workflows. Workflows written before this change have no record. The command reads
their inputs from the metadata in the shape setup writes. If the body contains the body rendered
from those inputs exactly once, the workflow is already current and gets a record. Otherwise it
needs a manual migration, and the rendered body only serves as a preview. Symphony never assumes
that the installed version generated an unversioned workflow.

Command. `symphony-trello migrate-workflows` owns discovery, classification, prompts, writes, and
messages. It is a top-level command like `start` and `status`, with `--board` or `--workflow`,
`--dry-run`, `--non-interactive`, `--from-version`, and `--mark-migrated`. Both installers call it
after an update, with `--from-version` set to the previous version and `--non-interactive` when
there is no terminal or `--no-onboard` was passed. A nonzero exit stops the installer before it
restarts workers: a rejected major downgrade must not run, and a failed write needs the operator to
read the backup message first. For release-archive installs, both installers also refuse an older
major release before they replace the app.

Files next to the workflow. A confirmed migration writes a timestamped backup next to the workflow,
and an interactive manual migration writes the target body as a preview there. Both are one-off
files that the operator asked for and can delete. Unattended runs write neither. This differs from
the rejected provenance file next to every workflow, which would exist permanently.

Extension point. `WorkflowBodyReplacement` describes one proposed migration as the original file,
the kept text before and after the old body, and the target body. An advisory step such as the
Codex review in
[GitHub issue #567](https://github.com/martin-francois/symphony-trello/issues/567) can propose
different kept text before the confirmation, while the metadata and the target body stay fixed.

### Consequences

* Good, because classification is exact and deterministic for every future template change.
* Good, because neither the workflow file nor the Codex prompt carries migration markers.
* Good, because a same-major downgrade works with the older version's own generator and the
  newer version's recorded body.
* Good, because the installers only decide whether to ask; Java decides what to change.
* Bad, because existing workflows from before this change need one manual migration unless their
  body already matches the running version.
* Bad, because a workflow that is moved or renamed outside Symphony loses its record and becomes
  unversioned.
* Bad, because the store keeps a copy of each generated body, about 30 KB per workflow.
* Bad, because the major-version check before a release-archive install repeats the Java major
  comparison in shell and PowerShell. It is a single version comparison, not a classifier, and it
  is the only check that can run before the older app replaces the newer one.
* Neutral, because an installer dry run cannot preview a target version's classification before
  that version is installed; `migrate-workflows --dry-run` previews it afterwards.
* Neutral, because unattended updates only report. No automatic apply policy exists yet; adding
  one is a separate change.

### Confirmation

This decision remains implemented when:

* `TrelloBoardSetup.generatedWorkflowBody` renders the body only from `GeneratedWorkflowBodyInputs`,
  and setup paths record it through `GeneratedWorkflowStore.recordGenerated`;
* `WorkflowBodyClassifierTest` covers every classification and `WorkflowBodyMigrationTest` covers
  updates, supported downgrades, prefix and suffix preservation, LF and CRLF, confirm, decline,
  batch, dry run, non-interactive runs, backup, rollback, permissions, symbolic links, path-set
  limits, unknown store fields, and major-downgrade rejection;
* `TrelloBoardSetupMainTest` and `LocalSetupGithubConfigurationTest` prove that generated workflows
  are recorded and that a freshly generated workflow is reported as unchanged;
* the POSIX and PowerShell installer tests show the `migrate-workflows` call on update; and
* `SPEC.md` Section 19.4.1 describes the same contract.

## Pros and Cons of the Options

### Record The Exact Generated Body, Its Version, And Its Inputs In A Store Beside The Manifest

Setup stores the exact body, the version, and the inputs in `generated-workflows.json` in the
config directory. Migration compares the workflow body with that text and renders the target body
from the stored inputs.

* Good, because the source text is exact and does not depend on old code.
* Good, because the stored inputs let any version render its own target body.
* Good, because the workflow file stays unchanged until a confirmed migration.
* Bad, because the store must be kept in step with every setup path that writes a workflow.

### Ship Historical Templates Or Body Fingerprints With Every Release

Each release keeps copies of older generators or hashes of older bodies to recognize them.

* Good, because existing workflows without records could be recognized.
* Bad, because bodies depend on list names and options, so a fixed set of texts cannot cover them.
* Bad, because frozen generator copies would grow with every release, and the compatibility rules
  forbid old-template fingerprints without a contract that needs them.

### Add A Version Marker Or Body Hash To The Workflow Front Matter

Setup writes a key such as the generating version and a body hash into the metadata.

* Good, because the provenance travels with the file.
* Bad, because a hash cannot locate the old block inside a body with additions.
* Bad, because migration would have to change metadata, which the issue requires to stay as the
  user left it.

### Wrap The Generated Block In Marker Comments Inside The Body

Setup writes comments such as `<!-- generated body begin -->` around the generated text.

* Good, because prefix and suffix detection becomes simple.
* Bad, because the markers become part of the prompt that Codex reads.
* Bad, because users can move, copy, or delete markers, and the old text is still unknown.

### Add Provenance Fields To connected-boards.json

Each manifest row gets fields for the version, inputs, and body.

* Good, because the data lives with the other per-board data.
* Bad, because released versions read the manifest strictly and reject unknown fields, so a
  supported downgrade would fail to load the manifest.
* Bad, because explicitly selected workflows outside the manifest could not have provenance.

### Write A Provenance File Next To Every Workflow

Setup writes a hidden file such as `.WORKFLOW.md.symphony.json` beside the workflow.

* Good, because the record moves with the folder.
* Bad, because workflows can live in user repositories, where the extra file would show up in
  version control.

### Merge Old And New Bodies With A General Three-Way Merge

Migration merges the old body, the new body, and the user's body.

* Good, because more edited workflows could be migrated automatically.
* Bad, because conflict rules are harder to explain and to trust; the issue rejected this option.

## More Information

[GitHub issue #567](https://github.com/martin-francois/symphony-trello/issues/567) adds the
optional Codex review for kept prefix and suffix text on top of this mechanism. `SPEC.md`
Section 19.4.1 is the normative contract; README section "Migrate Generated Workflows" is the user
guide.
