---
status: accepted
date: 2026-10-30
decision-makers: [François Martin]
consulted:
  - "[ADR 0069](0069-repository-owned-pull-request-size-labeling.md)"
  - "[GitHub issue triage: size labels](../agents/github-issue-triage.md#size-labels)"
  - "[GitHub REST API: list pull request files](https://docs.github.com/en/rest/pulls/pulls#list-pull-requests-files)"
informed: [Future maintainers, Contributors]
---

# Leave ADR lines out of pull request and issue size labels

## Context and Problem Statement

[ADR 0069](0069-repository-owned-pull-request-size-labeling.md) labels a pull request from all of
its changed lines, additions plus deletions, in every file. Issues use the same five labels for the
expected change, as
[GitHub issue triage](../agents/github-issue-triage.md#size-labels) describes.

Many pull requests here add or update an ADR next to the code. An ADR mostly records a decision
that the code review already covers, and it reads faster than code. On the last 100 merged pull
requests, files under `docs/adr/` held 11.4% of all changed lines, and 20 pull requests touched an
ADR. Leaving those lines out changes 6 of the 100 labels, for example
[GitHub PR #1163](https://github.com/martin-francois/symphony-trello/pull/1163) from `size L` to
`size M` and [GitHub PR #1149](https://github.com/martin-francois/symphony-trello/pull/1149) from
`size XL` to `size L`.

Which changed lines should a size label count?

## Decision Drivers

* A size label estimates how much a reviewer has to read.
* A pull request that changes only ADRs still needs a size that reflects its review.
* Issue and pull request labels measure the same thing.
* The labeler stays metadata-only on `pull_request_target`: no checkout of pull request code and no
  new permission.
* The rule is easy to state and to check by hand.

## Considered Options

* Count every line except lines under `docs/adr/`, unless the other files change no lines.
* Count every line, as before.
* Count every line except documentation: Markdown files and everything under `docs/`.
* Count every line except lines under `docs/adr/`, with no exception.

## Decision Outcome

Chosen option: count every line except lines under `docs/adr/`, unless the other files change no
lines. It removes most of the ADR weight from mixed pull requests and gives an ADR-only pull request
its real size.

The rule in detail:

* A changed file is an ADR when its path is under `docs/adr/`. A renamed or copied file is an ADR
  only when both its old and its new path are under `docs/adr/`. A deleted ADR is an ADR.
* When the other files change lines, the label counts the additions plus deletions of the other
  files only.
* When the other files change no lines, the label counts all lines. That covers a pull request
  that changes only ADRs, and one whose other files are binary files, pure renames or mode changes,
  which GitHub lists with 0 additions and 0 deletions. An ADR next to a new image would otherwise
  be labeled `size XS`.
* Other documentation, such as `README.md`, `CONTRIBUTING.md` and `docs/agents/`, counts as before,
  and so do tests. A pull request that changes only such documentation keeps its old label.
* The thresholds, label names and every trigger from ADR 0069 stay the same.

The labeler reads the changed files from the `pulls/{number}/files` endpoint, 100 per page, with the
`pull-requests: write` permission the job already has; it still checks out nothing from the pull
request. GitHub lists at most 3000 files. When the list holds fewer files than the pull request's
`changed_files`, the labeler cannot see every path, so it counts all lines from the pull request's
`additions` and `deletions`, as before this ADR. A pull request with that many files is almost
always `size XL` anyway, and the log then gives the ADR lines as "at least" the listed ones. When
the file list cannot be read, holds an entry without a path or with an invalid line count, or lists
one path twice (the list can shift between page reads and then miss another file), the labeler
warns and also counts all lines from the pull request totals. When the list is complete, the listed files decide the count, also if a push between the
two API reads left the pull request totals out of step.

Issues follow the same rule: the estimate leaves out expected ADR lines unless the rest of the
expected change has no lines. An issue split across several pull requests applies the rule to its
whole change.
[GitHub issue triage](../agents/github-issue-triage.md#size-labels) states it.

This decision amends the counting part of ADR 0069. ADR 0069's routing, mutation order and
reconciliation stay in force.

### Consequences

* Good, because a pull request that adds an ADR next to its code gets the label its code earns.
* Good, because an ADR-only pull request, or an ADR next to a binary file, still gets a size from
  its lines instead of `size XS`.
* Good, because issue estimates and pull request labels use one rule.
* Bad, because every label run now lists the pull request's files: one more API call per pull
  request, and up to 30 for one with 3000 files. The hourly reconciliation handles at most 100
  pull requests, about 300 to 500 requests with label changes, against the 1,000 requests an hour
  that GitHub allows the `GITHUB_TOKEN` of one repository.
* Bad, because a reviewer who reads ADRs closely gets a label that understates that part of the
  work.
* Bad, because labels set before this change follow the old rule until they are recalculated.

### Confirmation

`scripts/size-labeler.test.ts` runs the deployed module through `loadWorkflowScript` and covers:
ADR lines left out of a mixed pull request, an ADR-only pull request counted in full, an ADR next
to a binary file or a pure rename counted in full, an empty file list, a docs-only pull request
counted in full, `docs/adr-overview.md` counted as ordinary documentation, a file renamed or copied
inside, out of and into `docs/adr/`, a deleted ADR, each label boundary next to 600 ADR lines, a
list cut off below `changed_files`, a listed file count above `changed_files`, and failed or
invalid file reads labeled from the pull request totals. Run:

```bash
pnpm run verify:scripts
```

Review that `.github/workflows/size-labeler.yml` keeps `pull-requests: write` as the only grant of
the label job, with no checkout of pull request code.

## Pros and Cons of the Options

### Leave out ADR lines unless the other files change no lines

The labeler lists the changed files, counts the lines of the files outside `docs/adr/`, and counts
all lines again when those files change no lines.

* Good, because it removes the part of a mixed pull request that the code review already covers.
* Good, because an ADR-only pull request is not labeled `size XS` by mistake.
* Neutral, because the label of a pull request without ADRs does not change.
* Bad, because the labeler needs the file list instead of only the pull request totals.

### Count every line

Keep ADR 0069's count: the pull request's `additions` plus `deletions`, every file included.

* Good, because it needs only one API call and has no path rules.
* Bad, because a pull request that adds a long ADR next to small code reads as larger than its
  review is.

### Leave out all documentation

Subtract every Markdown file and everything under `docs/`, not only ADRs.

* Good, because it would count only code, tests and configuration.
* Bad, because `README.md`, `CONTRIBUTING.md` and `docs/agents/` change what contributors and
  agents do, and a reviewer reads them as closely as code.
* Bad, because a docs-only pull request would need its own exception.
* Bad, because on the last 100 merged pull requests it changes 7 labels, one more than leaving out
  only ADRs.

### Leave out ADR lines with no exception

Subtract every line under `docs/adr/` in every pull request.

* Good, because the rule has no special case.
* Bad, because a pull request that changes only ADRs counts 0 lines and is labeled `size XS`, even
  when it rewrites several decisions.

## More Information

[ADR 0069](0069-repository-owned-pull-request-size-labeling.md) owns the size-label workflow, its
triggers and its label mutation order. The counting lives in
`scripts/size-labeler-sync-size-label.cjs`.
