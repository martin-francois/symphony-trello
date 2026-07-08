---
name: issue-sweep
description: >
  Run a repository-wide GitHub issue triage sweep for Symphony for Trello.
  Use when the user asks for an issue sweep, issue triage audit, issue audit,
  cleanup of issue labels, or to check open issues for stale scope,
  dependencies, already-implemented work, labels, milestones, or template
  quality.
---

# Issue Sweep

This skill is a thin dispatcher. The canonical policy lives in
`../../../docs/agents/github-issue-triage.md`; do not duplicate the label matrix
or issue editing rules here.

## Workflow

1. Read `../../../docs/agents/github-issue-triage.md` completely before fetching or
   editing issues. Treat it as the source of truth for sweep scope,
   issue-body updates, labels, dependencies, compatibility decisions, and
   already-implemented checks.
2. Confirm GitHub authentication and repository context with the active
   repository-approved GitHub tooling, such as the helper or app named by
   `.tessl/RULES.md` and installed GitHub skills. Do not use direct `gh` where
   those rules require helper or app access. If authentication is missing or
   editing is not permitted, report the blocker and do not pretend the sweep was
   completed.
3. Fetch all open issues, including title, body, labels, milestone, assignees,
   assignment timeline events, linked or referencing open pull requests, and
   relevant comments. Do not limit the sweep to examples named by the user.
4. Apply the canonical triage document in cycles. After making any change,
   fetch the open issue set again and repeat until one full pass finds no body,
   label, milestone, dependency, already-implemented, or relationship changes.
5. When creating follow-up issues during the sweep, use the repository issue
   templates and the same full triage policy before publishing the issue.
6. Prefer editing maintainer-authored issue bodies over adding comments, unless
   the canonical triage document calls for a historical comment or the issue was
   opened by another account.
7. Finish with a concise report: number of cycles, issues changed, issues
   intentionally left unchanged, labels or dependencies repaired, users assigned
   from pickup comments, stale assigned issues with no open pull request, issues
   closed as already implemented, and any blockers.

## Checks

- Run the live invariant checks needed by
  `../../../docs/agents/github-issue-triage.md` for the policy areas touched
  during the sweep.
- If the sweep changes repo files, run the relevant local documentation or code
  checks before committing.

## Stop Conditions

- `../../../docs/agents/github-issue-triage.md` cannot be read.
- GitHub authentication or permissions are insufficient to inspect or update the
  required issues.
- An issue needs a maintainer decision that is not already represented in the
  issue; mark it according to the canonical triage policy instead of guessing.
