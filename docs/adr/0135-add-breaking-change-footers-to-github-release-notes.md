---
status: accepted
date: 2026-10-30
decision-makers: [François Martin]
consulted:
  - "[GitHub issue #1136](https://github.com/martin-francois/symphony-trello/issues/1136)"
  - "[ADR 0068](0068-use-github-generated-changelog-notes.md)"
  - "[Release Please changelog types](https://github.com/googleapis/release-please/blob/main/docs/customizing.md#changelog-types)"
  - "[Release Please GitHub changelog notes source](https://github.com/googleapis/release-please/blob/v17.11.2/src/changelog-notes/github.ts)"
  - "[Release Please issue #2860](https://github.com/googleapis/release-please/issues/2860)"
  - "[Release Please issue #2761](https://github.com/googleapis/release-please/issues/2761)"
  - "[Release Please issue #2080](https://github.com/googleapis/release-please/issues/2080)"
  - "[GitHub automatically generated release notes](https://docs.github.com/en/repositories/releasing-projects-on-github/automatically-generated-release-notes)"
informed: [Future maintainers, Contributors]
---

# Add breaking-change footers to GitHub-generated release notes

## Context and Problem Statement

[ADR 0068](0068-use-github-generated-changelog-notes.md) set `changelog-type` to `github` so the
release notes credit each pull request author and list new contributors. With that type, Release
Please asks GitHub's generate-notes API for the notes and ignores the commits it parsed:
`GitHubChangelogNotes.buildNotes` takes them as `_commits` and never reads them. GitHub's notes list
pull request titles only.

`BREAKING CHANGE:` footers therefore never reach the release notes. The 2.0.0 release pull request
lists the `!` pull request titles but none of the four footers on `main` since 1.2.0 (3999abdf,
e2a3433d, 3dc25b73, 7ec1cf05). Those footers hold the migration steps for the update refusals, the
`No PR` label, and the `github` section contract.

How do the release notes carry every footer's migration steps without losing the contributor
credits?

## Decision Drivers

* The release pull request, `CHANGELOG.md`, and the GitHub release show each footer's text.
* Each merged pull request keeps its `by @author in <pull request>` credit, and first-time
  contributors keep the `New Contributors` section.
* It works for every later release without a manual step.
* Prefer maintained Release Please or GitHub behavior over repository-owned code.
* A missing footer fails a check, both while the release pull request is open and before the release
  is published.

## Considered Options

* Switch to the default changelog type.
* Switch to the default changelog type with `include-commit-authors`.
* Keep the `github` type and put breaking pull requests in a release-notes category.
* Keep the `github` type and add the footers with a release workflow script.

## Decision Outcome

