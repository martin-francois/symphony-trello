---
status: accepted
date: 2026-10-31
decision-makers: [François Martin]
consulted:
  - .github/pull_request_template.md
  - .github/workflows/pull-request-author-handoff.yml
  - scripts/pull-request-author-handoff.cjs
  - "[ADR 0075](0075-automate-pull-request-author-handoffs.md)"
  - "[ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md)"
  - "[ADR 0132](0132-tick-the-understanding-box-on-martinfrancois-pull-requests.md)"
  - "[ADR 0141](0141-check-the-ai-assistance-disclosure-deterministically.md)"
  - "[ADR 0143](0143-keep-workflow-step-code-in-repository-files.md)"
  - "[ADR 0144](0144-require-the-understanding-box-on-every-pull-request.md)"
  - "[GitHub Actions security](../agents/github-actions-security.md)"
  - "[GitHub Actions workflows](../agents/github-actions.md)"
  - "[GitHub: securely using pull_request_target](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target)"
  - "[GitHub: triggering a workflow from a workflow](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow#triggering-a-workflow-from-a-workflow)"
  - "[GitHub: repository roles](https://docs.github.com/en/organizations/managing-user-access-to-your-organizations-repositories/managing-repository-roles/repository-roles-for-an-organization)"
  - "[apache/arrow issue #54696](https://github.com/apache/arrow/issues/54696)"
  - "[martinfrancois/reopen-event-test](https://github.com/martinfrancois/reopen-event-test)"
  - "[actions/stale](https://github.com/actions/stale/blob/main/README.md)"
  - "[TanStack postmortem](https://tanstack.com/blog/npm-supply-chain-compromise-postmortem)"
informed: [Contributors, Reviewers, Future maintainers]
---

# Flag and close pull requests with an incomplete AI disclosure

## Context and Problem Statement

The pull request template asks every author to tick one AI assistance choice and
`I confirm I understand what the code does`
([ADR 0144](0144-require-the-understanding-box-on-every-pull-request.md)). Two checks read those
lines: the CodeRabbit check from [ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md),
which posts nothing when CodeRabbit is rate-limited, and the metadata script from
[ADR 0141](0141-check-the-ai-assistance-disclosure-deterministically.md), which runs on
`pull_request`. Fork-run approval is set to all external contributors, so on an outside pull
request the script runs only after a maintainer approves the run. An outside author who skips the
box hears nothing until a person looks, and the pull request sits in the queue.

How can an outside pull request with an incomplete description get an immediate, safe answer,
how long should it stay open without one, and what happens once the author fixes it?

## Decision Drivers

* The answer arrives without a maintainer approving a run, so it needs a trigger that runs on
  fork pull requests at once.
