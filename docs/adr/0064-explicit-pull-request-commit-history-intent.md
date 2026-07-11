---
status: accepted
date: 2026-07-11
decision-makers: [François Martin, Codex]
consulted:
  - .github/pull_request_template.md
  - .github/workflows/commitlint.yml
  - CONTRIBUTING.md
  - docs/agents/default-workflow.md
  - "[GitHub PR #552](https://github.com/martin-francois/symphony-trello/pull/552)"
  - "[GitHub repository merge settings](https://github.com/martin-francois/symphony-trello/settings)"
informed: [Contributors, Reviewers, Future maintainers]
---

# Explicit Pull Request Commit History Intent

## Context and Problem Statement

This repository supports two intended results for a pull request. A pull request can become one final
commit in `main`, or its individual focused commits can remain in `main`. GitHub merge commits are not
part of the intended workflow.

The repository settings were inspected through the GitHub API on 2026-07-11. Squash merge and rebase
merge were enabled. Merge commits were disabled.

Breaking changes require `!` in the subject and a non-placeholder `BREAKING CHANGE:` footer in the
message that reaches `main`. The point at which that message exists depends on the intended history.
When the pull request becomes one final commit, the final body does not exist during ordinary pull
request CI. When individual commits remain, the commit that owns the breaking behavior already
exists and must contain both markers.

Commit count cannot identify the intended result. Several branch commits can be temporary review
steps, and a pull request with one current commit can later gain independently meaningful commits.
Changed files and commit subjects also cannot prove the maintainer's intended final history.

Terms such as squash, rebase, and fast-forward are not clear enough for every contributor. The pull
request template is the authoritative contributor-facing contract, so it needs a plain-language
choice that CI can validate without guessing.

How should contributors declare the intended commit history, and how should CI use that declaration
when validating breaking markers?

## Decision Drivers

* Enforce breaking-change markers correctly.
* Keep maintainer effort low.
* Give contributors clear instructions.
* Support independently meaningful refactoring and improvement commits.
* Avoid guessing from commit count, subjects, or changed files.
* Avoid predicting a future combined commit body.
* Use deterministic blocking CI instead of warnings that can be overlooked.
* Use plain-language terminology before Git terminology.

## Considered Options

* Add an explicit plain-language commit-history selection to the pull request template.
* Leave the template unchanged and emit a non-blocking manual-review warning.
* Infer the intended result from commit count, commit subjects, or changed files.
* Always require a branch commit with both breaking markers.
* Always defer breaking-footer validation to final manual merge review.

## Decision Outcome

Chosen option: "Add an explicit plain-language commit-history selection to the pull request
template", because it records the required contributor decision and lets CI enforce the correct
mode without guessing.

The template choices are:

* **Combine this pull request into one final commit.** This corresponds operationally to producing
  one final commit, normally through GitHub's squash merge.
* **Keep the individual commits.** This corresponds operationally to preserving the focused branch
  commits, normally through GitHub's rebase merge or an equivalent history-preserving fast-forward
  result.

The checkbox labels lead with these plain-language results. The suffixes `(squash)` and `(rebase)`
are short hints for experienced contributors. Contributors do not need to understand those terms to
choose the intended result.

The declared result controls only commit-message consistency. It does not change the Compatibility
Decision or the `breaking change` label.

For **Combine this pull request into one final commit**, a Breaking pull request needs a Breaking
decision, complete template fields, and `!` in the PR title. CI does not require a current branch
commit with breaking markers. If a branch commit contains either marker, that commit must still
contain both markers and align with the PR decision and title.

For **Keep the individual commits**, the same PR metadata is required and at least one retained
commit must contain both markers. The marker-bearing commit owns the breaking behavior. Every branch
commit containing either marker must contain both.

Final merge review remains responsible for verifying that the actual merge method matches the
declared result and that the message reaching `main` contains both breaking markers.

### Consequences

* Good, because breaking-marker enforcement matches the intended permanent history.
* Good, because focused refactoring and related improvements can remain as independently meaningful
  commits.
* Good, because CI no longer guesses from commit count or predicts a future combined body.
* Good, because contributor-facing choices use plain language.
* Bad, because every non-exempt pull request has one additional required template choice.
* Bad, because older open pull requests may need metadata updates when substantive work resumes.
* Neutral, because existing open pull requests are not migrated immediately.
* Neutral, because maintainers remain responsible for choosing a merge method that matches the
  declaration.
* Neutral, because CI cannot prove that every retained commit is semantically independent; a
  mismatched declaration remains a review concern.

### Confirmation

This decision remains implemented when:

* tests load the real checked-in template and accept both current choices;
* missing, multiple, invented, fenced, commented, quoted, nested, duplicate-section, LF, and CRLF
  forms have regression coverage;
* mode-specific tests prove Combine can defer the footer and Keep requires a complete retained
  breaking commit;
* any branch commit containing either marker must contain both markers in both modes;
* labeler tests prove the commit-history choice does not affect compatibility labeling;
* contributor and agent documentation describes the same responsibility split;
* hosted Commitlint passes the PR title, metadata, commit-range, and commitlint steps;
* repository settings still enable squash and rebase merges and disable merge commits; and
* final review verifies the selected result and actual merge method agree.

## Pros and Cons of the Options

### Add An Explicit Plain-Language Commit-History Selection

Add two required template choices that describe the intended result in `main`. CI parses the visible
selection and applies the matching breaking-marker rule.

* Good, because contributors state intent directly.
* Good, because CI is deterministic.
* Good, because it supports both one-commit and preserved-commit histories.
* Good, because it does not require a future combined body to exist during PR CI.
* Bad, because the template becomes slightly longer.

### Leave The Template Unchanged And Emit A Warning

Keep commit-history intent outside structured metadata and show a warning for maintainers to
interpret before merge.

* Good, because contributors have no new checkbox.
* Bad, because maintainers must interpret the same warning repeatedly.
* Bad, because warnings can be overlooked.
* Bad, because the gate is not deterministic.
* Bad, because the required decision is simple enough to record directly.

### Infer The Intended Result

Use commit count, commit subjects, or changed files to guess whether branch commits should remain in
`main`.

* Good, because no template field is added.
* Bad, because one or many commits do not prove intent.
* Bad, because temporary review commits and meaningful focused commits can have the same shape.
* Bad, because false guesses can block a valid pull request or weaken breaking enforcement.

### Always Require A Branch Commit With Both Breaking Markers

Require every Breaking pull request to contain a complete marker-bearing branch commit before CI can
pass, even when the branch commits will be combined.

* Good, because CI always sees a complete breaking message.
* Bad, because it treats temporary review commits as permanent history.
* Bad, because it requires a message that may be replaced by the final combined commit.
* Bad, because it adds avoidable commit-message churn to Combine mode.

### Always Defer Footer Validation To Final Merge Review

Do not require complete breaking markers in branch commits. Ask maintainers to check every final
message manually.

* Good, because it works when a future combined body does not yet exist.
* Bad, because Keep mode can merge incomplete retained commits.
* Bad, because CI cannot catch contradictions that are already visible in branch history.
* Bad, because it increases recurring manual review work.

## More Information

The implementation is part of
[GitHub PR #552](https://github.com/martin-francois/symphony-trello/pull/552).

Older open pull requests are not migrated in advance. They must adopt the current template metadata
when substantive work next resumes. This avoids broad metadata churn while keeping the current
contract authoritative for active work.
