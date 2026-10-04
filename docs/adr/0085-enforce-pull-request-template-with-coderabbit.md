---
status: accepted
date: 2026-10-04
decision-makers: [François Martin, Claude]
consulted:
  - .coderabbit.yaml
  - .github/pull_request_template.md
  - .github/workflows/commitlint.yml
  - scripts/check-pr-compatibility-metadata.ts
  - "[GitHub issue #655](https://github.com/martin-francois/symphony-trello/issues/655)"
  - "[CodeRabbit pre-merge checks](https://docs.coderabbit.ai/pr-reviews/pre-merge-checks)"
  - "[CodeRabbit request changes workflow](https://docs.coderabbit.ai/pr-reviews/request-changes-workflow)"
  - "[CodeRabbit configuration reference](https://docs.coderabbit.ai/reference/configuration)"
  - "[CodeRabbit configuration schema](https://coderabbit.ai/integrations/schema.v2.json)"
  - "[GitHub rules for rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)"
informed: [Contributors, Reviewers, Future maintainers]
---

# Enforce the Pull Request Template with CodeRabbit Pre-Merge Checks

## Context and Problem Statement

GitHub fills a new pull request body with `.github/pull_request_template.md`, but it cannot require
any part of it. An author can delete the body and the pull request still opens. The Commitlint
workflow runs `scripts/check-pr-compatibility-metadata.ts`, which reads the Compatibility Decision
and Commit History in Main sections. Nothing reads the rest of the template, including the AI
Assistance section. The authors who matter most here are those who open one pull request and do
not come back to answer a request for the missing disclosure.

CodeRabbit already reviews pull requests in this repository. Its configuration had
`commit_status: false` and `review_status: false`, which keep Release Please pull requests free of
CodeRabbit output. How should the template become a merge requirement?

## Decision Drivers

* A deleted or unfilled template must not merge unnoticed.
* The AI Assistance section must stay in the body, and a pull request marked AI-assisted must carry
  a person's confirmation that they understand the code.
* A breaking pull request must name what breaks, the migration path, and the alternative.
* The compatibility check must not fail a body that `scripts/check-pr-compatibility-metadata.ts`
  accepts.
* Release Please pull requests keep their no-check contract.
* Renovate pull requests keep their automerge.
* The pull request template itself does not change in this decision.

## Considered Options

* CodeRabbit pre-merge checks through the request changes workflow.
* CodeRabbit pre-merge checks through a commit status.
* Extend the repository's pull request metadata script.
* Keep the template advisory.

## Decision Outcome

Chosen option: CodeRabbit pre-merge checks through the request changes workflow.

`.coderabbit.yaml` sets the built-in description check to `error`. It adds two custom checks in
`error` mode. "AI assistance disclosed" fails when the AI Assistance heading or either checkbox is
missing, and when `AI-assisted PR` is ticked without `I confirm I understand what the code does`.
"Compatibility decision complete" fails when the Compatibility Decision section is missing, when
not exactly one option is ticked, and when Breaking is ticked without non-placeholder `Breaks:`,
`Migration:`, and `Alternative:` values.

CodeRabbit documents that error mode blocks a merge only together with
`request_changes_workflow: true`. With it, CodeRabbit submits a changes-requested review while a
check fails and approves once the checks pass and its threads are resolved. The ruleset on `main`
has a pull request rule, and under that rule a changes-requested review blocks the merge until the
same reviewer approves.

`commit_status` and `review_status` stay `false`. They are not the gate. The current CodeRabbit
schema describes `commit_status` as a legacy mirror of review progress that applies only when
`review_progress` is disabled, and `review_status` as the skip notice in the walkthrough comment.
Turning either on would put CodeRabbit output on Release Please pull requests, which
`ReleaseWorkflowTest.releasePleasePullRequestsDoNotCreateCiChecks` forbids, and would not make a
failing check block anything.

`renovate[bot]` joins `github-actions[bot]` in `auto_review.ignore_usernames`. Bots do not fill in
the template, so every check would fail on their pull requests and hold Renovate's automerge.
CodeRabbit already skipped Renovate pull requests by default, and the explicit entry keeps it that
way if the default changes.

The author of a pull request cannot clear a failed check without fixing the body.
`pre_merge_checks.override_requested_reviewers_only: true` limits "Ignore failed checks" and
`@coderabbitai ignore pre-merge checks` to requested reviewers. `allow_author_approval: false` stops
the author from running `@coderabbitai approve` or `@coderabbitai resolve`, which approve even with
failing checks. CodeRabbit documents that a pull request branch cannot turn author approval back
on.

