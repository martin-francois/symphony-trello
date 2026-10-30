---
status: accepted
date: 2026-10-30
decision-makers: [François Martin, Emilio Castejón]
consulted:
  - "[GitHub issue #1051](https://github.com/martin-francois/symphony-trello/issues/1051)"
informed: [Maintainers, Contributors]
---

# Share commitlint package pins between local checks and CI

## Context and Problem Statement

The local wrapper installs commitlint 21.0.1 while CI installs 21.2.2. Neither declaration is
discovered by the current Renovate manager. Local title and retained-commit checks must use the
same pinned CLI and conventional configuration as CI without changing either message policy.

## Decision Drivers

* Maintain each package version in one place.
* Keep the local ownership check, version marker and cache reuse.
* Keep CI's runner-temporary installation and exported tool paths.
* Let Renovate discover both packages, and fail a script test when it stops finding them.
* Do not add application dependencies or another installation mechanism.

## Considered Options

* Source one small Bash file from both existing setup scripts.
* Keep duplicated declarations and guard their equality with tests.
* Move installation into one shared executable setup helper.

## Decision Outcome

Chosen option: "Source one small Bash file from both existing setup scripts". The file
`scripts/commitlint-versions` contains both exact package specifications. The wrappers keep their
separate installation behaviour and read those same specifications. Renovate owns this file; the
dependency-governance test checks that Renovate finds both packages in it.

The two packages may pin different versions. Commitlint publishes only the packages that changed in
a release: `@commitlint/cli` 21.2.1 has no matching `@commitlint/config-conventional` release.
After such a release, Renovate's commitlint monorepo group bumps only the CLI, and a test that
required equal versions would fail that update.

There is no change to `SPEC.md`, application behaviour, public CLI commands, deployment, release
artifacts or companion repositories. This changes contributor tooling only. The title and
retained-commit configurations remain separate and unchanged.

### Consequences

* Good, because changing one shared source updates local and CI setup.
* Good, because the existing marker reinstalls the local cache after a version change.
* Bad, because each setup script now requires the shared file to be present in the checkout.

### Confirmation

`pnpm run verify:scripts` runs the tests that confirm this decision. "Renovate owns both commitlint
packages" in `scripts/dependency-governance.test.ts` fails when Renovate stops finding either pin.
`scripts/commitlint-setup.test.ts` runs both setup scripts against changed shared pins and fails
when either script stops installing them or the local cache stops reinstalling after a change.
For a manual check, run both setup paths with clean temporary directories, compare the installed
package versions, and check valid and invalid titles plus the stricter retained-commit rules.
Run the repository shell checks on the two wrappers and the shared Bash file.

## Pros and Cons of the Options

### Source one small Bash file from both existing setup scripts

Both scripts source repository-owned literal package assignments before installing packages. The
shared file performs no installation and does not alter the caller's shell options.

* Good, because it removes duplicated version declarations with a small change.
* Good, because existing local and CI installation contracts remain independent.
* Bad, because shell sourcing is an additional dependency between the scripts and the pin file.

### Keep duplicated declarations and guard their equality with tests

Each script continues to declare its own packages, with a test comparing all four declarations.

* Good, because each script stays self-contained.
* Bad, because each update still edits two copies and can drift before tests run.

### Move installation into one shared executable setup helper

A new helper would select versions and own installation for both scripts.

* Good, because it could centralise more installation logic.
* Bad, because combining persistent-cache and runner-temporary lifecycles adds unnecessary scope
  to a version-selection repair.

## More Information

The maintainer confirmed the shared-source approach in the
[discussion of GitHub issue #1051](https://github.com/martin-francois/symphony-trello/issues/1051#issuecomment-6526860263).
