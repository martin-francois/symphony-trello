---
status: accepted
date: 2026-10-30
decision-makers: [François Martin]
consulted:
  - .github/pull_request_template.md
  - .github/workflows/commitlint.yml
  - CONTRIBUTING.md
  - docs/agents/default-workflow.md
  - docs/agents/autonomy-and-escalation.md
  - docs/adr/0064-explicit-pull-request-commit-history-intent.md
informed: [Contributors, Reviewers, Future maintainers]
---

# One cohesive change per commit in main

## Context and Problem Statement

The pull request template asks the author to choose between **Combine this pull request into one
final commit** and **Keep the individual commits**
([ADR 0064](0064-explicit-pull-request-commit-history-intent.md)). The PR metadata check uses that
choice to decide where the breaking-change markers must be. Maintainers then picked the merge
method that matched the author's choice.

The agent docs also had a separate rule for outside contributors. Review fixes went into new commits
on top of the contributor's commits. Agents did not amend, squash, or fix up those commits, merged
with a rebase merge, and asked the contributor before combining.

Both rules let commits reach `main` that are not one cohesive change. A contributor can choose Keep
for five commits that are one change, or Combine for a branch that holds two changes. A separate
review-fix commit on an outside contributor's pull request reaches `main` as a commit that only
repairs the commit before it.

Who decides how a pull request lands in `main`, and where do review fixes go?

## Decision Drivers

* Each commit in `main` should be one cohesive change that can be read, reverted, and bisected on
  its own.
* Outside contributors keep the credit for their code.
* The breaking-marker validation from
  [ADR 0064](0064-explicit-pull-request-commit-history-intent.md) must check the history that
  actually reaches `main`.
* A force-push to someone else's branch must not drop commits they pushed in the meantime.

## Considered Options

* Maintainers choose the merge from the commits
* The author's choice decides, with separate review-fix commits for outside contributors
* Squash-merge every pull request

## Decision Outcome

Chosen option: "Maintainers choose the merge from the commits", because it keeps every commit in
`main` one cohesive change for every author. Review fixes are folded into the commit they fix on
every pull request.

Every commit that reaches `main` is one cohesive change. A pull request that is one cohesive change
is squash-merged, however many commits it has. A pull request with several cohesive changes is
first cleaned up to one commit per change and then merged with GitHub's rebase merge. Five commits
that hold two cohesive changes become two commits before the merge.

After a review, each fix goes into the commit that introduced the problem through
`git commit --fixup` and `git rebase --autosquash`. The rewritten commit keeps its author. A fix
that is a separate cohesive change gets its own commit. This holds for pull requests by
martinfrancois, his agents, Renovate, and outside contributors alike. The source branch of a
Renovate toolchain update stays untouched, because only Renovate commits to it
([ADR 0071](0071-automate-openrewrite-renovate-updates.md)).

The template choice records the author's intent for the branch as they submitted it. Whenever
martinfrancois or his agents push to a pull request, they re-check the choice against the new
commits and change the checkboxes to match. On a pull request someone else authored, this is the
one body edit they make without being asked. The `I confirm I understand what the code does` box
stays with its author
([ADR 0132](0132-tick-the-understanding-box-on-martinfrancois-pull-requests.md)). Editing the body
re-runs the PR metadata check, so it validates the breaking markers for the history that will reach
`main`.

A pushed branch is rewritten only on top of the pull request's current head. The agent checks that
the branch's remote-tracking ref equals the pull request's `headRefOid` and pushes with
`git push --force-with-lease --force-if-includes <remote> <branch>`. The lease then holds that head
SHA. The agent does not write the lease as `--force-with-lease=<branch>:<sha>`, because Git ignores
`--force-if-includes` when the lease names its value.

On an outside contributor's pull request, the comment about the push thanks the contributor, names
each of their commits that changed and what changed in it, and names any checkbox the agent changed.

### Consequences

* Good, because every commit in `main` is one cohesive change, whoever authored the pull request.
* Good, because outside contributors stay the authors of their commits, including the parts a
  maintainer fixed.
* Good, because the PR metadata check validates the history that will reach `main`, not a choice
  made before the review changed the commits.
* Bad, because maintainers force-push to contributors' branches. A contributor with local work on
  that branch has to rebase it onto the rewritten branch.
* Bad, because a review delta is no longer a separate commit. Reviewers read it with
  `git range-diff` or GitHub's compare view between the old and new head.
* Neutral, because no automation checks cohesion; pull-request review does.

### Confirmation

[Default workflow](../agents/default-workflow.md#committing-and-pull-requests) states the merge,
review-fix, checkbox, and force-push rules, and its
[outside contributor section](../agents/default-workflow.md#outside-contributor-pull-requests) adds
only the thanks, the comment, and the understanding box. The
[Codex review/fix loop](../agents/autonomy-and-escalation.md#codex-reviewfix-loop) folds fixes into
the owning commit with no outside-contributor exception. `AGENTS.md` and `CONTRIBUTING.md` state the
same rule in short form.

Pull-request review checks that every commit reaching `main` is one cohesive change, that the
ticked history box matches the commits, and that rewritten commits kept their authors. The
Commitlint workflow runs on the `edited` pull request event, so a checkbox change re-runs the PR
metadata check.

## Pros and Cons of the Options

### Maintainers choose the merge from the commits

Maintainers squash a pull request that is one cohesive change and clean up a pull request with
several cohesive changes to one commit per change before a rebase merge. Review fixes are folded
into the commit they fix on every pull request, and the template checkboxes are updated after every
push.

* Good, because the result in `main` follows the commits, not a guess made at submission time.
* Good, because one rule covers every author.
* Bad, because it rewrites pushed branches, including contributors' branches.

### The author's choice decides, with separate review-fix commits for outside contributors

The author's template choice picks the merge method. On an outside contributor's pull request,
review fixes are new commits on top, and the pull request is rebase-merged so those commits reach
`main`.

* Good, because contributors' commits are never rewritten.
* Good, because the review delta stays visible as its own commit.
* Bad, because commits that only repair an earlier commit reach `main`.
* Bad, because a wrong template choice decides the history in `main`.

### Squash-merge every pull request

Every pull request becomes one commit in `main`, whatever it contains.

* Good, because it needs no history cleanup and no force-push.
* Bad, because a pull request with a refactor and a behavior change, or work for two issues, lands
  as one mixed commit.

## More Information

martinfrancois stated this rule on 2026-10-30. It replaces the earlier agent-docs rule that kept
review fixes in separate commits on outside contributors' pull requests. That rule had no ADR.
[ADR 0064](0064-explicit-pull-request-commit-history-intent.md) still decides how the template
choice drives the breaking-marker validation and links here.
