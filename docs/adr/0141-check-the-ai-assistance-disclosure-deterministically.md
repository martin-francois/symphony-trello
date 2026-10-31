---
status: accepted
date: 2026-10-30
decision-makers: [François Martin]
consulted:
  - .coderabbit.yaml
  - .github/pull_request_template.md
  - .github/workflows/commitlint.yml
  - scripts/check-pr-compatibility-metadata.ts
  - "[ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md)"
  - "[ADR 0132](0132-tick-the-understanding-box-on-martinfrancois-pull-requests.md)"
  - "[GitHub PR #1148](https://github.com/martin-francois/symphony-trello/pull/1148)"
  - "[GitHub: securely using pull_request_target](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)"
informed: [Contributors, Reviewers, Future maintainers]
---

# Check the AI assistance disclosure deterministically

[ADR 0144](0144-require-the-understanding-box-on-every-pull-request.md) changed the rule on
2026-10-31: the confirmation is required on every pull request and lives in the Review Checklist.
The script now has two evaluations, one for the AI choice and one for the box; the trigger decision
here stands.

## Context and Problem Statement

[ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md) makes the AI Assistance section
of the pull request template a merge requirement through CodeRabbit's "AI assistance disclosed"
pre-merge check. That check is a language-model judgment that CodeRabbit runs when it reviews. On
[GitHub PR #1148](https://github.com/martin-francois/symphony-trello/pull/1148) CodeRabbit was
rate-limited and posted nothing, so nothing noticed that `AI-assisted PR` was ticked and
`I confirm I understand what the code does` was not.

`scripts/check-pr-compatibility-metadata.ts` already reads the body deterministically for the
Compatibility Decision and Commit History sections, in the Commitlint workflow's
`Check compatibility metadata` step on `pull_request`. Which check should back up CodeRabbit, and
on which trigger should it run?

## Decision Drivers

* The rules of the CodeRabbit check, so a body that passes the script passes CodeRabbit too.
* A result that does not depend on a third-party service being available.
* No new privileged context. The GitHub Actions security page added in
  [GitHub PR #1170](https://github.com/martin-francois/symphony-trello/pull/1170) forbids running
  pull request code where a write token or secret is reachable.
* The existing skips stay: bots and Release Please pull requests do not fill in the template.
* The error text says that a person has to tick the confirmation, as the CodeRabbit check does.

## Considered Options

* The rules in the metadata script on `pull_request`.
* The same check on `pull_request_target`.
* Leave CodeRabbit as the only check.

## Decision Outcome

Chosen option: "The rules in the metadata script on `pull_request`".

`evaluateAiAssistanceDisclosure` finds the `## AI Assistance (if used)` section in the visible
Markdown, outside HTML comments and fenced code, and reads the two choice lines the template
provides. It fails when the section is missing or appears twice, when either line is missing,
reworded or duplicated, and when neither or both choices are ticked.
`evaluateUnderstandingConfirmation` does the same for the `## Review Checklist` section and its
`I confirm I understand what the code does` line, and fails when the box is not ticked, on every
pull request ([ADR 0144](0144-require-the-understanding-box-on-every-pull-request.md); as first
written, the box sat in the AI section and one evaluation required it only with `AI-assisted
PR`). The CodeRabbit check in `.coderabbit.yaml` has the same failure rules and is a subset of the
script, as the Compatibility check already is: the script also rejects a duplicated section or
line, a reworded line, and a checkbox line whose spacing differs from the template, the way it
treats the Compatibility Decision and Commit History sections. A ticked line
in a blockquote, a nested list, a comment or a fence does not count in either check. A body
written from the template gets the same answer from both; a body the script accepts passes
CodeRabbit.

The step keeps its skips. The job does not run on `release-please--branches--*`, and the step
runs only when the actor is not a bot, so Renovate and Release Please pull requests are untouched.

`pull_request` is the right trigger. The step runs the pull request's own copy of the script with
`contents: read` and `pull-requests: read`, no secret, and a cache scoped to the pull request. A
pull request that edits the script to pass itself shows that edit in its diff, which review
sees. The box is an attestation by the author, not a security boundary, so a check the author
could weaken in their own pull request loses nothing. `pull_request_target` would run the base
branch's script against a fork without approval, but it would also give that run the base
repository's token, which the GitHub Actions security page reserves for metadata-only jobs. With fork-run
approval at `all_external_contributors`, the step runs on an outside pull request once a
maintainer approves the run, and CodeRabbit covers the time before that.

The Commitlint job stays out of the required checks; martinfrancois decided this on 2026-10-30.
The job skips `release-please--branches--*`, and a required check that reports no status blocks the
merge, so requiring it would block every Release Please pull request. A failure therefore blocks
nothing by itself; it shows red on the pull request, and pull-request review stops the merge.

### Consequences

* Good, because an unticked confirmation fails a check even when CodeRabbit posts nothing.
* Good, because the check runs in local tests and against a body on the command line, with
  `PR_TITLE`, `PR_BODY`, `PR_BASE_SHA` and `PR_HEAD_SHA` set.
* Good, because no new workflow, token or trigger is needed.
* Bad, because the rules exist twice, in `.coderabbit.yaml` and in the script. A script test
  checks that both quote the same template lines; the rule text itself is compared by review,
  and `.coderabbit.yaml` says beside the check that it stays a subset of the script.
* Bad, because on a fork pull request the check runs only after a maintainer approves the run.
* Bad, because the Commitlint job is not required, so a red check still merges unless review
  catches it.

### Confirmation

Run the script tests:

```bash
pnpm run verify:scripts
```

The AI Assistance tests in `scripts/check-pr-compatibility-metadata.test.ts` must pass, and removing
the section rule, the two-lines rule or the one-choice rule from `evaluateAiAssistanceDisclosure`,
or any rule of `evaluateUnderstandingConfirmation`, must fail at least one of them.
`.github/workflows/commitlint.yml` must keep the `Check compatibility metadata` step on
`pull_request` with read-only permissions, `persist-credentials: false` and the bot skip.

## Pros and Cons of the Options

### The rules in the metadata script on `pull_request`

The existing step gains one more evaluation over the body it already reads.

* Good, because the check is deterministic and tested.
* Good, because it needs no new privilege.
* Bad, because a fork pull request waits for run approval before the check runs.

### The same check on `pull_request_target`

A metadata-only job on the base branch reads the body from the event payload and fails without
approval.

* Good, because outside pull requests get the result at once.
* Bad, because the script step runs code, and the security page allows only metadata-only work
  on that trigger. A separate github-script job could do it, but that is a new workflow, not an
  extension of this check.

### Leave CodeRabbit as the only check

Keep `.coderabbit.yaml` as the one place that reads the AI Assistance section.

* Good, because nothing changes.
* Bad, because a rate-limited or inconclusive CodeRabbit run leaves the box unchecked, as on
  [GitHub PR #1148](https://github.com/martin-francois/symphony-trello/pull/1148).

## More Information

[ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md) keeps the CodeRabbit gate and
links here. [ADR 0132](0132-tick-the-understanding-box-on-martinfrancois-pull-requests.md) says
who ticks the box; this check does not care who did.
