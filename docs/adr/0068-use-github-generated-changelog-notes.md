---
status: accepted
date: 2026-07-18
decision-makers: [François Martin, Codex]
consulted:
  - "[Release Please manifest configuration](https://github.com/googleapis/release-please/blob/main/docs/manifest-releaser.md)"
  - "[GitHub automatically generated release notes](https://docs.github.com/en/repositories/releasing-projects-on-github/automatically-generated-release-notes)"
informed: [Future maintainers, Contributors]
---

# Use GitHub-Generated Changelog Notes

## Context and Problem Statement

Release Please generated changelog entries from Conventional Commits and linked each entry to its
commit. The entries did not name or thank the people who contributed them, and the changelog did not
identify first-time contributors.

How should release automation recognize contributors without adding custom changelog maintenance
code?

## Decision Drivers

* Credit the author of each merged pull request in the generated changelog.
* Identify first-time contributors separately.
* Keep contributor recognition fully automated.
* Use maintained Release Please and GitHub behavior instead of repository-owned post-processing.
* Keep the release workflow small.

## Considered Options

* Use GitHub-generated changelog notes through Release Please.
* Keep the default Release Please changelog and enable commit-author attribution.
* Post-process the generated changelog with a repository-owned workflow script.

## Decision Outcome

Chosen option: use GitHub-generated changelog notes through Release Please, because GitHub credits
each merged pull request author and adds a separate `New Contributors` section for first-time
contributors.

Set `changelog-type` to `github` in `release-please-config.json`. Release Please then calls GitHub's
generated release notes API for changelog content while it continues to own version selection,
release pull requests, tags, and releases.

### Consequences

* Good, because each merged pull request names its contributor.
* Good, because first-time contributor detection uses GitHub pull request history.
* Good, because the repository does not own contributor collection, sorting, or Markdown generation
  code.
* Good, because contributor recognition stays synchronized when Release Please refreshes a release
  pull request.
* Bad, because new changelog sections use GitHub's generated release-note format instead of the
  previous Conventional Commit grouping.
* Bad, because GitHub controls the exact wording and ordering of contributor acknowledgements.
* Bad, because changes that do not belong to a merged pull request have less contributor context.

### Confirmation

Run:

```bash
./mvnw -q -Dtest=ReleaseWorkflowTest test
```

Review the next generated release pull request and confirm that its new changelog section credits
merged pull request authors and includes `New Contributors` when the release contains a first-time
contributor.

## Pros and Cons of the Options

### Use GitHub-Generated Changelog Notes Through Release Please

Configure Release Please's `github` changelog type so GitHub generates the release-note body from
merged pull requests in the release range.

* Good, because GitHub includes pull request authors in change entries.
* Good, because GitHub identifies first-time contributors.
* Good, because this requires one Release Please configuration field.
* Bad, because it changes the section format of future changelog entries.
* Bad, because the repository cannot require an alphabetical comma-separated contributor footer.

### Keep the Default Changelog and Enable Commit-Author Attribution

Keep Conventional Commit sections and set `include-commit-authors` so Release Please appends an
author name to each generated entry.

* Good, because future changelog entries keep the current Features, Bug Fixes, and Documentation
  sections.
* Good, because every commit with GitHub author metadata receives inline attribution.
* Bad, because this does not identify first-time contributors.
* Bad, because commit attribution is less accurate than pull request attribution when commits are
  combined or co-authored.

### Post-Process the Generated Changelog

Add a workflow script that queries release pull requests, determines all and first-time
contributors, rewrites entry text, and appends repository-defined contributor sections.

* Good, because the repository could enforce exact thanks wording, alphabetical order, and
  comma-separated formatting.
* Good, because the previous changelog sections could remain unchanged.
* Bad, because the repository would own API pagination, contributor identity, first-contribution
  detection, sorting, idempotent Markdown updates, commits to the generated release branch, and
  failure recovery.
* Bad, because custom commits could conflict with later Release Please refreshes.

## More Information

GitHub-generated release notes include merged pull requests, release contributors, first-time
contributors when present, and a full changelog comparison link. Existing changelog entries remain
unchanged; the new format applies only to releases generated after this configuration reaches the
default branch.