* The run checks out nothing a pull request controls, uses no cache and no secret beyond the
  automatic token, as [GitHub Actions security](../agents/github-actions-security.md) requires
  for a privileged trigger. The
  [TanStack compromise](https://tanstack.com/blog/npm-supply-chain-compromise-postmortem) started
  with a `pull_request_target` workflow that ran fork code.
* The step's code lives in a repository file loaded from the commit the workflow came from
  ([ADR 0143](0143-keep-workflow-step-code-in-repository-files.md)).
* The same rules as the two other checks, so the three never disagree on a body.
* One comment per pull request, edited in place, short enough to read at a glance: what to fix,
  that nobody reviews it until then, when it closes, and what reopens it.
* Bots and martinfrancois are left alone: their bodies are generated or his agent ticks the box
  ([ADR 0132](0132-tick-the-understanding-box-on-martinfrancois-pull-requests.md)).
* The close happens at the first daily run 14 days after the flag, counted from the flag, not
  from the last activity, and the comment names that run's date. martinfrancois chose 14 days.
* A fork author cannot reopen a pull request that `github-actions[bot]` closed, so the automation
  reopens it once the description is fixed. A pull request a person closed stays closed.
* A maintainer can stop the automation on one pull request without editing the workflow.
* The handoff from [ADR 0075](0075-automate-pull-request-author-handoffs.md) must not move a
  still-incomplete pull request to `needs-maintainer-review`.
* The deployed module runs in a local test through the same `require` the workflow uses.

## Considered Options

* A sibling metadata-only workflow with its own module.
* The same job inside `pull-request-author-handoff.yml`.
* `actions/stale` for the close.
* Tell the author to reopen the pull request after the fix.
* Leave outside pull requests to the `pull_request` check and review.

## Decision Outcome

Chosen option: "A sibling metadata-only workflow with its own module".

`.github/workflows/pull-request-disclosure.yml` runs one job on `pull_request_target` for
`opened`, `edited`, `reopened`, `synchronize` and `ready_for_review`, on a daily schedule at
13:17 UTC, and on dispatch for a manual reconciliation. The job has `issues: write` and
`pull-requests: write`, no cache, a sparse checkout of `scripts/pull-request-disclosure.cjs` at
`github.sha` with no token, and one SHA-pinned `actions/github-script` step whose block is the
`require` call. `issues: write` is what the handoff workflow uses for labels and comments on pull
requests; `pull-requests: write` is for the close and the reopen. The job-level condition skips
bot authors and martinfrancois, and the module repeats those skips for the scheduled run. Every
other author is handled, collaborators included, because the box is required on every pull
request ([ADR 0144](0144-require-the-understanding-box-on-every-pull-request.md)). The condition
also compares `base.repo.full_name` with `github.repository`, as `openrewrite-renovate.yml` does;
on `pull_request_target` that comparison is always true, so it is not a guard. Branch names are
not a guard either, because a fork can reuse any of them; Release Please pull requests are
skipped as `github-actions[bot]`. Event runs use one
concurrency group per pull request and the daily sweep its own, and the sweep re-reads each pull
request through the API right before acting.

On an event, the module reads the pull request through the API rather than from the payload: the
text is the same author text either way, parsed as data and never passed to a shell, and the API
copy is current when a queued run starts after a later edit. It applies the rules of the metadata
script: the AI Assistance section once with exactly one of its two choices, the Review Checklist
once with the box line once and ticked. It finds its own comments by the marker
`<!-- symphony-trello:missing-confirmation ... -->` from `github-actions[bot]`.

When the description is incomplete and the pull request is open, the module adds
`missing-confirmation` and, unless present, `waiting-for-author`, removes
`needs-maintainer-review`, and posts or edits the one comment. The marker records the flag time,
taken from the pull request's `updated_at` on the first flag, whether the automation added
`waiting-for-author`, whether it removed `needs-maintainer-review`, and the problem the flag
started with; later edits keep all four. The comment thanks the author, names the current fix in
one sentence (for the box: "Please tick `I confirm I understand what the code does` in the
description."), says the pull request has `waiting-for-author` and won't be reviewed until then,
and in its own paragraph gives the close date and says that fixing the description reopens it.
The close date is the date of the first daily run at or after flag time plus 14 days, in UTC,
date only: "If it's still unticked on 2026-11-15 UTC, it closes automatically." The module keeps
the run time as one constant, and the script test reads the workflow's cron and fails when the
two differ. When the description becomes complete, the module removes `missing-confirmation`,
removes `waiting-for-author` only when its marker says it added it, restores
`needs-maintainer-review` when it had removed it, and rewrites the comment as resolved. When a
maintainer's own `waiting-for-author` stays, the resolved comment adds one sentence asking the
author to comment `/ready` once that feedback is addressed, because the handoff dropped author
signals while the pull request was flagged. An author's copy of the marker does not count,
because only comments by `github-actions[bot]` are read.

A maintainer opts a pull request out by removing `missing-confirmation` by hand. When the flag
comment is unresolved, the label is absent, and the pull request's latest `unlabeled` event for it
in the issue events API is by anyone but `github-actions[bot]`, the module adds no label back and
does not close. A removal by the automation itself, such as a resolve whose comment edit failed,
does not count. The opt-out ends when the description becomes complete, which resolves the
comment, so a later incomplete edit starts a fresh flag with a new date. Adding the label back by
hand resumes the original close date.

The scheduled run lists open pull requests with `missing-confirmation`. When the close time,
the first daily run at or after the marker's flag time plus 14 days, has come and the description
is still incomplete, it posts a closing comment and closes the pull request. Comparing against
that run time rather than flag time plus 14 days means a late run never closes before the date
the flag comment gave. When the problem at close time is the one the flag started with, the
closing comment says it lasted 14 days, for the box: "Closing:
`I confirm I understand what the code does` has been unticked for 14 days. Ticking it reopens this
pull request automatically." When the problem changed, it says "Closing: the description has been
incomplete for 14 days." followed by the current problem's fix and what reopens it. Either way it
asks the author not to force-push or rebase the branch until it is reopened. It carries its own
marker with the flag time, so a close that failed after the comment is retried without a second
comment, and a later flag gets a comment of its own. A pull request fixed between events is
resolved instead. A flagged pull request whose comment was deleted is flagged again with a fresh
date rather than closed. Issues are never read.

A fork author cannot reopen a pull request that the workflow closed. GitHub's
[repository roles](https://docs.github.com/en/organizations/managing-user-access-to-your-organizations-repositories/managing-repository-roles/repository-roles-for-an-organization)
grant "reopen issues they closed themselves" without a role and reopening all pull requests to
triage and above, and [apache/arrow issue #54696](https://github.com/apache/arrow/issues/54696)
shows the same setup, a `pull_request_target` workflow closing a fork pull request with the
automatic token, where the author's `gh pr reopen` failed and a maintainer's succeeded; across 35
such closures there, no author without a role reopened one. So the module reopens the pull request
itself. On an `edited` event for a closed pull request whose description is complete, it sets the
state back to open and resolves the comment only when both hold: a closed marker carries the flag
time of the current flag comment, and the pull request's most recent `closed` event in the issue
events API, read across all pages, is by `github-actions[bot]`. So a pull request a person closed
is never reopened: not one a maintainer closed after the automation reopened it, not one a person
closed again after the automation's close, and not one closed during a later flag cycle on an
earlier cycle's marker. GitHub refuses the reopen with 422 when the head branch was force-pushed
or rebased while closed, because the new head must descend from the head at close, or when the
fork is gone. The module then posts one comment saying the branch changed or was deleted and
asking for a new pull request, removes `missing-confirmation` and the `waiting-for-author` its
marker says it added, and leaves a reopen-failed marker. Event and scheduled runs skip a pull
request with that marker, which ends the automation's part in it. A throwaway repository,
[martinfrancois/reopen-event-test](https://github.com/martinfrancois/reopen-event-test), showed on
2026-10-31 that `pull_request_target` delivers `edited` for a closed pull request and that the
automatic token reopens a pull request `github-actions[bot]` closed (runs 41145024679 and
41145091310, same-repository branch). As a fallback for a missed event, the daily sweep also lists
closed pull requests, newest update first, and stops paging at the first update older than 90
days, so it never fetches the pages past that. It skips merged ones and ones without
`missing-confirmation`, re-reads each remaining one and reopens it under the same conditions when
the description is complete. A pull request a maintainer reopens by hand while it is still
incomplete is closed again by the next daily run once its close time has passed; the automation
reopens only after a fix.

Labels added with the workflow token do not start other workflows, so adding
`waiting-for-author` here does not post the handoff reminder. The handoff module gains one check:
while `missing-confirmation` is present, an author push, ready-for-review, review request or
`/ready` does not move the pull request to `needs-maintainer-review`. The disclosure workflow
owns the waiting state until the description is complete. `scripts/pull-request-disclosure.test.ts`
imports the module through `loadWorkflowScript`, and `scripts/pull-request-author-handoff.test.ts`
gains the new case.

The workflow creates the `missing-confirmation` label on first use when the repository does
not have it. [GitHub issue triage](../agents/github-issue-triage.md) lists it as a pull-request
label the workflow manages and describes the opt-out.

### Consequences

* Good, because an outside author learns within a minute what to fix, with no maintainer action.
* Good, because the run never touches pull request code, and holds nothing a fork could use.
* Good, because the comment is one per pull request and is edited in place, so repeated edits
  or pushes do not spam the thread.
* Good, because the close date is fixed at the flag and does not move when the author pushes,
  and the pull request closes on that date, not before.
* Good, because a fixed pull request comes back on its own, which the author could not do.
* Good, because a person's close always stands: the reopen checks who closed the pull request
  last and that the close belongs to the current flag.
* Good, because a maintainer can opt one pull request out by removing a label.
* Good, because the closing comment says a problem lasted 14 days only when the flag started
  with that problem.
* Bad, because the body rules now exist in three places: the metadata script, the CodeRabbit
  check, and this module, which carries a third copy of the body parser. The script tests pin
  the template lines; rule drift is a review item, as
  [ADR 0085](0085-enforce-pull-request-template-with-coderabbit.md) already accepts for the
  CodeRabbit copy.
* Bad, because a maintainer who applies `waiting-for-author` by hand after the automation added
  it will see it removed when the description is fixed; the marker records only the first state.
* Bad, because an author signal sent while the pull request is flagged is dropped, not kept for
  later; the resolved comment asks the author to comment `/ready` when a maintainer's
  `waiting-for-author` remains.
* Bad, because the opt-out lives in the issue events, not in a comment: a maintainer who adds the
  label back resumes the original close date, which may already have passed.
* Bad, because the reopen and the opt-out read the issue events, one more paginated API read on
  those paths.
* Bad, because a force-push while closed makes the reopen impossible, so the author has to open a
  new pull request; the closing comment warns against it.
* Bad, because a reopen whose `edited` event was missed waits for the daily sweep, up to a day.
* Bad, because the scheduled close runs once a day, so a pull request can wait up to a day past
  the 14 days; GitHub can also start a scheduled run late, which delays the close past 13:17 UTC
  on the date in the comment.
* Bad, because the run time lives in the cron and in the module; the script test fails when they
  differ.

### Confirmation

Run the script tests:

```bash
corepack pnpm run verify:scripts
```

`scripts/pull-request-disclosure.test.ts` must pass, and removing the confirmation rule, the
flag-time reuse, the flagged-problem reuse, the `added-waiting` check, the `restore-review` check,
the re-read before the close, the per-flag closing comment, the flagged-problem check in the
closing comment, the current-flag closed-marker condition or the latest-close check on the reopen,
the opt-out or its check of who removed the label, the 422 handling with its label removal, the
reopen-failed skip, the paging stop at the 90-day cutoff, or the close at the first daily run
after 14 days from the module must fail at least one of them. The test also reads the workflow's
cron and fails when the module's run time differs.
`scripts/pull-request-author-handoff.test.ts` must fail when the handoff stops checking
`missing-confirmation`. `scripts/github-actions-security.test.ts` and
`scripts/github-actions-inline-code.test.ts` cover the pinning, permissions, checkout shape and
no-cache rules. On a scratch fork pull request with the box empty, the comment and the two labels
must appear without run approval, ticking the box must resolve them, a pull request this
workflow closed must reopen after the fix, and one a person closed must stay closed after it.

## Pros and Cons of the Options

### A sibling metadata-only workflow with its own module

A new workflow file with one job that loads `scripts/pull-request-disclosure.cjs` and handles the
event path, the scheduled close and the reopen.

* Good, because the handoff workflow keeps its tight event condition and its own module.
* Good, because the flag, the close and the reopen share one parser and one marker format.
* Bad, because the repository gains a third copy of the body parser.

### The same job inside `pull-request-author-handoff.yml`

Add the event types and a second step to the existing handoff workflow.

* Good, because the two label automations live in one file.
* Bad, because the handoff's job condition admits only waiting-label events and author signals,
  so the condition and the per-pull-request `queue: max` concurrency would have to change.

### `actions/stale` for the close

Run `actions/stale` daily with `only-pr-labels: missing-confirmation` and
`days-before-pr-close: 14`.

* Good, because the action is maintained and pinned like the others.
* Bad, because its clock is the pull request's `updated_at`. The README says the close counts
  from the last update, and that the stale label counts only because adding it bumps
  `updated_at`. An author push or a comment edit after the flag would move the close date, and
  `remove-stale-when-updated` would have to be off to keep the label at all.
* Bad, because it cannot re-check the body before closing, and it cannot reopen.

### Tell the author to reopen the pull request after the fix

Close with a comment that says to reopen once the box is ticked.

* Good, because the module needs no reopen path.
* Bad, because a fork author cannot reopen a pull request the workflow closed, as the evidence
  above shows, so the comment would promise something the author cannot do.

### Leave outside pull requests to the `pull_request` check and review

Keep the script check from [ADR 0141](0141-check-the-ai-assistance-disclosure-deterministically.md)
as the only deterministic one.

* Good, because nothing new runs on a privileged trigger.
* Bad, because the author hears nothing until a maintainer approves the run, and the pull
  request stays open without a close date.

## More Information

[ADR 0075](0075-automate-pull-request-author-handoffs.md) links here for the one rule it gained.
`CONTRIBUTING.md` tells contributors about the comment, the 14-day close, the reopen and the
force-push warning.