Chosen option: keep the `github` type and add the footers with a release workflow script, because it
is the only option that keeps both the contributor credits and the migration steps. Release Please
has no built-in way to combine GitHub's notes with commit footers; the open feature requests are
[Release Please issue #2860](https://github.com/googleapis/release-please/issues/2860),
[Release Please issue #2741](https://github.com/googleapis/release-please/issues/2741), and
[Release Please issue #2758](https://github.com/googleapis/release-please/issues/2758). A custom
changelog type is not an option either, because the configuration file can only select `default`
or `github` ([Release Please issue #2080](https://github.com/googleapis/release-please/issues/2080)).

When the Release Please step updated its pull request, the last step of the release job runs
`scripts/release-breaking-changes.ts pull-request`. It comes after the release steps, so a failure
here never holds back a release created in the same run. The script:

1. Fetches the release branch and all tags, because Release Please may have created a release tag
   after the checkout. It reads every commit from the last `v*` release tag to the release branch
   head, which is the `main` commit Release Please built the pull request from.
2. Parses each message with `conventional-commits-parser`, the library commitlint's
   `@commitlint/parse` uses for the same footers. A `type!:` commit without a footer contributes its
   subject, as the default changelog type does.
3. Writes a `## Breaking Changes` section, one entry per footer with a link to its commit, between
   the version heading and GitHub's `What's Changed` section. It writes the pull request body and
   `CHANGELOG.md` on the release branch through the GitHub API. When the body would pass GitHub's
   65,536-character limit, it stores the notes in `release-notes.md` on the
   `<branch>--release-notes` branch and links them from the body, the same way Release Please
   handles notes that are too long.
4. Commits the changelog update with `[skip ci]`, like the Release Please commit it lands on top
   of, so a fast-forward merge of the release pull request still skips CI.
5. Reads both back and fails the workflow run when an entry is missing.

Release Please creates the GitHub release from the merged pull request body, or from that notes
file, so the release gets the same section. Before the draft release is published, the
`release` mode compares the draft's body with the footers between the previous tag and the new one
and fails the run when one is missing. The draft then stays unpublished with its assets uploaded.

The workflow's concurrency group queues runs, so one run's script never writes to a branch that
another run's Release Please step is rewriting.

### Consequences

* Good, because each release lists its migration steps next to GitHub's credited pull request list.
* Good, because the `BREAKING CHANGE:` footer stays the single source of the migration text, and
  contributors keep writing it as `CONTRIBUTING.md` already asks.
* Good, because the script and commitlint read footers with the same parser library. The versions
  are pinned separately: `package.json` for the script, `scripts/prepare-commitlint` for commitlint.
* Bad, because the repository owns code that edits a pull request and a branch Release Please
  manages. Each Release Please update rewrites both, and the script writes the section again in the
  same workflow run.
* Bad, because the script depends on Release Please details: the `## <version>` heading, the `---`
  body delimiter, and the notes file name and size limit for long notes. A Release Please upgrade
  that changes them fails the script's own check instead of dropping the footers silently.
* Bad, because a failed run leaves the release pull request without the section until the next push
  to `main`, and nothing blocks merging the release pull request then, since it skips CI. The
  release check catches the gap before publication, and a maintainer then adds the missing entries
  to the draft and publishes it by hand.
* Bad, because the release job installs Node.js and the script dependencies on every run that
  updates a release pull request.

### Confirmation

Run:

```bash
pnpm run verify:scripts
```

`scripts/release-breaking-changes.test.ts` covers footer parsing, the section placement in the pull
request body and changelog, the notes file for long notes, the tag range with a tag created after
checkout, the draft release check, the workflow step order, and the failure when a footer is missing
from the stored notes.

After a push to `main`, the release pull request body and its `CHANGELOG.md` show a
`Breaking Changes` section with one entry for each `BREAKING CHANGE:` footer since the last release.

## Pros and Cons of the Options

### Switch to the default changelog type

Set `changelog-type` to `default`. Release Please then renders its parsed commits with
`conventional-changelog-writer` and lists footers under `⚠ BREAKING CHANGES`.

* Good, because it is one configuration line and uses maintained Release Please code.
* Good, because the breaking-change section is part of Release Please's own output.
* Bad, because it drops GitHub's `What's Changed` list with its per-pull-request credits and the
  `New Contributors` section that ADR 0068 chose.
* Bad, because it lists commits by type and hides `chore`, `ci`, `test`, and other types by default.

### Switch to the default changelog type with `include-commit-authors`

Also set `include-commit-authors` so each entry ends with `(@username)`.

* Good, because it adds a credit to each entry with built-in options.
* Bad, because it credits commit authors, not pull request authors, links no pull request, and has
  no `New Contributors` section. ADR 0068 rejected it for these reasons.
* Bad, because the option has no effect in released Release Please versions
  ([#2761](https://github.com/googleapis/release-please/issues/2761)). The fix merged on 2026-10-22
  is not in a release yet; 17.11.2 is the latest tag.

### Keep the `github` type and use a release-notes category

Add `.github/release.yml` with a category for pull requests that carry the `breaking change` label.
GitHub's generated notes then list those pull requests under their own heading.

* Good, because it is maintained GitHub behavior and needs no code.
* Bad, because GitHub's notes contain pull request titles only, so the migration steps still do not
  appear.
* Bad, because it depends on a label that nothing ties to the commit footer.

### Keep the `github` type and add the footers with a release workflow script

The chosen option, described in the decision outcome.

* Good, because it keeps every part of GitHub's notes and adds the footers.
* Good, because it checks its own result and fails the release workflow run.
* Bad, because the repository owns and tests the script.

## More Information

Remove the script and switch back to configuration when Release Please can combine GitHub-generated
notes with commit footers, for example through one of the feature requests above.