The compatibility check is a subset of `scripts/check-pr-compatibility-metadata.ts`, which stays
authoritative. That script also requires the `Because ...` rationale, rejects `Unsure`, compares
the decision with the title and commits, and validates the commit history choice.

A live run on [GitHub PR #781](https://github.com/martin-francois/symphony-trello/pull/781) shaped
the instructions. CodeRabbit handed the custom checks a cut-off description of a 9,877-character
body, so the AI Assistance section at the end was missing and that check came back Inconclusive.
With the body emptied, the same check read its own instructions in the diff and passed. Both custom
checks therefore judge only the body as GitHub stores it, fail an empty body, and read the full body
from the GitHub REST API when the description they get is cut short.

The template has no box for "no AI assistance", so an unticked AI Assistance section passes. The
check cannot tell a pull request without AI help from one whose author skipped the disclosure.

### Consequences

* Good, because a deleted or unfilled template now gets a changes-requested review that stops the
  merge.
* Good, because an AI-assisted pull request cannot merge until a person ticks the confirmation.
* Good, because the checks need no new workflow, token, or script, and CodeRabbit already reviews
  every human pull request here.
* Good, because Release Please and Renovate pull requests behave as before.
* Bad, because the checks are language-model judgments. A check can return Inconclusive, which does
  not block, and the full-body fallback relies on the check agent fetching the body when it is cut
  short.
* Bad, because CodeRabbit reads `.coderabbit.yaml` from the pull request branch, so a pull request
  that edits it can weaken its own checks. Reviewers must read any change to that file.
* Bad, because the built-in description check takes no instructions. It judges the body against
  the template on its own terms and may fail a body that leaves an optional field blank.
* Bad, because the maintainer's own pull requests have no requested reviewer to override a failed
  check, so the body has to be fixed or the merge has to use the ruleset bypass.
* Bad, because a rate-limited CodeRabbit review leaves the changes-requested review in place until
  a later review completes.
* Bad, because the instructions quote template text, so a template edit must update them. A script
  test catches a quoted excerpt the template no longer contains.

### Confirmation

Run the configuration tests:

```bash
corepack pnpm run verify:scripts
./mvnw -q -Dtest=ReleaseWorkflowTest test
```

`scripts/coderabbit-config.test.ts` fails when a custom check quotes text that is not in the
template, when an error-mode check exists without `request_changes_workflow: true`, and when the
author regains the ability to ignore failed checks or approve their own pull request.
`ReleaseWorkflowTest` checks that both status settings stay off and that `github-actions[bot]` and
`renovate[bot]` stay in `ignore_usernames`. Validate `.coderabbit.yaml` against the CodeRabbit
schema after changing it, and review the compatibility check's instructions whenever
`scripts/check-pr-compatibility-metadata.ts` changes its rules, since no test compares the two.

On a pull request, an emptied body must produce a failed description check and a changes-requested
review from CodeRabbit. Comment `@coderabbitai run pre-merge checks` to rerun the checks after
editing the body.

## Pros and Cons of the Options

### CodeRabbit Pre-Merge Checks Through the Request Changes Workflow

Configure built-in and custom pre-merge checks in error mode and let CodeRabbit request changes
while one fails.

* Good, because this is the blocking path CodeRabbit documents for error mode.
* Good, because it uses the pull request rule the ruleset already has.
* Good, because the result appears in the walkthrough with an explanation and a fix for the author.
* Bad, because CodeRabbit also requests changes for actionable review comments. The ruleset
  already requires review threads to be resolved before merging, so this adds little new friction.

### CodeRabbit Pre-Merge Checks Through a Commit Status

Turn `commit_status` back on and require the CodeRabbit status in the ruleset.

* Good, because a required status is the usual GitHub merge gate.
* Bad, because the documentation does not say that a failing pre-merge check fails that status.
* Bad, because `commit_status` applies only when `review_progress` is disabled.
* Bad, because Release Please pull requests would get a CodeRabbit status, against their
  no-check contract.

### Extend the Repository's Pull Request Metadata Script

Teach `scripts/check-pr-compatibility-metadata.ts` to require every template section.

* Good, because the result is deterministic and runs in local tests.
* Bad, because checking whether free-text sections such as Summary or Validation say something
  useful needs judgment a script does not have.
* Bad, because the Commitlint workflow is not a required check, so a failure there blocks nothing
  either.

### Keep the Template Advisory

Leave `.coderabbit.yaml` without pre-merge checks.

* Good, because nothing changes.
* Bad, because an author can delete the template, including the AI disclosure, and merge.

## More Information

[GitHub issue #655](https://github.com/martin-francois/symphony-trello/issues/655) asked for this
gate and left the pull request template unchanged.
