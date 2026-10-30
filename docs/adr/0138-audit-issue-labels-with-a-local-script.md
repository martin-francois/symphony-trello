---
status: accepted
date: 2026-10-30
decision-makers: [François Martin]
consulted:
  - "[GitHub issue triage](../agents/github-issue-triage.md)"
  - "[GitHub issue dependencies](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/creating-issue-dependencies)"
  - "[mheap/github-action-required-labels](https://github.com/mheap/github-action-required-labels)"
informed: [Future maintainers, Contributors]
---

# Audit issue labels with a local script

## Context and Problem Statement

The Triage labels policy in [GitHub issue triage](../agents/github-issue-triage.md) is prose that
each agent applies by reading it. A recent triage sweep that read it still left ready, unassigned
issues without `help wanted`, new issues without a priority or size label, and `blocked` on issues
whose native blockers had closed, and it judged `good first issue` for only a few issues. Each of
those gaps follows from labels, assignees, and the native `blocked by` counts alone, so a program
can find them. Some need a person to decide: `blocked` can stay after its native blockers close
when the issue also waits on something outside the repository, such as an upstream release.

How should the repository check the mechanical part of the policy, so every agent on any machine
gets the same answer?

## Decision Drivers

* An agent can run the check on any machine with `gh` and Node, during a sweep or right after it
  creates or edits an issue.
* The check reads the native `blocked by` state, not only label names.
* Rules that need judgment, such as the `good first issue` 80% one-shot bar, are listed for a
  decision and never fail the run, because no program can decide them.
* The policy page stays the authority, and a policy change reaches the check in the same change.
* No new workflow permissions or tokens.

## Considered Options

* Keep the policy as prose only.
* A repository script, `scripts/audit-issue-labels.ts`, that agents run at the end of a sweep and
  after issue edits.
* The same script in a scheduled or issue-event GitHub Actions workflow.
* A premade label-check action such as `mheap/github-action-required-labels`.

## Decision Outcome

Chosen option: a repository script that agents run, because it is the only option that both reads
the native dependency summary and fits into the sweep loop, where the agent fixes a failure and
reruns the check at once. The policy page names the script, lists what it checks, and requires a
sweep to end with a run that reports no failures and a decision for every judgment item.

### Consequences

* Good, because the rules that the last sweep missed now fail a command, or are listed for a
  decision, instead of depending on a careful reading.
* Good, because the script uses only Node built-ins and `gh api graphql`, which the triage workflow
  already uses, so it runs without `pnpm install`.
* Bad, because nothing runs the audit when nobody triages. A label set by hand between sweeps stays
  wrong until the next sweep or issue edit runs it.
* Bad, because the rule list exists twice, in the policy prose and in `RULES`. The policy page says
  to change both in the same change, and pull-request review checks it.

### Confirmation

`scripts/audit-issue-labels.test.ts` covers every rule, the pagination parser, the report format,
and exit codes 0 and 1 with synthetic fixtures. It runs in the required `script-tests` CI job
through `pnpm run verify:scripts`.

## Pros and Cons of the Options

### Keep the policy as prose only

* Good, because there is nothing to maintain beside the page.
* Bad, because the last sweep shows that reading the prose does not catch these gaps.

### A repository script that agents run

* Good, because it reads `issueDependenciesSummary`, so it can tell a closed native blocker from an
  open one.
* Good, because the agent that runs it can fix a failure and rerun it in the same sweep.
* Bad, because it depends on agents following the page that tells them to run it.

### The same script in a GitHub Actions workflow

* Good, because a schedule would catch labels that drift between sweeps.
* Neutral, because no workflow here audits issue metadata yet; the labeler workflows write labels
  on pull requests. A failing scheduled run would need an owner, a notification route, and a way to
  record judgment decisions so the run does not stay red on items that need no change.
* Bad, because it adds a workflow to maintain before the local audit has shown which failures recur.
  The script can move into a workflow later without changes.

### A premade label-check action

* Good, because a maintained action needs no repository code.
* Bad, because `mheap/github-action-required-labels` checks one pull request per event against a
  label list. It cannot audit all open issues, read assignees together with labels, or read native
  issue dependencies.

## More Information

The judgment items stay out of the exit code on purpose: a small ready issue that does not meet the
80% one-shot bar would otherwise fail every run. The sweep summary records each decision instead.
